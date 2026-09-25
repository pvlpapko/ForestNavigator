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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import java.util.concurrent.ConcurrentHashMap

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

class OfflineMapDownloadService : Service() {

    private lateinit var manager: OfflineMapManager

    private val observedRegions =
        ConcurrentHashMap<Long, OfflineRegion>()
    private val regionMeta =
        ConcurrentHashMap<Long, OfflineMapManager.RegionMeta>()
    private val lastPublishAt =
        ConcurrentHashMap<Long, Long>()

    private val pendingUntilDatabaseReady =
        mutableListOf<() -> Unit>()

    private var databaseReady = false
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        manager = OfflineMapManager(applicationContext)
        createChannel()

        manager.prepareCleanDatabase(
            onReady = {
                databaseReady = true
                val pending = pendingUntilDatabaseReady.toList()
                pendingUntilDatabaseReady.clear()
                pending.forEach { it() }
                restoreActiveDownloads()
            },
            onError = {
                databaseReady = true
                val pending = pendingUntilDatabaseReady.toList()
                pendingUntilDatabaseReady.clear()
                pending.forEach { it() }
                restoreActiveDownloads()
            }
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureForeground()
                val spec = jobFromIntent(intent)
                whenDatabaseReady { createDownload(spec) }
            }

            ACTION_PAUSE -> {
                val id = intent.getLongExtra(EXTRA_REGION_ID, -1L)
                if (id > 0L) {
                    whenDatabaseReady { pauseRegion(id) }
                } else {
                    whenDatabaseReady { pauseAll() }
                }
            }

            ACTION_RESUME -> {
                val id = intent.getLongExtra(EXTRA_REGION_ID, -1L)
                if (id > 0L) {
                    ensureForeground()
                    whenDatabaseReady { resumeRegion(id) }
                }
            }

            ACTION_DELETE -> {
                val id = intent.getLongExtra(EXTRA_REGION_ID, -1L)
                if (id > 0L) {
                    whenDatabaseReady { deleteRegion(id) }
                }
            }

            null -> {
                whenDatabaseReady {
                    restoreActiveDownloads()
                    stopIfIdle()
                }
            }
        }

        return START_STICKY
    }

    private fun createDownload(spec: JobSpec) {
        manager.createRegion(
            name = spec.name,
            layer = spec.layer,
            latitude = spec.latitude,
            longitude = spec.longitude,
            radiusKm = spec.radiusKm,
            minZoom = spec.minZoom,
            maxZoom = spec.maxZoom,
            onCreated = { region, meta ->
                regionMeta[region.id] = meta
                markActive(region.id, true)
                observeAndStart(region, meta)
            },
            onError = { error ->
                notifyTerminal(
                    id = ERROR_NOTIFICATION_ID,
                    title = "Не удалось начать загрузку",
                    text = error
                )
                stopIfIdle()
            }
        )
    }

    private fun observeAndStart(
        region: OfflineRegion,
        meta: OfflineMapManager.RegionMeta
    ) {
        observedRegions[region.id] = region
        regionMeta[region.id] = meta
        region.setDeliverInactiveMessages(true)
        region.setObserver(
            object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    publishStatus(
                        region = region,
                        meta = meta,
                        status = status
                    )

                    if (status.isComplete) {
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        markActive(region.id, false)
                        region.setObserver(null)
                        observedRegions.remove(region.id)
                        lastPublishAt.remove(region.id)

                        notifyTerminal(
                            id = terminalNotificationId(region.id),
                            title = "Офлайн-карта готова",
                            text = meta.name
                        )

                        manager.packDatabase()
                        stopIfIdle()
                    }
                }

                override fun onError(error: OfflineRegionError) {
                    val previous =
                        OfflineMapDownloadState.progress.value[region.id]

                    OfflineMapDownloadState.publish(
                        OfflineMapManager.DownloadProgress(
                            regionId = region.id,
                            name = meta.name,
                            layerTitle = meta.layer.title,
                            completedResources =
                                previous?.completedResources ?: 0L,
                            requiredResources =
                                previous?.requiredResources ?: 0L,
                            bytes = previous?.bytes ?: 0L,
                            currentPackageProgress =
                                previous?.currentPackageProgress ?: 0,
                            missingResources =
                                previous?.missingResources ?: 0L,
                            needsRetry = true,
                            active = true,
                            error =
                                "Временная ошибка загрузки: ${error.message}"
                        )
                    )
                    updateAggregateNotification()
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    manager.configureFastDownloads()
                    updateAggregateNotification()
                }
            }
        )

        OfflineMapDownloadState.publish(
            OfflineMapManager.DownloadProgress(
                regionId = region.id,
                name = meta.name,
                layerTitle = meta.layer.title,
                active = true
            )
        )
        updateAggregateNotification()
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private fun publishStatus(
        region: OfflineRegion,
        meta: OfflineMapManager.RegionMeta,
        status: OfflineRegionStatus,
        force: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        val last = lastPublishAt[region.id] ?: 0L

        if (!force &&
            !status.isComplete &&
            now - last < PROGRESS_PUBLISH_INTERVAL_MS
        ) {
            return
        }

        lastPublishAt[region.id] = now

        val required = status.requiredResourceCount
        val completed = status.completedResourceCount
        val percent = if (required > 0L) {
            ((completed * 100L) / required)
                .toInt()
                .coerceIn(0, 100)
        } else {
            0
        }

        val active =
            !status.isComplete &&
                status.downloadState == OfflineRegion.STATE_ACTIVE

        OfflineMapDownloadState.publish(
            OfflineMapManager.DownloadProgress(
                regionId = region.id,
                name = meta.name,
                layerTitle = meta.layer.title,
                completedResources = completed,
                requiredResources = required,
                bytes = status.completedResourceSize,
                missingResources =
                    (required - completed).coerceAtLeast(0L),
                currentPackageProgress = percent,
                complete = status.isComplete,
                active = active,
                cancelled =
                    !status.isComplete &&
                        status.downloadState ==
                        OfflineRegion.STATE_INACTIVE
            )
        )

        updateAggregateNotification()
    }

    private fun restoreActiveDownloads() {
        val ids = activeIds()
        if (ids.isEmpty()) {
            stopIfIdle()
            return
        }

        ensureForeground()

        ids.forEach { id ->
            manager.getRegion(
                regionId = id,
                onSuccess = { region ->
                    val meta = manager.decodeMeta(region)
                    if (meta == null ||
                        meta.schema != OfflineMapManager.MAP_SCHEMA
                    ) {
                        markActive(id, false)
                        stopIfIdle()
                    } else if (!observedRegions.containsKey(id)) {
                        observeAndStart(region, meta)
                    }
                },
                onError = {
                    markActive(id, false)
                    stopIfIdle()
                }
            )
        }
    }

    private fun pauseRegion(id: Long) {
        manager.getRegion(
            regionId = id,
            onSuccess = { region ->
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                markActive(id, false)

                region.getStatus(
                    object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            val meta =
                                manager.decodeMeta(region)
                                    ?: regionMeta[id]
                                    ?: return
                            status?.let {
                                publishStatus(
                                    region,
                                    meta,
                                    it,
                                    force = true
                                )
                            }
                            region.setObserver(null)
                            observedRegions.remove(id)
                            stopIfIdle()
                        }

                        override fun onError(error: String?) {
                            region.setObserver(null)
                            observedRegions.remove(id)
                            stopIfIdle()
                        }
                    }
                )
            },
            onError = {
                markActive(id, false)
                stopIfIdle()
            }
        )
    }

    private fun resumeRegion(id: Long) {
        manager.getRegion(
            regionId = id,
            onSuccess = { region ->
                val meta = manager.decodeMeta(region)
                if (meta == null ||
                    meta.schema != OfflineMapManager.MAP_SCHEMA
                ) {
                    notifyTerminal(
                        terminalNotificationId(id),
                        "Не удалось продолжить",
                        "Эта область создана старой версией приложения."
                    )
                    stopIfIdle()
                } else {
                    ensureForeground()
                    markActive(id, true)
                    observeAndStart(region, meta)
                }
            },
            onError = {
                notifyTerminal(
                    terminalNotificationId(id),
                    "Не удалось продолжить",
                    it
                )
                stopIfIdle()
            }
        )
    }

    private fun deleteRegion(id: Long) {
        observedRegions.remove(id)?.let { region ->
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            region.setObserver(null)
        }

        markActive(id, false)

        manager.deleteRegion(
            id = id,
            onSuccess = {
                regionMeta.remove(id)
                lastPublishAt.remove(id)
                OfflineMapDownloadState.remove(id)
                getSystemService(NotificationManager::class.java)
                    .cancel(terminalNotificationId(id))
                stopIfIdle()
            },
            onError = {
                stopIfIdle()
            }
        )
    }

    private fun pauseAll() {
        val ids = activeIds().toList()
        if (ids.isEmpty()) {
            stopIfIdle()
            return
        }
        ids.forEach(::pauseRegion)
    }

    private fun whenDatabaseReady(action: () -> Unit) {
        if (databaseReady) {
            action()
        } else {
            pendingUntilDatabaseReady += action
        }
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

    private fun stopIfIdle() {
        if (activeIds().isNotEmpty()) return
        if (pendingUntilDatabaseReady.isNotEmpty()) return

        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }

        stopSelf()
    }

    private fun updateAggregateNotification() {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java)
            .notify(
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
            Intent(this, OfflineMapDownloadService::class.java)
                .setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val active =
            OfflineMapDownloadState.progress.value.values
                .filter { it.active }

        val completed = active.sumOf { it.completedResources }
        val required = active.sumOf { it.requiredResources }
        val bytes = active.sumOf { it.bytes }

        val text = when {
            active.isEmpty() ->
                "Подготовка прямой загрузки карты…"

            required > 0L ->
                "${active.size} загруз. • " +
                    "$completed/$required ресурсов • " +
                    "${bytes / 1_048_576L} МБ"

            else ->
                "${active.size} загруз. • получение списка ресурсов…"
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
                pauseAllIntent
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (required > 0L) {
                    val progress =
                        ((completed * 100L) /
                            required.coerceAtLeast(1L))
                            .toInt()
                            .coerceIn(0, 100)
                    setProgress(100, progress, false)
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
        getSystemService(NotificationManager::class.java)
            .notify(
                id,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_app)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build()
            )
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

    private fun markActive(id: Long, active: Boolean) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val ids = prefs
            .getStringSet(KEY_ACTIVE_IDS, emptySet())
            .orEmpty()
            .toMutableSet()

        if (active) {
            ids += id.toString()
        } else {
            ids -= id.toString()
        }

        prefs.edit()
            .putStringSet(KEY_ACTIVE_IDS, ids)
            .apply()
    }

    private fun activeIds(): Set<Long> =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(KEY_ACTIVE_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    private fun jobFromIntent(intent: Intent): JobSpec =
        JobSpec(
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
            maxZoom = intent.getDoubleExtra(EXTRA_MAX_ZOOM, 18.0)
        )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observedRegions.values.forEach { region ->
            region.setObserver(null)
        }
        observedRegions.clear()
        super.onDestroy()
    }

    private data class JobSpec(
        val name: String,
        val layer: MapLayer,
        val latitude: Double,
        val longitude: Double,
        val radiusKm: Double,
        val minZoom: Double,
        val maxZoom: Double
    )

    companion object {
        private const val CHANNEL_ID = "offline_map_download_v6"
        private const val NOTIFICATION_ID = 2101
        private const val ERROR_NOTIFICATION_ID = 2199
        private const val PREFS = "maplibre_offline_jobs_v6"
        private const val KEY_ACTIVE_IDS = "active_ids"
        private const val PROGRESS_PUBLISH_INTERVAL_MS = 250L

        private const val ACTION_START =
            "app.forestnav.action.DOWNLOAD_OFFLINE_MAP"
        private const val ACTION_PAUSE =
            "app.forestnav.action.PAUSE_OFFLINE_MAP"
        private const val ACTION_RESUME =
            "app.forestnav.action.RESUME_OFFLINE_MAP"
        private const val ACTION_DELETE =
            "app.forestnav.action.DELETE_OFFLINE_MAP"

        private const val EXTRA_NAME = "name"
        private const val EXTRA_LAYER = "layer"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_RADIUS = "radius"
        private const val EXTRA_MIN_ZOOM = "min_zoom"
        private const val EXTRA_MAX_ZOOM = "max_zoom"
        private const val EXTRA_REGION_ID = "region_id"

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
            ).setAction(ACTION_PAUSE)

            if (regionId != null) {
                intent.putExtra(EXTRA_REGION_ID, regionId)
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
                    .putExtra(EXTRA_REGION_ID, regionId)
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
                    .putExtra(EXTRA_REGION_ID, regionId)
            )
        }
    }

    private fun terminalNotificationId(regionId: Long): Int =
        3000 +
            (regionId xor (regionId ushr 32))
                .toInt()
                .and(0x3fff)
}
