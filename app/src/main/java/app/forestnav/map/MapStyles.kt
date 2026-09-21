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

        // Without a key the app falls back to the bundled Sentinel-2 style.
        // With the user's own key it switches to MapTiler's high-resolution
        // satellite catalog style.
        MapLayer.SATELLITE -> settings.mapTilerKey.takeIf { it.isNotBlank() }
            ?.let { "https://api.maptiler.com/maps/satellite-v4/style.json?key=$it" }
            ?: BUILTIN_SATELLITE

        // Outdoor v4 is more useful in a forest than the generic Landscape style:
        // it is designed for hiking/outdoor use and exposes contour/topographic detail.
        MapLayer.TERRAIN -> settings.mapTilerKey.takeIf { it.isNotBlank() }
            ?.let { "https://api.maptiler.com/maps/outdoor-v4/style.json?key=$it" }
            ?: BUILTIN_TERRAIN

        MapLayer.CUSTOM -> settings.customStyleUrl.takeIf { it.isNotBlank() }
    }

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        settings.mapTilerKey.isNotBlank() &&
            (layer == MapLayer.SATELLITE || layer == MapLayer.TERRAIN)
}
