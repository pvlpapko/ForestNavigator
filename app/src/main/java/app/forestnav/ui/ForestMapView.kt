package app.forestnav.ui

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.forestnav.data.Waypoint
import app.forestnav.data.WaypointType
import app.forestnav.map.MapLayer
import app.forestnav.map.MapStyles
import app.forestnav.map.OfflineLayerRole
import app.forestnav.map.OfflineMapManager
import com.arcgismaps.Color
import com.arcgismaps.geometry.GeometryEngine
import com.arcgismaps.geometry.Point
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.mapping.ArcGISMap
import com.arcgismaps.mapping.Basemap
import com.arcgismaps.mapping.Viewpoint
import com.arcgismaps.mapping.layers.ArcGISTiledLayer
import com.arcgismaps.mapping.layers.TileCache
import com.arcgismaps.mapping.symbology.SimpleMarkerSymbol
import com.arcgismaps.mapping.symbology.SimpleMarkerSymbolStyle
import com.arcgismaps.mapping.symbology.TextSymbol
import com.arcgismaps.mapping.view.Graphic
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.arcgismaps.mapping.view.MapView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

private class ArcMapState {
    val waypointOverlay = GraphicsOverlay()
    val userOverlay = GraphicsOverlay()
    var userGraphic: Graphic? = null
    var waypointFingerprint: Int = 0
    var currentLayer: MapLayer? = null
    var recenterToken: Int = -1
    var inputJobs: List<Job> = emptyList()
    var lastScale: Double = 0.0
}

@Composable
fun ForestMapView(
    modifier: Modifier,
    location: Location,
    heading: Float?,
    waypoints: List<Waypoint>,
    layer: MapLayer,
    recenterToken: Int = 0,
    pointPlacementEnabled: Boolean = false,
    onMapClick: (Double, Double) -> Unit = { _, _ -> },
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> },
    onWaypointClick: (Waypoint) -> Unit = {},
    onMapScaleChanged: (Double) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mapView = remember { MapView(context) }
    val state = remember { ArcMapState() }
    val offline = remember { OfflineMapManager(context) }

    val currentWaypoints = rememberUpdatedState(waypoints)
    val currentPlacement = rememberUpdatedState(pointPlacementEnabled)
    val currentMapClick = rememberUpdatedState(onMapClick)
    val currentLongPress = rememberUpdatedState(onMapLongPress)
    val currentWaypointClick = rememberUpdatedState(onWaypointClick)
    val currentScaleCallback = rememberUpdatedState(onMapScaleChanged)

    DisposableEffect(mapView, lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(mapView)
        mapView.graphicsOverlays.add(state.userOverlay)
        mapView.graphicsOverlays.add(state.waypointOverlay)

        state.inputJobs = listOf(
            scope.launch {
                mapView.onSingleTapConfirmed.collectLatest { event ->
                    val identify = mapView.identifyGraphicsOverlay(
                        graphicsOverlay = state.waypointOverlay,
                        screenCoordinate = event.screenCoordinate,
                        tolerance = 26.0,
                        returnPopupsOnly = false,
                        maximumResults = 1
                    ).getOrNull()

                    val id = identify?.geoElements
                        ?.firstOrNull()
                        ?.attributes
                        ?.get(WAYPOINT_ID)
                        ?.toString()
                        ?.toLongOrNull()

                    val waypoint = id?.let { wanted ->
                        currentWaypoints.value.firstOrNull { it.id == wanted }
                    }

                    if (waypoint != null) {
                        currentWaypointClick.value(waypoint)
                    } else if (currentPlacement.value) {
                        toWgs84(mapView.screenToLocation(event.screenCoordinate))?.let {
                            currentMapClick.value(it.y, it.x)
                        }
                    }
                }
            },
            scope.launch {
                mapView.onLongPress.collectLatest { event ->
                    toWgs84(mapView.screenToLocation(event.screenCoordinate))?.let {
                        currentLongPress.value(it.y, it.x)
                    }
                }
            },
            scope.launch {
                mapView.viewpointChanged.collectLatest {
                    val scaleMeters = (mapView.unitsPerDip * 160.0).coerceAtLeast(0.1)
                    if (state.lastScale <= 0.0 ||
                        abs(scaleMeters - state.lastScale) /
                            state.lastScale.coerceAtLeast(0.1) > 0.025
                    ) {
                        state.lastScale = scaleMeters
                        currentScaleCallback.value(scaleMeters)
                    }
                }
            }
        )

        onDispose {
            state.inputJobs.forEach { it.cancel() }
            state.inputJobs = emptyList()
            lifecycleOwner.lifecycle.removeObserver(mapView)
            runCatching { mapView.onDestroy(lifecycleOwner) }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                isAttributionBarVisible = true
                setViewpoint(
                    Viewpoint(
                        Point(
                            location.longitude,
                            location.latitude,
                            SpatialReference.wgs84()
                        ),
                        INITIAL_SCALE
                    )
                )
                state.recenterToken = recenterToken
                applyLayer(
                    mapView = this,
                    state = state,
                    offline = offline,
                    layer = layer
                )
                updateUserLocation(state, location)
                updateWaypoints(state, waypoints)
            }
        },
        update = { view ->
            if (state.currentLayer != layer) {
                applyLayer(view, state, offline, layer)
            }

            updateUserLocation(state, location)
            updateWaypoints(state, waypoints)

            if (state.recenterToken != recenterToken) {
                state.recenterToken = recenterToken
                scope.launch {
                    val center = Point(
                        location.longitude,
                        location.latitude,
                        SpatialReference.wgs84()
                    )
                    view.setViewpointCenter(center, RECENTER_SCALE)
                    heading?.takeIf { it.isFinite() }?.let {
                        view.setViewpointRotation(it.toDouble())
                    }
                }
            }
        }
    )
}

private fun applyLayer(
    mapView: MapView,
    state: ArcMapState,
    offline: OfflineMapManager,
    layer: MapLayer
) {
    val map = ArcGISMap(MapStyles.basemapStyle(layer)).apply {
        initialViewpoint = mapView.getCurrentViewpoint(
            com.arcgismaps.mapping.ViewpointType.CenterAndScale
        )
    }

    if (layer == MapLayer.SATELLITE_TERRAIN) {
        map.operationalLayers.add(
            ArcGISTiledLayer(MapStyles.HILLSHADE_ONLINE).apply {
                opacity = 0.24f
            }
        )
    }

    val packages = offline.packagesFor(layer)
    packages
        .sortedBy {
            when (it.role) {
                OfflineLayerRole.BASE -> 0
                OfflineLayerRole.HILLSHADE -> 1
                OfflineLayerRole.REFERENCE -> 2
            }
        }
        .forEach { item ->
            val cache = TileCache(item.file.absolutePath)
            val tiledLayer = ArcGISTiledLayer(cache).apply {
                opacity = item.opacity
            }
            map.operationalLayers.add(tiledLayer)
        }

    mapView.map = map
    state.currentLayer = layer
}

private fun updateUserLocation(state: ArcMapState, location: Location) {
    val point = Point(
        location.longitude,
        location.latitude,
        SpatialReference.wgs84()
    )

    val existing = state.userGraphic
    if (existing == null) {
        val symbol = SimpleMarkerSymbol(
            SimpleMarkerSymbolStyle.Circle,
            Color.fromRgba(25, 132, 255, 255),
            15.0f
        )
        val graphic = Graphic(point, symbol)
        state.userOverlay.graphics.add(graphic)
        state.userGraphic = graphic
    } else {
        existing.geometry = point
    }
}

private fun updateWaypoints(state: ArcMapState, waypoints: List<Waypoint>) {
    val fingerprint = waypoints.fold(1) { acc, waypoint ->
        var value = 31 * acc + waypoint.id.hashCode()
        value = 31 * value + waypoint.latitude.hashCode()
        value = 31 * value + waypoint.longitude.hashCode()
        value = 31 * value + waypoint.type.hashCode()
        value
    }
    if (fingerprint == state.waypointFingerprint) return

    state.waypointOverlay.graphics.clear()
    waypoints.forEach { waypoint ->
        val symbol = TextSymbol().apply {
            text = waypointSymbol(waypoint.type)
            size = 23.0f
            color = Color.white
            haloColor = Color.fromRgba(20, 28, 23, 255)
            haloWidth = 2.0f
        }
        val graphic = Graphic(
            Point(
                waypoint.longitude,
                waypoint.latitude,
                SpatialReference.wgs84()
            ),
            symbol
        )
        graphic.attributes[WAYPOINT_ID] = waypoint.id
        state.waypointOverlay.graphics.add(graphic)
    }
    state.waypointFingerprint = fingerprint
}

private fun waypointSymbol(type: WaypointType): String = when (type) {
    WaypointType.CAR -> "🚗"
    WaypointType.MUSHROOM -> "🍄"
    WaypointType.WATER -> "💧"
    WaypointType.DANGER -> "⚠"
    WaypointType.FAVORITE -> "★"
    WaypointType.CUSTOM -> "📍"
}

private fun toWgs84(point: Point?): Point? {
    if (point == null) return null
    return GeometryEngine.projectOrNull(point, SpatialReference.wgs84())
}

private const val WAYPOINT_ID = "waypointId"
private const val INITIAL_SCALE = 9_000.0
private const val RECENTER_SCALE = 4_000.0
