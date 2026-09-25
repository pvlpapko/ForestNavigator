package app.forestnav.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(
            "forestnav_settings",
            Context.MODE_PRIVATE
        )

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

    /**
     * Reference map scale shown by the ↔ indicator after the first GPS fix
     * and when the user recenters with "Я здесь".
     */
    var initialMapScaleMeters: Int
        get() = prefs
            .getInt(
                KEY_INITIAL_MAP_SCALE_METERS,
                DEFAULT_INITIAL_MAP_SCALE_METERS
            )
            .coerceIn(
                MIN_INITIAL_MAP_SCALE_METERS,
                MAX_INITIAL_MAP_SCALE_METERS
            )
        set(value) {
            prefs.edit()
                .putInt(
                    KEY_INITIAL_MAP_SCALE_METERS,
                    value.coerceIn(
                        MIN_INITIAL_MAP_SCALE_METERS,
                        MAX_INITIAL_MAP_SCALE_METERS
                    )
                )
                .apply()
        }

    companion object {
        const val DEFAULT_INITIAL_MAP_SCALE_METERS = 500
        const val MIN_INITIAL_MAP_SCALE_METERS = 50
        const val MAX_INITIAL_MAP_SCALE_METERS = 5_000

        private const val KEY_INITIAL_MAP_SCALE_METERS =
            "initial_map_scale_meters"
    }
}
