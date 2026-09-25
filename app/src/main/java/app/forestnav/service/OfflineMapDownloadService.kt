package app.forestnav.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.forestnav.MainActivity
import app.forestnav.R
import app.forestnav.map.MapLayer
import app.forestnav.map.OfflineMapManager
import com.arcgismaps.ArcGISEnvironment
import com.arcgismaps.geometry.Envelope
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.tasks.tilecache.ExportTileCacheJob
import com.arcgismaps.tasks.tilecache.ExportTileCacheTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

object OfflineMapDownloadState {
    private val lock = Any()
    private val _progress =
        MutableStateFlow<Map<Long, OfflineMapManager.DownloadProgress>>(emptyMap())
    val progress = _progress.asStateFlow()

    internal fun publish(value: OfflineMapManager.DownloadProgress) {
        val id = value.regionId ?: return
        synchronized(lock) { _progress.value = _progress.value + (id to value) }
    }

    internal fun remove(regionId: Long) {
        synchronized(lock) { _progress.value = _progress.value - regionId }
    }
}

class OfflineMapDownloadService : Service() {
    private lateinit var manager: OfflineMapManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Long, Job>()
    private val arcJobs =
        ConcurrentHashMap<Long, MutableSet<ExportTileCacheJob>>()
    private val deletedIds = ConcurrentHashMap.newKeySet<Long>()
    private val regionSlots = Semaphore(MAX_CONCURRENT_REGIONS)
    private val exportSlots = Semaphore(MAX_CONCURRENT_EXPORT_JOBS)
    private val lastProgressPublishAt = ConcurrentHashMap<Long, Long>()
    private val progressPublishLocks = ConcurrentHashMap<Long, Any>()
    private var foregroundStarted = false

    private data class JobSpec(
        val regionId: Long,
        val name: String,
        val layer: MapLayer,
        val latitude: Double,
        val longitude: Double,
        val radiusKm: Double,
        val minZoom: Double,
        val maxZoom: Double
    )

    override fun onCreate() {
        super.onCreate()
        manager = OfflineMapManager(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_REGION_ID, -1L)
                if (id > 0) cancelRegion(id) else cancelAll()
            }
            ACTION_DELETE -> {
                val id = intent.getLongExtra(EXTRA_REGION_ID, -1L)
                if (id > 0) deleteRegion(id)
            }
            ACTION_START -> startRegion(jobFromIntent(intent))
            null -> {
                val active = loadActiveJobs()
                if (active.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                active.forEach(::startRegion)
            }
        }
        return START_STICKY
    }

    private sealed interface ChunkOutcome {
        data object Complete : ChunkOutcome
        data class Split(
            val children: List<OfflineMapManager.ExportChunk>
        ) : ChunkOutcome
    }

    private fun startRegion(spec: JobSpec) {
        if (running.containsKey(spec.regionId)) return

        saveJob(spec, active = true)
        deletedIds.remove(spec.regionId)
        OfflineMapDownloadState.publish(
            OfflineMapManager.DownloadProgress(
                regionId = spec.regionId,
                name = spec.name,
                layerTitle = spec.layer.title,
                active = true
            )
        )
        ensureForeground()

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var regionPermit = false
            var retryLater = false

            try {
                regionSlots.acquire()
                regionPermit = true

                manager.prepareRegion(
                    spec.regionId,
                    spec.name,
                    spec.layer,
                    spec.latitude,
                    spec.longitude,
                    spec.radiusKm,
                    spec.minZoom,
                    spec.maxZoom
                )

                val initialChunks = manager.buildChunks(
                    spec.regionId,
                    spec.layer,
                    spec.latitude,
                    spec.longitude,
                    spec.radiusKm,
                    spec.maxZoom.toInt()
                )

                if (initialChunks.isEmpty()) {
                    saveJob(spec, active = false)
                    OfflineMapDownloadState.publish(
                        OfflineMapManager.DownloadProgress(
                            regionId = spec.regionId,
                            name = spec.name,
                            layerTitle = spec.layer.title,
                            fatal = true,
                            active = false,
                            error = "Для пользовательской карты автоматическая офлайн-загрузка не поддерживается."
                        )
                    )
                    return@launch
                }

                val queued = initialChunks.filterNot {
                    it.file.isFile && it.file.length() > MIN_PACKAGE_BYTES
                }
                val alreadyComplete = initialChunks.size - queued.size

                val completed = AtomicLong(alreadyComplete.toLong())
                val pending = AtomicInteger(queued.size)
                val required = AtomicLong(initialChunks.size.toLong())
                val activeProgress = ConcurrentHashMap<String, Int>()

                fun publishCurrent(force: Boolean = false) {
                    val values = activeProgress.values
                    val packageProgress = if (values.isEmpty()) {
                        0
                    } else {
                        values.sum().div(values.size).coerceIn(0, 100)
                    }
                    publish(
                        spec = spec,
                        completed = completed.get(),
                        required = required.get(),
                        currentPackageProgress = packageProgress,
                        force = force
                    )
                }

                publishCurrent(force = true)

                if (queued.isNotEmpty()) {
                    val channel = Channel<OfflineMapManager.ExportChunk>(Channel.UNLIMITED)
                    queued.forEach { chunk ->
                        check(channel.trySend(chunk).isSuccess) {
                            "Не удалось поставить пакет карты в очередь"
                        }
                    }

                    coroutineScope {
                        val workerCount =
                            minOf(MAX_PARALLEL_CHUNKS_PER_REGION, queued.size)

                        List(workerCount) { workerIndex ->
                            launch {
                                val taskCache =
                                    mutableMapOf<String, ExportTileCacheTask>()

                                for (chunk in channel) {
                                    val progressKey =
                                        "${workerIndex}:${chunk.file.absolutePath}"

                                    val outcome = if (
                                        chunk.file.isFile &&
                                        chunk.file.length() > MIN_PACKAGE_BYTES
                                    ) {
                                        ChunkOutcome.Complete
                                    } else {
                                        downloadChunk(
                                            spec = spec,
                                            chunk = chunk,
                                            taskCache = taskCache,
                                            onProgress = { percent ->
                                                activeProgress[progressKey] = percent
                                                publishCurrent()
                                            }
                                        )
                                    }

                                    activeProgress.remove(progressKey)

                                    when (outcome) {
                                        ChunkOutcome.Complete -> {
                                            completed.incrementAndGet()
                                            if (pending.decrementAndGet() == 0) {
                                                channel.close()
                                            }
                                        }

                                        is ChunkOutcome.Split -> {
                                            val children = outcome.children
                                            check(children.isNotEmpty()) {
                                                "Не удалось разделить большой пакет"
                                            }

                                            val extra = children.size - 1
                                            pending.addAndGet(extra)
                                            required.addAndGet(extra.toLong())

                                            children.forEach { child ->
                                                check(channel.trySend(child).isSuccess) {
                                                    "Не удалось добавить часть карты в очередь"
                                                }
                                            }
                                        }
                                    }

                                    publishCurrent(force = true)
                                }
                            }
                        }.joinAll()
                    }
                }

                val finalDir = manager.finalizeRegion(spec.regionId)
                clearJob(spec.regionId)

                val bytes = finalDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("tpkx", true) }
                    .sumOf { it.length() }

                OfflineMapDownloadState.publish(
                    OfflineMapManager.DownloadProgress(
                        regionId = spec.regionId,
                        name = spec.name,
                        layerTitle = spec.layer.title,
                        completedResources = completed.get(),
                        requiredResources = required.get(),
                        bytes = bytes,
                        complete = true,
                        active = false
                    )
                )

                notifyTerminal(
                    terminalNotificationId(spec.regionId),
                    "Офлайн-карта готова",
                    "${spec.layer.title}: ${completed.get()} пакетов"
                )
            } catch (_: CancellationException) {
                if (!deletedIds.contains(spec.regionId)) {
                    saveJob(spec, active = false)
                    OfflineMapDownloadState.publish(
                        OfflineMapManager.DownloadProgress(
                            regionId = spec.regionId,
                            name = spec.name,
                            layerTitle = spec.layer.title,
                            bytes = manager.calculateBytes(spec.regionId),
                            cancelled = true
                        )
                    )
                }
            } catch (t: Throwable) {
                saveJob(spec, active = true)
                retryLater = true
                OfflineMapDownloadState.publish(
                    OfflineMapManager.DownloadProgress(
                        regionId = spec.regionId,
                        name = spec.name,
                        layerTitle = spec.layer.title,
                        bytes = manager.calculateBytes(spec.regionId),
                        needsRetry = true,
                        active = true,
                        error = "Временная ошибка ArcGIS: ${t.message ?: "неизвестная ошибка"}. Докачивание продолжится."
                    )
                )
            } finally {
                cancelArcJobs(spec.regionId)
                if (regionPermit) regionSlots.release()

                lastProgressPublishAt.remove(spec.regionId)
                progressPublishLocks.remove(spec.regionId)
                running.remove(spec.regionId)

                updateAggregateNotification()

                if (retryLater && !deletedIds.contains(spec.regionId)) {
                    delay(AUTO_RETRY_MS)
                    if (!deletedIds.contains(spec.regionId)) {
                        startRegion(spec)
                    }
                } else {
                    stopIfIdle()
                }
            }
        }

        running[spec.regionId] = job
        job.start()
    }

    private suspend fun downloadChunk(
        spec: JobSpec,
        chunk: OfflineMapManager.ExportChunk,
        taskCache: MutableMap<String, ExportTileCacheTask>,
        onProgress: (Int) -> Unit
    ): ChunkOutcome {
        var lastError: Throwable? = null

        for (attempt in 0 until CHUNK_RETRIES) {
            var exportJob: ExportTileCacheJob? = null
            var progressJob: Job? = null
            var exportPermit = false

            try {
                exportSlots.acquire()
                exportPermit = true

                if (chunk.file.exists()) chunk.file.delete()
                chunk.file.parentFile?.mkdirs()

                val area = Envelope(
                    xMin = chunk.west,
                    yMin = chunk.south,
                    xMax = chunk.east,
                    yMax = chunk.north,
                    spatialReference = SpatialReference.wgs84()
                )

                val task = taskCache.getOrPut(chunk.source.url) {
                    ExportTileCacheTask(chunk.source.url).apply {
                        apiKey = ArcGISEnvironment.apiKey
                    }
                }

                val params = task.createDefaultExportTileCacheParameters(
                    areaOfInterest = area,
                    minScale = scaleForZoom(spec.minZoom.toInt()),
                    maxScale = scaleForZoom(spec.maxZoom.toInt())
                ).getOrThrow()

                exportJob = task.createExportTileCacheJob(
                    parameters = params,
                    downloadFilePath = chunk.file.absolutePath
                )

                registerArcJob(spec.regionId, exportJob)

                progressJob = scope.launch {
                    exportJob.progress.collectLatest { percent ->
                        onProgress(percent.coerceIn(0, 100))
                    }
                }

                check(exportJob.start()) {
                    "ArcGIS не запустил экспорт пакета"
                }

                withTimeout(CHUNK_TIMEOUT_MS) {
                    exportJob.result().getOrThrow()
                }

                check(
                    chunk.file.isFile &&
                        chunk.file.length() > MIN_PACKAGE_BYTES
                ) {
                    "ArcGIS вернул пустой пакет"
                }

                return ChunkOutcome.Complete
            } catch (e: TimeoutCancellationException) {
                lastError = IllegalStateException(
                    "ArcGIS слишком долго готовил пакет. Он будет автоматически повторён.",
                    e
                )
                runCatching { exportJob?.cancel() }
            } catch (e: CancellationException) {
                runCatching { exportJob?.cancel() }
                throw e
            } catch (t: Throwable) {
                lastError = t
                runCatching { exportJob?.cancel() }

                if (isTileLimitError(t)) {
                    check(chunk.depth < MAX_SUBDIVISION_DEPTH) {
                        "ArcGIS всё ещё отклоняет пакет после максимального дробления: ${t.message}"
                    }

                    chunk.file.delete()
                    return ChunkOutcome.Split(manager.splitChunk(chunk))
                }
            } finally {
                progressJob?.cancel()
                exportJob?.let {
                    unregisterArcJob(spec.regionId, it)
                }
                if (exportPermit) {
                    exportSlots.release()
                }
            }

            if (attempt + 1 < CHUNK_RETRIES) {
                delay(retryDelayMs(attempt, lastError))
            }
        }

        throw IllegalStateException(
            lastError?.message ?: "Не удалось скачать часть карты",
            lastError
        )
    }

    private fun registerArcJob(
        regionId: Long,
        job: ExportTileCacheJob
    ) {
        arcJobs.computeIfAbsent(regionId) {
            ConcurrentHashMap.newKeySet<ExportTileCacheJob>()
        }.add(job)
    }

    private fun unregisterArcJob(
        regionId: Long,
        job: ExportTileCacheJob
    ) {
        val jobs = arcJobs[regionId] ?: return
        jobs.remove(job)
        if (jobs.isEmpty()) {
            arcJobs.remove(regionId, jobs)
        }
    }

    private suspend fun cancelArcJobs(regionId: Long) {
        arcJobs.remove(regionId)
            ?.toList()
            .orEmpty()
            .forEach { job ->
                runCatching { job.cancel() }
            }
    }

    private fun retryDelayMs(attempt: Int, error: Throwable?): Long {
        val delays = if (error != null && isRateLimitError(error)) {
            RATE_LIMIT_RETRY_DELAYS_MS
        } else {
            RETRY_DELAYS_MS
        }
        return delays[attempt.coerceIn(0, delays.lastIndex)]
    }

    private fun publish(
        spec: JobSpec,
        completed: Long,
        required: Long,
        currentPackageProgress: Int = 0,
        force: Boolean = false
    ) {
        val lock = progressPublishLocks.computeIfAbsent(spec.regionId) { Any() }

        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val last = lastProgressPublishAt[spec.regionId] ?: 0L

            if (!force && now - last < PROGRESS_PUBLISH_INTERVAL_MS) {
                return
            }

            lastProgressPublishAt[spec.regionId] = now

            OfflineMapDownloadState.publish(
                OfflineMapManager.DownloadProgress(
                    regionId = spec.regionId,
                    name = spec.name,
                    layerTitle = spec.layer.title,
                    completedResources = completed,
                    requiredResources = required,
                    bytes = manager.calculateBytes(spec.regionId),
                    missingResources = (required - completed).coerceAtLeast(0),
                    currentPackageProgress =
                        currentPackageProgress.coerceIn(0, 100),
                    active = true
                )
            )
            updateAggregateNotification()
        }
    }

    private fun cancelRegion(id: Long) {
        scope.launch {
            cancelArcJobs(id)
            running.remove(id)?.cancelAndJoin()
            loadJob(id)?.let { saveJob(it, active = false) }
            lastProgressPublishAt.remove(id)
            progressPublishLocks.remove(id)
            updateAggregateNotification()
            stopIfIdle()
        }
    }

    private fun deleteRegion(id: Long) {
        scope.launch {
            deletedIds += id
            cancelArcJobs(id)
            running.remove(id)?.cancelAndJoin()

            manager.deleteRegion(
                id = id,
                onSuccess = {},
                onError = {}
            )
            clearJob(id)
            OfflineMapDownloadState.remove(id)
            lastProgressPublishAt.remove(id)
            progressPublishLocks.remove(id)

            getSystemService(NotificationManager::class.java)
                .cancel(terminalNotificationId(id))

            updateAggregateNotification()
            stopIfIdle()
        }
    }

    private fun cancelAll() {
        scope.launch {
            val ids = (arcJobs.keys + running.keys).toSet()
            ids.forEach { cancelArcJobs(it) }

            running.values.toList().forEach { it.cancel() }
            running.values.toList().forEach { runCatching { it.join() } }

            loadActiveJobs().forEach { saveJob(it, active = false) }
            lastProgressPublishAt.clear()
            progressPublishLocks.clear()

            updateAggregateNotification()
            stopIfIdle()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground() {
        if (!foregroundStarted) {
            val type = if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(), type
            )
            foregroundStarted = true
        } else updateAggregateNotification()
    }

    private fun updateAggregateNotification() {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 2101,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 2102,
            Intent(this, OfflineMapDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val active = OfflineMapDownloadState.progress.value.values.filter { it.active }
        val completed = active.sumOf { it.completedResources }
        val required = active.sumOf { it.requiredResources }
        val bytes = active.sumOf { it.bytes }
        val text = if (required > 0L) {
            "${active.size.coerceAtLeast(1)} загрузки • $completed/$required пакетов • ${bytes / 1_048_576} МБ"
        } else "Подготовка офлайн-карты…"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Офлайн-карты ArcGIS")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Пауза всех", cancelIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (required > 0L) {
                    setProgress(
                        100,
                        ((completed * 100L) / required).toInt().coerceIn(0, 100),
                        false
                    )
                } else setProgress(0, 0, true)
            }
            .build()
    }

    private fun notifyTerminal(id: Int, title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            id,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun stopIfIdle() {
        if (running.isNotEmpty()) return
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Скачивание офлайн-карт",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun scaleForZoom(zoom: Int): Double =
        591_657_527.591555 / 2.0.pow(zoom.toDouble())

    private fun jobFromIntent(intent: Intent): JobSpec =
        JobSpec(
            regionId = intent.getLongExtra(EXTRA_REGION_ID, System.currentTimeMillis()),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Офлайн-карта" },
            layer = runCatching {
                MapLayer.valueOf(intent.getStringExtra(EXTRA_LAYER).orEmpty())
            }.getOrDefault(MapLayer.MAP),
            latitude = intent.getDoubleExtra(EXTRA_LAT, 0.0),
            longitude = intent.getDoubleExtra(EXTRA_LON, 0.0),
            radiusKm = intent.getDoubleExtra(EXTRA_RADIUS, 5.0),
            minZoom = intent.getDoubleExtra(EXTRA_MIN_ZOOM, 10.0),
            maxZoom = intent.getDoubleExtra(EXTRA_MAX_ZOOM, 17.0)
        )

    private fun saveJob(spec: JobSpec, active: Boolean) {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val known = p.getStringSet(KEY_KNOWN_IDS, emptySet()).orEmpty().toMutableSet()
        val activeIds = p.getStringSet(KEY_ACTIVE_IDS, emptySet()).orEmpty().toMutableSet()
        val id = spec.regionId.toString()
        known += id
        if (active) activeIds += id else activeIds -= id
        p.edit()
            .putStringSet(KEY_KNOWN_IDS, known)
            .putStringSet(KEY_ACTIVE_IDS, activeIds)
            .putString(key(spec.regionId, "name"), spec.name)
            .putString(key(spec.regionId, "layer"), spec.layer.name)
            .putString(key(spec.regionId, "lat"), spec.latitude.toString())
            .putString(key(spec.regionId, "lon"), spec.longitude.toString())
            .putString(key(spec.regionId, "radius"), spec.radiusKm.toString())
            .putString(key(spec.regionId, "min_zoom"), spec.minZoom.toString())
            .putString(key(spec.regionId, "max_zoom"), spec.maxZoom.toString())
            .apply()
    }

    private fun loadActiveJobs(): List<JobSpec> {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        return p.getStringSet(KEY_ACTIVE_IDS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull()?.let(::loadJob) }
    }

    private fun loadJob(regionId: Long): JobSpec? {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!p.contains(key(regionId, "layer"))) return null
        return runCatching {
            JobSpec(
                regionId,
                p.getString(key(regionId, "name"), "Офлайн-карта").orEmpty(),
                MapLayer.valueOf(
                    p.getString(key(regionId, "layer"), MapLayer.MAP.name).orEmpty()
                ),
                p.getString(key(regionId, "lat"), "0")!!.toDouble(),
                p.getString(key(regionId, "lon"), "0")!!.toDouble(),
                p.getString(key(regionId, "radius"), "5")!!.toDouble(),
                p.getString(key(regionId, "min_zoom"), "10")!!.toDouble(),
                p.getString(key(regionId, "max_zoom"), "17")!!.toDouble()
            )
        }.getOrNull()
    }

    private fun clearJob(regionId: Long) {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val known = p.getStringSet(KEY_KNOWN_IDS, emptySet()).orEmpty().toMutableSet()
        val active = p.getStringSet(KEY_ACTIVE_IDS, emptySet()).orEmpty().toMutableSet()
        val id = regionId.toString()
        known -= id
        active -= id
        p.edit()
            .putStringSet(KEY_KNOWN_IDS, known)
            .putStringSet(KEY_ACTIVE_IDS, active)
            .remove(key(regionId, "name"))
            .remove(key(regionId, "layer"))
            .remove(key(regionId, "lat"))
            .remove(key(regionId, "lon"))
            .remove(key(regionId, "radius"))
            .remove(key(regionId, "min_zoom"))
            .remove(key(regionId, "max_zoom"))
            .apply()
    }

    private fun isTileLimitError(error: Throwable): Boolean {
        val text = generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()

        return text.contains("error 001564") ||
            text.contains("exceeds the maximum allowed number of tiles") ||
            text.contains("maxexporttilescount")
    }

    private fun isRateLimitError(error: Throwable): Boolean {
        val text = generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()

        return text.contains("429") ||
            text.contains("too many requests") ||
            text.contains("rate limit") ||
            text.contains("503") ||
            text.contains("service unavailable")
    }

    private fun key(id: Long, field: String) = "job_${id}_$field"

    private fun terminalNotificationId(regionId: Long): Int =
        3000 + (regionId xor (regionId ushr 32)).toInt().and(0x3fff)

    companion object {
        private const val CHANNEL_ID = "offline_map_download"
        private const val NOTIFICATION_ID = 2101
        private const val PREFS = "arcgis_offline_jobs"
        private const val KEY_KNOWN_IDS = "known_ids"
        private const val KEY_ACTIVE_IDS = "active_ids"
        private const val MAX_CONCURRENT_REGIONS = 2
        private const val MAX_PARALLEL_CHUNKS_PER_REGION = 6
        private const val MAX_CONCURRENT_EXPORT_JOBS = 8
        private const val CHUNK_RETRIES = 4
        private const val MIN_PACKAGE_BYTES = 512L
        private const val AUTO_RETRY_MS = 20_000L
        private const val CHUNK_TIMEOUT_MS = 600_000L
        private const val MAX_SUBDIVISION_DEPTH = 6
        private const val PROGRESS_PUBLISH_INTERVAL_MS = 750L
        private val RETRY_DELAYS_MS =
            longArrayOf(1_500L, 4_000L, 9_000L, 18_000L)
        private val RATE_LIMIT_RETRY_DELAYS_MS =
            longArrayOf(5_000L, 12_000L, 25_000L, 45_000L)

        private const val ACTION_START = "app.forestnav.action.DOWNLOAD_OFFLINE_MAP"
        private const val ACTION_CANCEL = "app.forestnav.action.CANCEL_OFFLINE_MAP"
        private const val ACTION_DELETE = "app.forestnav.action.DELETE_OFFLINE_MAP_DOWNLOAD"
        private const val EXTRA_REGION_ID = "region_id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LAYER = "layer"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_RADIUS = "radius"
        private const val EXTRA_MIN_ZOOM = "min_zoom"
        private const val EXTRA_MAX_ZOOM = "max_zoom"

        fun start(
            context: Context,
            name: String,
            layer: MapLayer,
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
            minZoom: Double,
            maxZoom: Double
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OfflineMapDownloadService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_REGION_ID, System.currentTimeMillis())
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_LAYER, layer.name)
                    .putExtra(EXTRA_LAT, latitude)
                    .putExtra(EXTRA_LON, longitude)
                    .putExtra(EXTRA_RADIUS, radiusKm)
                    .putExtra(EXTRA_MIN_ZOOM, minZoom)
                    .putExtra(EXTRA_MAX_ZOOM, maxZoom)
            )
        }

        fun cancel(context: Context, regionId: Long? = null) {
            val intent = Intent(context, OfflineMapDownloadService::class.java)
                .setAction(ACTION_CANCEL)
            if (regionId != null) intent.putExtra(EXTRA_REGION_ID, regionId)
            context.startService(intent)
        }

        fun delete(context: Context, regionId: Long) {
            context.startService(
                Intent(context, OfflineMapDownloadService::class.java)
                    .setAction(ACTION_DELETE)
                    .putExtra(EXTRA_REGION_ID, regionId)
            )
        }
    }
}
