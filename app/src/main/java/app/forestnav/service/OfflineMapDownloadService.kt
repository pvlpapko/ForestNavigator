package app.forestnav.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class OfflineMapDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "offline_map_download"
        const val NOTIFICATION_ID = 2101
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Подготовка загрузки карты", 0))
        return START_STICKY
    }

    fun updateProgress(percent: Int, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text, percent))
    }

    private fun notification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(app.forestnav.R.drawable.ic_app)
            .setContentTitle("🌲 Forest Navigator")
            .setContentText(text)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Offline map downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
