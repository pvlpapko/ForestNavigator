package app.forestnav.gnss

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

data class SensorCapability(val label: String, val available: Boolean, val purpose: String)

object SensorCapabilities {
    fun read(context: Context): List<SensorCapability> {
        val sm = context.getSystemService(SensorManager::class.java)
        fun has(type: Int) = sm.getDefaultSensor(type) != null
        return listOf(
            SensorCapability("GNSS/GPS", context.packageManager.hasSystemFeature("android.hardware.location.gps"), "Координаты со спутников"),
            SensorCapability("Rotation Vector", has(Sensor.TYPE_ROTATION_VECTOR), "Основной источник направления компаса"),
            SensorCapability("Магнитометр", has(Sensor.TYPE_MAGNETIC_FIELD), "Магнитный север; резерв для компаса"),
            SensorCapability("Акселерометр", has(Sensor.TYPE_ACCELEROMETER), "Наклон устройства; резерв для компаса"),
            SensorCapability("Гироскоп", has(Sensor.TYPE_GYROSCOPE), "Стабилизация ориентации на поддерживаемых устройствах"),
            SensorCapability("Барометр", has(Sensor.TYPE_PRESSURE), "Дополнительная оценка изменения высоты"),
            SensorCapability("Датчик приближения", has(Sensor.TYPE_PROXIMITY), "Не требуется для координат; только диагностика устройства")
        )
    }
}
