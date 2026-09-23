package app.forestnav.ui

import android.annotation.SuppressLint
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.forestnav.data.Waypoint
import app.forestnav.map.LocalMapStyleServer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private class Web3DMapState {
    var pageLoaded = false
    var mapReady = false
    var initialized = false
    var lastRecenterToken = -1
    var lastWaypointFingerprint = 0
    var lastPlacementEnabled: Boolean? = null
    var lastLat = Double.NaN
    var lastLon = Double.NaN
}

private class ForestMapJsBridge(
    private val onReady: () -> Unit,
    private val onClick: (Double, Double) -> Unit,
    private val onLongPress: (Double, Double) -> Unit,
    private val onWaypoint: (Long) -> Unit,
    private val onScale: (Double) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMapReady() {
        main.post { onReady() }
    }

    @JavascriptInterface
    fun onMapClick(latitude: Double, longitude: Double) {
        main.post { onClick(latitude, longitude) }
    }

    @JavascriptInterface
    fun onMapLongPress(latitude: Double, longitude: Double) {
        main.post { onLongPress(latitude, longitude) }
    }

    @JavascriptInterface
    fun onWaypointClick(id: String) {
        val value = id.toLongOrNull() ?: return
        main.post { onWaypoint(value) }
    }

    @JavascriptInterface
    fun onScaleChanged(meters: Double) {
        if (!meters.isFinite() || meters <= 0.0) return
        main.post { onScale(meters) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Forest3DMapView(
    modifier: Modifier,
    location: Location,
    heading: Float?,
    waypoints: List<Waypoint>,
    reliefOverlay: Boolean = true,
    recenterToken: Int = 0,
    pointPlacementEnabled: Boolean = false,
    onMapClick: (Double, Double) -> Unit = { _, _ -> },
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> },
    onWaypointClick: (Waypoint) -> Unit = {},
    onMapScaleChanged: (Double) -> Unit = {}
) {
    @Suppress("UNUSED_VARIABLE")
    val alwaysRelief = reliefOverlay

    val context = LocalContext.current
    val state = remember { Web3DMapState() }

    val currentWaypoints = rememberUpdatedState(waypoints)
    val currentMapClick = rememberUpdatedState(onMapClick)
    val currentLongPress = rememberUpdatedState(onMapLongPress)
    val currentWaypointClick = rememberUpdatedState(onWaypointClick)
    val currentScale = rememberUpdatedState(onMapScaleChanged)

    val webView = remember {
        WebView(context).apply {
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            webChromeClient = WebChromeClient()
        }
    }

    val bridge = remember {
        ForestMapJsBridge(
            onReady = {
                state.mapReady = true
                webView.post {
                    pushWaypoints(webView, currentWaypoints.value)
                    webView.evaluateJavascript(
                        "window.setPlacementEnabled($pointPlacementEnabled);",
                        null
                    )
                }
            },
            onClick = { lat, lon -> currentMapClick.value(lat, lon) },
            onLongPress = { lat, lon -> currentLongPress.value(lat, lon) },
            onWaypoint = { id ->
                currentWaypoints.value
                    .firstOrNull { it.id == id }
                    ?.let(currentWaypointClick.value)
            },
            onScale = { currentScale.value(it) }
        )
    }

    DisposableEffect(webView) {
        onDispose {
            runCatching { webView.removeJavascriptInterface("AndroidBridge") }
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
            runCatching { webView.clearHistory() }
            runCatching { webView.destroy() }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                webView.apply {
                    addJavascriptInterface(bridge, "AndroidBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            state.pageLoaded = true
                            state.initialized = true
                            state.lastRecenterToken = recenterToken
                            view.evaluateJavascript(
                                "window.initMap(" +
                                    "${location.latitude}," +
                                    "${location.longitude}," +
                                    "${heading?.toDouble() ?: 0.0}" +
                                    ");",
                                null
                            )
                        }
                    }
                    loadUrl(LocalMapStyleServer.webMapUrl())
                }
            },
            update = { view ->
                if (!state.pageLoaded || !state.initialized) return@AndroidView

                val moved =
                    !state.lastLat.isFinite() ||
                        abs(state.lastLat - location.latitude) > 0.0000005 ||
                        abs(state.lastLon - location.longitude) > 0.0000005

                if (moved) {
                    view.evaluateJavascript(
                        "window.updateLocation(" +
                            "${location.latitude}," +
                            "${location.longitude}" +
                            ");",
                        null
                    )
                    state.lastLat = location.latitude
                    state.lastLon = location.longitude
                }

                if (state.mapReady && state.lastRecenterToken != recenterToken) {
                    view.evaluateJavascript(
                        "window.recenter(" +
                            "${location.latitude}," +
                            "${location.longitude}," +
                            "${heading?.toDouble() ?: 0.0}" +
                            ");",
                        null
                    )
                    state.lastRecenterToken = recenterToken
                }

                if (state.mapReady) {
                    val fingerprint = waypointFingerprint(waypoints)
                    if (fingerprint != state.lastWaypointFingerprint) {
                        pushWaypoints(view, waypoints)
                        state.lastWaypointFingerprint = fingerprint
                    }

                    if (state.lastPlacementEnabled != pointPlacementEnabled) {
                        view.evaluateJavascript(
                            "window.setPlacementEnabled($pointPlacementEnabled);",
                            null
                        )
                        state.lastPlacementEnabled = pointPlacementEnabled
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp),
            horizontalAlignment = Alignment.End
        ) {
            CameraButton("Сверху") {
                setPitch(webView, TOP_PITCH)
            }
            CameraButton("Походный") {
                setPitch(webView, WALKING_PITCH)
            }
            CameraButton("Горизонт") {
                setPitch(webView, HORIZON_PITCH)
            }
        }
    }
}

@Composable
private fun CameraButton(
    label: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .padding(top = 6.dp)
            .widthIn(min = 92.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun setPitch(webView: WebView, pitch: Double) {
    webView.evaluateJavascript("window.setPitch($pitch);", null)
}

private fun pushWaypoints(webView: WebView, waypoints: List<Waypoint>) {
    val json = JSONArray()
    waypoints.forEach { waypoint ->
        json.put(
            JSONObject()
                .put("id", waypoint.id)
                .put("type", waypoint.type.name)
                .put("name", waypoint.name)
                .put("lat", waypoint.latitude)
                .put("lon", waypoint.longitude)
        )
    }
    webView.evaluateJavascript(
        "window.updateWaypoints($json);",
        null
    )
}

private fun waypointFingerprint(waypoints: List<Waypoint>): Int =
    waypoints.fold(1) { acc, waypoint ->
        var value = 31 * acc + waypoint.id.hashCode()
        value = 31 * value + waypoint.latitude.hashCode()
        value = 31 * value + waypoint.longitude.hashCode()
        value = 31 * value + waypoint.type.hashCode()
        value
    }

private const val TOP_PITCH = 0.0
private const val WALKING_PITCH = 72.0
private const val HORIZON_PITCH = 85.0
