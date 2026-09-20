package com.aifriend.core.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aifriend.core.settings.FontLevel

private val StandardColorScheme = lightColorScheme(
    primary = Color(0xFF245F73),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EEF5),
    onPrimaryContainer = Color(0xFF123C49),
    secondary = Color(0xFF536B55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE9DA),
    onSecondaryContainer = Color(0xFF2E4731),
    tertiary = Color(0xFF8A5A32),
    onTertiary = Color.White,
    background = Color(0xFFF3F6F7),
    onBackground = Color(0xFF182023),
    surface = Color.White,
    onSurface = Color(0xFF182023),
    surfaceVariant = Color(0xFFE5ECEF),
    onSurfaceVariant = Color(0xFF3F4B50),
    outline = Color(0xFF738187),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF5F1410),
)

private val HighContrastColorScheme = darkColorScheme(
    primary = Color(0xFFFFE100),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFFFFE100),
    onPrimaryContainer = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color.White,
    onSecondaryContainer = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF111111),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color.White,
    outline = Color.White,
    error = Color(0xFFFF8A80),
    onError = Color.Black,
    errorContainer = Color(0xFFFF8A80),
    onErrorContainer = Color.Black,
)

private val ElderFriendlyShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

/** 适老字体工厂，供主题和纯 JVM 边界测试共用。 */
internal fun elderFriendlyTypography(fontLevel: FontLevel): Typography {
    val larger = fontLevel == FontLevel.LARGER
    return Typography(
        bodyLarge = TextStyle(
            fontSize = if (larger) 26.sp else 22.sp,
            lineHeight = if (larger) 38.sp else 32.sp,
            fontWeight = FontWeight.Normal,
        ),
        bodyMedium = TextStyle(
            fontSize = if (larger) 22.sp else 19.sp,
            lineHeight = if (larger) 32.sp else 28.sp,
        ),
        headlineLarge = TextStyle(
            fontSize = if (larger) 40.sp else 34.sp,
            lineHeight = if (larger) 50.sp else 42.sp,
            fontWeight = FontWeight.Bold,
        ),
        headlineSmall = TextStyle(
            fontSize = if (larger) 32.sp else 28.sp,
            lineHeight = if (larger) 42.sp else 36.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleLarge = TextStyle(
            fontSize = if (larger) 28.sp else 24.sp,
            lineHeight = if (larger) 38.sp else 32.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = TextStyle(
            fontSize = if (larger) 24.sp else 21.sp,
            lineHeight = if (larger) 34.sp else 30.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        labelLarge = TextStyle(
            fontSize = if (larger) 26.sp else 22.sp,
            lineHeight = if (larger) 34.sp else 28.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

/**
 * 适老化 Material 主题基线。
 *
 * @author codex
 * @since 2026-07-25
 */
@Composable
public fun AiFriendTheme(
    fontLevel: FontLevel = FontLevel.LARGE,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (highContrast) HighContrastColorScheme else StandardColorScheme,
        typography = elderFriendlyTypography(fontLevel),
        shapes = ElderFriendlyShapes,
        content = content,
    )
}