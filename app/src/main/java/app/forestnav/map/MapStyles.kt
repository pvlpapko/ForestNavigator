package app.forestnav.map

import app.forestnav.data.SettingsStore

enum class MapLayer(val title: String) {
    MAP("Карта"),
    SATELLITE("Спутник"),
    TERRAIN("Рельеф"),
    CUSTOM("Своя карта")
}

object MapStyles {
    const val OPEN_FREE_MAP = "https://tiles.openfreemap.org/styles/liberty"
    private const val BUILTIN_SATELLITE = "asset://satellite_fallback.json"
    private const val BUILTIN_TERRAIN = "asset://terrain_fallback.json"

    fun url(layer: MapLayer, settings: SettingsStore): String? = when (layer) {
        MapLayer.MAP -> OPEN_FREE_MAP
        MapLayer.SATELLITE -> settings.mapTilerKey.takeIf { it.isNotBlank() }
            ?.let { "https://api.maptiler.com/maps/satellite-v4/style.json?key=$it" }
            ?: BUILTIN_SATELLITE
        MapLayer.TERRAIN -> settings.mapTilerKey.takeIf { it.isNotBlank() }
            ?.let { "https://api.maptiler.com/maps/landscape-v4/style.json?key=$it" }
            ?: BUILTIN_TERRAIN
        MapLayer.CUSTOM -> settings.customStyleUrl.takeIf { it.isNotBlank() }
    }
}
