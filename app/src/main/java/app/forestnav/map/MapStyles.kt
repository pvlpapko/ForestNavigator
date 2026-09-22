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
 * Exact visual stack restored from the early API-connected versions.
 *
 * MAP: OpenFreeMap Liberty
 * SATELLITE: MapTiler satellite-v2 direct 512px raster tiles
 * TERRAIN: MapTiler Outdoor v4 official vector style
 * SATELLITE_TERRAIN: satellite-v2 + terrain-rgb-v2 direct tiles
 *
 * Direct tile templates intentionally keep the old TMS addressing and 512px
 * tile size that were used after the historical "Fix tile addressing" commits.
 */
object MapStyles {
    const val OPEN_FREE_MAP = "https://tiles.openfreemap.org/styles/liberty"

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
            MapLayer.SATELLITE -> satelliteStyle(settings, safeKey)
            MapLayer.TERRAIN ->
                "https://api.maptiler.com/maps/outdoor-v4/style.json?key=$safeKey&forestnav=0916"

            MapLayer.SATELLITE_TERRAIN -> combinedStyle(settings, safeKey)
            MapLayer.CUSTOM -> null
        }
    }

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        layer == MapLayer.MAP || settings.mapTilerKey.isNotBlank()

    private fun satelliteStyle(settings: SettingsStore, safeKey: String): String {
        val json = """
            {
              "version": 8,
              "name": "ForestNavigator MapTiler Satellite Original",
              "sources": {
                "satellite": {
                  "type": "raster",
                  "tiles": [
                    "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$safeKey"
                  ],
                  "scheme": "tms",
                  "tileSize": 512,
                  "minzoom": 0,
                  "maxzoom": 22,
                  "attribution": "© MapTiler © OpenStreetMap contributors"
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
                }
              ]
            }
        """.trimIndent()

        return settings.writeGeneratedStyle(
            "maptiler_satellite_original_v2.json",
            json
        ) ?: LocalMapStyleServer.satelliteUrl()
    }

    private fun combinedStyle(settings: SettingsStore, safeKey: String): String {
        val json = """
            {
              "version": 8,
              "name": "ForestNavigator Satellite + Relief Original",
              "sources": {
                "satellite": {
                  "type": "raster",
                  "tiles": [
                    "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$safeKey"
                  ],
                  "scheme": "tms",
                  "tileSize": 512,
                  "minzoom": 0,
                  "maxzoom": 22,
                  "attribution": "© MapTiler © OpenStreetMap contributors"
                },
                "terrain": {
                  "type": "raster-dem",
                  "tiles": [
                    "https://api.maptiler.com/tiles/terrain-rgb-v2/{z}/{x}/{y}.webp?key=$safeKey"
                  ],
                  "scheme": "tms",
                  "tileSize": 512,
                  "minzoom": 0,
                  "maxzoom": 14,
                  "encoding": "mapbox",
                  "attribution": "© MapTiler © OpenStreetMap contributors"
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
                    "hillshade-exaggeration": 0.64,
                    "hillshade-shadow-color": "#261f18",
                    "hillshade-highlight-color": "#fff8e9",
                    "hillshade-accent-color": "#7a684d"
                  }
                }
              ]
            }
        """.trimIndent()

        return settings.writeGeneratedStyle(
            "maptiler_satellite_terrain_original_v2.json",
            json
        ) ?: LocalMapStyleServer.combinedUrl()
    }

    private fun encoded(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
