package app.forestnav.map

import app.forestnav.data.SettingsStore

enum class MapLayer(val title: String) {
    MAP("Карта"),
    SATELLITE("Спутник"),
    TERRAIN("Топографическая"),
    SATELLITE_TERRAIN("Спутник+рельеф"),
    THREE_D("3D спутник"),
    THREE_D_TERRAIN("3D+рельеф"),
    CUSTOM("Своя карта")
}

/**
 * Clean Mapbox raster stack for MapLibre.
 *
 * We deliberately use documented HTTPS raster endpoints rather than mapbox://
 * URLs so MapLibre does not depend on Mapbox SDK URL rewriting.
 */
object MapStyles {
    private const val JSON_PREFIX = "json:"
    private const val ATTRIBUTION =
        "© Mapbox © OpenStreetMap contributors"

    fun url(
        layer: MapLayer,
        settings: SettingsStore,
        online: Boolean = true
    ): String? {
        @Suppress("UNUSED_VARIABLE")
        val networkAvailable = online

        if (layer == MapLayer.CUSTOM) {
            return settings.customStyleUrl.takeIf { it.isNotBlank() }
        }

        if (!MapboxProvider.hasToken()) return null

        // All normal 2D rendering goes through the local proxy even while online.
        // The proxy always prefers downloaded/partial/cache tiles first and only
        // requests Mapbox for tiles that are still missing. This makes partially
        // downloaded regions immediately usable and prevents a weak network from
        // bypassing already-downloaded data.
        return when (layer) {
            MapLayer.MAP -> LocalMapStyleServer.mapUrl()
            MapLayer.SATELLITE -> LocalMapStyleServer.satelliteUrl()
            MapLayer.TERRAIN -> LocalMapStyleServer.terrainUrl()
            MapLayer.SATELLITE_TERRAIN -> LocalMapStyleServer.combinedUrl()
            MapLayer.THREE_D -> null
            MapLayer.THREE_D_TERRAIN -> null
            MapLayer.CUSTOM -> null
        }
    }

    fun isJsonStyle(value: String): Boolean = value.startsWith(JSON_PREFIX)

    fun jsonPayload(value: String): String = value.removePrefix(JSON_PREFIX)

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        layer != MapLayer.CUSTOM || settings.customStyleUrl.isNotBlank()

    private fun singleRasterStyle(
        name: String,
        source: MapboxSource
    ): String = """
        {
          "version": 8,
          "name": "$name",
          "sources": {
            "${source.id}": {
              "type": "raster",
              "tiles": ["${MapboxProvider.tileTemplate(source)}"],
              "scheme": "xyz",
              "tileSize": ${source.tileSize},
              "minzoom": 0,
              "maxzoom": ${source.maxZoom},
              "attribution": "$ATTRIBUTION"
            }
          },
          "layers": [
            {
              "id": "${source.id}",
              "type": "raster",
              "source": "${source.id}",
              "paint": {
                "raster-opacity": 1.0,
                "raster-resampling": "linear"
              }
            }
          ]
        }
    """.trimIndent()

    private fun terrainStyle(): String =
        singleRasterStyle(
            name = "Mapbox Outdoors Topographic",
            source = MapboxSource.OUTDOORS
        )

    private fun satelliteTerrainStyle(): String {
        val base = MapboxSource.SATELLITE_STREETS
        val dem = MapboxSource.TERRAIN_RGB

        return """
            {
              "version": 8,
              "name": "Mapbox Satellite Streets + Relief",
              "sources": {
                "${base.id}": {
                  "type": "raster",
                  "tiles": ["${MapboxProvider.tileTemplate(base)}"],
                  "scheme": "xyz",
                  "tileSize": ${base.tileSize},
                  "minzoom": 0,
                  "maxzoom": ${base.maxZoom},
                  "attribution": "$ATTRIBUTION"
                },
                "${dem.id}": {
                  "type": "raster-dem",
                  "tiles": ["${MapboxProvider.tileTemplate(dem)}"],
                  "scheme": "xyz",
                  "tileSize": ${dem.tileSize},
                  "minzoom": 0,
                  "maxzoom": ${dem.maxZoom},
                  "encoding": "mapbox",
                  "attribution": "$ATTRIBUTION"
                }
              },
              "layers": [
                {
                  "id": "satellite-streets",
                  "type": "raster",
                  "source": "${base.id}",
                  "paint": {
                    "raster-opacity": 1.0,
                    "raster-resampling": "linear"
                  }
                },
                {
                  "id": "terrain-hillshade",
                  "type": "hillshade",
                  "source": "${dem.id}",
                  "paint": {
                    "hillshade-exaggeration": 0.44,
                    "hillshade-shadow-color": "#261e18",
                    "hillshade-highlight-color": "#fff7e5",
                    "hillshade-accent-color": "#725d45"
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
