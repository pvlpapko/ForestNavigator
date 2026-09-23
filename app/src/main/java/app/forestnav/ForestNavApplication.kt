package app.forestnav

import android.app.Application
import app.forestnav.data.ForestDatabase
import app.forestnav.data.SettingsStore
import app.forestnav.map.LocalMapStyleServer
import com.mapbox.common.MapboxOptions
import org.maplibre.android.MapLibre

class ForestNavApplication : Application() {
    lateinit var database: ForestDatabase
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // Mapbox is retained only as the native 3D renderer. All map imagery,
        // relief and offline downloads use public EOX/AWS sources and do not
        // consume Mapbox tile/style API quota.
        MapboxOptions.accessToken =
            BuildConfig.MAPBOX_TOKEN_A +
                BuildConfig.MAPBOX_TOKEN_B +
                BuildConfig.MAPBOX_TOKEN_C
        LocalMapStyleServer.start(this)
        database = ForestDatabase(this)
        settings = SettingsStore(this)
    }
}
