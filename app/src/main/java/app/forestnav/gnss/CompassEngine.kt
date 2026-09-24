package app.forestnav.gnss

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp

class CompassEngine(private val context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading.asStateFlow()
    val available: Boolean
        get() = rotation != null || (accelerometer != null && magnetometer != null)

    private var lastEmitNs = 0L
    private var lastSensorNs = 0L
    private var gravity: FloatArray? = null
    private var magnetic: FloatArray? = null
    private var smoothedHeading: Float? = null

    fun start() {
        rotation?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            return
        }

        accelerometer?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometer?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val matrix = FloatArray(9)

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                gravity = lowPassVector(event.values, gravity)
                val g = gravity ?: return
                val m = magnetic ?: return
                if (!SensorManager.getRotationMatrix(matrix, null, g, m)) return
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetic = lowPassVector(event.values, magnetic)
                val g = gravity ?: return
                val m = magnetic ?: return
                if (!SensorManager.getRotationMatrix(matrix, null, g, m)) return
            }

            else -> return
        }

        val remapped = FloatArray(9)
        @Suppress("DEPRECATION")
        val displayRotation =
            context.getSystemService(WindowManager::class.java).defaultDisplay.rotation

        val (x, y) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }

        SensorManager.remapCoordinateSystem(matrix, x, y, remapped)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(remapped, orientation)

        var raw = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (raw < 0f) raw += 360f

        val now = event.timestamp
        val previous = smoothedHeading
        val smoothed = if (previous == null || lastSensorNs == 0L) {
            raw
        } else {
            val dtSeconds =
                ((now - lastSensorNs) / 1_000_000_000.0).coerceIn(0.005, 0.20)
            val delta = shortestAngle(raw - previous)
            val timeConstant = when {
                abs(delta) >= 35f -> 0.08
                abs(delta) >= 12f -> 0.12
                else -> 0.18
            }
            val alpha = (1.0 - exp(-dtSeconds / timeConstant)).toFloat()
            normalize(previous + delta * alpha)
        }

        lastSensorNs = now
        smoothedHeading = smoothed

        if (now - lastEmitNs < EMIT_INTERVAL_NS) return

        val current = _heading.value
        if (current == null ||
            abs(shortestAngle(smoothed - current)) >= MIN_EMIT_DELTA_DEG ||
            now - lastEmitNs >= FORCE_EMIT_INTERVAL_NS
        ) {
            _heading.value = smoothed
            lastEmitNs = now
        }
    }

    private fun shortestAngle(value: Float): Float {
        var d = value % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun normalize(value: Float): Float {
        var v = value % 360f
        if (v < 0f) v += 360f
        return v
    }

    private fun lowPassVector(input: FloatArray, previous: FloatArray?): FloatArray {
        val out = previous?.copyOf() ?: input.copyOf()
        for (i in 0..2) {
            out[i] += FALLBACK_VECTOR_ALPHA * (input[i] - out[i])
        }
        return out
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val FALLBACK_VECTOR_ALPHA = 0.12f
        private const val MIN_EMIT_DELTA_DEG = 0.12f
        private const val EMIT_INTERVAL_NS = 50_000_000L
        private const val FORCE_EMIT_INTERVAL_NS = 250_000_000L
    }
}
