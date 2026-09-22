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

/**
 * Local MapTiler raster cache used for offline areas and as a network fallback.
 *
 * Online rendering normally uses MapTiler's native style.json directly.
 * Downloaded regions use the matching MapTiler raster maps so they remain
 * available without internet.
 */
object LocalMapStyleServer {
    private data class Provider(
        val id: String,
        val url: (Int, Int, Int) -> String,
        val minIntervalMs: Long
    )

    private val started = AtomicBoolean(false)
    private lateinit var onlineCache: File
    private lateinit var offlineRoot: File
    private var serverSocket: ServerSocket? = null

    @Volatile private var port: Int = 8765

    private val providerBlockedUntil = ConcurrentHashMap<String, Long>()
    private val providerLocks = ConcurrentHashMap<String, Any>()
    private val providerNextRequestAt = ConcurrentHashMap<String, Long>()

    private val clientExecutor = ThreadPoolExecutor(
        10,
        10,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(256),
        { runnable -> Thread(runnable, "forest-maptiler-proxy").apply { isDaemon = true } }
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        onlineCache = File(appContext.filesDir, "map_cache_maptiler_v1").apply { mkdirs() }
        offlineRoot = File(appContext.filesDir, "offline_regions").apply { mkdirs() }

        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val socket = runCatching { ServerSocket(port, 96, loopback) }
                .getOrElse { ServerSocket(0, 96, loopback) }
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
            }, "forest-maptiler-http").apply {
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
        MapLayer.MAP -> listOf(SOURCE_STREET)
        MapLayer.SATELLITE -> listOf(SOURCE_SATELLITE)
        MapLayer.TERRAIN -> listOf(SOURCE_OUTDOOR)
        MapLayer.SATELLITE_TERRAIN -> listOf(SOURCE_SATELLITE, SOURCE_OUTDOOR)
        MapLayer.CUSTOM -> emptyList()
    }

    fun maxDownloadZoom(source: String): Int = when (source) {
        SOURCE_STREET, SOURCE_SATELLITE, SOURCE_OUTDOOR -> 18
        else -> 0
    }

    fun tileExtension(source: String): String =
        if (source == SOURCE_SATELLITE) "jpg" else "png"

    fun tileFile(regionDir: File, source: String, z: Int, x: Int, y: Int): File =
        File(regionDir, "maptiler_v1/$source/$z/$x/$y.${tileExtension(source)}")

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
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
        val requestLine = reader.readLine().orEmpty()
        val path = requestLine.split(' ').getOrNull(1)
            ?.substringBefore('?')
            ?.trimStart('/')
            .orEmpty()

        while (reader.readLine()?.isNotEmpty() == true) Unit

        when {
            path == "style/map.json" -> sendJson(client, rasterStyle(
                "Forest Navigator Streets Offline",
                SOURCE_STREET,
                18,
                "© MapTiler © OpenStreetMap contributors"
            ))
            path == "style/satellite.json" -> sendJson(client, rasterStyle(
                "Forest Navigator Satellite Offline",
                SOURCE_SATELLITE,
                18,
                "Satellite imagery © MapTiler"
            ))
            path == "style/terrain.json" -> sendJson(client, rasterStyle(
                "Forest Navigator Outdoor Offline",
                SOURCE_OUTDOOR,
                18,
                "Outdoor map © MapTiler © OpenStreetMap contributors"
            ))
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

        if (source !in VALID_SOURCES || z == null || x == null || y == null ||
            ext != tileExtension(source)
        ) {
            sendStatus(client, 400, "Bad Request")
            return
        }

        if (z > maxDownloadZoom(source)) {
            sendStatus(client, 404, "Native offline zoom exceeded")
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
            sendStatus(client, 502, error.message ?: "Tile fetch failed")
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

    private fun providerFor(source: String): Provider {
        val key = URLEncoder.encode(
            BuildConfig.MAPTILER_KEY,
            StandardCharsets.UTF_8.toString()
        )

        return when (source) {
            SOURCE_STREET -> Provider(
                id = "maptiler-streets-v4",
                url = { z, x, y ->
                    "https://api.maptiler.com/maps/streets-v4/256/$z/$x/$y.png?key=$key"
                },
                minIntervalMs = 25L
            )

            SOURCE_SATELLITE -> Provider(
                id = "maptiler-satellite-v2",
                url = { z, x, y ->
                    "https://api.maptiler.com/tiles/satellite-v2/$z/$x/$y.jpg?key=$key"
                },
                minIntervalMs = 25L
            )

            SOURCE_OUTDOOR -> Provider(
                id = "maptiler-outdoor-v4",
                url = { z, x, y ->
                    "https://api.maptiler.com/maps/outdoor-v4/256/$z/$x/$y.png?key=$key"
                },
                minIntervalMs = 25L
            )

            else -> error("Unknown source: $source")
        }
    }

    private fun fetchRemoteTile(source: String, z: Int, x: Int, y: Int): ByteArray {
        val provider = providerFor(source)
        var lastError = "MapTiler: неизвестная ошибка"

        repeat(MAX_ATTEMPTS) { attempt ->
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Загрузка отменена")
            }

            val blockedUntil = providerBlockedUntil[provider.id] ?: 0L
            val blockedFor = blockedUntil - System.currentTimeMillis()
            if (blockedFor > 0L) {
                Thread.sleep(blockedFor.coerceAtMost(8_000L))
            }

            awaitProviderSlot(provider)

            val connection = URL(provider.url(z, x, y)).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "ForestNavigator/0.9.13 Android")
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
            connection.setRequestProperty("Accept-Encoding", "identity")

            try {
                val status = connection.responseCode
                if (status in 200..299) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    if (bytes.size < 128) throw IOException("Пустой тайл")
                    providerBlockedUntil.remove(provider.id)
                    return bytes
                }

                lastError = "MapTiler HTTP $status"
                if (status == 429) {
                    val retryAfterMs = connection.getHeaderField("Retry-After")
                        ?.toLongOrNull()
                        ?.times(1000L)
                        ?: 5_000L
                    providerBlockedUntil[provider.id] =
                        System.currentTimeMillis() + retryAfterMs.coerceAtMost(30_000L)
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: SocketTimeoutException) {
                lastError = "MapTiler timeout"
            } catch (e: IOException) {
                lastError = "MapTiler: ${e.message ?: "ошибка сети"}"
            } finally {
                runCatching { connection.errorStream?.close() }
                connection.disconnect()
            }

            if (attempt + 1 < MAX_ATTEMPTS) {
                Thread.sleep(300L * (attempt + 1))
            }
        }

        throw IOException(lastError)
    }

    private fun awaitProviderSlot(provider: Provider) {
        val lock = providerLocks.getOrPut(provider.id) { Any() }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val next = providerNextRequestAt[provider.id] ?: 0L
            val waitMs = next - now
            if (waitMs > 0L) Thread.sleep(waitMs)
            providerNextRequestAt[provider.id] =
                System.currentTimeMillis() + provider.minIntervalMs
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

    private fun tileTemplate(source: String): String =
        "http://127.0.0.1:$port/tile/$source/{z}/{x}/{y}.${tileExtension(source)}"

    private fun rasterStyle(
        name: String,
        source: String,
        maxZoom: Int,
        attribution: String
    ): String = """
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
              "maxzoom": $maxZoom,
              "attribution": "$attribution"
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

    private fun combinedStyle(): String = """
        {
          "version": 8,
          "name": "Forest Navigator Satellite + Outdoor Offline",
          "sources": {
            "$SOURCE_SATELLITE": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_SATELLITE)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 18
            },
            "$SOURCE_OUTDOOR": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_OUTDOOR)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 18
            }
          },
          "layers": [
            {
              "id": "satellite",
              "type": "raster",
              "source": "$SOURCE_SATELLITE"
            },
            {
              "id": "outdoor-overlay",
              "type": "raster",
              "source": "$SOURCE_OUTDOOR",
              "paint": {
                "raster-opacity": 0.24,
                "raster-contrast": 0.12
              }
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

    private const val SOURCE_STREET = "street"
    private const val SOURCE_SATELLITE = "satellite"
    private const val SOURCE_OUTDOOR = "outdoor"
    private val VALID_SOURCES = setOf(SOURCE_STREET, SOURCE_SATELLITE, SOURCE_OUTDOOR)

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_ATTEMPTS = 2
}
