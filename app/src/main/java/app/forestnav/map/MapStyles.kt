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

    // Remote immutable styles are intentional: MapLibre OfflineManager can fetch
    // the style document and enumerate its remote raster resources. Local asset://
    // styles render fine but do not reliably populate OfflineRegion resources.
    private const val SATELLITE_STYLE =
        "https://raw.githubusercontent.com/pvlpapko/ForestNavigator/73fcdb88f58f84c862ff44b7b86151fb0f4a9f69/app/src/main/assets/maptiler_satellite.json"
    private const val TERRAIN_STYLE =
        "https://raw.githubusercontent.com/pvlpapko/ForestNavigator/73fcdb88f58f84c862ff44b7b86151fb0f4a9f69/app/src/main/assets/maptiler_terrain.json"
    private const val COMBINED_STYLE =
        "https://raw.githubusercontent.com/pvlpapko/ForestNavigator/73fcdb88f58f84c862ff44b7b86151fb0f4a9f69/app/src/main/assets/maptiler_satellite_terrain.json"

    fun url(layer: MapLayer, settings: SettingsStore): String? = when (layer) {
        MapLayer.MAP -> OPEN_FREE_MAP
        MapLayer.SATELLITE -> SATELLITE_STYLE
        MapLayer.TERRAIN -> TERRAIN_STYLE
        MapLayer.SATELLITE_TERRAIN -> COMBINED_STYLE
        MapLayer.CUSTOM -> settings.customStyleUrl.takeIf { it.isNotBlank() }
    }

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        settings.mapTilerKey.isNotBlank() &&
            (layer == MapLayer.SATELLITE ||
                layer == MapLayer.TERRAIN ||
                layer == MapLayer.SATELLITE_TERRAIN)
}
