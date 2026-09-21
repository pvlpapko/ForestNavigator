package app.forestnav.ui

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.forestnav.data.Waypoint
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

private class NativeMapState {
    var map: MapLibreMap? = null
    var userMarker: Marker? = null
    var waypointMarkers: List<Marker> = emptyList()
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
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { NativeMapState() }
    val mapView = remember { MapView(context) }
    val currentLongPress = rememberUpdatedState(onMapLongPress)

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
                    map.addOnMapLongClickListener { point ->
                        currentLongPress.value(point.latitude, point.longitude)
                        true
                    }

                    val target = LatLng(location.latitude, location.longitude)
                    map.cameraPosition = CameraPosition.Builder()
                        .target(target)
                        .zoom(10.5)
                        .build()

                    state.lastRecenterToken = recenterToken
                    applyStyle(map, state, styleUrl, location, waypoints)
                }
            }
        },
        update = {
            val map = state.map ?: return@AndroidView
            if (styleUrl != null && styleUrl != state.loadedStyle) {
                applyStyle(map, state, styleUrl, location, waypoints)
                return@AndroidView
            }

            updateAnnotations(map, state, location, waypoints)

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
    map: MapLibreMap,
    state: NativeMapState,
    styleUrl: String?,
    location: Location,
    waypoints: List<Waypoint>
) {
    val url = styleUrl ?: return
    state.loadedStyle = url
    map.setStyle(url) {
        state.waypointMarkers = emptyList()
        state.userMarker = null
        state.waypointFingerprint = 0
        updateAnnotations(map, state, location, waypoints)

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

@Suppress("DEPRECATION")
private fun updateAnnotations(
    map: MapLibreMap,
    state: NativeMapState,
    location: Location,
    waypoints: List<Waypoint>
) {
    val fingerprint = waypoints.fold(1) { acc, p -> 31 * acc + p.hashCode() }
    if (fingerprint != state.waypointFingerprint) {
        state.waypointMarkers.forEach { runCatching { map.removeMarker(it) } }
        state.waypointMarkers = waypoints.map { p ->
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(p.latitude, p.longitude))
                    .title(p.name)
                    .snippet(
                        p.accuracyMeters?.let { "Точность ±${String.format("%.1f", it)} м" }
                            ?: "Точка поставлена вручную на карте"
                    )
            )
        }
        state.waypointFingerprint = fingerprint
    }

    val pos = LatLng(location.latitude, location.longitude)
    val marker = state.userMarker
    if (marker == null) {
        state.userMarker = map.addMarker(
            MarkerOptions().position(pos).title("Вы здесь").snippet("GPS ±${location.accuracy.toInt()} м")
        )
    } else {
        marker.position = pos
    }
}
