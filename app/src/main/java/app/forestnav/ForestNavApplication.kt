package app.forestnav

import android.app.Application
import app.forestnav.data.ForestDatabase
import app.forestnav.data.SettingsStore
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestImpl
import java.util.concurrent.TimeUnit

class ForestNavApplication : Application() {
    lateinit var database: ForestDatabase
        private set

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()

        MapLibre.getInstance(this)

        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }

        val mapHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        HttpRequestUtil.setOkHttpClient(mapHttpClient)

        database = ForestDatabase(this)
        settings = SettingsStore(this)
    }
}
