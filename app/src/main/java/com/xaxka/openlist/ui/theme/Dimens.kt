package com.xaxka.openlist.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/*
 * 尺寸令牌：PIXEL_SPEC §3/§6.3，Flutter dp → Compose dp 1:1。
 * StatusTopInset（Web 页状态栏占位）为动态值，须用 WindowInsets.statusBars 获取，禁止硬编码。
 */

object Dimens {

    // ---------- §3.1 内边距 / 间距 ----------
    val DividerTitlePaddingH = 16.dp
    val DividerTitlePaddingV = 8.dp
    val ListTilePaddingStart = 16.dp
    val ListTilePaddingEnd = 24.dp
    val ListTileMinVerticalPadding = 8.dp
    val ListTileMinLeadingWidth = 24.dp
    val ListTileHorizontalTitleGap = 16.dp
    val DialogTitlePaddingHorizontal = 24.dp
    val DialogTitlePaddingTop = 24.dp
    val DialogTitlePaddingBottomNoContent = 20.dp
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
    val InputContentPadding = PaddingValues(start = 12.dp, top = 24.dp, end = 12.dp, bottom = 16.dp)
    val InputGapPadding = 4.dp
    val PopupMenuItemPaddingH = 12.dp

    // ---------- §3.2 控件宽高 ----------
    val AppBarToolbarHeight = 64.dp
    /** 主页 AppBar 总高：对齐源 Flutter AppBar 默认 56dp（M3 默认 64dp 偏高，省空间） */
    val AppBarHeight = 56.dp
    val NavHeight = 80.dp
    val NavIndicatorWidth = 56.dp
    val NavIndicatorHeight = 32.dp
    val NavIconSize = 24.dp
    val NavSvgIconSize = 32.dp
    val FABSize = 56.dp
    val FabIconStop = 48.dp
    val FabIconSend = 32.dp
    val IconButtonSize = 40.dp
    val IconDefault = 24.dp
    val ChevronIconSize = 24.dp
    val ProgressCircularSize = 24.dp
    val ProgressCircularStrokeWidth = 2.dp
    val ProgressLinearHeight = 4.dp
    val ButtonMinWidth = 64.dp
    val ButtonHeight = 40.dp
    val SwitchTrackWidth = 52.dp
    val SwitchTrackHeight = 32.dp
    val SwitchThumbOff = 16.dp
    val SwitchThumbOn = 24.dp
    val ListTileHeight2Line = 72.dp
    val ListTileHeight2LineDense = 64.dp
    val PopupMenuItemHeight = 48.dp
    val AboutIconSize = 48.dp
    val DividerPreferenceHeight = 1.dp

    // ---------- §3.4 分割线 / 描边 ----------
    val DividerThickness = 1.dp
    val DividerSpaceDefault = 16.dp
    val DividerIndent = 0.dp
    val SwitchTrackOutlineWidth = 2.dp
    val InputBorderWidthIdle = 1.dp
    val InputBorderWidthFocus = 2.dp

    // ---------- §3.5 Elevation ----------
    val FabElevation = 8.dp
    val AppBarElevation = 0.dp
    val AppBarElevationScrolled = 3.dp
    val NavElevation = 3.dp
    val DialogElevation = 6.dp
    val MenuElevation = 3.dp
    val TextButtonElevation = 0.dp
    val FilledButtonElevation = 0.dp
}
