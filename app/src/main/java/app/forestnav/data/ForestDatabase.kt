package app.forestnav.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ForestDatabase(context: Context) : SQLiteOpenHelper(context, "forestnav.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE waypoints(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                name TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                altitude REAL,
                accuracy REAL,
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE tracks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE track_points(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                altitude REAL,
                accuracy REAL,
                speed REAL,
                bearing REAL,
                time INTEGER NOT NULL,
                FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_track_points_track ON track_points(track_id)")
        db.execSQL("CREATE INDEX idx_waypoints_type ON waypoints(type)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insertWaypoint(
        type: WaypointType,
        name: String,
        lat: Double,
        lon: Double,
        altitude: Double?,
        accuracy: Float?,
        note: String
    ): Long {
        val values = ContentValues().apply {
            put("type", type.name)
            put("name", name)
            put("lat", lat)
            put("lon", lon)
            altitude?.let { put("altitude", it) }
            accuracy?.let { put("accuracy", it) }
            put("note", note)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("waypoints", null, values)
    }

    fun listWaypoints(): List<Waypoint> {
        val out = mutableListOf<Waypoint>()
        readableDatabase.query(
            "waypoints", null, null, null, null, null, "created_at DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out += Waypoint(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    type = WaypointType.valueOf(c.getString(c.getColumnIndexOrThrow("type"))),
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    latitude = c.getDouble(c.getColumnIndexOrThrow("lat")),
                    longitude = c.getDouble(c.getColumnIndexOrThrow("lon")),
                    altitude = c.columnOrNullDouble("altitude"),
                    accuracyMeters = c.columnOrNullFloat("accuracy"),
                    note = c.getString(c.getColumnIndexOrThrow("note")),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return out
    }

    fun deleteWaypoint(id: Long) {
        writableDatabase.delete("waypoints", "id=?", arrayOf(id.toString()))
    }

    fun beginTrack(name: String): Long {
        val v = ContentValues().apply {
            put("name", name)
            put("started_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("tracks", null, v)
    }

    fun appendTrackPoint(trackId: Long, p: TrackPoint) {
        val v = ContentValues().apply {
            put("track_id", trackId)
            put("lat", p.latitude)
            put("lon", p.longitude)
            p.altitude?.let { put("altitude", it) }
            p.accuracyMeters?.let { put("accuracy", it) }
            p.speedMps?.let { put("speed", it) }
            p.bearing?.let { put("bearing", it) }
            put("time", p.time)
        }
        writableDatabase.insertOrThrow("track_points", null, v)
    }

    fun endTrack(trackId: Long) {
        val v = ContentValues().apply { put("ended_at", System.currentTimeMillis()) }
        writableDatabase.update("tracks", v, "id=?", arrayOf(trackId.toString()))
    }

    fun listTracks(): List<TrackSummary> {
        val sql = """
            SELECT t.id,t.name,t.started_at,t.ended_at,COUNT(p.id) AS point_count
            FROM tracks t LEFT JOIN track_points p ON p.track_id=t.id
            GROUP BY t.id ORDER BY t.started_at DESC
        """.trimIndent()
        val out = mutableListOf<TrackSummary>()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out += TrackSummary(
                    c.getLong(0), c.getString(1), c.getLong(2),
                    if (c.isNull(3)) null else c.getLong(3), c.getInt(4)
                )
            }
        }
        return out
    }

    fun deleteTrack(trackId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                "track_points",
                "track_id=?",
                arrayOf(trackId.toString())
            )
            db.delete(
                "tracks",
                "id=?",
                arrayOf(trackId.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun trackPoints(trackId: Long): List<TrackPoint> {
        val out = mutableListOf<TrackPoint>()
        readableDatabase.query("track_points", null, "track_id=?", arrayOf(trackId.toString()), null, null, "time ASC").use { c ->
            while (c.moveToNext()) {
                out += TrackPoint(
                    latitude = c.getDouble(c.getColumnIndexOrThrow("lat")),
                    longitude = c.getDouble(c.getColumnIndexOrThrow("lon")),
                    altitude = c.columnOrNullDouble("altitude"),
                    accuracyMeters = c.columnOrNullFloat("accuracy"),
                    speedMps = c.columnOrNullFloat("speed"),
                    bearing = c.columnOrNullFloat("bearing"),
                    time = c.getLong(c.getColumnIndexOrThrow("time"))
                )
            }
        }
        return out
    }

    private fun android.database.Cursor.columnOrNullDouble(name: String): Double? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getDouble(i)
    }

    private fun android.database.Cursor.columnOrNullFloat(name: String): Float? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getFloat(i)
    }
}
