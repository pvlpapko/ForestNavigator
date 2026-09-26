package app.forestnav.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.forestnav.data.TrackPoint
import app.forestnav.data.Waypoint
import app.forestnav.data.WaypointType
import app.forestnav.map.MapLayer
import app.forestnav.map.MapStyles
import app.forestnav.map.OfflineMapManager
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow

private class NativeMapState {
    var map: MapLibreMap? = null
    var waypointMarkers: List<Marker> = emptyList()
    val waypointByMarkerId = mutableMapOf<Long, Waypoint>()
    var waypointFingerprint: Int = 0
    var trackPolyline: Polyline? = null
    var trackFingerprint: Int = 0
    var measurementPolyline: Polyline? = null
    var measurementMarkers: List<Marker> = emptyList()
    var measurementFingerprint: Int = 0
    val measurementIcons = mutableMapOf<String, Icon>()
    var loadedStyle: String? = null
    var loadingStyle: String? = null
    val waypointIcons = mutableMapOf<WaypointType, Icon>()
    var initialCameraAnimationDone = false
    var lastRecenterToken: Int = -1
    var lastScaleMeters: Double = 0.0
    var gestureActive = false
    var followUser = true
    var lastLocationElapsedNs: Long = Long.MIN_VALUE
    var lastLocationTimeMs: Long = Long.MIN_VALUE
    var lastLocationLatitude: Double = Double.NaN
    var lastLocationLongitude: Double = Double.NaN
}

@Composable
fun ForestMapView(
    modifier: Modifier,
    location: Location,
    heading: Float?,
    waypoints: List<Waypoint>,
    trackPoints: List<TrackPoint> = emptyList(),
    measurementPoints: List<Pair<Double, Double>> = emptyList(),
    layer: MapLayer,
    online: Boolean,
    initialScaleMeters: Double = DEFAULT_INITIAL_SCALE_METERS,
    recenterToken: Int = 0,
    pointPlacementEnabled: Boolean = false,
    onMapClick: (Double, Double) -> Unit = { _, _ -> },
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> },
    onWaypointClick: (Waypoint) -> Unit = {},
    onMapScaleChanged: (Double) -> Unit = {}
) {
    // heading is still consumed by the screen's compass/navigation UI.
    // Map rotation itself intentionally uses MapLibre's native compass animator.
    @Suppress("UNUSED_VARIABLE")
    val externalHeading = heading

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { NativeMapState() }
    val mapView = remember { MapView(context) }
    val currentPlacementEnabled = rememberUpdatedState(pointPlacementEnabled)
    val currentMapClick = rememberUpdatedState(onMapClick)
    val currentLongPress = rememberUpdatedState(onMapLongPress)
    val currentWaypointClick = rememberUpdatedState(onWaypointClick)
    val currentScaleCallback = rememberUpdatedState(onMapScaleChanged)

    val offlineManager = remember { OfflineMapManager(context) }
    val styleSpec = if (online) {
        MapStyles.onlineStyle(layer)
            ?: MapStyles.emptyStyle(layer)
    } else {
        offlineManager.selectionFor(
            layer = layer,
            latitude = location.latitude,
            longitude = location.longitude
        )?.style ?: MapStyles.emptyStyle(layer)
    }
    val styleKey = styleSpec.key
    val styleJson = styleSpec.json

    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onCreate(null)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.onPause() }
            runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    state.map = map

                    map.uiSettings.isCompassEnabled = false
                    map.uiSettings.isLogoEnabled = true
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.isRotateGesturesEnabled = true
                    map.uiSettings.isTiltGesturesEnabled = false
                    map.setMaxZoomPreference(25.5)

                    map.addOnMapClickListener { point ->
                        if (currentPlacementEnabled.value) {
                            currentMapClick.value(point.latitude, point.longitude)
                            true
                        } else {
                            false
                        }
                    }

                    map.addOnMapLongClickListener { point ->
                        currentLongPress.value(point.latitude, point.longitude)
                        true
                    }

                    @Suppress("DEPRECATION")
                    map.setOnMarkerClickListener { marker ->
                        val waypoint = state.waypointByMarkerId[marker.id]
                        if (waypoint != null) {
                            currentWaypointClick.value(waypoint)
                            true
                        } else {
                            false
                        }
                    }

                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            // A manual pan/zoom/rotate switches to free exploration.
                            // Nothing recenters again until the user taps "Я здесь".
                            state.gestureActive = true
                            state.followUser = false
                            val component = map.locationComponent
                            if (component.isLocationComponentActivated) {
                                component.cameraMode = CameraMode.NONE
                            }
                        }
                    }

                    map.addOnCameraMoveListener {
                        publishScale(map, mapView, state, currentScaleCallback.value)
                    }

                    map.addOnCameraIdleListener {
                        publishScale(
                            map,
                            mapView,
                            state,
                            currentScaleCallback.value,
                            force = true
                        )
                        state.gestureActive = false
                    }

                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(location.latitude, location.longitude))
                        .zoom(
                            zoomForScale(
                                latitude = location.latitude,
                                scaleMeters = initialScaleMeters,
                                mapView = mapView
                            )
                        )
                        .bearing(0.0)
                        .tilt(0.0)
                        .build()

                    state.lastRecenterToken = recenterToken
                    applyStyle(
                        context = context,
                        map = map,
                        state = state,
                        styleKey = styleKey,
                        styleJson = styleJson,
                        location = location,
                        waypoints = waypoints,
                        trackPoints = trackPoints,
                        measurementPoints = measurementPoints,
                        mapView = mapView,
                        initialScaleMeters = initialScaleMeters
                    )

                    mapView.post {
                        publishScale(
                            map,
                            mapView,
                            state,
                            currentScaleCallback.value,
                            force = true
                        )
                    }
                }
            }
        },
        update = {
            val map = state.map ?: return@AndroidView

            if (styleJson != null &&
                styleKey != state.loadedStyle &&
                styleKey != state.loadingStyle
            ) {
                applyStyle(
                    context = context,
                    map = map,
                    state = state,
                    styleKey = styleKey,
                    styleJson = styleJson,
                    location = location,
                    waypoints = waypoints,
                    trackPoints = trackPoints,
                    measurementPoints = measurementPoints,
                    mapView = mapView,
                    initialScaleMeters = initialScaleMeters
                )
                return@AndroidView
            }

            updateLocationPuck(map, state, location)
            updateWaypointAnnotations(context, map, state, waypoints)
            updateTrackAnnotation(
                map = map,
                state = state,
                points = trackPoints
            )
            updateMeasurementAnnotations(
                context = context,
                map = map,
                state = state,
                points = measurementPoints
            )

            if (state.lastRecenterToken != recenterToken) {
                state.gestureActive = false
                state.followUser = true
                enableNativeFollow(
                    map = map,
                    location = location,
                    minimumZoom = zoomForScale(
                        latitude = location.latitude,
                        scaleMeters = initialScaleMeters,
                        mapView = mapView
                    ),
                    transitionDurationMs = 550L
                )
                state.lastRecenterToken = recenterToken
            }
        }
    )
}

private fun publishScale(
    map: MapLibreMap,
    mapView: MapView,
    state: NativeMapState,
    callback: (Double) -> Unit,
    force: Boolean = false
) {
    val width = mapView.width
    if (width <= 0) return

    val zoom = map.cameraPosition.zoom
    val latitude = map.cameraPosition.target?.latitude ?: 0.0
    val metersPerPixel = 156543.03392 * cos(Math.toRadians(latitude)) / 2.0.pow(zoom)
    val referencePixels = minOf(
        width * 0.32,
        160.0 * mapView.resources.displayMetrics.density
    )
    val scaleMeters = (metersPerPixel * referencePixels).coerceAtLeast(0.1)

    val changedEnough = state.lastScaleMeters <= 0.0 ||
        abs(scaleMeters - state.lastScaleMeters) /
        state.lastScaleMeters.coerceAtLeast(0.1) > 0.025

    if (force || changedEnough) {
        state.lastScaleMeters = scaleMeters
        callback(scaleMeters)
    }
}

private fun applyStyle(
    context: Context,
    map: MapLibreMap,
    state: NativeMapState,
    styleKey: String,
    styleJson: String?,
    location: Location,
    waypoints: List<Waypoint>,
    trackPoints: List<TrackPoint>,
    measurementPoints: List<Pair<Double, Double>>,
    mapView: MapView,
    initialScaleMeters: Double
) {
    val json = styleJson ?: return
    if (styleKey == state.loadedStyle || styleKey == state.loadingStyle) return
    state.loadingStyle = styleKey

    val onLoaded = Style.OnStyleLoaded { style ->
        state.loadedStyle = styleKey
        state.loadingStyle = null
        state.waypointMarkers = emptyList()
        state.waypointByMarkerId.clear()
        state.waypointFingerprint = 0
        state.trackPolyline = null
        state.trackFingerprint = 0
        state.measurementPolyline = null
        state.measurementMarkers = emptyList()
        state.measurementFingerprint = 0

        setupLocationPuck(
            context = context,
            map = map,
            style = style,
            location = location,
            followUser = state.followUser
        )
        updateWaypointAnnotations(context, map, state, waypoints)
        updateTrackAnnotation(
            map = map,
            state = state,
            points = trackPoints
        )
        updateMeasurementAnnotations(
            context = context,
            map = map,
            state = state,
            points = measurementPoints
        )

        if (!state.initialCameraAnimationDone) {
            state.followUser = true
            enableNativeFollow(
                map = map,
                location = location,
                minimumZoom = zoomForScale(
                    latitude = location.latitude,
                    scaleMeters = initialScaleMeters,
                    mapView = mapView
                ),
                transitionDurationMs = 650L
            )
            state.initialCameraAnimationDone = true
        } else if (state.followUser) {
            enableNativeFollow(
                map = map,
                location = location,
                minimumZoom = null,
                transitionDurationMs = 250L
            )
        }
    }

    map.setStyle(
        Style.Builder().fromJson(json),
        onLoaded
    )
}

@SuppressLint("MissingPermission")
private fun setupLocationPuck(
    context: Context,
    map: MapLibreMap,
    style: Style,
    location: Location,
    followUser: Boolean
) {
    val component = map.locationComponent

    if (!component.isLocationComponentActivated) {
        val puckOptions = LocationComponentOptions.builder(context)
            .bearingOnTop(true)
            .compassAnimationEnabled(true)
            .trackingGesturesManagement(false)
            .build()

        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(false)
                .useSpecializedLocationLayer(false)
                .locationComponentOptions(puckOptions)
                .build()
        )
    }

    component.isLocationComponentEnabled = true
    component.renderMode = RenderMode.COMPASS
    component.setMaxAnimationFps(60)
    component.forceLocationUpdate(location)
    component.cameraMode = if (followUser) CameraMode.TRACKING_COMPASS else CameraMode.NONE
}

@SuppressLint("MissingPermission")
private fun updateLocationPuck(
    map: MapLibreMap,
    state: NativeMapState,
    location: Location
) {
    val elapsedNs = location.elapsedRealtimeNanos
    val sameSample = if (elapsedNs > 0L && state.lastLocationElapsedNs > 0L) {
        elapsedNs == state.lastLocationElapsedNs
    } else {
        location.time == state.lastLocationTimeMs &&
            location.latitude == state.lastLocationLatitude &&
            location.longitude == state.lastLocationLongitude
    }
    if (sameSample) return

    state.lastLocationElapsedNs = elapsedNs
    state.lastLocationTimeMs = location.time
    state.lastLocationLatitude = location.latitude
    state.lastLocationLongitude = location.longitude

    val component = map.locationComponent
    if (component.isLocationComponentActivated && component.isLocationComponentEnabled) {
        component.forceLocationUpdate(location)
    }
}

@SuppressLint("MissingPermission")
private fun enableNativeFollow(
    map: MapLibreMap,
    location: Location,
    minimumZoom: Double?,
    transitionDurationMs: Long
) {
    val component = map.locationComponent
    if (!component.isLocationComponentActivated || !component.isLocationComponentEnabled) return

    component.forceLocationUpdate(location)
    component.renderMode = RenderMode.COMPASS
    component.setMaxAnimationFps(60)

    val targetZoom = minimumZoom
    component.setCameraMode(
        CameraMode.TRACKING_COMPASS,
        transitionDurationMs,
        targetZoom,
        null,
        0.0,
        null
    )
}

@Suppress("DEPRECATION")
private fun updateTrackAnnotation(
    map: MapLibreMap,
    state: NativeMapState,
    points: List<TrackPoint>
) {
    val fingerprint =
        points.size * 31 +
            (points.lastOrNull()?.latitude?.hashCode() ?: 0) * 17 +
            (points.lastOrNull()?.longitude?.hashCode() ?: 0)

    if (fingerprint == state.trackFingerprint) return

    state.trackPolyline?.let { line ->
        runCatching { map.removePolyline(line) }
    }
    state.trackPolyline = null

    if (points.size >= 2) {
        @Suppress("DEPRECATION")
        state.trackPolyline = map.addPolyline(
            PolylineOptions()
                .addAll(
                    points.map {
                        LatLng(
                            it.latitude,
                            it.longitude
                        )
                    }
                )
                .color(Color.rgb(255, 140, 0))
                .width(6f)
                .alpha(0.95f)
        )
    }

    state.trackFingerprint = fingerprint
}

private fun updateMeasurementAnnotations(
    context: Context,
    map: MapLibreMap,
    state: NativeMapState,
    points: List<Pair<Double, Double>>
) {
    val fingerprint =
        points.fold(1) { acc, point ->
            31 * acc + point.hashCode()
        }

    if (fingerprint == state.measurementFingerprint) {
        return
    }

    state.measurementPolyline?.let { line ->
        runCatching { map.removePolyline(line) }
    }
    state.measurementPolyline = null

    state.measurementMarkers.forEach { marker ->
        runCatching { map.removeMarker(marker) }
    }
    state.measurementMarkers = emptyList()

    if (points.size >= 2) {
        @Suppress("DEPRECATION")
        state.measurementPolyline = map.addPolyline(
            PolylineOptions()
                .addAll(
                    points.take(2).map {
                        LatLng(it.first, it.second)
                    }
                )
                .color(Color.rgb(255, 235, 59))
                .width(5f)
                .alpha(1f)
        )
    }

    @Suppress("DEPRECATION")
    state.measurementMarkers =
        points.take(2).mapIndexed { index, point ->
            val label = if (index == 0) "A" else "B"
            map.addMarker(
                MarkerOptions()
                    .position(
                        LatLng(
                            point.first,
                            point.second
                        )
                    )
                    .icon(
                        state.measurementIcons
                            .getOrPut(label) {
                                createMeasurementIcon(
                                    context,
                                    label
                                )
                            }
                    )
                    .title("Точка $label")
            )
        }

    state.measurementFingerprint = fingerprint
}

@Suppress("DEPRECATION")
private fun createMeasurementIcon(
    context: Context,
    label: String
): Icon {
    val density =
        context.resources.displayMetrics.density
    val size =
        (38f * density).toInt().coerceAtLeast(38)

    val bitmap = Bitmap.createBitmap(
        size,
        size,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 235, 59)
        style = Paint.Style.FILL
    }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    val radius = size * 0.42f
    canvas.drawCircle(
        size / 2f,
        size / 2f,
        radius,
        fill
    )
    canvas.drawCircle(
        size / 2f,
        size / 2f,
        radius,
        border
    )

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 20f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    val fm = text.fontMetrics
    val y =
        size / 2f -
            (fm.ascent + fm.descent) / 2f

    canvas.drawText(
        label,
        size / 2f,
        y,
        text
    )

    return IconFactory.getInstance(context)
        .fromBitmap(bitmap)
}

private fun updateWaypointAnnotations(
    context: Context,
    map: MapLibreMap,
    state: NativeMapState,
    waypoints: List<Waypoint>
) {
    val fingerprint = waypoints.fold(1) { acc, p -> 31 * acc + p.hashCode() }
    if (fingerprint == state.waypointFingerprint) return

    state.waypointMarkers.forEach { marker ->
        runCatching { map.removeMarker(marker) }
    }
    state.waypointMarkers = emptyList()
    state.waypointByMarkerId.clear()

    state.waypointMarkers = waypoints.map { p ->
        val marker = map.addMarker(
            MarkerOptions()
                .position(LatLng(p.latitude, p.longitude))
                .icon(
                    state.waypointIcons.getOrPut(p.type) {
                        createWaypointIcon(context, p.type)
                    }
                )
                .title(p.name)
                .snippet(
                    p.accuracyMeters?.let {
                        "Точность ±${String.format("%.1f", it)} м"
                    } ?: "Точка поставлена вручную на карте"
                )
        )
        state.waypointByMarkerId[marker.id] = p
        marker
    }

    state.waypointFingerprint = fingerprint
}

@Suppress("DEPRECATION")
private fun createWaypointIcon(context: Context, type: WaypointType): Icon {
    val density = context.resources.displayMetrics.density
    val size = (48f * density).toInt().coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 20, 30, 24)
        style = Paint.Style.FILL
    }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    val radius = size * 0.46f
    canvas.drawCircle(size / 2f, size / 2f, radius, bg)
    canvas.drawCircle(size / 2f, size / 2f, radius, ring)

    val symbol = when (type) {
        WaypointType.CAR -> "🚗"
        WaypointType.MUSHROOM -> "🍄"
        WaypointType.WATER -> "💧"
        WaypointType.DANGER -> "⚠"
        WaypointType.FAVORITE -> "★"
        WaypointType.CUSTOM -> "📍"
    }

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 30f * density
        typeface = Typeface.DEFAULT_BOLD
        color = if (type == WaypointType.FAVORITE) Color.YELLOW else Color.WHITE
    }

    val fm = paint.fontMetrics
    val y = size / 2f - (fm.ascent + fm.descent) / 2f
    canvas.drawText(symbol, size / 2f, y, paint)

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

private fun zoomForScale(
    latitude: Double,
    scaleMeters: Double,
    mapView: MapView
): Double {
    val safeScale = scaleMeters.coerceIn(50.0, 5_000.0)
    val density = mapView.resources.displayMetrics.density
    val referencePixels = if (mapView.width > 0) {
        minOf(
            mapView.width * 0.32,
            160.0 * density
        )
    } else {
        160.0 * density
    }

    val numerator =
        156543.03392 *
            cos(Math.toRadians(latitude))
                .coerceAtLeast(0.05) *
            referencePixels

    return log2(
        numerator / safeScale
    ).coerceIn(2.0, 23.0)
}

private const val DEFAULT_INITIAL_SCALE_METERS = 500.0
