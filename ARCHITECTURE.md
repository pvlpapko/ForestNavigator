# ForestNavigator architecture

## Navigation core
- Android LocationManager GPS_PROVIDER is used for GNSS-only position fixes.
- Normal foreground navigation uses a moderate update cadence.
- Precise waypoint capture temporarily requests a higher cadence and averages multiple accepted fixes.
- Track recording uses a location foreground service so recording remains explicit and visible to the user.

## Power strategy
- NORMAL mode: 3 s / 2 m.
- ECO mode is available in the GNSS engine: 10 s / 5 m.
- PRECISION mode: 1 s / 0 m and is used only while refining a saved point.
- Sensor UI updates are throttled.
- Foreground Activity sensors are stopped when the Activity is paused unless a precise capture is active.

## Offline maps
MapLibre Native stores selected regions locally. Map, satellite, terrain and a custom style can be selected. Satellite/terrain providers that require an API key keep the key in local SharedPreferences rather than the repository.

## Adaptive UI
The Compose UI uses constraint-aware layouts, system safe insets, flexible map sizing, scrollable layer controls, two-row compact actions on narrow phones, and a NavigationRail on wide displays.

## Data
SQLite stores waypoints and track points locally. Mushroom places, car position and other waypoints remain available without internet.
