package app.forestnav.map

import android.content.Context
import java.io.File
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

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
        val currentPackageProgress: Int = 0,
        val needsRetry: Boolean = false,
        val fatal: Boolean = false,
        val complete: Boolean = false,
        val active: Boolean = false,
        val cancelled: Boolean = false,
        val error: String? = null
    )

    data class OfflinePackage(
        val file: File,
        val role: OfflineLayerRole,
        val opacity: Float
    )

    data class ExportChunk(
        val source: ArcGisOfflineSource,
        val index: Int,
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
        val file: File,
        val depth: Int = 0
    )

    private val root = File(context.filesDir, ROOT_DIR).apply { mkdirs() }

    fun partialDir(regionId: Long): File =
        File(root, ".partial-$regionId").apply { mkdirs() }

    fun finalDir(regionId: Long): File = File(root, regionId.toString())

    fun prepareRegion(
        regionId: Long,
        name: String,
        layer: MapLayer,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        minZoom: Double,
        maxZoom: Double
    ): File {
        val dir = partialDir(regionId)
        File(dir, "name.txt").writeText(name, Charsets.UTF_8)
        File(dir, "layer.txt").writeText(layer.name, Charsets.UTF_8)
        File(dir, "center.txt").writeText("$latitude,$longitude", Charsets.UTF_8)
        File(dir, "radius.txt").writeText(radiusKm.toString(), Charsets.UTF_8)
        File(dir, "zoom.txt").writeText("$minZoom,$maxZoom", Charsets.UTF_8)
        return dir
    }

    fun buildChunks(
        regionId: Long,
        layer: MapLayer,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        maxZoom: Int
    ): List<ExportChunk> {
        val latDelta = radiusKm / 111.32
        val lonDelta = radiusKm /
            (111.32 * cos(Math.toRadians(latitude)).coerceAtLeast(0.2))
        val north = (latitude + latDelta).coerceIn(-85.0, 85.0)
        val south = (latitude - latDelta).coerceIn(-85.0, 85.0)
        val west = (longitude - lonDelta).coerceIn(-180.0, 180.0)
        val east = (longitude + lonDelta).coerceIn(-180.0, 180.0)

        val widthMeters = radiusKm * 2_000.0
        val tileMeters = (
            40_075_016.686 * cos(Math.toRadians(latitude)).coerceAtLeast(0.2) /
                2.0.pow(maxZoom.toDouble())
            ).coerceAtLeast(1.0)
        val maxLevelTiles = (widthMeters / tileMeters).pow(2.0)
        val allLevelTiles = maxLevelTiles * 1.34
        val splits = ceil(sqrt(allLevelTiles / SAFE_TILES_PER_EXPORT))
            .toInt()
            .coerceIn(1, MAX_SPLITS_PER_AXIS)

        val dir = partialDir(regionId)
        val chunks = mutableListOf<ExportChunk>()
        var index = 0

        MapStyles.offlineSources(layer).forEach { source ->
            for (row in 0 until splits) {
                val s = south + (north - south) * row / splits
                val n = south + (north - south) * (row + 1) / splits
                for (col in 0 until splits) {
                    val w = west + (east - west) * col / splits
                    val e = west + (east - west) * (col + 1) / splits
                    val file = File(
                        dir,
                        "${source.role.name.lowercase()}_${source.id}_${row}_${col}.tpkx"
                    )
                    chunks += ExportChunk(
                        source = source,
                        index = index++,
                        west = w,
                        south = s,
                        east = e,
                        north = n,
                        file = file
                    )
                }
            }
        }
        return chunks
    }

    fun splitChunk(chunk: ExportChunk): List<ExportChunk> {
        val midLon = (chunk.west + chunk.east) / 2.0
        val midLat = (chunk.south + chunk.north) / 2.0
        val parent = chunk.file.parentFile ?: return emptyList()
        val stem = chunk.file.nameWithoutExtension

        val boxes = listOf(
            doubleArrayOf(chunk.west, chunk.south, midLon, midLat),
            doubleArrayOf(midLon, chunk.south, chunk.east, midLat),
            doubleArrayOf(chunk.west, midLat, midLon, chunk.north),
            doubleArrayOf(midLon, midLat, chunk.east, chunk.north)
        )

        return boxes.mapIndexed { quadrant, box ->
            ExportChunk(
                source = chunk.source,
                index = chunk.index * 4 + quadrant,
                west = box[0],
                south = box[1],
                east = box[2],
                north = box[3],
                file = File(parent, "${stem}_q$quadrant.tpkx"),
                depth = chunk.depth + 1
            )
        }
    }

    fun finalizeRegion(regionId: Long): File {
        val partial = partialDir(regionId)
        val final = finalDir(regionId)
        if (final.exists()) final.deleteRecursively()
        if (!partial.renameTo(final)) {
            partial.copyRecursively(final, overwrite = true)
            partial.deleteRecursively()
        }
        return final
    }

    fun calculateBytes(regionId: Long): Long =
        partialDir(regionId).walkTopDown()
            .filter { it.isFile && it.extension.equals("tpkx", true) }
            .sumOf { it.length() }

    fun listRegions(
        onSuccess: (List<Pair<Long, String>>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        runCatching {
            root.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".partial-") }
                .mapNotNull { dir ->
                    val id = dir.name.toLongOrNull() ?: return@mapNotNull null
                    val name = File(dir, "name.txt").takeIf { it.isFile }
                        ?.readText(Charsets.UTF_8).orEmpty()
                    id to name
                }
                .sortedByDescending { it.first }
        }.onSuccess(onSuccess).onFailure(onError)
    }

    fun packagesFor(layer: MapLayer): List<OfflinePackage> {
        val packages = mutableListOf<OfflinePackage>()
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".partial-") }
            .forEach { dir ->
                val stored = runCatching {
                    MapLayer.valueOf(File(dir, "layer.txt").readText(Charsets.UTF_8).trim())
                }.getOrNull()
                if (stored != layer) return@forEach
                dir.listFiles { file -> file.isFile && file.extension.equals("tpkx", true) }
                    .orEmpty()
                    .forEach { file ->
                        val role = when {
                            file.name.startsWith("reference_") -> OfflineLayerRole.REFERENCE
                            file.name.startsWith("hillshade_") -> OfflineLayerRole.HILLSHADE
                            else -> OfflineLayerRole.BASE
                        }
                        val opacity = if (role == OfflineLayerRole.HILLSHADE) 0.25f else 1f
                        packages += OfflinePackage(file, role, opacity)
                    }
            }
        return packages
    }

    fun deleteRegion(
        id: Long,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        runCatching {
            finalDir(id).deleteRecursively()
            File(root, ".partial-$id").deleteRecursively()
        }.onSuccess { onSuccess() }.onFailure(onError)
    }

    companion object {
        private const val ROOT_DIR = "arcgis_offline_v2"
        private const val SAFE_TILES_PER_EXPORT = 12_000.0
        private const val MAX_SPLITS_PER_AXIS = 20
    }
}
