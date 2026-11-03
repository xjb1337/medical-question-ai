package com.xjb.medicalquestionai.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.xjb.medicalquestionai.data.ThemeMode

private val Night = Color(0xFF0F1C24)
private val NightSurface = Color(0xFF172A33)
private val NightVariant = Color(0xFF203842)
private val NightText = Color(0xFFE7F3F2)
private val NightMuted = Color(0xFFA9C1C2)
private val NightOutline = Color(0xFF5B7780)
private val Amber300 = Color(0xFFFCD34D)
private val Amber900 = Color(0xFF78350F)
private val Red300 = Color(0xFFFCA5A5)
private val Red900 = Color(0xFF4B2227)

private val LightColors = lightColorScheme(
    primary = Teal600,
    onPrimary = White,
    primaryContainer = Teal100,
    onPrimaryContainer = Teal900,
    secondary = Sky600,
    onSecondary = White,
    secondaryContainer = Sky100,
    onSecondaryContainer = Sky900,
    tertiary = Amber700,
    onTertiary = White,
    tertiaryContainer = Amber100,
    onTertiaryContainer = Ink900,
    background = Mist50,
    onBackground = Ink900,
    surface = White,
    onSurface = Ink900,
    surfaceVariant = Mist100,
    onSurfaceVariant = Ink700,
    outline = Mist200,
    outlineVariant = Mist300,
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
    secondary = Color(0xFF8ADDF1),
    onSecondary = Sky900,
    secondaryContainer = Color(0xFF123A47),
    onSecondaryContainer = Color(0xFFD7F5FC),
    tertiary = Amber300,
    onTertiary = Amber900,
    tertiaryContainer = Amber900,
    onTertiaryContainer = Amber100,
    background = Night,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightVariant,
    onSurfaceVariant = NightMuted,
    outline = NightOutline,
    outlineVariant = NightOutline.copy(alpha = 0.62f),
    surfaceTint = Teal200,
    error = Red300,
    onError = Red900,
    errorContainer = Red900,
    onErrorContainer = Red300
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.2).sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
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
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.15.sp
    )
)

@Composable
fun MedicalQuestionAiTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val resolvedDarkTheme = darkTheme ?: when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    if (!view.isInEditMode) {
        SideEffect {
            val window = activity?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !resolvedDarkTheme
                isAppearanceLightNavigationBars = !resolvedDarkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (resolvedDarkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
