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
import kotlin.math.roundToInt

class CompassEngine(private val context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading.asStateFlow()
    val available: Boolean get() = rotation != null || (accelerometer != null && magnetometer != null)

    private var lastEmitNs = 0L
    private var gravity: FloatArray? = null
    private var magnetic: FloatArray? = null

    fun start() {
        rotation?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            return
        }
        accelerometer?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.timestamp - lastEmitNs < 100_000_000L) return
        val matrix = FloatArray(9)
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                gravity = lowPass(event.values, gravity)
                val g = gravity ?: return
                val m = magnetic ?: return
                if (!SensorManager.getRotationMatrix(matrix, null, g, m)) return
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetic = lowPass(event.values, magnetic)
                val g = gravity ?: return
                val m = magnetic ?: return
                if (!SensorManager.getRotationMatrix(matrix, null, g, m)) return
            }
            else -> return
        }
        lastEmitNs = event.timestamp
        val remapped = FloatArray(9)
        @Suppress("DEPRECATION")
        val displayRotation = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
        val (x, y) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(matrix, x, y, remapped)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(remapped, orientation)
        var deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (deg < 0) deg += 360f
        _heading.value = ((deg * 10f).roundToInt() / 10f)
    }

    private fun lowPass(input: FloatArray, previous: FloatArray?): FloatArray {
        val out = previous?.copyOf() ?: input.copyOf()
        val alpha = 0.18f
        for (i in 0..2) out[i] += alpha * (input[i] - out[i])
        return out
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
