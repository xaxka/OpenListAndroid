package com.xaxka.openlist.ui.theme

import android.os.Build
import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

/** 亮色 ColorScheme：OpenListSeed → TonalSpot(contrast 0) 实算值，显式覆写全部 M3 默认（R18） */
private val LightColorScheme: ColorScheme = lightColorScheme(
    primary = OpenListPrimaryLight,
    onPrimary = OpenListOnPrimaryLight,
    primaryContainer = OpenListPrimaryContainerLight,
    onPrimaryContainer = OpenListOnPrimaryContainerLight,
    inversePrimary = OpenListInversePrimaryLight,
    secondary = OpenListSecondaryLight,
    onSecondary = OpenListOnSecondaryLight,
    secondaryContainer = OpenListSecondaryContainerLight,
    onSecondaryContainer = OpenListOnSecondaryContainerLight,
    tertiary = OpenListTertiaryLight,
    onTertiary = OpenListOnTertiaryLight,
    tertiaryContainer = OpenListTertiaryContainerLight,
    onTertiaryContainer = OpenListOnTertiaryContainerLight,
    error = OpenListErrorLight,
    onError = OpenListOnErrorLight,
    errorContainer = OpenListErrorContainerLight,
    onErrorContainer = OpenListOnErrorContainerLight,
    surface = OpenListSurfaceLight,
    onSurface = OpenListOnSurfaceLight,
    surfaceVariant = OpenListSurfaceVariantLight,
    onSurfaceVariant = OpenListOnSurfaceVariantLight,
    surfaceTint = OpenListSurfaceTintLight,
    inverseSurface = OpenListInverseSurfaceLight,
    inverseOnSurface = OpenListInverseOnSurfaceLight,
    outline = OpenListOutlineLight,
    outlineVariant = OpenListOutlineVariantLight,
    scrim = OpenListScrim,
    surfaceDim = OpenListSurfaceDimLight,
    surfaceBright = OpenListSurfaceBrightLight,
    surfaceContainerLowest = OpenListSurfaceContainerLowestLight,
    surfaceContainerLow = OpenListSurfaceContainerLowLight,
    surfaceContainer = OpenListSurfaceContainerLight,
    surfaceContainerHigh = OpenListSurfaceContainerHighLight,
    surfaceContainerHighest = OpenListSurfaceContainerHighestLight,
)

/** 暗色 ColorScheme：同种子实算值 */
private val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = OpenListPrimaryDark,
    onPrimary = OpenListOnPrimaryDark,
    primaryContainer = OpenListPrimaryContainerDark,
    onPrimaryContainer = OpenListOnPrimaryContainerDark,
    inversePrimary = OpenListInversePrimaryDark,
    secondary = OpenListSecondaryDark,
    onSecondary = OpenListOnSecondaryDark,
    secondaryContainer = OpenListSecondaryContainerDark,
    onSecondaryContainer = OpenListOnSecondaryContainerDark,
    tertiary = OpenListTertiaryDark,
    onTertiary = OpenListOnTertiaryDark,
    tertiaryContainer = OpenListTertiaryContainerDark,
    onTertiaryContainer = OpenListOnTertiaryContainerDark,
    error = OpenListErrorDark,
    onError = OpenListOnErrorDark,
    errorContainer = OpenListErrorContainerDark,
    onErrorContainer = OpenListOnErrorContainerDark,
    surface = OpenListSurfaceDark,
    onSurface = OpenListOnSurfaceDark,
    surfaceVariant = OpenListSurfaceVariantDark,
    onSurfaceVariant = OpenListOnSurfaceVariantDark,
    surfaceTint = OpenListSurfaceTintDark,
    inverseSurface = OpenListInverseSurfaceDark,
    inverseOnSurface = OpenListInverseOnSurfaceDark,
    outline = OpenListOutlineDark,
    outlineVariant = OpenListOutlineVariantDark,
    scrim = OpenListScrim,
    surfaceDim = OpenListSurfaceDimDark,
    surfaceBright = OpenListSurfaceBrightDark,
    surfaceContainerLowest = OpenListSurfaceContainerLowestDark,
    surfaceContainerLow = OpenListSurfaceContainerLowDark,
    surfaceContainer = OpenListSurfaceContainerDark,
    surfaceContainerHigh = OpenListSurfaceContainerHighDark,
    surfaceContainerHighest = OpenListSurfaceContainerHighestDark,
)

/** 主题 Shapes：小圆角 = 输入框/菜单 R4，大圆角 = 对话框 R28（PIXEL_SPEC §4/附录 R 系列） */
val OpenListShapes = Shapes(
    extraSmall = ShapeMenuR4,
    small = ShapeInputOutlineR4,
    medium = ShapeMenuR4,
    large = ShapeDialogR28,
    extraLarge = ShapeDialogR28,
)

/*
 * 动画令牌：PIXEL_SPEC §5，共 6 个。
 */

/** get-4.6.6 snackbar 进/出曲线 */
val EaseOutCirc: Easing = Easing { t -> sqrt(1f - (t - 1f) * (t - 1f)) }

/** 底部导航页面切换：400ms 线性淡入 */
val AnimPageFade = tween<Float>(durationMillis = 400, easing = LinearEasing)

/** 主页 FAB 半圈旋转：200ms 线性（每次从 0.5 起） */
val AnimFabRotate = tween<Float>(durationMillis = 200, easing = LinearEasing)

/** 设置页 Switch 切换：300ms（M3 默认 ease） */
val AnimSwitchToggle = tween<Float>(durationMillis = 300, easing = Ease)

/** 主题/默认文字样式切换：200ms 线性（kThemeChangeDuration） */
val AnimThemeChange = tween<Float>(durationMillis = 200, easing = LinearEasing)

/** GetSnackBar 进/出：1000ms easeOutCirc */
val AnimGetSnack = tween<Float>(durationMillis = 1000, easing = EaseOutCirc)

/** 对话框打开：150ms 线性淡入 */
val AnimDialogOpen = tween<Float>(durationMillis = 150, easing = LinearEasing)

/**
 * 全局主题：默认固定 PIXEL_SPEC 亮/暗 scheme（ADR-2）；
 * dynamicColor 为 true 且 SDK ≥ 31 才启用 Material You 动态取色。
 */
@Composable
fun OpenListTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OpenListTypography,
        shapes = OpenListShapes,
        content = content,
    )
}
