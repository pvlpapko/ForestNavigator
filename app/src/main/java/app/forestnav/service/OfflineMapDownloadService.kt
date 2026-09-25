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
import app.forestnav.map.OfflinePackageFormat
import app.forestnav.map.OfflineSource
import com.arcgismaps.ArcGISEnvironment
import com.arcgismaps.geometry.Envelope
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.ArcGISMap
import com.arcgismaps.mapping.layers.ArcGISVectorTiledLayer
import com.arcgismaps.tasks.exportvectortiles.ExportVectorTilesJob
import com.arcgismaps.tasks.exportvectortiles.ExportVectorTilesTask
import com.arcgismaps.tasks.tilecache.ExportTileCacheJob
import com.arcgismaps.tasks.tilecache.ExportTileCacheTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
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
        synchronized(lock) {
            _progress.value = _progress.value + (id to value)
        }
    }

    internal fun remove(regionId: Long) {
        synchronized(lock) {
            _progress.value = _progress.value - regionId
        }
    }
}

/**
 * Clean v3 offline downloader.
 *
 * Each region has an isolated staging directory and an atomic final manifest.
 * Raster imagery and hillshade use ExportTileCacheTask; the normal street map
 * uses ExportVectorTilesTask so relief is not baked into the ordinary map.
 * Multiple packages download concurrently, but all server jobs remain
 * individually cancellable.
 */
class OfflineMapDownloadService : Service() {

    private lateinit var manager: OfflineMapManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val running = ConcurrentHashMap<Long, Job>()
    private val rasterJobs =
        ConcurrentHashMap<Long, MutableSet<ExportTileCacheJob>>()
    private val vectorJobs =
        ConcurrentHashMap<Long, MutableSet<ExportVectorTilesJob>>()

    private val deletedIds = ConcurrentHashMap.newKeySet<Long>()
    private val regionSlots = Semaphore(MAX_CONCURRENT_REGIONS)
    private val exportSlots = Semaphore(MAX_CONCURRENT_EXPORT_JOBS)

    private val vectorSourceUris = ConcurrentHashMap<String, String>()
    private val lastProgressPublishAt = ConcurrentHashMap<Long, Long>()
    private val progressLocks = ConcurrentHashMap<Long, Any>()

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

    private sealed interface ChunkOutcome {
        data object Complete : ChunkOutcome
        data class Split(
            val children: List<OfflineMapManager.ExportChunk>
        ) : ChunkOutcome
    }

    private data class WorkerTasks(
        val raster: MutableMap<String, ExportTileCacheTask> = mutableMapOf(),
        val vector: MutableMap<String, ExportVectorTilesTask> = mutableMapOf()
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
                if (id > 0L) cancelRegion(id) else cancelAll()
            }

            ACTION_DELETE -> {
                val id = intent.getLongExtra(EXTRA_REGION_ID, -1L)
                if (id > 0L) deleteRegion(id)
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
                    regionId = spec.regionId,
                    name = spec.name,
                    layer = spec.layer,
                    latitude = spec.latitude,
                    longitude = spec.longitude,
                    radiusKm = spec.radiusKm,
                    minZoom = spec.minZoom,
                    maxZoom = spec.maxZoom
                )

                val initialChunks = manager.buildChunks(
                    regionId = spec.regionId,
                    layer = spec.layer,
                    latitude = spec.latitude,
                    longitude = spec.longitude,
                    radiusKm = spec.radiusKm,
                    maxZoom = spec.maxZoom.toInt()
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

                val queued = initialChunks.filterNot(manager::isPackageComplete)
                val completeAtStart = initialChunks.size - queued.size

                queued
                    .map { it.source }
                    .filter { it.format == OfflinePackageFormat.VECTOR }
                    .distinctBy { it.id }
                    .forEach { resolveVectorSourceUri(it) }

                val completed = AtomicLong(completeAtStart.toLong())
                val pending = AtomicInteger(queued.size)
                val required = AtomicLong(initialChunks.size.toLong())
                val activeProgress = ConcurrentHashMap<String, Int>()

                fun publishCurrent(force: Boolean = false) {
                    val values = activeProgress.values
                    val current = if (values.isEmpty()) {
                        0
                    } else {
                        (values.sum() / values.size).coerceIn(0, 100)
                    }

                    publish(
                        spec = spec,
                        completed = completed.get(),
                        required = required.get(),
                        currentPackageProgress = current,
                        force = force
                    )
                }

                publishCurrent(force = true)

                if (queued.isNotEmpty()) {
                    val channel =
                        Channel<OfflineMapManager.ExportChunk>(Channel.UNLIMITED)

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
                                val tasks = WorkerTasks()

                                for (chunk in channel) {
                                    val progressKey =
                                        "$workerIndex:${chunk.file.absolutePath}"

                                    val outcome =
                                        if (manager.isPackageComplete(chunk)) {
                                            ChunkOutcome.Complete
                                        } else {
                                            downloadChunk(
                                                spec = spec,
                                                chunk = chunk,
                                                tasks = tasks,
                                                onProgress = { percent ->
                                                    activeProgress[progressKey] =
                                                        percent.coerceIn(0, 100)
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
                    .filter {
                        it.isFile &&
                            (
                                it.extension.equals("tpkx", true) ||
                                    it.extension.equals("vtpk", true)
                                )
                    }
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
                            cancelled = true,
                            active = false
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

                if (regionPermit) {
                    regionSlots.release()
                }

                lastProgressPublishAt.remove(spec.regionId)
                progressLocks.remove(spec.regionId)
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
        tasks: WorkerTasks,
        onProgress: (Int) -> Unit
    ): ChunkOutcome {
        var lastError: Throwable? = null

        for (attempt in 0 until CHUNK_RETRIES) {
            var exportPermit = false

            try {
                exportSlots.acquire()
                exportPermit = true

                if (chunk.file.exists()) {
                    chunk.file.delete()
                }
                chunk.file.parentFile?.mkdirs()

                val outcome = when (chunk.source.format) {
                    OfflinePackageFormat.RASTER ->
                        downloadRasterChunk(
                            spec = spec,
                            chunk = chunk,
                            tasks = tasks,
                            onProgress = onProgress
                        )

                    OfflinePackageFormat.VECTOR ->
                        downloadVectorChunk(
                            spec = spec,
                            chunk = chunk,
                            tasks = tasks,
                            onProgress = onProgress
                        )
                }

                manager.markPackageComplete(chunk)
                return outcome
            } catch (e: TimeoutCancellationException) {
                lastError = IllegalStateException(
                    "ArcGIS слишком долго готовил пакет",
                    e
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t

                if (isTileLimitError(t)) {
                    check(chunk.depth < MAX_SUBDIVISION_DEPTH) {
                        "ArcGIS отклоняет пакет даже после максимального дробления: ${t.message}"
                    }

                    chunk.file.delete()
                    return ChunkOutcome.Split(manager.splitChunk(chunk))
                }
            } finally {
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

    private suspend fun downloadRasterChunk(
        spec: JobSpec,
        chunk: OfflineMapManager.ExportChunk,
        tasks: WorkerTasks,
        onProgress: (Int) -> Unit
    ): ChunkOutcome {
        val url = chunk.source.rasterUrl
            ?: error("У растрового источника нет URL")

        val task = tasks.raster.getOrPut(chunk.source.id) {
            ExportTileCacheTask(url).apply {
                apiKey = ArcGISEnvironment.apiKey
            }
        }

        val area = chunkEnvelope(chunk)
        val parameters = task.createDefaultExportTileCacheParameters(
            areaOfInterest = area,
            minScale = scaleForZoom(spec.minZoom.toInt()),
            maxScale = scaleForZoom(spec.maxZoom.toInt())
        ).getOrThrow()

        val exportJob = task.createExportTileCacheJob(
            parameters = parameters,
            downloadFilePath = chunk.file.absolutePath
        )

        registerRasterJob(spec.regionId, exportJob)

        val progressJob = scope.launch {
            exportJob.progress.collectLatest { percent ->
                onProgress(percent)
            }
        }

        try {
            check(exportJob.start()) {
                "ArcGIS не запустил растровый экспорт"
            }

            withTimeout(CHUNK_TIMEOUT_MS) {
                exportJob.result().getOrThrow()
            }

            check(
                chunk.file.isFile &&
                    chunk.file.length() > MIN_PACKAGE_BYTES
            ) {
                "ArcGIS вернул пустой растровый пакет"
            }

            return ChunkOutcome.Complete
        } catch (t: Throwable) {
            runCatching {
                exportJob.cancel()
            }
            throw t
        } finally {
            progressJob.cancel()
            unregisterRasterJob(spec.regionId, exportJob)
        }
    }

    private suspend fun downloadVectorChunk(
        spec: JobSpec,
        chunk: OfflineMapManager.ExportChunk,
        tasks: WorkerTasks,
        onProgress: (Int) -> Unit
    ): ChunkOutcome {
        val uri = vectorSourceUris[chunk.source.id]
            ?: resolveVectorSourceUri(chunk.source)

        val task = tasks.vector.getOrPut(chunk.source.id) {
            ExportVectorTilesTask(uri).apply {
                apiKey = ArcGISEnvironment.apiKey
            }
        }

        val parameters = task.createDefaultExportVectorTilesParameters(
            areaOfInterest = chunkEnvelope(chunk),
            maxScale = scaleForZoom(spec.maxZoom.toInt())
        ).getOrThrow()

        val exportJob = task.createExportVectorTilesJob(
            parameters = parameters,
            downloadFilePath = chunk.file.absolutePath
        )

        registerVectorJob(spec.regionId, exportJob)

        val progressJob = scope.launch {
            exportJob.progress.collectLatest { percent ->
                onProgress(percent)
            }
        }

        try {
            check(exportJob.start()) {
                "ArcGIS не запустил экспорт обычной карты"
            }

            withTimeout(CHUNK_TIMEOUT_MS) {
                exportJob.result().getOrThrow()
            }

            check(
                chunk.file.isFile &&
                    chunk.file.length() > MIN_PACKAGE_BYTES
            ) {
                "ArcGIS вернул пустой пакет обычной карты"
            }

            return ChunkOutcome.Complete
        } catch (t: Throwable) {
            runCatching {
                exportJob.cancel()
            }
            throw t
        } finally {
            progressJob.cancel()
            unregisterVectorJob(spec.regionId, exportJob)
        }
    }

    /**
     * Resolves the real vector layer behind ArcGIS Streets. ArcGIS Runtime can
     * then substitute its export-enabled service automatically.
     */
    private suspend fun resolveVectorSourceUri(source: OfflineSource): String {
        vectorSourceUris[source.id]?.let { return it }

        val style = source.vectorBasemapStyle
            ?: error("У векторного источника нет стиля")

        val map = ArcGISMap(style)
        map.load().getOrThrow()

        val basemap = map.basemap.value
            ?: error("ArcGIS не создал базовую карту")

        val layer = basemap.baseLayers
            .filterIsInstance<ArcGISVectorTiledLayer>()
            .firstOrNull()
            ?: error("В ArcGIS Streets не найден векторный слой")

        layer.load().getOrThrow()

        val uri = layer.uri
            ?.takeIf { it.isNotBlank() }
            ?: error("ArcGIS не вернул URI векторной карты")

        vectorSourceUris.putIfAbsent(source.id, uri)
        return vectorSourceUris[source.id] ?: uri
    }

    private fun chunkEnvelope(
        chunk: OfflineMapManager.ExportChunk
    ): Envelope =
        Envelope(
            xMin = chunk.west,
            yMin = chunk.south,
            xMax = chunk.east,
            yMax = chunk.north,
            spatialReference = SpatialReference.wgs84()
        )

    private fun publish(
        spec: JobSpec,
        completed: Long,
        required: Long,
        currentPackageProgress: Int = 0,
        force: Boolean = false
    ) {
        val lock = progressLocks.computeIfAbsent(spec.regionId) { Any() }

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

    private fun registerRasterJob(
        regionId: Long,
        job: ExportTileCacheJob
    ) {
        rasterJobs.computeIfAbsent(regionId) {
            ConcurrentHashMap.newKeySet<ExportTileCacheJob>()
        }.add(job)
    }

    private fun unregisterRasterJob(
        regionId: Long,
        job: ExportTileCacheJob
    ) {
        val jobs = rasterJobs[regionId] ?: return
        jobs.remove(job)
        if (jobs.isEmpty()) {
            rasterJobs.remove(regionId, jobs)
        }
    }

    private fun registerVectorJob(
        regionId: Long,
        job: ExportVectorTilesJob
    ) {
        vectorJobs.computeIfAbsent(regionId) {
            ConcurrentHashMap.newKeySet<ExportVectorTilesJob>()
        }.add(job)
    }

    private fun unregisterVectorJob(
        regionId: Long,
        job: ExportVectorTilesJob
    ) {
        val jobs = vectorJobs[regionId] ?: return
        jobs.remove(job)
        if (jobs.isEmpty()) {
            vectorJobs.remove(regionId, jobs)
        }
    }

    private suspend fun cancelArcJobs(regionId: Long) {
        rasterJobs.remove(regionId)
            ?.toList()
            .orEmpty()
            .forEach { job ->
                runCatching { job.cancel() }
            }

        vectorJobs.remove(regionId)
            ?.toList()
            .orEmpty()
            .forEach { job ->
                runCatching { job.cancel() }
            }
    }

    private fun cancelRegion(id: Long) {
        scope.launch {
            cancelArcJobs(id)
            running.remove(id)?.cancelAndJoin()
            loadJob(id)?.let {
                saveJob(it, active = false)
            }
            lastProgressPublishAt.remove(id)
            progressLocks.remove(id)
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
            progressLocks.remove(id)

            getSystemService(NotificationManager::class.java)
                .cancel(terminalNotificationId(id))

            updateAggregateNotification()
            stopIfIdle()
        }
    }

    private fun cancelAll() {
        scope.launch {
            val ids = (
                rasterJobs.keys +
                    vectorJobs.keys +
                    running.keys
                ).toSet()

            ids.forEach { id ->
                cancelArcJobs(id)
            }

            val jobs = running.values.toList()
            jobs.forEach { it.cancel() }
            jobs.joinAll()

            loadActiveJobs().forEach {
                saveJob(it, active = false)
            }

            lastProgressPublishAt.clear()
            progressLocks.clear()
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
            } else {
                0
            }

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                type
            )
            foregroundStarted = true
        } else {
            updateAggregateNotification()
        }
    }

    private fun updateAggregateNotification() {
        if (!foregroundStarted) return

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            2101,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = PendingIntent.getService(
            this,
            2102,
            Intent(this, OfflineMapDownloadService::class.java)
                .setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val active =
            OfflineMapDownloadState.progress.value.values.filter { it.active }
        val completed = active.sumOf { it.completedResources }
        val required = active.sumOf { it.requiredResources }
        val bytes = active.sumOf { it.bytes }

        val text = if (required > 0L) {
            "${active.size.coerceAtLeast(1)} загрузки • " +
                "$completed/$required пакетов • " +
                "${bytes / 1_048_576} МБ"
        } else {
            "Подготовка офлайн-карты…"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Офлайн-карты")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Пауза всех",
                cancelIntent
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (required > 0L) {
                    setProgress(
                        100,
                        (
                            (completed * 100L) /
                                required.coerceAtLeast(1L)
                            ).toInt().coerceIn(0, 100),
                        false
                    )
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun notifyTerminal(
        id: Int,
        title: String,
        text: String
    ) {
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
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
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
            regionId = intent.getLongExtra(
                EXTRA_REGION_ID,
                System.currentTimeMillis()
            ),
            name = intent.getStringExtra(EXTRA_NAME)
                .orEmpty()
                .ifBlank { "Офлайн-карта" },
            layer = runCatching {
                MapLayer.valueOf(
                    intent.getStringExtra(EXTRA_LAYER).orEmpty()
                )
            }.getOrDefault(MapLayer.MAP),
            latitude = intent.getDoubleExtra(EXTRA_LAT, 0.0),
            longitude = intent.getDoubleExtra(EXTRA_LON, 0.0),
            radiusKm = intent.getDoubleExtra(EXTRA_RADIUS, 5.0),
            minZoom = intent.getDoubleExtra(EXTRA_MIN_ZOOM, 10.0),
            maxZoom = intent.getDoubleExtra(EXTRA_MAX_ZOOM, 17.0)
        )

    private fun saveJob(spec: JobSpec, active: Boolean) {
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        val known = preferences
            .getStringSet(KEY_KNOWN_IDS, emptySet())
            .orEmpty()
            .toMutableSet()
        val activeIds = preferences
            .getStringSet(KEY_ACTIVE_IDS, emptySet())
            .orEmpty()
            .toMutableSet()

        val id = spec.regionId.toString()
        known += id

        if (active) {
            activeIds += id
        } else {
            activeIds -= id
        }

        preferences.edit()
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
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)

        return preferences
            .getStringSet(KEY_ACTIVE_IDS, emptySet())
            .orEmpty()
            .mapNotNull { rawId ->
                rawId.toLongOrNull()?.let(::loadJob)
            }
    }

    private fun loadJob(regionId: Long): JobSpec? {
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)

        if (!preferences.contains(key(regionId, "layer"))) {
            return null
        }

        return runCatching {
            JobSpec(
                regionId = regionId,
                name = preferences
                    .getString(
                        key(regionId, "name"),
                        "Офлайн-карта"
                    )
                    .orEmpty(),
                layer = MapLayer.valueOf(
                    preferences
                        .getString(
                            key(regionId, "layer"),
                            MapLayer.MAP.name
                        )
                        .orEmpty()
                ),
                latitude = preferences
                    .getString(key(regionId, "lat"), "0")!!
                    .toDouble(),
                longitude = preferences
                    .getString(key(regionId, "lon"), "0")!!
                    .toDouble(),
                radiusKm = preferences
                    .getString(key(regionId, "radius"), "5")!!
                    .toDouble(),
                minZoom = preferences
                    .getString(key(regionId, "min_zoom"), "10")!!
                    .toDouble(),
                maxZoom = preferences
                    .getString(key(regionId, "max_zoom"), "17")!!
                    .toDouble()
            )
        }.getOrNull()
    }

    private fun clearJob(regionId: Long) {
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        val known = preferences
            .getStringSet(KEY_KNOWN_IDS, emptySet())
            .orEmpty()
            .toMutableSet()
        val active = preferences
            .getStringSet(KEY_ACTIVE_IDS, emptySet())
            .orEmpty()
            .toMutableSet()

        val id = regionId.toString()
        known -= id
        active -= id

        preferences.edit()
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
        val text = errorText(error)

        return text.contains("error 001564") ||
            text.contains("exceeds the maximum allowed number of tiles") ||
            text.contains("maximum export tile count") ||
            text.contains("maxexporttilescount") ||
            text.contains("maxexporttilecount")
    }

    private fun isRateLimitError(error: Throwable): Boolean {
        val text = errorText(error)

        return text.contains("429") ||
            text.contains("too many requests") ||
            text.contains("rate limit") ||
            text.contains("503") ||
            text.contains("service unavailable")
    }

    private fun errorText(error: Throwable): String =
        generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()

    private fun retryDelayMs(
        attempt: Int,
        error: Throwable?
    ): Long {
        val delays =
            if (error != null && isRateLimitError(error)) {
                RATE_LIMIT_RETRY_DELAYS_MS
            } else {
                RETRY_DELAYS_MS
            }

        return delays[attempt.coerceIn(0, delays.lastIndex)]
    }

    private fun key(id: Long, field: String): String =
        "job_${id}_$field"

    private fun terminalNotificationId(regionId: Long): Int =
        3000 + (regionId xor (regionId ushr 32))
            .toInt()
            .and(0x3fff)

    companion object {
        private const val CHANNEL_ID = "offline_map_download_v3"
        private const val NOTIFICATION_ID = 2101

        private const val PREFS = "arcgis_offline_jobs_v3"
        private const val KEY_KNOWN_IDS = "known_ids"
        private const val KEY_ACTIVE_IDS = "active_ids"

        private const val MAX_CONCURRENT_REGIONS = 2
        private const val MAX_PARALLEL_CHUNKS_PER_REGION = 6
        private const val MAX_CONCURRENT_EXPORT_JOBS = 8
        private const val CHUNK_RETRIES = 4
        private const val MAX_SUBDIVISION_DEPTH = 7

        private const val MIN_PACKAGE_BYTES = 1_024L
        private const val AUTO_RETRY_MS = 20_000L
        private const val CHUNK_TIMEOUT_MS = 900_000L
        private const val PROGRESS_PUBLISH_INTERVAL_MS = 1_000L

        private val RETRY_DELAYS_MS =
            longArrayOf(1_500L, 4_000L, 9_000L, 18_000L)
        private val RATE_LIMIT_RETRY_DELAYS_MS =
            longArrayOf(5_000L, 12_000L, 25_000L, 45_000L)

        private const val ACTION_START =
            "app.forestnav.action.DOWNLOAD_OFFLINE_MAP"
        private const val ACTION_CANCEL =
            "app.forestnav.action.CANCEL_OFFLINE_MAP"
        private const val ACTION_DELETE =
            "app.forestnav.action.DELETE_OFFLINE_MAP_DOWNLOAD"

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
                Intent(
                    context,
                    OfflineMapDownloadService::class.java
                )
                    .setAction(ACTION_START)
                    .putExtra(
                        EXTRA_REGION_ID,
                        System.currentTimeMillis()
                    )
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_LAYER, layer.name)
                    .putExtra(EXTRA_LAT, latitude)
                    .putExtra(EXTRA_LON, longitude)
                    .putExtra(EXTRA_RADIUS, radiusKm)
                    .putExtra(EXTRA_MIN_ZOOM, minZoom)
                    .putExtra(EXTRA_MAX_ZOOM, maxZoom)
            )
        }

        fun cancel(
            context: Context,
            regionId: Long? = null
        ) {
            val intent = Intent(
                context,
                OfflineMapDownloadService::class.java
            ).setAction(ACTION_CANCEL)

            if (regionId != null) {
                intent.putExtra(EXTRA_REGION_ID, regionId)
            }

            context.startService(intent)
        }

        fun delete(
            context: Context,
            regionId: Long
        ) {
            context.startService(
                Intent(
                    context,
                    OfflineMapDownloadService::class.java
                )
                    .setAction(ACTION_DELETE)
                    .putExtra(EXTRA_REGION_ID, regionId)
            )
        }
    }
}
