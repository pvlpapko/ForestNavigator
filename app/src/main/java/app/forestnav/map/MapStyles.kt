package app.forestnav.map

import app.forestnav.BuildConfig
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class MapLayer(val title: String) {
    MAP("Карта"),
    SATELLITE("Спутник")
}

data class MapStyleSpec(
    val key: String,
    val json: String
)

data class TileSourceSpec(
    val id: String,
    val tileSize: Int,
    val urlTemplate: String
)

object MapStyles {
    private const val STATIC_BASE =
        "https://static-map-tiles-api.arcgis.com/arcgis/rest/services/static-basemap-tiles-service/v1"

    private const val WORLD_IMAGERY =
        "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    fun description(layer: MapLayer): String = when (layer) {
        MapLayer.MAP ->
            "Open OSM Style • дороги, тропы, здания, подписи и объекты Open Basemap"
        MapLayer.SATELLITE ->
            "Open Hybrid • спутниковые снимки + дороги, границы, объекты и подписи"
    }

    fun tileSources(layer: MapLayer): List<TileSourceSpec> {
        val token = BuildConfig.ARCGIS_API_KEY.trim()
        if (token.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(
            token,
            StandardCharsets.UTF_8.name()
        )

        return when (layer) {
            MapLayer.MAP -> listOf(
                TileSourceSpec(
                    id = "osm",
                    tileSize = 512,
                    urlTemplate =
                        "$STATIC_BASE/open/osm-style/static/tile/{z}/{y}/{x}?token=$encoded"
                )
            )

            MapLayer.SATELLITE -> listOf(
                TileSourceSpec(
                    id = "imagery",
                    tileSize = 256,
                    urlTemplate = WORLD_IMAGERY
                ),
                TileSourceSpec(
                    id = "hybrid-detail",
                    tileSize = 512,
                    urlTemplate =
                        "$STATIC_BASE/open/hybrid/detail/static/tile/{z}/{y}/{x}?token=$encoded"
                )
            )
        }
    }

    fun onlineStyle(layer: MapLayer): MapStyleSpec? {
        val sources = tileSources(layer)
        if (sources.isEmpty()) return null

        return MapStyleSpec(
            key = "online-v8-${layer.name}",
            json = buildStyleJson(
                layer = layer,
                tileTemplates = sources.associate { it.id to it.urlTemplate }
            )
        )
    }

    fun offlineStyle(
        layer: MapLayer,
        regionId: Long,
        regionDirectory: File
    ): MapStyleSpec? {
        val sources = tileSources(layer)
        if (sources.isEmpty()) return null

        val templates = sources.associate { source ->
            val sourceDir = File(regionDirectory, source.id)
            val template =
                "file://${sourceDir.absolutePath}/{z}/{x}/{y}.png"
            source.id to template
        }

        return MapStyleSpec(
            key = "offline-v8-$regionId-${layer.name}",
            json = buildStyleJson(
                layer = layer,
                tileTemplates = templates
            )
        )
    }

    fun emptyStyle(layer: MapLayer): MapStyleSpec =
        MapStyleSpec(
            key = "empty-v8-${layer.name}",
            json = """
                {
                  "version": 8,
                  "name": "ForestNavigator Offline Empty",
                  "sources": {},
                  "layers": [
                    {
                      "id": "background",
                      "type": "background",
                      "paint": {
                        "background-color": "#202420"
                      }
                    }
                  ]
                }
            """.trimIndent()
        )

    fun tileUrl(
        source: TileSourceSpec,
        z: Int,
        x: Int,
        y: Int
    ): String =
        source.urlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())

    private fun buildStyleJson(
        layer: MapLayer,
        tileTemplates: Map<String, String>
    ): String {
        val background =
            if (layer == MapLayer.SATELLITE) "#0d0f0d" else "#d8ded6"

        val sourceJson = tileTemplates.entries.joinToString(",") { (id, url) ->
            val tileSize = when (id) {
                "imagery" -> 256
                else -> 512
            }
            """
            "$id": {
              "type": "raster",
              "tiles": ["$url"],
              "tileSize": $tileSize,
              "minzoom": 0,
              "maxzoom": 22
            }
            """.trimIndent()
        }

        val layerJson = when (layer) {
            MapLayer.MAP -> """
                {
                  "id": "osm",
                  "type": "raster",
                  "source": "osm",
                  "minzoom": 0,
                  "maxzoom": 23
                }
            """.trimIndent()

            MapLayer.SATELLITE -> """
                {
                  "id": "imagery",
                  "type": "raster",
                  "source": "imagery",
                  "minzoom": 0,
                  "maxzoom": 23
                },
                {
                  "id": "hybrid-detail",
                  "type": "raster",
                  "source": "hybrid-detail",
                  "minzoom": 0,
                  "maxzoom": 23
                }
            """.trimIndent()
        }

        return """
            {
              "version": 8,
              "name": "ForestNavigator ${layer.title}",
              "sources": {
                $sourceJson
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": {
                    "background-color": "$background"
                  }
                },
                $layerJson
              ]
            }
        """.trimIndent()
    }
}
