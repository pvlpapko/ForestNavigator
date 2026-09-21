package app.forestnav.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class Screen(val title: String, val icon: ImageVector) {
    MAP("Карта", Icons.Default.Map),
    POINTS("Точки", Icons.Default.Place),
    COMPASS("Компас", Icons.Default.Explore),
    MORE("Ещё", Icons.Default.MoreHoriz)
}

@Composable
fun ForestNavRoot(vm: AppViewModel, hasFineLocation: Boolean, requestPermissions: () -> Unit) {
    ForestTheme {
        var screen by remember { mutableStateOf(Screen.MAP) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 700.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail {
                        Spacer(Modifier.height(12.dp))
                        Screen.entries.forEach { item ->
                            NavigationRailItem(
                                selected = screen == item,
                                onClick = { screen = item },
                                icon = { Icon(item.icon, null) },
                                label = { Text(item.title) }
                            )
                        }
                    }
                    Surface(Modifier.weight(1f)) {
                        ScreenContent(screen, vm, hasFineLocation, requestPermissions)
                    }
                }
            } else {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        NavigationBar {
                            Screen.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = screen == item,
                                    onClick = { screen = item },
                                    icon = { Icon(item.icon, null) },
                                    label = { Text(item.title) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        ScreenContent(screen, vm, hasFineLocation, requestPermissions)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen, vm: AppViewModel, hasFineLocation: Boolean, requestPermissions: () -> Unit) {
    if (!hasFineLocation) {
        PermissionScreen(requestPermissions)
        return
    }
    when (screen) {
        Screen.MAP -> MapScreen(vm)
        Screen.POINTS -> PointsScreen(vm)
        Screen.COMPASS -> CompassScreen(vm)
        Screen.MORE -> MoreScreen(vm)
    }
}

@Composable
private fun PermissionScreen(requestPermissions: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.GpsFixed, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Нужен доступ к точной геолокации", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Приложение использует GNSS/GPS для точки машины, маршрутов и навигации в лесу. Сеть для определения координат не требуется.")
        Spacer(Modifier.height(20.dp))
        Button(onClick = requestPermissions) { Text("Разрешить точное местоположение") }
    }
}
