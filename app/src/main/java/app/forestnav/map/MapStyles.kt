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

/**
 * Online built-in maps use MapTiler's native styles directly.
 *
 * The user's device has shown unstable raster rendering with the Vulkan backend,
 * so the app uses MapLibre OpenGL-only. Satellite and Outdoor are never wrapped
 * into a local file style while online. The combined mode is an inline style
 * using MapTiler's documented Satellite v2 + Terrain RGB v2 sources.
 */
object MapStyles {
    const val OPEN_FREE_MAP = "https://tiles.openfreemap.org/styles/liberty"
    private const val JSON_PREFIX = "json:"

    fun url(
        layer: MapLayer,
        settings: SettingsStore,
        online: Boolean = true
    ): String? {
        if (layer == MapLayer.CUSTOM) {
            return settings.customStyleUrl.takeIf { it.isNotBlank() }
        }

        if (!online) {
            return when (layer) {
                MapLayer.MAP -> LocalMapStyleServer.mapUrl()
                MapLayer.SATELLITE -> LocalMapStyleServer.satelliteUrl()
                MapLayer.TERRAIN -> LocalMapStyleServer.terrainUrl()
                MapLayer.SATELLITE_TERRAIN -> LocalMapStyleServer.combinedUrl()
                MapLayer.CUSTOM -> null
            }
        }

        val key = settings.mapTilerKey.takeIf { it.isNotBlank() }
        if (key == null) {
            return when (layer) {
                MapLayer.MAP -> OPEN_FREE_MAP
                MapLayer.SATELLITE -> LocalMapStyleServer.satelliteUrl()
                MapLayer.TERRAIN -> LocalMapStyleServer.terrainUrl()
                MapLayer.SATELLITE_TERRAIN -> LocalMapStyleServer.combinedUrl()
                MapLayer.CUSTOM -> null
            }
        }

        val safeKey = encoded(key)
        return when (layer) {
            MapLayer.MAP -> OPEN_FREE_MAP

            MapLayer.SATELLITE ->
                "https://api.maptiler.com/maps/satellite-v4/style.json?key=$safeKey&forestnav=0917"

            MapLayer.TERRAIN ->
                "https://api.maptiler.com/maps/outdoor-v4/style.json?key=$safeKey&forestnav=0917"

            MapLayer.SATELLITE_TERRAIN ->
                combinedInlineStyle(safeKey)

            MapLayer.CUSTOM -> null
        }
    }

    fun isJsonStyle(value: String): Boolean = value.startsWith(JSON_PREFIX)

    fun jsonPayload(value: String): String = value.removePrefix(JSON_PREFIX)

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        layer == MapLayer.MAP || settings.mapTilerKey.isNotBlank()

    private fun combinedInlineStyle(safeKey: String): String {
        val json = """
            {
              "version": 8,
              "name": "ForestNavigator Satellite + Terrain RGB",
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
                  "maxzoom": 14
                }
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": {
                    "background-color": "#263238"
                  }
                },
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
                    "hillshade-exaggeration": 0.62,
                    "hillshade-shadow-color": "#2c251e",
                    "hillshade-highlight-color": "#fff8e9",
                    "hillshade-accent-color": "#7b694e"
                  }
                }
              ]
            }
        """.trimIndent()

        return JSON_PREFIX + json
    }

    private fun encoded(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
