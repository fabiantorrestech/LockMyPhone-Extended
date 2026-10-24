package com.fabiantorrestech.lockmyphone_extended.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Defaults = Typography()

/** The Material 3 type scale, with headlines and titles a step bolder so screens read at a glance. */
val Typography = Typography(
    displaySmall = Defaults.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = Defaults.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Defaults.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Defaults.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Defaults.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Defaults.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
)
