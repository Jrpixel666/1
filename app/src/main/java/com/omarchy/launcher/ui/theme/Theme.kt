package com.omarchy.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OmarchyColorScheme = darkColorScheme(
    primary = OmarchyPalette.Accent,
    onPrimary = OmarchyPalette.DarkerBackground,
    secondary = OmarchyPalette.Magenta,
    onSecondary = OmarchyPalette.DarkerBackground,
    tertiary = OmarchyPalette.Green,
    background = OmarchyPalette.Background,
    onBackground = OmarchyPalette.Foreground,
    surface = OmarchyPalette.DarkBackground,
    onSurface = OmarchyPalette.Foreground,
    surfaceVariant = OmarchyPalette.LighterBackground,
    onSurfaceVariant = OmarchyPalette.LightForeground,
    outline = OmarchyPalette.Muted,
    error = OmarchyPalette.Red,
    onError = OmarchyPalette.DarkerBackground,
)

/**
 * Omarchy is a dark-only, terminal-driven aesthetic - there is deliberately no light variant
 * here, same as the distro itself defaults to a single dark palette per theme.
 */
@Composable
fun OmarchyLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OmarchyColorScheme,
        typography = OmarchyTypography,
        shapes = OmarchyShapes,
        content = content,
    )
}
