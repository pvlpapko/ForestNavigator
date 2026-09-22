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
 * Built-in map rendering restored to the 0.9.3 stack.
 *
 * The built-in modes are served through LocalMapStyleServer exactly like the
 * known-good 0.9.3 version. This keeps map/satellite/terrain visuals isolated
 * from later experimental style changes while still allowing newer UI/camera
 * behavior in the rest of the app.
 */
object MapStyles {
    private const val JSON_PREFIX = "json:"

    fun url(
        layer: MapLayer,
        settings: SettingsStore,
        online: Boolean = true
    ): String? = when (layer) {
        MapLayer.MAP -> LocalMapStyleServer.mapUrl()
        MapLayer.SATELLITE -> LocalMapStyleServer.satelliteUrl()
        MapLayer.TERRAIN -> LocalMapStyleServer.terrainUrl()
        MapLayer.SATELLITE_TERRAIN -> LocalMapStyleServer.combinedUrl()
        MapLayer.CUSTOM -> settings.customStyleUrl.takeIf { it.isNotBlank() }
    }

    fun isJsonStyle(value: String): Boolean = value.startsWith(JSON_PREFIX)

    fun jsonPayload(value: String): String = value.removePrefix(JSON_PREFIX)

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        settings.mapTilerKey.isNotBlank() &&
            (layer == MapLayer.SATELLITE ||
                layer == MapLayer.TERRAIN ||
                layer == MapLayer.SATELLITE_TERRAIN)
}
