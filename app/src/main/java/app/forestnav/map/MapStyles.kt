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
        MapLayer.MAP -> BasemapStyle.OpenStreets
        MapLayer.SATELLITE -> BasemapStyle.ArcGISImageryStandard
        MapLayer.RELIEF -> BasemapStyle.OpenStreetsRelief
        MapLayer.SATELLITE_TERRAIN -> BasemapStyle.ArcGISImagery
        MapLayer.CUSTOM -> BasemapStyle.ArcGISStreets
    }

    fun description(layer: MapLayer): String = when (layer) {
        MapLayer.MAP ->
            "Open Streets • обычная карта на Open Basemap: дороги, здания, подписи и объекты без рельефа"
        MapLayer.SATELLITE ->
            "ArcGIS World Imagery • HD спутниковые и аэрофотоснимки"
        MapLayer.RELIEF ->
            "Open Streets Relief • Open Basemap с отдельным теневым рельефом"
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
     * Offline sources are explicit. MAP uses Open Streets vector tiles;
     * RELIEF uses the dedicated Open Streets Relief Base vector style plus
     * hillshade. The two modes never reuse the same vector package.
     */
    fun offlineSources(layer: MapLayer): List<OfflineSource> = when (layer) {
        MapLayer.MAP -> listOf(OPEN_STREETS_VECTOR)

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
            OPEN_STREETS_RELIEF_VECTOR,
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

    private val OPEN_STREETS_VECTOR = OfflineSource(
        id = "open-streets",
        format = OfflinePackageFormat.VECTOR,
        role = OfflineLayerRole.BASE,
        vectorBasemapStyle = BasemapStyle.OpenStreets,
        targetTilesPerPackage = 24_000.0
    )

    private val OPEN_STREETS_RELIEF_VECTOR = OfflineSource(
        id = "open-streets-relief",
        format = OfflinePackageFormat.VECTOR,
        role = OfflineLayerRole.BASE,
        vectorBasemapStyle = BasemapStyle.OpenStreetsReliefBase,
        targetTilesPerPackage = 24_000.0
    )

    private const val IMAGERY_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/World_Imagery/MapServer"
    private const val HILLSHADE_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/Elevation/World_Hillshade/MapServer"
    private const val REFERENCE_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/Reference/World_Boundaries_and_Places/MapServer"
}
