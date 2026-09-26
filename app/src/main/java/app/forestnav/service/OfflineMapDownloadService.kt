package app.forestnav.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.forestnav.MainActivity
import app.forestnav.R
import app.forestnav.map.MapLayer
import app.forestnav.map.MapStyles
import app.forestnav.map.OfflineMapManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

    internal fun markPaused(regionId: Long) {
        synchronized(lock) {
            val current = _progress.value[regionId]
                ?: return
            _progress.value =
                _progress.value +
                    (
                        regionId to current.copy(
                            active = false,
                            cancelled = true
                        )
                    )
        }
    }

    internal fun markActive(regionId: Long) {
        synchronized(lock) {
            val current = _progress.value[regionId]
                ?: return
            _progress.value =
                _progress.value +
                    (
                        regionId to current.copy(
                            active = true,
                            cancelled = false,
                            needsRetry = false,
                            error = null
                        )
                    )
        }
    }
}

/**
 * Direct tile downloader.
 *
 * This intentionally does not use MapLibre OfflineManager. The native offline
 * region database was the source of repeat crashes after pause/delete/restart
 * on some devices. Tiles are downloaded with OkHttp into an isolated directory
 * per region and MapLibre reads those files directly when the phone is offline.
 */
class OfflineMapDownloadService : Service() {

    private lateinit var manager: OfflineMapManager

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val automaticParallelism by lazy {
        calculateAutomaticParallelism()
    }

    private val downloadDispatcher by lazy {
        Dispatchers.IO.limitedParallelism(
            automaticParallelism
        )
    }

    private val running =
        ConcurrentHashMap<Long, Job>()

    private val activeCalls =
        ConcurrentHashMap<
            Long,
            MutableSet<okhttp3.Call>
        >()

    private val pendingResume =
        ConcurrentHashMap.newKeySet<Long>()

    private val deleting =
        ConcurrentHashMap.newKeySet<Long>()

    private val lastPublishAt =
        ConcurrentHashMap<Long, Long>()

    private val httpClient by lazy {
        val dispatcher = Dispatcher().apply {
            // No fixed app speed cap. The active request ceiling is derived
            // from this process' heap and file-descriptor budget.
            maxRequests = automaticParallelism
            maxRequestsPerHost = automaticParallelism
        }

        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(
                ConnectionPool(
                    automaticParallelism,
                    5,
                    TimeUnit.MINUTES
                )
            )
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        manager = OfflineMapManager(applicationContext)
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                val spec = specFromIntent(intent)
                val meta = manager.createRegion(
                    name = spec.name,
                    layer = spec.layer,
                    latitude = spec.latitude,
                    longitude = spec.longitude,
                    radiusKm = spec.radiusKm,
                    minZoom = spec.minZoom,
                    maxZoom = spec.maxZoom
                )
                launchDownload(meta)
            }

            ACTION_PAUSE -> {
                val id = intent.getLongExtra(EXTRA_REGION_ID, -1L)
                if (id > 0L) {
                    pauseRegion(id)
                } else {
                    pauseAll()
                }
            }

            ACTION_RESUME -> {
                val id = intent.getLongExtra(
                    EXTRA_REGION_ID,
                    -1L
                )

                if (id > 0L) {
                    OfflineMapDownloadState.markActive(id)
                    val existing = running[id]

                    if (existing != null) {
                        pendingResume += id
                    } else {
                        manager.loadMeta(id)
                            ?.let(::launchDownload)
                    }
                }
            }

            ACTION_DELETE -> {
                val id = intent.getLongExtra(EXTRA_REGION_ID, -1L)
                if (id > 0L) {
                    deleteRegion(id)
                }
            }

            ACTION_STOP_ALL -> {
                pauseAll()
            }

            ACTION_RESTORE, null -> {
                restoreState()
            }
        }

        return START_STICKY
    }

    private fun restoreState() {
        val metas = manager.activeOrPausedMetas()

        metas.forEach { meta ->
            val total = manager.totalTiles(meta)
            val (completed, bytes) = manager.completedStats(meta)

            OfflineMapDownloadState.publish(
                OfflineMapManager.DownloadProgress(
                    regionId = meta.id,
                    name = meta.name,
                    layerTitle = meta.layer.title,
                    completedResources = completed,
                    requiredResources = total,
                    bytes = bytes,
                    missingResources =
                        (total - completed).coerceAtLeast(0L),
                    currentPackageProgress =
                        percent(completed, total),
                    active = meta.status ==
                        OfflineMapManager.RegionStatus.ACTIVE,
                    cancelled = meta.status ==
                        OfflineMapManager.RegionStatus.PAUSED,
                    needsRetry = meta.status ==
                        OfflineMapManager.RegionStatus.ERROR
                )
            )

            if (meta.status == OfflineMapManager.RegionStatus.ACTIVE) {
                launchDownload(meta)
            }
        }

        stopIfIdle()
    }

    private fun launchDownload(
        originalMeta: OfflineMapManager.RegionMeta
    ) {
        if (running.containsKey(originalMeta.id)) return

        val meta = manager.updateStatus(
            originalMeta.id,
            OfflineMapManager.RegionStatus.ACTIVE
        ) ?: return

        deleting.remove(meta.id)
        ensureForeground()

        val job = scope.launch {
            val total = manager.totalTiles(meta)
            val completed = AtomicLong(0L)
            val bytes = AtomicLong(0L)
            val skipped = AtomicLong(0L)

            try {
                val (alreadyDone, existingBytes) =
                    manager.completedStats(meta)

                completed.set(alreadyDone)
                bytes.set(existingBytes)

                publish(
                    meta = meta,
                    completed = completed.get(),
                    total = total,
                    bytes = bytes.get(),
                    skipped = skipped.get(),
                    force = true
                )

                coroutineScope {
                    val queue =
                        Channel<OfflineMapManager.TileTask>(
                            capacity =
                                (
                                    automaticParallelism *
                                        TILE_QUEUE_MULTIPLIER
                                    ).coerceAtLeast(
                                    MIN_TILE_QUEUE_CAPACITY
                                )
                        )

                    val producer = launch {
                        try {
                            manager.tileTasks(meta).forEach { task ->
                                queue.send(task)
                            }
                        } finally {
                            queue.close()
                        }
                    }

                    val workerCount =
                        minOf(
                            automaticParallelism.toLong(),
                            total.coerceAtLeast(1L)
                        ).toInt()

                    val workers = List(workerCount) {
                        launch(downloadDispatcher) {
                            for (task in queue) {
                                if (
                                    task.file.isFile &&
                                    task.file.length() > MIN_TILE_BYTES
                                ) {
                                    continue
                                }

                                val skipMarker =
                                    File(
                                        task.file.parentFile,
                                        task.file.name + SKIP_SUFFIX
                                    )

                                if (skipMarker.isFile) {
                                    skipped.incrementAndGet()
                                    publish(
                                        meta = meta,
                                        completed = completed.get(),
                                        total = total,
                                        bytes = bytes.get(),
                                        skipped = skipped.get()
                                    )
                                    continue
                                }

                                when (
                                    val result =
                                        downloadTile(meta, task)
                                ) {
                                    is TileResult.Success -> {
                                        completed.incrementAndGet()
                                        bytes.addAndGet(result.bytes)
                                    }

                                    TileResult.Skip -> {
                                        task.file.parentFile?.mkdirs()
                                        skipMarker.writeText("skip")
                                        skipped.incrementAndGet()
                                    }
                                }

                                publish(
                                    meta = meta,
                                    completed = completed.get(),
                                    total = total,
                                    bytes = bytes.get(),
                                    skipped = skipped.get()
                                )
                            }
                        }
                    }

                    producer.join()
                    workers.joinAll()
                }

                manager.markComplete(meta.id)

                publish(
                    meta = meta,
                    completed = completed.get(),
                    total = total,
                    bytes = bytes.get(),
                    skipped = skipped.get(),
                    complete = true,
                    active = false,
                    force = true
                )

                notifyTerminal(
                    terminalNotificationId(meta.id),
                    "Офлайн-карта готова",
                    meta.name
                )
            } catch (_: CancellationException) {
                if (!deleting.contains(meta.id)) {
                    manager.updateStatus(
                        meta.id,
                        OfflineMapManager.RegionStatus.PAUSED
                    )

                    publish(
                        meta = meta,
                        completed = completed.get(),
                        total = total,
                        bytes = bytes.get(),
                        skipped = skipped.get(),
                        active = false,
                        cancelled = true,
                        force = true
                    )
                }
            } catch (t: Throwable) {
                if (!deleting.contains(meta.id)) {
                    manager.updateStatus(
                        meta.id,
                        OfflineMapManager.RegionStatus.ERROR
                    )

                    publish(
                        meta = meta,
                        completed = completed.get(),
                        total = total,
                        bytes = bytes.get(),
                        skipped = skipped.get(),
                        active = false,
                        needsRetry = true,
                        error =
                            t.message ?: "Ошибка загрузки тайлов",
                        force = true
                    )
                }
            } finally {
                running.remove(meta.id)
                cancelCalls(meta.id)
                lastPublishAt.remove(meta.id)

                when {
                    deleting.contains(meta.id) -> {
                        finishDelete(meta.id)
                    }

                    pendingResume.remove(meta.id) -> {
                        manager.loadMeta(meta.id)
                            ?.let(::launchDownload)
                    }

                    else -> {
                        updateAggregateNotification()
                        stopIfIdle()
                    }
                }
            }
        }

        running[meta.id] = job
    }

    private sealed interface TileResult {
        data class Success(val bytes: Long) : TileResult
        data object Skip : TileResult
    }

    private fun downloadTile(
        meta: OfflineMapManager.RegionMeta,
        task: OfflineMapManager.TileTask
    ): TileResult {
        val url = MapStyles.tileUrl(
            source = task.source,
            z = task.z,
            x = task.x,
            y = task.y
        )

        var lastError: Throwable? = null

        repeat(TILE_RETRIES) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "ForestNavigator/1.7.3")
                    .build()

                val call = httpClient.newCall(request)
                registerCall(meta.id, call)

                try {
                    call.execute().use { response ->
                    when {
                        response.code == 404 ||
                            response.code == 204 -> {
                            return TileResult.Skip
                        }

                        response.code == 401 ||
                            response.code == 403 -> {
                            throw IllegalStateException(
                                "Провайдер отклонил доступ к карте: HTTP ${response.code}"
                            )
                        }

                        response.isSuccessful -> {
                            val body = response.body
                                ?: throw IllegalStateException(
                                    "Провайдер вернул пустой ответ"
                                )

                            task.file.parentFile?.mkdirs()

                            val temp = File(
                                task.file.parentFile,
                                task.file.name + ".part"
                            )

                            val written = body.byteStream().use { input ->
                                FileOutputStream(temp).use { output ->
                                    input.copyTo(
                                        output,
                                        NETWORK_BUFFER_BYTES
                                    )
                                }
                            }

                            if (written <= MIN_TILE_BYTES) {
                                temp.delete()
                                return TileResult.Skip
                            }

                            // Only boundary tiles are decoded. Interior tiles
                            // stay byte-for-byte as received, which avoids GC
                            // pressure while 64 workers are downloading.
                            val nominalClip =
                                manager.tileClip(
                                    meta = meta,
                                    task = task
                                )

                            if (
                                !nominalClip.isFull(
                                    task.source.tileSize,
                                    task.source.tileSize
                                )
                            ) {
                                cropBoundaryTile(
                                    meta = meta,
                                    task = task,
                                    file = temp
                                )
                            }

                            if (task.file.exists()) {
                                task.file.delete()
                            }

                            check(temp.renameTo(task.file)) {
                                "Не удалось сохранить тайл"
                            }

                            return TileResult.Success(
                                task.file.length()
                            )
                        }

                        response.code == 429 ||
                            response.code >= 500 -> {
                            lastError = IllegalStateException(
                                "HTTP ${response.code}"
                            )
                        }

                        else -> {
                            lastError = IllegalStateException(
                                "HTTP ${response.code}"
                            )
                        }
                    }
                    }
                } finally {
                    unregisterCall(meta.id, call)
                }
            } catch (t: Throwable) {
                lastError = t
            }

            if (attempt + 1 < TILE_RETRIES) {
                Thread.sleep(
                    RETRY_DELAYS_MS[
                        attempt.coerceAtMost(
                            RETRY_DELAYS_MS.lastIndex
                        )
                    ]
                )
            }
        }

        throw IllegalStateException(
            "Не удалось скачать тайл: " +
                (lastError?.message ?: "неизвестная ошибка"),
            lastError
        )
    }

    private fun cropBoundaryTile(
        meta: OfflineMapManager.RegionMeta,
        task: OfflineMapManager.TileTask,
        file: File
    ) {
        val sourceBitmap =
            BitmapFactory.decodeFile(file.absolutePath)
                ?: return

        try {
            val clip = manager.tileClip(
                meta = meta,
                task = task,
                width = sourceBitmap.width,
                height = sourceBitmap.height
            )

            if (
                clip.isFull(
                    sourceBitmap.width,
                    sourceBitmap.height
                )
            ) {
                return
            }

            val masked = Bitmap.createBitmap(
                sourceBitmap.width,
                sourceBitmap.height,
                Bitmap.Config.ARGB_8888
            )

            try {
                val canvas = Canvas(masked)
                canvas.clipRect(
                    clip.left,
                    clip.top,
                    clip.right,
                    clip.bottom
                )
                canvas.drawBitmap(
                    sourceBitmap,
                    0f,
                    0f,
                    null
                )

                val cropped = File(
                    file.parentFile,
                    file.name + ".crop"
                )

                FileOutputStream(cropped).use { output ->
                    check(
                        masked.compress(
                            Bitmap.CompressFormat.PNG,
                            100,
                            output
                        )
                    ) {
                        "Не удалось обрезать край тайла"
                    }
                }

                file.delete()
                check(cropped.renameTo(file)) {
                    "Не удалось заменить краевой тайл"
                }
            } finally {
                masked.recycle()
            }
        } finally {
            sourceBitmap.recycle()
        }
    }

    private fun pauseRegion(id: Long) {
        OfflineMapDownloadState.markPaused(id)
        pendingResume.remove(id)

        manager.updateStatus(
            id,
            OfflineMapManager.RegionStatus.PAUSED
        )

        cancelCalls(id)
        running[id]?.cancel()

        scope.launch {
            running[id]?.join()
            stopIfIdle()
        }
    }

    private fun pauseAll() {
        val ids = running.keys.toList()

        ids.forEach { id ->
            OfflineMapDownloadState.markPaused(id)
            pendingResume.remove(id)
            manager.updateStatus(
                id,
                OfflineMapManager.RegionStatus.PAUSED
            )
            cancelCalls(id)
            running[id]?.cancel()
        }

        scope.launch {
            ids.mapNotNull { running[it] }
                .joinAll()
            stopIfIdle()
        }
    }

    private fun deleteRegion(id: Long) {
        deleting += id
        pendingResume.remove(id)

        OfflineMapDownloadState.remove(id)
        cancelCalls(id)
        running[id]?.cancel()

        val job = running[id]
        if (job == null) {
            finishDelete(id)
        }
    }

    private fun finishDelete(id: Long) {
        scope.launch {
            manager.deleteRegion(
                id = id,
                onSuccess = {
                    OfflineMapDownloadState.remove(id)
                    getSystemService(
                        NotificationManager::class.java
                    ).cancel(
                        terminalNotificationId(id)
                    )
                },
                onError = {}
            )

            deleting.remove(id)
            lastPublishAt.remove(id)
            stopIfIdle()
        }
    }

    private fun registerCall(
        regionId: Long,
        call: okhttp3.Call
    ) {
        val set = activeCalls.getOrPut(regionId) {
            ConcurrentHashMap.newKeySet()
        }
        set += call
    }

    private fun unregisterCall(
        regionId: Long,
        call: okhttp3.Call
    ) {
        activeCalls[regionId]?.let { set ->
            set -= call
            if (set.isEmpty()) {
                activeCalls.remove(regionId, set)
            }
        }
    }

    private fun cancelCalls(regionId: Long) {
        activeCalls.remove(regionId)
            ?.forEach { call ->
                runCatching { call.cancel() }
            }
    }

    private fun publish(
        meta: OfflineMapManager.RegionMeta,
        completed: Long,
        total: Long,
        bytes: Long,
        skipped: Long = 0L,
        complete: Boolean = false,
        active: Boolean = true,
        cancelled: Boolean = false,
        needsRetry: Boolean = false,
        error: String? = null,
        force: Boolean = false
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastPublishAt[meta.id] ?: 0L

        if (!force &&
            now - last < PROGRESS_PUBLISH_INTERVAL_MS
        ) {
            return
        }

        lastPublishAt[meta.id] = now

        val done = completed + skipped
        val missing =
            (total - done).coerceAtLeast(0L)

        OfflineMapDownloadState.publish(
            OfflineMapManager.DownloadProgress(
                regionId = meta.id,
                name = meta.name,
                layerTitle = meta.layer.title,
                completedResources = completed,
                requiredResources = total,
                bytes = bytes,
                skippedResources = skipped,
                missingResources = missing,
                currentPackageProgress =
                    percent(done, total),
                needsRetry = needsRetry,
                complete = complete,
                active = active,
                cancelled = cancelled,
                error = error
            )
        )

        updateAggregateNotification()
    }

    private fun percent(
        done: Long,
        total: Long
    ): Int =
        if (total <= 0L) {
            0
        } else {
            ((done * 100L) / total)
                .toInt()
                .coerceIn(0, 100)
        }

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

        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            buildNotification()
        )
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

        val pauseAllIntent = PendingIntent.getService(
            this,
            2102,
            Intent(
                this,
                OfflineMapDownloadService::class.java
            ).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val active =
            OfflineMapDownloadState.progress.value.values
                .filter { it.active }

        val completed =
            active.sumOf {
                it.completedResources +
                    it.skippedResources
            }

        val required =
            active.sumOf { it.requiredResources }

        val bytes =
            active.sumOf { it.bytes }

        val text = when {
            active.isEmpty() ->
                "Подготовка загрузки…"

            required > 0L ->
                "${active.size} загруз. • " +
                    "$completed/$required тайлов • " +
                    "${bytes / 1_048_576L} МБ"

            else ->
                "${active.size} загруз. • подготовка…"
        }

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Офлайн-карты")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Пауза всех",
                pauseAllIntent
            )
            .setCategory(
                NotificationCompat.CATEGORY_PROGRESS
            )
            .apply {
                if (required > 0L) {
                    setProgress(
                        100,
                        percent(completed, required),
                        false
                    )
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun stopIfIdle() {
        if (running.isNotEmpty()) return

        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }

        stopSelf()
    }

    private fun notifyTerminal(
        id: Int,
        title: String,
        text: String
    ) {
        getSystemService(
            NotificationManager::class.java
        ).notify(
            id,
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun createChannel() {
        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Скачивание офлайн-карт",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun specFromIntent(
        intent: Intent
    ): JobSpec =
        JobSpec(
            name = intent.getStringExtra(EXTRA_NAME)
                .orEmpty()
                .ifBlank { "Офлайн-карта" },
            layer = runCatching {
                MapLayer.valueOf(
                    intent.getStringExtra(EXTRA_LAYER)
                        .orEmpty()
                )
            }.getOrDefault(MapLayer.MAP),
            latitude =
                intent.getDoubleExtra(EXTRA_LAT, 0.0),
            longitude =
                intent.getDoubleExtra(EXTRA_LON, 0.0),
            radiusKm =
                intent.getDoubleExtra(EXTRA_RADIUS, 5.0),
            minZoom =
                intent.getIntExtra(EXTRA_MIN_ZOOM, 10),
            maxZoom =
                intent.getIntExtra(EXTRA_MAX_ZOOM, 18)
        )

    override fun onBind(intent: Intent?): IBinder? =
        null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class JobSpec(
        val name: String,
        val layer: MapLayer,
        val latitude: Double,
        val longitude: Double,
        val radiusKm: Double,
        val minZoom: Int,
        val maxZoom: Int
    )

    private fun calculateAutomaticParallelism(): Int {
        val runtime = Runtime.getRuntime()

        val heapBudget =
            (
                (
                    runtime.maxMemory() *
                        HEAP_BUDGET_PERCENT
                    ) /
                    100L /
                    ESTIMATED_ACTIVE_REQUEST_BYTES
                )
                .coerceAtLeast(
                    MIN_AUTO_PARALLELISM.toLong()
                )
                .coerceAtMost(Int.MAX_VALUE.toLong())
            ).toInt()

        val openFdCount =
            File("/proc/self/fd")
                .list()
                ?.size
                ?: FALLBACK_OPEN_FD_COUNT

        val fdLimit =
            readSoftFileDescriptorLimit()

        val fdBudget =
            (
                (
                    fdLimit.toLong() -
                        openFdCount.toLong() -
                        FD_RESERVE.toLong()
                    ) /
                    FD_PER_ACTIVE_REQUEST
                )
                .coerceAtLeast(
                    MIN_AUTO_PARALLELISM.toLong()
                )
                .coerceAtMost(Int.MAX_VALUE.toLong())
            ).toInt()

        return minOf(
            heapBudget,
            fdBudget
        ).coerceAtLeast(
            MIN_AUTO_PARALLELISM
        )
    }

    private fun readSoftFileDescriptorLimit(): Int {
        return runCatching {
            val line =
                File("/proc/self/limits")
                    .useLines { lines ->
                        lines.firstOrNull {
                            it.startsWith("Max open files")
                        }
                    }
                    ?: return@runCatching FALLBACK_FD_LIMIT

            val match =
                Regex(
                    """Max open files\s+(\d+|unlimited)"""
                ).find(line)
                    ?: return@runCatching FALLBACK_FD_LIMIT

            val raw = match.groupValues[1]

            if (raw == "unlimited") {
                FALLBACK_UNLIMITED_FD_BUDGET
            } else {
                raw.toLong()
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            }
        }.getOrDefault(
            FALLBACK_FD_LIMIT
        )
    }

    companion object {
        private const val CHANNEL_ID =
            "direct_tile_download_v8"

        private const val NOTIFICATION_ID = 2101

        private const val ACTION_START =
            "app.forestnav.action.DOWNLOAD_DIRECT_TILES"

        private const val ACTION_PAUSE =
            "app.forestnav.action.PAUSE_DIRECT_TILES"

        private const val ACTION_RESUME =
            "app.forestnav.action.RESUME_DIRECT_TILES"

        private const val ACTION_DELETE =
            "app.forestnav.action.DELETE_DIRECT_TILES"

        private const val ACTION_RESTORE =
            "app.forestnav.action.RESTORE_DIRECT_TILES"

        private const val ACTION_STOP_ALL =
            "app.forestnav.action.STOP_ALL_DIRECT_TILES"

        private const val EXTRA_NAME = "name"
        private const val EXTRA_LAYER = "layer"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_RADIUS = "radius"
        private const val EXTRA_MIN_ZOOM = "min_zoom"
        private const val EXTRA_MAX_ZOOM = "max_zoom"
        private const val EXTRA_REGION_ID = "region_id"

        private const val MIN_AUTO_PARALLELISM = 64
        private const val HEAP_BUDGET_PERCENT = 35L
        private const val ESTIMATED_ACTIVE_REQUEST_BYTES =
            768L * 1024L
        private const val FD_RESERVE = 128
        private const val FD_PER_ACTIVE_REQUEST = 2L
        private const val FALLBACK_OPEN_FD_COUNT = 64
        private const val FALLBACK_FD_LIMIT = 1024
        private const val FALLBACK_UNLIMITED_FD_BUDGET = 4096
        private const val TILE_QUEUE_MULTIPLIER = 8
        private const val MIN_TILE_QUEUE_CAPACITY = 512
        private const val TILE_RETRIES = 4
        private const val MIN_TILE_BYTES = 128L
        private const val NETWORK_BUFFER_BYTES = 64 * 1024
        private const val SKIP_SUFFIX = ".skip"
        private const val PROGRESS_PUBLISH_INTERVAL_MS = 750L

        private val RETRY_DELAYS_MS =
            longArrayOf(
                700L,
                1_500L,
                3_000L,
                6_000L
            )

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
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_LAYER, layer.name)
                    .putExtra(EXTRA_LAT, latitude)
                    .putExtra(EXTRA_LON, longitude)
                    .putExtra(EXTRA_RADIUS, radiusKm)
                    .putExtra(
                        EXTRA_MIN_ZOOM,
                        minZoom.toInt()
                    )
                    .putExtra(
                        EXTRA_MAX_ZOOM,
                        maxZoom.toInt()
                    )
            )
        }

        fun cancel(
            context: Context,
            regionId: Long? = null
        ) {
            val intent = Intent(
                context,
                OfflineMapDownloadService::class.java
            ).setAction(ACTION_PAUSE)

            if (regionId != null) {
                intent.putExtra(
                    EXTRA_REGION_ID,
                    regionId
                )
            }

            context.startService(intent)
        }

        fun resume(
            context: Context,
            regionId: Long
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(
                    context,
                    OfflineMapDownloadService::class.java
                )
                    .setAction(ACTION_RESUME)
                    .putExtra(
                        EXTRA_REGION_ID,
                        regionId
                    )
            )
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
                    .putExtra(
                        EXTRA_REGION_ID,
                        regionId
                    )
            )
        }

        fun restore(context: Context) {
            context.startService(
                Intent(
                    context,
                    OfflineMapDownloadService::class.java
                ).setAction(ACTION_RESTORE)
            )
        }

        fun stopAll(context: Context) {
            context.startService(
                Intent(
                    context,
                    OfflineMapDownloadService::class.java
                ).setAction(ACTION_STOP_ALL)
            )
        }
    }

    private fun terminalNotificationId(
        regionId: Long
    ): Int =
        3000 +
            (
                regionId xor
                    (regionId ushr 32)
                )
                .toInt()
                .and(0x3fff)
}
