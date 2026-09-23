package app.forestnav

import android.app.Application
import app.forestnav.data.ForestDatabase
import app.forestnav.data.SettingsStore
import com.arcgismaps.ArcGISEnvironment
import com.arcgismaps.ApiKey

class ForestNavApplication : Application() {
    lateinit var database: ForestDatabase
        private set

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        database = ForestDatabase(this)
        settings = SettingsStore(this)
        applyArcGisApiKey(settings.arcGisApiKey)
    }

    fun applyArcGisApiKey(value: String) {
        val key = value.trim()
        ArcGISEnvironment.apiKey = if (key.isBlank()) null else ApiKey.create(key)
    }
}
