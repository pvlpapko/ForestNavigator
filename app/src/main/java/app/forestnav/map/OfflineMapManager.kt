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

                val tiles = buildTileList(
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm,
                    minZoom = minZoom.toInt(),
                    maxZoom = maxZoom.toInt()
                )

                val allTasks = tiles.flatMap { tile ->
                    sources.map { source -> DownloadTask(source, tile) }
                }
                required = allTasks.size.toLong()

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

                // Verify the provider before queueing hundreds/thousands of requests.
                // This also gives immediate visible progress (1/N) instead of sitting at 0/N.
                if (pending.isNotEmpty()) {
                    checkNotCancelled()
                    val probe = pending.removeAt(0)
                    val probeFile = LocalMapStyleServer.tileFile(
                        partial,
                        probe.source,
                        probe.tile.z,
                        probe.tile.x,
                        probe.tile.y
                    )
                    try {
                        val stored = LocalMapStyleServer.downloadTileTo(
                            source = probe.source,
                            z = probe.tile.z,
                            x = probe.tile.x,
                            y = probe.tile.y,
                            destination = probeFile
                        )
                        completed.incrementAndGet()
                        bytes.addAndGet(stored)
                        publish(force = true)
                    } catch (t: Throwable) {
                        if (t is InterruptedException) throw t
                        throw IllegalStateException(
                            "Сервер карт недоступен: ${t.message ?: "не удалось получить первый тайл"}"
                        )
                    }
                }

                var remaining: List<DownloadTask> = pending
                repeat(MAX_DOWNLOAD_ROUNDS) { round ->
                    if (remaining.isEmpty()) return@repeat
                    checkNotCancelled()

                    if (round > 0) {
                        Thread.sleep((1_250L * round).coerceAtMost(4_000L))
                    }

                    val failures = Collections.synchronizedList(mutableListOf<DownloadTask>())
                    val pool = Executors.newFixedThreadPool(WORKER_COUNT) { runnable ->
                        Thread(runnable, "forest-offline-worker").apply { isDaemon = true }
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

                File(partial, "name.txt").writeText(name, Charsets.UTF_8)
                File(partial, "layer.txt").writeText(layer.name, Charsets.UTF_8)
                File(partial, "radius.txt").writeText(radiusKm.toString(), Charsets.UTF_8)
                File(partial, "center.txt").writeText("$latitude,$longitude", Charsets.UTF_8)

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
        private const val WORKER_COUNT = 6
        private const val MAX_DOWNLOAD_ROUNDS = 3
        private const val MAX_RESOURCES = 60_000L
    }
}
