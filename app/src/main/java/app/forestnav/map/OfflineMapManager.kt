package app.forestnav.map

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

class OfflineMapManager(context: Context) {

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

    enum class RegionStatus {
        ACTIVE,
        PAUSED,
        COMPLETE,
        ERROR
    }

    data class RegionMeta(
        val id: Long,
        val name: String,
        val layer: MapLayer,
        val centerLat: Double,
        val centerLon: Double,
        val radiusKm: Double,
        val minZoom: Int,
        val maxZoom: Int,
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
        val status: RegionStatus,
        val createdAt: Long
    )

    data class TileTask(
        val source: TileSourceSpec,
        val z: Int,
        val x: Int,
        val y: Int,
        val file: File
    )

    data class OfflineSelection(
        val meta: RegionMeta,
        val style: MapStyleSpec
    )

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, ROOT_DIR).apply { mkdirs() }

    fun cleanupLegacyFiles() {
        LEGACY_ROOT_DIRS.forEach { directory ->
            File(appContext.filesDir, directory).deleteRecursively()
        }

        LEGACY_DOWNLOAD_PREFS.forEach { preferences ->
            appContext.getSharedPreferences(
                preferences,
                Context.MODE_PRIVATE
            ).edit().clear().apply()
        }
    }

    fun createRegion(
        name: String,
        layer: MapLayer,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        minZoom: Int,
        maxZoom: Int
    ): RegionMeta {
        val id = nextRegionId()
        val bounds = boundsFor(latitude, longitude, radiusKm)

        val meta = RegionMeta(
            id = id,
            name = name,
            layer = layer,
            centerLat = latitude,
            centerLon = longitude,
            radiusKm = radiusKm,
            minZoom = minZoom,
            maxZoom = maxZoom,
            west = bounds[0],
            south = bounds[1],
            east = bounds[2],
            north = bounds[3],
            status = RegionStatus.ACTIVE,
            createdAt = System.currentTimeMillis()
        )

        regionDir(id).mkdirs()
        saveMeta(meta)
        return meta
    }

    fun loadMeta(id: Long): RegionMeta? =
        readMeta(File(regionDir(id), META_FILE))

    fun updateStatus(
        id: Long,
        status: RegionStatus
    ): RegionMeta? {
        val current = loadMeta(id) ?: return null
        val updated = current.copy(status = status)
        saveMeta(updated)
        return updated
    }

    fun deleteRegion(
        id: Long,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        runCatching {
            regionDir(id).deleteRecursively()
        }.onSuccess {
            onSuccess()
        }.onFailure(onError)
    }

    fun listRegions(
        onSuccess: (List<Pair<Long, String>>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        runCatching {
            allMetas()
                .filter { it.status == RegionStatus.COMPLETE }
                .sortedByDescending { it.createdAt }
                .map { it.id to it.name }
        }.onSuccess(onSuccess).onFailure(onError)
    }

    fun activeOrPausedMetas(): List<RegionMeta> =
        allMetas()
            .filter {
                it.status == RegionStatus.ACTIVE ||
                    it.status == RegionStatus.PAUSED ||
                    it.status == RegionStatus.ERROR
            }
            .sortedByDescending { it.createdAt }

    fun totalTiles(meta: RegionMeta): Long {
        val sources = MapStyles.tileSources(meta.layer)
        var total = 0L

        for (z in meta.minZoom..meta.maxZoom) {
            val range = tileRange(meta, z)
            val width = range[1] - range[0] + 1
            val height = range[3] - range[2] + 1
            val activeSources = sources.count {
                z <= it.nativeMaxZoom
            }
            total +=
                width.toLong() *
                    height.toLong() *
                    activeSources.toLong()
        }

        return total
    }

    fun tileTasks(meta: RegionMeta): Sequence<TileTask> = sequence {
        val dir = regionDir(meta.id)
        val sources = MapStyles.tileSources(meta.layer)

        // Interleave sources per tile. Satellite downloads can therefore use
        // the imagery host and the Hybrid Detail host at the same time instead
        // of finishing one complete source before starting the other.
        for (z in meta.minZoom..meta.maxZoom) {
            val range = tileRange(meta, z)
            val activeSources = sources.filter {
                z <= it.nativeMaxZoom
            }

            for (x in range[0]..range[1]) {
                for (y in range[2]..range[3]) {
                    for (source in activeSources) {
                        yield(
                            TileTask(
                                source = source,
                                z = z,
                                x = x,
                                y = y,
                                file = File(
                                    dir,
                                    "${source.id}/$z/$x/$y.png"
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    fun completedStats(meta: RegionMeta): Pair<Long, Long> {
        val dir = regionDir(meta.id)
        if (!dir.isDirectory) return 0L to 0L

        var count = 0L
        var bytes = 0L

        // Scan only files that actually exist. The old implementation walked
        // every theoretical z/x/y tile path, which can mean millions of
        // filesystem checks for a 50 km radius before the first request starts.
        dir.walkTopDown().forEach { file ->
            if (
                file.isFile &&
                file.extension.equals("png", ignoreCase = true) &&
                file.length() > MIN_TILE_BYTES
            ) {
                count += 1L
                bytes += file.length()
            }
        }

        return count to bytes
    }

    fun markComplete(id: Long): RegionMeta? =
        updateStatus(id, RegionStatus.COMPLETE)

    fun selectionFor(
        layer: MapLayer,
        latitude: Double,
        longitude: Double
    ): OfflineSelection? {
        val meta = allMetas()
            .asSequence()
            .filter { it.status == RegionStatus.COMPLETE }
            .filter { it.layer == layer }
            .filter {
                latitude in it.south..it.north &&
                    longitude in it.west..it.east
            }
            .maxByOrNull { it.createdAt }
            ?: return null

        val style = MapStyles.offlineStyle(
            layer = meta.layer,
            regionId = meta.id,
            regionDirectory = regionDir(meta.id),
            maxDownloadedZoom = meta.maxZoom
        ) ?: return null

        return OfflineSelection(meta, style)
    }

    fun regionDirectory(id: Long): File =
        regionDir(id)

    private fun saveMeta(meta: RegionMeta) {
        val props = Properties().apply {
            setProperty("schema", SCHEMA.toString())
            setProperty("id", meta.id.toString())
            setProperty("name", meta.name)
            setProperty("layer", meta.layer.name)
            setProperty("centerLat", meta.centerLat.toString())
            setProperty("centerLon", meta.centerLon.toString())
            setProperty("radiusKm", meta.radiusKm.toString())
            setProperty("minZoom", meta.minZoom.toString())
            setProperty("maxZoom", meta.maxZoom.toString())
            setProperty("west", meta.west.toString())
            setProperty("south", meta.south.toString())
            setProperty("east", meta.east.toString())
            setProperty("north", meta.north.toString())
            setProperty("status", meta.status.name)
            setProperty("createdAt", meta.createdAt.toString())
        }

        val target = File(regionDir(meta.id), META_FILE)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")

        FileOutputStream(temp).use {
            props.store(it, "ForestNavigator direct offline tiles")
            it.fd.sync()
        }

        if (target.exists()) target.delete()
        check(temp.renameTo(target)) {
            "Не удалось сохранить метаданные офлайн-карты"
        }
    }

    private fun readMeta(file: File): RegionMeta? =
        runCatching {
            val props = Properties()
            FileInputStream(file).use { props.load(it) }

            if (props.getProperty("schema")?.toIntOrNull() != SCHEMA) {
                return@runCatching null
            }

            RegionMeta(
                id = props.getProperty("id").toLong(),
                name = props.getProperty("name"),
                layer = MapLayer.valueOf(props.getProperty("layer")),
                centerLat = props.getProperty("centerLat").toDouble(),
                centerLon = props.getProperty("centerLon").toDouble(),
                radiusKm = props.getProperty("radiusKm").toDouble(),
                minZoom = props.getProperty("minZoom").toInt(),
                maxZoom = props.getProperty("maxZoom").toInt(),
                west = props.getProperty("west").toDouble(),
                south = props.getProperty("south").toDouble(),
                east = props.getProperty("east").toDouble(),
                north = props.getProperty("north").toDouble(),
                status = RegionStatus.valueOf(props.getProperty("status")),
                createdAt = props.getProperty("createdAt").toLong()
            )
        }.getOrNull()

    private fun allMetas(): List<RegionMeta> =
        root.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { readMeta(File(it, META_FILE)) }

    private fun regionDir(id: Long): File =
        File(root, id.toString())

    private fun nextRegionId(): Long {
        var id = System.currentTimeMillis()
        while (regionDir(id).exists()) {
            id += 1L
        }
        return id
    }

    private fun tileRange(
        meta: RegionMeta,
        z: Int
    ): IntArray {
        val n = 1 shl z

        val xMin = lonToTileX(meta.west, z)
            .coerceIn(0, n - 1)
        val xMax = lonToTileX(meta.east, z)
            .coerceIn(0, n - 1)
        val yMin = latToTileY(meta.north, z)
            .coerceIn(0, n - 1)
        val yMax = latToTileY(meta.south, z)
            .coerceIn(0, n - 1)

        return intArrayOf(
            minOf(xMin, xMax),
            maxOf(xMin, xMax),
            minOf(yMin, yMax),
            maxOf(yMin, yMax)
        )
    }

    private fun lonToTileX(
        longitude: Double,
        z: Int
    ): Int {
        val n = 1 shl z
        return floor(
            (longitude + 180.0) / 360.0 * n
        ).toInt()
    }

    private fun latToTileY(
        latitude: Double,
        z: Int
    ): Int {
        val lat = latitude.coerceIn(-85.05112878, 85.05112878)
        val latRad = Math.toRadians(lat)
        val n = 1 shl z

        return floor(
            (
                1.0 -
                    ln(
                        tan(latRad) +
                            1.0 / cos(latRad)
                    ) / PI
                ) / 2.0 * n
        ).toInt()
    }

    private fun boundsFor(
        latitude: Double,
        longitude: Double,
        radiusKm: Double
    ): DoubleArray {
        val distanceMeters = radiusKm * 1_000.0

        // The selected value is a true radius from the GPS marker:
        // 2 km => 2 km north, south, east and west => a 4 x 4 km square.
        val north = destinationPoint(
            latitude,
            longitude,
            distanceMeters,
            0.0
        )
        val east = destinationPoint(
            latitude,
            longitude,
            distanceMeters,
            90.0
        )
        val south = destinationPoint(
            latitude,
            longitude,
            distanceMeters,
            180.0
        )
        val west = destinationPoint(
            latitude,
            longitude,
            distanceMeters,
            270.0
        )

        return doubleArrayOf(
            west.second.coerceIn(-180.0, 180.0),
            south.first.coerceIn(-85.05112878, 85.05112878),
            east.second.coerceIn(-180.0, 180.0),
            north.first.coerceIn(-85.05112878, 85.05112878)
        )
    }

    private fun destinationPoint(
        latitude: Double,
        longitude: Double,
        distanceMeters: Double,
        bearingDegrees: Double
    ): Pair<Double, Double> {
        val angularDistance =
            distanceMeters / EARTH_RADIUS_METERS
        val bearing =
            Math.toRadians(bearingDegrees)
        val lat1 =
            Math.toRadians(latitude)
        val lon1 =
            Math.toRadians(longitude)

        val lat2 = asin(
            sin(lat1) * cos(angularDistance) +
                cos(lat1) *
                sin(angularDistance) *
                cos(bearing)
        )

        val lon2 = lon1 + atan2(
            sin(bearing) *
                sin(angularDistance) *
                cos(lat1),
            cos(angularDistance) -
                sin(lat1) * sin(lat2)
        )

        val normalizedLon =
            Math.toDegrees(lon2)
                .let { value ->
                    ((value + 540.0) % 360.0) - 180.0
                }

        return Math.toDegrees(lat2) to normalizedLon
    }

    companion object {
        private const val SCHEMA = 8
        private const val ROOT_DIR = "offline_maps_v8"
        private const val META_FILE = "region.properties"
        private const val MIN_TILE_BYTES = 128L
        private const val EARTH_RADIUS_METERS = 6_371_008.8

        private val LEGACY_ROOT_DIRS = arrayOf(
            "arcgis_offline_v2",
            "offline_maps_v3",
            "offline_maps_v4",
            "map_styles_v7"
        )

        private val LEGACY_DOWNLOAD_PREFS = arrayOf(
            "arcgis_offline_jobs",
            "arcgis_offline_jobs_v3",
            "arcgis_offline_jobs_v4",
            "maplibre_offline_jobs_v6"
        )
    }
}
