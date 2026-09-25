package app.forestnav.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(
            "forestnav_settings",
            Context.MODE_PRIVATE
        )

    var customStyleUrl: String
        get() = prefs
            .getString("custom_style_url", "")
            .orEmpty()
        set(value) {
            prefs.edit()
                .putString(
                    "custom_style_url",
                    value.trim()
                )
                .apply()
        }

    var trackingMode: String
        get() = prefs
            .getString(
                "tracking_mode",
                "NORMAL"
            )
            ?: "NORMAL"
        set(value) {
            prefs.edit()
                .putString(
                    "tracking_mode",
                    value
                )
                .apply()
        }
}
