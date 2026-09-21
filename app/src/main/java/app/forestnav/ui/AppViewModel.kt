package app.forestnav.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.forestnav.ForestNavApplication
import app.forestnav.data.Waypoint
import app.forestnav.data.WaypointType
import app.forestnav.gnss.CompassEngine
import app.forestnav.gnss.GnssEngine
import app.forestnav.gnss.PreciseFixCollector
import app.forestnav.map.MapLayer
import app.forestnav.map.MapStyles
import app.forestnav.map.OfflineMapManager
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

    private val _download = MutableStateFlow<OfflineMapManager.DownloadProgress?>(null)
    val download = _download.asStateFlow()

    private val _offlineRegions = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val offlineRegions = _offlineRegions.asStateFlow()

    init { refreshWaypoints(); refreshOfflineRegions() }

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

    fun styleUrl(): String? = MapStyles.url(_mapLayer.value, app.settings)

    fun setNavigationTarget(w: Waypoint?) { _navigationTarget.value = w }

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

    fun updateMapTilerKey(value: String) { app.settings.mapTilerKey = value }
    fun mapTilerKey(): String = app.settings.mapTilerKey
    fun updateCustomStyle(value: String) { app.settings.customStyleUrl = value }
    fun customStyle(): String = app.settings.customStyleUrl

    fun downloadCurrentRegion(radiusKm: Double) {
        val loc: Location = location.value ?: run {
            _download.value = OfflineMapManager.DownloadProgress(error = "Нет GPS-координат")
            return
        }
        val style = styleUrl() ?: run {
            _download.value = OfflineMapManager.DownloadProgress(error = "Для этого слоя нужен ключ/URL карты")
            return
        }
        val layer = _mapLayer.value
        val name = "${layer.title} ${radiusKm.toInt()}км ${java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        _download.value = OfflineMapManager.DownloadProgress()
        val maxZoom = when {
            radiusKm <= 2.0 -> 17.0
            radiusKm <= 5.0 -> 16.0
            else -> 15.0
        }
        offline.downloadAround(name, style, loc.latitude, loc.longitude, radiusKm, maxZoom = maxZoom) {
            _download.value = it
            if (it.complete) refreshOfflineRegions()
        }
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

    override fun onCleared() {
        preciseJob?.cancel()
        gnss.stop()
        compass.stop()
        super.onCleared()
    }
}
