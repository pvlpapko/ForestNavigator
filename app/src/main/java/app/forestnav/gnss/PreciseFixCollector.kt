package app.forestnav.gnss

import android.location.Location
import kotlin.math.max
import kotlin.math.sqrt

class PreciseFixCollector(
    private val minSamples: Int = 8,
    private val maxSamples: Int = 30,
    private val targetAccuracyMeters: Float = 2.5f
) {
    private val samples = ArrayList<Location>(maxSamples)

    data class Result(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val estimatedAccuracyMeters: Float,
        val sampleCount: Int
    )

    fun add(location: Location): Result? {
        if (!location.hasAccuracy() || location.accuracy > 50f) return null
        samples += Location(location)
        if (samples.size < minSamples) return null
        val best = samples.minOf { it.accuracy }
        return if (best <= targetAccuracyMeters || samples.size >= maxSamples) calculate() else null
    }

    fun progress(): Pair<Int, Float?> = samples.size to samples.minOfOrNull { it.accuracy }

    private fun calculate(): Result {
        var sumW = 0.0
        var lat = 0.0
        var lon = 0.0
        var alt = 0.0
        var altW = 0.0
        for (s in samples) {
            val a = max(s.accuracy.toDouble(), 0.8)
            val w = 1.0 / (a * a)
            sumW += w
            lat += s.latitude * w
            lon += s.longitude * w
            if (s.hasAltitude()) {
                alt += s.altitude * w
                altW += w
            }
        }
        val formal = sqrt(1.0 / sumW).toFloat()
        val conservative = max(formal, samples.minOf { it.accuracy } * 0.75f)
        return Result(lat / sumW, lon / sumW, if (altW > 0) alt / altW else null, conservative, samples.size)
    }
}
