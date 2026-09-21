package app.forestnav.ui

import android.annotation.SuppressLint
import android.content.Context
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
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private class NativeMapState {
    var map: MapLibreMap? = null
    var waypointMarkers: List<Marker> = emptyList()
    val waypointByMarkerId = mutableMapOf<Long, Waypoint>()
    var waypointFingerprint: Int = 0
    var loadedStyle: String? = null
    var initialCameraAnimationDone = false
    var lastRecenterToken: Int = -1
}

@Composable
fun ForestMapView(
    modifier: Modifier,
    location: Location,
    waypoints: List<Waypoint>,
    styleUrl: String?,
    recenterToken: Int = 0,
    pointPlacementEnabled: Boolean = false,
    onMapClick: (Double, Double) -> Unit = { _, _ -> },
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> },
    onWaypointClick: (Waypoint) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { NativeMapState() }
    val mapView = remember { MapView(context) }
    val currentPlacementEnabled = rememberUpdatedState(pointPlacementEnabled)
    val currentMapClick = rememberUpdatedState(onMapClick)
    val currentLongPress = rememberUpdatedState(onMapLongPress)
    val currentWaypointClick = rememberUpdatedState(onWaypointClick)

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

                    val target = LatLng(location.latitude, location.longitude)
                    map.cameraPosition = CameraPosition.Builder()
                        .target(target)
                        .zoom(10.5)
                        .build()

                    state.lastRecenterToken = recenterToken
                    applyStyle(context, map, state, styleUrl, location, waypoints)
                }
            }
        },
        update = {
            val map = state.map ?: return@AndroidView

            if (styleUrl != null && styleUrl != state.loadedStyle) {
                applyStyle(context, map, state, styleUrl, location, waypoints)
                return@AndroidView
            }

            updateLocationPuck(map, location)
            updateWaypointAnnotations(map, state, waypoints)

            if (state.lastRecenterToken != recenterToken) {
                val target = CameraPosition.Builder()
                    .target(LatLng(location.latitude, location.longitude))
                    .zoom(maxOf(map.cameraPosition.zoom, 15.5))
                    .build()
                map.easeCamera(CameraUpdateFactory.newCameraPosition(target), 900)
                state.lastRecenterToken = recenterToken
            }
        }
    )
}

private fun applyStyle(
    context: Context,
    map: MapLibreMap,
    state: NativeMapState,
    styleUrl: String?,
    location: Location,
    waypoints: List<Waypoint>
) {
    val url = styleUrl ?: return
    state.loadedStyle = url

    map.setStyle(url) { style ->
        state.waypointMarkers = emptyList()
        state.waypointByMarkerId.clear()
        state.waypointFingerprint = 0

        setupLocationPuck(context, map, style, location)
        updateWaypointAnnotations(map, state, waypoints)

        if (!state.initialCameraAnimationDone) {
            val target = CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(16.0)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(target), 2200)
            state.initialCameraAnimationDone = true
        }
    }
}

@SuppressLint("MissingPermission")
private fun setupLocationPuck(
    context: Context,
    map: MapLibreMap,
    style: Style,
    location: Location
) {
    val component = map.locationComponent

    if (!component.isLocationComponentActivated) {
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(false)
                .useSpecializedLocationLayer(false)
                .build()
        )
    }

    component.isLocationComponentEnabled = true
    component.cameraMode = CameraMode.NONE
    component.renderMode = RenderMode.NORMAL
    component.setMaxAnimationFps(15)
    component.forceLocationUpdate(location)
}

@SuppressLint("MissingPermission")
private fun updateLocationPuck(map: MapLibreMap, location: Location) {
    val component = map.locationComponent
    if (component.isLocationComponentActivated && component.isLocationComponentEnabled) {
        component.forceLocationUpdate(location)
    }
}

@Suppress("DEPRECATION")
private fun updateWaypointAnnotations(
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
