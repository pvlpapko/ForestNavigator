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
 * Built from the early MapTiler API setup that had the desired visual maps:
 * - normal map: OpenFreeMap Liberty
 * - satellite: MapTiler Satellite v4 official style
 * - relief: MapTiler Outdoor v4 official style
 * - satellite+relief: MapTiler Satellite v2 + Terrain RGB hillshade
 *
 * Online mode deliberately uses the provider's native style JSON directly.
 * Offline mode switches to LocalMapStyleServer only when Android reports that
 * validated internet is unavailable.
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
        return when (layer) {
            MapLayer.MAP -> OPEN_FREE_MAP

            MapLayer.SATELLITE -> key?.let {
                "https://api.maptiler.com/maps/satellite-v4/style.json?key=${encoded(it)}"
            } ?: LocalMapStyleServer.satelliteUrl()

            MapLayer.TERRAIN -> key?.let {
                "https://api.maptiler.com/maps/outdoor-v4/style.json?key=${encoded(it)}"
            } ?: LocalMapStyleServer.terrainUrl()

            MapLayer.SATELLITE_TERRAIN -> key?.let {
                combinedStyle(encoded(it))
            } ?: LocalMapStyleServer.combinedUrl()

            MapLayer.CUSTOM -> null
        }
    }

    fun isJsonStyle(value: String): Boolean = value.startsWith(JSON_PREFIX)

    fun jsonPayload(value: String): String = value.removePrefix(JSON_PREFIX)

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        layer == MapLayer.MAP || settings.mapTilerKey.isNotBlank()

    private fun combinedStyle(safeKey: String): String {
        val json = """
            {
              "version": 8,
              "name": "ForestNavigator Satellite + Relief HD",
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
                    "hillshade-exaggeration": 0.64,
                    "hillshade-shadow-color": "#261f18",
                    "hillshade-highlight-color": "#fff8e9",
                    "hillshade-accent-color": "#7a684d"
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
