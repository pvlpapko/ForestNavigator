package app.forestnav.map

import app.forestnav.BuildConfig
import app.forestnav.data.SettingsStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class MapLayer(val title: String) {
    MAP("Карта"),
    SATELLITE("Спутник"),
    TERRAIN("Рельеф"),
    SATELLITE_TERRAIN("Спутник+рельеф"),
    CUSTOM("Своя карта")
}

object MapStyles {
    const val OPEN_FREE_MAP = "https://tiles.openfreemap.org/styles/liberty"
    private const val JSON_PREFIX = "json:"

    fun url(layer: MapLayer, settings: SettingsStore): String? = when (layer) {
        MapLayer.MAP -> OPEN_FREE_MAP
        MapLayer.SATELLITE -> JSON_PREFIX + satelliteJson()
        MapLayer.TERRAIN -> "asset://terrain_fallback.json"
        MapLayer.SATELLITE_TERRAIN -> JSON_PREFIX + combinedJson()
        MapLayer.CUSTOM -> settings.customStyleUrl.takeIf { it.isNotBlank() }
    }

    fun isJsonStyle(value: String): Boolean = value.startsWith(JSON_PREFIX)

    fun jsonPayload(value: String): String = value.removePrefix(JSON_PREFIX)

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        settings.mapTilerKey.isNotBlank() &&
            (layer == MapLayer.SATELLITE ||
                layer == MapLayer.TERRAIN ||
                layer == MapLayer.SATELLITE_TERRAIN)

    private fun key(): String =
        URLEncoder.encode(BuildConfig.MAPTILER_KEY, StandardCharsets.UTF_8.toString())

    private fun satelliteJson(): String {
        val k = key()
        return """
            {
              "version": 8,
              "name": "Forest Navigator Satellite",
              "sources": {
                "satellite": {
                  "type": "raster",
                  "tiles": [
                    "https://api.maptiler.com/maps/satellite-v4/256/{z}/{x}/{y}.jpg?key=$k"
                  ],
                  "scheme": "xyz",
                  "tileSize": 256,
                  "minzoom": 0,
                  "maxzoom": 20
                }
              },
              "layers": [
                {
                  "id": "satellite",
                  "type": "raster",
                  "source": "satellite"
                }
              ]
            }
        """.trimIndent()
    }

    private fun combinedJson(): String {
        val k = key()
        return """
            {
              "version": 8,
              "name": "Forest Navigator Satellite + Relief",
              "sources": {
                "satellite": {
                  "type": "raster",
                  "tiles": [
                    "https://api.maptiler.com/maps/satellite-v4/256/{z}/{x}/{y}.jpg?key=$k"
                  ],
                  "scheme": "xyz",
                  "tileSize": 256,
                  "minzoom": 0,
                  "maxzoom": 20
                },
                "relief": {
                  "type": "raster",
                  "tiles": [
                    "https://a.tile.opentopomap.org/{z}/{x}/{y}.png"
                  ],
                  "scheme": "xyz",
                  "tileSize": 256,
                  "minzoom": 0,
                  "maxzoom": 17
                }
              },
              "layers": [
                {
                  "id": "satellite",
                  "type": "raster",
                  "source": "satellite"
                },
                {
                  "id": "relief",
                  "type": "raster",
                  "source": "relief",
                  "paint": {
                    "raster-opacity": 0.30,
                    "raster-contrast": 0.10
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
