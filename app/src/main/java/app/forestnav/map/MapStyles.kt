package app.forestnav.map

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
    private const val BUILTIN_SATELLITE = "asset://satellite_fallback.json"
    private const val BUILTIN_TERRAIN = "asset://terrain_fallback.json"
    private const val BUILTIN_COMBINED = "asset://satellite_terrain_fallback.json"

    fun url(layer: MapLayer, settings: SettingsStore): String? = when (layer) {
        MapLayer.MAP -> OPEN_FREE_MAP
        MapLayer.SATELLITE -> settings.mapTilerKey.takeIf { it.isNotBlank() }
            ?.let { "https://api.maptiler.com/maps/satellite-v4/style.json?key=${encoded(it)}" }
            ?: BUILTIN_SATELLITE
        MapLayer.TERRAIN -> settings.mapTilerKey.takeIf { it.isNotBlank() }
            ?.let { "https://api.maptiler.com/maps/outdoor-v4/style.json?key=${encoded(it)}" }
            ?: BUILTIN_TERRAIN
        MapLayer.SATELLITE_TERRAIN -> combinedStyle(settings)
        MapLayer.CUSTOM -> settings.customStyleUrl.takeIf { it.isNotBlank() }
    }

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        settings.mapTilerKey.isNotBlank() &&
            (layer == MapLayer.SATELLITE ||
                layer == MapLayer.TERRAIN ||
                layer == MapLayer.SATELLITE_TERRAIN)

    private fun combinedStyle(settings: SettingsStore): String {
        val key = settings.mapTilerKey.takeIf { it.isNotBlank() } ?: return BUILTIN_COMBINED
        val safeKey = encoded(key)
        val json = """
            {
              "version": 8,
              "name": "ForestNavigator Satellite + Relief",
              "sources": {
                "satellite": {
                  "type": "raster",
                  "url": "https://api.maptiler.com/tiles/satellite-v4/tiles.json?key=$safeKey",
                  "tileSize": 512
                },
                "terrain": {
                  "type": "raster-dem",
                  "url": "https://api.maptiler.com/tiles/terrain-rgb-v2/tiles.json?key=$safeKey",
                  "tileSize": 512,
                  "maxzoom": 14,
                  "encoding": "mapbox"
                }
              },
              "layers": [
                {
                  "id": "satellite",
                  "type": "raster",
                  "source": "satellite"
                },
                {
                  "id": "terrain-hillshade",
                  "type": "hillshade",
                  "source": "terrain",
                  "paint": {
                    "hillshade-exaggeration": 0.62,
                    "hillshade-shadow-color": "#261f18",
                    "hillshade-highlight-color": "#fff8e9",
                    "hillshade-accent-color": "#7a684d"
                  }
                }
              ]
            }
        """.trimIndent()
        return settings.writeGeneratedStyle("satellite_relief_maptiler.json", json)
            ?: BUILTIN_COMBINED
    }

    private fun encoded(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
