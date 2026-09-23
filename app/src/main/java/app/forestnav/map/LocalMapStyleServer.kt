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
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

object LocalMapStyleServer {
    class PermanentTileException(message: String) : IOException(message)
    class FatalTileException(message: String) : IOException(message)

    private val started = AtomicBoolean(false)
    private lateinit var appContext: Context
    private lateinit var cacheRoot: File
    private lateinit var offlineRoot: File
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var port: Int = 8765

    private val nextAt = ConcurrentHashMap<String, Long>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val networkSlots = Semaphore(12, true)

    private val clientExecutor = ThreadPoolExecutor(
        8,
        8,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(256),
        { r -> Thread(r, "forest-3d-local-http").apply { isDaemon = true } }
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        appContext = context.applicationContext
        cacheRoot = File(appContext.filesDir, CACHE_DIR).apply { mkdirs() }
        offlineRoot = File(appContext.filesDir, OFFLINE_DIR).apply { mkdirs() }

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
                        client.use { runCatching { serve(it) } }
                    }
                } catch (_: RejectedExecutionException) {
                    runCatching { client.close() }
                }
            }
        }, "forest-3d-local-server").apply {
            isDaemon = true
            start()
        }
    }

    fun offlineRoot(context: Context): File =
        File(context.filesDir, OFFLINE_DIR).apply { mkdirs() }

    fun webMapUrl(): String = baseUrl("web/map3d.html")

    // Kept for compatibility with older settings/state. The UI now exposes only 3D.
    fun mapUrl(): String = baseUrl("style/3d.json")
    fun satelliteUrl(): String = baseUrl("style/3d.json")
    fun reliefUrl(): String = baseUrl("style/3d.json")
    fun combinedUrl(): String = baseUrl("style/3d.json")

    fun threeDStyleJson(reliefOverlay: Boolean = true): String =
        styleJson(reliefOverlay)

    fun sourcesFor(layer: MapLayer): List<String> = when (layer) {
        MapLayer.THREE_D,
        MapLayer.THREE_D_TERRAIN -> listOf(
            OpenMapSource.VERSATILES_SATELLITE.id,
            OpenMapSource.MAPTERHORN_DEM.id
        )

        // Old jobs remain readable after updating, but all new downloads use 3D.
        MapLayer.MAP,
        MapLayer.SATELLITE,
        MapLayer.RELIEF,
        MapLayer.SATELLITE_TERRAIN -> listOf(
            OpenMapSource.VERSATILES_SATELLITE.id,
            OpenMapSource.MAPTERHORN_DEM.id
        )

        MapLayer.CUSTOM -> emptyList()
    }

    fun maxDownloadZoom(source: String): Int =
        OpenMapProvider.sourceById(source).maxZoom

    fun tileFile(regionDir: File, source: String, z: Int, x: Int, y: Int): File {
        val spec = OpenMapProvider.sourceById(source)
        return File(regionDir, "terrain3d/$source/$z/$x/$y.${spec.extension}")
    }

    fun downloadTileTo(
        source: String,
        z: Int,
        x: Int,
        y: Int,
        destination: File
    ): Long {
        val spec = OpenMapProvider.sourceById(source)
        if (!spec.downloadable) {
            throw PermanentTileException("Источник используется только онлайн")
        }

        if (valid(destination)) return destination.length()

        findCached(source, z, x, y)?.let { cached ->
            destination.parentFile?.mkdirs()
            if (cached.absolutePath != destination.absolutePath) {
                cached.copyTo(destination, overwrite = true)
            }
            return destination.length()
        }

        val bytes = fetch(source, z, x, y)
        writeAtomic(destination, bytes)
        return bytes.size.toLong()
    }

    private fun baseUrl(path: String): String {
        check(started.get()) { "LocalMapStyleServer not started" }
        return "http://127.0.0.1:$port/$path"
    }

    private fun serve(client: Socket) {
        client.soTimeout = 20_000
        val reader = BufferedReader(
            InputStreamReader(client.getInputStream(), Charsets.US_ASCII)
        )
        val requestLine = reader.readLine().orEmpty()
        val path = requestLine
            .split(' ')
            .getOrNull(1)
            ?.substringBefore('?')
            ?.trimStart('/')
            .orEmpty()

        while (reader.readLine()?.isNotEmpty() == true) Unit

        when {
            path == "web/map3d.html" ->
                sendAsset(client, "map3d.html", "text/html; charset=utf-8")

            path == "web/maplibre-gl.js" ->
                sendAsset(client, "maplibre-gl.js", "application/javascript; charset=utf-8")

            path == "web/maplibre-gl.css" ->
                sendAsset(client, "maplibre-gl.css", "text/css; charset=utf-8")

            path == "style/3d.json" ->
                sendJson(client, styleJson(reliefOverlay = true))

            path.startsWith("tile/") ->
                serveTile(client, path)

            else ->
                sendStatus(client, 404, "Not Found")
        }
    }

    private fun sendAsset(client: Socket, assetName: String, contentType: String) {
        runCatching {
            val bytes = appContext.assets.open(assetName).use { it.readBytes() }
            sendBinary(client, bytes, contentType)
        }.onFailure {
            sendStatus(client, 404, "Asset not found")
        }
    }

    private fun serveTile(client: Socket, path: String) {
        val parts = path.split('/')
        if (parts.size != 5) {
            return sendStatus(client, 400, "Bad Request")
        }

        val source = parts[1]
        val spec = runCatching { OpenMapProvider.sourceById(source) }.getOrNull()
            ?: return sendStatus(client, 400, "Bad Request")
        val z = parts[2].toIntOrNull()
            ?: return sendStatus(client, 400, "Bad Request")
        val x = parts[3].toIntOrNull()
            ?: return sendStatus(client, 400, "Bad Request")
        val yPart = parts[4]
        val y = yPart.substringBeforeLast('.').toIntOrNull()
            ?: return sendStatus(client, 400, "Bad Request")

        if (yPart.substringAfterLast('.') != spec.extension) {
            return sendStatus(client, 400, "Bad Request")
        }

        findCached(source, z, x, y)?.let {
            return sendBinary(client, it.readBytes(), spec.contentType)
        }

        try {
            val bytes = fetch(source, z, x, y)
            writeAtomic(cacheFile(source, z, x, y), bytes)
            sendBinary(client, bytes, spec.contentType)
        } catch (_: PermanentTileException) {
            // Important for OpenAerialMap: no high-resolution image at this tile.
            // Returning 404 leaves the lower VersaTiles satellite layer visible.
            sendStatus(client, 404, "Tile unavailable")
        } catch (t: Throwable) {
            sendStatus(client, 502, t.message ?: "Tile unavailable")
        }
    }

    private fun findCached(source: String, z: Int, x: Int, y: Int): File? {
        val runtime = cacheFile(source, z, x, y)
        if (valid(runtime)) return runtime

        offlineRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                val file = tileFile(dir, source, z, x, y)
                if (valid(file)) return file
            }

        return null
    }

    private fun cacheFile(source: String, z: Int, x: Int, y: Int): File {
        val spec = OpenMapProvider.sourceById(source)
        return File(cacheRoot, "$source/$z/$x/$y.${spec.extension}")
    }

    private fun valid(file: File): Boolean =
        file.isFile && file.length() >= MIN_VALID_TILE_BYTES

    private fun fetch(sourceId: String, z: Int, x: Int, y: Int): ByteArray {
        val source = OpenMapProvider.sourceById(sourceId)
        var last = "Tile service error"

        repeat(MAX_ATTEMPTS) { attempt ->
            awaitSlot(source)
            networkSlots.acquire()

            var connection: HttpURLConnection? = null
            try {
                connection = URL(OpenMapProvider.tileUrl(source, z, x, y))
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 18_000
                connection.instanceFollowRedirects = true
                connection.useCaches = true
                connection.setRequestProperty(
                    "User-Agent",
                    "ForestNavigator/1.3 Android"
                )
                connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")

                val status = connection.responseCode
                if (status in 200..299) {
                    val bytes = connection.inputStream
                        .buffered()
                        .use { it.readBytes() }

                    if (bytes.size < MIN_VALID_TILE_BYTES) {
                        throw IOException("Provider returned an empty tile")
                    }

                    blockedUntil.remove(source.id)
                    return bytes
                }

                when (status) {
                    400, 401, 403, 422 ->
                        throw FatalTileException(
                            "Источник отклонил запрос: HTTP $status"
                        )

                    404, 410 ->
                        throw PermanentTileException(
                            "Тайл отсутствует у источника: HTTP $status"
                        )

                    429 -> {
                        last = "Источник ограничил частоту запросов: HTTP 429"
                        val delay = connection
                            .getHeaderField("Retry-After")
                            ?.toLongOrNull()
                            ?.times(1000L)
                            ?: retryDelay(attempt)

                        blockedUntil[source.id] =
                            System.currentTimeMillis() +
                                delay.coerceAtMost(MAX_BLOCK_MS)
                    }

                    in 500..599 -> {
                        last = "Источник временно недоступен: HTTP $status"
                        blockedUntil[source.id] =
                            System.currentTimeMillis() + retryDelay(attempt)
                    }

                    else -> last = "Ошибка источника: HTTP $status"
                }
            } catch (e: PermanentTileException) {
                throw e
            } catch (e: FatalTileException) {
                throw e
            } catch (_: SocketTimeoutException) {
                last = "Таймаут источника"
            } catch (e: IOException) {
                last = e.message ?: "Ошибка сети"
            } finally {
                runCatching { connection?.errorStream?.close() }
                connection?.disconnect()
                networkSlots.release()
            }

            if (attempt + 1 < MAX_ATTEMPTS) {
                sleepInterruptibly(retryDelay(attempt))
            }
        }

        throw IOException(last)
    }

    private fun awaitSlot(source: OpenMapSource) {
        val lock = locks.getOrPut(source.id) { Any() }

        synchronized(lock) {
            while (true) {
                val now = System.currentTimeMillis()
                val wait =
                    max(
                        nextAt[source.id] ?: 0L,
                        blockedUntil[source.id] ?: 0L
                    ) - now

                if (wait <= 0L) {
                    nextAt[source.id] = now + source.minRequestIntervalMs
                    return
                }

                sleepInterruptibly(wait.coerceAtMost(1000L))
            }
        }
    }

    private fun retryDelay(attempt: Int): Long =
        (700L * (1L shl attempt.coerceAtMost(5)))
            .coerceAtMost(MAX_BLOCK_MS)

    private fun sleepInterruptibly(ms: Long) {
        var remaining = ms

        while (remaining > 0L) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Cancelled")
            }

            val part = minOf(remaining, 500L)
            Thread.sleep(part)
            remaining -= part
        }
    }

    private fun writeAtomic(destination: File, bytes: ByteArray) {
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

    private fun localTemplate(source: OpenMapSource): String =
        "http://127.0.0.1:$port/tile/${source.id}/{z}/{x}/{y}.${source.extension}"

    private fun styleJson(reliefOverlay: Boolean): String {
        val base = OpenMapSource.VERSATILES_SATELLITE
        val detail = OpenMapSource.OAM_HIGHRES
        val dem = OpenMapSource.MAPTERHORN_DEM
        val hillshade = if (reliefOverlay) 0.55 else 0.20

        return """
            {
              "version": 8,
              "name": "Forest Navigator 3D",
              "sources": {
                "${base.id}": {
                  "type": "raster",
                  "tiles": ["${localTemplate(base)}"],
                  "scheme": "xyz",
                  "tileSize": 512,
                  "minzoom": 0,
                  "maxzoom": 12,
                  "attribution": "${OpenMapProvider.ATTRIBUTION}"
                },
                "${detail.id}": {
                  "type": "raster",
                  "tiles": ["${localTemplate(detail)}"],
                  "scheme": "xyz",
                  "tileSize": 256,
                  "minzoom": 14,
                  "maxzoom": 22,
                  "attribution": "${OpenMapProvider.ATTRIBUTION}"
                },
                "${dem.id}": {
                  "type": "raster-dem",
                  "tiles": ["${localTemplate(dem)}"],
                  "scheme": "xyz",
                  "tileSize": 512,
                  "minzoom": 0,
                  "maxzoom": 15,
                  "encoding": "terrarium",
                  "attribution": "${OpenMapProvider.ATTRIBUTION}"
                }
              },
              "terrain": {
                "source": "${dem.id}",
                "exaggeration": 1.35
              },
              "layers": [
                {
                  "id": "satellite-base",
                  "type": "raster",
                  "source": "${base.id}",
                  "paint": {
                    "raster-opacity": 1.0,
                    "raster-resampling": "linear"
                  }
                },
                {
                  "id": "oam-highres",
                  "type": "raster",
                  "source": "${detail.id}",
                  "minzoom": 14,
                  "paint": {
                    "raster-opacity": 1.0,
                    "raster-resampling": "linear"
                  }
                },
                {
                  "id": "terrain-hillshade",
                  "type": "hillshade",
                  "source": "${dem.id}",
                  "paint": {
                    "hillshade-exaggeration": $hillshade,
                    "hillshade-shadow-color": "#201a15",
                    "hillshade-highlight-color": "#fff8e8",
                    "hillshade-accent-color": "#6a5846"
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private fun sendJson(client: Socket, body: String) =
        sendBinary(
            client,
            body.toByteArray(Charsets.UTF_8),
            "application/json; charset=utf-8"
        )

    private fun sendBinary(client: Socket, bytes: ByteArray, type: String) {
        val output = client.getOutputStream()
        val headers =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: public, max-age=31536000\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"

        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun sendStatus(client: Socket, status: Int, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val output = client.getOutputStream()
        val headers =
            "HTTP/1.1 $status Error\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"

        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private const val CACHE_DIR = "map_cache_3d_v1"
    private const val OFFLINE_DIR = "offline_regions_3d_v1"
    private const val MAX_ATTEMPTS = 3
    private const val MAX_BLOCK_MS = 30_000L
    private const val MIN_VALID_TILE_BYTES = 128
}
