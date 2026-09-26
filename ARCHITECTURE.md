# ForestNavigator architecture — 1.7.4

## Map rendering

Built-in rendering uses MapLibre.

- MAP: ArcGIS Static Basemap Tiles `open/osm-style`.
- SATELLITE: World Imagery raster base + ArcGIS Static Basemap Tiles `open/hybrid/detail` overlay. The imagery source has native max zoom 18 and is overzoomed beyond that level, preventing provider placeholder tiles at close scales.
- The app builds local style JSON itself; it does not depend on MapLibre parsing the remote Esri basemap style.
- Manual pan disables follow mode; «Я здесь» restores compass tracking.
- Initial camera zoom is tuned to roughly a 200 m reference scale on the target phone class.

## Offline storage

MapLibre OfflineManager is not used.

Each region lives under `files/offline_maps_v8/<regionId>` with:
- `region.properties`;
- one directory per tile source;
- `z/x/y.png` tile files.

Region states: ACTIVE, PAUSED, COMPLETE, ERROR.

Pause/resume/delete are ordinary coroutine/filesystem operations. There is no native offline-region database and therefore no native region delete/recreate race.

## Download engine

- Foreground data-sync service.
- 48 workers per region.
- OkHttp dispatcher: 96 total requests, 48 per host.
- satellite sources are interleaved in the queue so imagery and hybrid-detail hosts are saturated concurrently;
- bounded tile task channel;
- retries for transient failures and 429/5xx responses;
- completed files are reused on resume;
- 404/204 responses are recorded as skip markers;
- no per-request fsync, avoiding unnecessary storage stalls.

## User data

Waypoints and tracks remain in `forestnav.db` and are isolated from map cache migrations.


## Download geometry

The radius is geodesic from the current GPS fix in the four cardinal directions. A 2 km selection therefore defines a 4 km × 4 km square centered on the marker. Tile rows/columns that intersect that square are downloaded; the unavoidable excess is at most the outer tile boundaries.


## Initial map scale

`SettingsStore.initialMapScaleMeters` persists a user-selected reference scale (50–5000 m, default 500 m). MapLibre zoom is derived at runtime from latitude, view width and density using the same reference-pixel formula as the on-screen ↔ scale indicator. Startup and «Я здесь» recenter therefore use the configured scale instead of a hard-coded zoom.

## Large-region preparation

Expected tile count is computed from tile ranges mathematically. Resume statistics walk only existing PNG files. The downloader no longer probes every theoretical z/x/y path before starting, which avoids the multi-million filesystem-check stall seen with 50 km regions.


## Exact offline square v9

Offline schema 9 uses `offline_maps_v9`. Older v8 regions are discarded so their low-zoom background tiles cannot reappear.

The minimum downloaded zoom now scales with selected radius (15/14/13/12/11 for 2/5/10/25/50 km). Offline raster sources expose the same minzoom, so MapLibre does not render a coarse tile far outside the requested region when the user zooms out.

Boundary tiles are clipped to the geodesic square before final storage. Interior tiles are streamed byte-for-byte to disk; only boundary images are decoded/re-encoded as PNG with transparency outside the requested square.

## Throughput v1.7.3

- 64 coroutine tile workers per active region;
- OkHttp dispatcher: 128 total / 64 per host;
- connection pool up to 64 idle connections;
- 64 KiB streaming copy buffer;
- no per-tile fsync;
- progress remains throttled to avoid UI/notification overhead.


## Offline overview levels v10

Schema 10 stores every region from zoom 8 through its configured max zoom. The low levels add very few tiles relative to zoom 17–18 but prevent the raster source from disappearing when the camera zooms out.

Exact-square clipping remains active for every boundary tile at every zoom. Thus overview tiles are transparent outside the geodesic region instead of painting a large coarse rectangle around it.

v9 regions are invalidated on upgrade because they were created without the missing overview levels and cannot be repaired by style metadata alone.


## Map measurement

Measurement is transient UI state. MapScreen owns up to two latitude/longitude pairs and passes them to ForestMapView. MapLibre renders A/B markers plus a yellow Polyline. Distance is calculated geodesically through the existing Geo utility. Long-press waypoint creation remains independent.

## Live track overlay

TrackRecordingState exposes both recording state and the current route points. TrackRecordingService restores the active track from SQLite, appends accepted fixes to the DB and StateFlow, and ForestMapView renders them as a Polyline on both map styles.

The location foreground service owns its own GnssEngine, so MainActivity stopping foreground UI sensors on screen-off does not stop track recording. A PARTIAL_WAKE_LOCK is held only while recording. Active track id/state are persisted so START_STICKY recreation continues the same database track instead of starting a duplicate.

## Immediate download controls

OfflineMapDownloadState performs optimistic PAUSED/ACTIVE/remove updates from the ViewModel. OfflineMapDownloadService also tracks every live OkHttp Call per region. Pause/delete cancel those calls immediately and cancel the coroutine job, avoiding the previous wait for a 45-second read timeout. Resume requests received while a job is stopping are queued and relaunched from the job's finally block.


## 1.8.1 UI and GNSS hardening

Measurement owns an additional centered MapLibre marker whose icon is generated from the formatted geodesic distance. The bottom measurement text is shown only while A or B is still being selected.

The main navigation now contains MAP / POINTS / TRACKS / COMPASS / MORE. Track history comes from ForestDatabase.listTracks(); deletion is transactional across track_points and tracks. TrackRecordingState exposes the displayed track id so deleting the last completed track also clears its map overlay.

MainActivity owns one full-exit callback shared by the top map button and the double-back gesture. Full exit pauses offline downloads, stops track recording and UI sensors, removes the task and terminates the process.

GnssEngine now serializes registration on the main looper, tracks desired/started/status-registration state separately, rolls back partial LocationManager registration on exceptions, tolerates optional satellite-status registration failure, and retries transient RuntimeException startup failures after 1.2 seconds rather than crashing the app.


## 1.8.3 automatic download parallelism

The downloader no longer uses fixed constants for 64 workers / 128 total HTTP calls. At service startup it derives a process-safe concurrency budget from Runtime.maxMemory() and /proc/self/limits + /proc/self/fd.

35% of the app heap is available to active download work using a conservative per-request memory estimate. The file-descriptor budget reserves descriptors for SQLite, MapLibre, notifications and Android internals, then divides the remainder by the expected descriptors per active request. The lower of the heap and FD budgets becomes the automatic parallelism.

Workers run on Dispatchers.IO.limitedParallelism(automaticParallelism). The same value configures OkHttp maxRequests, maxRequestsPerHost and the connection pool. There is no fixed performance ceiling; only resource-derived protection against OOM/EMFILE process death.


## 1.8.4 offline-first rendering

When the current GPS fix is inside a completed offline region for the selected MapLayer, ForestMapView always selects an offline-first composite style regardless of ConnectivityManager state.

Each provider source is represented twice:
1. a local file:// raster source rendered first;
2. the corresponding remote raster source rendered above it.

Local layers use zero raster fade and therefore paint as soon as MapLibre reads the downloaded tile. Remote layers may request in parallel; until a remote tile exists, the local layer beneath remains visible. As remote tiles arrive they naturally replace the same screen area without a full style swap. Connectivity state changes therefore do not invalidate or rebuild a valid local map.

If no completed region covers the current GPS fix, the previous behavior remains: validated internet uses the online style and no internet uses the empty style.

Waypoint deletion is UI-confirmed before AppViewModel.deleteWaypoint() is invoked.


## 1.8.5 fast location bootstrap

GnssEngine now registers both GPS_PROVIDER and NETWORK_PROVIDER. It first publishes the best recent last-known fix (<=5 minutes old and <=250 m accuracy), then requests both providers concurrently.

NETWORK_PROVIDER is presentation bootstrap/fallback only. GPS updates populate rawLocation and update the display immediately; once a GPS fix is recent, network callbacks are ignored for 15 seconds. This gives fast indoor/cold-start map positioning without contaminating PreciseFixCollector, which still consumes only raw GPS fixes.

Provider registration remains individually guarded so an unavailable OEM network provider cannot break GPS startup.


## 1.8.6 durable offline-map deletion

OfflineMapManager persists deleted region IDs before filesystem cleanup. allMetas() and loadMeta() ignore tombstoned IDs, which also removes them from listRegions(), selectionFor(), activeOrPausedMetas() and service restore.

deleteRecursively() is therefore physical cleanup only. If it cannot remove every tile immediately, the tombstone remains durable and cleanupLegacyFiles() retries later. The ViewModel optimistically removes the completed region from StateFlow before IO cleanup begins.

The Offline UI requires explicit confirmation before deleting a completed map region.


## 1.8.7 persistent location architecture

LocationTrackingService is the sole owner of GnssEngine. It is a START_STICKY location foreground service started while MainActivity is foreground and fine-location permission is granted. The service owns a PARTIAL_WAKE_LOCK and persists an enabled flag so screen-off/background lifecycle transitions do not tear down GNSS acquisition.

LocationTrackingState exposes location, raw GPS location, satellite counts and service state to the UI and track recorder.

AppViewModel no longer constructs GnssEngine. Activity pause stops only the compass; GPS remains owned by the service. Precise waypoint collection temporarily switches the service to PRECISION mode and consumes the shared raw GPS flow.

TrackRecordingService consumes LocationTrackingState.location instead of starting a second LocationManager subscription.

GnssEngine startup now treats GPS registration as mandatory even when NETWORK_PROVIDER succeeds. If GPS requestLocationUpdates fails transiently, it retries rather than short-circuiting on the network provider. The first GPS fix after a network/cached bootstrap bypasses display smoothing so the true GNSS position becomes authoritative immediately.

The location notification exposes an explicit full-exit action. Full exit stops track recording and offline downloads, releases the location wake lock, removes the foreground notification and terminates the app process.
