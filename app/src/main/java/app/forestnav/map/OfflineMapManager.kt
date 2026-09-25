package app.forestnav.map

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Single owner of the offline-map filesystem.
 *
 * Version 3 deliberately ignores the former arcgis_offline_v2 layout. A region
 * is visible to the renderer only after a complete manifest is atomically
 * published. Partial downloads can therefore never be mixed with finished
 * maps, and packages from different map modes are never guessed from filenames
 * at render time.
 */
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
        val format: OfflinePackageFormat,
        val role: OfflineLayerRole,
        val opacity: Float,
        val sourceId: String
    )

    data class OfflineSelection(
        val regionId: Long,
        val name: String,
        val layer: MapLayer,
        val packages: List<OfflinePackage>
    )

    data class ExportChunk(
        val source: OfflineSource,
        val index: Int,
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
        val file: File,
        val depth: Int = 0
    )

    private data class Bounds(
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double
    ) {
        fun contains(latitude: Double, longitude: Double): Boolean =
            latitude in south..north && longitude in west..east
    }

    private data class RegionRecord(
        val id: Long,
        val name: String,
        val layer: MapLayer,
        val bounds: Bounds,
        val packages: List<OfflinePackage>
    )

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, ROOT_DIR).apply { mkdirs() }

    @Volatile
    private var catalogCache: List<RegionRecord>? = null

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
        val dir = partialDir(regionId, create = true)
        val bounds = boundsFor(latitude, longitude, radiusKm)
        val props = Properties().apply {
            setProperty(KEY_SCHEMA, STORE_SCHEMA.toString())
            setProperty(KEY_REGION_ID, regionId.toString())
            setProperty(KEY_NAME, name)
            setProperty(KEY_LAYER, layer.name)
            setProperty(KEY_CENTER_LAT, latitude.toString())
            setProperty(KEY_CENTER_LON, longitude.toString())
            setProperty(KEY_RADIUS_KM, radiusKm.toString())
            setProperty(KEY_MIN_ZOOM, minZoom.toString())
            setProperty(KEY_MAX_ZOOM, maxZoom.toString())
            setProperty(KEY_WEST, bounds.west.toString())
            setProperty(KEY_SOUTH, bounds.south.toString())
            setProperty(KEY_EAST, bounds.east.toString())
            setProperty(KEY_NORTH, bounds.north.toString())
        }
        storeProperties(File(dir, REGION_META_FILE), props)
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
        val bounds = boundsFor(latitude, longitude, radiusKm)
        val widthMeters = radiusKm * 2_000.0
        val tileMeters = (
            WEB_MERCATOR_CIRCUMFERENCE_METERS *
                cos(Math.toRadians(latitude)).coerceAtLeast(0.2) /
                2.0.pow(maxZoom.toDouble())
            ).coerceAtLeast(1.0)

        val maxLevelTiles = (widthMeters / tileMeters).pow(2.0)
        val allLevelTiles = maxLevelTiles * TILE_PYRAMID_FACTOR
        val dir = partialDir(regionId, create = true)
        val chunks = mutableListOf<ExportChunk>()
        var globalIndex = 0

        MapStyles.offlineSources(layer).forEach { source ->
            val splitsByBudget = ceil(
                sqrt(allLevelTiles / source.targetTilesPerPackage)
            ).toInt()
            val parallelFloor =
                if (allLevelTiles >= PARALLEL_SPLIT_THRESHOLD_TILES) {
                    MIN_PARALLEL_SPLITS_PER_AXIS
                } else {
                    1
                }
            val splits = maxOf(splitsByBudget, parallelFloor)
                .coerceIn(1, MAX_SPLITS_PER_AXIS)

            for (row in 0 until splits) {
                val south = bounds.south +
                    (bounds.north - bounds.south) * row / splits
                val north = bounds.south +
                    (bounds.north - bounds.south) * (row + 1) / splits

                for (col in 0 until splits) {
                    val west = bounds.west +
                        (bounds.east - bounds.west) * col / splits
                    val east = bounds.west +
                        (bounds.east - bounds.west) * (col + 1) / splits

                    val file = File(
                        dir,
                        packageFileName(
                            source = source,
                            row = row,
                            col = col
                        )
                    )

                    chunks += ExportChunk(
                        source = source,
                        index = globalIndex++,
                        west = west,
                        south = south,
                        east = east,
                        north = north,
                        file = file
                    )
                }
            }
        }

        return chunks
    }

    fun isPackageComplete(chunk: ExportChunk): Boolean {
        if (!chunk.file.isFile || chunk.file.length() <= MIN_PACKAGE_BYTES) {
            return false
        }
        val meta = packageMetaFile(chunk.file)
        if (!meta.isFile) return false

        val props = loadProperties(meta) ?: return false
        return props.getProperty(PKG_FILE) == chunk.file.name &&
            props.getProperty(PKG_FORMAT) == chunk.source.format.name &&
            props.getProperty(PKG_ROLE) == chunk.source.role.name &&
            props.getProperty(PKG_SOURCE) == chunk.source.id
    }

    fun markPackageComplete(chunk: ExportChunk) {
        check(chunk.file.isFile && chunk.file.length() > MIN_PACKAGE_BYTES) {
            "Нельзя отметить пустой пакет как готовый"
        }

        val props = Properties().apply {
            setProperty(PKG_FILE, chunk.file.name)
            setProperty(PKG_FORMAT, chunk.source.format.name)
            setProperty(PKG_ROLE, chunk.source.role.name)
            setProperty(PKG_OPACITY, chunk.source.opacity.toString())
            setProperty(PKG_SOURCE, chunk.source.id)
        }
        storeProperties(packageMetaFile(chunk.file), props)
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
                file = File(
                    parent,
                    "${stem}_q$quadrant.${chunk.source.format.extension}"
                ),
                depth = chunk.depth + 1
            )
        }
    }

    /**
     * Publishes a region atomically. The renderer only reads final directories
     * with a schema-3 manifest, never partial folders.
     */
    fun finalizeRegion(regionId: Long): File {
        val partial = partialDir(regionId, create = false)
        check(partial.isDirectory) { "Временная папка офлайн-карты не найдена" }

        val regionMeta = loadProperties(File(partial, REGION_META_FILE))
            ?: error("Метаданные офлайн-карты повреждены")

        val packageMetas = partial.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(PACKAGE_META_SUFFIX) }
            .mapNotNull { metaFile ->
                val props = loadProperties(metaFile) ?: return@mapNotNull null
                val packageName = props.getProperty(PKG_FILE) ?: return@mapNotNull null
                val packageFile = File(partial, packageName)
                if (!packageFile.isFile || packageFile.length() <= MIN_PACKAGE_BYTES) {
                    return@mapNotNull null
                }
                props
            }
            .sortedBy { it.getProperty(PKG_FILE).orEmpty() }

        check(packageMetas.isNotEmpty()) {
            "Офлайн-карта не содержит готовых пакетов"
        }

        val manifest = Properties().apply {
            putAll(regionMeta)
            setProperty(KEY_SCHEMA, STORE_SCHEMA.toString())
            setProperty(KEY_COMPLETED_AT, System.currentTimeMillis().toString())
            setProperty(KEY_PACKAGE_COUNT, packageMetas.size.toString())

            packageMetas.forEachIndexed { index, pkg ->
                setProperty("package.$index.file", pkg.getProperty(PKG_FILE))
                setProperty("package.$index.format", pkg.getProperty(PKG_FORMAT))
                setProperty("package.$index.role", pkg.getProperty(PKG_ROLE))
                setProperty("package.$index.opacity", pkg.getProperty(PKG_OPACITY))
                setProperty("package.$index.source", pkg.getProperty(PKG_SOURCE))
            }
        }

        storeProperties(File(partial, MANIFEST_FILE), manifest)

        val final = finalDir(regionId)
        if (final.exists()) final.deleteRecursively()

        if (!partial.renameTo(final)) {
            partial.copyRecursively(final, overwrite = true)
            partial.deleteRecursively()
        }

        catalogCache = null
        return final
    }

    fun selectionFor(
        layer: MapLayer,
        latitude: Double,
        longitude: Double
    ): OfflineSelection? {
        val record = catalog()
            .asSequence()
            .filter { it.layer == layer }
            .filter { it.bounds.contains(latitude, longitude) }
            .maxByOrNull { it.id }
            ?: return null

        return OfflineSelection(
            regionId = record.id,
            name = record.name,
            layer = record.layer,
            packages = record.packages
        )
    }

    fun listRegions(
        onSuccess: (List<Pair<Long, String>>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        runCatching {
            catalog()
                .sortedByDescending { it.id }
                .map { it.id to it.name }
        }.onSuccess(onSuccess).onFailure(onError)
    }

    fun calculateBytes(regionId: Long): Long =
        directoryPackageBytes(partialDir(regionId, create = false))

    fun regionBytes(regionId: Long): Long =
        directoryPackageBytes(finalDir(regionId))

    fun deleteRegion(
        id: Long,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        runCatching {
            finalDir(id).deleteRecursively()
            partialDir(id, create = false).deleteRecursively()
            catalogCache = null
        }.onSuccess { onSuccess() }.onFailure(onError)
    }

    /**
     * Old downloads used a different mixed raster layout. They are removed on
     * the IO dispatcher by the ViewModel so an update cannot accidentally draw
     * legacy relief/streets packages over the new clean store.
     */
    fun cleanupLegacyStorage() {
        File(appContext.filesDir, LEGACY_ROOT_DIR).deleteRecursively()
        appContext.getSharedPreferences(
            LEGACY_DOWNLOAD_PREFS,
            Context.MODE_PRIVATE
        ).edit().clear().apply()
    }

    private fun catalog(): List<RegionRecord> {
        catalogCache?.let { return it }

        synchronized(this) {
            catalogCache?.let { return it }

            val loaded = root.listFiles()
                .orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(PARTIAL_PREFIX) }
                .mapNotNull(::readRegion)
                .sortedByDescending { it.id }

            catalogCache = loaded
            return loaded
        }
    }

    private fun readRegion(dir: File): RegionRecord? {
        val id = dir.name.toLongOrNull() ?: return null
        val manifest = loadProperties(File(dir, MANIFEST_FILE)) ?: return null
        if (manifest.getProperty(KEY_SCHEMA)?.toIntOrNull() != STORE_SCHEMA) {
            return null
        }

        val layer = runCatching {
            MapLayer.valueOf(manifest.getProperty(KEY_LAYER))
        }.getOrNull() ?: return null

        val bounds = Bounds(
            west = manifest.getProperty(KEY_WEST)?.toDoubleOrNull() ?: return null,
            south = manifest.getProperty(KEY_SOUTH)?.toDoubleOrNull() ?: return null,
            east = manifest.getProperty(KEY_EAST)?.toDoubleOrNull() ?: return null,
            north = manifest.getProperty(KEY_NORTH)?.toDoubleOrNull() ?: return null
        )

        val count = manifest.getProperty(KEY_PACKAGE_COUNT)
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: return null

        val packages = buildList {
            for (index in 0 until count) {
                val fileName = manifest.getProperty("package.$index.file")
                    ?: continue
                val file = File(dir, fileName)
                if (!file.isFile || file.length() <= MIN_PACKAGE_BYTES) continue

                val format = runCatching {
                    OfflinePackageFormat.valueOf(
                        manifest.getProperty("package.$index.format")
                    )
                }.getOrNull() ?: continue

                val role = runCatching {
                    OfflineLayerRole.valueOf(
                        manifest.getProperty("package.$index.role")
                    )
                }.getOrNull() ?: continue

                add(
                    OfflinePackage(
                        file = file,
                        format = format,
                        role = role,
                        opacity = manifest
                            .getProperty("package.$index.opacity")
                            ?.toFloatOrNull()
                            ?: 1f,
                        sourceId = manifest
                            .getProperty("package.$index.source")
                            .orEmpty()
                    )
                )
            }
        }

        if (packages.isEmpty()) return null

        return RegionRecord(
            id = id,
            name = manifest.getProperty(KEY_NAME).orEmpty()
                .ifBlank { "Офлайн-карта" },
            layer = layer,
            bounds = bounds,
            packages = packages
        )
    }

    private fun boundsFor(
        latitude: Double,
        longitude: Double,
        radiusKm: Double
    ): Bounds {
        val latDelta = radiusKm / KM_PER_DEGREE_LATITUDE
        val lonDelta = radiusKm /
            (
                KM_PER_DEGREE_LATITUDE *
                    cos(Math.toRadians(latitude)).coerceAtLeast(0.2)
                )

        return Bounds(
            west = (longitude - lonDelta).coerceIn(-180.0, 180.0),
            south = (latitude - latDelta).coerceIn(-85.0, 85.0),
            east = (longitude + lonDelta).coerceIn(-180.0, 180.0),
            north = (latitude + latDelta).coerceIn(-85.0, 85.0)
        )
    }

    private fun packageFileName(
        source: OfflineSource,
        row: Int,
        col: Int
    ): String =
        "${source.role.name.lowercase()}-${source.id}-$row-$col.${source.format.extension}"

    private fun partialDir(regionId: Long, create: Boolean): File =
        File(root, "$PARTIAL_PREFIX$regionId").also {
            if (create) it.mkdirs()
        }

    private fun finalDir(regionId: Long): File =
        File(root, regionId.toString())

    private fun packageMetaFile(packageFile: File): File =
        File(packageFile.parentFile, packageFile.name + PACKAGE_META_SUFFIX)

    private fun directoryPackageBytes(dir: File): Long {
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    (
                        file.extension.equals("tpkx", true) ||
                            file.extension.equals("vtpk", true)
                        )
            }
            .sumOf { it.length() }
    }

    private fun storeProperties(file: File, properties: Properties) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use {
            properties.store(it, "Forest Navigator offline map schema $STORE_SCHEMA")
            it.fd.sync()
        }
        if (file.exists()) file.delete()
        check(temp.renameTo(file)) {
            "Не удалось сохранить метаданные офлайн-карты"
        }
    }

    private fun loadProperties(file: File): Properties? =
        runCatching {
            Properties().apply {
                FileInputStream(file).use { input ->
                    load(input)
                }
            }
        }.getOrNull()

    companion object {
        private const val STORE_SCHEMA = 3
        private const val ROOT_DIR = "offline_maps_v3"
        private const val LEGACY_ROOT_DIR = "arcgis_offline_v2"
        private const val LEGACY_DOWNLOAD_PREFS = "arcgis_offline_jobs"
        private const val PARTIAL_PREFIX = ".partial-"
        private const val REGION_META_FILE = "region.properties"
        private const val MANIFEST_FILE = "manifest.properties"
        private const val PACKAGE_META_SUFFIX = ".meta"

        private const val KEY_SCHEMA = "schema"
        private const val KEY_REGION_ID = "regionId"
        private const val KEY_NAME = "name"
        private const val KEY_LAYER = "layer"
        private const val KEY_CENTER_LAT = "centerLat"
        private const val KEY_CENTER_LON = "centerLon"
        private const val KEY_RADIUS_KM = "radiusKm"
        private const val KEY_MIN_ZOOM = "minZoom"
        private const val KEY_MAX_ZOOM = "maxZoom"
        private const val KEY_WEST = "west"
        private const val KEY_SOUTH = "south"
        private const val KEY_EAST = "east"
        private const val KEY_NORTH = "north"
        private const val KEY_COMPLETED_AT = "completedAt"
        private const val KEY_PACKAGE_COUNT = "packageCount"

        private const val PKG_FILE = "file"
        private const val PKG_FORMAT = "format"
        private const val PKG_ROLE = "role"
        private const val PKG_OPACITY = "opacity"
        private const val PKG_SOURCE = "source"

        private const val WEB_MERCATOR_CIRCUMFERENCE_METERS = 40_075_016.686
        private const val KM_PER_DEGREE_LATITUDE = 111.32
        private const val TILE_PYRAMID_FACTOR = 1.34
        private const val MIN_PACKAGE_BYTES = 1_024L
        private const val PARALLEL_SPLIT_THRESHOLD_TILES = 6_000.0
        private const val MIN_PARALLEL_SPLITS_PER_AXIS = 2
        private const val MAX_SPLITS_PER_AXIS = 24
    }
}
