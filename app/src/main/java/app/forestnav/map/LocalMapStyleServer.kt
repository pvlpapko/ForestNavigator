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
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

object LocalMapStyleServer {
    class PermanentTileException(message: String) : IOException(message)
    class FatalTileException(message: String) : IOException(message)

    private val started = AtomicBoolean(false)
    private lateinit var cacheRoot: File
    private lateinit var offlineRoot: File
    private var serverSocket: ServerSocket? = null
    @Volatile private var port: Int = 8765

    private val nextAt = ConcurrentHashMap<String, Long>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val networkSlots = Semaphore(20, true)

    private val clientExecutor = ThreadPoolExecutor(
        8, 8, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(256),
        { r -> Thread(r, "forest-mapbox-local-http").apply { isDaemon = true } }
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        cacheRoot = File(app.filesDir, CACHE_DIR).apply { mkdirs() }
        offlineRoot = File(app.filesDir, OFFLINE_DIR).apply { mkdirs() }

        val loopback = InetAddress.getByName("127.0.0.1")
        val socket = runCatching { ServerSocket(port, 96, loopback) }
            .getOrElse { ServerSocket(0, 96, loopback) }
        serverSocket = socket
        port = socket.localPort

        Thread({
            while (started.get() && !socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                try {
                    clientExecutor.execute {
                        client.use { runCatching { serve(it) } }
                    }
                } catch (_: RejectedExecutionException) {
                    runCatching { client.close() }
                }
            }
        }, "forest-mapbox-local-server").apply { isDaemon = true; start() }
    }

    fun offlineRoot(context: Context): File =
        File(context.filesDir, OFFLINE_DIR).apply { mkdirs() }

    fun mapUrl(): String = baseUrl("style/map.json")
    fun satelliteUrl(): String = baseUrl("style/satellite.json")
    fun reliefUrl(): String = baseUrl("style/relief.json")
    fun combinedUrl(): String = baseUrl("style/combined.json")

    /**
     * True 3D terrain style for the Mapbox v11 renderer.
     * Both the satellite raster and DEM point to this local proxy, so downloaded,
     * partially downloaded and normal runtime-cache tiles are reused before network.
     */
    fun threeDStyleJson(reliefOverlay: Boolean = false): String =
        if (reliefOverlay) local3DRelief() else local3DSatellite()

    fun sourcesFor(layer: MapLayer): List<String> = when (layer) {
        MapLayer.MAP -> listOf(MapboxSource.STREETS.id)
        MapLayer.SATELLITE -> listOf(MapboxSource.SATELLITE.id)
        MapLayer.RELIEF -> listOf(
            MapboxSource.OUTDOORS.id,
            MapboxSource.TERRAIN_RGB.id
        )
        MapLayer.SATELLITE_TERRAIN -> listOf(
            MapboxSource.SATELLITE_STREETS.id,
            MapboxSource.TERRAIN_RGB.id
        )
        MapLayer.THREE_D -> listOf(
            MapboxSource.SATELLITE_STREETS.id,
            MapboxSource.TERRAIN_RGB.id
        )
        MapLayer.THREE_D_TERRAIN -> listOf(
            MapboxSource.SATELLITE_STREETS.id,
            MapboxSource.TERRAIN_RGB.id
        )
        MapLayer.CUSTOM -> emptyList()
    }

    fun maxDownloadZoom(source: String): Int = MapboxProvider.sourceById(source).maxZoom

    fun tileFile(regionDir: File, source: String, z: Int, x: Int, y: Int): File {
        val s = MapboxProvider.sourceById(source)
        return File(regionDir, "mapbox/$source/$z/$x/$y.${s.extension}")
    }

    fun downloadTileTo(source: String, z: Int, x: Int, y: Int, destination: File): Long {
        if (valid(destination)) return destination.length()
        findCached(source, z, x, y)?.let {
            destination.parentFile?.mkdirs()
            if (it.absolutePath != destination.absolutePath) it.copyTo(destination, overwrite = true)
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
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
        val requestLine = reader.readLine().orEmpty()
        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?')?.trimStart('/').orEmpty()
        while (reader.readLine()?.isNotEmpty() == true) Unit
        when {
            path == "style/map.json" -> sendJson(client, localSingle(MapboxSource.STREETS))
            path == "style/satellite.json" -> sendJson(client, localSingle(MapboxSource.SATELLITE))
            path == "style/relief.json" -> sendJson(client, localRelief())
            path == "style/combined.json" -> sendJson(client, localCombined())
            path.startsWith("tile/") -> serveTile(client, path)
            else -> sendStatus(client, 404, "Not Found")
        }
    }

    private fun serveTile(client: Socket, path: String) {
        val p = path.split('/')
        if (p.size != 5) return sendStatus(client, 400, "Bad Request")
        val source = p[1]
        val spec = runCatching { MapboxProvider.sourceById(source) }.getOrNull()
            ?: return sendStatus(client, 400, "Bad Request")
        val z = p[2].toIntOrNull() ?: return sendStatus(client, 400, "Bad Request")
        val x = p[3].toIntOrNull() ?: return sendStatus(client, 400, "Bad Request")
        val yPart = p[4]
        val y = yPart.substringBeforeLast('.').toIntOrNull() ?: return sendStatus(client, 400, "Bad Request")
        if (yPart.substringAfterLast('.') != spec.extension) return sendStatus(client, 400, "Bad Request")

        findCached(source, z, x, y)?.let {
            return sendBinary(client, it.readBytes(), spec.contentType)
        }

        runCatching {
            val bytes = fetch(source, z, x, y)
            writeAtomic(cacheFile(source, z, x, y), bytes)
            sendBinary(client, bytes, spec.contentType)
        }.onFailure {
            sendStatus(client, 502, it.message ?: "Tile unavailable")
        }
    }

    private fun findCached(source: String, z: Int, x: Int, y: Int): File? {
        val c = cacheFile(source, z, x, y)
        if (valid(c)) return c
        offlineRoot.listFiles()?.asSequence()?.filter { it.isDirectory }?.forEach { dir ->
            val f = tileFile(dir, source, z, x, y)
            if (valid(f)) return f
        }
        return null
    }

    private fun cacheFile(source: String, z: Int, x: Int, y: Int): File {
        val s = MapboxProvider.sourceById(source)
        return File(cacheRoot, "$source/$z/$x/$y.${s.extension}")
    }

    private fun valid(file: File): Boolean = file.isFile && file.length() >= 128L

    private fun fetch(sourceId: String, z: Int, x: Int, y: Int): ByteArray {
        val source = MapboxProvider.sourceById(sourceId)
        var last = "Mapbox tile error"

        repeat(MAX_ATTEMPTS) { attempt ->
            awaitSlot(source)
            networkSlots.acquire()

            var c: HttpURLConnection? = null
            try {
                c = URL(MapboxProvider.tileUrl(source, z, x, y)).openConnection() as HttpURLConnection
                c.connectTimeout = 8_000
                c.readTimeout = 12_000
                c.instanceFollowRedirects = true
                c.useCaches = true
                c.setRequestProperty("User-Agent", "ForestNavigator/1.2 Android")
                c.setRequestProperty("Accept", "image/*,*/*;q=0.8")

                val status = c.responseCode
                if (status in 200..299) {
                    val bytes = c.inputStream.buffered().use { it.readBytes() }
                    if (bytes.size < 128) throw IOException("Mapbox returned empty tile")
                    blockedUntil.remove(source.id)
                    return bytes
                }

                when (status) {
                    401, 403 -> throw FatalTileException("Mapbox token rejected: HTTP $status")
                    400, 422 ->
                        throw FatalTileException("Mapbox tile request rejected: HTTP $status")
                    404 -> {
                        // Do not create a permanent hole from a CDN/style miss.
                        // Offline repair will retry this tile on later passes.
                        last = "Mapbox tile temporarily unavailable: HTTP 404"
                    }
                    410 ->
                        throw PermanentTileException("Mapbox tile permanently unavailable: HTTP 410")
                    429 -> {
                        last = "Mapbox HTTP 429"
                        val delay = c.getHeaderField("Retry-After")
                            ?.toLongOrNull()
                            ?.times(1000L)
                            ?: retryDelay(attempt)
                        blockedUntil[source.id] =
                            System.currentTimeMillis() + delay.coerceAtMost(30_000L)
                    }
                    in 500..599 -> {
                        last = "Mapbox HTTP $status"
                        blockedUntil[source.id] =
                            System.currentTimeMillis() + retryDelay(attempt)
                    }
                    else -> {
                        last = "Mapbox HTTP $status"
                    }
                }
            } catch (e: PermanentTileException) {
                throw e
            } catch (e: FatalTileException) {
                throw e
            } catch (e: SocketTimeoutException) {
                last = "Mapbox timeout"
            } catch (e: IOException) {
                last = e.message ?: "Mapbox network error"
                if (e is FatalTileException || last.contains("token rejected")) throw FatalTileException(last)
            } finally {
                runCatching { c?.errorStream?.close() }
                c?.disconnect()
                networkSlots.release()
            }

            if (attempt + 1 < MAX_ATTEMPTS) {
                sleepInterruptibly(retryDelay(attempt))
            }
        }

        throw IOException(last)
    }

    private fun awaitSlot(source: MapboxSource) {
        val lock = locks.getOrPut(source.id) { Any() }
        synchronized(lock) {
            while (true) {
                val now = System.currentTimeMillis()
                val wait = max(nextAt[source.id] ?: 0L, blockedUntil[source.id] ?: 0L) - now
                if (wait <= 0L) {
                    nextAt[source.id] = now + source.minRequestIntervalMs
                    return
                }
                sleepInterruptibly(wait.coerceAtMost(1000L))
            }
        }
    }

    private fun retryDelay(attempt: Int): Long =
        (800L * (1L shl attempt.coerceAtMost(5))).coerceAtMost(30_000L)

    private fun sleepInterruptibly(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")
            val part = minOf(remaining, 500L)
            Thread.sleep(part)
            remaining -= part
        }
    }

    private fun writeAtomic(dest: File, bytes: ByteArray) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, ".${dest.name}.${Thread.currentThread().id}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun localTemplate(source: MapboxSource): String =
        "http://127.0.0.1:$port/tile/${source.id}/{z}/{x}/{y}.${source.extension}"

    private fun localSingle(source: MapboxSource): String = """
        {"version":8,"sources":{"${source.id}":{"type":"raster","tiles":["${localTemplate(source)}"],"scheme":"xyz","tileSize":${source.tileSize},"minzoom":0,"maxzoom":${source.maxZoom}}},"layers":[{"id":"${source.id}","type":"raster","source":"${source.id}"}]}
    """.trimIndent()

    private fun localRelief(): String {
        val base = MapboxSource.OUTDOORS
        val dem = MapboxSource.TERRAIN_RGB
        return """
            {
              "version": 8,
              "name": "Mapbox Outdoors + Relief",
              "sources": {
                "${base.id}": {
                  "type": "raster",
                  "tiles": ["${localTemplate(base)}"],
                  "scheme": "xyz",
                  "tileSize": ${base.tileSize},
                  "minzoom": 0,
                  "maxzoom": ${base.maxZoom}
                },
                "${dem.id}": {
                  "type": "raster-dem",
                  "tiles": ["${localTemplate(dem)}"],
                  "scheme": "xyz",
                  "tileSize": ${dem.tileSize},
                  "minzoom": 0,
                  "maxzoom": ${dem.maxZoom},
                  "encoding": "mapbox"
                }
              },
              "layers": [
                {
                  "id": "outdoors",
                  "type": "raster",
                  "source": "${base.id}"
                },
                {
                  "id": "terrain-hillshade",
                  "type": "hillshade",
                  "source": "${dem.id}",
                  "paint": {
                    "hillshade-exaggeration": 0.56,
                    "hillshade-shadow-color": "#3a3028",
                    "hillshade-highlight-color": "#fff8e8",
                    "hillshade-accent-color": "#7c674d"
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private fun local3DSatellite(): String {
        val base = MapboxSource.SATELLITE_STREETS
        val dem = MapboxSource.TERRAIN_RGB
        return """
            {
              "version": 8,
              "name": "Forest Navigator 3D Satellite",
              "sources": {
                "${base.id}": {
                  "type": "raster",
                  "tiles": ["${localTemplate(base)}"],
                  "scheme": "xyz",
                  "tileSize": ${base.tileSize},
                  "minzoom": 0,
                  "maxzoom": ${base.maxZoom}
                },
                "${dem.id}": {
                  "type": "raster-dem",
                  "tiles": ["${localTemplate(dem)}"],
                  "scheme": "xyz",
                  "tileSize": ${dem.tileSize},
                  "minzoom": 0,
                  "maxzoom": ${dem.maxZoom},
                  "encoding": "mapbox"
                }
              },
              "terrain": {
                "source": "${dem.id}",
                "exaggeration": 1.25
              },
              "layers": [
                {
                  "id": "satellite-streets",
                  "type": "raster",
                  "source": "${base.id}",
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
                    "hillshade-exaggeration": 0.28,
                    "hillshade-shadow-color": "#221b17",
                    "hillshade-highlight-color": "#fff8e9",
                    "hillshade-accent-color": "#6a5744"
                  }
                }
              ]
            }
        """.trimIndent()
    }


    private fun local3DRelief(): String {
        // Same satellite base and 3D geometry as the normal 3D mode.
        // The only visual difference is a stronger hillshade overlay so slopes,
        // ridges and hollows are easier to read in the field.
        val base = MapboxSource.SATELLITE_STREETS
        val dem = MapboxSource.TERRAIN_RGB
        return """
            {
              "version": 8,
              "name": "Forest Navigator 3D Satellite + Relief",
              "sources": {
                "${base.id}": {
                  "type": "raster",
                  "tiles": ["${localTemplate(base)}"],
                  "scheme": "xyz",
                  "tileSize": ${base.tileSize},
                  "minzoom": 0,
                  "maxzoom": ${base.maxZoom}
                },
                "${dem.id}": {
                  "type": "raster-dem",
                  "tiles": ["${localTemplate(dem)}"],
                  "scheme": "xyz",
                  "tileSize": ${dem.tileSize},
                  "minzoom": 0,
                  "maxzoom": ${dem.maxZoom},
                  "encoding": "mapbox"
                }
              },
              "terrain": {
                "source": "${dem.id}",
                "exaggeration": 1.35
              },
              "layers": [
                {
                  "id": "satellite-streets",
                  "type": "raster",
                  "source": "${base.id}",
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
                    "hillshade-exaggeration": 0.52,
                    "hillshade-shadow-color": "#2b211a",
                    "hillshade-highlight-color": "#fff8e9",
                    "hillshade-accent-color": "#756047"
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private fun localCombined(): String {
        val base = MapboxSource.SATELLITE_STREETS
        val dem = MapboxSource.TERRAIN_RGB
        return """
            {"version":8,"sources":{"${base.id}":{"type":"raster","tiles":["${localTemplate(base)}"],"scheme":"xyz","tileSize":${base.tileSize},"minzoom":0,"maxzoom":${base.maxZoom}},"${dem.id}":{"type":"raster-dem","tiles":["${localTemplate(dem)}"],"scheme":"xyz","tileSize":${dem.tileSize},"minzoom":0,"maxzoom":${dem.maxZoom},"encoding":"mapbox"}},"layers":[{"id":"satellite-streets","type":"raster","source":"${base.id}"},{"id":"terrain-hillshade","type":"hillshade","source":"${dem.id}","paint":{"hillshade-exaggeration":0.44}}]}
        """.trimIndent()
    }

    private fun sendJson(client: Socket, body: String) = sendBinary(client, body.toByteArray(), "application/json")
    private fun sendBinary(client: Socket, bytes: ByteArray, type: String) {
        val o = client.getOutputStream()
        val h = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        o.write(h.toByteArray(Charsets.US_ASCII)); o.write(bytes); o.flush()
    }
    private fun sendStatus(client: Socket, status: Int, text: String) {
        val b = text.toByteArray()
        val o = client.getOutputStream()
        val h = "HTTP/1.1 $status Error\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n"
        o.write(h.toByteArray(Charsets.US_ASCII)); o.write(b); o.flush()
    }

    private const val CACHE_DIR = "map_cache_mapbox_v1"
    private const val OFFLINE_DIR = "offline_regions_mapbox_v1"
    private const val MAX_ATTEMPTS = 2
}
