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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.forestnav.MainActivity
import app.forestnav.R
import app.forestnav.map.MapLayer
import app.forestnav.map.OfflineMapManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

object OfflineMapDownloadState {
    private val _progress = MutableStateFlow<OfflineMapManager.DownloadProgress?>(null)
    val progress = _progress.asStateFlow()

    internal fun publish(value: OfflineMapManager.DownloadProgress?) {
        _progress.value = value
    }
}

class OfflineMapDownloadService : Service() {
    private lateinit var manager: OfflineMapManager
    private var running = false
    private var runningRegionId: Long? = null
    private var currentSpec: JobSpec? = null

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
        createChannel()
        manager = OfflineMapManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelByUser()
            return START_NOT_STICKY
        }

        val requested = intent?.takeIf { it.action == ACTION_START }?.let(::jobFromIntent)
        val saved = loadSavedJob(requireActive = intent == null)
        val spec = when {
            requested != null -> chooseResumeJob(requested)
            saved != null -> saved
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (running && runningRegionId == spec.regionId) return START_REDELIVER_INTENT
        if (running) manager.cancelDownload(deletePartial = false)

        saveJob(spec, active = true)
        running = true
        runningRegionId = spec.regionId
        currentSpec = spec
        OfflineMapDownloadState.publish(
            OfflineMapManager.DownloadProgress(regionId = spec.regionId, active = true)
        )

        val fgsType = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(spec, null),
            fgsType
        )

        manager.downloadAround(
            name = spec.name,
            layer = spec.layer,
            latitude = spec.latitude,
            longitude = spec.longitude,
            radiusKm = spec.radiusKm,
            minZoom = spec.minZoom,
            maxZoom = spec.maxZoom,
            regionId = spec.regionId
        ) { progress ->
            OfflineMapDownloadState.publish(progress)

            when {
                progress.complete -> finishSuccess(spec, progress)
                progress.cancelled -> finishCancelled()
                progress.error != null -> finishError(spec, progress)
                else -> updateNotification(spec, progress)
            }
        }

        return START_REDELIVER_INTENT
    }

    private fun chooseResumeJob(requested: JobSpec): JobSpec {
        val previous = loadSavedJob(requireActive = false) ?: return requested
        if (!manager.hasPartial(previous.regionId)) return requested

        val sameArea = previous.layer == requested.layer &&
            abs(previous.latitude - requested.latitude) < 0.001 &&
            abs(previous.longitude - requested.longitude) < 0.001 &&
            abs(previous.radiusKm - requested.radiusKm) < 0.01 &&
            previous.minZoom == requested.minZoom &&
            previous.maxZoom == requested.maxZoom

        return if (sameArea) requested.copy(regionId = previous.regionId) else requested
    }

    private fun updateNotification(spec: JobSpec, progress: OfflineMapManager.DownloadProgress) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildProgressNotification(spec, progress))
    }

    private fun finishSuccess(spec: JobSpec, progress: OfflineMapManager.DownloadProgress) {
        running = false
        runningRegionId = null
        currentSpec = null
        clearJob()
        stopForeground(STOP_FOREGROUND_DETACH)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildTerminalNotification(
                title = "Карта скачана",
                text = "${spec.layer.title}: ${progress.completedResources} тайлов"
            )
        )
        stopSelf()
    }

    private fun finishCancelled() {
        running = false
        runningRegionId = null
        currentSpec = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishError(spec: JobSpec, progress: OfflineMapManager.DownloadProgress) {
        running = false
        runningRegionId = null
        currentSpec = null
        saveJob(spec, active = false)
        stopForeground(STOP_FOREGROUND_DETACH)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildTerminalNotification(
                title = "Загрузка карты остановлена",
                text = progress.error ?: "Ошибка загрузки"
            )
        )
        stopSelf()
    }

    private fun cancelByUser() {
        currentSpec?.let { saveJob(it, active = false) }
        manager.cancelDownload(deletePartial = false)
        OfflineMapDownloadState.publish(
            OfflineMapManager.DownloadProgress(
                regionId = runningRegionId,
                cancelled = true
            )
        )
        running = false
        runningRegionId = null
        currentSpec = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.cancelDownload(deletePartial = false)
        currentSpec?.let { saveJob(it, active = false) }
        running = false
        runningRegionId = null
        currentSpec = null
        OfflineMapDownloadState.publish(
            OfflineMapManager.DownloadProgress(
                error = "Android остановил длительную фоновую загрузку. Уже скачанная часть сохранена."
            )
        )
        stopSelf()
    }

    override fun onDestroy() {
        if (running) manager.cancelDownload(deletePartial = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildProgressNotification(
        spec: JobSpec,
        progress: OfflineMapManager.DownloadProgress?
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            2101,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this,
            2102,
            Intent(this, OfflineMapDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val completed = progress?.completedResources ?: 0L
        val required = progress?.requiredResources ?: 0L
        val percent = if (required > 0L) {
            ((completed * 100L) / required).toInt().coerceIn(0, 100)
        } else 0
        val sizeMb = (progress?.bytes ?: 0L) / (1024L * 1024L)
        val text = if (required > 0L) {
            "$completed / $required тайлов • $sizeMb МБ"
        } else {
            "Подготовка области ${spec.radiusKm.toInt()} км"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Скачивание: ${spec.layer.title}")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Пауза", cancelIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (required > 0L) setProgress(100, percent, false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    private fun buildTerminalNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            2103,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Скачивание офлайн-карт",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Прогресс фоновой загрузки карт"
            }
        )
    }

    private fun jobFromIntent(intent: Intent): JobSpec {
        return JobSpec(
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
    }

    private fun saveJob(spec: JobSpec, active: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, active)
            .putLong(EXTRA_REGION_ID, spec.regionId)
            .putString(EXTRA_NAME, spec.name)
            .putString(EXTRA_LAYER, spec.layer.name)
            .putString(EXTRA_LAT, spec.latitude.toString())
            .putString(EXTRA_LON, spec.longitude.toString())
            .putString(EXTRA_RADIUS, spec.radiusKm.toString())
            .putString(EXTRA_MIN_ZOOM, spec.minZoom.toString())
            .putString(EXTRA_MAX_ZOOM, spec.maxZoom.toString())
            .apply()
    }

    private fun loadSavedJob(requireActive: Boolean): JobSpec? {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!p.contains(EXTRA_REGION_ID)) return null
        if (requireActive && !p.getBoolean(KEY_ACTIVE, false)) return null

        return runCatching {
            JobSpec(
                regionId = p.getLong(EXTRA_REGION_ID, 0L),
                name = p.getString(EXTRA_NAME, "Офлайн-карта").orEmpty(),
                layer = MapLayer.valueOf(p.getString(EXTRA_LAYER, MapLayer.MAP.name).orEmpty()),
                latitude = p.getString(EXTRA_LAT, "0")!!.toDouble(),
                longitude = p.getString(EXTRA_LON, "0")!!.toDouble(),
                radiusKm = p.getString(EXTRA_RADIUS, "5")!!.toDouble(),
                minZoom = p.getString(EXTRA_MIN_ZOOM, "10")!!.toDouble(),
                maxZoom = p.getString(EXTRA_MAX_ZOOM, "17")!!.toDouble()
            )
        }.getOrNull()
    }

    private fun clearJob() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
    }

    companion object {
        private const val CHANNEL_ID = "offline_map_download"
        private const val NOTIFICATION_ID = 2101
        private const val PREFS = "offline_map_download_job"
        private const val KEY_ACTIVE = "active"

        private const val ACTION_START = "app.forestnav.action.DOWNLOAD_OFFLINE_MAP"
        private const val ACTION_CANCEL = "app.forestnav.action.CANCEL_OFFLINE_MAP"
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
            val intent = Intent(context, OfflineMapDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REGION_ID, System.currentTimeMillis())
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_LAYER, layer.name)
                .putExtra(EXTRA_LAT, latitude)
                .putExtra(EXTRA_LON, longitude)
                .putExtra(EXTRA_RADIUS, radiusKm)
                .putExtra(EXTRA_MIN_ZOOM, minZoom)
                .putExtra(EXTRA_MAX_ZOOM, maxZoom)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, OfflineMapDownloadService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
