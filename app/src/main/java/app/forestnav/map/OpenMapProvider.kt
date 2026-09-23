package app.forestnav.map

internal enum class OpenMapSource(
    val id: String,
    val extension: String,
    val contentType: String,
    val maxZoom: Int,
    val tileSize: Int,
    val minRequestIntervalMs: Long
) {
    EOX_OSM(
        id = "eox_osm",
        extension = "jpg",
        contentType = "image/jpeg",
        maxZoom = 18,
        tileSize = 256,
        minRequestIntervalMs = 45L
    ),
    EOX_SATELLITE_2025(
        id = "eox_satellite_2025",
        extension = "jpg",
        contentType = "image/jpeg",
        maxZoom = 14,
        tileSize = 256,
        minRequestIntervalMs = 55L
    ),
    EOX_TERRAIN(
        id = "eox_terrain",
        extension = "jpg",
        contentType = "image/jpeg",
        maxZoom = 18,
        tileSize = 256,
        minRequestIntervalMs = 45L
    ),
    AWS_TERRARIUM(
        id = "aws_terrarium",
        extension = "png",
        contentType = "image/png",
        maxZoom = 15,
        tileSize = 256,
        minRequestIntervalMs = 35L
    )
}

internal object OpenMapProvider {
    const val ATTRIBUTION =
        "EOX / EOxCloudless 2025 • Copernicus Sentinel data • OpenStreetMap contributors • AWS/Mapzen Terrain"

    fun tileUrl(source: OpenMapSource, z: Int, x: Int, y: Int): String =
        when (source) {
            OpenMapSource.EOX_OSM ->
                "https://tiles.maps.eox.at/wmts/1.0.0/osm_3857/default/g/$z/$y/$x.jpg"

            OpenMapSource.EOX_SATELLITE_2025 ->
                "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/g/$z/$y/$x.jpg"

            OpenMapSource.EOX_TERRAIN ->
                "https://tiles.maps.eox.at/wmts/1.0.0/terrain_3857/default/g/$z/$y/$x.jpg"

            OpenMapSource.AWS_TERRARIUM ->
                "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png"
        }

    fun tileTemplate(source: OpenMapSource): String =
        when (source) {
            OpenMapSource.EOX_OSM ->
                "https://tiles.maps.eox.at/wmts/1.0.0/osm_3857/default/g/{z}/{y}/{x}.jpg"

            OpenMapSource.EOX_SATELLITE_2025 ->
                "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/g/{z}/{y}/{x}.jpg"

            OpenMapSource.EOX_TERRAIN ->
                "https://tiles.maps.eox.at/wmts/1.0.0/terrain_3857/default/g/{z}/{y}/{x}.jpg"

            OpenMapSource.AWS_TERRARIUM ->
                "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
        }

    fun sourceById(id: String): OpenMapSource =
        OpenMapSource.entries.firstOrNull { it.id == id }
            ?: error("Unknown open map source: $id")
}
