package app.forestnav.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ForestColors = darkColorScheme(
    primary = Color(0xFF7DE2AE),
    onPrimary = Color(0xFF062316),
    primaryContainer = Color(0xFF1B4D34),
    secondary = Color(0xFFB9D6C7),
    background = Color(0xFF0D1511),
    surface = Color(0xFF121D17),
    surfaceVariant = Color(0xFF1C2922),
    outline = Color(0xFF76877D),
    error = Color(0xFFFFB4AB)
)

@Composable
fun ForestTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ForestColors, content = content)
}
