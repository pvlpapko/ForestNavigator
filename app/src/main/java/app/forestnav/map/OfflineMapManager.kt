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
import java.util.concurrent.atomic.AtomicReference
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
        val missingResources: Long = 0,
        val needsRetry: Boolean = false,
        val fatal: Boolean = false,
        val complete: Boolean = false,
        val active: Boolean = false,
        val cancelled: Boolean = false,
        val error: String? = null
    )

    private data class Tile(val z: Int, val x: Int, val y: Int)
    private data class DownloadTask(val source: String, val tile: Tile)

    /**
     * Compact description of a rectangular slippy-tile range.
     *
     * HD satellite downloads can contain hundreds of thousands or even millions
     * of tiles. Keeping every tile as a Kotlin object caused excessive heap use,
     * so large regions are now streamed through bounded batches.
     */
    private data class TileRange(
        val source: String,
        val z: Int,
        val x0: Int,
        val x1: Int,
        val y0: Int,
        val y1: Int
    ) {
        val count: Long
            get() = (x1.toLong() - x0.toLong() + 1L) *
                (y1.toLong() - y0.toLong() + 1L)
    }

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
                    fatal = true,
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
                        missingResources = (required - completed.get()).coerceAtLeast(0L),
                        active = active
                    )
                )
            }

            try {
                regionSlots.acquire()
                regionSlotAcquired = true

                if (!partial.exists() &&
                    finalDir.isDirectory &&
                    File(finalDir, INCOMPLETE_MARKER).isFile
                ) {
                    if (!finalDir.renameTo(partial)) {
                        finalDir.copyRecursively(partial, overwrite = true)
                        finalDir.deleteRecursively()
                    }
                }

                partial.mkdirs()
                File(partial, INCOMPLETE_MARKER).delete()
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

                val ranges = buildTileRanges(
                    sources = sources,
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                    minZoom = minZoom.toInt(),
                    maxZoom = maxZoom.toInt()
                )

                required = ranges.sumOf { it.count }
                if (required <= 0L) {
                    throw IllegalStateException("Для выбранной области нет тайлов")
                }

                publish(force = true)

                for (range in ranges) {
                    checkNotCancelled(handle)

                    for (batch in batches(range)) {
                        checkNotCancelled(handle)

                        val pending = ArrayList<DownloadTask>(batch.size)
                        batch.forEach { task ->
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

                        publish()

                        if (pending.isEmpty()) continue

                        var remaining: List<DownloadTask> = pending

                        repeat(MAX_DOWNLOAD_ROUNDS) { round ->
                            if (remaining.isEmpty()) return@repeat
                            checkNotCancelled(handle)

                            if (round > 0) {
                                sleepWithCancellation(retryRoundDelay(round), handle)
                            }

                            val failures =
                                Collections.synchronizedList(mutableListOf<DownloadTask>())
                            val fatalError = AtomicReference<Throwable?>(null)
                            val pool =
                                Executors.newFixedThreadPool(workerCountForRound(round)) { runnable ->
                                    Thread(runnable, "forest-offline-tile").apply {
                                        isDaemon = true
                                    }
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

                                        if (file.isFile &&
                                            file.length() >= MIN_VALID_TILE_BYTES
                                        ) {
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
                                        // Only a provider-declared permanently removed tile
                                        // may be skipped and still allow the area to complete.
                                        skipped.incrementAndGet()
                                        completed.incrementAndGet()
                                    } catch (fatal: LocalMapStyleServer.FatalTileException) {
                                        fatalError.compareAndSet(null, fatal)
                                    } catch (_: InterruptedException) {
                                        // Cancellation is handled by the coordinator loop.
                                    } catch (_: Throwable) {
                                        if (!handle.cancelRequested.get()) {
                                            failures += task
                                        }
                                    } finally {
                                        latch.countDown()
                                    }
                                }
                            }

                            var lastCompleted = completed.get()
                            var lastMovementAt = System.currentTimeMillis()

                            while (!latch.await(
                                    PROGRESS_INTERVAL_MS,
                                    TimeUnit.MILLISECONDS
                                )
                            ) {
                                checkNotCancelled(handle)
                                publish()

                                val nowCompleted = completed.get()
                                if (nowCompleted != lastCompleted) {
                                    lastCompleted = nowCompleted
                                    lastMovementAt = System.currentTimeMillis()
                                } else if (
                                    System.currentTimeMillis() - lastMovementAt >=
                                    ROUND_STALL_TIMEOUT_MS
                                ) {
                                    pool.shutdownNow()
                                    throw IllegalStateException(
                                        "Сеть перестала отвечать. Скачанная часть сохранена, автодокачивание продолжится."
                                    )
                                }
                            }

                            pool.shutdown()
                            pool.awaitTermination(2L, TimeUnit.SECONDS)
                            handle.workerPool = null

                            fatalError.get()?.let { throw it }
                            remaining = failures.toList()
                        }

                        if (remaining.isNotEmpty()) {
                            val left = (required - completed.get()).coerceAtLeast(
                                remaining.size.toLong()
                            )
                            onProgress(
                                DownloadProgress(
                                    regionId = actualRegionId,
                                    name = name,
                                    layerTitle = layer.title,
                                    completedResources = completed.get(),
                                    requiredResources = required,
                                    bytes = bytes.get(),
                                    skippedResources = skipped.get(),
                                    missingResources = left,
                                    needsRetry = true,
                                    active = true
                                )
                            )
                            return@submit
                        }
                    }
                }

                checkNotCancelled(handle)

                if (finalDir.exists()) finalDir.deleteRecursively()
                if (!partial.renameTo(finalDir)) {
                    partial.copyRecursively(finalDir, overwrite = true)
                    partial.deleteRecursively()
                }
                File(finalDir, INCOMPLETE_MARKER).delete()

                val validDownloaded = countValidDownloaded(finalDir, ranges)
                val expectedDownloaded = required - skipped.get()

                if (validDownloaded < expectedDownloaded) {
                    val repair = File(root, ".partial-$actualRegionId")
                    if (repair.exists()) repair.deleteRecursively()
                    if (!finalDir.renameTo(repair)) {
                        finalDir.copyRecursively(repair, overwrite = true)
                        finalDir.deleteRecursively()
                    }

                    onProgress(
                        DownloadProgress(
                            regionId = actualRegionId,
                            name = name,
                            layerTitle = layer.title,
                            completedResources = validDownloaded + skipped.get(),
                            requiredResources = required,
                            bytes = bytes.get(),
                            skippedResources = skipped.get(),
                            missingResources =
                                (expectedDownloaded - validDownloaded).coerceAtLeast(0L),
                            needsRetry = true,
                            active = true
                        )
                    )
                    return@submit
                }

                onProgress(
                    DownloadProgress(
                        regionId = actualRegionId,
                        name = name,
                        layerTitle = layer.title,
                        completedResources = required,
                        requiredResources = required,
                        bytes = bytes.get(),
                        skippedResources = skipped.get(),
                        missingResources = 0L,
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
                        missingResources = (required - completed.get()).coerceAtLeast(0L),
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
                        missingResources = (required - completed.get()).coerceAtLeast(0L),
                        fatal = t is LocalMapStyleServer.FatalTileException,
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

    fun hasResumable(regionId: Long): Boolean =
        hasPartial(regionId) ||
            File(File(root, regionId.toString()), INCOMPLETE_MARKER).isFile

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

                    val incomplete = !partial && File(dir, INCOMPLETE_MARKER).isFile
                    id to when {
                        partial -> "⏸ $baseName"
                        incomplete -> "◐ $baseName"
                        else -> baseName
                    }
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

    private fun buildTileRanges(
        sources: List<String>,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        minZoom: Int,
        maxZoom: Int
    ): List<TileRange> {
        val result = ArrayList<TileRange>()

        sources.forEach { source ->
            val sourceSpec = MapboxProvider.sourceById(source)
            val zoomOffset = if (sourceSpec.tileSize == 512) 1 else 0
            val sourceMinZoom = (minZoom - zoomOffset).coerceAtLeast(0)
            val sourceMaxZoom = minOf(
                maxZoom - zoomOffset,
                LocalMapStyleServer.maxDownloadZoom(source)
            )

            if (sourceMaxZoom < sourceMinZoom) return@forEach

            val latDelta = radiusKm / 111.32
            val lonDelta = radiusKm /
                (111.32 * cos(Math.toRadians(latitude)).coerceAtLeast(0.2))

            val north = (latitude + latDelta).coerceIn(-85.0, 85.0)
            val south = (latitude - latDelta).coerceIn(-85.0, 85.0)
            val west = (longitude - lonDelta).coerceIn(-180.0, 180.0)
            val east = (longitude + lonDelta).coerceIn(-180.0, 180.0)

            for (z in sourceMinZoom..sourceMaxZoom) {
                val n = 1 shl z
                val x0 = lonToTileX(west, z).coerceIn(0, n - 1)
                val x1 = lonToTileX(east, z).coerceIn(0, n - 1)
                val y0 = latToTileY(north, z).coerceIn(0, n - 1)
                val y1 = latToTileY(south, z).coerceIn(0, n - 1)

                if (x1 >= x0 && y1 >= y0) {
                    result += TileRange(source, z, x0, x1, y0, y1)
                }
            }
        }

        return result
    }

    private fun batches(range: TileRange): Sequence<List<DownloadTask>> = sequence {
        var batch = ArrayList<DownloadTask>(TILE_BATCH_SIZE)

        for (x in range.x0..range.x1) {
            for (y in range.y0..range.y1) {
                batch += DownloadTask(
                    source = range.source,
                    tile = Tile(range.z, x, y)
                )

                if (batch.size >= TILE_BATCH_SIZE) {
                    yield(batch)
                    batch = ArrayList(TILE_BATCH_SIZE)
                }
            }
        }

        if (batch.isNotEmpty()) yield(batch)
    }

    private fun countValidDownloaded(
        regionDir: File,
        ranges: List<TileRange>
    ): Long {
        var valid = 0L

        ranges.forEach { range ->
            for (x in range.x0..range.x1) {
                for (y in range.y0..range.y1) {
                    val file = LocalMapStyleServer.tileFile(
                        regionDir,
                        range.source,
                        range.z,
                        x,
                        y
                    )
                    if (file.isFile && file.length() >= MIN_VALID_TILE_BYTES) {
                        valid++
                    }
                }
            }
        }

        return valid
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
        1 -> 7
        2 -> 5
        3 -> 3
        4 -> 2
        else -> 1
    }

    private fun retryRoundDelay(round: Int): Long = when (round) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        4 -> 7_000L
        else -> 10_000L
    }

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
        private const val MAX_DOWNLOAD_ROUNDS = 6
        private const val TILE_BATCH_SIZE = 8_000
        private const val MIN_VALID_TILE_BYTES = 128L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val ROUND_STALL_TIMEOUT_MS = 120_000L
        private const val INCOMPLETE_MARKER = "incomplete.txt"
    }
}
