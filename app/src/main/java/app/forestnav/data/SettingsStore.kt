package app.forestnav.data

import android.content.Context
import java.io.File

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("forestnav_settings", Context.MODE_PRIVATE)

    var mapTilerKey: String
        get() = prefs.getString("maptiler_key", "").orEmpty()
        set(value) = prefs.edit().putString("maptiler_key", value.trim()).apply()

    var customStyleUrl: String
        get() = prefs.getString("custom_style_url", "").orEmpty()
        set(value) = prefs.edit().putString("custom_style_url", value.trim()).apply()

    var trackingMode: String
        get() = prefs.getString("tracking_mode", "NORMAL") ?: "NORMAL"
        set(value) = prefs.edit().putString("tracking_mode", value).apply()

    fun writeGeneratedStyle(fileName: String, json: String): String? = runCatching {
        val dir = File(appContext.filesDir, "map_styles").apply { mkdirs() }
        val file = File(dir, fileName)
        if (!file.exists() || file.readText(Charsets.UTF_8) != json) {
            file.writeText(json, Charsets.UTF_8)
        }
        file.toURI().toString()
    }.getOrNull()
}
