package app.forestnav.map

import app.forestnav.data.SettingsStore

enum class MapLayer(val title: String) {
    MAP("Карта"),
    SATELLITE("Спутник"),
    TERRAIN("Рельеф"),
    SATELLITE_TERRAIN("Спутник+рельеф"),
    CUSTOM("Своя карта")
}

/**
 * Clean ArcGIS/Esri map stack.
 *
 * No MapTiler URLs, generated files, TileJSON indirection or provider-specific
 * API keys are used by the built-in modes. Online styles use ArcGIS cached
 * MapServer tiles directly. Offline styles are served by LocalMapStyleServer
 * from the exact same source definitions.
 */
object MapStyles {
    private const val JSON_PREFIX = "json:"

    private const val STREET =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}"
    private const val IMAGERY =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    private const val TOPO =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}"
    private const val HILLSHADE =
        "https://server.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer/tile/{z}/{y}/{x}"
    private const val BOUNDARIES =
        "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"
    private const val TRANSPORT =
        "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"

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

        val json = when (layer) {
            MapLayer.MAP -> singleRasterStyle(
                name = "Esri World Street Map",
                sourceId = "street",
                tiles = STREET,
                maxZoom = 23,
                attribution = "Sources: Esri, HERE, Garmin, USGS, OpenStreetMap contributors"
            )

            MapLayer.SATELLITE -> singleRasterStyle(
                name = "Esri World Imagery",
                sourceId = "imagery",
                tiles = IMAGERY,
                maxZoom = 23,
                attribution = "Source: Esri, Vantor, Earthstar Geographics, GIS User Community"
            )

            MapLayer.TERRAIN -> singleRasterStyle(
                name = "Esri World Topographic Map",
                sourceId = "topo",
                tiles = TOPO,
                maxZoom = 23,
                attribution = "Sources: Esri, HERE, Garmin, USGS, OpenStreetMap contributors"
            )

            MapLayer.SATELLITE_TERRAIN -> satelliteTerrainStyle()
            MapLayer.CUSTOM -> return null
        }

        return JSON_PREFIX + json
    }

    fun isJsonStyle(value: String): Boolean = value.startsWith(JSON_PREFIX)

    fun jsonPayload(value: String): String = value.removePrefix(JSON_PREFIX)

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        layer != MapLayer.CUSTOM || settings.customStyleUrl.isNotBlank()

    private fun singleRasterStyle(
        name: String,
        sourceId: String,
        tiles: String,
        maxZoom: Int,
        attribution: String
    ): String = """
        {
          "version": 8,
          "name": "$name",
          "sources": {
            "$sourceId": {
              "type": "raster",
              "tiles": ["$tiles"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": $maxZoom,
              "attribution": "$attribution"
            }
          },
          "layers": [
            {
              "id": "$sourceId",
              "type": "raster",
              "source": "$sourceId",
              "paint": {
                "raster-opacity": 1.0,
                "raster-resampling": "linear"
              }
            }
          ]
        }
    """.trimIndent()

    private fun satelliteTerrainStyle(): String = """
        {
          "version": 8,
          "name": "Esri Imagery + Relief + Reference",
          "sources": {
            "imagery": {
              "type": "raster",
              "tiles": ["$IMAGERY"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 23
            },
            "hillshade": {
              "type": "raster",
              "tiles": ["$HILLSHADE"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 23
            },
            "transport": {
              "type": "raster",
              "tiles": ["$TRANSPORT"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 23
            },
            "boundaries": {
              "type": "raster",
              "tiles": ["$BOUNDARIES"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 23
            }
          },
          "layers": [
            {
              "id": "imagery",
              "type": "raster",
              "source": "imagery"
            },
            {
              "id": "hillshade",
              "type": "raster",
              "source": "hillshade",
              "paint": {
                "raster-opacity": 0.30,
                "raster-contrast": 0.16,
                "raster-saturation": -0.18
              }
            },
            {
              "id": "transport",
              "type": "raster",
              "source": "transport",
              "paint": {
                "raster-opacity": 0.86
              }
            },
            {
              "id": "boundaries",
              "type": "raster",
              "source": "boundaries",
              "paint": {
                "raster-opacity": 0.94
              }
            }
          ]
        }
    """.trimIndent()
}
