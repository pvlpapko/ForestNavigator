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
 * Resumable offline downloader for the restored 0.9.3 map stack.
 *
 * Improvements over 0.9.3:
 * - conservative worker count to avoid bursts/timeouts;
 * - multiple retry rounds with increasing cool-down;
 * - only failed/missing tiles are retried;
 * - downloaded files remain in the partial region and are reused on resume;
 * - metadata is written before the first request so interrupted regions remain identifiable.
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

    private val root = File(context.filesDir, "offline_regions").apply { mkdirs() }
    private val coordinator = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "forest-offline-coordinator").apply { isDaemon = true }
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
            onProgress(
                DownloadProgress(
                    error = "Для своей карты офлайн-загрузка пока не поддерживается"
                )
            )
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
                if (!force && now - lastNotifyAt < PROGRESS_INTERVAL_MS) return
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
                        ).map { tile ->
                            DownloadTask(source, tile)
                        }
                    }
                }

                required = allTasks.size.toLong()

                if (required == 0L) {
                    throw IllegalStateException("Для выбранной области нет тайлов для загрузки")
                }

                if (required > MAX_RESOURCES) {
                    throw IllegalStateException(
                        "Область слишком большая для выбранного масштаба: $required тайлов"
                    )
                }

                val pending = ArrayList<DownloadTask>(allTasks.size)

                allTasks.forEach { task ->
                    val file = fileFor(partial, task)
                    if (file.isFile && file.length() > 0L) {
                        completed.incrementAndGet()
                        bytes.addAndGet(file.length())
                    } else {
                        pending += task
                    }
                }

                publish(force = true)

                var remaining: List<DownloadTask> = pending

                for (round in 0 until MAX_DOWNLOAD_ROUNDS) {
                    if (remaining.isEmpty()) break
                    checkNotCancelled()

                    if (round > 0) {
                        sleepWithCancellation(retryDelayMs(round))
                    }

                    val failures = Collections.synchronizedList(
                        mutableListOf<DownloadTask>()
                    )
                    val workerCount = workerCountForRound(round)
                    val pool = Executors.newFixedThreadPool(workerCount) { runnable ->
                        Thread(
                            runnable,
                            "forest-offline-worker-r$round"
                        ).apply { isDaemon = true }
                    }
                    currentWorkerPool = pool
                    val latch = CountDownLatch(remaining.size)

                    remaining.forEach { task ->
                        pool.execute {
                            try {
                                checkNotCancelled()

                                val file = fileFor(partial, task)
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
                                // Cancellation is handled by the coordinator loop.
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
                        if (cancelRequested.get() || Thread.currentThread().isInterrupted) {
                            pool.shutdownNow()
                            throw InterruptedException("Загрузка отменена")
                        }
                        publish()
                    }

                    pool.shutdown()
                    pool.awaitTermination(5L, TimeUnit.SECONDS)
                    currentWorkerPool = null

                    remaining = failures.toList()
                    publish(force = true)

                    if (remaining.isNotEmpty()) {
                        File(partial, "remaining.txt").writeText(
                            remaining.size.toString(),
                            Charsets.UTF_8
                        )
                    } else {
                        File(partial, "remaining.txt").delete()
                    }
                }

                checkNotCancelled()

                if (remaining.isNotEmpty()) {
                    throw IllegalStateException(
                        "Осталось скачать ${remaining.size} тайлов из $required. " +
                            "Скачанная часть сохранена. Нажмите «Скачать» ещё раз — " +
                            "загрузка продолжится только с недостающих тайлов."
                    )
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

                if (deletePartialOnCancel.get()) {
                    partial.deleteRecursively()
                }

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

    fun deleteRegion(
        id: Long,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        runCatching {
            val finalDir = File(root, id.toString())
            val partialDir = File(root, ".partial-$id")

            if (finalDir.exists() && !finalDir.deleteRecursively()) {
                error("Не удалось удалить область")
            }

            if (partialDir.exists() && !partialDir.deleteRecursively()) {
                error("Не удалось удалить незавершённую область")
            }
        }.onSuccess {
            onDone()
        }.onFailure {
            onError(it.message ?: "Не удалось удалить область")
        }
    }

    private fun fileFor(regionDir: File, task: DownloadTask): File =
        LocalMapStyleServer.tileFile(
            regionDir,
            task.source,
            task.tile.z,
            task.tile.x,
            task.tile.y
        )

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
        File(partial, "center.txt").writeText(
            "$latitude,$longitude",
            Charsets.UTF_8
        )
        File(partial, "zoom.txt").writeText(
            "$minZoom,$maxZoom",
            Charsets.UTF_8
        )
    }

    private fun workerCountForRound(round: Int): Int = when {
        round <= 1 -> 4
        round <= 4 -> 3
        else -> 2
    }

    private fun retryDelayMs(round: Int): Long = when (round) {
        1 -> 1_500L
        2 -> 3_000L
        3 -> 6_000L
        4 -> 10_000L
        5 -> 15_000L
        else -> 20_000L
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
        private const val MAX_DOWNLOAD_ROUNDS = 7
        private const val MAX_RESOURCES = 60_000L
        private const val PROGRESS_INTERVAL_MS = 300L
    }
}
