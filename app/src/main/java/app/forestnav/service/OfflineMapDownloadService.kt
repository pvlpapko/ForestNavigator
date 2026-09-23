package app.forestnav.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

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
    private val runningJobs = ConcurrentHashMap<Long, JobSpec>()
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
        createChannel()
        manager = OfflineMapManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val regionId = intent.getLongExtra(EXTRA_REGION_ID, -1L)
                if (regionId > 0L) cancelByUser(regionId) else cancelAllByUser()
                return START_STICKY
            }

            ACTION_START -> {
                val requested = jobFromIntent(intent)
                val spec = chooseResumeJob(requested)
                startJob(spec)
                return START_STICKY
            }

            null -> {
                val saved = loadActiveJobs()
                if (saved.isEmpty()) {
                    stopIfIdle()
                    return START_NOT_STICKY
                }
                saved.forEach(::startJob)
                return START_STICKY
            }

            else -> return START_STICKY
        }
    }

    private fun startJob(spec: JobSpec) {
        if (runningJobs.putIfAbsent(spec.regionId, spec) != null) return

        saveJob(spec, active = true)
        OfflineMapDownloadState.publish(
            OfflineMapManager.DownloadProgress(
                regionId = spec.regionId,
                name = spec.name,
                layerTitle = spec.layer.title,
                active = true
            )
        )
        ensureForeground()

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
                progress.cancelled -> finishCancelled(spec)
                progress.error != null -> finishError(spec, progress)
                else -> updateAggregateNotification()
            }
        }
    }

    private fun chooseResumeJob(requested: JobSpec): JobSpec {
        val candidates = loadKnownJobs()
            .filter { it.regionId != requested.regionId && manager.hasPartial(it.regionId) }
            .sortedByDescending { it.regionId }

        val previous = candidates.firstOrNull { sameArea(it, requested) } ?: return requested
        return previous.copy(name = requested.name)
    }

    private fun sameArea(previous: JobSpec, requested: JobSpec): Boolean {
        val distanceMeters = FloatArray(1)
        Location.distanceBetween(
            previous.latitude,
            previous.longitude,
            requested.latitude,
            requested.longitude,
            distanceMeters
        )
        val allowedDriftMeters = maxOf(750.0, previous.radiusKm * 150.0)

        return previous.layer == requested.layer &&
            distanceMeters[0] <= allowedDriftMeters &&
            abs(previous.radiusKm - requested.radiusKm) < 0.01 &&
            previous.minZoom == requested.minZoom &&
            previous.maxZoom == requested.maxZoom
    }

    private fun finishSuccess(
        spec: JobSpec,
        progress: OfflineMapManager.DownloadProgress
    ) {
        if (runningJobs.remove(spec.regionId) == null) return
        clearJob(spec.regionId)

        val skippedText = if (progress.skippedResources > 0L) {
            " • пропущено ${progress.skippedResources}"
        } else ""
        notifyTerminal(
            id = terminalNotificationId(spec.regionId),
            title = "Карта скачана",
            text = "${spec.layer.title}: ${progress.completedResources} тайлов$skippedText"
        )
        updateAggregateNotification()
        stopIfIdle()
    }

    private fun finishCancelled(spec: JobSpec) {
        if (runningJobs.remove(spec.regionId) == null) return
        saveJob(spec, active = false)
        updateAggregateNotification()
        stopIfIdle()
    }

    private fun finishError(
        spec: JobSpec,
        progress: OfflineMapManager.DownloadProgress
    ) {
        if (runningJobs.remove(spec.regionId) == null) return
        saveJob(spec, active = false)
        notifyTerminal(
            id = terminalNotificationId(spec.regionId),
            title = "Загрузка карты приостановлена",
            text = progress.error ?: "Ошибка загрузки"
        )
        updateAggregateNotification()
        stopIfIdle()
    }

    private fun cancelByUser(regionId: Long) {
        val spec = runningJobs[regionId]
        if (spec != null) saveJob(spec, active = false)
        manager.cancelDownload(regionId, deletePartial = false)
    }

    private fun cancelAllByUser() {
        runningJobs.values.forEach { saveJob(it, active = false) }
        manager.cancelDownload(regionId = null, deletePartial = false)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val snapshot = runningJobs.values.toList()
        snapshot.forEach { saveJob(it, active = false) }
        manager.cancelDownload(regionId = null, deletePartial = false)
        snapshot.forEach { spec ->
            OfflineMapDownloadState.publish(
                OfflineMapManager.DownloadProgress(
                    regionId = spec.regionId,
                    name = spec.name,
                    layerTitle = spec.layer.title,
                    error = "Android остановил длительную фоновую загрузку. Уже скачанная часть сохранена."
                )
            )
        }
        runningJobs.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    override fun onDestroy() {
        if (runningJobs.isNotEmpty()) {
            runningJobs.values.forEach { saveJob(it, active = true) }
            manager.cancelDownload(regionId = null, deletePartial = false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground() {
        if (!foregroundStarted) {
            val fgsType = if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildAggregateNotification(),
                fgsType
            )
            foregroundStarted = true
        } else {
            updateAggregateNotification()
        }
    }

    private fun updateAggregateNotification() {
        if (!foregroundStarted) return
        val active = OfflineMapDownloadState.progress.value.values.filter { it.active }
        if (active.isEmpty() && runningJobs.isEmpty()) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildAggregateNotification())
    }

    private fun buildAggregateNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            2101,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this,
            2102,
            Intent(this, OfflineMapDownloadService::class.java)
                .setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val active = OfflineMapDownloadState.progress.value.values.filter { it.active }
        val count = maxOf(active.size, runningJobs.size)
        val completed = active.sumOf { it.completedResources }
        val required = active.sumOf { it.requiredResources }
        val bytes = active.sumOf { it.bytes }
        val percent = if (required > 0L) {
            ((completed * 100L) / required).toInt().coerceIn(0, 100)
        } else 0

        val text = when {
            count <= 0 -> "Подготовка загрузок"
            required > 0L ->
                "$count загрузки • $completed/$required тайлов • ${bytes / (1024L * 1024L)} МБ"
            else -> "$count загрузки • подготовка областей"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Офлайн-карты")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Пауза всех", cancelIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (required > 0L) setProgress(100, percent, false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    private fun notifyTerminal(id: Int, title: String, text: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            id + 1000,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        getSystemService(NotificationManager::class.java).notify(
            id,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun stopIfIdle() {
        if (runningJobs.isNotEmpty()) return
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
            ).apply {
                description = "Прогресс фоновой загрузки карт"
            }
        )
    }

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
        return p.getStringSet(KEY_ACTIVE_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull()?.let(::loadJob) }
    }

    private fun loadKnownJobs(): List<JobSpec> {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        return p.getStringSet(KEY_KNOWN_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull()?.let(::loadJob) }
    }

    private fun loadJob(regionId: Long): JobSpec? {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!p.contains(key(regionId, "layer"))) return null
        return runCatching {
            JobSpec(
                regionId = regionId,
                name = p.getString(key(regionId, "name"), "Офлайн-карта").orEmpty(),
                layer = MapLayer.valueOf(
                    p.getString(key(regionId, "layer"), MapLayer.MAP.name).orEmpty()
                ),
                latitude = p.getString(key(regionId, "lat"), "0")!!.toDouble(),
                longitude = p.getString(key(regionId, "lon"), "0")!!.toDouble(),
                radiusKm = p.getString(key(regionId, "radius"), "5")!!.toDouble(),
                minZoom = p.getString(key(regionId, "min_zoom"), "10")!!.toDouble(),
                maxZoom = p.getString(key(regionId, "max_zoom"), "17")!!.toDouble()
            )
        }.getOrNull()
    }

    private fun clearJob(regionId: Long) {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val known = p.getStringSet(KEY_KNOWN_IDS, emptySet()).orEmpty().toMutableSet()
        val activeIds = p.getStringSet(KEY_ACTIVE_IDS, emptySet()).orEmpty().toMutableSet()
        val id = regionId.toString()
        known -= id
        activeIds -= id

        p.edit()
            .putStringSet(KEY_KNOWN_IDS, known)
            .putStringSet(KEY_ACTIVE_IDS, activeIds)
            .remove(key(regionId, "name"))
            .remove(key(regionId, "layer"))
            .remove(key(regionId, "lat"))
            .remove(key(regionId, "lon"))
            .remove(key(regionId, "radius"))
            .remove(key(regionId, "min_zoom"))
            .remove(key(regionId, "max_zoom"))
            .apply()
    }

    private fun key(regionId: Long, field: String): String = "job_${regionId}_$field"

    private fun terminalNotificationId(regionId: Long): Int =
        3000 + (regionId xor (regionId ushr 32)).toInt().and(0x3fff)

    companion object {
        private const val CHANNEL_ID = "offline_map_download"
        private const val NOTIFICATION_ID = 2101
        private const val PREFS = "offline_map_download_jobs"
        private const val KEY_KNOWN_IDS = "known_ids"
        private const val KEY_ACTIVE_IDS = "active_ids"

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

        fun cancel(context: Context, regionId: Long? = null) {
            val intent = Intent(context, OfflineMapDownloadService::class.java)
                .setAction(ACTION_CANCEL)
            if (regionId != null) intent.putExtra(EXTRA_REGION_ID, regionId)
            context.startService(intent)
        }
    }
}
