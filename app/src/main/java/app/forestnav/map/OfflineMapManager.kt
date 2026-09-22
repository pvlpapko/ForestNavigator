package app.forestnav.map

import android.content.Context
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

    private data class Tile(val z: Int, val x: Int, val y: Int)
    private data class DownloadTask(val source: String, val tile: Tile)

    private val root = LocalMapStyleServer.offlineRoot(context)
    private val coordinator = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "forest-esri-offline-coordinator").apply { isDaemon = true }
    }
    private val cancelRequested = AtomicBoolean(false)
    private val deletePartialOnCancel = AtomicBoolean(true)

    @Volatile private var currentFuture: Future<*>? = null
    @Volatile private var currentWorkerPool: ExecutorService? = null

    fun downloadAround(
        name: String,
        layer: MapLayer,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        minZoom: Double = 10.0,
        maxZoom: Double = 17.0,
        regionId: Long? = null,
        onProgress: (DownloadProgress) -> Unit
    ) {
        currentFuture?.takeIf { !it.isDone }?.let { previous ->
            cancelDownload(deletePartial = false)
            runCatching { previous.get(2L, TimeUnit.SECONDS) }
        }
        cancelRequested.set(false)
        deletePartialOnCancel.set(true)

        val sources = LocalMapStyleServer.sourcesFor(layer)
        if (sources.isEmpty()) {
            onProgress(DownloadProgress(error = "Для своей карты офлайн-загрузка пока не поддерживается"))
            return
        }

        val actualRegionId = regionId ?: System.currentTimeMillis()
        val partial = File(root, ".partial-$actualRegionId")
        val finalDir = File(root, actualRegionId.toString())

        currentFuture = coordinator.submit {
            val completed = AtomicLong(0L)
            val bytes = AtomicLong(0L)
            var lastNotifyAt = 0L
            var required = 0L

            fun publish(force: Boolean = false, active: Boolean = true) {
                val now = System.currentTimeMillis()
                if (!force && now - lastNotifyAt < 250L) return
                lastNotifyAt = now
                onProgress(
                    DownloadProgress(
                        regionId = actualRegionId,
                        completedResources = completed.get(),
                        requiredResources = required,
                        bytes = bytes.get(),
                        active = active
                    )
                )
            }

            try {
                partial.mkdirs()
                writePartialMetadata(
                    partial = partial,
                    name = name,
                    layer = layer,
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                    minZoom = minZoom,
                    maxZoom = maxZoom
                )

                val allTasks = sources.flatMap { source ->
                    val sourceMaxZoom = minOf(
                        maxZoom.toInt(),
                        LocalMapStyleServer.maxDownloadZoom(source)
                    )
                    if (sourceMaxZoom < minZoom.toInt()) {
                        emptyList()
                    } else {
                        buildTileList(
                            latitude = latitude,
                            longitude = longitude,
                            radiusKm = radiusKm,
                            minZoom = minZoom.toInt(),
                            maxZoom = sourceMaxZoom
                        ).map { tile -> DownloadTask(source, tile) }
                    }
                }
                required = allTasks.size.toLong()

                if (required == 0L) {
                    throw IllegalStateException("Для выбранной области нет тайлов")
                }

                if (required > MAX_RESOURCES) {
                    throw IllegalStateException(
                        "Область слишком большая для выбранного масштаба: $required тайлов"
                    )
                }

                val pending = ArrayList<DownloadTask>(allTasks.size)
                allTasks.forEach { task ->
                    val file = LocalMapStyleServer.tileFile(
                        partial,
                        task.source,
                        task.tile.z,
                        task.tile.x,
                        task.tile.y
                    )
                    if (file.isFile && file.length() > 0L) {
                        completed.incrementAndGet()
                        bytes.addAndGet(file.length())
                    } else {
                        pending += task
                    }
                }

                publish(force = true)

                // Do not fail the whole region on a single probe tile.
                // Every missing tile now participates in the retry queue.
                var remaining: List<DownloadTask> = pending
                repeat(MAX_DOWNLOAD_ROUNDS) { round ->
                    if (remaining.isEmpty()) return@repeat
                    checkNotCancelled()

                    if (round > 0) {
                        sleepWithCancellation(retryRoundDelay(round))
                    }

                    val failures = Collections.synchronizedList(mutableListOf<DownloadTask>())
                    val pool = Executors.newFixedThreadPool(workerCountForRound(round)) { runnable ->
                        Thread(runnable, "forest-esri-offline-worker").apply { isDaemon = true }
                    }
                    currentWorkerPool = pool
                    val latch = CountDownLatch(remaining.size)

                    remaining.forEach { task ->
                        pool.execute {
                            try {
                                checkNotCancelled()
                                val file = LocalMapStyleServer.tileFile(
                                    partial,
                                    task.source,
                                    task.tile.z,
                                    task.tile.x,
                                    task.tile.y
                                )

                                if (file.isFile && file.length() > 0L) {
                                    completed.incrementAndGet()
                                    bytes.addAndGet(file.length())
                                } else {
                                    val stored = LocalMapStyleServer.downloadTileTo(
                                        source = task.source,
                                        z = task.tile.z,
                                        x = task.tile.x,
                                        y = task.tile.y,
                                        destination = file
                                    )
                                    completed.incrementAndGet()
                                    bytes.addAndGet(stored)
                                }
                            } catch (_: InterruptedException) {
                            } catch (_: Throwable) {
                                if (!cancelRequested.get()) failures += task
                            } finally {
                                latch.countDown()
                            }
                        }
                    }

                    while (!latch.await(250L, TimeUnit.MILLISECONDS)) {
                        if (cancelRequested.get() || Thread.currentThread().isInterrupted) {
                            pool.shutdownNow()
                            throw InterruptedException("Загрузка отменена")
                        }
                        publish()
                    }

                    pool.shutdown()
                    pool.awaitTermination(2L, TimeUnit.SECONDS)
                    currentWorkerPool = null
                    remaining = failures.toList()
                }

                checkNotCancelled()

                if (remaining.isNotEmpty()) {
                    throw IllegalStateException(
                        "Не удалось скачать ${remaining.size} тайлов. Уже скачанная часть сохранена — повторите загрузку для продолжения."
                    )
                }

                if (finalDir.exists()) finalDir.deleteRecursively()
                if (!partial.renameTo(finalDir)) {
                    partial.copyRecursively(finalDir, overwrite = true)
                    partial.deleteRecursively()
                }

                onProgress(
                    DownloadProgress(
                        regionId = actualRegionId,
                        completedResources = completed.get(),
                        requiredResources = required,
                        bytes = bytes.get(),
                        complete = true
                    )
                )
            } catch (_: InterruptedException) {
                currentWorkerPool?.shutdownNow()
                if (deletePartialOnCancel.get()) partial.deleteRecursively()
                onProgress(
                    DownloadProgress(
                        regionId = actualRegionId,
                        completedResources = completed.get(),
                        requiredResources = required,
                        bytes = bytes.get(),
                        cancelled = true
                    )
                )
            } catch (t: Throwable) {
                currentWorkerPool?.shutdownNow()
                onProgress(
                    DownloadProgress(
                        regionId = actualRegionId,
                        completedResources = completed.get(),
                        requiredResources = required,
                        bytes = bytes.get(),
                        error = t.message ?: "Ошибка загрузки"
                    )
                )
            } finally {
                currentWorkerPool = null
                currentFuture = null
                cancelRequested.set(false)
                deletePartialOnCancel.set(true)
            }
        }
    }

    fun cancelDownload(deletePartial: Boolean = true) {
        deletePartialOnCancel.set(deletePartial)
        cancelRequested.set(true)
        currentWorkerPool?.shutdownNow()
        currentFuture?.cancel(true)
    }

    fun hasPartial(regionId: Long): Boolean =
        File(root, ".partial-$regionId").isDirectory

    fun listRegions(
        onResult: (List<Pair<Long, String>>) -> Unit,
        onError: (String) -> Unit
    ) {
        runCatching {
            root.listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .mapNotNull { dir ->
                    val partial = dir.name.startsWith(".partial-")
                    val id = if (partial) {
                        dir.name.removePrefix(".partial-").toLongOrNull()
                    } else {
                        dir.name.toLongOrNull()
                    } ?: return@mapNotNull null

                    val baseName = File(dir, "name.txt")
                        .takeIf { it.isFile }
                        ?.readText(Charsets.UTF_8)
                        .orEmpty()
                        .ifBlank { "Офлайн-область #$id" }

                    id to if (partial) "⏸ $baseName" else baseName
                }
                .sortedByDescending { it.first }
        }.onSuccess(onResult).onFailure {
            onError(it.message ?: "Не удалось прочитать офлайн-области")
        }
    }

    fun deleteRegion(id: Long, onDone: () -> Unit, onError: (String) -> Unit) {
        runCatching {
            val dir = File(root, id.toString())
            val partial = File(root, ".partial-$id")
            if (dir.exists() && !dir.deleteRecursively()) {
                error("Не удалось удалить область")
            }
            if (partial.exists() && !partial.deleteRecursively()) {
                error("Не удалось удалить незавершённую область")
            }
        }.onSuccess { onDone() }.onFailure {
            onError(it.message ?: "Не удалось удалить область")
        }
    }

    private fun writePartialMetadata(
        partial: File,
        name: String,
        layer: MapLayer,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        minZoom: Double,
        maxZoom: Double
    ) {
        File(partial, "name.txt").writeText(name, Charsets.UTF_8)
        File(partial, "layer.txt").writeText(layer.name, Charsets.UTF_8)
        File(partial, "radius.txt").writeText(radiusKm.toString(), Charsets.UTF_8)
        File(partial, "center.txt").writeText("$latitude,$longitude", Charsets.UTF_8)
        File(partial, "zoom.txt").writeText("$minZoom,$maxZoom", Charsets.UTF_8)
    }

    private fun workerCountForRound(round: Int): Int = when {
        round <= 1 -> 4
        round <= 3 -> 3
        round <= 5 -> 2
        else -> 1
    }

    private fun retryRoundDelay(round: Int): Long = when (round) {
        1 -> 1_500L
        2 -> 3_000L
        3 -> 5_000L
        4 -> 8_000L
        5 -> 12_000L
        6 -> 18_000L
        else -> 25_000L
    }

    private fun sleepWithCancellation(durationMs: Long) {
        var remaining = durationMs
        while (remaining > 0L) {
            checkNotCancelled()
            val slice = minOf(remaining, 500L)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    private fun checkNotCancelled() {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Загрузка отменена")
        }
    }

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

    companion object {
        private const val MAX_DOWNLOAD_ROUNDS = 8
        private const val MAX_RESOURCES = 60_000L
    }
}
