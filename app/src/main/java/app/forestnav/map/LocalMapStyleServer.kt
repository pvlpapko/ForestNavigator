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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Clean map/cache server rebuilt from the final 0.9.3 map stack.
 *
 * Visual map definitions and providers intentionally match 0.9.3. Only the
 * transport/cache implementation is new: a fresh cache namespace prevents any
 * tiles from later experimental versions from being reused, and bounded retries
 * avoid poisoning the cache with failed/partial responses.
 */
object LocalMapStyleServer {
    private val started = AtomicBoolean(false)

    @Volatile private var port: Int = 8765

    private lateinit var onlineCache: File
    private lateinit var offlineRoot: File
    private var serverSocket: ServerSocket? = null

    private val providerLocks = ConcurrentHashMap<String, Any>()
    private val providerNextRequestAt = ConcurrentHashMap<String, Long>()
    private val providerBlockedUntil = ConcurrentHashMap<String, Long>()

    private val clientExecutor = ThreadPoolExecutor(
        8,
        8,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(192),
        { runnable ->
            Thread(runnable, "forest-map-http-client-clean").apply { isDaemon = true }
        }
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        onlineCache = File(appContext.filesDir, CACHE_DIR).apply { mkdirs() }
        offlineRoot = File(appContext.filesDir, OFFLINE_ROOT).apply { mkdirs() }

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
            }, "forest-map-http-server-clean").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            started.set(false)
            throw t
        }
    }

    fun offlineRoot(context: Context): File =
        File(context.filesDir, OFFLINE_ROOT).apply { mkdirs() }

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
        File(regionDir, "tiles/$source/$z/$x/$y.${tileExtension(source)}")

    fun downloadTileTo(
        source: String,
        z: Int,
        x: Int,
        y: Int,
        destination: File
    ): Long {
        if (isValidCachedTile(destination, source)) return destination.length()

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
            val status = when {
                message.contains("HTTP 404") -> 404
                message.contains("HTTP 429") -> 503
                else -> 502
            }
            sendStatus(client, status, message.ifBlank { "Tile fetch failed" })
        }
    }

    private fun findCachedTile(source: String, z: Int, x: Int, y: Int): File? {
        val online = onlineTile(source, z, x, y)
        if (isValidCachedTile(online, source)) return online

        offlineRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.forEach { region ->
                val file = tileFile(region, source, z, x, y)
                if (isValidCachedTile(file, source)) return file
            }

        return null
    }

    private fun onlineTile(source: String, z: Int, x: Int, y: Int): File =
        File(onlineCache, "$source/$z/$x/$y.${tileExtension(source)}")

    private fun isValidCachedTile(file: File, source: String): Boolean {
        if (!file.isFile || file.length() < MIN_TILE_BYTES) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(12)
                val count = input.read(header)
                count >= 3 && looksLikeImage(header, count, source)
            }
        }.getOrDefault(false)
    }

    private fun fetchRemoteTile(source: String, z: Int, x: Int, y: Int): ByteArray {
        val providerId = source
        var lastError = "Не удалось получить тайл"

        for (attempt in 0 until MAX_REMOTE_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Загрузка отменена")
            }

            awaitProviderSlot(providerId)

            val connection = URL(remoteUrl(source, z, x, y)).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "ForestNavigator/0.10 Android")
            connection.setRequestProperty("Accept", "image/*")
            connection.setRequestProperty("Accept-Encoding", "identity")

            try {
                val status = connection.responseCode
                if (status in 200..299) {
                    val data = connection.inputStream.use { it.readBytes() }
                    if (data.size < MIN_TILE_BYTES || !looksLikeImage(data, data.size, source)) {
                        throw IOException("Сервер вернул повреждённый тайл")
                    }
                    providerBlockedUntil.remove(providerId)
                    return data
                }

                if (status == 404) {
                    throw IllegalStateException("HTTP 404")
                }

                lastError = "HTTP $status"

                if (status == 429 || status in 500..599) {
                    val retryAfterMs = connection.getHeaderField("Retry-After")
                        ?.toLongOrNull()
                        ?.times(1000L)
                    val backoff = retryAfterMs ?: retryBackoffMs(attempt)
                    blockProvider(providerId, backoff)
                } else if (attempt + 1 < MAX_REMOTE_ATTEMPTS) {
                    sleepInterruptibly(retryBackoffMs(attempt).coerceAtMost(4_000L))
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: SocketTimeoutException) {
                lastError = "Timeout"
                if (attempt + 1 < MAX_REMOTE_ATTEMPTS) {
                    sleepInterruptibly(retryBackoffMs(attempt))
                }
            } catch (e: IOException) {
                lastError = e.message ?: "Ошибка сети"
                if (attempt + 1 < MAX_REMOTE_ATTEMPTS) {
                    sleepInterruptibly(retryBackoffMs(attempt))
                }
            } finally {
                runCatching { connection.errorStream?.close() }
                connection.disconnect()
            }
        }

        throw IllegalStateException(lastError)
    }

    private fun awaitProviderSlot(providerId: String) {
        val lock = providerLocks.getOrPut(providerId) { Any() }
        synchronized(lock) {
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Загрузка отменена")
                }

                val now = System.currentTimeMillis()
                val nextRequest = providerNextRequestAt[providerId] ?: 0L
                val blockedUntil = providerBlockedUntil[providerId] ?: 0L
                val earliest = max(nextRequest, blockedUntil)
                val waitMs = earliest - now

                if (waitMs <= 0L) {
                    providerNextRequestAt[providerId] = now + MIN_REQUEST_INTERVAL_MS
                    return
                }

                sleepInterruptibly(waitMs.coerceAtMost(1_000L))
            }
        }
    }

    private fun blockProvider(providerId: String, delayMs: Long) {
        val until = System.currentTimeMillis() + delayMs.coerceAtMost(MAX_PROVIDER_BACKOFF_MS)
        providerBlockedUntil.compute(providerId) { _, old ->
            max(old ?: 0L, until)
        }
    }

    private fun retryBackoffMs(attempt: Int): Long =
        (1_000L shl attempt.coerceAtMost(4)).coerceAtMost(MAX_PROVIDER_BACKOFF_MS)

    private fun sleepInterruptibly(durationMs: Long) {
        var remaining = durationMs
        while (remaining > 0L) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Загрузка отменена")
            }
            val slice = minOf(remaining, 500L)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    private fun looksLikeImage(bytes: ByteArray, count: Int, source: String): Boolean {
        if (source == SOURCE_SATELLITE) {
            return count >= 3 &&
                (bytes[0].toInt() and 0xFF) == 0xFF &&
                (bytes[1].toInt() and 0xFF) == 0xD8 &&
                (bytes[2].toInt() and 0xFF) == 0xFF
        }

        return count >= 8 &&
            (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1].toInt() == 0x50 &&
            bytes[2].toInt() == 0x4E &&
            bytes[3].toInt() == 0x47
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

    // These style definitions are intentionally unchanged from 0.9.3.
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
        if (source == SOURCE_SATELLITE) "image/jpeg" else "image/png"

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

    private const val CACHE_DIR = "map_tile_cache_clean_0100"
    private const val OFFLINE_ROOT = "offline_regions_clean_0100"

    private const val SOURCE_MAP = "map"
    private const val SOURCE_SATELLITE = "satellite"
    private const val SOURCE_TERRAIN = "terrain"
    private val VALID_SOURCES = setOf(SOURCE_MAP, SOURCE_SATELLITE, SOURCE_TERRAIN)

    private const val MIN_REQUEST_INTERVAL_MS = 75L
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 25_000
    private const val MAX_REMOTE_ATTEMPTS = 5
    private const val MAX_PROVIDER_BACKOFF_MS = 30_000L
    private const val MIN_TILE_BYTES = 128
}
