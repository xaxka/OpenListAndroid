package com.xaxka.openlist.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/*
 * 尺寸令牌：Blue Light UI §2.5（间距节奏 4/6/8/12/16/20/28/32）。
 * StatusTopInset（Web 页状态栏占位）为动态值，须用 WindowInsets.statusBars 获取，禁止硬编码。
 */

object Dimens {

    // ---------- Blue Light 间距标尺（§2.5） ----------
    val PageMargin = 16.dp // 页面左右留白
    val CardSpacing = 12.dp // 卡片间距
    val CardPadding = 12.dp // 卡片内边距
    val RowPaddingH = 16.dp // 列表行水平内边距
    val RowPaddingV = 12.dp // 列表行垂直内边距

    // ---------- 内边距 / 间距 ----------
    val DividerTitlePaddingH = 16.dp
    val DividerTitlePaddingV = 8.dp
    val ListTilePaddingStart = 16.dp
    val ListTilePaddingEnd = 16.dp
    val ListTileMinLeadingWidth = 24.dp
    val ListTileHorizontalTitleGap = 16.dp
    val DialogTitlePaddingHorizontal = 24.dp
    val DialogTitlePaddingTop = 24.dp
    val DialogContentPaddingHorizontal = 24.dp
    val DialogContentPaddingTop = 16.dp
    val DialogContentPaddingBottom = 24.dp
    val DialogActionsPadding = PaddingValues(start = 24.dp, top = 0.dp, end = 24.dp, bottom = 24.dp)
    val DialogButtonSpacing = 8.dp
    val AboutRowTextGapH = 24.dp
    val AboutTextVerticalGap = 18.dp
    val TextButtonPaddingH = 12.dp
    val FilledButtonPaddingH = 24.dp
    val ButtonPaddingV = 8.dp
    val SnackBarPaddingAll = 16.dp
    val SnackBarTitleMessageGap = 6.dp
    val SnackBarMainButtonPaddingR = 4.dp
    val PopupMenuItemPaddingH = 12.dp

    // ---------- 控件宽高 ----------
    /** 主页 AppBar 总高：56dp（省空间，工具型紧凑顶栏） */
    val AppBarHeight = 56.dp
    val NavIconSize = 24.dp
    val FABSize = 56.dp
    val FabIconSend = 32.dp
    val FabIconDot = 16.dp

    /** 图标按钮尺寸：满足触控目标 ≥44dp（§6），M3 最小 48dp 内的最紧凑合规值 */
    val IconButtonSize = 44.dp
    val IconDefault = 24.dp
    val ChevronIconSize = 24.dp
    val ProgressCircularSize = 24.dp
    val ProgressCircularStrokeWidth = 2.dp

    /** 线性进度条高度：4dp 圆角轨道（§3.8） */
    val ProgressLinearHeight = 4.dp
    val ButtonMinWidth = 64.dp
    val ButtonHeight = 40.dp
    val SwitchTrackWidth = 52.dp
    val SwitchTrackHeight = 32.dp
    val ListTileHeight2Line = 72.dp
    val ListTileHeight2LineDense = 64.dp
    val PopupMenuItemHeight = 48.dp
    val AboutIconSize = 48.dp
    val DividerPreferenceHeight = 1.dp

    // ---------- Elevation（§2.7：层级用描边表达，阴影仅限浮起对象） ----------
    /** FAB 浮起阴影：6dp（cardFloatPressed 档） */
    val FabElevation = 6.dp
    val MenuElevation = 3.dp
}
