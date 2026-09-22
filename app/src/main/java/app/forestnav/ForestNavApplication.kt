package app.forestnav

import android.app.Application
import app.forestnav.data.ForestDatabase
import app.forestnav.data.SettingsStore
import app.forestnav.map.LocalMapStyleServer
import org.maplibre.android.MapLibre

class ForestNavApplication : Application() {
    lateinit var database: ForestDatabase
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        LocalMapStyleServer.start(this)
        database = ForestDatabase(this)
        settings = SettingsStore(this)
    }
}
