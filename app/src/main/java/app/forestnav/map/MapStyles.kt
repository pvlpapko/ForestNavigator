package app.forestnav.map

import app.forestnav.BuildConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class MapLayer(val title: String) {
    MAP("Карта"),
    SATELLITE("Спутник")
}

object MapStyles {
    private const val BASE =
        "https://basemapstyles-api.arcgis.com/arcgis/rest/services/styles/v2/styles/open"

    fun styleUrl(layer: MapLayer): String? {
        val token = BuildConfig.ARCGIS_API_KEY.trim()
        if (token.isBlank()) return null

        val style = when (layer) {
            MapLayer.MAP -> "osm-style"
            MapLayer.SATELLITE -> "hybrid"
        }

        val encoded = URLEncoder.encode(
            token,
            StandardCharsets.UTF_8.name()
        )

        return "$BASE/$style?language=ru&token=$encoded"
    }

    fun description(layer: MapLayer): String = when (layer) {
        MapLayer.MAP ->
            "Open OSM Style • дороги, тропы, здания, подписи и объекты Open Basemap"
        MapLayer.SATELLITE ->
            "Open Hybrid • спутниковые снимки + дороги, границы, объекты и подписи"
    }
}
