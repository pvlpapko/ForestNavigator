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
import app.forestnav.data.Waypoint
import app.forestnav.data.WaypointType
import app.forestnav.map.MapStyles
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
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
import kotlin.math.pow

private class NativeMapState {
    var map: MapLibreMap? = null
    var waypointMarkers: List<Marker> = emptyList()
    val waypointByMarkerId = mutableMapOf<Long, Waypoint>()
    var waypointFingerprint: Int = 0
    var loadedStyle: String? = null
    var loadingStyle: String? = null
    val waypointIcons = mutableMapOf<WaypointType, Icon>()
    var initialCameraAnimationDone = false
    var lastRecenterToken: Int = -1
    var lastScaleMeters: Double = 0.0
}

@Composable
fun ForestMapView(
    modifier: Modifier,
    location: Location,
    heading: Float?,
    waypoints: List<Waypoint>,
    styleUrl: String?,
    recenterToken: Int = 0,
    pointPlacementEnabled: Boolean = false,
    onMapClick: (Double, Double) -> Unit = { _, _ -> },
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> },
    onWaypointClick: (Waypoint) -> Unit = {},
    onMapScaleChanged: (Double) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { NativeMapState() }
    val mapView = remember { MapView(context) }
    val currentPlacementEnabled = rememberUpdatedState(pointPlacementEnabled)
    val currentMapClick = rememberUpdatedState(onMapClick)
    val currentLongPress = rememberUpdatedState(onMapLongPress)
    val currentWaypointClick = rememberUpdatedState(onWaypointClick)
    val currentScaleCallback = rememberUpdatedState(onMapScaleChanged)

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
                    map.setMaxZoomPreference(25.5)

                    map.addOnMapClickListener { point ->
                        if (currentPlacementEnabled.value) {
                            currentMapClick.value(point.latitude, point.longitude)
                            true
                        } else false
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
                        } else false
                    }

                    map.addOnCameraMoveListener {
                        publishScale(map, mapView, state, currentScaleCallback.value)
                    }
                    map.addOnCameraIdleListener {
                        publishScale(map, mapView, state, currentScaleCallback.value, force = true)
                    }

                    val target = LatLng(location.latitude, location.longitude)
                    map.cameraPosition = CameraPosition.Builder()
                        .target(target)
                        .zoom(10.5)
                        .build()

                    state.lastRecenterToken = recenterToken
                    applyStyle(context, map, state, styleUrl, location, heading, waypoints)
                    mapView.post {
                        publishScale(map, mapView, state, currentScaleCallback.value, force = true)
                    }
                }
            }
        },
        update = {
            val map = state.map ?: return@AndroidView

            if (styleUrl != null && styleUrl != state.loadedStyle && styleUrl != state.loadingStyle) {
                applyStyle(context, map, state, styleUrl, location, heading, waypoints)
                return@AndroidView
            }

            updateLocationPuck(map, location, heading)
            updateWaypointAnnotations(context, map, state, waypoints)

            if (state.lastRecenterToken != recenterToken) {
                val target = CameraPosition.Builder(map.cameraPosition)
                    .target(LatLng(location.latitude, location.longitude))
                    .zoom(maxOf(map.cameraPosition.zoom, 15.5))
                    .build()
                map.easeCamera(CameraUpdateFactory.newCameraPosition(target), 900)
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
    val referencePixels = minOf(width * 0.32, 160.0 * mapView.resources.displayMetrics.density)
    val scaleMeters = (metersPerPixel * referencePixels).coerceAtLeast(1.0)

    val changedEnough = state.lastScaleMeters <= 0.0 ||
        abs(scaleMeters - state.lastScaleMeters) / state.lastScaleMeters > 0.025
    if (force || changedEnough) {
        state.lastScaleMeters = scaleMeters
        callback(scaleMeters)
    }
}

private fun applyStyle(
    context: Context,
    map: MapLibreMap,
    state: NativeMapState,
    styleUrl: String?,
    location: Location,
    heading: Float?,
    waypoints: List<Waypoint>
) {
    val url = styleUrl ?: return
    if (url == state.loadedStyle || url == state.loadingStyle) return
    state.loadingStyle = url

    val onLoaded = Style.OnStyleLoaded { style ->
        state.loadedStyle = url
        state.loadingStyle = null
        state.waypointMarkers = emptyList()
        state.waypointByMarkerId.clear()
        state.waypointFingerprint = 0

        setupLocationPuck(context, map, style, location, heading)
        updateWaypointAnnotations(context, map, state, waypoints)

        if (!state.initialCameraAnimationDone) {
            val target = CameraPosition.Builder(map.cameraPosition)
                .target(LatLng(location.latitude, location.longitude))
                .zoom(16.0)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(target), 2200)
            state.initialCameraAnimationDone = true
        }
    }

    if (MapStyles.isJsonStyle(url)) {
        map.setStyle(Style.Builder().fromJson(MapStyles.jsonPayload(url)), onLoaded)
    } else {
        map.setStyle(url, onLoaded)
    }
}

@SuppressLint("MissingPermission")
private fun setupLocationPuck(
    context: Context,
    map: MapLibreMap,
    style: Style,
    location: Location,
    heading: Float?
) {
    val component = map.locationComponent

    if (!component.isLocationComponentActivated) {
        val puckOptions = LocationComponentOptions.builder(context)
            .bearingOnTop(true)
            .compassAnimationEnabled(true)
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
    component.renderMode = if (heading != null) RenderMode.COMPASS else RenderMode.NORMAL
    component.cameraMode = if (heading != null) {
        CameraMode.TRACKING_COMPASS
    } else {
        CameraMode.TRACKING
    }
    component.setMaxAnimationFps(20)
    component.forceLocationUpdate(location)
}

@SuppressLint("MissingPermission")
private fun updateLocationPuck(map: MapLibreMap, location: Location, heading: Float?) {
    val component = map.locationComponent
    if (component.isLocationComponentActivated && component.isLocationComponentEnabled) {
        component.renderMode = if (heading != null) RenderMode.COMPASS else RenderMode.NORMAL
        component.cameraMode = if (heading != null) {
            CameraMode.TRACKING_COMPASS
        } else {
            CameraMode.TRACKING
        }
        component.forceLocationUpdate(location)
    }
}

@Suppress("DEPRECATION")
private fun updateWaypointAnnotations(
    context: Context,
    map: MapLibreMap,
    state: NativeMapState,
    waypoints: List<Waypoint>
) {
    val fingerprint = waypoints.fold(1) { acc, p -> 31 * acc + p.hashCode() }
    if (fingerprint == state.waypointFingerprint) return

    state.waypointMarkers.forEach { marker -> runCatching { map.removeMarker(marker) } }
    state.waypointMarkers = emptyList()
    state.waypointByMarkerId.clear()

    state.waypointMarkers = waypoints.map { p ->
        val marker = map.addMarker(
            MarkerOptions()
                .position(LatLng(p.latitude, p.longitude))
                .icon(state.waypointIcons.getOrPut(p.type) { createWaypointIcon(context, p.type) })
                .title(p.name)
                .snippet(
                    p.accuracyMeters?.let { "Точность ±${String.format("%.1f", it)} м" }
                        ?: "Точка поставлена вручную на карте"
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
