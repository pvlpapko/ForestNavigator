package app.forestnav.data

data class Waypoint(
    val id: Long,
    val type: WaypointType,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float?,
    val note: String,
    val createdAt: Long
)

enum class WaypointType { CAR, MUSHROOM, WATER, DANGER, FAVORITE, CUSTOM }

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val bearing: Float?,
    val time: Long
)

data class TrackSummary(
    val id: Long,
    val name: String,
    val startedAt: Long,
    val endedAt: Long?,
    val pointCount: Int
)
