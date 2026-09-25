# ForestNavigator architecture — 1.7.0

## Map rendering

Built-in rendering uses MapLibre.

- MAP: ArcGIS Static Basemap Tiles `open/osm-style`.
- SATELLITE: World Imagery raster base + ArcGIS Static Basemap Tiles `open/hybrid/detail` overlay.
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
- 32 workers per region.
- OkHttp dispatcher: 64 total requests, 32 per host.
- bounded tile task channel;
- retries for transient failures and 429/5xx responses;
- completed files are reused on resume;
- 404/204 responses are recorded as skip markers;
- no per-request fsync, avoiding unnecessary storage stalls.

## User data

Waypoints and tracks remain in `forestnav.db` and are isolated from map cache migrations.
