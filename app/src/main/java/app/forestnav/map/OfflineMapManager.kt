package app.forestnav.map

import android.content.Context
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
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
        val name: String = "",
        val layerTitle: String = "",
        val completedResources: Long = 0,
        val requiredResources: Long = 0,
        val bytes: Long = 0,
        val skippedResources: Long = 0,
        val complete: Boolean = false,
        val active: Boolean = false,
        val cancelled: Boolean = false,
        val error: String? = null
    )

    private data class Tile(val z: Int, val x: Int, val y: Int)
    private data class DownloadTask(val source: String, val tile: Tile)

    private class DownloadHandle {
        val cancelRequested = AtomicBoolean(false)
        val deletePartialOnCancel = AtomicBoolean(false)

        @Volatile var future: Future<*>? = null
        @Volatile var workerPool: ExecutorService? = null
    }

    private val root = LocalMapStyleServer.offlineRoot(context)
    private val coordinator = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "forest-offline-region").apply { isDaemon = true }
    }
    private val regionSlots = Semaphore(MAX_CONCURRENT_REGIONS, true)
    private val handles = ConcurrentHashMap<Long, DownloadHandle>()

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
    ): Long {
        val sources = LocalMapStyleServer.sourcesFor(layer)
        val actualRegionId = regionId ?: System.currentTimeMillis()

        if (sources.isEmpty()) {
            onProgress(
                DownloadProgress(
                    regionId = actualRegionId,
                    name = name,
                    layerTitle = layer.title,
                    error = "Для своей карты офлайн-загрузка пока не поддерживается"
                )
            )
            return actualRegionId
        }

        val handle = DownloadHandle()
        if (handles.putIfAbsent(actualRegionId, handle) != null) {
            return actualRegionId
        }

        val partial = File(root, ".partial-$actualRegionId")
        val finalDir = File(root, actualRegionId.toString())

        onProgress(
            DownloadProgress(
                regionId = actualRegionId,
                name = name,
                layerTitle = layer.title,
                active = true
            )
        )

        handle.future = coordinator.submit {
            var regionSlotAcquired = false
            val completed = AtomicLong(0L)
            val bytes = AtomicLong(0L)
            val skipped = AtomicLong(0L)
            var required = 0L
            var lastNotifyAt = 0L

            fun publish(force: Boolean = false, active: Boolean = true) {
                val now = System.currentTimeMillis()
                if (!force && now - lastNotifyAt < PROGRESS_INTERVAL_MS) return
                lastNotifyAt = now
                onProgress(
                    DownloadProgress(
                        regionId = actualRegionId,
                        name = name,
                        layerTitle = layer.title,
                        completedResources = completed.get(),
                        requiredResources = required,
                        bytes = bytes.get(),
                        skippedResources = skipped.get(),
                        active = active
                    )
                )
            }

            try {
                regionSlots.acquire()
                regionSlotAcquired = true
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
                    val sourceSpec = MapboxProvider.sourceById(source)
                    val zoomOffset = if (sourceSpec.tileSize == 512) 1 else 0
                    val sourceMinZoom = (minZoom.toInt() - zoomOffset).coerceAtLeast(0)
                    val sourceMaxZoom = minOf(
                        maxZoom.toInt() - zoomOffset,
                        LocalMapStyleServer.maxDownloadZoom(source)
                    )
                    if (sourceMaxZoom < sourceMinZoom) {
                        emptyList()
                    } else {
                        buildTileList(
                            latitude = latitude,
                            longitude = longitude,
                            radiusKm = radiusKm,
                            minZoom = sourceMinZoom,
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
                    if (file.isFile && file.length() >= MIN_VALID_TILE_BYTES) {
                        completed.incrementAndGet()
                        bytes.addAndGet(file.length())
                    } else {
                        pending += task
                    }
                }

                publish(force = true)

                var remaining: List<DownloadTask> = pending
                repeat(MAX_DOWNLOAD_ROUNDS) { round ->
                    if (remaining.isEmpty()) return@repeat
                    checkNotCancelled(handle)

                    if (round > 0) {
                        sleepWithCancellation(retryRoundDelay(round), handle)
                    }

                    val failures = Collections.synchronizedList(mutableListOf<DownloadTask>())
                    val pool = Executors.newFixedThreadPool(workerCountForRound(round)) { runnable ->
                        Thread(runnable, "forest-offline-tile").apply { isDaemon = true }
                    }
                    handle.workerPool = pool
                    val latch = CountDownLatch(remaining.size)

                    remaining.forEach { task ->
                        pool.execute {
                            try {
                                checkNotCancelled(handle)
                                val file = LocalMapStyleServer.tileFile(
                                    partial,
                                    task.source,
                                    task.tile.z,
                                    task.tile.x,
                                    task.tile.y
                                )

                                if (file.isFile && file.length() >= MIN_VALID_TILE_BYTES) {
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
                            } catch (_: LocalMapStyleServer.PermanentTileException) {
                                skipped.incrementAndGet()
                                completed.incrementAndGet()
                            } catch (_: InterruptedException) {
                                // Cancellation is handled by the coordinator loop.
                            } catch (_: Throwable) {
                                if (!handle.cancelRequested.get()) failures += task
                            } finally {
                                latch.countDown()
                            }
                        }
                    }

                    var lastCompleted = completed.get()
                    var lastMovementAt = System.currentTimeMillis()

                    while (!latch.await(PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                        checkNotCancelled(handle)
                        publish()

                        val nowCompleted = completed.get()
                        if (nowCompleted != lastCompleted) {
                            lastCompleted = nowCompleted
                            lastMovementAt = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - lastMovementAt >= ROUND_STALL_TIMEOUT_MS) {
                            pool.shutdownNow()
                            throw IllegalStateException(
                                "Сеть перестала отвечать. Скачанная часть сохранена — повторите загрузку для продолжения."
                            )
                        }
                    }

                    pool.shutdown()
                    pool.awaitTermination(2L, TimeUnit.SECONDS)
                    handle.workerPool = null
                    remaining = failures.toList()
                }

                checkNotCancelled(handle)

                if (remaining.isNotEmpty()) {
                    val tolerated = toleratedFailureCount(required)
                    if (remaining.size.toLong() <= tolerated) {
                        skipped.addAndGet(remaining.size.toLong())
                        completed.addAndGet(remaining.size.toLong())
                        remaining = emptyList()
                    } else {
                        throw IllegalStateException(
                            "Не удалось скачать ${remaining.size} тайлов. Уже скачанная часть сохранена — повторите загрузку для продолжения."
                        )
                    }
                }

                if (finalDir.exists()) finalDir.deleteRecursively()
                if (!partial.renameTo(finalDir)) {
                    partial.copyRecursively(finalDir, overwrite = true)
                    partial.deleteRecursively()
                }

                onProgress(
                    DownloadProgress(
                        regionId = actualRegionId,
                        name = name,
                        layerTitle = layer.title,
                        completedResources = completed.get(),
                        requiredResources = required,
                        bytes = bytes.get(),
                        skippedResources = skipped.get(),
                        complete = true
                    )
                )
            } catch (_: InterruptedException) {
                handle.workerPool?.shutdownNow()
                if (handle.deletePartialOnCancel.get()) partial.deleteRecursively()
                onProgress(
                    DownloadProgress(
                        regionId = actualRegionId,
                        name = name,
                        layerTitle = layer.title,
                        completedResources = completed.get(),
                        requiredResources = required,
                        bytes = bytes.get(),
                        skippedResources = skipped.get(),
                        cancelled = true
                    )
                )
            } catch (t: Throwable) {
                handle.workerPool?.shutdownNow()
                onProgress(
                    DownloadProgress(
                        regionId = actualRegionId,
                        name = name,
                        layerTitle = layer.title,
                        completedResources = completed.get(),
                        requiredResources = required,
                        bytes = bytes.get(),
                        skippedResources = skipped.get(),
                        error = t.message ?: "Ошибка загрузки"
                    )
                )
            } finally {
                handle.workerPool = null
                if (regionSlotAcquired) regionSlots.release()
                handles.remove(actualRegionId, handle)
            }
        }

        return actualRegionId
    }

    fun cancelDownload(regionId: Long? = null, deletePartial: Boolean = false) {
        if (regionId == null) {
            handles.entries.toList().forEach { (id, _) ->
                cancelDownload(id, deletePartial)
            }
            return
        }

        val handle = handles[regionId] ?: return
        handle.deletePartialOnCancel.set(deletePartial)
        handle.cancelRequested.set(true)
        handle.workerPool?.shutdownNow()
        handle.future?.cancel(true)
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
            cancelDownload(id, deletePartial = true)
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

    private fun workerCountForRound(round: Int): Int = when (round) {
        0 -> 10
        1 -> 6
        else -> 3
    }

    private fun retryRoundDelay(round: Int): Long = when (round) {
        1 -> 1_000L
        else -> 2_500L
    }

    private fun toleratedFailureCount(required: Long): Long =
        minOf(MAX_TOLERATED_MISSING, maxOf(2L, required / 500L))

    private fun sleepWithCancellation(durationMs: Long, handle: DownloadHandle) {
        var remaining = durationMs
        while (remaining > 0L) {
            checkNotCancelled(handle)
            val slice = minOf(remaining, 250L)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    private fun checkNotCancelled(handle: DownloadHandle) {
        if (handle.cancelRequested.get() || Thread.currentThread().isInterrupted) {
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
        private const val MAX_CONCURRENT_REGIONS = 3
        private const val MAX_DOWNLOAD_ROUNDS = 3
        private const val MAX_RESOURCES = 60_000L
        private const val MAX_TOLERATED_MISSING = 25L
        private const val MIN_VALID_TILE_BYTES = 128L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val ROUND_STALL_TIMEOUT_MS = 45_000L
    }
}
