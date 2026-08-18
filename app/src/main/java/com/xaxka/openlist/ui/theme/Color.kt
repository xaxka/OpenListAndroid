package com.xaxka.openlist.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * 颜色令牌：Blue Light UI §2.1（design-tokens.json），浅色单方案、固定生效。
 * 冷白底 × 纯白卡片 × 浅蓝主强调；语义色只表达状态，禁止挪作品牌色。
 * primary 之上必须用深色文字（onPrimary），白字对比度不达标（§6）。
 */

// ---------- 品牌色板 ----------
val OpenListPrimary = Color(0xFF91C6FF) // 主强调：链接、选中、进度
val OpenListOnPrimary = Color(0xFF001D36) // primary 上文字（必须深色）
val OpenListPrimaryContainer = Color(0xFFCFE6FF) // 选中背景、多选高亮
val OpenListOnPrimaryContainer = Color(0xFF001D36)
val OpenListInversePrimary = Color(0xFF5B7CC4) // FAB 运行态（secondary 同系）

val OpenListSecondary = Color(0xFF5B7CC4) // 次级强调（中蓝，更沉稳）
val OpenListOnSecondary = Color(0xFFFFFFFF)
val OpenListSecondaryContainer = Color(0xFFCDE7F2)
val OpenListOnSecondaryContainer = Color(0xFF003543)

val OpenListTertiary = Color(0xFF7D5260) // 点缀色，用量最少
val OpenListOnTertiary = Color(0xFFFFFFFF)
val OpenListTertiaryContainer = Color(0xFFFFD9E2)
val OpenListOnTertiaryContainer = Color(0xFF31111D)

val OpenListError = Color(0xFFBA1A1A)
val OpenListOnError = Color(0xFFFFFFFF)
val OpenListErrorContainer = Color(0xFFFFDAD6)
val OpenListOnErrorContainer = Color(0xFF410002)

// ---------- 背景与表面 ----------
// 页面底 = 冷白（与卡片形成微弱层次）；卡片/弹层/列表行表面 = 纯白（surfaceContainer 系）。
// 层级用 1dp 描边 / 少量阴影表达，不用色阶表达（§1.2 原则 3）。
val OpenListBackground = Color(0xFFF7F7F9)
val OpenListOnBackground = Color(0xFF1A1C1E)
val OpenListSurface = Color(0xFFF7F7F9) // Scaffold / TopAppBar / NavigationBar 页面底
val OpenListOnSurface = Color(0xFF1A1C1E)
val OpenListSurfaceVariant = Color(0xFFE6E8EB) // 统计卡底、占位框、Switch 未选轨道
val OpenListOnSurfaceVariant = Color(0xFF44474E)

val OpenListOutline = Color(0xFF44474E) // 描边（强调场景）
val OpenListOutlineVariant = Color(0xFF74777F) // 常规描边、分隔线（配合 50% 透明度）
val OpenListInverseSurface = Color(0xFF2F3033)
val OpenListInverseOnSurface = Color(0xFFF7F7F9)
val OpenListScrim = Color(0x99000000)
val OpenListSurfaceTint = Color(0xFF91C6FF)
val OpenListSurfaceDim = Color(0xFFE6E8EB)
val OpenListSurfaceBright = Color(0xFFFFFFFF)

// 卡片 / 弹层 / 菜单表面统一纯白（§3.1 两级卡片体系：描边卡为主，阴影卡仅限浮起对象）
val OpenListSurfaceContainerLowest = Color(0xFFFFFFFF)
val OpenListSurfaceContainerLow = Color(0xFFFFFFFF)
val OpenListSurfaceContainer = Color(0xFFFFFFFF)
val OpenListSurfaceContainerHigh = Color(0xFFFFFFFF)
val OpenListSurfaceContainerHighest = Color(0xFFFFFFFF)

// ---------- 语义状态色（仅表达状态，禁止当品牌色用，§2.2） ----------
val StatusSuccess = Color(0xFF34C759) // 成功
val StatusWarning = Color(0xFFFF9500) // 警告/待确认

// ---------- 三级文字（§2.3：层级最多三级） ----------
val TextPrimary = Color(0xFF1A1C1E)
val TextSecondary = Color(0xFF44474E)
val TextDisabled = Color(0xFF74777F)

// ---------- 应用内固定色 ----------
// 日志级别 → 语义色映射（§2.2 状态色映射，替代原纯 RGB 三原色）
val LogRed = Color(0xFFBA1A1A) // panic / fatal / error → error
val LogWarn = Color(0xFFFF9500) // warn → warning
val LogInfo = Color(0xFF5B7CC4) // info → secondary（可读中蓝）
val LogGreen = Color(0xFF34C759) // debug / trace → success
val LogDefault = Color(0xFF1A1C1E) // 未知级别回退 → textPrimary

// WebView 进度条（§3.8：surfaceVariant 轨道 + primary 进度）
val WebProgressTrack = Color(0xFFE6E8EB)
val WebProgressActive = Color(0xFF91C6FF)

// WebView 跟随系统深色（App 外壳固定浅色，网页内容按系统深色渲染）：
// 底色对齐 OpenList Web 暗色主题背景（Radix slate1），进度条轨道用 slate3
val WebDarkSurface = Color(0xFF18191B)
val WebDarkProgressTrack = Color(0xFF2A2D33)

// Snackbar 固定配色（§2.1 inverseSurface / inverseOnSurface）
val SnackBackground = Color(0xFF2F3033)
val SnackOnBackground = Color(0xFFF7F7F9)

/*
 * 深色方案令牌（界面偏好「深色模式」开启时生效，默认关闭保持 Blue Light 浅色）：
 * 与浅色令牌同源（主强调同色相），仅明度反转；卡片体系仍以层次微差表达，
 * 未逐 token 复刻，darkColorScheme() 未覆盖项走 Material 基线。
 */
val DarkPrimary = Color(0xFF9AC7FF)
val DarkOnPrimary = Color(0xFF00305B)
val DarkPrimaryContainer = Color(0xFF1E4A7A)
val DarkOnPrimaryContainer = Color(0xFFD4E3FF)
val DarkSecondary = Color(0xFFAEC6F0)
val DarkOnSecondary = Color(0xFF16294A)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkBackground = Color(0xFF111418)
val DarkOnBackground = Color(0xFFE2E2E7)
val DarkSurface = Color(0xFF111418)
val DarkOnSurface = Color(0xFFE2E2E7)
val DarkSurfaceVariant = Color(0xFF2A2D33)
val DarkOnSurfaceVariant = Color(0xFFC4C6CF)
val DarkOutline = Color(0xFF8E9199)
