package com.xjb.medicalquestionai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Teal600,
    onPrimary = White,
    primaryContainer = Teal100,
    onPrimaryContainer = Teal900,
    secondary = Teal700,
    onSecondary = White,
    secondaryContainer = Mist100,
    onSecondaryContainer = Teal900,
    tertiary = Amber700,
    onTertiary = White,
    tertiaryContainer = Amber100,
    onTertiaryContainer = ColorTokens.Ink900,
    background = Mist50,
    onBackground = Ink900,
    surface = White,
    onSurface = Ink900,
    surfaceVariant = Mist100,
    onSurfaceVariant = Ink700,
    outline = Mist200,
    outlineVariant = Mist100,
    surfaceTint = Teal600,
    error = Red700,
    onError = White,
    errorContainer = Red100,
    onErrorContainer = Red700
)

private val DarkColors = darkColorScheme(
    primary = Teal200,
    onPrimary = Teal900,
    primaryContainer = Teal700,
    onPrimaryContainer = Teal50,
    secondary = Teal200,
    onSecondary = Teal900,
    secondaryContainer = Ink700,
    onSecondaryContainer = Teal50,
    tertiary = ColorTokens.Amber300,
    onTertiary = ColorTokens.Amber900,
    tertiaryContainer = ColorTokens.Amber900,
    onTertiaryContainer = Amber100,
    background = ColorTokens.Night,
    onBackground = ColorTokens.NightText,
    surface = ColorTokens.NightSurface,
    onSurface = ColorTokens.NightText,
    surfaceVariant = ColorTokens.NightVariant,
    onSurfaceVariant = ColorTokens.NightMuted,
    outline = ColorTokens.NightOutline,
    outlineVariant = ColorTokens.NightOutline.copy(alpha = 0.55f),
    surfaceTint = Teal200,
    error = ColorTokens.Red300,
    onError = ColorTokens.Red900,
    errorContainer = ColorTokens.Red900,
    onErrorContainer = ColorTokens.Red300
)

private object ColorTokens {
    val Ink900 = androidx.compose.ui.graphics.Color(0xFF10201F)
    val Amber300 = androidx.compose.ui.graphics.Color(0xFFFCD34D)
    val Amber900 = androidx.compose.ui.graphics.Color(0xFF78350F)
    val Night = androidx.compose.ui.graphics.Color(0xFF101B27)
    val NightSurface = androidx.compose.ui.graphics.Color(0xFF182736)
    val NightVariant = androidx.compose.ui.graphics.Color(0xFF223646)
    val NightText = androidx.compose.ui.graphics.Color(0xFFE6EFF5)
    val NightMuted = androidx.compose.ui.graphics.Color(0xFFACBFCC)
    val NightOutline = androidx.compose.ui.graphics.Color(0xFF3D5363)
    val Red300 = androidx.compose.ui.graphics.Color(0xFFFCA5A5)
    val Red900 = androidx.compose.ui.graphics.Color(0xFF7F1D1D)
}

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 27.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 19.sp
    )
)

@Composable
fun MedicalQuestionAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
