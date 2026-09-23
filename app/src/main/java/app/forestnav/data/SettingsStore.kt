package app.forestnav.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        "forestnav_settings",
        Context.MODE_PRIVATE
    )

    fun hasValidatedInternet(): Boolean {
        val connectivity =
            appContext.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        val capabilities =
            connectivity.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        )
    }

    var customStyleUrl: String
        get() = prefs.getString("custom_style_url", "").orEmpty()
        set(value) = prefs.edit()
            .putString("custom_style_url", value.trim())
            .apply()

    var trackingMode: String
        get() = prefs.getString("tracking_mode", "NORMAL") ?: "NORMAL"
        set(value) = prefs.edit()
            .putString("tracking_mode", value)
            .apply()
}
