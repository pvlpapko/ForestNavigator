package app.forestnav.map

import app.forestnav.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

object LocalMapStyleServer {
    private val started = AtomicBoolean(false)
    @Volatile private var port: Int = -1
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return

        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort

        Thread {
            while (!socket.isClosed) {
                runCatching {
                    socket.accept().use(::serve)
                }
            }
        }.apply {
            name = "forest-map-style-server"
            isDaemon = true
            start()
        }
    }

    fun satelliteUrl(): String = baseUrl("satellite.json")
    fun terrainUrl(): String = baseUrl("terrain.json")
    fun combinedUrl(): String = baseUrl("combined.json")

    private fun baseUrl(path: String): String {
        if (!started.get()) start()
        return "http://127.0.0.1:$port/$path"
    }

    private fun serve(client: Socket) {
        client.soTimeout = 2500
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
        val requestLine = reader.readLine().orEmpty()
        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"

        val body = when (path) {
            "/satellite.json" -> satelliteStyle()
            "/terrain.json" -> terrainStyle()
            "/combined.json" -> combinedStyle()
            else -> null
        }

        val output = client.getOutputStream()
        if (body == null) {
            val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.US_ASCII))
        } else {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(headers.toByteArray(Charsets.US_ASCII))
            output.write(bytes)
        }
        output.flush()
    }

    private fun key(): String =
        URLEncoder.encode(BuildConfig.MAPTILER_KEY, StandardCharsets.UTF_8.toString())

    private fun satelliteStyle(): String {
        val k = key()
        return """
            {
              "version": 8,
              "name": "ForestNavigator Satellite",
              "sources": {
                "satellite": {
                  "type": "raster",
                  "tiles": [
                    "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$k"
                  ],
                  "scheme": "tms",
                  "tileSize": 512,
                  "minzoom": 0,
                  "maxzoom": 22,
                  "attribution": "© MapTiler © OpenStreetMap contributors"
                }
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#263238" }
                },
                {
                  "id": "satellite",
                  "type": "raster",
                  "source": "satellite",
                  "paint": { "raster-opacity": 1.0 }
                }
              ]
            }
        """.trimIndent()
    }

    private fun terrainStyle(): String {
        val k = key()
        return """
            {
              "version": 8,
              "name": "ForestNavigator Terrain",
              "sources": {
                "topo": {
                  "type": "raster",
                  "tiles": [
                    "https://a.tile.opentopomap.org/{z}/{x}/{y}.png"
                  ],
                  "scheme": "xyz",
                  "tileSize": 256,
                  "minzoom": 0,
                  "maxzoom": 17,
                  "attribution": "© OpenStreetMap contributors, SRTM; © OpenTopoMap (CC-BY-SA)"
                },
                "terrain": {
                  "type": "raster-dem",
                  "tiles": [
                    "https://api.maptiler.com/tiles/terrain-rgb-v2/{z}/{x}/{y}.webp?key=$k"
                  ],
                  "scheme": "tms",
                  "tileSize": 512,
                  "minzoom": 0,
                  "maxzoom": 14,
                  "encoding": "mapbox",
                  "attribution": "© MapTiler © OpenStreetMap contributors"
                }
              },
              "layers": [
                { "id": "topo", "type": "raster", "source": "topo" },
                {
                  "id": "terrain-hillshade",
                  "type": "hillshade",
                  "source": "terrain",
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
    }

    private fun combinedStyle(): String {
        val k = key()
        return """
            {
              "version": 8,
              "name": "ForestNavigator Satellite + Relief",
              "sources": {
                "satellite": {
                  "type": "raster",
                  "tiles": [
                    "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$k"
                  ],
                  "scheme": "tms",
                  "tileSize": 512,
                  "minzoom": 0,
                  "maxzoom": 22,
                  "attribution": "© MapTiler © OpenStreetMap contributors"
                },
                "terrain": {
                  "type": "raster-dem",
                  "tiles": [
                    "https://api.maptiler.com/tiles/terrain-rgb-v2/{z}/{x}/{y}.webp?key=$k"
                  ],
                  "scheme": "tms",
                  "tileSize": 512,
                  "minzoom": 0,
                  "maxzoom": 14,
                  "encoding": "mapbox",
                  "attribution": "© MapTiler © OpenStreetMap contributors"
                }
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#263238" }
                },
                { "id": "satellite", "type": "raster", "source": "satellite" },
                {
                  "id": "terrain-hillshade",
                  "type": "hillshade",
                  "source": "terrain",
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
    }
}
