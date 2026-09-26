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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.forestnav.MainActivity
import app.forestnav.gnss.GnssEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object LocationTrackingState {
    private val _location =
        MutableStateFlow<Location?>(null)
    val location = _location.asStateFlow()

    private val _rawLocation =
        MutableStateFlow<Location?>(null)
    val rawLocation = _rawLocation.asStateFlow()

    private val _satellites =
        MutableStateFlow(
            GnssEngine.SatelliteInfo()
        )
    val satellites = _satellites.asStateFlow()

    private val _tracking =
        MutableStateFlow(false)
    val tracking = _tracking.asStateFlow()

    internal fun setLocation(
        value: Location?
    ) {
        _location.value =
            value?.let(::Location)
    }

    internal fun setRawLocation(
        value: Location?
    ) {
        _rawLocation.value =
            value?.let(::Location)
    }

    internal fun setSatellites(
        value: GnssEngine.SatelliteInfo
    ) {
        _satellites.value = value
    }

    internal fun setTracking(
        value: Boolean
    ) {
        _tracking.value = value
    }
}

class LocationTrackingService : Service() {
    private lateinit var gnss: GnssEngine

    private val serviceJob =
        SupervisorJob()

    private val scope =
        CoroutineScope(
            serviceJob + Dispatchers.Default
        )

    private var wakeLock:
        PowerManager.WakeLock? = null

    private var locationCollector:
        Job? = null

    private var rawCollector:
        Job? = null

    private var satelliteCollector:
        Job? = null

    private var precisionMode = false
    private var lastNotificationAt = 0L

    private val prefs by lazy {
        getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        )
    }

    override fun onCreate() {
        super.onCreate()

        createChannel()
        gnss = GnssEngine(this)

        locationCollector =
            scope.launch {
                gnss.location.collect { location ->
                    LocationTrackingState
                        .setLocation(location)
                    maybeRefreshNotification()
                }
            }

        rawCollector =
            scope.launch {
                gnss.rawLocation.collect { location ->
                    LocationTrackingState
                        .setRawLocation(location)
                }
            }

        satelliteCollector =
            scope.launch {
                gnss.satellites.collect {
                    satellites ->
                    LocationTrackingState
                        .setSatellites(satellites)
                    maybeRefreshNotification()
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_EXIT_ALL -> {
                exitEverything()
                return START_NOT_STICKY
            }

            ACTION_PRECISION -> {
                startTracking()
                precisionMode = true
                gnss.start(
                    GnssEngine.PowerMode.PRECISION
                )
            }

            ACTION_NORMAL -> {
                startTracking()
                precisionMode = false
                gnss.start(
                    GnssEngine.PowerMode.NORMAL
                )
            }

            else -> {
                val shouldRun =
                    intent != null ||
                        prefs.getBoolean(
                            KEY_ENABLED,
                            false
                        )

                if (shouldRun) {
                    startTracking()
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        return START_STICKY
    }

    private fun startTracking() {
        prefs.edit()
            .putBoolean(
                KEY_ENABLED,
                true
            )
            .apply()

        startAsForeground()

        if (
            wakeLock?.isHeld != true
        ) {
            val powerManager =
                getSystemService(
                    PowerManager::class.java
                )

            wakeLock =
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ForestNavigator:LocationTracking"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }

        LocationTrackingState
            .setTracking(true)

        gnss.start(
            if (precisionMode) {
                GnssEngine.PowerMode.PRECISION
            } else {
                GnssEngine.PowerMode.NORMAL
            }
        )
    }

    private fun startAsForeground() {
        val type =
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            type
        )
    }

    private fun buildNotification() =
        NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable
                    .ic_menu_mylocation
            )
            .setContentTitle(
                "Отслеживание геопозиции"
            )
            .setContentText(
                notificationText()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(
                NotificationCompat
                    .CATEGORY_SERVICE
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    301,
                    Intent(
                        this,
                        MainActivity::class.java
                    ),
                    PendingIntent.FLAG_IMMUTABLE or
                        PendingIntent
                            .FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                0,
                "Закрыть приложение",
                PendingIntent.getService(
                    this,
                    302,
                    Intent(
                        this,
                        LocationTrackingService::class.java
                    ).setAction(
                        ACTION_EXIT_ALL
                    ),
                    PendingIntent.FLAG_IMMUTABLE or
                        PendingIntent
                            .FLAG_UPDATE_CURRENT
                )
            )
            .build()

    private fun notificationText(): String {
        val location =
            LocationTrackingState
                .location.value

        val satellites =
            LocationTrackingState
                .satellites.value

        val accuracy =
            if (
                location != null &&
                location.hasAccuracy()
            ) {
                " • ±${location.accuracy.toInt()} м"
            } else {
                ""
            }

        return "GPS ${satellites.usedInFix}/${satellites.visible}$accuracy"
    }

    private fun maybeRefreshNotification() {
        if (
            !LocationTrackingState
                .tracking.value
        ) {
            return
        }

        val now =
            android.os.SystemClock
                .elapsedRealtime()

        if (
            now - lastNotificationAt <
            NOTIFICATION_UPDATE_INTERVAL_MS
        ) {
            return
        }

        lastNotificationAt = now

        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            buildNotification()
        )
    }

    private fun exitEverything() {
        prefs.edit()
            .putBoolean(
                KEY_ENABLED,
                false
            )
            .commit()

        LocationTrackingState
            .setTracking(false)

        runCatching {
            TrackRecordingService.stop(this)
        }

        runCatching {
            OfflineMapDownloadService
                .stopAll(this)
        }

        runCatching {
            gnss.stop()
        }

        releaseWakeLock()

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )
        stopSelf()

        Handler(
            Looper.getMainLooper()
        ).postDelayed(
            {
                Process.killProcess(
                    Process.myPid()
                )
            },
            EXIT_DELAY_MS
        )
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        val explicitStop =
            !prefs.getBoolean(
                KEY_ENABLED,
                false
            )

        if (explicitStop) {
            LocationTrackingState
                .setTracking(false)
        }

        runCatching {
            gnss.stop()
        }

        releaseWakeLock()

        locationCollector?.cancel()
        rawCollector?.cancel()
        satelliteCollector?.cancel()
        scope.cancel()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    private fun createChannel() {
        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Геопозиция",
                NotificationManager
                    .IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL_ID =
            "location_tracking"

        private const val NOTIFICATION_ID =
            41

        private const val PREFS =
            "location_tracking_state"

        private const val KEY_ENABLED =
            "enabled"

        private const val ACTION_EXIT_ALL =
            "app.forestnav.action.EXIT_ALL"

        private const val ACTION_PRECISION =
            "app.forestnav.action.LOCATION_PRECISION"

        private const val ACTION_NORMAL =
            "app.forestnav.action.LOCATION_NORMAL"

        private const val NOTIFICATION_UPDATE_INTERVAL_MS =
            2_000L

        private const val EXIT_DELAY_MS =
            900L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(
                    context,
                    LocationTrackingService::class.java
                )
            )
        }

        fun setPrecision(
            context: Context,
            enabled: Boolean
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(
                    context,
                    LocationTrackingService::class.java
                ).setAction(
                    if (enabled) {
                        ACTION_PRECISION
                    } else {
                        ACTION_NORMAL
                    }
                )
            )
        }

        fun exitApp(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(
                    context,
                    LocationTrackingService::class.java
                ).setAction(
                    ACTION_EXIT_ALL
                )
            )
        }
    }
}
