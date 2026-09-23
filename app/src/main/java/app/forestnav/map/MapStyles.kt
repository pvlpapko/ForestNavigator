package app.forestnav.map

import app.forestnav.data.SettingsStore

enum class MapLayer(val title: String) {
    MAP("Карта"),
    SATELLITE("Спутник"),
    RELIEF("Рельеф"),
    SATELLITE_TERRAIN("Спутник+рельеф"),
    THREE_D("3D спутник"),
    THREE_D_TERRAIN("3D"),
    CUSTOM("Своя карта")
}

object MapStyles {
    private const val JSON_PREFIX = "json:"

    fun url(
        layer: MapLayer,
        settings: SettingsStore,
        online: Boolean = true
    ): String? {
        @Suppress("UNUSED_VARIABLE")
        val networkAvailable = online

        if (layer == MapLayer.CUSTOM) {
            return settings.customStyleUrl.takeIf { it.isNotBlank() }
        }

        // Normal 2D modes always use the localhost proxy. It prefers downloaded
        // regions/cache first and only fetches missing public EOX/AWS tiles online.
        return when (layer) {
            MapLayer.MAP -> LocalMapStyleServer.mapUrl()
            MapLayer.SATELLITE -> LocalMapStyleServer.satelliteUrl()
            MapLayer.RELIEF -> LocalMapStyleServer.reliefUrl()
            MapLayer.SATELLITE_TERRAIN -> LocalMapStyleServer.combinedUrl()
            MapLayer.THREE_D,
            MapLayer.THREE_D_TERRAIN,
            MapLayer.CUSTOM -> null
        }
    }

    fun isJsonStyle(value: String): Boolean =
        value.startsWith(JSON_PREFIX)

    fun jsonPayload(value: String): String =
        value.removePrefix(JSON_PREFIX)

    fun highDetailEnabled(layer: MapLayer, settings: SettingsStore): Boolean =
        layer != MapLayer.CUSTOM || settings.customStyleUrl.isNotBlank()
}
