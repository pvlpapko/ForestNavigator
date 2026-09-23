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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ForestNavApplication
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(hasInternetTransport())
    val online = _online.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _online.value = hasInternetTransport()
        }

        override fun onLost(network: Network) {
            _online.value = hasInternetTransport()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            _online.value = hasInternetTransport()
        }
    }

    val gnss = GnssEngine(application)
    val compass = CompassEngine(application)
    private val offline = OfflineMapManager(application)

    val location = gnss.location
    val satellites = gnss.satellites
    val heading = compass.heading

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

    private val _navigationTarget = MutableStateFlow<Waypoint?>(null)
    val navigationTarget = _navigationTarget.asStateFlow()

    private val _mapLayer = MutableStateFlow(MapLayer.MAP)
    val mapLayer = _mapLayer.asStateFlow()

    private val _arcGisKeyVersion = MutableStateFlow(0)
    val arcGisKeyVersion = _arcGisKeyVersion.asStateFlow()

    data class PreciseState(
        val active: Boolean = false,
        val samples: Int = 0,
        val bestAccuracy: Float? = null,
        val label: String = "",
        val error: String? = null
    )
    private val _preciseState = MutableStateFlow(PreciseState())
    val preciseState = _preciseState.asStateFlow()
    private var preciseJob: Job? = null
    private var foregroundActive = false

    val downloads = OfflineMapDownloadState.progress

    private val _offlineRegions = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val offlineRegions = _offlineRegions.asStateFlow()

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        _online.value = hasInternetTransport()
        refreshWaypoints()
        refreshOfflineRegions()
        viewModelScope.launch {
            downloads.collectLatest { states ->
                if (states.values.any {
                        it.complete || it.cancelled || it.error != null
                    }
                ) {
                    refreshOfflineRegions()
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
        if (!_preciseState.value.active) gnss.stop()
    }

    fun setLayer(layer: MapLayer) { _mapLayer.value = layer }

    fun arcGisApiKey(): String = app.settings.arcGisApiKey

    fun updateArcGisApiKey(value: String) {
        app.settings.arcGisApiKey = value
        app.applyArcGisApiKey(value)
        _arcGisKeyVersion.value = _arcGisKeyVersion.value + 1
    }

    fun setNavigationTarget(w: Waypoint?) { _navigationTarget.value = w }

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

    fun saveCurrent(type: WaypointType, name: String, note: String = "") {
        val loc = location.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            app.database.insertWaypoint(
                type, name, loc.latitude, loc.longitude,
                if (loc.hasAltitude()) loc.altitude else null,
                if (loc.hasAccuracy()) loc.accuracy else null, note
            )
            refreshWaypointsInternal()
        }
    }

    fun savePrecise(type: WaypointType, name: String, note: String = "") {
        if (_preciseState.value.active) return
        preciseJob?.cancel()
        val collector = PreciseFixCollector()
        _preciseState.value = PreciseState(active = true, label = name)
        gnss.start(GnssEngine.PowerMode.PRECISION)
        preciseJob = viewModelScope.launch {
            location.collectLatest { loc ->
                if (loc == null) return@collectLatest
                val result = collector.add(loc)
                val (count, best) = collector.progress()
                _preciseState.value = PreciseState(true, count, best, name)
                if (result != null) {
                    withContext(Dispatchers.IO) {
                        app.database.insertWaypoint(
                            type, name, result.latitude, result.longitude,
                            result.altitude, result.estimatedAccuracyMeters, note
                        )
                        refreshWaypointsInternal()
                    }
                    _preciseState.value = PreciseState(
                        active = false,
                        samples = result.sampleCount,
                        bestAccuracy = result.estimatedAccuracyMeters,
                        label = name
                    )
                    if (foregroundActive) gnss.start(GnssEngine.PowerMode.NORMAL) else gnss.stop()
                    preciseJob?.cancel()
                }
            }
        }
    }

    fun cancelPrecise() {
        preciseJob?.cancel()
        preciseJob = null
        _preciseState.value = PreciseState()
        if (foregroundActive) gnss.start(GnssEngine.PowerMode.NORMAL) else gnss.stop()
    }

    fun deleteWaypoint(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            app.database.deleteWaypoint(id)
            if (_navigationTarget.value?.id == id) _navigationTarget.value = null
            refreshWaypointsInternal()
        }
    }

    fun downloadCurrentRegion(radiusKm: Double) {
        val loc: Location = location.value ?: return
        val layer = _mapLayer.value
        val name = "${layer.title} ${radiusKm.toInt()}км ${java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        // HD detail is kept across the entire selected area. ArcGIS export
        // requests are split into smaller TPKX packages so the provider's
        // per-request tile cap does not force lower quality at large radii.
        val maxZoom = when (layer) {
            MapLayer.SATELLITE,
            MapLayer.SATELLITE_TERRAIN -> 18.0
            MapLayer.MAP,
            MapLayer.RELIEF -> 17.0
        }
        val minZoom = when {
            radiusKm >= 100.0 -> 7.0
            radiusKm >= 50.0 -> 8.0
            radiusKm >= 25.0 -> 9.0
            else -> 10.0
        }

        OfflineMapDownloadService.start(
            context = app,
            name = name,
            layer = layer,
            latitude = loc.latitude,
            longitude = loc.longitude,
            radiusKm = radiusKm,
            minZoom = minZoom,
            maxZoom = maxZoom
        )
    }

    fun cancelDownload(regionId: Long) {
        OfflineMapDownloadService.cancel(app, regionId)
    }

    fun refreshOfflineRegions() {
        offline.listRegions({ _offlineRegions.value = it }, { })
    }

    fun deleteOfflineRegion(id: Long) {
        offline.deleteRegion(id, { refreshOfflineRegions() }, { })
    }

    private fun refreshWaypoints() {
        viewModelScope.launch(Dispatchers.IO) { refreshWaypointsInternal() }
    }

    private fun refreshWaypointsInternal() { _waypoints.value = app.database.listWaypoints() }

    private fun hasInternetTransport(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onCleared() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        preciseJob?.cancel()
        gnss.stop()
        compass.stop()
        super.onCleared()
    }
}
