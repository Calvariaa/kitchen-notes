package com.dishplanner.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 主题。
 *
 * 三档优先级：
 *  1. 用户在设置里开了「动态取色」且系统是 Android 12+ → 用壁纸色（保留我们的版式与动效）。
 *  2. 否则用 [palette] 这套手工厨房配色。
 *  3. 明暗跟随系统，或用户强制。
 *
 * 为什么不直接用 `dynamicColorScheme` 覆盖掉厨房配色：
 * 动态取色的色相来自壁纸，有时会变成冷蓝或紫色，跟食物照片打架。
 * 所以做成开关，默认关，让用户自己在「厨房感」和「跟随系统」之间选。
 */
@Composable
fun DishPlannerTheme(
    palette: Palette = Palette.TERRACOTTA,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> palette.dark.toDarkScheme()
        else -> palette.light.toLightScheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = DishTypography,
        shapes = DishShapes,
        content = content,
    )
}

/** 内部辅助：把 tokens 映射到 Material3 的 ColorScheme */
@Suppress("FunctionName")
private fun ColorSchemeTokens.toLightScheme(): ColorScheme = lightColorScheme(
    primary = this.primary,
    onPrimary = this.onPrimary,
    primaryContainer = this.primaryContainer,
    onPrimaryContainer = this.onPrimaryContainer,
    secondary = this.secondary,
    onSecondary = this.onSecondary,
    secondaryContainer = this.secondaryContainer,
    onSecondaryContainer = this.onSecondaryContainer,
    tertiary = this.tertiary,
    onTertiary = this.onTertiary,
    tertiaryContainer = this.tertiaryContainer,
    onTertiaryContainer = this.onTertiaryContainer,
    background = this.background,
    onBackground = this.onBackground,
    surface = this.surface,
    onSurface = this.onSurface,
    surfaceVariant = this.surfaceVariant,
    onSurfaceVariant = this.onSurfaceVariant,
    outline = this.outline,
    outlineVariant = this.outlineVariant,
    error = this.error,
    onError = this.onError,
    errorContainer = this.errorContainer,
    onErrorContainer = this.onErrorContainer,
    surfaceContainerLowest = this.surfaceContainerLowest,
    surfaceContainerLow = this.surfaceContainerLow,
    surfaceContainer = this.surfaceContainer,
    surfaceContainerHigh = this.surfaceContainerHigh,
    surfaceContainerHighest = this.surfaceContainerHighest,
)

@Suppress("FunctionName")
private fun ColorSchemeTokens.toDarkScheme(): ColorScheme = darkColorScheme(
    primary = this.primary,
    onPrimary = this.onPrimary,
    primaryContainer = this.primaryContainer,
    onPrimaryContainer = this.onPrimaryContainer,
    secondary = this.secondary,
    onSecondary = this.onSecondary,
    secondaryContainer = this.secondaryContainer,
    onSecondaryContainer = this.onSecondaryContainer,
    tertiary = this.tertiary,
    onTertiary = this.onTertiary,
    tertiaryContainer = this.tertiaryContainer,
    onTertiaryContainer = this.onTertiaryContainer,
    background = this.background,
    onBackground = this.onBackground,
    surface = this.surface,
    onSurface = this.onSurface,
    surfaceVariant = this.surfaceVariant,
    onSurfaceVariant = this.onSurfaceVariant,
    outline = this.outline,
    outlineVariant = this.outlineVariant,
    error = this.error,
    onError = this.onError,
    errorContainer = this.errorContainer,
    onErrorContainer = this.onErrorContainer,
    surfaceContainerLowest = this.surfaceContainerLowest,
    surfaceContainerLow = this.surfaceContainerLow,
    surfaceContainer = this.surfaceContainer,
    surfaceContainerHigh = this.surfaceContainerHigh,
    surfaceContainerHighest = this.surfaceContainerHighest,
)

/** 菜谱卡片标题用的是「有性格」的字重，正文保持可读 */
val DishTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

/** M3 的「圆角很圆」是该风格的灵魂，卡片用 20dp，图片方框用 16dp */
val DishShapes
    get() = androidx.compose.material3.Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    )

// 供 UI 里做「无图占位」时用的中性色，取值仍跟随主题
val NeutralImagePlaceholder: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
