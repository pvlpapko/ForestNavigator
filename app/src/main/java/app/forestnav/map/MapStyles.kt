package app.forestnav.map

import app.forestnav.data.SettingsStore
import com.arcgismaps.mapping.BasemapStyle

enum class MapLayer(val title: String) {
    MAP("Карта"),
    SATELLITE("Спутник"),
    RELIEF("Рельеф"),
    SATELLITE_TERRAIN("Спутник+рельеф"),
    CUSTOM("Своя карта")
}

enum class OfflineLayerRole {
    BASE,
    HILLSHADE,
    REFERENCE
}

enum class OfflinePackageFormat(val extension: String) {
    RASTER("tpkx"),
    VECTOR("vtpk")
}

data class OfflineSource(
    val id: String,
    val format: OfflinePackageFormat,
    val role: OfflineLayerRole,
    val opacity: Float = 1f,
    val rasterUrl: String? = null,
    val vectorBasemapStyle: BasemapStyle? = null,
    val targetTilesPerPackage: Double = 40_000.0
)

object MapStyles {
    private const val JSON_PREFIX = "json:"

    /**
     * Built-in layers intentionally use ArcGIS basemaps end-to-end.
     * This keeps the online and downloaded map families consistent and,
     * critically, keeps the ordinary street map separate from relief.
     */
    fun basemapStyle(layer: MapLayer): BasemapStyle = when (layer) {
        MapLayer.MAP -> BasemapStyle.ArcGISStreets
        MapLayer.SATELLITE -> BasemapStyle.ArcGISImageryStandard
        MapLayer.RELIEF -> BasemapStyle.ArcGISStreetsRelief
        MapLayer.SATELLITE_TERRAIN -> BasemapStyle.ArcGISImagery
        MapLayer.CUSTOM -> BasemapStyle.ArcGISStreets
    }

    fun description(layer: MapLayer): String = when (layer) {
        MapLayer.MAP ->
            "ArcGIS Streets • обычная карта: дороги, здания, подписи и объекты без слоя рельефа"
        MapLayer.SATELLITE ->
            "ArcGIS World Imagery • HD спутниковые и аэрофотоснимки"
        MapLayer.RELIEF ->
            "ArcGIS Streets + Hillshade • обычная карта с отдельным теневым рельефом"
        MapLayer.SATELLITE_TERRAIN ->
            "ArcGIS Imagery • спутник + подписи + теневой рельеф"
        MapLayer.CUSTOM ->
            "Своя карта • пользовательский MapLibre style URL"
    }

    fun url(
        layer: MapLayer,
        settings: SettingsStore,
        online: Boolean = true
    ): String? {
        @Suppress("UNUSED_VARIABLE")
        val networkAvailable = online
        return if (layer == MapLayer.CUSTOM) {
            settings.customStyleUrl.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    fun isJsonStyle(value: String): Boolean = value.startsWith(JSON_PREFIX)

    fun jsonPayload(value: String): String = value.removePrefix(JSON_PREFIX)

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        layer != MapLayer.CUSTOM || settings.customStyleUrl.isNotBlank()

    /**
     * Offline sources are explicit. MAP is a vector streets package, so it
     * cannot accidentally inherit the shaded relief baked into the legacy
     * World_Street_Map raster export.
     */
    fun offlineSources(layer: MapLayer): List<OfflineSource> = when (layer) {
        MapLayer.MAP -> listOf(STREETS_VECTOR)

        MapLayer.SATELLITE -> listOf(
            OfflineSource(
                id = "imagery",
                format = OfflinePackageFormat.RASTER,
                role = OfflineLayerRole.BASE,
                rasterUrl = IMAGERY_EXPORT,
                targetTilesPerPackage = 40_000.0
            )
        )

        MapLayer.RELIEF -> listOf(
            STREETS_VECTOR,
            OfflineSource(
                id = "hillshade",
                format = OfflinePackageFormat.RASTER,
                role = OfflineLayerRole.HILLSHADE,
                opacity = 0.22f,
                rasterUrl = HILLSHADE_EXPORT,
                targetTilesPerPackage = 40_000.0
            )
        )

        MapLayer.SATELLITE_TERRAIN -> listOf(
            OfflineSource(
                id = "imagery",
                format = OfflinePackageFormat.RASTER,
                role = OfflineLayerRole.BASE,
                rasterUrl = IMAGERY_EXPORT,
                targetTilesPerPackage = 40_000.0
            ),
            OfflineSource(
                id = "hillshade",
                format = OfflinePackageFormat.RASTER,
                role = OfflineLayerRole.HILLSHADE,
                opacity = 0.22f,
                rasterUrl = HILLSHADE_EXPORT,
                targetTilesPerPackage = 40_000.0
            ),
            OfflineSource(
                id = "reference",
                format = OfflinePackageFormat.RASTER,
                role = OfflineLayerRole.REFERENCE,
                rasterUrl = REFERENCE_EXPORT,
                targetTilesPerPackage = 40_000.0
            )
        )

        MapLayer.CUSTOM -> emptyList()
    }

    const val HILLSHADE_ONLINE =
        "https://services.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer"

    private val STREETS_VECTOR = OfflineSource(
        id = "streets",
        format = OfflinePackageFormat.VECTOR,
        role = OfflineLayerRole.BASE,
        vectorBasemapStyle = BasemapStyle.ArcGISStreets,
        targetTilesPerPackage = 24_000.0
    )

    private const val IMAGERY_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/World_Imagery/MapServer"
    private const val HILLSHADE_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/Elevation/World_Hillshade/MapServer"
    private const val REFERENCE_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/Reference/World_Boundaries_and_Places/MapServer"
}
