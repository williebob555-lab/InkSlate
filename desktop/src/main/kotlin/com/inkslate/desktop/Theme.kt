package com.inkslate.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The Android app's palette, value for value.
 *
 * Deliberately a copy of `com.inkslate.ui.theme.Theme` rather than an approximation. The two
 * builds are meant to look like one application, and a colour that is nearly right is worse than
 * one that is obviously different - it reads as a rendering fault rather than a design.
 *
 * This is the one file that has to be kept in step by hand. If the Android palette changes,
 * change it here too; there is no build-time link between them yet.
 */
private val Ink = Color(0xFF3B82F6)
private val InkDim = Color(0xFF1D4ED8)

private val DarkColors = darkColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF64748B),
    background = Color(0xFF14171B),
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF1B1F24),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF262B31),
    onSurfaceVariant = Color(0xFFB6BDC6),
    outline = Color(0xFF3A424B),
    error = Color(0xFFEF4444)
)

private val LightColors = lightColorScheme(
    primary = InkDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF64748B),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF14171B),
    surface = Color.White,
    onSurface = Color(0xFF14171B),
    surfaceVariant = Color(0xFFE8EBEF),
    onSurfaceVariant = Color(0xFF4A5460),
    outline = Color(0xFFC7CDD5),
    error = Color(0xFFDC2626)
)

@Composable
fun InkSlateTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
