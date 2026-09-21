package app.forestnav.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.forestnav.data.WaypointType
import app.forestnav.gnss.SensorCapabilities
import app.forestnav.map.MapLayer
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
    val recording by TrackRecordingState.recording.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var recenterToken by remember { mutableIntStateOf(0) }

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
                    ForestMapView(
                        modifier = Modifier.fillMaxSize(),
                        location = location!!,
                        waypoints = waypoints,
                        styleUrl = vm.styleUrl(),
                        recenterToken = recenterToken
                    )
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 28.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.34f),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            if (wide || short) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                        Icons.Default.MyLocation,
                                        "Я здесь",
                                        location != null
                                    ) { recenterToken++ }
                                }
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { vm.savePrecise(WaypointType.CAR, "Машина") },
                                        enabled = location != null,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.DirectionsCar, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Машина", maxLines = 1)
                                    }

                                    Button(
                                        onClick = {
                                            vm.savePrecise(
                                                WaypointType.MUSHROOM,
                                                "Грибное место ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
                                            )
                                        },
                                        enabled = location != null,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Forest, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Грибы", maxLines = 1)
                                    }
                                }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            if (recording) TrackRecordingService.stop(context)
                                            else TrackRecordingService.start(context)
                                        },
                                        enabled = location != null,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(if (recording) Icons.Default.Stop else Icons.Default.Route, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (recording) "Стоп трек" else "Записать трек", maxLines = 1)
                                    }

                                    FilledTonalButton(
                                        onClick = { recenterToken++ },
                                        enabled = location != null,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.MyLocation, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Я здесь", maxLines = 1)
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

@Composable
private fun SmallAction(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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

        val relative = if (bearing != null && heading != null) {
            (bearing - heading!! + 360f) % 360f
        } else 0f

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
    val progress by vm.download.collectAsState()
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
                    "После загрузки интернет для этой области не нужен."
            )

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(2.0, 5.0, 10.0).forEach { r ->
                    FilterChip(
                        selected = radius == r,
                        onClick = { radius = r },
                        label = { Text("${r.toInt()} км") }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Button(
                enabled = location != null,
                onClick = { vm.downloadCurrentRegion(radius) }
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(6.dp))
                Text("Скачать")
            }

            progress?.let { p ->
                Spacer(Modifier.height(10.dp))
                p.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                if (p.error == null) {
                    val ratio = if (p.requiredResources > 0) {
                        p.completedResources.toFloat() / p.requiredResources
                    } else 0f

                    LinearProgressIndicator(
                        progress = { ratio.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Ресурсов: ${p.completedResources}/" +
                            (if (p.requiredResources > 0) p.requiredResources.toString() else "…") +
                            " • ${p.bytes / (1024 * 1024)} МБ"
                    )
                    if (p.complete) {
                        Text(
                            "Готово — область доступна офлайн.",
                            color = MaterialTheme.colorScheme.primary
                        )
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
    var key by remember { mutableStateOf(vm.mapTilerKey()) }
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
            "Карта, спутник и рельеф работают без ключа. Если указать свой MapTiler API key, " +
                "спутниковый и рельефный слои будут использовать MapTiler. Ключ хранится только на телефоне."
        )

        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("MapTiler API key") },
            singleLine = true
        )
        Button(onClick = { vm.updateMapTilerKey(key) }) {
            Text("Сохранить ключ")
        }

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
                "точной точки. Компас ограничен примерно 10 обновлениями в секунду."
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
