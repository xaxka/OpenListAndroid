package com.xaxka.openlist.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import kotlin.math.sqrt

/**
 * Blue Light UI 全局主题：默认浅色单方案（原则 5「可预期」）。
 * 界面偏好「深色模式 / 动态取色」开启时切换深色 / Material You 方案
 * （照源 AppConfig 界面组，偏好存于 AppPrefsRepository，默认均关闭）。
 */
private val BlueLightColorScheme: ColorScheme = lightColorScheme(
    primary = OpenListPrimary,
    onPrimary = OpenListOnPrimary,
    primaryContainer = OpenListPrimaryContainer,
    onPrimaryContainer = OpenListOnPrimaryContainer,
    inversePrimary = OpenListInversePrimary,
    secondary = OpenListSecondary,
    onSecondary = OpenListOnSecondary,
    secondaryContainer = OpenListSecondaryContainer,
    onSecondaryContainer = OpenListOnSecondaryContainer,
    tertiary = OpenListTertiary,
    onTertiary = OpenListOnTertiary,
    tertiaryContainer = OpenListTertiaryContainer,
    onTertiaryContainer = OpenListOnTertiaryContainer,
    error = OpenListError,
    onError = OpenListOnError,
    errorContainer = OpenListErrorContainer,
    onErrorContainer = OpenListOnErrorContainer,
    background = OpenListBackground,
    onBackground = OpenListOnBackground,
    surface = OpenListSurface,
    onSurface = OpenListOnSurface,
    surfaceVariant = OpenListSurfaceVariant,
    onSurfaceVariant = OpenListOnSurfaceVariant,
    surfaceTint = OpenListSurfaceTint,
    inverseSurface = OpenListInverseSurface,
    inverseOnSurface = OpenListInverseOnSurface,
    outline = OpenListOutline,
    outlineVariant = OpenListOutlineVariant,
    scrim = OpenListScrim,
    surfaceDim = OpenListSurfaceDim,
    surfaceBright = OpenListSurfaceBright,
    surfaceContainerLowest = OpenListSurfaceContainerLowest,
    surfaceContainerLow = OpenListSurfaceContainerLow,
    surfaceContainer = OpenListSurfaceContainer,
    surfaceContainerHigh = OpenListSurfaceContainerHigh,
    surfaceContainerHighest = OpenListSurfaceContainerHighest,
)

/** 深色方案：与浅色同源令牌的明度反转版（未覆盖 token 走 Material 深色基线） */
private val BlueDarkColorScheme: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    error = DarkError,
    onError = DarkOnError,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
)

/** 主题 Shapes：4（输入框/菜单）/ 8（小控件）/ 12（默认卡片）/ 16（大卡）/ 28（弹层） */
val OpenListShapes = Shapes(
    extraSmall = ShapeRadius4,
    small = ShapeRadius8,
    medium = ShapeCardR12,
    large = ShapeRadius16,
    extraLarge = ShapeDialogR28,
)

/*
 * 动效令牌：Blue Light UI §2.6。
 * 规则：不用弹性动画；动效只服务于「状态切换的认知」，不做展示型动效。
 */

/** Snackbar 进/出曲线（easeOutCirc） */
val EaseOutCirc: Easing = Easing { t -> sqrt(1f - (t - 1f) * (t - 1f)) }

/** 页面转场：150ms 线性淡入，仅进入动画（退出无动画防旧页拦截输入） */
val AnimPageFade = tween<Float>(durationMillis = 150, easing = LinearEasing)

/** 主页 FAB 半圈旋转：200ms 线性（每次从 0.5 起） */
val AnimFabRotate = tween<Float>(durationMillis = 200, easing = LinearEasing)

/** GetSnackBar 进/出：1000ms easeOutCirc */
val AnimGetSnack = tween<Float>(durationMillis = 1000, easing = EaseOutCirc)

/**
 * 全局主题入口：默认 Blue Light 浅色；darkTheme 切换深色方案，
 * dynamicColor 在 Android 12+ 且开启时优先取 Material You 动态色。
 */
@Composable
fun OpenListTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> BlueDarkColorScheme
        else -> BlueLightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = OpenListTypography,
        shapes = OpenListShapes,
        content = content,
    )
}
