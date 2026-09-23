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

data class ArcGisOfflineSource(
    val id: String,
    val url: String,
    val role: OfflineLayerRole,
    val opacity: Float = 1f
)

enum class OfflineLayerRole { BASE, HILLSHADE, REFERENCE }

object MapStyles {
    private const val JSON_PREFIX = "json:"

    fun basemapStyle(layer: MapLayer): BasemapStyle = when (layer) {
        MapLayer.MAP -> BasemapStyle.OpenOsmStyle
        MapLayer.SATELLITE -> BasemapStyle.ArcGISImageryStandard
        MapLayer.RELIEF -> BasemapStyle.OpenOsmStyleRelief
        MapLayer.SATELLITE_TERRAIN -> BasemapStyle.ArcGISImagery
        MapLayer.CUSTOM -> BasemapStyle.OpenOsmStyle
    }

    fun description(layer: MapLayer): String = when (layer) {
        MapLayer.MAP ->
            "Open OSM • дома, дороги, тропы, подписи и адресные номера на крупном масштабе"
        MapLayer.SATELLITE ->
            "ArcGIS World Imagery • HD спутниковые и аэрофотоснимки"
        MapLayer.RELIEF ->
            "Open OSM Relief • те же дома и подписи + теневой рельеф"
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

    fun offlineSources(layer: MapLayer): List<ArcGisOfflineSource> = when (layer) {
        MapLayer.MAP -> listOf(
            ArcGisOfflineSource(
                id = "streets",
                url = STREET_EXPORT,
                role = OfflineLayerRole.BASE
            )
        )
        MapLayer.SATELLITE -> listOf(
            ArcGisOfflineSource(
                id = "imagery",
                url = IMAGERY_EXPORT,
                role = OfflineLayerRole.BASE
            )
        )
        MapLayer.RELIEF -> listOf(
            ArcGisOfflineSource(
                id = "streets",
                url = STREET_EXPORT,
                role = OfflineLayerRole.BASE
            ),
            ArcGisOfflineSource(
                id = "hillshade",
                url = HILLSHADE_EXPORT,
                role = OfflineLayerRole.HILLSHADE,
                opacity = 0.22f
            )
        )
        MapLayer.SATELLITE_TERRAIN -> listOf(
            ArcGisOfflineSource(
                id = "imagery",
                url = IMAGERY_EXPORT,
                role = OfflineLayerRole.BASE
            ),
            ArcGisOfflineSource(
                id = "hillshade",
                url = HILLSHADE_EXPORT,
                role = OfflineLayerRole.HILLSHADE,
                opacity = 0.22f
            ),
            ArcGisOfflineSource(
                id = "reference",
                url = REFERENCE_EXPORT,
                role = OfflineLayerRole.REFERENCE
            )
        )
        MapLayer.CUSTOM -> emptyList()
    }

    const val HILLSHADE_ONLINE =
        "https://services.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer"

    private const val STREET_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/World_Street_Map/MapServer"
    private const val IMAGERY_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/World_Imagery/MapServer"
    private const val HILLSHADE_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/Elevation/World_Hillshade/MapServer"
    private const val REFERENCE_EXPORT =
        "https://tiledbasemaps.arcgis.com/arcgis/rest/services/Reference/World_Boundaries_and_Places/MapServer"
}
