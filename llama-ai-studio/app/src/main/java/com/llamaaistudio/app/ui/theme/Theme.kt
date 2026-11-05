package com.llamaaistudio.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Llama AI Studio is dark-only by design: the orange-on-charcoal palette is tuned for
 * one appearance rather than adapting to system light/dark.
 */
private val StudioColorScheme = darkColorScheme(
    primary = StudioOrange,
    onPrimary = Color.Black,
    primaryContainer = StudioOrangeHighlight,
    onPrimaryContainer = Color.Black,
    secondary = StudioOrangeHighlight,
    onSecondary = Color.Black,
    background = StudioBackground,
    onBackground = StudioText,
    surface = StudioPanel,
    onSurface = StudioText,
    surfaceVariant = StudioPanelSecondary,
    onSurfaceVariant = StudioText,
    outline = StudioDivider,
    error = StudioError,
    onError = Color.Black,
)

@Composable
fun LlamaAiStudioTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = StudioTypography,
        content = content
    )
}
