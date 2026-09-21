package app.forestnav.map

import android.content.Context
import app.forestnav.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Local raster cache/proxy used by MapLibre.
 *
 * MapLibre only talks to 127.0.0.1. Missing tiles are fetched from MapTiler
 * one-at-a-time with retry/backoff, which prevents the burst of requests that
 * previously caused HTTP 429 and also lets finalized offline regions override
 * the network transparently.
 */
object LocalMapStyleServer {
    private val started = AtomicBoolean(false)
    private val fetchLock = Any()

    @Volatile private var port: Int = 8765
    @Volatile private var lastRemoteRequestAt: Long = 0L

    private lateinit var appContext: Context
    private lateinit var onlineCache: File
    private lateinit var offlineRoot: File
    private var serverSocket: ServerSocket? = null
    private val clientExecutor = ThreadPoolExecutor(
        4,
        4,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(48),
        { runnable ->
            Thread(runnable, "forest-map-http-client").apply { isDaemon = true }
        }
    )

    private const val MIN_REMOTE_REQUEST_INTERVAL_MS = 100L

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        onlineCache = File(appContext.filesDir, "map_tile_cache").apply { mkdirs() }
        offlineRoot = File(appContext.filesDir, "offline_regions").apply { mkdirs() }
    }

    fun mapUrl(): String = baseUrl("style/map.json")
    fun satelliteUrl(): String = baseUrl("style/satellite.json")
    fun terrainUrl(): String = baseUrl("style/terrain.json")
    fun combinedUrl(): String = baseUrl("style/combined.json")

    fun sourcesFor(layer: MapLayer): List<String> = when (layer) {
        MapLayer.MAP -> listOf("map")
        MapLayer.SATELLITE -> listOf("satellite")
        MapLayer.TERRAIN -> listOf("terrain")
        MapLayer.SATELLITE_TERRAIN -> listOf("satellite", "terrain")
        MapLayer.CUSTOM -> emptyList()
    }

    fun tileExtension(source: String): String =
        if (source == "satellite") "jpg" else "png"

    fun tileFile(regionDir: File, source: String, z: Int, x: Int, y: Int): File =
        File(regionDir, "$source/$z/$x/$y.${tileExtension(source)}")

    fun downloadTileTo(
        source: String,
        z: Int,
        x: Int,
        y: Int,
        destination: File
    ): Long {
        findCachedTile(source, z, x, y)?.let { cached ->
            destination.parentFile?.mkdirs()
            cached.copyTo(destination, overwrite = true)
            return destination.length()
        }

        val bytes = fetchRemoteTile(source, z, x, y)
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)

        val online = onlineTile(source, z, x, y)
        online.parentFile?.mkdirs()
        if (!online.exists()) {
            runCatching { destination.copyTo(online, overwrite = false) }
        }
        return bytes.size.toLong()
    }

    private fun baseUrl(path: String): String {
        check(started.get()) { "LocalMapStyleServer.start(context) must run first" }
        return "http://127.0.0.1:$port/$path"
    }

    private fun serve(client: Socket) {
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
        val requestLine = reader.readLine().orEmpty()
        val path = requestLine.split(' ').getOrNull(1)
            ?.substringBefore('?')
            ?.trimStart('/')
            .orEmpty()

        when {
            path == "style/map.json" -> sendJson(client, mapStyle())
            path == "style/satellite.json" -> sendJson(client, satelliteStyle())
            path == "style/terrain.json" -> sendJson(client, terrainStyle())
            path == "style/combined.json" -> sendJson(client, combinedStyle())
            path.startsWith("tile/") -> serveTile(client, path)
            else -> sendStatus(client, 404, "Not Found")
        }
    }

    private fun serveTile(client: Socket, path: String) {
        // tile/{source}/{z}/{x}/{y}.{ext}
        val parts = path.split('/')
        if (parts.size != 5) {
            sendStatus(client, 400, "Bad Request")
            return
        }

        val source = parts[1]
        val z = parts[2].toIntOrNull()
        val x = parts[3].toIntOrNull()
        val yPart = parts[4]
        val y = yPart.substringBeforeLast('.').toIntOrNull()
        val ext = yPart.substringAfterLast('.', missingDelimiterValue = "")

        if (source !in setOf("map", "satellite", "terrain") ||
            z == null || x == null || y == null ||
            ext != tileExtension(source)
        ) {
            sendStatus(client, 400, "Bad Request")
            return
        }

        findCachedTile(source, z, x, y)?.let { cached ->
            sendBinary(client, cached.readBytes(), contentType(source))
            return
        }

        runCatching {
            val bytes = fetchRemoteTile(source, z, x, y)
            val online = onlineTile(source, z, x, y)
            online.parentFile?.mkdirs()
            online.writeBytes(bytes)
            sendBinary(client, bytes, contentType(source))
        }.onFailure { error ->
            val msg = error.message.orEmpty()
            val code = when {
                msg.contains("429") -> 503
                msg.contains("404") -> 404
                else -> 502
            }
            sendStatus(client, code, msg.ifBlank { "Tile fetch failed" })
        }
    }

    private fun findCachedTile(source: String, z: Int, x: Int, y: Int): File? {
        val online = onlineTile(source, z, x, y)
        if (online.isFile && online.length() > 0L) return online

        offlineRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".partial-") }
            ?.forEach { region ->
                val file = tileFile(region, source, z, x, y)
                if (file.isFile && file.length() > 0L) return file
            }

        return null
    }

    private fun onlineTile(source: String, z: Int, x: Int, y: Int): File =
        File(onlineCache, "$source/$z/$x/$y.${tileExtension(source)}")

    private fun fetchRemoteTile(source: String, z: Int, x: Int, y: Int): ByteArray =
        synchronized(fetchLock) {
            var lastError: String? = null

            repeat(5) { attempt ->
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Загрузка отменена")
                }

                val now = System.currentTimeMillis()
                val waitMs = MIN_REMOTE_REQUEST_INTERVAL_MS - (now - lastRemoteRequestAt)
                if (waitMs > 0L) Thread.sleep(waitMs)
                lastRemoteRequestAt = System.currentTimeMillis()

                val connection = URL(remoteUrl(source, z, x, y)).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 25_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "ForestNavigator/0.9 Android")
                connection.setRequestProperty("Accept", "image/*")

                try {
                    val status = connection.responseCode
                    if (status in 200..299) {
                        return@synchronized connection.inputStream.use { it.readBytes() }
                    }

                    if (status == 429) {
                        val retryAfterSeconds =
                            connection.getHeaderField("Retry-After")?.toLongOrNull()
                        val delayMs = max(
                            (retryAfterSeconds ?: 0L) * 1000L,
                            (1L shl attempt.coerceAtMost(4)) * 1200L
                        )
                        lastError = "HTTP 429"
                        Thread.sleep(delayMs.coerceAtMost(20_000L))
                    } else {
                        lastError = "HTTP $status"
                        if (status == 404) {
                            throw IllegalStateException("HTTP 404")
                        }
                        Thread.sleep((attempt + 1) * 700L)
                    }
                } finally {
                    connection.disconnect()
                }
            }

            throw IllegalStateException(
                if (lastError == "HTTP 429") {
                    "HTTP 429: сервис карты временно ограничил частоту запросов"
                } else {
                    lastError ?: "Не удалось получить тайл"
                }
            )
        }

    private fun remoteUrl(source: String, z: Int, x: Int, y: Int): String {
        val key = URLEncoder.encode(BuildConfig.MAPTILER_KEY, StandardCharsets.UTF_8.toString())
        return when (source) {
            "map" ->
                "https://api.maptiler.com/maps/streets-v4/256/$z/$x/$y.png?key=$key"
            "satellite" ->
                "https://api.maptiler.com/maps/satellite-v4/256/$z/$x/$y.jpg?key=$key"
            "terrain" ->
                "https://api.maptiler.com/maps/outdoor-v4/256/$z/$x/$y.png?key=$key"
            else -> error("Unknown map source: $source")
        }
    }

    private fun tileTemplate(source: String): String =
        "http://127.0.0.1:$port/tile/$source/{z}/{x}/{y}.${tileExtension(source)}"

    private fun mapStyle(): String = rasterStyle(
        name = "Forest Navigator Map",
        source = "map",
        maxZoom = 20
    )

    private fun satelliteStyle(): String = rasterStyle(
        name = "Forest Navigator Satellite",
        source = "satellite",
        maxZoom = 20
    )

    private fun terrainStyle(): String = rasterStyle(
        name = "Forest Navigator Relief",
        source = "terrain",
        maxZoom = 20
    )

    private fun combinedStyle(): String = """
        {
          "version": 8,
          "name": "Forest Navigator Satellite + Relief",
          "sources": {
            "satellite": {
              "type": "raster",
              "tiles": ["${tileTemplate("satellite")}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 20
            },
            "terrain": {
              "type": "raster",
              "tiles": ["${tileTemplate("terrain")}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 20
            }
          },
          "layers": [
            {
              "id": "satellite",
              "type": "raster",
              "source": "satellite"
            },
            {
              "id": "relief-overlay",
              "type": "raster",
              "source": "terrain",
              "paint": {
                "raster-opacity": 0.34,
                "raster-contrast": 0.12
              }
            }
          ]
        }
    """.trimIndent()

    private fun rasterStyle(name: String, source: String, maxZoom: Int): String = """
        {
          "version": 8,
          "name": "$name",
          "sources": {
            "$source": {
              "type": "raster",
              "tiles": ["${tileTemplate(source)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": $maxZoom
            }
          },
          "layers": [
            {
              "id": "$source",
              "type": "raster",
              "source": "$source"
            }
          ]
        }
    """.trimIndent()

    private fun contentType(source: String): String =
        if (source == "satellite") "image/jpeg" else "image/png"

    private fun sendJson(client: Socket, body: String) =
        sendBinary(client, body.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

    private fun sendBinary(client: Socket, bytes: ByteArray, contentType: String) {
        val output = client.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Cache-Control: public, max-age=604800\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun sendStatus(client: Socket, status: Int, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val output = client.getOutputStream()
        val reason = when (status) {
            400 -> "Bad Request"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "Bad Gateway"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }
}
