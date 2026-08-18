package com.xaxka.openlist.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * 字体令牌：PIXEL_SPEC §2/§6.2，共 22 个命名令牌。
 * 基准 Flutter Typography.material2021 默认值；字族 = 平台默认（Roboto / Noto Sans CJK）。
 * 行高按「字号 × height 倍数」写入绝对 sp（R17）。
 * 带主题色的令牌（LogTitle/LogSubtitle/InputLabel/InputHint）不在此绑定颜色，
 * 由 UI 侧经 MaterialTheme.colorScheme（onSurface/onSurfaceVariant）提供；SnackBar 两令牌为固定白色。
 */

// ---------- M3 2021 标准缩放（15） ----------
val OpenListDisplayLarge = TextStyle(
    fontSize = 57.sp, fontWeight = FontWeight.W400,
    lineHeight = 63.84.sp, letterSpacing = (-0.25).sp,
)
val OpenListDisplayMedium = TextStyle(
    fontSize = 45.sp, fontWeight = FontWeight.W400,
    lineHeight = 52.2.sp, letterSpacing = 0.sp,
)
val OpenListDisplaySmall = TextStyle(
    fontSize = 36.sp, fontWeight = FontWeight.W400,
    lineHeight = 43.92.sp, letterSpacing = 0.sp,
)
val OpenListHeadlineLarge = TextStyle(
    fontSize = 32.sp, fontWeight = FontWeight.W400,
    lineHeight = 40.sp, letterSpacing = 0.sp,
)
val OpenListHeadlineMedium = TextStyle(
    fontSize = 28.sp, fontWeight = FontWeight.W400,
    lineHeight = 36.12.sp, letterSpacing = 0.sp,
)
val OpenListHeadlineSmall = TextStyle( // AlertDialog 标题 / 关于页应用名
    fontSize = 24.sp, fontWeight = FontWeight.W400,
    lineHeight = 31.92.sp, letterSpacing = 0.sp,
)
val OpenListTitleLarge = TextStyle( // 主页 AppBar 标题
    fontSize = 22.sp, fontWeight = FontWeight.W400,
    lineHeight = 27.94.sp, letterSpacing = 0.sp,
)
val OpenListTitleMedium = TextStyle( // 设置页分组标题（色 OpenListPrimary）
    fontSize = 16.sp, fontWeight = FontWeight.W500,
    lineHeight = 24.sp, letterSpacing = 0.15.sp,
)
val OpenListTitleSmall = TextStyle(
    fontSize = 14.sp, fontWeight = FontWeight.W500,
    lineHeight = 20.02.sp, letterSpacing = 0.1.sp,
)
val OpenListBodyLarge = TextStyle( // 设置页条目标题
    fontSize = 16.sp, fontWeight = FontWeight.W400,
    lineHeight = 24.sp, letterSpacing = 0.5.sp,
)
val OpenListBodyMedium = TextStyle( // 副标题 / 对话框正文
    fontSize = 14.sp, fontWeight = FontWeight.W400,
    lineHeight = 20.02.sp, letterSpacing = 0.25.sp,
)
val OpenListBodySmall = TextStyle(
    fontSize = 12.sp, fontWeight = FontWeight.W400,
    lineHeight = 15.96.sp, letterSpacing = 0.4.sp,
)
val OpenListLabelLarge = TextStyle( // 按钮 / 菜单项文字
    fontSize = 14.sp, fontWeight = FontWeight.W500,
    lineHeight = 20.02.sp, letterSpacing = 0.1.sp,
)
val OpenListLabelMedium = TextStyle( // 底部导航标签
    fontSize = 12.sp, fontWeight = FontWeight.W500,
    lineHeight = 15.96.sp, letterSpacing = 0.5.sp,
)
val OpenListLabelSmall = TextStyle( // 日志级别徽标基础样式
    fontSize = 11.sp, fontWeight = FontWeight.W500,
    lineHeight = 15.95.sp, letterSpacing = 0.5.sp,
)

// ---------- 派生样式（7） ----------
/** 日志内容（dense ListTile 覆写 13sp；色 onSurface 由 UI 侧提供） */
val LogTitle = TextStyle(
    fontSize = 13.sp, fontWeight = FontWeight.W400,
    lineHeight = 19.5.sp, letterSpacing = 0.5.sp,
)

/** 日志时间（dense ListTile 覆写 12sp；色 onSurfaceVariant 由 UI 侧提供） */
val LogSubtitle = TextStyle(
    fontSize = 12.sp, fontWeight = FontWeight.W400,
    lineHeight = 17.16.sp, letterSpacing = 0.25.sp,
)

/** 日志级别徽标文字（级别色 LogRed/LogWarn/LogInfo/LogGreen 由 UI 侧按级别提供） */
val LogLevelText = TextStyle(
    fontSize = 11.sp, fontWeight = FontWeight.W500,
    lineHeight = 15.95.sp, letterSpacing = 0.5.sp,
)

/** Snackbar 标题（get-4.6.6，固定白字） */
val SnackBarTitle = TextStyle(
    fontSize = 16.sp, fontWeight = FontWeight.W700,
    color = SnackOnBackground,
)

/** Snackbar 正文（get-4.6.6，固定白字） */
val SnackBarMessage = TextStyle(
    fontSize = 14.sp, fontWeight = FontWeight.W400,
    color = SnackOnBackground,
)

/** 输入框标签（浮动聚焦时色 primary，由 UI 侧提供） */
val InputLabel = TextStyle(
    fontSize = 16.sp, fontWeight = FontWeight.W400,
)

/** 输入框提示文字（色 onSurfaceVariant 由 UI 侧提供） */
val InputHint = TextStyle(
    fontSize = 14.sp, fontWeight = FontWeight.W400,
)

/** 主题 Typography：M3 默认槽位全部显式覆写为上述令牌（R18：禁止依赖默认值） */
val OpenListTypography = Typography(
    displayLarge = OpenListDisplayLarge,
    displayMedium = OpenListDisplayMedium,
    displaySmall = OpenListDisplaySmall,
    headlineLarge = OpenListHeadlineLarge,
    headlineMedium = OpenListHeadlineMedium,
    headlineSmall = OpenListHeadlineSmall,
    titleLarge = OpenListTitleLarge,
    titleMedium = OpenListTitleMedium,
    titleSmall = OpenListTitleSmall,
    bodyLarge = OpenListBodyLarge,
    bodyMedium = OpenListBodyMedium,
    bodySmall = OpenListBodySmall,
    labelLarge = OpenListLabelLarge,
    labelMedium = OpenListLabelMedium,
    labelSmall = OpenListLabelSmall,
)
