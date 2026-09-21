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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GnssEngine(private val context: Context) : LocationListener {
    enum class PowerMode(val intervalMs: Long, val minDistanceM: Float) {
        ECO(10_000L, 5f),
        NORMAL(3_000L, 2f),
        PRECISION(1_000L, 0f)
    }

    data class SatelliteInfo(val visible: Int = 0, val usedInFix: Int = 0)

    private val manager = context.getSystemService(LocationManager::class.java)
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()
    private val _satellites = MutableStateFlow(SatelliteInfo())
    val satellites: StateFlow<SatelliteInfo> = _satellites.asStateFlow()

    private var started = false
    private var mode = PowerMode.NORMAL

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            _satellites.value = SatelliteInfo(status.satelliteCount, used)
        }

        override fun onStopped() {
            _satellites.value = SatelliteInfo()
        }
    }

    fun hasFinePermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    fun start(powerMode: PowerMode = mode) {
        if (!hasFinePermission()) return
        if (started && powerMode == mode) return
        if (started) stop()
        mode = powerMode
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            mode.intervalMs,
            mode.minDistanceM,
            this
        )
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            manager.registerGnssStatusCallback(context.mainExecutor, statusCallback)
        } else {
            @Suppress("DEPRECATION")
            manager.registerGnssStatusCallback(statusCallback)
        }
        started = true
    }

    fun stop() {
        if (!started) return
        manager.removeUpdates(this)
        manager.unregisterGnssStatusCallback(statusCallback)
        started = false
        _satellites.value = SatelliteInfo()
    }

    override fun onLocationChanged(location: Location) {
        if (location.provider == LocationManager.GPS_PROVIDER) _location.value = location
    }

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
}
