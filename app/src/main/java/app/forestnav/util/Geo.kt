package app.forestnav.util

import android.location.Location
import kotlin.math.roundToInt

object Geo {
    fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, out)
        return out[0]
    }

    fun bearingDegrees(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val out = FloatArray(2)
        Location.distanceBetween(aLat, aLon, bLat, bLon, out)
        return (out[1] + 360f) % 360f
    }

    fun distanceLabel(meters: Float): String = when {
        meters < 1000f -> "${meters.roundToInt()} м"
        else -> String.format("%.2f км", meters / 1000f)
    }

    fun cardinal(deg: Float): String = when (((deg + 22.5f) / 45f).toInt() % 8) {
        0 -> "С"; 1 -> "СВ"; 2 -> "В"; 3 -> "ЮВ"; 4 -> "Ю"; 5 -> "ЮЗ"; 6 -> "З"; else -> "СЗ"
    }
}
