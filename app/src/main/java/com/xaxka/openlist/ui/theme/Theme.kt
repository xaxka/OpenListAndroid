package com.xaxka.openlist.ui.theme

import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import kotlin.math.sqrt

/**
 * Blue Light UI 全局主题：固定浅色单方案。
 * 原则 5「可预期」：固定浅色、禁动态取色；darkTheme / dynamicColor 参数仅为
 * 兼容保留，取值一律忽略（§7.1 移植指南）。
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

/** 状态切换 crossfade：300ms（加载→内容、执行中→完成） */
val AnimCrossfade = tween<Float>(durationMillis = 300, easing = LinearEasing)

/** 主页 FAB 半圈旋转：200ms 线性（每次从 0.5 起） */
val AnimFabRotate = tween<Float>(durationMillis = 200, easing = LinearEasing)

/** 设置页 Switch 切换：300ms（M3 默认 ease） */
val AnimSwitchToggle = tween<Float>(durationMillis = 300, easing = Ease)

/** Tab 指示器：250ms FastOutSlowIn（位置 + 宽度双动画） */
val AnimTabIndicator = tween<Float>(durationMillis = 250, easing = FastOutSlowInEasing)

/** GetSnackBar 进/出：1000ms easeOutCirc */
val AnimGetSnack = tween<Float>(durationMillis = 1000, easing = EaseOutCirc)

/** 对话框打开：150ms 线性淡入 */
val AnimDialogOpen = tween<Float>(durationMillis = 150, easing = LinearEasing)

/**
 * 全局主题入口：固定 Blue Light 浅色方案。
 */
@Composable
fun OpenListTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = false, // 兼容参数：固定浅色，值被忽略
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false, // 兼容参数：禁动态取色，值被忽略
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = BlueLightColorScheme,
        typography = OpenListTypography,
        shapes = OpenListShapes,
        content = content,
    )
}
