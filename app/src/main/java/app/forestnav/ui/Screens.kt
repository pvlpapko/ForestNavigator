package app.forestnav.ui

import android.hardware.GeomagneticField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.forestnav.data.Waypoint
import app.forestnav.data.WaypointType
import app.forestnav.gnss.SensorCapabilities
import app.forestnav.map.MapLayer
import app.forestnav.map.MapStyles
import app.forestnav.service.TrackRecordingService
import app.forestnav.service.TrackRecordingState
import app.forestnav.util.Geo
import kotlin.math.roundToInt

@Composable
fun MapScreen(vm: AppViewModel) {
    val location by vm.location.collectAsState()
    val satellites by vm.satellites.collectAsState()
    val waypoints by vm.waypoints.collectAsState()
    val layer by vm.mapLayer.collectAsState()
    val precise by vm.preciseState.collectAsState()
    val navigationTarget by vm.navigationTarget.collectAsState()
    val heading by vm.heading.collectAsState()
    val online by vm.online.collectAsState()
    val recording by TrackRecordingState.recording.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var recenterToken by remember { mutableIntStateOf(0) }
    var pendingMapPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var pointPlacementMode by remember { mutableStateOf(false) }
    var selectedMapWaypoint by remember { mutableStateOf<Waypoint?>(null) }
    var mapScaleMeters by remember { mutableDoubleStateOf(0.0) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val short = maxHeight < 560.dp
        val wide = maxWidth >= 600.dp

        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = if (short) 4.dp else 8.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MapLayer.entries.forEach { item ->
                        FilterChip(
                            selected = layer == item,
                            onClick = { vm.setLayer(item) },
                            label = { Text(item.title) },
                            leadingIcon = if (layer == item) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }

                Text(
                    MapStyles.description(layer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(if (short) 4.dp else 8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.GpsFixed, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (location == null) "Поиск спутников…"
                            else "GPS ±${location!!.accuracy.roundToInt()} м",
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${satellites.usedInFix}/${satellites.visible} спутн.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (location == null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Определяем ваше местоположение…",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Карта откроется сразу возле вашей GPS-метки.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    val mapClick: (Double, Double) -> Unit = { lat, lon ->
                        pendingMapPoint = lat to lon
                        pointPlacementMode = false
                    }
                    val waypointClick: (Waypoint) -> Unit = { waypoint ->
                        selectedMapWaypoint = waypoint
                        pointPlacementMode = false
                    }

                    if (layer == MapLayer.CUSTOM) {
                        CustomMapLibreView(
                            modifier = Modifier.fillMaxSize(),
                            location = location!!,
                            heading = heading,
                            waypoints = waypoints,
                            styleUrl = vm.styleUrl(online),
                            recenterToken = recenterToken,
                            pointPlacementEnabled = pointPlacementMode,
                            onMapClick = mapClick,
                            onMapLongPress = mapClick,
                            onWaypointClick = waypointClick,
                            onMapScaleChanged = { mapScaleMeters = it }
                        )
                    } else {
                        ForestMapView(
                            modifier = Modifier.fillMaxSize(),
                            location = location!!,
                            heading = heading,
                            waypoints = waypoints,
                            layer = layer,
                            online = online,
                            recenterToken = recenterToken,
                            pointPlacementEnabled = pointPlacementMode,
                            onMapClick = mapClick,
                            onMapLongPress = mapClick,
                            onWaypointClick = waypointClick,
                            onMapScaleChanged = { mapScaleMeters = it }
                        )
                    }
                }

                if (location != null) {
                    val altitudeLabel = if (location!!.hasAltitude()) {
                        "${location!!.altitude.roundToInt()} м"
                    } else {
                        "—"
                    }
                    Text(
                        text = "↑ $altitudeLabel\n↔ ${mapScaleLabel(mapScaleMeters)}",
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = 58.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            shadow = Shadow(
                                color = Color.Black,
                                offset = Offset(0f, 1.5f),
                                blurRadius = 4f
                            )
                        )
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (navigationTarget != null && location != null) {
                        NavigationMapCard(
                            target = navigationTarget!!,
                            latitude = location!!.latitude,
                            longitude = location!!.longitude,
                            altitude = if (location!!.hasAltitude()) location!!.altitude else 0.0,
                            sensorHeading = heading,
                            gpsBearing = if (location!!.hasBearing()) location!!.bearing else null,
                            onStop = { vm.setNavigationTarget(null) }
                        )
                    }

                    selectedMapWaypoint?.let { waypoint ->
                        WaypointMapCard(
                            waypoint = waypoint,
                            isActiveTarget = navigationTarget?.id == waypoint.id,
                            onNavigate = {
                                vm.setNavigationTarget(waypoint)
                                selectedMapWaypoint = null
                            },
                            onClose = { selectedMapWaypoint = null }
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, bottom = 0.dp),
                    color = Color.Transparent,
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (precise.active) {
                            Text(
                                "Уточняем: ${precise.label}",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Измерений: ${precise.samples} • лучший фикс: " +
                                    (precise.bestAccuracy?.let { "±${String.format("%.1f", it)} м" } ?: "—"),
                                style = MaterialTheme.typography.bodySmall
                            )
                            LinearProgressIndicator(
                                progress = { (precise.samples / 30f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextButton(onClick = vm::cancelPrecise) { Text("Отмена") }
                        } else {
                            if (pointPlacementMode) {
                                Text(
                                    "Коснитесь нужного места на карте",
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                SmallAction(
                                    Modifier.weight(1f),
                                    Icons.Default.DirectionsCar,
                                    "Машина",
                                    location != null
                                ) { vm.savePrecise(WaypointType.CAR, "Машина") }

                                SmallAction(
                                    Modifier.weight(1f),
                                    Icons.Default.Forest,
                                    "Грибы",
                                    location != null
                                ) {
                                    vm.savePrecise(
                                        WaypointType.MUSHROOM,
                                        "Грибное место ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
                                    )
                                }

                                SmallAction(
                                    Modifier.weight(1f),
                                    if (recording) Icons.Default.Stop else Icons.Default.Route,
                                    if (recording) "Стоп" else "Трек",
                                    location != null
                                ) {
                                    if (recording) TrackRecordingService.stop(context)
                                    else TrackRecordingService.start(context)
                                }

                                SmallAction(
                                    Modifier.weight(1f),
                                    Icons.Default.AddLocationAlt,
                                    if (pointPlacementMode) "Отмена" else "Точка",
                                    location != null,
                                    selected = pointPlacementMode
                                ) {
                                    pointPlacementMode = !pointPlacementMode
                                }

                                SmallAction(
                                    Modifier.weight(1f),
                                    Icons.Default.MyLocation,
                                    "Я здесь",
                                    location != null
                                ) {
                                    pointPlacementMode = false
                                    recenterToken++
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    pendingMapPoint?.let { point ->
        MapPointDialog(
            latitude = point.first,
            longitude = point.second,
            onDismiss = { pendingMapPoint = null },
            onSave = { type, name ->
                vm.saveMapPoint(type, name, point.first, point.second)
                pendingMapPoint = null
            }
        )
    }
}

private fun mapScaleLabel(meters: Double): String {
    if (meters <= 0.0) return "—"
    return when {
        meters < 10.0 -> String.format(java.util.Locale.getDefault(), "%.1f м", meters)
        meters < 1000.0 -> "${meters.roundToInt()} м"
        else -> {
            val km = meters / 1000.0
            if (km < 10.0) String.format(java.util.Locale.getDefault(), "%.1f км", km)
            else "${km.roundToInt()} км"
        }
    }
}

@Composable
private fun WaypointMapCard(
    waypoint: Waypoint,
    isActiveTarget: Boolean,
    onNavigate: () -> Unit,
    onClose: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                pointIcon(waypoint.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    waypoint.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    waypoint.accuracyMeters?.let {
                        "Сохранённая точка • ±${String.format("%.1f", it)} м"
                    } ?: "Сохранённая точка на карте",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!isActiveTarget) {
                FilledTonalButton(onClick = onNavigate) {
                    Icon(Icons.Default.Navigation, null)
                    Spacer(Modifier.width(4.dp))
                    Text("К точке")
                }
            } else {
                AssistChip(
                    onClick = {},
                    label = { Text("Маршрут активен") },
                    leadingIcon = { Icon(Icons.Default.Navigation, null) }
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Закрыть")
            }
        }
    }
}

@Composable
private fun NavigationMapCard(
    target: Waypoint,
    latitude: Double,
    longitude: Double,
    altitude: Double,
    sensorHeading: Float?,
    gpsBearing: Float?,
    onStop: () -> Unit
) {
    var showStopDialog by remember { mutableStateOf(false) }

    val bearing = Geo.bearingDegrees(latitude, longitude, target.latitude, target.longitude)
    val distance = Geo.distanceMeters(latitude, longitude, target.latitude, target.longitude)

    val declination = remember(latitude, longitude, altitude) {
        GeomagneticField(
            latitude.toFloat(),
            longitude.toFloat(),
            altitude.toFloat(),
            System.currentTimeMillis()
        ).declination
    }

    val trueHeading = sensorHeading?.let { (it + declination + 360f) % 360f }
        ?: gpsBearing?.let { (it + 360f) % 360f }

    val arrowRotation = if (trueHeading != null) {
        (bearing - trueHeading + 360f) % 360f
    } else {
        bearing
    }

    Column(
        modifier = Modifier
            .clickable { showStopDialog = true }
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Icon(
            Icons.Default.Navigation,
            contentDescription = "Направление к точке",
            modifier = Modifier
                .size(112.dp)
                .rotate(arrowRotation),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            Geo.distanceLabel(distance),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFFFF2D2D)
        )
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Навигация к точке") },
            text = {
                Text("Расстояние: ${Geo.distanceLabel(distance)}. Остановить следование к этой точке?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopDialog = false
                        onStop()
                    }
                ) {
                    Text("Остановить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) {
                    Text("Продолжить")
                }
            }
        )
    }
}

@Composable
private fun SmallAction(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    FilledTonalButton(
        modifier = modifier.height(46.dp),
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
        colors = if (selected) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun MapPointDialog(
    latitude: Double,
    longitude: Double,
    onDismiss: () -> Unit,
    onSave: (WaypointType, String) -> Unit
) {
    var type by remember { mutableStateOf(WaypointType.CUSTOM) }
    var name by remember { mutableStateOf("Точка на карте") }

    val types = listOf(
        WaypointType.MUSHROOM to "Грибы",
        WaypointType.CAR to "Машина",
        WaypointType.FAVORITE to "Избранное",
        WaypointType.WATER to "Вода",
        WaypointType.DANGER to "Опасность",
        WaypointType.CUSTOM to "Другое"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.AddLocationAlt, null) },
        title = { Text("Поставить точку") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${String.format("%.6f", latitude)}, ${String.format("%.6f", longitude)}",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    types.forEach { (item, label) ->
                        FilterChip(
                            selected = type == item,
                            onClick = {
                                type = item
                                name = when (item) {
                                    WaypointType.MUSHROOM -> "Грибное место"
                                    WaypointType.CAR -> "Машина"
                                    WaypointType.FAVORITE -> "Избранное место"
                                    WaypointType.WATER -> "Вода"
                                    WaypointType.DANGER -> "Опасность"
                                    WaypointType.CUSTOM -> "Точка на карте"
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название") },
                    singleLine = true
                )

                Text(
                    "Координаты берутся из выбранного места на карте, а не из текущей GPS-позиции.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(type, name.ifBlank { "Точка на карте" }) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
fun PointsScreen(vm: AppViewModel) {
    val points by vm.waypoints.collectAsState()
    val target by vm.navigationTarget.collectAsState()

    if (points.isEmpty()) {
        EmptyState(
            Icons.Default.Place,
            "Точек пока нет",
            "Сохрани машину, грибное место или другую точку на карте."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Сохранённые точки", style = MaterialTheme.typography.headlineSmall)
        }

        items(points, key = { it.id }) { p ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(pointIcon(p.type), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${String.format("%.6f", p.latitude)}, " +
                                    "${String.format("%.6f", p.longitude)} • " +
                                    (p.accuracyMeters?.let { "±${String.format("%.1f", it)} м" } ?: "точность —"),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (p.note.isNotBlank()) {
                                Text(
                                    p.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                vm.setNavigationTarget(
                                    if (target?.id == p.id) null else p
                                )
                            }
                        ) {
                            Icon(Icons.Default.Navigation, null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (target?.id == p.id) "Отменить" else "К точке")
                        }

                        TextButton(onClick = { vm.deleteWaypoint(p.id) }) {
                            Icon(Icons.Default.DeleteOutline, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Удалить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompassScreen(vm: AppViewModel) {
    val loc by vm.location.collectAsState()
    val heading by vm.heading.collectAsState()
    val target by vm.navigationTarget.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!vm.compass.available) {
            Icon(Icons.Default.ExploreOff, null, Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "В телефоне нет доступного датчика направления",
                style = MaterialTheme.typography.titleMedium
            )
            Text("При движении направление можно оценивать по GPS-треку.")
            return
        }

        val bearing = if (loc != null && target != null) {
            Geo.bearingDegrees(
                loc!!.latitude,
                loc!!.longitude,
                target!!.latitude,
                target!!.longitude
            )
        } else null

        val relative = when {
            bearing != null && heading != null -> (bearing - heading!! + 360f) % 360f
            heading != null -> heading!!
            else -> 0f
        }

        Icon(
            Icons.Default.Navigation,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(150.dp)
                .rotate(relative)
        )

        Spacer(Modifier.height(16.dp))
        Text(
            heading?.let { "${it.roundToInt()}° ${Geo.cardinal(it)}" }
                ?: "Определяем направление…",
            style = MaterialTheme.typography.headlineMedium
        )

        if (target != null && loc != null && bearing != null) {
            val dist = Geo.distanceMeters(
                loc!!.latitude,
                loc!!.longitude,
                target!!.latitude,
                target!!.longitude
            )
            Spacer(Modifier.height(8.dp))
            Text(target!!.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "${Geo.distanceLabel(dist)} • ${bearing.roundToInt()}° ${Geo.cardinal(bearing)}",
                style = MaterialTheme.typography.titleMedium
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text("Выбери сохранённую точку и нажми «К точке».")
        }
    }
}

@Composable
fun MoreScreen(vm: AppViewModel) {
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("Офлайн") },
                icon = { Icon(Icons.Default.Download, null) }
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("Настройки") },
                icon = { Icon(Icons.Default.Settings, null) }
            )
        }

        if (tab == 0) OfflineScreen(vm) else SettingsScreen(vm)
    }
}

@Composable
private fun OfflineScreen(vm: AppViewModel) {
    val location by vm.location.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val regions by vm.offlineRegions.collectAsState()
    val layer by vm.mapLayer.collectAsState()
    var radius by remember { mutableDoubleStateOf(5.0) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Скачать область", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Слой: ${layer.title}. Центр — текущая GPS-позиция. " +
                    "Офлайн ArcGIS делится на несколько пакетов, поэтому большие области не обрезаются лимитом одной операции. " +
                    "Спутник и спутник+рельеф скачиваются в HD по всей выбранной области; готовые части при продолжении не скачиваются повторно."
            )

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(2.0, 5.0, 10.0, 25.0, 50.0, 100.0).forEach { r ->
                    FilterChip(
                        selected = radius == r,
                        onClick = { radius = r },
                        label = { Text("${r.toInt()} км") }
                    )
                }
            }

            if (radius >= 50.0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Большая HD-область может занимать много места и скачиваться долго. " +
                        "Качество не снижается к краям: приложение автоматически делит область на части.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            Button(
                enabled = location != null,
                onClick = { vm.downloadCurrentRegion(radius) }
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(6.dp))
                Text(if (downloads.values.any { it.active }) "Добавить загрузку" else "Скачать")
            }

            if (downloads.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Текущие загрузки", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))

                downloads.values
                    .sortedByDescending { it.regionId ?: 0L }
                    .forEach { p ->
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    p.name.ifBlank {
                                        p.layerTitle.ifBlank { "Офлайн-карта" }
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                when {
                                    p.error != null -> {
                                        Text(
                                            p.error,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "Скачанная часть сохранена. Повторный запуск этой же области продолжит загрузку.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    p.cancelled -> {
                                        Text(
                                            "Загрузка приостановлена. Скачанная часть сохранена.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    p.complete -> {
                                        Text(
                                            when {
                                                p.missingResources > 0L ->
                                                    "Область уже работает офлайн. Осталось докачать: ${p.missingResources} тайлов; приложение продолжит автоматически."
                                                p.skippedResources > 0L ->
                                                    "Готово. Недоступных у провайдера тайлов пропущено: ${p.skippedResources}."
                                                else ->
                                                    "Готово — область доступна офлайн."
                                            },
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    else -> {
                                        val ratio = if (p.requiredResources > 0L) {
                                            (
                                                p.completedResources.toFloat() +
                                                    p.currentPackageProgress / 100f
                                                ) / p.requiredResources
                                        } else 0f

                                        if (p.requiredResources > 0L) {
                                            LinearProgressIndicator(
                                                progress = { ratio.coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Text(
                                                "Пакетов: ${p.completedResources}/${p.requiredResources} • " +
                                                    "текущий ${p.currentPackageProgress}% • " +
                                                    "${p.bytes / (1024 * 1024)} МБ",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            if (p.needsRetry && p.missingResources > 0L) {
                                                Text(
                                                    "Автодокачивание: осталось ${p.missingResources} пакетов. Приложение продолжит само.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        } else {
                                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                            Text(
                                                "Подготовка области или ожидание свободного слота…",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        if (p.active) {
                                            p.regionId?.let { id ->
                                                TextButton(onClick = { vm.cancelDownload(id) }) {
                                                    Icon(Icons.Default.Pause, null)
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Пауза")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }

        }

        item { HorizontalDivider() }
        item {
            Text("Скачанные области", style = MaterialTheme.typography.titleLarge)
        }

        if (regions.isEmpty()) {
            item { Text("Пока нет скачанных областей.") }
        }

        items(regions, key = { it.first }) { region ->
            ListItem(
                headlineContent = {
                    Text(
                        region.second.ifBlank {
                            "Офлайн-область #${region.first}"
                        }
                    )
                },
                supportingContent = { Text("ID ${region.first}") },
                trailingContent = {
                    IconButton(onClick = { vm.deleteOfflineRegion(region.first) }) {
                        Icon(Icons.Default.DeleteOutline, "Удалить")
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    var custom by remember { mutableStateOf(vm.customStyle()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val capabilities = remember(context) { SensorCapabilities.read(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Карты", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Основа интерфейса, навигации и указателей сохранена от версии 1.2.9. " +
                "3D удалён полностью. Карта — ArcGIS Open OSM с домами и подробными подписями; " +
                "рельеф — тот же подробный Open OSM с теневым рельефом; спутник — ArcGIS World Imagery; " +
                "спутник+рельеф — World Imagery с подписями и hillshade."
        )
        AssistChip(
            onClick = {},
            label = { Text("ArcGIS + Open OSM • 2D") },
            leadingIcon = { Icon(Icons.Default.CheckCircle, null) }
        )

        HorizontalDivider()

        Text("Свой MapLibre style URL")
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("https://…/style.json") },
            singleLine = true
        )
        Button(onClick = { vm.updateCustomStyle(custom) }) {
            Text("Сохранить URL")
        }

        HorizontalDivider()

        Text("Энергопотребление", style = MaterialTheme.typography.titleMedium)
        Text(
            "Обычный режим GNSS запрашивает обновления не чаще необходимого для пешей " +
                "навигации. Режим повышенной точности включается только при сохранении " +
                "точной точки. Компас сглаживается по круговой шкале и обновляет направление без мелкой дрожи."
        )

        HorizontalDivider()

        Text("Датчики устройства", style = MaterialTheme.typography.titleMedium)
        capabilities.forEach { sensor ->
            ListItem(
                headlineContent = { Text(sensor.label) },
                supportingContent = { Text(sensor.purpose) },
                trailingContent = {
                    Icon(
                        if (sensor.available) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        null,
                        tint = if (sensor.available) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(text)
    }
}

private fun pointIcon(type: WaypointType) = when (type) {
    WaypointType.CAR -> Icons.Default.DirectionsCar
    WaypointType.MUSHROOM -> Icons.Default.Forest
    WaypointType.WATER -> Icons.Default.WaterDrop
    WaypointType.DANGER -> Icons.Default.Warning
    WaypointType.FAVORITE -> Icons.Default.Star
    WaypointType.CUSTOM -> Icons.Default.Place
}
