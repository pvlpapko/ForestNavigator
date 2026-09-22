package app.forestnav.map

import app.forestnav.data.SettingsStore

enum class MapLayer(val title: String) {
    MAP("Карта"),
    SATELLITE("Спутник"),
    TERRAIN("Рельеф"),
    SATELLITE_TERRAIN("Спутник+рельеф"),
    CUSTOM("Своя карта")
}

object MapStyles {
    private const val JSON_PREFIX = "json:"

    fun url(layer: MapLayer, settings: SettingsStore): String? = when (layer) {
        MapLayer.MAP -> OPEN_FREE_MAP
        MapLayer.SATELLITE -> LocalMapStyleServer.satelliteUrl()
        MapLayer.TERRAIN -> "asset://terrain_fallback.json"
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
