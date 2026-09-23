package app.forestnav.map

import app.forestnav.BuildConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal enum class MapboxSource(
    val id: String,
    val extension: String,
    val contentType: String,
    val maxZoom: Int,
    val tileSize: Int,
    val minRequestIntervalMs: Long
) {
    STREETS("streets", "png", "image/png", 22, 512, 35L),
    SATELLITE("satellite", "jpg", "image/jpeg", 22, 256, 20L),
    OUTDOORS("outdoors", "png", "image/png", 22, 512, 35L),
    SATELLITE_STREETS("satellite_streets", "jpeg", "image/jpeg", 22, 512, 35L),
    TERRAIN_RGB("terrain_rgb", "png", "image/png", 15, 256, 20L)
}

internal object MapboxProvider {
    fun token(): String = BuildConfig.MAPBOX_ACCESS_TOKEN.trim()

    fun hasToken(): Boolean = token().startsWith("pk.") && token().length > 20

    fun tileUrl(source: MapboxSource, z: Int, x: Int, y: Int): String {
        val token = encodedToken()
        return when (source) {
            MapboxSource.STREETS ->
                "https://api.mapbox.com/styles/v1/mapbox/streets-v12/tiles/512/$z/$x/$y.png?access_token=$token"
            MapboxSource.SATELLITE ->
                "https://api.mapbox.com/v4/mapbox.satellite/$z/$x/$y@2x.jpg90?access_token=$token"
            MapboxSource.OUTDOORS ->
                "https://api.mapbox.com/styles/v1/mapbox/outdoors-v12/tiles/512/$z/$x/$y.png?access_token=$token"
            MapboxSource.SATELLITE_STREETS ->
                "https://api.mapbox.com/styles/v1/mapbox/satellite-streets-v12/tiles/512/$z/$x/$y.jpeg?access_token=$token"
            MapboxSource.TERRAIN_RGB ->
                "https://api.mapbox.com/v4/mapbox.terrain-rgb/$z/$x/$y.pngraw?access_token=$token"
        }
    }

    fun tileTemplate(source: MapboxSource): String {
        val token = encodedToken()
        return when (source) {
            MapboxSource.STREETS ->
                "https://api.mapbox.com/styles/v1/mapbox/streets-v12/tiles/512/{z}/{x}/{y}.png?access_token=$token"
            MapboxSource.SATELLITE ->
                "https://api.mapbox.com/v4/mapbox.satellite/{z}/{x}/{y}@2x.jpg90?access_token=$token"
            MapboxSource.OUTDOORS ->
                "https://api.mapbox.com/styles/v1/mapbox/outdoors-v12/tiles/512/{z}/{x}/{y}.png?access_token=$token"
            MapboxSource.SATELLITE_STREETS ->
                "https://api.mapbox.com/styles/v1/mapbox/satellite-streets-v12/tiles/512/{z}/{x}/{y}.jpeg?access_token=$token"
            MapboxSource.TERRAIN_RGB ->
                "https://api.mapbox.com/v4/mapbox.terrain-rgb/{z}/{x}/{y}.pngraw?access_token=$token"
        }
    }

    fun sourceById(id: String): MapboxSource =
        MapboxSource.entries.firstOrNull { it.id == id }
            ?: error("Unknown Mapbox source: $id")

    private fun encodedToken(): String {
        val value = token()
        require(value.isNotBlank()) { "Mapbox access token is empty" }
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }
}
