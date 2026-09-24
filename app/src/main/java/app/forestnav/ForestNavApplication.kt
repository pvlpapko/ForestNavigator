package app.forestnav

import android.app.Application
import app.forestnav.data.ForestDatabase
import app.forestnav.data.SettingsStore
import com.arcgismaps.ArcGISEnvironment
import com.arcgismaps.ApiKey
import org.maplibre.android.MapLibre

class ForestNavApplication : Application() {
    lateinit var database: ForestDatabase
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)

        ArcGISEnvironment.applicationContext = applicationContext

        database = ForestDatabase(this)
        settings = SettingsStore(this)

        val key = BuildConfig.ARCGIS_API_KEY.trim()
        ArcGISEnvironment.apiKey = if (key.isBlank()) null else ApiKey.create(key)
    }
}
