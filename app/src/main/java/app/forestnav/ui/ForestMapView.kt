package app.forestnav.ui

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import app.forestnav.map.OfflinePackageFormat
import com.arcgismaps.Color
import com.arcgismaps.geometry.GeometryEngine
import com.arcgismaps.geometry.Point
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.location.CustomLocationDataSource
import com.arcgismaps.location.Location as ArcGISLocation
import com.arcgismaps.location.LocationDisplayAutoPanMode
import com.arcgismaps.mapping.ArcGISMap
import com.arcgismaps.mapping.Basemap
import com.arcgismaps.mapping.Viewpoint
import com.arcgismaps.mapping.layers.ArcGISTiledLayer
import com.arcgismaps.mapping.layers.ArcGISVectorTiledLayer
import com.arcgismaps.mapping.layers.Layer
import com.arcgismaps.mapping.layers.TileCache
import com.arcgismaps.mapping.symbology.TextSymbol
import com.arcgismaps.mapping.view.Graphic
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.arcgismaps.mapping.view.MapView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.abs

private class AppLocationProvider(
    sourceLocations: StateFlow<Location?>,
    sourceHeadings: StateFlow<Float?>
) : CustomLocationDataSource.LocationProvider {

    override val locations: Flow<ArcGISLocation> =
        sourceLocations
            .filterNotNull()
            .map(::toArcGISLocation)

    override val headings: Flow<Double> =
        sourceHeadings
            .filterNotNull()
            .map { it.toDouble() }
}

private class ArcMapState {
    val waypointOverlay = GraphicsOverlay()
    val location = MutableStateFlow<Location?>(null)
    val heading = MutableStateFlow<Float?>(null)

    val locationDataSource = CustomLocationDataSource {
        AppLocationProvider(location, heading)
    }

    var layer: MapLayer? = null
    var online: Boolean? = null
    var offlineRegionId: Long? = null
    var recenterToken: Int = -1

    var waypointFingerprint: Int = 0
    var inputJobs: List<Job> = emptyList()
    var locationStartJob: Job? = null
    var lastScaleMeters: Double = 0.0

    private var lastLocationElapsedNs = Long.MIN_VALUE
    private var lastLocationTimeMs = Long.MIN_VALUE
    private var lastLatitude = Double.NaN
    private var lastLongitude = Double.NaN

    fun submitLocation(value: Location) {
        val elapsedNs = value.elapsedRealtimeNanos
        val duplicate =
            if (elapsedNs > 0L && lastLocationElapsedNs > 0L) {
                elapsedNs == lastLocationElapsedNs
            } else {
                value.time == lastLocationTimeMs &&
                    value.latitude == lastLatitude &&
                    value.longitude == lastLongitude
            }

        if (duplicate) return

        lastLocationElapsedNs = elapsedNs
        lastLocationTimeMs = value.time
        lastLatitude = value.latitude
        lastLongitude = value.longitude
        location.value = Location(value)
    }

    fun submitHeading(value: Float?) {
        if (value == null) return

        val previous = heading.value
        if (
            previous == null ||
            abs(shortestAngle(value - previous)) >= HEADING_UPDATE_EPSILON
        ) {
            heading.value = value
        }
    }
}

@Composable
fun ForestMapView(
    modifier: Modifier,
    location: Location,
    heading: Float?,
    waypoints: List<Waypoint>,
    layer: MapLayer,
    online: Boolean,
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

    LaunchedEffect(
        location.elapsedRealtimeNanos,
        location.time,
        location.latitude,
        location.longitude
    ) {
        state.submitLocation(location)
    }

    LaunchedEffect(heading) {
        state.submitHeading(heading)
    }

    DisposableEffect(mapView, lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(mapView)
        mapView.graphicsOverlays.add(state.waypointOverlay)

        state.inputJobs = listOf(
            scope.launch {
                mapView.onSingleTapConfirmed.collectLatest { event ->
                    val identified = mapView.identifyGraphicsOverlay(
                        graphicsOverlay = state.waypointOverlay,
                        screenCoordinate = event.screenCoordinate,
                        tolerance = 26.0,
                        returnPopupsOnly = false,
                        maximumResults = 1
                    ).getOrNull()

                    val waypointId = identified
                        ?.geoElements
                        ?.firstOrNull()
                        ?.attributes
                        ?.get(WAYPOINT_ID)
                        ?.toString()
                        ?.toLongOrNull()

                    val waypoint = waypointId?.let { id ->
                        currentWaypoints.value.firstOrNull {
                            it.id == id
                        }
                    }

                    if (waypoint != null) {
                        currentWaypointClick.value(waypoint)
                    } else if (currentPlacement.value) {
                        toWgs84(
                            mapView.screenToLocation(event.screenCoordinate)
                        )?.let { point ->
                            currentMapClick.value(point.y, point.x)
                        }
                    }
                }
            },
            scope.launch {
                mapView.onLongPress.collectLatest { event ->
                    toWgs84(
                        mapView.screenToLocation(event.screenCoordinate)
                    )?.let { point ->
                        currentLongPress.value(point.y, point.x)
                    }
                }
            },
            scope.launch {
                mapView.viewpointChanged.collectLatest {
                    val scaleMeters =
                        (mapView.unitsPerDip * 160.0).coerceAtLeast(0.1)

                    val previous = state.lastScaleMeters
                    val changed =
                        previous <= 0.0 ||
                            abs(scaleMeters - previous) /
                            previous.coerceAtLeast(0.1) > 0.025

                    if (changed) {
                        state.lastScaleMeters = scaleMeters
                        currentScaleCallback.value(scaleMeters)
                    }
                }
            }
        )

        onDispose {
            state.inputJobs.forEach { it.cancel() }
            state.inputJobs = emptyList()

            state.locationStartJob?.cancel()
            state.locationStartJob = null

            lifecycleOwner.lifecycle.removeObserver(mapView)
            runCatching {
                mapView.onDestroy(lifecycleOwner)
            }
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
                state.submitLocation(location)
                state.submitHeading(heading)

                val selection = offlineSelection(
                    offline = offline,
                    online = online,
                    layer = layer,
                    location = location
                )

                installMap(
                    mapView = this,
                    state = state,
                    layer = layer,
                    online = online,
                    selection = selection
                )

                configureLocationDisplay(
                    mapView = this,
                    state = state,
                    scope = scope
                )

                updateWaypoints(state, waypoints)
            }
        },
        update = { view ->
            val selection = offlineSelection(
                offline = offline,
                online = online,
                layer = layer,
                location = location
            )

            val mapChanged =
                state.layer != layer ||
                    state.online != online ||
                    (
                        !online &&
                            state.offlineRegionId != selection?.regionId
                        )

            if (mapChanged) {
                installMap(
                    mapView = view,
                    state = state,
                    layer = layer,
                    online = online,
                    selection = selection
                )

                scope.launch {
                    view.locationDisplay.setAutoPanMode(
                        LocationDisplayAutoPanMode.CompassNavigation
                    )
                }
            }

            updateWaypoints(state, waypoints)

            if (state.recenterToken != recenterToken) {
                state.recenterToken = recenterToken

                scope.launch {
                    val center = Point(
                        location.longitude,
                        location.latitude,
                        SpatialReference.wgs84()
                    )

                    view.setViewpointCenter(
                        center,
                        RECENTER_SCALE
                    )

                    view.locationDisplay.initialZoomScale =
                        RECENTER_SCALE

                    view.locationDisplay.setAutoPanMode(
                        LocationDisplayAutoPanMode.CompassNavigation
                    )
                }
            }
        }
    )
}

private fun offlineSelection(
    offline: OfflineMapManager,
    online: Boolean,
    layer: MapLayer,
    location: Location
): OfflineMapManager.OfflineSelection? {
    if (online) return null

    return offline.selectionFor(
        layer = layer,
        latitude = location.latitude,
        longitude = location.longitude
    )
}

private fun installMap(
    mapView: MapView,
    state: ArcMapState,
    layer: MapLayer,
    online: Boolean,
    selection: OfflineMapManager.OfflineSelection?
) {
    val currentViewpoint = mapView.getCurrentViewpoint(
        com.arcgismaps.mapping.ViewpointType.CenterAndScale
    )

    val map = if (online) {
        createOnlineMap(
            layer = layer,
            currentViewpoint = currentViewpoint
        )
    } else {
        createOfflineMap(
            selection = selection,
            currentViewpoint = currentViewpoint
        )
    }

    mapView.map = map

    state.layer = layer
    state.online = online
    state.offlineRegionId = selection?.regionId
}

private fun createOnlineMap(
    layer: MapLayer,
    currentViewpoint: Viewpoint?
): ArcGISMap =
    ArcGISMap(MapStyles.basemapStyle(layer)).apply {
        initialViewpoint = currentViewpoint

        if (layer == MapLayer.SATELLITE_TERRAIN) {
            operationalLayers.add(
                ArcGISTiledLayer(
                    MapStyles.HILLSHADE_ONLINE
                ).apply {
                    opacity = 0.22f
                }
            )
        }
    }

private fun createOfflineMap(
    selection: OfflineMapManager.OfflineSelection?,
    currentViewpoint: Viewpoint?
): ArcGISMap {
    if (selection == null) {
        return ArcGISMap(
            SpatialReference.webMercator()
        ).apply {
            initialViewpoint = currentViewpoint
        }
    }

    val baseLayers = mutableListOf<Layer>()
    val referenceLayers = mutableListOf<Layer>()
    val overlays = mutableListOf<Layer>()

    selection.packages.forEach { item ->
        val localLayer: Layer =
            when (item.format) {
                OfflinePackageFormat.RASTER ->
                    ArcGISTiledLayer(
                        TileCache(item.file.absolutePath)
                    )

                OfflinePackageFormat.VECTOR ->
                    ArcGISVectorTiledLayer(
                        item.file.absolutePath
                    )
            }

        localLayer.opacity = item.opacity

        when (item.role) {
            OfflineLayerRole.BASE ->
                baseLayers += localLayer

            OfflineLayerRole.HILLSHADE ->
                overlays += localLayer

            OfflineLayerRole.REFERENCE ->
                referenceLayers += localLayer
        }
    }

    if (
        baseLayers.isEmpty() &&
        referenceLayers.isEmpty() &&
        overlays.isEmpty()
    ) {
        return ArcGISMap(
            SpatialReference.webMercator()
        ).apply {
            initialViewpoint = currentViewpoint
        }
    }

    return ArcGISMap(
        Basemap(
            baseLayers = baseLayers,
            referenceLayers = referenceLayers
        )
    ).apply {
        initialViewpoint = currentViewpoint
        operationalLayers.addAll(overlays)
    }
}

private fun configureLocationDisplay(
    mapView: MapView,
    state: ArcMapState,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val display = mapView.locationDisplay

    display.showLocation = true
    display.showAccuracy = true
    display.showPingAnimationSymbol = false
    display.useCourseSymbolOnMovement = false
    display.initialZoomScale = INITIAL_SCALE
    display.navigationPointHeightFactor = 0.5f
    display.dataSource = state.locationDataSource

    state.locationStartJob?.cancel()
    state.locationStartJob = scope.launch {
        state.locationDataSource
            .start()
            .onSuccess {
                display.setAutoPanMode(
                    LocationDisplayAutoPanMode.CompassNavigation
                )
            }
    }
}

private fun toArcGISLocation(source: Location): ArcGISLocation =
    ArcGISLocation.create(
        position = Point(
            source.longitude,
            source.latitude,
            SpatialReference.wgs84()
        ),
        horizontalAccuracy =
            if (source.hasAccuracy()) {
                source.accuracy.toDouble()
            } else {
                Double.NaN
            },
        verticalAccuracy =
            if (source.hasVerticalAccuracy()) {
                source.verticalAccuracyMeters.toDouble()
            } else {
                Double.NaN
            },
        speed =
            if (source.hasSpeed()) {
                source.speed.toDouble()
            } else {
                Double.NaN
            },
        course =
            if (source.hasBearing()) {
                source.bearing.toDouble()
            } else {
                Double.NaN
            },
        lastKnown =
            System.currentTimeMillis() - source.time >
                LAST_KNOWN_AFTER_MS,
        timestamp = Instant.ofEpochMilli(source.time)
    )

private fun updateWaypoints(
    state: ArcMapState,
    waypoints: List<Waypoint>
) {
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
            haloColor = Color.fromRgba(
                20,
                28,
                23,
                255
            )
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

private fun waypointSymbol(type: WaypointType): String =
    when (type) {
        WaypointType.CAR -> "🚗"
        WaypointType.MUSHROOM -> "🍄"
        WaypointType.WATER -> "💧"
        WaypointType.DANGER -> "⚠"
        WaypointType.FAVORITE -> "★"
        WaypointType.CUSTOM -> "📍"
    }

private fun toWgs84(point: Point?): Point? {
    if (point == null) return null

    return GeometryEngine.projectOrNull(
        point,
        SpatialReference.wgs84()
    )
}

private fun shortestAngle(value: Float): Float {
    var delta = value % 360f

    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f

    return delta
}

private const val WAYPOINT_ID = "waypointId"
private const val INITIAL_SCALE = 7_500.0
private const val RECENTER_SCALE = 3_500.0
private const val LAST_KNOWN_AFTER_MS = 5_000L
private const val HEADING_UPDATE_EPSILON = 0.05f
