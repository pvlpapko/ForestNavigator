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
    private const val JSON_PREFIX = "json:"

    fun url(layer: MapLayer, settings: SettingsStore): String? {
        if (layer == MapLayer.CUSTOM) {
            return settings.customStyleUrl.takeIf { it.isNotBlank() }
        }

        val key = settings.mapTilerKey.takeIf { it.isNotBlank() }
        val online = key != null && settings.hasValidatedInternet()

        if (!online) {
            return when (layer) {
                MapLayer.MAP -> LocalMapStyleServer.mapUrl()
                MapLayer.SATELLITE -> LocalMapStyleServer.satelliteUrl()
                MapLayer.TERRAIN -> LocalMapStyleServer.terrainUrl()
                MapLayer.SATELLITE_TERRAIN -> LocalMapStyleServer.combinedUrl()
                MapLayer.CUSTOM -> null
            }
        }

        val safeKey = encoded(key!!)
        return when (layer) {
            MapLayer.MAP ->
                "https://api.maptiler.com/maps/streets-v4/style.json?key=$safeKey"

            MapLayer.SATELLITE ->
                "https://api.maptiler.com/maps/satellite-v4/style.json?key=$safeKey"

            MapLayer.TERRAIN ->
                "https://api.maptiler.com/maps/outdoor-v4/style.json?key=$safeKey"

            MapLayer.SATELLITE_TERRAIN ->
                combinedStyle(settings, safeKey)

            MapLayer.CUSTOM -> null
        }
    }

    fun isJsonStyle(value: String): Boolean = value.startsWith(JSON_PREFIX)

    fun jsonPayload(value: String): String = value.removePrefix(JSON_PREFIX)

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        settings.mapTilerKey.isNotBlank() && layer != MapLayer.CUSTOM

    private fun combinedStyle(settings: SettingsStore, safeKey: String): String {
        val json = """
            {
              "version": 8,
              "name": "ForestNavigator Satellite + Relief",
              "sources": {
                "satellite": {
                  "type": "raster",
                  "url": "https://api.maptiler.com/tiles/satellite-v2/tiles.json?key=$safeKey",
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
                  "source": "satellite",
                  "paint": {
                    "raster-opacity": 1.0,
                    "raster-resampling": "linear"
                  }
                },
                {
                  "id": "terrain-hillshade",
                  "type": "hillshade",
                  "source": "terrain",
                  "paint": {
                    "hillshade-exaggeration": 0.58,
                    "hillshade-shadow-color": "#261f18",
                    "hillshade-highlight-color": "#fff8e9",
                    "hillshade-accent-color": "#7a684d"
                  }
                }
              ]
            }
        """.trimIndent()

        return settings.writeGeneratedStyle("maptiler_satellite_relief_v3.json", json)
            ?: "https://api.maptiler.com/maps/hybrid-v4/style.json?key=$safeKey"
    }

    private fun encoded(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
