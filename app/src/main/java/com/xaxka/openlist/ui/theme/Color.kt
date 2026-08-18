package com.xaxka.openlist.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * 颜色令牌：PIXEL_SPEC §1/§6.1，共 48 个命名令牌。
 * 33 个 scheme 角色为亮/暗成对（后缀 Light/Dark），15 个为应用内固定色。
 * 种子色 OpenListSeed → TonalSpot(contrast 0) 实算值，逐值照抄，禁止改动。
 */

// ---------- 种子与阴影 ----------
val OpenListSeed = Color(0xFF91C6FF) // 主题种子色（main.dart L42/L50）
val OpenListShadow = Color(0xFF000000)
val OpenListScrim = Color(0xFF000000)

// ---------- 亮色 scheme（33） ----------
val OpenListPrimaryLight = Color(0xFF33618D)
val OpenListOnPrimaryLight = Color(0xFFFFFFFF)
val OpenListPrimaryContainerLight = Color(0xFFD0E4FF) // 主页 AppBar / FAB 停止态背景
val OpenListOnPrimaryContainerLight = Color(0xFF154974)
val OpenListSecondaryLight = Color(0xFF526070)
val OpenListOnSecondaryLight = Color(0xFFFFFFFF)
val OpenListSecondaryContainerLight = Color(0xFFD6E4F7) // NavigationBar 选中指示器
val OpenListOnSecondaryContainerLight = Color(0xFF3B4857)
val OpenListTertiaryLight = Color(0xFF6A5779)
val OpenListOnTertiaryLight = Color(0xFFFFFFFF)
val OpenListTertiaryContainerLight = Color(0xFFF1DBFF)
val OpenListOnTertiaryContainerLight = Color(0xFF514060)
val OpenListErrorLight = Color(0xFFBA1A1A)
val OpenListOnErrorLight = Color(0xFFFFFFFF)
val OpenListErrorContainerLight = Color(0xFFFFDAD6)
val OpenListOnErrorContainerLight = Color(0xFF93000A)
val OpenListSurfaceLight = Color(0xFFF8F9FF) // Scaffold / AppBar / AlertDialog 背景
val OpenListOnSurfaceLight = Color(0xFF191C20)
val OpenListSurfaceVariantLight = Color(0xFFDEE3EB) // Switch 未选中轨道
val OpenListOnSurfaceVariantLight = Color(0xFF42474E)
val OpenListOutlineLight = Color(0xFF73777F) // 输入框描边
val OpenListOutlineVariantLight = Color(0xFFC2C7CF) // Divider / Switch 未选中滑块
val OpenListInverseSurfaceLight = Color(0xFF2D3135)
val OpenListInverseOnSurfaceLight = Color(0xFFEFF1F7)
val OpenListInversePrimaryLight = Color(0xFF9ECAFC) // FAB 运行态背景
val OpenListSurfaceTintLight = Color(0xFF33618D)
val OpenListSurfaceDimLight = Color(0xFFD8DAE0)
val OpenListSurfaceBrightLight = Color(0xFFF8F9FF)
val OpenListSurfaceContainerLowestLight = Color(0xFFFFFFFF)
val OpenListSurfaceContainerLowLight = Color(0xFFF2F3F9)
val OpenListSurfaceContainerLight = Color(0xFFECEEF4)
val OpenListSurfaceContainerHighLight = Color(0xFFE6E8EE)
val OpenListSurfaceContainerHighestLight = Color(0xFFE0E2E8)

// ---------- 暗色 scheme（33） ----------
val OpenListPrimaryDark = Color(0xFF9ECAFC)
val OpenListOnPrimaryDark = Color(0xFF003256)
val OpenListPrimaryContainerDark = Color(0xFF154974) // 主页 AppBar / FAB 停止态背景
val OpenListOnPrimaryContainerDark = Color(0xFFD0E4FF)
val OpenListSecondaryDark = Color(0xFFBAC8DB)
val OpenListOnSecondaryDark = Color(0xFF243140)
val OpenListSecondaryContainerDark = Color(0xFF3B4857)
val OpenListOnSecondaryContainerDark = Color(0xFFD6E4F7)
val OpenListTertiaryDark = Color(0xFFD5BEE5)
val OpenListOnTertiaryDark = Color(0xFF3A2A48)
val OpenListTertiaryContainerDark = Color(0xFF514060)
val OpenListOnTertiaryContainerDark = Color(0xFFF1DBFF)
val OpenListErrorDark = Color(0xFFFFB4AB)
val OpenListOnErrorDark = Color(0xFF690005)
val OpenListErrorContainerDark = Color(0xFF93000A)
val OpenListOnErrorContainerDark = Color(0xFFFFDAD6)
val OpenListSurfaceDark = Color(0xFF101418)
val OpenListOnSurfaceDark = Color(0xFFE0E2E8)
val OpenListSurfaceVariantDark = Color(0xFF42474E)
val OpenListOnSurfaceVariantDark = Color(0xFFC2C7CF)
val OpenListOutlineDark = Color(0xFF8C9199)
val OpenListOutlineVariantDark = Color(0xFF42474E)
val OpenListInverseSurfaceDark = Color(0xFFE0E2E8)
val OpenListInverseOnSurfaceDark = Color(0xFF2D3135)
val OpenListInversePrimaryDark = Color(0xFF33618D) // FAB 运行态背景
val OpenListSurfaceTintDark = Color(0xFF9ECAFC)
val OpenListSurfaceDimDark = Color(0xFF101418)
val OpenListSurfaceBrightDark = Color(0xFF36393E)
val OpenListSurfaceContainerLowestDark = Color(0xFF0B0E12)
val OpenListSurfaceContainerLowDark = Color(0xFF191C20)
val OpenListSurfaceContainerDark = Color(0xFF1D2024)
val OpenListSurfaceContainerHighDark = Color(0xFF272A2F)
val OpenListSurfaceContainerHighestDark = Color(0xFF32353A)

// ---------- 应用内硬编码色（不随 scheme 派生） ----------
// 日志级别文字色（源 contant/log_level.dart L16-L23）
val LogRed = Color(0xFFFF0000) // panic / fatal / error
val LogWarn = Color(0xFFFFA500) // warn
val LogInfo = Color(0xFF0000FF) // info
val LogGreen = Color(0xFF00FF00) // debug / trace
val LogDefault = Color(0xFF000000) // 未知级别回退（纯黑，不随暗色切换）

// WebView 顶部进度条（源 pages/web/web.dart L76-L77，亮暗一致）
val WebProgressTrack = Color(0xFFEEEEEE)
val WebProgressActive = Color(0xFF2196F3)

// Snackbar 固定配色（get-4.6.6 默认，亮暗一致）
val SnackBackground = Color(0xFF303030)
val SnackOnBackground = Color(0xFFFFFFFF)

// NavigationBar 中间 SVG 图标着色（hintColor）
val HintContentLight = Color(0x99000000)
val HintContentDark = Color(0x99FFFFFF)
