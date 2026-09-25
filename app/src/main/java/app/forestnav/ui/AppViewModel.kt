package app.forestnav.ui

import android.app.Application
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.forestnav.ForestNavApplication
import app.forestnav.data.Waypoint
import app.forestnav.data.WaypointType
import app.forestnav.gnss.CompassEngine
import app.forestnav.gnss.GnssEngine
import app.forestnav.gnss.PreciseFixCollector
import app.forestnav.map.MapLayer
import app.forestnav.map.OfflineMapManager
import app.forestnav.service.OfflineMapDownloadService
import app.forestnav.service.OfflineMapDownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ForestNavApplication
    private val connectivity =
        application.getSystemService(ConnectivityManager::class.java)

    val gnss = GnssEngine(application)
    val compass = CompassEngine(application)

    private val offline = OfflineMapManager(application)

    val location = gnss.location
    val satellites = gnss.satellites
    val heading = compass.heading
    val downloads = OfflineMapDownloadState.progress

    private val _online =
        MutableStateFlow(hasValidatedInternet())
    val online = _online.asStateFlow()

    private val _waypoints =
        MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> =
        _waypoints.asStateFlow()

    private val _navigationTarget =
        MutableStateFlow<Waypoint?>(null)
    val navigationTarget =
        _navigationTarget.asStateFlow()

    private val _mapLayer =
        MutableStateFlow(MapLayer.MAP)
    val mapLayer =
        _mapLayer.asStateFlow()

    private val _initialMapScaleMeters =
        MutableStateFlow(app.settings.initialMapScaleMeters)
    val initialMapScaleMeters =
        _initialMapScaleMeters.asStateFlow()

    private val _offlineRegions =
        MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val offlineRegions =
        _offlineRegions.asStateFlow()

    data class PreciseState(
        val active: Boolean = false,
        val samples: Int = 0,
        val bestAccuracy: Float? = null,
        val label: String = "",
        val error: String? = null
    )

    private val _preciseState =
        MutableStateFlow(PreciseState())
    val preciseState =
        _preciseState.asStateFlow()

    private var preciseJob: Job? = null
    private var connectivityRefreshJob: Job? = null
    private var foregroundActive = false

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                scheduleConnectivityRefresh()
            }

            override fun onLost(network: Network) {
                scheduleConnectivityRefresh(immediate = true)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                scheduleConnectivityRefresh()
            }
        }

    init {
        runCatching {
            connectivity.registerDefaultNetworkCallback(
                networkCallback
            )
        }

        _online.value = hasValidatedInternet()

        viewModelScope.launch(Dispatchers.IO) {
            offline.cleanupLegacyFiles()
            refreshWaypointsInternal()
            refreshOfflineRegionsInternal()
        }
        OfflineMapDownloadService.restore(app)

        viewModelScope.launch {
            downloads.collectLatest { states ->
                val catalogMayHaveChanged =
                    states.values.any {
                        it.complete ||
                            it.cancelled ||
                            it.error != null
                    }

                if (catalogMayHaveChanged) {
                    withContext(Dispatchers.IO) {
                        refreshOfflineRegionsInternal()
                    }
                }
            }
        }
    }

    fun startForegroundSensors() {
        foregroundActive = true
        gnss.start(GnssEngine.PowerMode.NORMAL)
        compass.start()
    }

    fun stopForegroundSensors() {
        foregroundActive = false
        compass.stop()

        if (!_preciseState.value.active) {
            gnss.stop()
        }
    }

    fun setLayer(layer: MapLayer) {
        _mapLayer.value = layer
    }

    fun setInitialMapScaleMeters(value: Int) {
        app.settings.initialMapScaleMeters = value
        _initialMapScaleMeters.value =
            app.settings.initialMapScaleMeters
    }

    fun setNavigationTarget(waypoint: Waypoint?) {
        _navigationTarget.value = waypoint
    }

    fun saveMapPoint(
        type: WaypointType,
        name: String,
        latitude: Double,
        longitude: Double,
        note: String = ""
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            app.database.insertWaypoint(
                type = type,
                name = name,
                lat = latitude,
                lon = longitude,
                altitude = null,
                accuracy = null,
                note = note
            )
            refreshWaypointsInternal()
        }
    }

    fun saveCurrent(
        type: WaypointType,
        name: String,
        note: String = ""
    ) {
        val current = location.value ?: return

        viewModelScope.launch(Dispatchers.IO) {
            app.database.insertWaypoint(
                type = type,
                name = name,
                lat = current.latitude,
                lon = current.longitude,
                altitude =
                    if (current.hasAltitude()) {
                        current.altitude
                    } else {
                        null
                    },
                accuracy =
                    if (current.hasAccuracy()) {
                        current.accuracy
                    } else {
                        null
                    },
                note = note
            )
            refreshWaypointsInternal()
        }
    }

    fun savePrecise(
        type: WaypointType,
        name: String,
        note: String = ""
    ) {
        if (_preciseState.value.active) return

        preciseJob?.cancel()

        val collector = PreciseFixCollector()
        _preciseState.value =
            PreciseState(
                active = true,
                label = name
            )

        gnss.start(GnssEngine.PowerMode.PRECISION)

        preciseJob = viewModelScope.launch {
            gnss.rawLocation.collectLatest { raw ->
                if (raw == null) return@collectLatest

                val result = collector.add(raw)
                val (count, best) = collector.progress()

                _preciseState.value =
                    PreciseState(
                        active = true,
                        samples = count,
                        bestAccuracy = best,
                        label = name
                    )

                if (result == null) {
                    return@collectLatest
                }

                withContext(Dispatchers.IO) {
                    app.database.insertWaypoint(
                        type = type,
                        name = name,
                        lat = result.latitude,
                        lon = result.longitude,
                        altitude = result.altitude,
                        accuracy =
                            result.estimatedAccuracyMeters,
                        note = note
                    )
                    refreshWaypointsInternal()
                }

                _preciseState.value =
                    PreciseState(
                        active = false,
                        samples = result.sampleCount,
                        bestAccuracy =
                            result.estimatedAccuracyMeters,
                        label = name
                    )

                restoreNormalGnssMode()
                preciseJob?.cancel()
            }
        }
    }

    fun cancelPrecise() {
        preciseJob?.cancel()
        preciseJob = null
        _preciseState.value = PreciseState()
        restoreNormalGnssMode()
    }

    fun deleteWaypoint(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            app.database.deleteWaypoint(id)

            if (_navigationTarget.value?.id == id) {
                _navigationTarget.value = null
            }

            refreshWaypointsInternal()
        }
    }

    fun downloadCurrentRegion(radiusKm: Double) {
        val current: Location =
            location.value ?: return
        val layer = _mapLayer.value

        val timestamp = SimpleDateFormat(
            "dd.MM.yy HH:mm",
            Locale.getDefault()
        ).format(Date())

        val name =
            "${layer.title} ${radiusKm.toInt()}км $timestamp"

        val maxZoom =
            when (layer) {
                MapLayer.MAP -> 18.0
                MapLayer.SATELLITE -> 18.0
            }

        val minZoom =
            when {
                radiusKm <= 2.0 -> 15.0
                radiusKm <= 5.0 -> 14.0
                radiusKm <= 10.0 -> 13.0
                radiusKm <= 25.0 -> 12.0
                else -> 11.0
            }

        OfflineMapDownloadService.start(
            context = app,
            name = name,
            layer = layer,
            latitude = current.latitude,
            longitude = current.longitude,
            radiusKm = radiusKm,
            minZoom = minZoom,
            maxZoom = maxZoom
        )
    }

    fun cancelDownload(regionId: Long) {
        OfflineMapDownloadService.cancel(
            context = app,
            regionId = regionId
        )
    }

    fun resumeDownload(regionId: Long) {
        OfflineMapDownloadService.resume(
            context = app,
            regionId = regionId
        )
    }

    fun deleteDownload(regionId: Long) {
        OfflineMapDownloadService.delete(
            context = app,
            regionId = regionId
        )
    }

    fun refreshOfflineRegions() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshOfflineRegionsInternal()
        }
    }

    fun deleteOfflineRegion(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            offline.deleteRegion(
                id = id,
                onSuccess = {
                    refreshOfflineRegionsInternal()
                },
                onError = {}
            )
        }
    }

    private fun refreshWaypointsInternal() {
        _waypoints.value =
            app.database.listWaypoints()
    }

    private fun refreshOfflineRegionsInternal() {
        offline.listRegions(
            onSuccess = {
                _offlineRegions.value = it
            },
            onError = {}
        )
    }

    private fun restoreNormalGnssMode() {
        if (foregroundActive) {
            gnss.start(GnssEngine.PowerMode.NORMAL)
        } else {
            gnss.stop()
        }
    }

    private fun scheduleConnectivityRefresh(
        immediate: Boolean = false
    ) {
        connectivityRefreshJob?.cancel()

        connectivityRefreshJob =
            viewModelScope.launch {
                if (!immediate) {
                    delay(CONNECTIVITY_STABILIZE_MS)
                }

                _online.value =
                    hasValidatedInternet()
            }
    }

    private fun hasValidatedInternet(): Boolean {
        val network =
            connectivity.activeNetwork ?: return false
        val capabilities =
            connectivity.getNetworkCapabilities(network)
                ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) &&
            capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
    }

    override fun onCleared() {
        runCatching {
            connectivity.unregisterNetworkCallback(
                networkCallback
            )
        }

        connectivityRefreshJob?.cancel()
        preciseJob?.cancel()
        gnss.stop()
        compass.stop()

        super.onCleared()
    }

    companion object {
        private const val CONNECTIVITY_STABILIZE_MS =
            800L
    }
}
