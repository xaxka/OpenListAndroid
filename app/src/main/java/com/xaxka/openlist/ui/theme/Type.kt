package com.xaxka.openlist.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * 字体令牌：Blue Light UI §2.3，系统默认字族（不引入自定义字体）。
 * 标题偏重（SemiBold）、行高宽松；文件名/关键条目用 Medium~SemiBold 与普通信息区分。
 * 文字层级最多三级：主（textPrimary）/ 次（textSecondary）/ 弱（textDisabled）。
 * 带主题色的令牌（LogTitle/LogSubtitle/InputLabel/InputHint）不在此绑定颜色，
 * 由 UI 侧经 MaterialTheme.colorScheme 提供；SnackBar 两令牌为固定反色。
 */

// ---------- Blue Light 12 级字阶 → M3 槽位 ----------
val OpenListDisplaySmall = TextStyle( // display：结果页大字主标题
    fontSize = 32.sp, fontWeight = FontWeight.W700,
    lineHeight = 40.sp, letterSpacing = 0.sp,
)
val OpenListHeadlineMedium = TextStyle( // headlineM：页面大标题
    fontSize = 26.sp, fontWeight = FontWeight.W600,
    lineHeight = 34.sp, letterSpacing = 0.sp,
)
val OpenListHeadlineSmall = TextStyle( // headlineS：大数字（统计）、对话框标题
    fontSize = 22.sp, fontWeight = FontWeight.W600,
    lineHeight = 30.sp, letterSpacing = 0.sp,
)
val OpenListTitleLarge = TextStyle( // titleL：卡片标题、弹层标题、主页 AppBar 标题
    fontSize = 20.sp, fontWeight = FontWeight.W600,
    lineHeight = 28.sp, letterSpacing = 0.sp,
)
val OpenListTitleMedium = TextStyle( // titleM：文件名、槽位标签、设置页分组标题
    fontSize = 16.sp, fontWeight = FontWeight.W500,
    lineHeight = 24.sp, letterSpacing = 0.15.sp,
)
val OpenListTitleSmall = TextStyle( // titleS：区块标题
    fontSize = 14.sp, fontWeight = FontWeight.W500,
    lineHeight = 20.sp, letterSpacing = 0.1.sp,
)
val OpenListBodyLarge = TextStyle( // bodyL：列表主行、设置页条目标题
    fontSize = 16.sp, fontWeight = FontWeight.W400,
    lineHeight = 24.sp, letterSpacing = 0.5.sp,
)
val OpenListBodyMedium = TextStyle( // bodyM：正文、副标题、对话框正文
    fontSize = 14.sp, fontWeight = FontWeight.W400,
    lineHeight = 20.sp, letterSpacing = 0.25.sp,
)
val OpenListBodySmall = TextStyle( // bodyS：辅助信息、时间戳
    fontSize = 12.sp, fontWeight = FontWeight.W400,
    lineHeight = 16.sp, letterSpacing = 0.4.sp,
)
val OpenListLabelLarge = TextStyle( // labelL：按钮、菜单项文字
    fontSize = 14.sp, fontWeight = FontWeight.W500,
    lineHeight = 20.sp, letterSpacing = 0.1.sp,
)
val OpenListLabelMedium = TextStyle( // labelM：徽章、组标题、底部导航标签
    fontSize = 12.sp, fontWeight = FontWeight.W500,
    lineHeight = 16.sp, letterSpacing = 0.5.sp,
)
val OpenListLabelSmall = TextStyle( // labelS：最弱辅助、日志级别徽标
    fontSize = 11.sp, fontWeight = FontWeight.W500,
    lineHeight = 16.sp, letterSpacing = 0.5.sp,
)

// ---------- 派生样式 ----------
/** 日志内容（色 onSurface 由 UI 侧提供） */
val LogTitle = TextStyle(
    fontSize = 13.sp, fontWeight = FontWeight.W400,
    lineHeight = 19.5.sp, letterSpacing = 0.5.sp,
)

/** 日志时间（色 onSurfaceVariant 由 UI 侧提供） */
val LogSubtitle = TextStyle(
    fontSize = 12.sp, fontWeight = FontWeight.W400,
    lineHeight = 17.sp, letterSpacing = 0.25.sp,
)

/** 日志级别徽标文字（级别色 LogRed/LogWarn/LogInfo/LogGreen 由 UI 侧按级别提供） */
val LogLevelText = TextStyle(
    fontSize = 11.sp, fontWeight = FontWeight.W500,
    lineHeight = 16.sp, letterSpacing = 0.5.sp,
)

/** Snackbar 标题（固定反色白） */
val SnackBarTitle = TextStyle(
    fontSize = 16.sp, fontWeight = FontWeight.W700,
    color = SnackOnBackground,
)

/** Snackbar 正文（固定反色白） */
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

/** 主题 Typography：Blue Light 12 级显式覆写；未覆盖槽位保持 M3 默认 */
val OpenListTypography = Typography(
    displaySmall = OpenListDisplaySmall,
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
