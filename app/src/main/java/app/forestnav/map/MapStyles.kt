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
        MapLayer.SATELLITE -> satelliteStyle(settings)
        MapLayer.TERRAIN -> terrainStyle(settings)
        MapLayer.SATELLITE_TERRAIN -> combinedStyle(settings)
        MapLayer.CUSTOM -> settings.customStyleUrl.takeIf { it.isNotBlank() }
    }

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        settings.mapTilerKey.isNotBlank() &&
            (layer == MapLayer.SATELLITE ||
                layer == MapLayer.TERRAIN ||
                layer == MapLayer.SATELLITE_TERRAIN)

    /**
     * Important: MapTiler's current Satellite v4 MAP STYLE internally references
     * the satellite-v2 TILESET. The satellite-v4 tiles.json endpoint itself is 404.
     * Using a tiny local style with satellite-v2 avoids newer style features that
     * older/other MapLibre Native renderers can reject.
     */
    private fun satelliteStyle(settings: SettingsStore): String {
        val key = settings.mapTilerKey.takeIf { it.isNotBlank() } ?: return BUILTIN_SATELLITE
        val safeKey = encoded(key)
        val json = """
            {
              "version": 8,
              "name": "ForestNavigator Satellite HD",
              "sources": {
                "satellite": {
                  "type": "raster",
                  "url": "https://api.maptiler.com/tiles/satellite-v2/tiles.json?key=$safeKey",
                  "tileSize": 512
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
                }
              ]
            }
        """.trimIndent()
        return settings.writeGeneratedStyle("satellite_maptiler_v2.json", json)
            ?: BUILTIN_SATELLITE
    }

    /**
     * Terrain is intentionally a simple MapLibre-compatible local style:
     * OpenTopoMap gives readable paths/contours while MapTiler Terrain RGB adds
     * real DEM hillshade. This avoids loading the large Outdoor v4 style, whose
     * newest style features are not guaranteed on every MapLibre Native build.
     */
    private fun terrainStyle(settings: SettingsStore): String {
        val key = settings.mapTilerKey.takeIf { it.isNotBlank() } ?: return BUILTIN_TERRAIN
        val safeKey = encoded(key)
        val json = """
            {
              "version": 8,
              "name": "ForestNavigator Terrain HD",
              "sources": {
                "topo": {
                  "type": "raster",
                  "tiles": [
                    "https://a.tile.opentopomap.org/{z}/{x}/{y}.png"
                  ],
                  "tileSize": 256,
                  "minzoom": 0,
                  "maxzoom": 17,
                  "attribution": "© OpenStreetMap contributors, SRTM; © OpenTopoMap (CC-BY-SA)"
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
                  "id": "topo",
                  "type": "raster",
                  "source": "topo"
                },
                {
                  "id": "terrain-hillshade",
                  "type": "hillshade",
                  "source": "terrain",
                  "paint": {
                    "hillshade-exaggeration": 0.72,
                    "hillshade-shadow-color": "#30281f",
                    "hillshade-highlight-color": "#fff8e6",
                    "hillshade-accent-color": "#75634a"
                  }
                }
              ]
            }
        """.trimIndent()
        return settings.writeGeneratedStyle("terrain_maptiler_v2.json", json)
            ?: BUILTIN_TERRAIN
    }

    private fun combinedStyle(settings: SettingsStore): String {
        val key = settings.mapTilerKey.takeIf { it.isNotBlank() } ?: return BUILTIN_COMBINED
        val safeKey = encoded(key)
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
                    "hillshade-exaggeration": 0.62,
                    "hillshade-shadow-color": "#261f18",
                    "hillshade-highlight-color": "#fff8e9",
                    "hillshade-accent-color": "#7a684d"
                  }
                }
              ]
            }
        """.trimIndent()
        return settings.writeGeneratedStyle("satellite_relief_maptiler_v2.json", json)
            ?: BUILTIN_COMBINED
    }

    private fun encoded(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
