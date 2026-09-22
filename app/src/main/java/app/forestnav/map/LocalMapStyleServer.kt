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
 * 0.9.3 map stack with a more conservative network layer.
 *
 * Visual sources/styles intentionally match 0.9.3:
 * - MapTiler Streets v4
 * - MapTiler Satellite v4
 * - MapTiler Outdoor v4
 * - Satellite + Outdoor raster overlay
 *
 * Only transport/cache/retry behavior is changed for stability.
 */
object LocalMapStyleServer {
    private val started = AtomicBoolean(false)
    private val rateLock = Any()

    @Volatile private var port: Int = 8765
    @Volatile private var lastRemoteRequestAt: Long = 0L
    @Volatile private var remoteBlockedUntil: Long = 0L

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

        val appContext = context.applicationContext
        onlineCache = File(appContext.filesDir, "map_tile_cache_093_stable").apply { mkdirs() }
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
        MapLayer.MAP -> listOf(SOURCE_MAP)
        MapLayer.SATELLITE -> listOf(SOURCE_SATELLITE)
        MapLayer.TERRAIN -> listOf(SOURCE_TERRAIN)
        MapLayer.SATELLITE_TERRAIN -> listOf(SOURCE_SATELLITE, SOURCE_TERRAIN)
        MapLayer.CUSTOM -> emptyList()
    }

    fun maxDownloadZoom(source: String): Int = when (source) {
        SOURCE_MAP, SOURCE_SATELLITE, SOURCE_TERRAIN -> 20
        else -> 0
    }

    fun tileExtension(source: String): String =
        if (source == SOURCE_SATELLITE) "jpg" else "png"

    fun tileFile(regionDir: File, source: String, z: Int, x: Int, y: Int): File =
        File(regionDir, "v093-stable/$source/$z/$x/$y.${tileExtension(source)}")

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
            if (cached.absolutePath != destination.absolutePath) {
                cached.copyTo(destination, overwrite = true)
            }
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

        while (reader.readLine()?.isNotEmpty() == true) Unit

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

        if (source !in VALID_SOURCES ||
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
            val message = error.message.orEmpty()
            val code = when {
                message.contains("429") -> 503
                message.contains("404") -> 404
                else -> 502
            }
            sendStatus(client, code, message.ifBlank { "Tile fetch failed" })
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
        File(onlineCache, "$source/$z/$x/$y.${tileExtension(source)}")

    private fun fetchRemoteTile(source: String, z: Int, x: Int, y: Int): ByteArray {
        var lastError = "Не удалось получить тайл"

        for (attempt in 0 until MAX_REMOTE_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Загрузка отменена")
            }

            awaitRemoteRequestSlot()

            val connection = URL(remoteUrl(source, z, x, y)).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "ForestNavigator/0.9.18 Android")
            connection.setRequestProperty("Accept", "image/*")
            connection.setRequestProperty("Accept-Encoding", "identity")

            try {
                val status = connection.responseCode
                if (status in 200..299) {
                    val data = connection.inputStream.use { it.readBytes() }
                    if (data.size < MIN_TILE_BYTES) {
                        throw IOException("Пустой или повреждённый тайл")
                    }
                    return data
                }

                if (status == 404) {
                    throw IllegalStateException("HTTP 404")
                }

                if (status == 429) {
                    val retryAfterSeconds =
                        connection.getHeaderField("Retry-After")?.toLongOrNull()
                    val delayMs = max(
                        (retryAfterSeconds ?: 0L) * 1000L,
                        (1L shl attempt.coerceAtMost(4)) * 1_500L
                    ).coerceAtMost(MAX_GLOBAL_BACKOFF_MS)
                    lastError = "HTTP 429"
                    pauseRemoteRequests(delayMs)
                } else {
                    lastError = "HTTP $status"
                    if (attempt + 1 < MAX_REMOTE_ATTEMPTS) {
                        Thread.sleep(
                            ((attempt + 1) * 900L).coerceAtMost(4_500L)
                        )
                    }
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: SocketTimeoutException) {
                lastError = "Timeout: ${e.message ?: "сервер карты не ответил"}"
                if (attempt + 1 < MAX_REMOTE_ATTEMPTS) {
                    Thread.sleep(
                        ((attempt + 1) * 1_000L).coerceAtMost(5_000L)
                    )
                }
            } catch (e: IOException) {
                lastError = e.message ?: "Ошибка сети"
                if (attempt + 1 < MAX_REMOTE_ATTEMPTS) {
                    Thread.sleep(
                        ((attempt + 1) * 850L).coerceAtMost(4_250L)
                    )
                }
            } finally {
                runCatching { connection.errorStream?.close() }
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
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Загрузка отменена")
                }

                val now = System.currentTimeMillis()
                val earliest = max(
                    lastRemoteRequestAt + MIN_REMOTE_REQUEST_INTERVAL_MS,
                    remoteBlockedUntil
                )
                val waitMs = earliest - now

                if (waitMs <= 0L) {
                    lastRemoteRequestAt = now
                    return
                }

                Thread.sleep(waitMs.coerceAtMost(MAX_GLOBAL_BACKOFF_MS))
            }
        }
    }

    private fun pauseRemoteRequests(delayMs: Long) {
        synchronized(rateLock) {
            remoteBlockedUntil = max(
                remoteBlockedUntil,
                System.currentTimeMillis() + delayMs
            )
        }
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        destination.parentFile?.mkdirs()
        val temp = File(
            destination.parentFile,
            ".${destination.name}.${Thread.currentThread().id}.tmp"
        )
        temp.writeBytes(bytes)
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }

    private fun remoteUrl(source: String, z: Int, x: Int, y: Int): String {
        val key = URLEncoder.encode(
            BuildConfig.MAPTILER_KEY,
            StandardCharsets.UTF_8.toString()
        )
        return when (source) {
            SOURCE_MAP ->
                "https://api.maptiler.com/maps/streets-v4/$z/$x/$y.png?key=$key"
            SOURCE_SATELLITE ->
                "https://api.maptiler.com/maps/satellite-v4/$z/$x/$y.jpg?key=$key"
            SOURCE_TERRAIN ->
                "https://api.maptiler.com/maps/outdoor-v4/$z/$x/$y.png?key=$key"
            else -> error("Unknown map source: $source")
        }
    }

    private fun tileTemplate(source: String): String =
        "http://127.0.0.1:$port/tile/$source/{z}/{x}/{y}.${tileExtension(source)}"

    private fun mapStyle(): String =
        rasterStyle("Forest Navigator Map", SOURCE_MAP, 20)

    private fun satelliteStyle(): String =
        rasterStyle("Forest Navigator Satellite", SOURCE_SATELLITE, 20)

    private fun terrainStyle(): String =
        rasterStyle("Forest Navigator Relief", SOURCE_TERRAIN, 20)

    private fun combinedStyle(): String = """
        {
          "version": 8,
          "name": "Forest Navigator Satellite + Relief",
          "sources": {
            "$SOURCE_SATELLITE": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_SATELLITE)}"],
              "scheme": "xyz",
              "tileSize": 512,
              "minzoom": 0,
              "maxzoom": 20
            },
            "$SOURCE_TERRAIN": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_TERRAIN)}"],
              "scheme": "xyz",
              "tileSize": 512,
              "minzoom": 0,
              "maxzoom": 20
            }
          },
          "layers": [
            {
              "id": "$SOURCE_SATELLITE",
              "type": "raster",
              "source": "$SOURCE_SATELLITE"
            },
            {
              "id": "relief-overlay",
              "type": "raster",
              "source": "$SOURCE_TERRAIN",
              "paint": {
                "raster-opacity": 0.34,
                "raster-contrast": 0.12
              }
            }
          ]
        }
    """.trimIndent()

    private fun rasterStyle(
        name: String,
        source: String,
        maxZoom: Int
    ): String = """
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
        if (source == SOURCE_SATELLITE) "image/jpeg" else "image/png"

    private fun sendJson(client: Socket, body: String) =
        sendBinary(
            client,
            body.toByteArray(Charsets.UTF_8),
            "application/json; charset=utf-8"
        )

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

    private const val SOURCE_MAP = "map"
    private const val SOURCE_SATELLITE = "satellite"
    private const val SOURCE_TERRAIN = "terrain"
    private val VALID_SOURCES = setOf(
        SOURCE_MAP,
        SOURCE_SATELLITE,
        SOURCE_TERRAIN
    )

    private const val MIN_REMOTE_REQUEST_INTERVAL_MS = 55L
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 25_000
    private const val MAX_REMOTE_ATTEMPTS = 4
    private const val MAX_GLOBAL_BACKOFF_MS = 30_000L
    private const val MIN_TILE_BYTES = 128
}
