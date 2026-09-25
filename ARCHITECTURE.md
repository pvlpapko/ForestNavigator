# ForestNavigator architecture — 1.6.0

## Map rendering

The built-in map stack is MapLibre-only. ArcGIS Maps SDK is no longer a runtime dependency.

- `MapLayer.MAP` → ArcGIS Basemap Styles service `styles/open/osm-style`.
- `MapLayer.SATELLITE` → `styles/open/hybrid`.
- Both online and offline rendering use the same style URL, preventing mismatched map content.
- Relief and satellite+relief modes were removed.
- The MapLibre location component receives the app's stabilized GNSS location and uses compass tracking.
- A manual map gesture releases camera follow; recenter restores tracking.

## Offline maps

Offline downloads use MapLibre `OfflineManager` and `OfflineTilePyramidRegionDefinition`.

- The default 6000-tile ceiling is raised to `Long.MAX_VALUE`.
- Automatic DB packing is disabled while downloading and a pack is requested after completion.
- The offline service stores region metadata with schema 6.
- Old ArcGIS file stores and job preferences are deleted separately from user data.
- Before the first schema-6 download, old MapLibre offline regions are removed.
- Progress comes from `OfflineRegionStatus`: completed resources, required resources and completed bytes.
- Pause/resume maps directly to `OfflineRegion.STATE_INACTIVE/STATE_ACTIVE`.
- The foreground service restores active region IDs after process recreation.

## Network throughput

MapLibre's HTTP layer uses a dedicated OkHttp client:
- max requests: 64;
- max requests per host: 32;
- retry on connection failure enabled;
- no application bandwidth throttle.

This removes the previous server-side ArcGIS export workflow and its package-generation bottleneck. Provider-side throttling and physical network throughput still apply.

## Positioning and user data

- GNSS uses Android `LocationManager.GPS_PROVIDER`.
- Display position is filtered; precise waypoint capture uses raw fixes.
- Compass uses device sensors with filtering.
- Waypoints and tracks remain in the existing `forestnav.db` SQLite database.
- Map-cache migration never deletes that database.
