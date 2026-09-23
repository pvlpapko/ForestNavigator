package app.forestnav.ui

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
import app.forestnav.map.LocalMapStyleServer
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.gestures.addOnMapLongClickListener
import com.mapbox.maps.plugin.gestures.gestures
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

private class Native3DMapState {
    var waypointManager: PointAnnotationManager? = null
    var userManager: PointAnnotationManager? = null
    var userAnnotation: PointAnnotation? = null
    val waypointByAnnotationId = mutableMapOf<String, Waypoint>()
    var waypointFingerprint: Int = 0
    var styleLoaded = false
    var lastRecenterToken = -1
    var lastScaleMeters = 0.0
}

@Composable
fun Forest3DMapView(
    modifier: Modifier,
    location: Location,
    heading: Float?,
    waypoints: List<Waypoint>,
    recenterToken: Int = 0,
    pointPlacementEnabled: Boolean = false,
    onMapClick: (Double, Double) -> Unit = { _, _ -> },
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> },
    onWaypointClick: (Waypoint) -> Unit = {},
    onMapScaleChanged: (Double) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    val state = remember { Native3DMapState() }

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
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mapView.onStart()
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                gestures.pitchEnabled = true
                gestures.rotateEnabled = true
                gestures.scrollEnabled = true
                gestures.pinchToZoomEnabled = true

                gestures.addOnMapClickListener { point ->
                    if (currentPlacementEnabled.value) {
                        currentMapClick.value(point.latitude(), point.longitude())
                        true
                    } else {
                        false
                    }
                }

                gestures.addOnMapLongClickListener { point ->
                    currentLongPress.value(point.latitude(), point.longitude())
                    true
                }

                val mapboxMap = mapboxMap
                mapboxMap.setCamera(
                    CameraOptions.Builder()
                        .center(Point.fromLngLat(location.longitude, location.latitude))
                        .zoom(15.5)
                        .pitch(DEFAULT_PITCH)
                        .bearing(heading?.toDouble() ?: 0.0)
                        .build()
                )

                mapboxMap.loadStyle(LocalMapStyleServer.threeDStyleJson()) {
                    state.styleLoaded = true
                    state.lastRecenterToken = recenterToken

                    state.waypointManager = annotations.createPointAnnotationManager().apply {
                        addClickListener { annotation ->
                            val waypoint = state.waypointByAnnotationId[annotation.id]
                            if (waypoint != null) {
                                currentWaypointClick.value(waypoint)
                                true
                            } else {
                                false
                            }
                        }
                    }

                    state.userManager = annotations.createPointAnnotationManager()
                    updateUserAnnotation(context, state, location)
                    updateWaypointAnnotations3D(context, state, waypoints)
                    publish3DScale(mapView, state, currentScaleCallback.value, force = true)
                }
            }
        },
        update = {
            if (!state.styleLoaded) return@AndroidView

            updateUserAnnotation(context, state, location)
            updateWaypointAnnotations3D(context, state, waypoints)

            if (state.lastRecenterToken != recenterToken) {
                val current = mapView.mapboxMap.cameraState
                mapView.mapboxMap.setCamera(
                    CameraOptions.Builder()
                        .center(Point.fromLngLat(location.longitude, location.latitude))
                        .zoom(maxOf(current.zoom, 16.0))
                        .pitch(DEFAULT_PITCH)
                        .bearing(heading?.toDouble() ?: current.bearing)
                        .build()
                )
                state.lastRecenterToken = recenterToken
            }

            publish3DScale(mapView, state, currentScaleCallback.value)
        }
    )
}

private fun updateUserAnnotation(
    context: android.content.Context,
    state: Native3DMapState,
    location: Location
) {
    val manager = state.userManager ?: return
    val point = Point.fromLngLat(location.longitude, location.latitude)
    val existing = state.userAnnotation

    if (existing == null) {
        state.userAnnotation = manager.create(
            PointAnnotationOptions()
                .withPoint(point)
                .withIconImage(create3DUserIcon(context))
                .withIconSize(1.0)
        )
    } else {
        existing.point = point
        manager.update(existing)
    }
}

private fun updateWaypointAnnotations3D(
    context: android.content.Context,
    state: Native3DMapState,
    waypoints: List<Waypoint>
) {
    val manager = state.waypointManager ?: return
    val fingerprint = waypoints.fold(1) { acc, p -> 31 * acc + p.hashCode() }
    if (fingerprint == state.waypointFingerprint) return

    manager.deleteAll()
    state.waypointByAnnotationId.clear()

    waypoints.forEach { p ->
        val annotation = manager.create(
            PointAnnotationOptions()
                .withPoint(Point.fromLngLat(p.longitude, p.latitude))
                .withIconImage(create3DWaypointIcon(context, p.type))
                .withIconSize(1.0)
                .withTextField(p.name)
                .withTextColor(Color.WHITE)
                .withTextHaloColor(Color.BLACK)
                .withTextHaloWidth(1.3)
                .withTextOffset(listOf(0.0, -2.4))
        )
        state.waypointByAnnotationId[annotation.id] = p
    }

    state.waypointFingerprint = fingerprint
}

private fun publish3DScale(
    mapView: MapView,
    state: Native3DMapState,
    callback: (Double) -> Unit,
    force: Boolean = false
) {
    val width = mapView.width
    if (width <= 0) return

    val camera = mapView.mapboxMap.cameraState
    val metersPerPixel =
        156543.03392 * cos(Math.toRadians(camera.center.latitude())) / 2.0.pow(camera.zoom)
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

private fun create3DUserIcon(context: android.content.Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (42f * density).toInt().coerceAtLeast(42)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 210, 145)
        style = Paint.Style.FILL
    }

    canvas.drawCircle(size / 2f, size / 2f, size * 0.46f, outer)
    canvas.drawCircle(size / 2f, size / 2f, size * 0.34f, inner)
    return bitmap
}

private fun create3DWaypointIcon(
    context: android.content.Context,
    type: WaypointType
): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (48f * density).toInt().coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 18, 28, 22)
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
    return bitmap
}

private const val DEFAULT_PITCH = 55.0
