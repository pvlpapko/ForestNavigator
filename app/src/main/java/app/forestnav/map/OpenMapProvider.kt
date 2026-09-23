package app.forestnav.map

internal enum class OpenMapSource(
    val id: String,
    val extension: String,
    val contentType: String,
    val maxZoom: Int,
    val tileSize: Int,
    val minRequestIntervalMs: Long,
    val downloadable: Boolean
) {
    VERSATILES_SATELLITE(
        id = "versatiles_satellite",
        extension = "webp",
        contentType = "image/webp",
        maxZoom = 12,
        tileSize = 512,
        minRequestIntervalMs = 25L,
        downloadable = true
    ),
    OAM_HIGHRES(
        id = "oam_highres",
        extension = "png",
        contentType = "image/png",
        maxZoom = 22,
        tileSize = 256,
        minRequestIntervalMs = 40L,
        downloadable = false
    ),
    MAPTERHORN_DEM(
        id = "mapterhorn_dem",
        extension = "webp",
        contentType = "image/webp",
        maxZoom = 15,
        tileSize = 512,
        minRequestIntervalMs = 20L,
        downloadable = true
    )
}

internal object OpenMapProvider {
    const val ATTRIBUTION =
        "VersaTiles sources • OpenAerialMap / Open Imagery Network • © Mapterhorn"

    fun tileUrl(source: OpenMapSource, z: Int, x: Int, y: Int): String =
        when (source) {
            OpenMapSource.VERSATILES_SATELLITE ->
                "https://tiles.versatiles.org/tiles/satellite/$z/$x/$y"

            OpenMapSource.OAM_HIGHRES ->
                "https://global.imagery.hotosm.org/$z/$x/$y.png"

            OpenMapSource.MAPTERHORN_DEM ->
                "https://tiles.mapterhorn.com/$z/$x/$y.webp"
        }

    fun sourceById(id: String): OpenMapSource =
        OpenMapSource.entries.firstOrNull { it.id == id }
            ?: error("Unknown open map source: $id")
}
