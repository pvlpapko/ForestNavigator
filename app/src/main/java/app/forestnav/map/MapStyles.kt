package app.forestnav.map

import android.content.Context
import app.forestnav.BuildConfig
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class MapLayer(val title: String) {
    MAP("Карта"),
    SATELLITE("Спутник")
}

object MapStyles {
    private const val STATIC_BASE =
        "https://static-map-tiles-api.arcgis.com/arcgis/rest/services/static-basemap-tiles-service/v1"

    fun description(layer: MapLayer): String = when (layer) {
        MapLayer.MAP ->
            "Open OSM Style • дороги, тропы, здания, подписи и объекты Open Basemap"
        MapLayer.SATELLITE ->
            "Open Hybrid • спутниковые снимки + дороги, границы, объекты и подписи"
    }

    fun styleKey(layer: MapLayer): String =
        "static-v1-${layer.name}"

    fun styleJson(layer: MapLayer): String? {
        val tileUrl = tileUrl(layer) ?: return null
        val background = when (layer) {
            MapLayer.MAP -> "#d8ded6"
            MapLayer.SATELLITE -> "#1b1b1b"
        }
        val name = when (layer) {
            MapLayer.MAP -> "Open OSM Style"
            MapLayer.SATELLITE -> "Open Hybrid"
        }

        return """
            {
              "version": 8,
              "name": "$name",
              "sources": {
                "forestnav-basemap": {
                  "type": "raster",
                  "tiles": ["$tileUrl"],
                  "tileSize": 512,
                  "minzoom": 0,
                  "maxzoom": 22,
                  "attribution": "Esri, OpenStreetMap contributors, Microsoft, Esri Community Maps contributors"
                }
              },
              "layers": [
                {
                  "id": "forestnav-background",
                  "type": "background",
                  "paint": {
                    "background-color": "$background"
                  }
                },
                {
                  "id": "forestnav-basemap",
                  "type": "raster",
                  "source": "forestnav-basemap",
                  "minzoom": 0,
                  "maxzoom": 23
                }
              ]
            }
        """.trimIndent()
    }

    fun offlineStyleUri(context: Context, layer: MapLayer): String? {
        val json = styleJson(layer) ?: return null
        val dir = File(context.filesDir, "map_styles_v7").apply { mkdirs() }
        val file = File(dir, "${layer.name.lowercase()}.json")
        val temp = File(dir, file.name + ".tmp")

        if (!file.isFile || file.readText() != json) {
            temp.writeText(json)
            if (file.exists()) file.delete()
            check(temp.renameTo(file)) {
                "Не удалось сохранить локальный стиль карты"
            }
        }

        return "file://${file.absolutePath}"
    }

    private fun tileUrl(layer: MapLayer): String? {
        val token = BuildConfig.ARCGIS_API_KEY.trim()
        if (token.isBlank()) return null

        val stylePath = when (layer) {
            MapLayer.MAP -> "open/osm-style"
            MapLayer.SATELLITE -> "open/hybrid/detail"
        }

        val encoded = URLEncoder.encode(
            token,
            StandardCharsets.UTF_8.name()
        )

        return "$STATIC_BASE/$stylePath/static/tile/{z}/{y}/{x}?token=$encoded"
    }
}
