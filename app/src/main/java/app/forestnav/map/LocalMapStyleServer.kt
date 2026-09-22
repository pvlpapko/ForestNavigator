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
 * Clean raster map stack.
 *
 * All built-in layers use the same local cache/proxy. The proxy prefers widely
 * available raster providers and falls back to MapTiler when a primary host is
 * unavailable. Online rendering and offline downloads therefore use identical
 * tile coordinates and identical cached files.
 */
object LocalMapStyleServer {
    private data class Provider(
        val id: String,
        val host: String,
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
        { runnable ->
            Thread(runnable, "forest-map-proxy").apply { isDaemon = true }
        }
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        onlineCache = File(appContext.filesDir, "map_cache_detail_v3").apply { mkdirs() }
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
            }, "forest-map-http").apply {
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
        MapLayer.TERRAIN -> listOf(SOURCE_TOPO, SOURCE_HILLSHADE)
        MapLayer.SATELLITE_TERRAIN -> listOf(SOURCE_SATELLITE, SOURCE_HILLSHADE)
        MapLayer.CUSTOM -> emptyList()
    }

    fun tileExtension(source: String): String = when (source) {
        SOURCE_MAP, SOURCE_TOPO -> "png"
        SOURCE_SATELLITE, SOURCE_HILLSHADE -> "jpg"
        else -> "bin"
    }

    fun tileFile(regionDir: File, source: String, z: Int, x: Int, y: Int): File =
        File(regionDir, "detail_v3/$source/$z/$x/$y.${tileExtension(source)}")

    fun downloadTileTo(
        source: String,
        z: Int,
        x: Int,
        y: Int,
        destination: File
    ): Long {
        if (destination.isFile && destination.length() > 0L) {
            return destination.length()
        }

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

        if (source !in VALID_SOURCES || z == null || x == null || y == null ||
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

    private fun fetchRemoteTile(source: String, z: Int, x: Int, y: Int): ByteArray {
        var lastError = "Нет доступного сервера тайлов"

        for (provider in providersFor(source, z, x, y)) {
            val blockedUntil = providerBlockedUntil[provider.id] ?: 0L
            if (blockedUntil > System.currentTimeMillis()) continue

            repeat(ATTEMPTS_PER_PROVIDER) { attempt ->
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Загрузка отменена")
                }

                awaitProviderSlot(provider)
                val connection = URL(provider.url(z, x, y)).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty(
                    "User-Agent",
                    "ForestNavigator/0.9.7 Android (offline-capable map client)"
                )
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                connection.setRequestProperty("Accept-Encoding", "identity")

                try {
                    val status = connection.responseCode
                    if (status in 200..299) {
                        val bytes = connection.inputStream.use { it.readBytes() }
                        if (bytes.size < 128) throw IOException("Пустой тайл")
                        providerBlockedUntil.remove(provider.id)
                        return bytes
                    }

                    lastError = "${provider.host}: HTTP $status"
                    if (status == 429) {
                        val retryAfter = connection.getHeaderField("Retry-After")
                            ?.toLongOrNull()
                            ?.times(1000L)
                            ?: 15_000L
                        providerBlockedUntil[provider.id] =
                            System.currentTimeMillis() + retryAfter.coerceAtMost(60_000L)
                        return@repeat
                    }

                    if (status in 400..499) {
                        providerBlockedUntil[provider.id] =
                            System.currentTimeMillis() + 60_000L
                        return@repeat
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: SocketTimeoutException) {
                    lastError = "${provider.host}: timeout"
                    if (attempt + 1 == ATTEMPTS_PER_PROVIDER) {
                        providerBlockedUntil[provider.id] =
                            System.currentTimeMillis() + PROVIDER_FAILURE_COOLDOWN_MS
                    }
                } catch (e: IOException) {
                    lastError = "${provider.host}: ${e.message ?: "ошибка сети"}"
                    if (attempt + 1 == ATTEMPTS_PER_PROVIDER) {
                        providerBlockedUntil[provider.id] =
                            System.currentTimeMillis() + PROVIDER_FAILURE_COOLDOWN_MS
                    }
                } finally {
                    runCatching { connection.errorStream?.close() }
                }
            }
        }

        throw IOException(lastError)
    }

    private fun providersFor(source: String, z: Int, x: Int, y: Int): List<Provider> {
        val encodedKey = URLEncoder.encode(
            BuildConfig.MAPTILER_KEY,
            StandardCharsets.UTF_8.toString()
        )

        return when (source) {
            SOURCE_MAP -> listOf(
                Provider(
                    id = "esri-street",
                    host = "server.arcgisonline.com",
                    url = { zz, xx, yy ->
                        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/$zz/$yy/$xx"
                    },
                    minIntervalMs = 25L
                ),
                Provider(
                    id = "maptiler-map",
                    host = "api.maptiler.com",
                    url = { zz, xx, yy ->
                        "https://api.maptiler.com/maps/streets-v4/256/$zz/$xx/$yy.png?key=$encodedKey"
                    },
                    minIntervalMs = 25L
                ),
                Provider(
                    id = "osm",
                    host = "tile.openstreetmap.org",
                    url = { zz, xx, yy ->
                        "https://tile.openstreetmap.org/$zz/$xx/$yy.png"
                    },
                    minIntervalMs = 100L
                )
            )

            SOURCE_SATELLITE -> listOf(
                Provider(
                    id = "maptiler-satellite",
                    host = "api.maptiler.com",
                    url = { zz, xx, yy ->
                        "https://api.maptiler.com/maps/satellite-v4/256/$zz/$xx/$yy.jpg?key=$encodedKey"
                    },
                    minIntervalMs = 20L
                ),
                Provider(
                    id = "esri-imagery",
                    host = "server.arcgisonline.com",
                    url = { zz, xx, yy ->
                        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$zz/$yy/$xx"
                    },
                    minIntervalMs = 25L
                )
            )

            SOURCE_TOPO -> listOf(
                Provider(
                    id = "opentopo",
                    host = "tile.opentopomap.org",
                    url = { zz, xx, yy ->
                        val sub = when ((xx + yy) % 3) {
                            0 -> "a"
                            1 -> "b"
                            else -> "c"
                        }
                        "https://$sub.tile.opentopomap.org/$zz/$xx/$yy.png"
                    },
                    minIntervalMs = 90L
                ),
                Provider(
                    id = "esri-topo",
                    host = "server.arcgisonline.com",
                    url = { zz, xx, yy ->
                        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/$zz/$yy/$xx"
                    },
                    minIntervalMs = 25L
                )
            )

            SOURCE_HILLSHADE -> listOf(
                Provider(
                    id = "esri-hillshade",
                    host = "server.arcgisonline.com",
                    url = { zz, xx, yy ->
                        "https://server.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer/tile/$zz/$yy/$xx"
                    },
                    minIntervalMs = 25L
                )
            )

            else -> emptyList()
        }
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

    private fun mapStyle(): String = rasterStyle(
        name = "Forest Navigator Map",
        source = SOURCE_MAP,
        maxZoom = 19,
        attribution = "Tiles © Esri; fallback data © OpenStreetMap contributors"
    )

    private fun satelliteStyle(): String = rasterStyle(
        name = "Forest Navigator Satellite",
        source = SOURCE_SATELLITE,
        maxZoom = 22,
        attribution = "Satellite imagery © MapTiler; fallback © Esri and contributors"
    )

    private fun terrainStyle(): String = """
        {
          "version": 8,
          "name": "Forest Navigator Relief",
          "sources": {
            "$SOURCE_TOPO": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_TOPO)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 19,
              "attribution": "© OpenTopoMap (CC-BY-SA), © OpenStreetMap contributors, SRTM; fallback © Esri"
            },
            "$SOURCE_HILLSHADE": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_HILLSHADE)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 13,
              "attribution": "Hillshade © Esri and elevation data contributors"
            }
          },
          "layers": [
            {
              "id": "topographic-base",
              "type": "raster",
              "source": "$SOURCE_TOPO"
            },
            {
              "id": "hillshade-overlay",
              "type": "raster",
              "source": "$SOURCE_HILLSHADE",
              "paint": {
                "raster-opacity": 0.42,
                "raster-contrast": 0.18,
                "raster-brightness-min": 0.10,
                "raster-brightness-max": 0.92
              }
            }
          ]
        }
    """.trimIndent()

    private fun combinedStyle(): String = """
        {
          "version": 8,
          "name": "Forest Navigator Satellite + Relief",
          "sources": {
            "$SOURCE_SATELLITE": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_SATELLITE)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 22,
              "attribution": "Satellite imagery © MapTiler; fallback © Esri and contributors"
            },
            "$SOURCE_HILLSHADE": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_HILLSHADE)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 13,
              "attribution": "Hillshade © Esri and elevation data contributors"
            }
          },
          "layers": [
            {
              "id": "$SOURCE_SATELLITE",
              "type": "raster",
              "source": "$SOURCE_SATELLITE"
            },
            {
              "id": "hillshade-overlay",
              "type": "raster",
              "source": "$SOURCE_HILLSHADE",
              "paint": {
                "raster-opacity": 0.24,
                "raster-contrast": 0.16,
                "raster-brightness-min": 0.12,
                "raster-brightness-max": 0.95
              }
            }
          ]
        }
    """.trimIndent()

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

    private fun contentType(source: String): String =
        if (source == SOURCE_MAP || source == SOURCE_TOPO) "image/png" else "image/jpeg"

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
            append("Cache-Control: public, max-age=86400\r\n")
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

    private const val SOURCE_MAP = "map"
    private const val SOURCE_SATELLITE = "satellite"
    private const val SOURCE_TOPO = "topo"
    private const val SOURCE_HILLSHADE = "hillshade"
    private val VALID_SOURCES = setOf(
        SOURCE_MAP,
        SOURCE_SATELLITE,
        SOURCE_TOPO,
        SOURCE_HILLSHADE
    )

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val ATTEMPTS_PER_PROVIDER = 2
    private const val PROVIDER_FAILURE_COOLDOWN_MS = 45_000L
}
