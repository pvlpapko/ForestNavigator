package app.forestnav.map

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.tan

class OfflineMapManager(private val context: Context) {
    data class DownloadProgress(
        val regionId: Long? = null,
        val completedResources: Long = 0,
        val requiredResources: Long = 0,
        val bytes: Long = 0,
        val complete: Boolean = false,
        val active: Boolean = false,
        val cancelled: Boolean = false,
        val error: String? = null
    )

    private val root = File(context.filesDir, "offline_regions").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelRequested = AtomicBoolean(false)

    @Volatile private var currentFuture: Future<*>? = null

    fun downloadAround(
        name: String,
        layer: MapLayer,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        minZoom: Double = 10.0,
        maxZoom: Double = 17.0,
        onProgress: (DownloadProgress) -> Unit
    ) {
        cancelDownload()
        cancelRequested.set(false)

        val sources = LocalMapStyleServer.sourcesFor(layer)
        if (sources.isEmpty()) {
            onProgress(
                DownloadProgress(
                    error = "Для своей карты офлайн-загрузка пока не поддерживается"
                )
            )
            return
        }

        val regionId = System.currentTimeMillis()
        val partial = File(root, ".partial-$regionId")
        val finalDir = File(root, regionId.toString())

        currentFuture = executor.submit {
            try {
                partial.deleteRecursively()
                partial.mkdirs()

                val tiles = buildTileList(
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                    minZoom = minZoom.toInt(),
                    maxZoom = maxZoom.toInt()
                )

                val required = tiles.size.toLong() * sources.size.toLong()
                if (required > 30_000L) {
                    throw IllegalStateException(
                        "Область слишком большая для выбранного масштаба: $required тайлов"
                    )
                }

                var completed = 0L
                var bytes = 0L

                onProgress(
                    DownloadProgress(
                        regionId = regionId,
                        requiredResources = required,
                        active = true
                    )
                )

                for (source in sources) {
                    for (tile in tiles) {
                        if (cancelRequested.get() || Thread.currentThread().isInterrupted) {
                            throw InterruptedException("Загрузка отменена")
                        }

                        val file = LocalMapStyleServer.tileFile(
                            partial, source, tile.z, tile.x, tile.y
                        )

                        bytes += LocalMapStyleServer.downloadTileTo(
                            source = source,
                            z = tile.z,
                            x = tile.x,
                            y = tile.y,
                            destination = file
                        )
                        completed++

                        if (completed == required ||
                            completed % 5L == 0L ||
                            completed <= 3L
                        ) {
                            onProgress(
                                DownloadProgress(
                                    regionId = regionId,
                                    completedResources = completed,
                                    requiredResources = required,
                                    bytes = bytes,
                                    active = true
                                )
                            )
                        }
                    }
                }

                File(partial, "name.txt").writeText(name, Charsets.UTF_8)
                File(partial, "layer.txt").writeText(layer.name, Charsets.UTF_8)
                File(partial, "radius.txt").writeText(radiusKm.toString(), Charsets.UTF_8)

                if (!partial.renameTo(finalDir)) {
                    finalDir.deleteRecursively()
                    partial.copyRecursively(finalDir, overwrite = true)
                    partial.deleteRecursively()
                }

                onProgress(
                    DownloadProgress(
                        regionId = regionId,
                        completedResources = completed,
                        requiredResources = required,
                        bytes = bytes,
                        complete = true
                    )
                )
            } catch (_: InterruptedException) {
                partial.deleteRecursively()
                onProgress(
                    DownloadProgress(
                        regionId = regionId,
                        cancelled = true
                    )
                )
            } catch (t: Throwable) {
                partial.deleteRecursively()
                onProgress(
                    DownloadProgress(
                        regionId = regionId,
                        error = t.message ?: "Ошибка загрузки"
                    )
                )
            } finally {
                currentFuture = null
                cancelRequested.set(false)
            }
        }
    }

    fun cancelDownload() {
        cancelRequested.set(true)
        currentFuture?.cancel(true)
        currentFuture = null
    }

    fun listRegions(
        onResult: (List<Pair<Long, String>>) -> Unit,
        onError: (String) -> Unit
    ) {
        runCatching {
            root.listFiles()
                .orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".partial-") }
                .mapNotNull { dir ->
                    val id = dir.name.toLongOrNull() ?: return@mapNotNull null
                    val name = File(dir, "name.txt")
                        .takeIf { it.isFile }
                        ?.readText(Charsets.UTF_8)
                        .orEmpty()
                    id to name
                }
                .sortedByDescending { it.first }
        }.onSuccess(onResult).onFailure {
            onError(it.message ?: "Не удалось прочитать офлайн-области")
        }
    }

    fun deleteRegion(id: Long, onDone: () -> Unit, onError: (String) -> Unit) {
        runCatching {
            val dir = File(root, id.toString())
            if (dir.exists() && !dir.deleteRecursively()) {
                error("Не удалось удалить область")
            }
        }.onSuccess { onDone() }.onFailure {
            onError(it.message ?: "Не удалось удалить область")
        }
    }

    private data class Tile(val z: Int, val x: Int, val y: Int)

    private fun buildTileList(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        minZoom: Int,
        maxZoom: Int
    ): List<Tile> {
        val latDelta = radiusKm / 111.32
        val lonDelta = radiusKm /
            (111.32 * cos(Math.toRadians(latitude)).coerceAtLeast(0.2))

        val north = (latitude + latDelta).coerceIn(-85.0, 85.0)
        val south = (latitude - latDelta).coerceIn(-85.0, 85.0)
        val west = (longitude - lonDelta).coerceIn(-180.0, 180.0)
        val east = (longitude + lonDelta).coerceIn(-180.0, 180.0)

        val result = ArrayList<Tile>()

        for (z in minZoom..maxZoom) {
            val n = 1 shl z
            val x0 = lonToTileX(west, z).coerceIn(0, n - 1)
            val x1 = lonToTileX(east, z).coerceIn(0, n - 1)
            val y0 = latToTileY(north, z).coerceIn(0, n - 1)
            val y1 = latToTileY(south, z).coerceIn(0, n - 1)

            for (x in x0..x1) {
                for (y in y0..y1) {
                    result += Tile(z, x, y)
                }
            }
        }

        return result
    }

    private fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = (1 shl zoom).toDouble()
        return floor((lon + 180.0) / 360.0 * n).toInt()
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val n = (1 shl zoom).toDouble()
        val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val value = (1.0 - asinh(tan(rad)) / PI) / 2.0 * n
        return floor(value).toInt()
    }
}
