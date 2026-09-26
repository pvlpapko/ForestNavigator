package app.forestnav.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

class GnssEngine(private val context: Context) : LocationListener {
    enum class PowerMode(val intervalMs: Long, val minDistanceM: Float) {
        ECO(10_000L, 5f),
        NORMAL(1_000L, 0f),
        PRECISION(500L, 0f)
    }

    data class SatelliteInfo(val visible: Int = 0, val usedInFix: Int = 0)

    private val manager = context.getSystemService(LocationManager::class.java)

    private val _rawLocation = MutableStateFlow<Location?>(null)
    val rawLocation: StateFlow<Location?> = _rawLocation.asStateFlow()

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val _satellites = MutableStateFlow(SatelliteInfo())
    val satellites: StateFlow<SatelliteInfo> = _satellites.asStateFlow()

    private var started = false
    private var statusRegistered = false
    private var desiredMode: PowerMode? = null
    private var mode = PowerMode.NORMAL
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNonZeroSatelliteAt = 0L

    private var lastStableLocation: Location? = null
    private var lastAcceptedElapsedNs = 0L

    private val clearSatellitesRunnable = Runnable {
        if (started &&
            SystemClock.elapsedRealtime() - lastNonZeroSatelliteAt >= SATELLITE_ZERO_GRACE_MS
        ) {
            _satellites.value = SatelliteInfo()
        }
    }

    private val clearAfterStopRunnable = Runnable {
        if (!started) _satellites.value = SatelliteInfo()
    }

    private val retryStartRunnable =
        Runnable {
            desiredMode?.let { requested ->
                attemptStart(requested)
            }
        }

    private fun scheduleSatelliteZero() {
        mainHandler.removeCallbacks(clearSatellitesRunnable)
        val age = SystemClock.elapsedRealtime() - lastNonZeroSatelliteAt
        val delay = (SATELLITE_ZERO_GRACE_MS - age).coerceAtLeast(1_000L)
        mainHandler.postDelayed(clearSatellitesRunnable, delay)
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onStarted() {
            mainHandler.removeCallbacks(clearSatellitesRunnable)
            mainHandler.removeCallbacks(clearAfterStopRunnable)
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }

            if (status.satelliteCount > 0) {
                lastNonZeroSatelliteAt = SystemClock.elapsedRealtime()
                mainHandler.removeCallbacks(clearSatellitesRunnable)
                _satellites.value = SatelliteInfo(status.satelliteCount, used)
            } else {
                scheduleSatelliteZero()
            }
        }

        override fun onStopped() {
            if (started) scheduleSatelliteZero()
        }
    }

    fun hasFinePermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    fun start(powerMode: PowerMode = mode) {
        desiredMode = powerMode
        mainHandler.removeCallbacks(retryStartRunnable)

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                desiredMode?.let { requested ->
                    attemptStart(requested)
                }
            }
            return
        }

        attemptStart(powerMode)
    }

    @SuppressLint("MissingPermission")
    private fun attemptStart(powerMode: PowerMode) {
        if (desiredMode == null) return
        if (!hasFinePermission()) return
        if (started && powerMode == mode) return

        if (started) {
            stopInternal(clearUiLater = false)
        }

        mainHandler.removeCallbacks(clearAfterStopRunnable)
        mainHandler.removeCallbacks(retryStartRunnable)
        mode = powerMode

        var locationRegistered = false

        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                mode.intervalMs,
                mode.minDistanceM,
                this,
                Looper.getMainLooper()
            )
            locationRegistered = true
            started = true

            statusRegistered =
                runCatching {
                    if (
                        android.os.Build.VERSION.SDK_INT >= 30
                    ) {
                        manager.registerGnssStatusCallback(
                            context.mainExecutor,
                            statusCallback
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        manager.registerGnssStatusCallback(
                            statusCallback
                        )
                    }
                }.isSuccess
        } catch (_: SecurityException) {
            if (locationRegistered) {
                runCatching {
                    manager.removeUpdates(this)
                }
            }
            started = false
            statusRegistered = false
        } catch (_: RuntimeException) {
            if (locationRegistered) {
                runCatching {
                    manager.removeUpdates(this)
                }
            }

            started = false
            statusRegistered = false

            if (
                desiredMode != null &&
                hasFinePermission()
            ) {
                mainHandler.postDelayed(
                    retryStartRunnable,
                    START_RETRY_DELAY_MS
                )
            }
        }
    }

    fun stop() {
        desiredMode = null
        mainHandler.removeCallbacks(retryStartRunnable)

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                stopInternal(clearUiLater = true)
            }
            return
        }

        stopInternal(clearUiLater = true)
    }

    private fun stopInternal(
        clearUiLater: Boolean
    ) {
        if (started) {
            runCatching {
                manager.removeUpdates(this)
            }
        }

        if (statusRegistered) {
            runCatching {
                manager.unregisterGnssStatusCallback(
                    statusCallback
                )
            }
        }

        started = false
        statusRegistered = false

        mainHandler.removeCallbacks(clearSatellitesRunnable)
        mainHandler.removeCallbacks(clearAfterStopRunnable)

        if (clearUiLater) {
            mainHandler.postDelayed(
                clearAfterStopRunnable,
                SATELLITE_STOP_GRACE_MS
            )
        }
    }

    override fun onLocationChanged(location: Location) {
        if (location.provider != LocationManager.GPS_PROVIDER) return

        val raw = Location(location)
        _rawLocation.value = raw

        stabilize(raw)?.let { stable ->
            lastStableLocation = stable
            lastAcceptedElapsedNs = raw.elapsedRealtimeNanos
            _location.value = stable
        }
    }

    private fun stabilize(candidate: Location): Location? {
        if (!candidate.latitude.isFinite() || !candidate.longitude.isFinite()) return null

        val previous = lastStableLocation ?: return Location(candidate)
        val elapsedNs = candidate.elapsedRealtimeNanos

        if (lastAcceptedElapsedNs > 0L && elapsedNs > 0L && elapsedNs <= lastAcceptedElapsedNs) {
            return null
        }

        val dtSeconds = if (lastAcceptedElapsedNs > 0L && elapsedNs > lastAcceptedElapsedNs) {
            ((elapsedNs - lastAcceptedElapsedNs) / 1_000_000_000.0).coerceIn(0.05, 30.0)
        } else {
            1.0
        }

        if (dtSeconds >= REACQUIRE_AFTER_SECONDS) {
            return Location(candidate)
        }

        val previousAccuracy = accuracyOrDefault(previous)
        val candidateAccuracy = accuracyOrDefault(candidate)

        if (candidateAccuracy > VERY_POOR_ACCURACY_M &&
            previousAccuracy <= GOOD_ACCURACY_M
        ) {
            return null
        }

        val distance = previous.distanceTo(candidate).toDouble()
        val allowedJump = max(
            MIN_JUMP_ALLOWANCE_M,
            MAX_REASONABLE_SPEED_MPS * dtSeconds +
                previousAccuracy + candidateAccuracy
        )

        val candidateClearlyBetter =
            candidateAccuracy + BETTER_ACCURACY_MARGIN_M < previousAccuracy

        if (distance > allowedJump && !candidateClearlyBetter) {
            return null
        }

        val speed = when {
            candidate.hasSpeed() && candidate.speed.isFinite() ->
                candidate.speed.toDouble().coerceAtLeast(0.0)
            dtSeconds > 0.0 -> distance / dtSeconds
            else -> 0.0
        }

        val alpha = when {
            distance >= SNAP_DISTANCE_M || speed >= FAST_MOVEMENT_MPS -> 0.90
            speed >= WALKING_FAST_MPS -> 0.72
            speed >= WALKING_SLOW_MPS -> 0.52
            distance > max(candidateAccuracy * 1.35, 6.0) -> 0.42
            else -> 0.24
        }

        val result = Location(candidate)
        result.latitude = previous.latitude +
            (candidate.latitude - previous.latitude) * alpha

        var lonDelta = candidate.longitude - previous.longitude
        if (lonDelta > 180.0) lonDelta -= 360.0
        if (lonDelta < -180.0) lonDelta += 360.0
        result.longitude = normalizeLongitude(previous.longitude + lonDelta * alpha)

        if (candidate.hasAltitude() && previous.hasAltitude() && speed < WALKING_FAST_MPS) {
            result.altitude = previous.altitude +
                (candidate.altitude - previous.altitude) * ALTITUDE_ALPHA
        }

        return result
    }

    private fun accuracyOrDefault(location: Location): Double =
        if (location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy > 0f) {
            location.accuracy.toDouble()
        } else {
            DEFAULT_ACCURACY_M
        }

    private fun normalizeLongitude(value: Double): Double {
        var lon = value
        while (lon > 180.0) lon -= 360.0
        while (lon < -180.0) lon += 360.0
        return lon
    }

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    companion object {
        private const val START_RETRY_DELAY_MS = 1_200L
        private const val SATELLITE_ZERO_GRACE_MS = 6_000L
        private const val SATELLITE_STOP_GRACE_MS = 3_000L

        private const val DEFAULT_ACCURACY_M = 25.0
        private const val GOOD_ACCURACY_M = 18.0
        private const val VERY_POOR_ACCURACY_M = 80.0
        private const val BETTER_ACCURACY_MARGIN_M = 4.0

        private const val MAX_REASONABLE_SPEED_MPS = 60.0
        private const val MIN_JUMP_ALLOWANCE_M = 35.0
        private const val REACQUIRE_AFTER_SECONDS = 8.0

        private const val WALKING_SLOW_MPS = 0.8
        private const val WALKING_FAST_MPS = 2.5
        private const val FAST_MOVEMENT_MPS = 8.0
        private const val SNAP_DISTANCE_M = 45.0
        private const val ALTITUDE_ALPHA = 0.30
    }
}
