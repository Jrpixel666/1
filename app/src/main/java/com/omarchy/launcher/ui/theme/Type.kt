package com.omarchy.launcher.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Omarchy/Hyprland setups lean entirely on a monospace terminal font (JetBrains Mono / Cascadia
 * Code / Iosevka depending on theme). Android ships a system monospace family with no extra
 * font files to bundle, so that's used everywhere here instead of the default sans type scale.
 */
val OmarchyFontFamily = FontFamily.Monospace

val OmarchyTypography = Typography(
    displayLarge = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = 0.sp),
    displayMedium = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp),
    headlineLarge = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    headlineMedium = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.Medium, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = OmarchyFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)
