package app.forestnav.service

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.forestnav.ForestNavApplication
import app.forestnav.MainActivity
import app.forestnav.data.TrackPoint
import app.forestnav.gnss.GnssEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object TrackRecordingState {
    private val _recording = MutableStateFlow(false)
    val recording = _recording.asStateFlow()

    private val _points =
        MutableStateFlow<List<TrackPoint>>(emptyList())
    val points = _points.asStateFlow()

    private val _trackId =
        MutableStateFlow<Long?>(null)
    val trackId = _trackId.asStateFlow()

    internal fun setRecording(value: Boolean) {
        _recording.value = value
    }

    internal fun replacePoints(points: List<TrackPoint>) {
        _points.value = points
    }

    internal fun setTrackId(value: Long?) {
        _trackId.value = value
    }

    internal fun clearIfTrack(trackId: Long) {
        if (_trackId.value == trackId) {
            _points.value = emptyList()
            _trackId.value = null
        }
    }

    internal fun append(point: TrackPoint) {
        _points.value = _points.value + point
    }
}

class TrackRecordingService : Service() {
    private lateinit var gnss: GnssEngine
    private var trackId: Long = -1L
    private var lastAccepted: Location? = null
    private var collectorThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var collecting = false

    private val prefs by lazy {
        getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        gnss = GnssEngine(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            else -> startOrResumeRecording()
        }
        return START_STICKY
    }

    private fun startOrResumeRecording() {
        if (collecting) return

        val app = application as ForestNavApplication
        val savedTrackId =
            prefs.getLong(KEY_TRACK_ID, -1L)
        val wasRecording =
            prefs.getBoolean(KEY_RECORDING, false)

        trackId =
            if (wasRecording && savedTrackId > 0L) {
                savedTrackId
            } else {
                val title =
                    "Маршрут " +
                        java.text.SimpleDateFormat(
                            "dd.MM HH:mm",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date())

                app.database.beginTrack(title).also { id ->
                    prefs.edit()
                        .putLong(KEY_TRACK_ID, id)
                        .putBoolean(KEY_RECORDING, true)
                        .apply()
                }
            }

        lastAccepted = null
        collecting = true
        TrackRecordingState.setTrackId(trackId)
        TrackRecordingState.setRecording(true)

        Thread {
            val restored =
                runCatching {
                    app.database.trackPoints(trackId)
                }.getOrDefault(emptyList())

            TrackRecordingState.replacePoints(restored)

            lastAccepted =
                restored.lastOrNull()?.let { point ->
                    Location("stored-track").apply {
                        latitude = point.latitude
                        longitude = point.longitude
                        time = point.time
                        point.accuracyMeters?.let {
                            accuracy = it
                        }
                    }
                }
        }.apply {
            name = "forest-track-restore"
            start()
        }

        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(
                this,
                TrackRecordingService::class.java
            ).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification =
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(
                    android.R.drawable.ic_menu_mylocation
                )
                .setContentTitle("Запись маршрута")
                .setContentText(
                    "Трек продолжает записываться при заблокированном экране"
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openIntent)
                .addAction(
                    0,
                    "Остановить",
                    stopIntent
                )
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .build()

        val fgsType =
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            fgsType
        )

        val powerManager =
            getSystemService(PowerManager::class.java)

        wakeLock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ForestNavigator:TrackRecording"
            ).apply {
                setReferenceCounted(false)
                if (!isHeld) {
                    acquire()
                }
            }

        gnss.start(GnssEngine.PowerMode.NORMAL)

        collectorThread = Thread {
            var seenElapsedNs = Long.MIN_VALUE
            var seenTime = 0L

            while (collecting) {
                val loc = gnss.location.value

                if (loc != null) {
                    val newSample =
                        if (loc.elapsedRealtimeNanos > 0L) {
                            loc.elapsedRealtimeNanos != seenElapsedNs
                        } else {
                            loc.time != seenTime
                        }

                    if (newSample && shouldAccept(loc)) {
                        seenElapsedNs =
                            loc.elapsedRealtimeNanos
                        seenTime = loc.time

                        val point = TrackPoint(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            altitude =
                                if (loc.hasAltitude()) {
                                    loc.altitude
                                } else {
                                    null
                                },
                            accuracyMeters =
                                if (loc.hasAccuracy()) {
                                    loc.accuracy
                                } else {
                                    null
                                },
                            speedMps =
                                if (loc.hasSpeed()) {
                                    loc.speed
                                } else {
                                    null
                                },
                            bearing =
                                if (loc.hasBearing()) {
                                    loc.bearing
                                } else {
                                    null
                                },
                            time = loc.time
                        )

                        runCatching {
                            app.database.appendTrackPoint(
                                trackId,
                                point
                            )
                            TrackRecordingState.append(point)
                            lastAccepted = Location(loc)
                        }
                    }
                }

                try {
                    Thread.sleep(750L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "forest-track-writer"
            start()
        }
    }

    private fun shouldAccept(loc: Location): Boolean {
        if (
            loc.hasAccuracy() &&
            loc.accuracy > MAX_TRACK_ACCURACY_METERS
        ) {
            return false
        }

        val prev = lastAccepted ?: return true
        val dt = loc.time - prev.time

        return dt >= MIN_TRACK_INTERVAL_MS ||
            loc.distanceTo(prev) >=
                MIN_TRACK_DISTANCE_METERS
    }

    private fun stopRecording() {
        if (!collecting && trackId < 0L) {
            prefs.edit()
                .putBoolean(KEY_RECORDING, false)
                .remove(KEY_TRACK_ID)
                .apply()

            TrackRecordingState.setRecording(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        collecting = false
        collectorThread?.interrupt()
        collectorThread = null
        gnss.stop()
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null

        if (trackId >= 0L) {
            runCatching {
                (application as ForestNavApplication)
                    .database
                    .endTrack(trackId)
            }
        }

        trackId = -1L
        prefs.edit()
            .putBoolean(KEY_RECORDING, false)
            .remove(KEY_TRACK_ID)
            .apply()

        TrackRecordingState.setRecording(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        collecting = false
        collectorThread?.interrupt()
        collectorThread = null
        gnss.stop()
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
        TrackRecordingState.setRecording(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? =
        null

    private fun createChannel() {
        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Запись маршрута",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL = "track_recording"
        private const val NOTIFICATION_ID = 42
        private const val PREFS =
            "track_recording_state"
        private const val KEY_TRACK_ID = "track_id"
        private const val KEY_RECORDING = "recording"

        private const val MAX_TRACK_ACCURACY_METERS =
            35f
        private const val MIN_TRACK_INTERVAL_MS =
            3_000L
        private const val MIN_TRACK_DISTANCE_METERS =
            3f

        const val ACTION_STOP =
            "app.forestnav.STOP_TRACK"

        fun start(context: Context) {
            androidx.core.content.ContextCompat
                .startForegroundService(
                    context,
                    Intent(
                        context,
                        TrackRecordingService::class.java
                    )
                )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(
                    context,
                    TrackRecordingService::class.java
                ).setAction(ACTION_STOP)
            )
        }
    }
}
