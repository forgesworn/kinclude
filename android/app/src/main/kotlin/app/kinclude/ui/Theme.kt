package app.kinclude.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The kinclude.app palette: candlelight on paper, and on ink at night. */
object Brand {
    val Ink = Color(0xFF241B12)
    val Room = Color(0xFF17110B)
    val Card = Color(0xFF2A2015)
    val Gold = Color(0xFFE0A458)
    val Amber = Color(0xFF8A5C0C)
    val Paper = Color(0xFFF9F8F4)
    val PaperSoft = Color(0xFFF2F0E9)
    val Leaf = Color(0xFF38756A)
    val LeafSoft = Color(0xFFE6F0ED)
    val Warn = Color(0xFF9B3D1E)
    val WarnSoft = Color(0xFFF7E6DC)
    val Mine = Color(0xFFF4EAD9)
    val MineDark = Color(0xFF3A2C1B)
}

private val Light = lightColorScheme(
    primary = Brand.Amber, onPrimary = Color.White,
    secondary = Brand.Leaf, onSecondary = Color.White,
    background = Brand.Paper, onBackground = Brand.Ink,
    surface = Brand.Paper, onSurface = Brand.Ink,
    surfaceVariant = Brand.PaperSoft, onSurfaceVariant = Color(0xFF4C463F),
    surfaceContainer = Brand.PaperSoft,
    error = Brand.Warn, outline = Color(0xFFE5E1D6),
)

private val Dark = darkColorScheme(
    primary = Brand.Gold, onPrimary = Brand.Ink,
    secondary = Color(0xFF7CC0B2), onSecondary = Brand.Ink,
    background = Brand.Room, onBackground = Brand.Paper,
    surface = Brand.Room, onSurface = Brand.Paper,
    surfaceVariant = Brand.Card, onSurfaceVariant = Color(0xFFD8D1C4),
    surfaceContainer = Brand.Card,
    error = Color(0xFFF0A080), outline = Color(0xFF3A2E1F),
)

@Composable
fun KincludeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
