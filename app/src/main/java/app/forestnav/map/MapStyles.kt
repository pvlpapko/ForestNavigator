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
 * Clean rebuild based on the last early map configuration with corrected
 * MapTiler addressing.
 *
 * ONLINE rendering is kept completely separate from the offline downloader:
 * the exact app assets render directly in MapLibre and never pass through the
 * local cache/proxy. OFFLINE rendering switches to LocalMapStyleServer, which
 * serves only downloaded/cached tiles.
 */
object MapStyles {
    private const val JSON_PREFIX = "json:"
    private const val OPEN_FREE_MAP = "https://tiles.openfreemap.org/styles/liberty"
    private const val SATELLITE_ASSET = "asset://maptiler_satellite.json"
    private const val TERRAIN_ASSET = "asset://maptiler_terrain.json"
    private const val COMBINED_ASSET = "asset://maptiler_satellite_terrain.json"

    fun url(
        layer: MapLayer,
        settings: SettingsStore,
        online: Boolean = true
    ): String? {
        if (layer == MapLayer.CUSTOM) {
            return settings.customStyleUrl.takeIf { it.isNotBlank() }
        }

        if (!online) {
            return when (layer) {
                MapLayer.MAP -> LocalMapStyleServer.mapUrl()
                MapLayer.SATELLITE -> LocalMapStyleServer.satelliteUrl()
                MapLayer.TERRAIN -> LocalMapStyleServer.terrainUrl()
                MapLayer.SATELLITE_TERRAIN -> LocalMapStyleServer.combinedUrl()
                MapLayer.CUSTOM -> null
            }
        }

        return when (layer) {
            MapLayer.MAP -> OPEN_FREE_MAP
            MapLayer.SATELLITE -> SATELLITE_ASSET
            MapLayer.TERRAIN -> TERRAIN_ASSET
            MapLayer.SATELLITE_TERRAIN -> COMBINED_ASSET
            MapLayer.CUSTOM -> null
        }
    }

    fun isJsonStyle(value: String): Boolean = value.startsWith(JSON_PREFIX)

    fun jsonPayload(value: String): String = value.removePrefix(JSON_PREFIX)

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        settings.mapTilerKey.isNotBlank() &&
            (layer == MapLayer.SATELLITE ||
                layer == MapLayer.TERRAIN ||
                layer == MapLayer.SATELLITE_TERRAIN)
}
