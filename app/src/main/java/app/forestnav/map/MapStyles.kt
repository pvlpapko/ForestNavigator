package app.forestnav.map

import com.arcgismaps.mapping.BasemapStyle

enum class MapLayer(val title: String) {
    MAP("Карта"),
    SATELLITE("Спутник"),
    RELIEF("Рельеф"),
    SATELLITE_TERRAIN("Спутник+рельеф")
}

data class ArcGisOfflineSource(
    val id: String,
    val url: String,
    val role: OfflineLayerRole,
    val opacity: Float = 1f
)

enum class OfflineLayerRole { BASE, HILLSHADE, REFERENCE }

object MapStyles {
    fun basemapStyle(layer: MapLayer): BasemapStyle = when (layer) {
        MapLayer.MAP -> BasemapStyle.ArcGISNavigation
        MapLayer.SATELLITE -> BasemapStyle.ArcGISImageryStandard
        MapLayer.RELIEF -> BasemapStyle.ArcGISStreetsRelief
        MapLayer.SATELLITE_TERRAIN -> BasemapStyle.ArcGISImagery
    }

    fun description(layer: MapLayer): String = when (layer) {
        MapLayer.MAP -> "ArcGIS Navigation • дороги, подписи, здания и объекты"
        MapLayer.SATELLITE -> "ArcGIS World Imagery • HD спутник/аэрофото"
        MapLayer.RELIEF -> "ArcGIS Streets Relief • дороги, тропы, подписи и рельеф"
        MapLayer.SATELLITE_TERRAIN -> "World Imagery + hillshade + подписи"
    }

    fun offlineSources(layer: MapLayer): List<ArcGisOfflineSource> = when (layer) {
        MapLayer.MAP -> listOf(ArcGisOfflineSource("topo", TOPO_EXPORT, OfflineLayerRole.BASE))
        MapLayer.SATELLITE -> listOf(ArcGisOfflineSource("imagery", IMAGERY_EXPORT, OfflineLayerRole.BASE))
        MapLayer.RELIEF -> listOf(ArcGisOfflineSource("topo", TOPO_EXPORT, OfflineLayerRole.BASE))
        MapLayer.SATELLITE_TERRAIN -> listOf(
            ArcGisOfflineSource("imagery", IMAGERY_EXPORT, OfflineLayerRole.BASE),
            ArcGisOfflineSource("hillshade", HILLSHADE_EXPORT, OfflineLayerRole.HILLSHADE, 0.25f),
            ArcGisOfflineSource("reference", REFERENCE_EXPORT, OfflineLayerRole.REFERENCE)
        )
    }

    const val HILLSHADE_ONLINE =
        "https://services.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer"

    private const val IMAGERY_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/World_Imagery/MapServer"
    private const val TOPO_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/World_Topo_Map/MapServer"
    private const val HILLSHADE_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/Elevation/World_Hillshade/MapServer"
    private const val REFERENCE_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/Reference/World_Boundaries_and_Places/MapServer"
}
