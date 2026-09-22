package app.forestnav.map

import android.content.Context
import app.forestnav.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
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
 * Local raster cache/proxy used by MapLibre and by the offline downloader.
 *
 * Remote requests are allowed to overlap while a shared rate limiter spaces the
 * request starts. This hides network latency without hammering the tile service.
 */
object LocalMapStyleServer {
    private val started = AtomicBoolean(false)
    private val rateLock = Any()

    @Volatile private var port: Int = 8765
    @Volatile private var lastRemoteRequestAt: Long = 0L
    @Volatile private var remoteBlockedUntil: Long = 0L

    private lateinit var appContext: Context
    private lateinit var onlineCache: File
    private lateinit var offlineRoot: File
    private var serverSocket: ServerSocket? = null

    private val clientExecutor = ThreadPoolExecutor(
        8,
        8,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(192),
        { runnable ->
            Thread(runnable, "forest-map-http-client").apply { isDaemon = true }
        }
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        onlineCache = File(appContext.filesDir, "map_tile_cache").apply { mkdirs() }
        offlineRoot = File(appContext.filesDir, "offline_regions").apply { mkdirs() }

        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val socket = runCatching { ServerSocket(port, 64, loopback) }
                .getOrElse { ServerSocket(0, 64, loopback) }
            serverSocket = socket
            port = socket.localPort

            Thread({
                while (started.get() && !socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }

                    try {
                        clientExecutor.execute {
                            client.use { connection ->
                                runCatching { serve(connection) }
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        runCatching { client.close() }
                    }
                }
            }, "forest-map-http-server").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            started.set(false)
            throw t
        }
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
        File(regionDir, "v3/$source/$z/$x/$y.${tileExtension(source)}")

    fun downloadTileTo(
        source: String,
        z: Int,
        x: Int,
        y: Int,
        destination: File
    ): Long {
        if (destination.isFile && destination.length() > 0L) return destination.length()

        findCachedTile(source, z, x, y)?.let { cached ->
            destination.parentFile?.mkdirs()
            cached.copyTo(destination, overwrite = true)
            return destination.length()
        }

        val bytes = fetchRemoteTile(source, z, x, y)
        writeAtomically(destination, bytes)
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

        while (reader.readLine()?.isNotEmpty() == true) {
            // Drain request headers before writing the response.
        }

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
            writeAtomically(onlineTile(source, z, x, y), bytes)
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
            ?.filter { it.isDirectory }
            ?.sortedBy { if (it.name.startsWith(".partial-")) 1 else 0 }
            ?.forEach { region ->
                val file = tileFile(region, source, z, x, y)
                if (file.isFile && file.length() > 0L) return file
            }

        return null
    }

    private fun onlineTile(source: String, z: Int, x: Int, y: Int): File =
        File(onlineCache, "v3/$source/$z/$x/$y.${tileExtension(source)}")

    private fun fetchRemoteTile(source: String, z: Int, x: Int, y: Int): ByteArray {
        var lastError = "Не удалось получить тайл"

        for (attempt in 0 until MAX_REMOTE_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Загрузка отменена")

            awaitRemoteRequestSlot()

            val connection = URL(remoteUrl(source, z, x, y)).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "ForestNavigator/0.9 Android")
            connection.setRequestProperty("Accept", "image/*")

            try {
                val status = connection.responseCode
                if (status in 200..299) {
                    val data = connection.inputStream.use { it.readBytes() }
                    if (data.isEmpty()) throw IOException("Пустой ответ сервера")
                    return data
                }

                if (status == 404) throw IllegalStateException("HTTP 404")

                if (status == 429) {
                    val retryAfterSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
                    val delayMs = max(
                        (retryAfterSeconds ?: 0L) * 1000L,
                        (1L shl attempt.coerceAtMost(4)) * 1_200L
                    ).coerceAtMost(20_000L)
                    lastError = "HTTP 429"
                    pauseRemoteRequests(delayMs)
                } else {
                    lastError = "HTTP $status"
                    Thread.sleep(((attempt + 1) * 600L).coerceAtMost(3_000L))
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: SocketTimeoutException) {
                lastError = "Timeout: ${e.message ?: "сервер карты не ответил"}"
                if (attempt + 1 < MAX_REMOTE_ATTEMPTS) {
                    Thread.sleep(((attempt + 1) * 800L).coerceAtMost(4_000L))
                }
            } catch (e: IOException) {
                lastError = e.message ?: "Ошибка сети"
                if (attempt + 1 < MAX_REMOTE_ATTEMPTS) {
                    Thread.sleep(((attempt + 1) * 700L).coerceAtMost(3_500L))
                }
            } finally {
                connection.disconnect()
            }
        }

        throw IllegalStateException(
            if (lastError == "HTTP 429") {
                "HTTP 429: сервис карты временно ограничил частоту запросов"
            } else {
                lastError
            }
        )
    }

    private fun awaitRemoteRequestSlot() {
        synchronized(rateLock) {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Загрузка отменена")
                val now = System.currentTimeMillis()
                val earliest = max(lastRemoteRequestAt + MIN_REMOTE_REQUEST_INTERVAL_MS, remoteBlockedUntil)
                val waitMs = earliest - now
                if (waitMs <= 0L) {
                    lastRemoteRequestAt = now
                    return
                }
                Thread.sleep(waitMs.coerceAtMost(20_000L))
            }
        }
    }

    private fun pauseRemoteRequests(delayMs: Long) {
        synchronized(rateLock) {
            remoteBlockedUntil = max(remoteBlockedUntil, System.currentTimeMillis() + delayMs)
        }
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, ".${destination.name}.${Thread.currentThread().id}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }

    private fun remoteUrl(source: String, z: Int, x: Int, y: Int): String {
        val key = URLEncoder.encode(BuildConfig.MAPTILER_KEY, StandardCharsets.UTF_8.toString())
        return when (source) {
            "map" -> "https://api.maptiler.com/maps/streets-v4/256/$z/$x/$y@2x.png?key=$key"
            "satellite" -> "https://api.maptiler.com/maps/satellite-v4/256/$z/$x/$y@2x.jpg?key=$key"
            "terrain" -> "https://api.maptiler.com/maps/outdoor-v4/256/$z/$x/$y@2x.png?key=$key"
            else -> error("Unknown map source: $source")
        }
    }

    private fun tileTemplate(source: String): String =
        "http://127.0.0.1:$port/tile/$source/{z}/{x}/{y}.${tileExtension(source)}"

    private fun mapStyle(): String = rasterStyle("Forest Navigator Map", "map", 20)
    private fun satelliteStyle(): String = rasterStyle("Forest Navigator Satellite", "satellite", 20)
    private fun terrainStyle(): String = rasterStyle("Forest Navigator Relief", "terrain", 20)

    private fun combinedStyle(): String = """
        {
          "version": 8,
          "name": "Forest Navigator Satellite + Relief",
          "sources": {
            "satellite": {
              "type": "raster",
              "tiles": ["${tileTemplate("satellite")}"],
              "scheme": "xyz",
              "tileSize": 512,
              "minzoom": 0,
              "maxzoom": 20
            },
            "terrain": {
              "type": "raster",
              "tiles": ["${tileTemplate("terrain")}"],
              "scheme": "xyz",
              "tileSize": 512,
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
              "tileSize": 512,
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

    private const val MIN_REMOTE_REQUEST_INTERVAL_MS = 40L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 40_000
    private const val MAX_REMOTE_ATTEMPTS = 6
}
