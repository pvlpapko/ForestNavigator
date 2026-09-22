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
 * Offline-only local tile server for the clean rebuild.
 *
 * Online MapLibre styles never use this server. This server is used only when
 * Android has no active network and when the foreground downloader stores tiles.
 * That separation prevents a downloader/proxy failure from turning online maps
 * into a blank/black screen.
 */
object LocalMapStyleServer {
    private data class SourceSpec(
        val id: String,
        val extension: String,
        val contentType: String,
        val maxZoom: Int,
        val tileSize: Int,
        val tmsRemote: Boolean,
        val minRequestIntervalMs: Long,
        val remoteUrl: (Int, Int, Int, String) -> String
    )

    private val started = AtomicBoolean(false)
    private lateinit var appContext: Context
    private lateinit var cacheRoot: File
    private lateinit var offlineRoot: File
    private var serverSocket: ServerSocket? = null

    @Volatile private var port: Int = 8765

    private val providerLocks = ConcurrentHashMap<String, Any>()
    private val providerNextAt = ConcurrentHashMap<String, Long>()
    private val providerBlockedUntil = ConcurrentHashMap<String, Long>()

    private val clientExecutor = ThreadPoolExecutor(
        8,
        8,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(192),
        { runnable ->
            Thread(runnable, "forest-offline-style-client").apply { isDaemon = true }
        }
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        appContext = context.applicationContext
        cacheRoot = File(appContext.filesDir, CACHE_ROOT).apply { mkdirs() }
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
            }, "forest-offline-style-server").apply {
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
        MapLayer.TERRAIN -> listOf(SOURCE_TOPO, SOURCE_DEM)
        MapLayer.SATELLITE_TERRAIN -> listOf(SOURCE_SATELLITE, SOURCE_DEM)
        MapLayer.CUSTOM -> emptyList()
    }

    fun maxDownloadZoom(source: String): Int = spec(source).maxZoom

    fun tileFile(regionDir: File, source: String, z: Int, x: Int, y: Int): File {
        val s = spec(source)
        return File(regionDir, "tiles/$source/$z/$x/$y.${s.extension}")
    }

    fun downloadTileTo(
        source: String,
        z: Int,
        x: Int,
        y: Int,
        destination: File
    ): Long {
        if (isValidTile(destination, source)) return destination.length()

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
        val sourceSpec = runCatching { spec(source) }.getOrNull()
        val z = parts[2].toIntOrNull()
        val x = parts[3].toIntOrNull()
        val yPart = parts[4]
        val y = yPart.substringBeforeLast('.').toIntOrNull()
        val ext = yPart.substringAfterLast('.', missingDelimiterValue = "")

        if (sourceSpec == null ||
            z == null || x == null || y == null ||
            ext != sourceSpec.extension
        ) {
            sendStatus(client, 400, "Bad Request")
            return
        }

        findCachedTile(source, z, x, y)?.let { cached ->
            sendBinary(client, cached.readBytes(), sourceSpec.contentType)
            return
        }

        // This path is primarily for already-downloaded offline tiles. If the
        // device comes online while this style is active, fetching a missing tile
        // is still safe and fills the isolated cache.
        runCatching {
            val bytes = fetchRemoteTile(source, z, x, y)
            writeAtomically(cacheTile(source, z, x, y), bytes)
            sendBinary(client, bytes, sourceSpec.contentType)
        }.onFailure { error ->
            val message = error.message.orEmpty()
            val status = when {
                message.contains("HTTP 404") -> 404
                message.contains("HTTP 429") -> 503
                else -> 502
            }
            sendStatus(client, status, message.ifBlank { "Tile unavailable" })
        }
    }

    private fun findCachedTile(source: String, z: Int, x: Int, y: Int): File? {
        val cached = cacheTile(source, z, x, y)
        if (isValidTile(cached, source)) return cached

        offlineRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.forEach { region ->
                val file = tileFile(region, source, z, x, y)
                if (isValidTile(file, source)) return file
            }

        return null
    }

    private fun cacheTile(source: String, z: Int, x: Int, y: Int): File {
        val s = spec(source)
        return File(cacheRoot, "$source/$z/$x/$y.${s.extension}")
    }

    private fun fetchRemoteTile(source: String, z: Int, x: Int, y: Int): ByteArray {
        val sourceSpec = spec(source)
        val apiKey = URLEncoder.encode(
            BuildConfig.MAPTILER_KEY,
            StandardCharsets.UTF_8.toString()
        )
        val remoteY = if (sourceSpec.tmsRemote) {
            ((1L shl z) - 1L - y.toLong()).toInt()
        } else {
            y
        }

        var lastError = "Не удалось получить тайл"

        for (attempt in 0 until MAX_REMOTE_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Загрузка отменена")
            }

            awaitProviderSlot(sourceSpec)

            val connection = URL(
                sourceSpec.remoteUrl(z, x, remoteY, apiKey)
            ).openConnection() as HttpURLConnection

            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "ForestNavigator/1.0 Android")
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
            connection.setRequestProperty("Accept-Encoding", "identity")

            try {
                val status = connection.responseCode
                if (status in 200..299) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    if (!looksValid(bytes, sourceSpec)) {
                        throw IOException("Сервер вернул повреждённый тайл")
                    }
                    providerBlockedUntil.remove(sourceSpec.id)
                    return bytes
                }

                if (status == 404) throw IllegalStateException("HTTP 404")

                lastError = "HTTP $status"
                if (status == 429 || status in 500..599) {
                    val retryAfterMs = connection.getHeaderField("Retry-After")
                        ?.toLongOrNull()
                        ?.times(1000L)
                    blockProvider(
                        sourceSpec.id,
                        retryAfterMs ?: retryBackoffMs(attempt)
                    )
                } else if (attempt + 1 < MAX_REMOTE_ATTEMPTS) {
                    sleepInterruptibly(
                        retryBackoffMs(attempt).coerceAtMost(4_000L)
                    )
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

    private fun awaitProviderSlot(source: SourceSpec) {
        val lock = providerLocks.getOrPut(source.id) { Any() }
        synchronized(lock) {
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Загрузка отменена")
                }

                val now = System.currentTimeMillis()
                val earliest = max(
                    providerNextAt[source.id] ?: 0L,
                    providerBlockedUntil[source.id] ?: 0L
                )
                val waitMs = earliest - now

                if (waitMs <= 0L) {
                    providerNextAt[source.id] =
                        now + source.minRequestIntervalMs
                    return
                }

                sleepInterruptibly(waitMs.coerceAtMost(1_000L))
            }
        }
    }

    private fun blockProvider(id: String, delayMs: Long) {
        val until = System.currentTimeMillis() +
            delayMs.coerceAtMost(MAX_PROVIDER_BACKOFF_MS)
        providerBlockedUntil.compute(id) { _, old ->
            max(old ?: 0L, until)
        }
    }

    private fun retryBackoffMs(attempt: Int): Long =
        (1_000L shl attempt.coerceAtMost(4))
            .coerceAtMost(MAX_PROVIDER_BACKOFF_MS)

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

    private fun isValidTile(file: File, source: String): Boolean {
        if (!file.isFile || file.length() < MIN_TILE_BYTES) return false
        return runCatching {
            val bytes = file.inputStream().use { input ->
                val header = ByteArray(16)
                val count = input.read(header)
                if (count <= 0) return@use ByteArray(0)
                header.copyOf(count)
            }
            looksValid(bytes, spec(source))
        }.getOrDefault(false)
    }

    private fun looksValid(bytes: ByteArray, source: SourceSpec): Boolean {
        if (bytes.size < 3) return false

        return when (source.extension) {
            "jpg" ->
                (bytes[0].toInt() and 0xFF) == 0xFF &&
                    (bytes[1].toInt() and 0xFF) == 0xD8 &&
                    (bytes[2].toInt() and 0xFF) == 0xFF

            "png" ->
                bytes.size >= 8 &&
                    (bytes[0].toInt() and 0xFF) == 0x89 &&
                    bytes[1].toInt() == 0x50 &&
                    bytes[2].toInt() == 0x4E &&
                    bytes[3].toInt() == 0x47

            "webp" ->
                bytes.size >= 12 &&
                    String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

            else -> false
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

    private fun tileTemplate(source: String): String {
        val s = spec(source)
        return "http://127.0.0.1:$port/tile/$source/{z}/{x}/{y}.${s.extension}"
    }

    private fun mapStyle(): String = """
        {
          "version": 8,
          "name": "Forest Navigator Offline Map",
          "sources": {
            "$SOURCE_MAP": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_MAP)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 20
            }
          },
          "layers": [
            {
              "id": "$SOURCE_MAP",
              "type": "raster",
              "source": "$SOURCE_MAP"
            }
          ]
        }
    """.trimIndent()

    private fun satelliteStyle(): String = """
        {
          "version": 8,
          "name": "Forest Navigator Offline Satellite",
          "sources": {
            "$SOURCE_SATELLITE": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_SATELLITE)}"],
              "scheme": "xyz",
              "tileSize": 512,
              "minzoom": 0,
              "maxzoom": 22
            }
          },
          "layers": [
            {
              "id": "$SOURCE_SATELLITE",
              "type": "raster",
              "source": "$SOURCE_SATELLITE",
              "paint": {
                "raster-opacity": 1.0,
                "raster-resampling": "linear"
              }
            }
          ]
        }
    """.trimIndent()

    private fun terrainStyle(): String = """
        {
          "version": 8,
          "name": "Forest Navigator Offline Terrain",
          "sources": {
            "$SOURCE_TOPO": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_TOPO)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 17
            },
            "$SOURCE_DEM": {
              "type": "raster-dem",
              "tiles": ["${tileTemplate(SOURCE_DEM)}"],
              "scheme": "xyz",
              "tileSize": 512,
              "minzoom": 0,
              "maxzoom": 14,
              "encoding": "mapbox"
            }
          },
          "layers": [
            {
              "id": "$SOURCE_TOPO",
              "type": "raster",
              "source": "$SOURCE_TOPO"
            },
            {
              "id": "terrain-hillshade",
              "type": "hillshade",
              "source": "$SOURCE_DEM",
              "paint": {
                "hillshade-exaggeration": 0.72,
                "hillshade-shadow-color": "#30281f",
                "hillshade-highlight-color": "#fff8e6",
                "hillshade-accent-color": "#75634a"
              }
            }
          ]
        }
    """.trimIndent()

    private fun combinedStyle(): String = """
        {
          "version": 8,
          "name": "Forest Navigator Offline Satellite + Relief",
          "sources": {
            "$SOURCE_SATELLITE": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_SATELLITE)}"],
              "scheme": "xyz",
              "tileSize": 512,
              "minzoom": 0,
              "maxzoom": 22
            },
            "$SOURCE_DEM": {
              "type": "raster-dem",
              "tiles": ["${tileTemplate(SOURCE_DEM)}"],
              "scheme": "xyz",
              "tileSize": 512,
              "minzoom": 0,
              "maxzoom": 14,
              "encoding": "mapbox"
            }
          },
          "layers": [
            {
              "id": "$SOURCE_SATELLITE",
              "type": "raster",
              "source": "$SOURCE_SATELLITE",
              "paint": {
                "raster-opacity": 1.0,
                "raster-resampling": "linear"
              }
            },
            {
              "id": "terrain-hillshade",
              "type": "hillshade",
              "source": "$SOURCE_DEM",
              "paint": {
                "hillshade-exaggeration": 0.64,
                "hillshade-shadow-color": "#261f18",
                "hillshade-highlight-color": "#fff8e9",
                "hillshade-accent-color": "#7a684d"
              }
            }
          ]
        }
    """.trimIndent()

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

    private fun spec(source: String): SourceSpec = when (source) {
        SOURCE_MAP -> SourceSpec(
            id = SOURCE_MAP,
            extension = "png",
            contentType = "image/png",
            maxZoom = 20,
            tileSize = 256,
            tmsRemote = false,
            minRequestIntervalMs = 75L,
            remoteUrl = { z, x, y, key ->
                "https://api.maptiler.com/maps/streets-v4/256/$z/$x/$y.png?key=$key"
            }
        )

        SOURCE_SATELLITE -> SourceSpec(
            id = SOURCE_SATELLITE,
            extension = "jpg",
            contentType = "image/jpeg",
            maxZoom = 22,
            tileSize = 512,
            tmsRemote = true,
            minRequestIntervalMs = 90L,
            remoteUrl = { z, x, y, key ->
                "https://api.maptiler.com/tiles/satellite-v2/$z/$x/$y.jpg?key=$key"
            }
        )

        SOURCE_TOPO -> SourceSpec(
            id = SOURCE_TOPO,
            extension = "png",
            contentType = "image/png",
            maxZoom = 17,
            tileSize = 256,
            tmsRemote = false,
            minRequestIntervalMs = 120L,
            remoteUrl = { z, x, y, _ ->
                val sub = when ((x + y) % 3) {
                    0 -> "a"
                    1 -> "b"
                    else -> "c"
                }
                "https://$sub.tile.opentopomap.org/$z/$x/$y.png"
            }
        )

        SOURCE_DEM -> SourceSpec(
            id = SOURCE_DEM,
            extension = "webp",
            contentType = "image/webp",
            maxZoom = 14,
            tileSize = 512,
            tmsRemote = true,
            minRequestIntervalMs = 90L,
            remoteUrl = { z, x, y, key ->
                "https://api.maptiler.com/tiles/terrain-rgb-v2/$z/$x/$y.webp?key=$key"
            }
        )

        else -> error("Unknown source: $source")
    }

    private const val SOURCE_MAP = "map"
    private const val SOURCE_SATELLITE = "satellite"
    private const val SOURCE_TOPO = "topo"
    private const val SOURCE_DEM = "dem"

    private const val CACHE_ROOT = "map_cache_clean_100"
    private const val OFFLINE_ROOT = "offline_regions_clean_100"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REMOTE_ATTEMPTS = 4
    private const val MAX_PROVIDER_BACKOFF_MS = 30_000L
    private const val MIN_TILE_BYTES = 128
}
