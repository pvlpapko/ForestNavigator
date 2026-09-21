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
    const val OPEN_FREE_MAP = "https://tiles.openfreemap.org/styles/liberty"
    private const val MAPTILER_SATELLITE = "asset://maptiler_satellite.json"
    private const val MAPTILER_TERRAIN = "asset://maptiler_terrain.json"
    private const val MAPTILER_COMBINED = "asset://maptiler_satellite_terrain.json"

    fun url(layer: MapLayer, settings: SettingsStore): String? = when (layer) {
        MapLayer.MAP -> OPEN_FREE_MAP
        MapLayer.SATELLITE -> MAPTILER_SATELLITE
        MapLayer.TERRAIN -> MAPTILER_TERRAIN
        MapLayer.SATELLITE_TERRAIN -> MAPTILER_COMBINED
        MapLayer.CUSTOM -> settings.customStyleUrl.takeIf { it.isNotBlank() }
    }

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        settings.mapTilerKey.isNotBlank() &&
            (layer == MapLayer.SATELLITE ||
                layer == MapLayer.TERRAIN ||
                layer == MapLayer.SATELLITE_TERRAIN)
}
