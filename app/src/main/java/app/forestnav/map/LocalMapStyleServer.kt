package app.forestnav.map

import android.content.Context
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Alternative map stack rebuilt from scratch.
 *
 * Normal map: Esri World Street Map
 * Satellite: Esri World Imagery (native requests capped at z17, overzoom above)
 * Relief: OpenTopoMap (native requests capped at z17, overzoom above)
 * Satellite + relief: Esri World Imagery + Esri World Hillshade
 *
 * Camera zoom can go beyond the native source zoom. MapLibre then overzooms
 * the last valid tile instead of requesting placeholder tiles.
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
        { runnable -> Thread(runnable, "forest-map-alt-proxy").apply { isDaemon = true } }
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        onlineCache = File(appContext.filesDir, "map_cache_alt_v1").apply { mkdirs() }
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
            }, "forest-map-alt-http").apply {
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
        MapLayer.TERRAIN -> listOf(SOURCE_TOPO)
        MapLayer.SATELLITE_TERRAIN -> listOf(SOURCE_SATELLITE, SOURCE_HILLSHADE)
        MapLayer.CUSTOM -> emptyList()
    }

    fun maxDownloadZoom(source: String): Int = when (source) {
        SOURCE_STREET -> STREET_NATIVE_MAX_ZOOM
        SOURCE_SATELLITE -> SATELLITE_NATIVE_MAX_ZOOM
        SOURCE_TOPO -> TOPO_NATIVE_MAX_ZOOM
        SOURCE_HILLSHADE -> HILLSHADE_NATIVE_MAX_ZOOM
        else -> 0
    }

    fun tileExtension(source: String): String = when (source) {
        SOURCE_TOPO -> "png"
        SOURCE_STREET, SOURCE_SATELLITE, SOURCE_HILLSHADE -> "jpg"
        else -> "bin"
    }

    fun tileFile(regionDir: File, source: String, z: Int, x: Int, y: Int): File =
        File(regionDir, "alt_v1/$source/$z/$x/$y.${tileExtension(source)}")

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

        val nativeMax = maxDownloadZoom(source)
        if (z > nativeMax) {
            sendStatus(client, 404, "Native tile zoom exceeded")
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

    private fun providerFor(source: String): Provider = when (source) {
        SOURCE_STREET -> Provider(
            id = "esri-street",
            host = "server.arcgisonline.com",
            url = { z, x, y ->
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/$z/$y/$x"
            },
            minIntervalMs = 20L
        )

        SOURCE_SATELLITE -> Provider(
            id = "esri-imagery",
            host = "server.arcgisonline.com",
            url = { z, x, y ->
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            },
            minIntervalMs = 20L
        )

        SOURCE_TOPO -> Provider(
            id = "opentopo",
            host = "tile.opentopomap.org",
            url = { z, x, y ->
                val sub = when ((x + y) % 3) {
                    0 -> "a"
                    1 -> "b"
                    else -> "c"
                }
                "https://$sub.tile.opentopomap.org/$z/$x/$y.png"
            },
            minIntervalMs = 70L
        )

        SOURCE_HILLSHADE -> Provider(
            id = "esri-hillshade",
            host = "server.arcgisonline.com",
            url = { z, x, y ->
                "https://server.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer/tile/$z/$y/$x"
            },
            minIntervalMs = 25L
        )

        else -> error("Unknown map source: $source")
    }

    private fun fetchRemoteTile(source: String, z: Int, x: Int, y: Int): ByteArray {
        val provider = providerFor(source)
        var lastError = "${provider.host}: неизвестная ошибка"

        repeat(MAX_ATTEMPTS) { attempt ->
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Загрузка отменена")
            }

            val blockedUntil = providerBlockedUntil[provider.id] ?: 0L
            val blockedFor = blockedUntil - System.currentTimeMillis()
            if (blockedFor > 0L) {
                Thread.sleep(blockedFor.coerceAtMost(10_000L))
            }

            awaitProviderSlot(provider)

            val connection = URL(provider.url(z, x, y)).openConnection() as HttpURLConnection
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
                    if (bytes.size < 128) throw IOException("Пустой тайл")
                    providerBlockedUntil.remove(provider.id)
                    return bytes
                }

                lastError = "${provider.host}: HTTP $status"
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
                lastError = "${provider.host}: timeout"
            } catch (e: IOException) {
                lastError = "${provider.host}: ${e.message ?: "ошибка сети"}"
            } finally {
                runCatching { connection.errorStream?.close() }
                connection.disconnect()
            }

            if (attempt + 1 < MAX_ATTEMPTS) {
                Thread.sleep((350L * (attempt + 1)).coerceAtMost(1_000L))
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

    private fun mapStyle(): String = rasterStyle(
        name = "Forest Navigator Streets",
        source = SOURCE_STREET,
        maxZoom = STREET_NATIVE_MAX_ZOOM,
        attribution = "Sources: Esri, HERE, Garmin, USGS, OpenStreetMap contributors"
    )

    private fun satelliteStyle(): String = rasterStyle(
        name = "Forest Navigator Imagery",
        source = SOURCE_SATELLITE,
        maxZoom = SATELLITE_NATIVE_MAX_ZOOM,
        attribution = "Source: Esri, Vantor, Earthstar Geographics and GIS User Community"
    )

    private fun terrainStyle(): String = rasterStyle(
        name = "Forest Navigator Topographic",
        source = SOURCE_TOPO,
        maxZoom = TOPO_NATIVE_MAX_ZOOM,
        attribution = "© OpenTopoMap (CC-BY-SA), © OpenStreetMap contributors, SRTM"
    )

    private fun combinedStyle(): String = """
        {
          "version": 8,
          "name": "Forest Navigator Imagery + Relief",
          "sources": {
            "$SOURCE_SATELLITE": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_SATELLITE)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": $SATELLITE_NATIVE_MAX_ZOOM,
              "attribution": "Source: Esri, Vantor, Earthstar Geographics and GIS User Community"
            },
            "$SOURCE_HILLSHADE": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_HILLSHADE)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": $HILLSHADE_NATIVE_MAX_ZOOM,
              "attribution": "Hillshade © Esri and elevation data contributors"
            }
          },
          "layers": [
            {
              "id": "imagery",
              "type": "raster",
              "source": "$SOURCE_SATELLITE"
            },
            {
              "id": "hillshade",
              "type": "raster",
              "source": "$SOURCE_HILLSHADE",
              "paint": {
                "raster-opacity": 0.20,
                "raster-contrast": 0.14,
                "raster-brightness-min": 0.08,
                "raster-brightness-max": 0.96
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
        if (source == SOURCE_TOPO) "image/png" else "image/jpeg"

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
    private const val SOURCE_TOPO = "topo"
    private const val SOURCE_HILLSHADE = "hillshade"

    private const val STREET_NATIVE_MAX_ZOOM = 17
    private const val SATELLITE_NATIVE_MAX_ZOOM = 17
    private const val TOPO_NATIVE_MAX_ZOOM = 17
    private const val HILLSHADE_NATIVE_MAX_ZOOM = 13

    private val VALID_SOURCES = setOf(
        SOURCE_STREET,
        SOURCE_SATELLITE,
        SOURCE_TOPO,
        SOURCE_HILLSHADE
    )

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_ATTEMPTS = 2
}
