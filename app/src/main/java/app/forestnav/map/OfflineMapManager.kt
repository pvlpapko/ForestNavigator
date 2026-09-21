package app.forestnav.map

import android.content.Context
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.cos

class OfflineMapManager(private val context: Context) {
    data class DownloadProgress(
        val regionId: Long? = null,
        val completedResources: Long = 0,
        val requiredResources: Long = 0,
        val bytes: Long = 0,
        val complete: Boolean = false,
        val error: String? = null
    )

    private val manager = OfflineManager.getInstance(context)

    fun downloadAround(
        name: String,
        styleUrl: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        minZoom: Double = 10.0,
        maxZoom: Double = 17.0,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val latDelta = radiusKm / 111.32
        val lonDelta = radiusKm / (111.32 * cos(Math.toRadians(latitude)).coerceAtLeast(0.2))
        val bounds = LatLngBounds.from(
            (latitude + latDelta).coerceAtMost(85.0),
            (longitude + lonDelta).coerceAtMost(180.0),
            (latitude - latDelta).coerceAtLeast(-85.0),
            (longitude - lonDelta).coerceAtLeast(-180.0)
        )
        val pixelRatio = context.resources.displayMetrics.density.coerceIn(1f, 2f)
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl, bounds, minZoom, maxZoom, pixelRatio, false
        )
        val metadata = name.toByteArray(Charsets.UTF_8)

        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        onProgress(
                            DownloadProgress(
                                regionId = offlineRegion.id,
                                completedResources = status.completedResourceCount,
                                requiredResources = status.requiredResourceCount,
                                bytes = status.completedResourceSize,
                                complete = status.isComplete
                            )
                        )
                        if (status.isComplete) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        }
                    }

                    override fun onError(error: OfflineRegionError) {
                        onProgress(DownloadProgress(regionId = offlineRegion.id, error = error.message))
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        onProgress(DownloadProgress(regionId = offlineRegion.id, error = "Превышен лимит офлайн-тайлов: $limit"))
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) {
                onProgress(DownloadProgress(error = error))
            }
        })
    }

    fun listRegions(onResult: (List<Pair<Long, String>>) -> Unit, onError: (String) -> Unit) {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                onResult(offlineRegions.orEmpty().map { it.id to it.metadata.toString(Charsets.UTF_8) })
            }

            override fun onError(error: String) = onError(error)
        })
    }

    fun deleteRegion(id: Long, onDone: () -> Unit, onError: (String) -> Unit) {
        manager.getOfflineRegion(id, object : OfflineManager.GetOfflineRegionCallback {
            override fun onRegion(offlineRegion: OfflineRegion) {
                offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = onDone()
                    override fun onError(error: String) = onError(error)
                })
            }
            override fun onError(error: String) = onError(error)
        })
    }
}
