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
 * Local offline server for the clean ArcGIS provider stack.
 *
 * Online rendering does not use this server. Downloaded areas and the explicit
 * offline mode use exactly the same ArcGIS cached tile services.
 */
object LocalMapStyleServer {
    private data class SourceSpec(
        val id: String,
        val extension: String,
        val contentType: String,
        val maxZoom: Int,
        val url: (Int, Int, Int) -> String,
        val minIntervalMs: Long = 45L
    )

    private val started = AtomicBoolean(false)
    private lateinit var cacheRoot: File
    private lateinit var offlineRoot: File
    private var serverSocket: ServerSocket? = null

    @Volatile private var port: Int = 8765

    private val nextRequestAt = ConcurrentHashMap<String, Long>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()
    private val sourceLocks = ConcurrentHashMap<String, Any>()

    private val clientExecutor = ThreadPoolExecutor(
        8,
        8,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(256),
        { runnable ->
            Thread(runnable, "forest-esri-local-http").apply { isDaemon = true }
        }
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        cacheRoot = File(appContext.filesDir, CACHE_DIR).apply { mkdirs() }
        offlineRoot = File(appContext.filesDir, OFFLINE_DIR).apply { mkdirs() }

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
            }, "forest-esri-local-server").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            started.set(false)
            throw t
        }
    }

    fun offlineRoot(context: Context): File =
        File(context.filesDir, OFFLINE_DIR).apply { mkdirs() }

    fun mapUrl(): String = baseUrl("style/map.json")
    fun satelliteUrl(): String = baseUrl("style/satellite.json")
    fun terrainUrl(): String = baseUrl("style/terrain.json")
    fun combinedUrl(): String = baseUrl("style/combined.json")

    fun sourcesFor(layer: MapLayer): List<String> = when (layer) {
        MapLayer.MAP -> listOf(SOURCE_STREET)
        MapLayer.SATELLITE -> listOf(SOURCE_IMAGERY)
        MapLayer.TERRAIN -> listOf(SOURCE_TOPO)
        MapLayer.SATELLITE_TERRAIN ->
            listOf(SOURCE_IMAGERY, SOURCE_HILLSHADE, SOURCE_TRANSPORT, SOURCE_BOUNDARIES)
        MapLayer.CUSTOM -> emptyList()
    }

    fun maxDownloadZoom(source: String): Int = spec(source).maxZoom

    fun tileFile(regionDir: File, source: String, z: Int, x: Int, y: Int): File {
        val s = spec(source)
        return File(regionDir, "esri/$source/$z/$x/$y.${s.extension}")
    }

    fun downloadTileTo(
        source: String,
        z: Int,
        x: Int,
        y: Int,
        destination: File
    ): Long {
        if (isValid(destination)) return destination.length()

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
            path == "style/map.json" -> sendJson(client, localSingleStyle(
                "Esri World Street Map Offline", SOURCE_STREET
            ))
            path == "style/satellite.json" -> sendJson(client, localSingleStyle(
                "Esri World Imagery Offline", SOURCE_IMAGERY
            ))
            path == "style/terrain.json" -> sendJson(client, localSingleStyle(
                "Esri World Topographic Map Offline", SOURCE_TOPO
            ))
            path == "style/combined.json" -> sendJson(client, localCombinedStyle())
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

        runCatching {
            val bytes = fetchRemoteTile(source, z, x, y)
            writeAtomically(cacheTile(source, z, x, y), bytes)
            sendBinary(client, bytes, sourceSpec.contentType)
        }.onFailure { error ->
            val message = error.message.orEmpty()
            val status = when {
                message.contains("404") -> 404
                message.contains("429") -> 503
                else -> 502
            }
            sendStatus(client, status, message.ifBlank { "Tile unavailable" })
        }
    }

    private fun findCachedTile(source: String, z: Int, x: Int, y: Int): File? {
        val cached = cacheTile(source, z, x, y)
        if (isValid(cached)) return cached

        offlineRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.forEach { region ->
                val file = tileFile(region, source, z, x, y)
                if (isValid(file)) return file
            }

        return null
    }

    private fun cacheTile(source: String, z: Int, x: Int, y: Int): File {
        val s = spec(source)
        return File(cacheRoot, "$source/$z/$x/$y.${s.extension}")
    }

    private fun isValid(file: File): Boolean =
        file.isFile && file.length() >= MIN_TILE_BYTES

    private fun fetchRemoteTile(source: String, z: Int, x: Int, y: Int): ByteArray {
        val s = spec(source)
        var lastError = "ArcGIS: не удалось получить тайл"

        for (attempt in 0 until MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Загрузка отменена")
            }

            awaitSourceSlot(s)

            val connection = URL(s.url(z, x, y)).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "ForestNavigator/1.1 Android")
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
            connection.setRequestProperty("Accept-Encoding", "identity")

            try {
                val status = connection.responseCode
                if (status in 200..299) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    if (bytes.size < MIN_TILE_BYTES) {
                        throw IOException("ArcGIS вернул пустой тайл")
                    }
                    blockedUntil.remove(source)
                    return bytes
                }

                if (status == 404) {
                    throw IOException("ArcGIS HTTP 404")
                }

                lastError = "ArcGIS HTTP $status"
                if (status == 429 || status in 500..599) {
                    val retryAfterMs = connection.getHeaderField("Retry-After")
                        ?.toLongOrNull()
                        ?.times(1000L)
                        ?: retryDelay(attempt)
                    blockSource(source, retryAfterMs)
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: SocketTimeoutException) {
                lastError = "ArcGIS timeout"
            } catch (e: IOException) {
                lastError = e.message ?: "ArcGIS: ошибка сети"
            } finally {
                runCatching { connection.errorStream?.close() }
                connection.disconnect()
            }

            if (attempt + 1 < MAX_ATTEMPTS) {
                sleepInterruptibly(retryDelay(attempt))
            }
        }

        throw IOException(lastError)
    }

    private fun awaitSourceSlot(source: SourceSpec) {
        val lock = sourceLocks.getOrPut(source.id) { Any() }
        synchronized(lock) {
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Загрузка отменена")
                }

                val now = System.currentTimeMillis()
                val earliest = max(
                    nextRequestAt[source.id] ?: 0L,
                    blockedUntil[source.id] ?: 0L
                )
                val waitMs = earliest - now

                if (waitMs <= 0L) {
                    nextRequestAt[source.id] = now + source.minIntervalMs
                    return
                }

                sleepInterruptibly(waitMs.coerceAtMost(1_000L))
            }
        }
    }

    private fun blockSource(source: String, delayMs: Long) {
        val until = System.currentTimeMillis() + delayMs.coerceAtMost(MAX_BACKOFF_MS)
        blockedUntil.compute(source) { _, old -> max(old ?: 0L, until) }
    }

    private fun retryDelay(attempt: Int): Long =
        (800L * (1L shl attempt.coerceAtMost(5))).coerceAtMost(MAX_BACKOFF_MS)

    private fun sleepInterruptibly(durationMs: Long) {
        var remaining = durationMs
        while (remaining > 0L) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Загрузка отменена")
            }
            val part = minOf(remaining, 500L)
            Thread.sleep(part)
            remaining -= part
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

    private fun localSingleStyle(name: String, source: String): String {
        val s = spec(source)
        return """
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
                  "maxzoom": ${s.maxZoom}
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
    }

    private fun localCombinedStyle(): String = """
        {
          "version": 8,
          "name": "Esri Imagery + Relief Offline",
          "sources": {
            "$SOURCE_IMAGERY": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_IMAGERY)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 23
            },
            "$SOURCE_HILLSHADE": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_HILLSHADE)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 23
            },
            "$SOURCE_TRANSPORT": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_TRANSPORT)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 23
            },
            "$SOURCE_BOUNDARIES": {
              "type": "raster",
              "tiles": ["${tileTemplate(SOURCE_BOUNDARIES)}"],
              "scheme": "xyz",
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 23
            }
          },
          "layers": [
            {
              "id": "imagery",
              "type": "raster",
              "source": "$SOURCE_IMAGERY"
            },
            {
              "id": "hillshade",
              "type": "raster",
              "source": "$SOURCE_HILLSHADE",
              "paint": {
                "raster-opacity": 0.30,
                "raster-contrast": 0.16,
                "raster-saturation": -0.18
              }
            },
            {
              "id": "transport",
              "type": "raster",
              "source": "$SOURCE_TRANSPORT",
              "paint": {
                "raster-opacity": 0.86
              }
            },
            {
              "id": "boundaries",
              "type": "raster",
              "source": "$SOURCE_BOUNDARIES",
              "paint": {
                "raster-opacity": 0.94
              }
            }
          ]
        }
    """.trimIndent()

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

    private fun spec(source: String): SourceSpec = when (source) {
        SOURCE_STREET -> SourceSpec(
            id = SOURCE_STREET,
            extension = "jpg",
            contentType = "image/jpeg",
            maxZoom = 23,
            url = { z, x, y ->
                "$BASE/World_Street_Map/MapServer/tile/$z/$y/$x"
            }
        )

        SOURCE_IMAGERY -> SourceSpec(
            id = SOURCE_IMAGERY,
            extension = "jpg",
            contentType = "image/jpeg",
            maxZoom = 23,
            url = { z, x, y ->
                "$BASE/World_Imagery/MapServer/tile/$z/$y/$x"
            }
        )

        SOURCE_TOPO -> SourceSpec(
            id = SOURCE_TOPO,
            extension = "jpg",
            contentType = "image/jpeg",
            maxZoom = 23,
            url = { z, x, y ->
                "$BASE/World_Topo_Map/MapServer/tile/$z/$y/$x"
            }
        )

        SOURCE_HILLSHADE -> SourceSpec(
            id = SOURCE_HILLSHADE,
            extension = "jpg",
            contentType = "image/jpeg",
            maxZoom = 23,
            url = { z, x, y ->
                "$BASE/Elevation/World_Hillshade/MapServer/tile/$z/$y/$x"
            }
        )

        SOURCE_BOUNDARIES -> SourceSpec(
            id = SOURCE_BOUNDARIES,
            extension = "png",
            contentType = "image/png",
            maxZoom = 23,
            url = { z, x, y ->
                "$BASE/Reference/World_Boundaries_and_Places/MapServer/tile/$z/$y/$x"
            }
        )

        SOURCE_TRANSPORT -> SourceSpec(
            id = SOURCE_TRANSPORT,
            extension = "png",
            contentType = "image/png",
            maxZoom = 23,
            url = { z, x, y ->
                "$BASE/Reference/World_Transportation/MapServer/tile/$z/$y/$x"
            }
        )

        else -> error("Unknown source: $source")
    }

    private const val BASE =
        "https://server.arcgisonline.com/ArcGIS/rest/services"

    private const val SOURCE_STREET = "street"
    private const val SOURCE_IMAGERY = "imagery"
    private const val SOURCE_TOPO = "topo"
    private const val SOURCE_HILLSHADE = "hillshade"
    private const val SOURCE_BOUNDARIES = "boundaries"
    private const val SOURCE_TRANSPORT = "transport"

    private const val CACHE_DIR = "map_cache_esri_v1"
    private const val OFFLINE_DIR = "offline_regions_esri_v1"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 25_000
    private const val MAX_ATTEMPTS = 5
    private const val MAX_BACKOFF_MS = 30_000L
    private const val MIN_TILE_BYTES = 128
}
