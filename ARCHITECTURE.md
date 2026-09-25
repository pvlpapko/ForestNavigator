# ForestNavigator architecture — 1.7.1

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
