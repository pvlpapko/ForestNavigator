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

/**
 * Clean resumable downloader.
 *
 * A region is written directly into a versioned partial directory. Starting the
 * same region again scans existing files first and requests only missing tiles.
 * Transient failures are retried in progressively smaller worker pools.
 */
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
        Thread(runnable, "forest-offline-clean-coordinator").apply { isDaemon = true }
    }
    private val cancelRequested = AtomicBoolean(false)

    @Volatile private var currentFuture: Future<*>? = null
    @Volatile private var currentWorkerPool: ExecutorService? = null
    @Volatile private var keepPartialOnCancel: Boolean = true

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
        stopPreviousJob()

        val sources = LocalMapStyleServer.sourcesFor(layer)
        if (sources.isEmpty()) {
            onProgress(
                DownloadProgress(
                    error = "Для своей карты офлайн-загрузка пока не поддерживается"
                )
            )
            return
        }

        cancelRequested.set(false)
        keepPartialOnCancel = true

        val actualRegionId = regionId ?: System.currentTimeMillis()
        val partial = partialDir(actualRegionId)
        val finalDir = finalDir(actualRegionId)

        currentFuture = coordinator.submit {
            val completed = AtomicLong(0L)
            val bytes = AtomicLong(0L)
            var required = 0L
            var lastNotifyAt = 0L

            fun publish(
                force: Boolean = false,
                active: Boolean = true,
                error: String? = null,
                cancelled: Boolean = false,
                complete: Boolean = false
            ) {
                val now = System.currentTimeMillis()
                if (!force && now - lastNotifyAt < PROGRESS_INTERVAL_MS) return
                lastNotifyAt = now
                onProgress(
                    DownloadProgress(
                        regionId = actualRegionId,
                        completedResources = completed.get(),
                        requiredResources = required,
                        bytes = bytes.get(),
                        complete = complete,
                        active = active,
                        cancelled = cancelled,
                        error = error
                    )
                )
            }

            try {
                partial.mkdirs()
                writeMetadata(
                    dir = partial,
                    name = name,
                    layer = layer,
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                    minZoom = minZoom,
                    maxZoom = maxZoom
                )

                val allTasks = buildTasks(
                    sources = sources,
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                    minZoom = minZoom.toInt(),
                    maxZoom = maxZoom.toInt()
                )

                required = allTasks.size.toLong()
                if (required <= 0L) {
                    error("Для выбранной области нет тайлов для загрузки")
                }
                if (required > MAX_RESOURCES) {
                    error("Область слишком большая: $required тайлов")
                }

                val pending = ArrayList<DownloadTask>(allTasks.size)
                allTasks.forEach { task ->
                    val file = tileFile(partial, task)
                    if (file.isFile && file.length() > 0L) {
                        completed.incrementAndGet()
                        bytes.addAndGet(file.length())
                    } else {
                        pending += task
                    }
                }

                writeRemaining(partial, pending.size)
                publish(force = true)

                var remaining: List<DownloadTask> = pending

                for (round in 0 until MAX_DOWNLOAD_ROUNDS) {
                    if (remaining.isEmpty()) break
                    checkNotCancelled()

                    if (round > 0) {
                        sleepWithCancellation(retryRoundDelay(round))
                    }

                    val failures = Collections.synchronizedList(
                        mutableListOf<DownloadTask>()
                    )
                    val workers = workerCountForRound(round)
                    val pool = Executors.newFixedThreadPool(workers) { runnable ->
                        Thread(
                            runnable,
                            "forest-offline-clean-r$round"
                        ).apply { isDaemon = true }
                    }
                    currentWorkerPool = pool

                    val latch = CountDownLatch(remaining.size)
                    remaining.forEach { task ->
                        pool.execute {
                            try {
                                checkNotCancelled()
                                val file = tileFile(partial, task)

                                val stored = if (file.isFile && file.length() > 0L) {
                                    file.length()
                                } else {
                                    LocalMapStyleServer.downloadTileTo(
                                        source = task.source,
                                        z = task.tile.z,
                                        x = task.tile.x,
                                        y = task.tile.y,
                                        destination = file
                                    )
                                }

                                completed.incrementAndGet()
                                bytes.addAndGet(stored)
                            } catch (_: InterruptedException) {
                                // Coordinator owns cancellation reporting.
                            } catch (_: Throwable) {
                                if (!cancelRequested.get()) {
                                    failures += task
                                }
                            } finally {
                                latch.countDown()
                            }
                        }
                    }

                    while (!latch.await(PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                        checkNotCancelled()
                        publish()
                    }

                    pool.shutdown()
                    pool.awaitTermination(5L, TimeUnit.SECONDS)
                    currentWorkerPool = null

                    remaining = failures.toList()
                    writeRemaining(partial, remaining.size)
                    publish(force = true)
                }

                checkNotCancelled()

                if (remaining.isNotEmpty()) {
                    publish(
                        force = true,
                        active = false,
                        error = "Осталось ${remaining.size} из $required тайлов. " +
                            "Скачанная часть сохранена. Нажмите «Скачать» ещё раз — " +
                            "продолжатся только недостающие тайлы."
                    )
                    return@submit
                }

                File(partial, "remaining.txt").delete()
                File(partial, "complete.txt").writeText("1", Charsets.UTF_8)

                if (finalDir.exists()) {
                    finalDir.deleteRecursively()
                }
                if (!partial.renameTo(finalDir)) {
                    partial.copyRecursively(finalDir, overwrite = true)
                    partial.deleteRecursively()
                }

                publish(
                    force = true,
                    active = false,
                    complete = true
                )
            } catch (_: InterruptedException) {
                currentWorkerPool?.shutdownNow()
                if (!keepPartialOnCancel) {
                    partial.deleteRecursively()
                }

                publish(
                    force = true,
                    active = false,
                    cancelled = true
                )
            } catch (t: Throwable) {
                currentWorkerPool?.shutdownNow()
                publish(
                    force = true,
                    active = false,
                    error = t.message ?: "Ошибка загрузки"
                )
            } finally {
                currentWorkerPool = null
                currentFuture = null
                cancelRequested.set(false)
                keepPartialOnCancel = true
            }
        }
    }

    fun cancelDownload(deletePartial: Boolean = false) {
        keepPartialOnCancel = !deletePartial
        cancelRequested.set(true)
        currentWorkerPool?.shutdownNow()
        currentFuture?.cancel(true)
    }

    fun hasPartial(regionId: Long): Boolean =
        partialDir(regionId).isDirectory

    fun listRegions(
        onResult: (List<Pair<Long, String>>) -> Unit,
        onError: (String) -> Unit
    ) {
        runCatching {
            val regions = mutableListOf<Pair<Long, String>>()

            root.listFiles().orEmpty().forEach { dir ->
                if (!dir.isDirectory) return@forEach

                val partial = dir.name.startsWith(PARTIAL_PREFIX)
                val id = if (partial) {
                    dir.name.removePrefix(PARTIAL_PREFIX).toLongOrNull()
                } else {
                    dir.name.toLongOrNull()
                } ?: return@forEach

                val baseName = File(dir, "name.txt")
                    .takeIf { it.isFile }
                    ?.readText(Charsets.UTF_8)
                    .orEmpty()
                    .ifBlank { "Офлайн-область #$id" }

                val label = if (partial) {
                    val remaining = File(dir, "remaining.txt")
                        .takeIf { it.isFile }
                        ?.readText(Charsets.UTF_8)
                        ?.toIntOrNull()
                    if (remaining != null && remaining > 0) {
                        "⏸ $baseName • осталось $remaining"
                    } else {
                        "⏸ $baseName"
                    }
                } else {
                    baseName
                }

                regions += id to label
            }

            regions.sortedByDescending { it.first }
        }.onSuccess(onResult).onFailure {
            onError(it.message ?: "Не удалось прочитать офлайн-области")
        }
    }

    fun deleteRegion(
        id: Long,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        runCatching {
            val final = finalDir(id)
            val partial = partialDir(id)

            if (final.exists() && !final.deleteRecursively()) {
                error("Не удалось удалить область")
            }
            if (partial.exists() && !partial.deleteRecursively()) {
                error("Не удалось удалить незавершённую область")
            }
        }.onSuccess {
            onDone()
        }.onFailure {
            onError(it.message ?: "Не удалось удалить область")
        }
    }

    private fun stopPreviousJob() {
        val previous = currentFuture ?: return
        if (previous.isDone) return

        cancelDownload(deletePartial = false)
        runCatching { previous.get(3L, TimeUnit.SECONDS) }
    }

    private fun buildTasks(
        sources: List<String>,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        minZoom: Int,
        maxZoom: Int
    ): List<DownloadTask> {
        return sources.flatMap { source ->
            val sourceMaxZoom = minOf(
                maxZoom,
                LocalMapStyleServer.maxDownloadZoom(source)
            )
            if (sourceMaxZoom < minZoom) {
                emptyList()
            } else {
                buildTileList(
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                    minZoom = minZoom,
                    maxZoom = sourceMaxZoom
                ).map { tile -> DownloadTask(source, tile) }
            }
        }
    }

    private fun tileFile(regionDir: File, task: DownloadTask): File =
        LocalMapStyleServer.tileFile(
            regionDir,
            task.source,
            task.tile.z,
            task.tile.x,
            task.tile.y
        )

    private fun writeMetadata(
        dir: File,
        name: String,
        layer: MapLayer,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        minZoom: Double,
        maxZoom: Double
    ) {
        File(dir, "name.txt").writeText(name, Charsets.UTF_8)
        File(dir, "layer.txt").writeText(layer.name, Charsets.UTF_8)
        File(dir, "radius.txt").writeText(radiusKm.toString(), Charsets.UTF_8)
        File(dir, "center.txt").writeText("$latitude,$longitude", Charsets.UTF_8)
        File(dir, "zoom.txt").writeText("$minZoom,$maxZoom", Charsets.UTF_8)
    }

    private fun writeRemaining(dir: File, count: Int) {
        File(dir, "remaining.txt").writeText(count.toString(), Charsets.UTF_8)
    }

    private fun workerCountForRound(round: Int): Int = when {
        round <= 1 -> 4
        round <= 4 -> 3
        else -> 2
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

    private fun partialDir(id: Long): File =
        File(root, "$PARTIAL_PREFIX$id")

    private fun finalDir(id: Long): File =
        File(root, id.toString())

    companion object {
        private const val PARTIAL_PREFIX = ".partial-"
        private const val MAX_DOWNLOAD_ROUNDS = 8
        private const val MAX_RESOURCES = 60_000L
        private const val PROGRESS_INTERVAL_MS = 300L
    }
}
