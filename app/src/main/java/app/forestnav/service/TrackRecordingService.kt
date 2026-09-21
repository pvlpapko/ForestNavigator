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
    internal fun set(value: Boolean) { _recording.value = value }
}

class TrackRecordingService : Service() {
    private lateinit var gnss: GnssEngine
    private var trackId: Long = -1L
    private var lastAccepted: Location? = null
    private var collectorThread: Thread? = null
    @Volatile private var collecting = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        gnss = GnssEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            else -> startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (collecting) return
        val app = application as ForestNavApplication
        trackId = app.database.beginTrack("Маршрут ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
        collecting = true
        TrackRecordingState.set(true)

        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, TrackRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Запись маршрута")
            .setContentText("GPS-трек сохраняется локально")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Остановить", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        gnss.start(GnssEngine.PowerMode.NORMAL)

        collectorThread = Thread {
            var seenTime = 0L
            while (collecting) {
                val loc = gnss.location.value
                if (loc != null && loc.time != seenTime && shouldAccept(loc)) {
                    seenTime = loc.time
                    app.database.appendTrackPoint(trackId, TrackPoint(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        altitude = if (loc.hasAltitude()) loc.altitude else null,
                        accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
                        speedMps = if (loc.hasSpeed()) loc.speed else null,
                        bearing = if (loc.hasBearing()) loc.bearing else null,
                        time = loc.time
                    ))
                    lastAccepted = Location(loc)
                }
                try { Thread.sleep(1000L) } catch (_: InterruptedException) { break }
            }
        }.apply { name = "forest-track-writer"; start() }
    }

    private fun shouldAccept(loc: Location): Boolean {
        if (loc.hasAccuracy() && loc.accuracy > 35f) return false
        val prev = lastAccepted ?: return true
        val dt = loc.time - prev.time
        return dt >= 3_000L || loc.distanceTo(prev) >= 3f
    }

    private fun stopRecording() {
        if (!collecting) {
            stopSelf(); return
        }
        collecting = false
        collectorThread?.interrupt()
        collectorThread = null
        gnss.stop()
        if (trackId >= 0) (application as ForestNavApplication).database.endTrack(trackId)
        trackId = -1
        TrackRecordingState.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (collecting) stopRecording() else gnss.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Запись маршрута", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val CHANNEL = "track_recording"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "app.forestnav.STOP_TRACK"

        fun start(context: Context) {
            val i = Intent(context, TrackRecordingService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackRecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
