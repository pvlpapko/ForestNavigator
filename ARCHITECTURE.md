# ForestNavigator architecture

## Positioning and navigation
- GNSS is read from Android `LocationManager.GPS_PROVIDER`.
- The UI receives a stabilized location stream; raw GNSS fixes remain available for precise waypoint averaging.
- Compass heading uses rotation-vector sensors when available and a filtered accelerometer/magnetometer fallback.
- ArcGIS receives the same stabilized location and heading source as the rest of the app, so the map and navigation marker do not compete for separate location providers.
- Track recording runs in a location foreground service and stores points in the local SQLite database.

## Map engine
Built-in map modes use ArcGIS Maps SDK for Kotlin 200.8.3:
- **Map** — `BasemapStyle.ArcGISStreets`.
- **Satellite** — `BasemapStyle.ArcGISImageryStandard`.
- **Relief** — `BasemapStyle.ArcGISStreetsRelief`.
- **Satellite + relief** — imagery plus an explicit hillshade overlay.
- **Custom map** remains isolated in the MapLibre renderer.

The ordinary map and relief map are deliberately different sources. Relief is never silently added to the ordinary map.

## Offline maps v3
Offline data lives only in `files/offline_maps_v3`.

Each download has:
- an isolated `.partial-<regionId>` staging directory;
- explicit metadata for map type, bounds, zoom range and source;
- one metadata sidecar per finished package;
- a final `manifest.properties` written only when the region is complete.

The renderer ignores partial directories and only opens schema-v3 final manifests. It also selects a single completed region containing the current GPS position, avoiding accidental stacking of unrelated downloaded regions.

### Package types
- Ordinary map: ArcGIS Streets **vector tiles** (`.vtpk`) via `ExportVectorTilesTask`.
- Satellite: raster tile packages (`.tpkx`) via `ExportTileCacheTask`.
- Relief: the same vector Streets base plus a separate raster hillshade package.
- Satellite + relief: imagery, hillshade and reference-label packages kept as separate roles.

The previous `arcgis_offline_v2` store is ignored and removed on the IO dispatcher after upgrading, so legacy relief/street packages cannot mix with the new store.

## Download concurrency
- Up to 6 packages from one region can download concurrently.
- Up to 8 ArcGIS export jobs run globally.
- Up to 2 regions can be active concurrently.
- Packages are split before export to stay below provider tile limits.
- Completed package files are reused when a paused or interrupted download resumes.
- Server-side ArcGIS jobs are tracked individually so pause/delete cancels every active export.

## Connectivity
The app enters online mode only when Android reports both:
- `NET_CAPABILITY_INTERNET`; and
- `NET_CAPABILITY_VALIDATED`.

Transient capability changes are debounced. A real loss of validated internet switches built-in maps to the local schema-v3 packages; GNSS and waypoint graphics remain independent of internet access.

## Data
Waypoints and tracks remain in the existing local SQLite database. Rebuilding the map subsystem does not delete saved car points, mushroom points, tracks, or user settings.
