package app.forestnav.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import kotlin.math.cos

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

    data class RegionMeta(
        val schema: Int,
        val name: String,
        val layer: MapLayer,
        val centerLat: Double,
        val centerLon: Double,
        val radiusKm: Double,
        val createdAt: Long
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val offlineManager = OfflineManager.getInstance(appContext)
    private val migrationPrefs = appContext.getSharedPreferences(
        MIGRATION_PREFS,
        Context.MODE_PRIVATE
    )

    init {
        runOnMain {
            configureFastDownloads()
        }
    }

    fun configureFastDownloads() {
        offlineManager.setOfflineMapboxTileCountLimit(Long.MAX_VALUE)
        offlineManager.runPackDatabaseAutomatically(false)
    }

    /**
     * Before the first v6 download, remove any MapLibre regions created by
     * older app generations. This is intentionally scoped to map cache only:
     * saved waypoints and tracks live in a separate SQLite database.
     */
    fun prepareCleanDatabase(
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        runOnMain {
            configureFastDownloads()

            if (migrationPrefs.getInt(KEY_MAP_SCHEMA, 0) >= MAP_SCHEMA) {
                onReady()
                return@runOnMain
            }

            offlineManager.listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        val regions = offlineRegions.orEmpty()
                        if (regions.isEmpty()) {
                            finishMigration(onReady)
                            return
                        }

                        var remaining = regions.size
                        var failed = false

                        regions.forEach { region ->
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            region.setObserver(null)
                            region.delete(
                                object : OfflineRegion.OfflineRegionDeleteCallback {
                                    override fun onDelete() {
                                        remaining -= 1
                                        if (remaining == 0 && !failed) {
                                            finishMigration(onReady)
                                        }
                                    }

                                    override fun onError(error: String) {
                                        if (!failed) {
                                            failed = true
                                            onError(error)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    override fun onError(error: String) {
                        onError(error)
                    }
                }
            )
        }
    }

    fun createRegion(
        name: String,
        layer: MapLayer,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        minZoom: Double,
        maxZoom: Double,
        onCreated: (OfflineRegion, RegionMeta) -> Unit,
        onError: (String) -> Unit
    ) {
        val styleUrl = MapStyles.styleUrl(layer)
        if (styleUrl == null) {
            onError("Ключ доступа к Open Basemap отсутствует.")
            return
        }

        runOnMain {
            val bounds = boundsFor(latitude, longitude, radiusKm)
            val definition = OfflineTilePyramidRegionDefinition(
                styleUrl,
                bounds,
                minZoom,
                maxZoom,
                appContext.resources.displayMetrics.density.coerceIn(1f, 2f),
                false
            )
            val meta = RegionMeta(
                schema = MAP_SCHEMA,
                name = name,
                layer = layer,
                centerLat = latitude,
                centerLon = longitude,
                radiusKm = radiusKm,
                createdAt = System.currentTimeMillis()
            )

            offlineManager.createOfflineRegion(
                definition,
                encodeMeta(meta),
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        onCreated(offlineRegion, meta)
                    }

                    override fun onError(error: String) {
                        onError(error)
                    }
                }
            )
        }
    }

    fun getRegion(
        regionId: Long,
        onSuccess: (OfflineRegion) -> Unit,
        onError: (String) -> Unit
    ) {
        runOnMain {
            offlineManager.getOfflineRegion(
                regionId,
                object : OfflineManager.GetOfflineRegionCallback {
                    override fun onRegion(offlineRegion: OfflineRegion) {
                        onSuccess(offlineRegion)
                    }

                    override fun onError(error: String) {
                        onError(error)
                    }
                }
            )
        }
    }

    fun listRegions(
        onSuccess: (List<Pair<Long, String>>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        runOnMain {
            offlineManager.listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        val values = offlineRegions
                            .orEmpty()
                            .mapNotNull { region ->
                                val meta = decodeMeta(region.metadata)
                                    ?: return@mapNotNull null
                                if (meta.schema != MAP_SCHEMA) {
                                    return@mapNotNull null
                                }
                                region.id to meta.name
                            }
                            .sortedByDescending { it.first }
                        onSuccess(values)
                    }

                    override fun onError(error: String) {
                        onError(IllegalStateException(error))
                    }
                }
            )
        }
    }

    fun deleteRegion(
        id: Long,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        getRegion(
            regionId = id,
            onSuccess = { region ->
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                region.setObserver(null)
                region.delete(
                    object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {
                            onSuccess()
                        }

                        override fun onError(error: String) {
                            onError(IllegalStateException(error))
                        }
                    }
                )
            },
            onError = {
                onError(IllegalStateException(it))
            }
        )
    }

    fun packDatabase() {
        runOnMain {
            offlineManager.packDatabase(null)
        }
    }

    fun decodeMeta(region: OfflineRegion): RegionMeta? =
        decodeMeta(region.metadata)

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

    private fun finishMigration(onReady: () -> Unit) {
        offlineManager.clearAmbientCache(null)
        migrationPrefs.edit()
            .putInt(KEY_MAP_SCHEMA, MAP_SCHEMA)
            .apply()
        onReady()
    }

    private fun boundsFor(
        latitude: Double,
        longitude: Double,
        radiusKm: Double
    ): LatLngBounds {
        val latDelta = radiusKm / KM_PER_DEGREE_LATITUDE
        val lonDelta = radiusKm / (
            KM_PER_DEGREE_LATITUDE *
                cos(Math.toRadians(latitude)).coerceAtLeast(0.2)
            )

        val north = (latitude + latDelta).coerceIn(-85.0, 85.0)
        val south = (latitude - latDelta).coerceIn(-85.0, 85.0)
        val east = (longitude + lonDelta).coerceIn(-180.0, 180.0)
        val west = (longitude - lonDelta).coerceIn(-180.0, 180.0)

        return LatLngBounds.from(
            north,
            east,
            south,
            west
        )
    }

    private fun encodeMeta(meta: RegionMeta): ByteArray =
        JSONObject()
            .put("schema", meta.schema)
            .put("name", meta.name)
            .put("layer", meta.layer.name)
            .put("centerLat", meta.centerLat)
            .put("centerLon", meta.centerLon)
            .put("radiusKm", meta.radiusKm)
            .put("createdAt", meta.createdAt)
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun decodeMeta(bytes: ByteArray): RegionMeta? =
        runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            RegionMeta(
                schema = json.optInt("schema", 0),
                name = json.optString("name", "Офлайн-карта"),
                layer = MapLayer.valueOf(
                    json.optString("layer", MapLayer.MAP.name)
                ),
                centerLat = json.optDouble("centerLat", 0.0),
                centerLon = json.optDouble("centerLon", 0.0),
                radiusKm = json.optDouble("radiusKm", 0.0),
                createdAt = json.optLong("createdAt", 0L)
            )
        }.getOrNull()

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
    }

    companion object {
        const val MAP_SCHEMA = 6

        private const val MIGRATION_PREFS = "offline_map_schema"
        private const val KEY_MAP_SCHEMA = "schema"
        private const val KM_PER_DEGREE_LATITUDE = 111.32

        private val LEGACY_ROOT_DIRS = arrayOf(
            "arcgis_offline_v2",
            "offline_maps_v3",
            "offline_maps_v4"
        )

        private val LEGACY_DOWNLOAD_PREFS = arrayOf(
            "arcgis_offline_jobs",
            "arcgis_offline_jobs_v3",
            "arcgis_offline_jobs_v4"
        )
    }
}
