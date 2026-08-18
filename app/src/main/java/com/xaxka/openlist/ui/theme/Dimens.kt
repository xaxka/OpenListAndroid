package com.xaxka.openlist.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/*
 * 尺寸令牌：Blue Light UI §2.5（间距节奏 4/6/8/12/16/20/28/32）。
 * 触控目标 ≥44dp（§6 无障碍）；StatusTopInset（Web 页状态栏占位）为动态值，
 * 须用 WindowInsets.statusBars 获取，禁止硬编码。
 */

object Dimens {

    // ---------- Blue Light 间距标尺（§2.5） ----------
    val PageMargin = 16.dp // 页面左右留白
    val CardSpacing = 12.dp // 卡片间距
    val CardPadding = 12.dp // 卡片内边距
    val RowPaddingH = 16.dp // 列表行水平内边距
    val RowPaddingV = 12.dp // 列表行垂直内边距
    val SheetPaddingH = 20.dp // 底部弹层水平内边距
    val SheetPaddingBottom = 28.dp // 底部弹层底部留白
    val EmptySpace = 32.dp // 空状态整体留白
    val IconSlot = 48.dp // 列表行固定图标区（防内容位移）
    val TouchTarget = 44.dp // 最小触控目标

    // ---------- 内边距 / 间距 ----------
    val DividerTitlePaddingH = 16.dp
    val DividerTitlePaddingV = 8.dp
    val ListTilePaddingStart = 16.dp
    val ListTilePaddingEnd = 16.dp
    val ListTileMinVerticalPadding = 12.dp
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

    // ---------- 控件宽高 ----------
    val AppBarToolbarHeight = 64.dp
    /** 主页 AppBar 总高：56dp（省空间，工具型紧凑顶栏） */
    val AppBarHeight = 56.dp
    val NavHeight = 80.dp
    val NavIndicatorWidth = 56.dp
    val NavIndicatorHeight = 32.dp
    val NavIconSize = 24.dp
    val NavSvgIconSize = 32.dp
    val FABSize = 56.dp
    val FabIconStop = 48.dp
    val FabIconSend = 32.dp

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
    val SwitchThumbOff = 16.dp
    val SwitchThumbOn = 24.dp
    val ListTileHeight2Line = 72.dp
    val ListTileHeight2LineDense = 64.dp
    val PopupMenuItemHeight = 48.dp
    val AboutIconSize = 48.dp
    val DividerPreferenceHeight = 1.dp

    // ---------- 分割线 / 描边 ----------
    val DividerThickness = 1.dp

    /** 卡片描边：默认卡 1dp outlineVariant（§3.1 描边卡） */
    val CardBorderWidth = 1.dp

    /** 重复绑定/冲突槽位红色描边 2dp（§4 批量编辑页） */
    val CardBorderWidthError = 2.dp
    val DividerSpaceDefault = 16.dp
    val DividerIndent = 0.dp
    val SwitchTrackOutlineWidth = 2.dp
    val InputBorderWidthIdle = 1.dp
    val InputBorderWidthFocus = 2.dp

    // ---------- Elevation（§2.7：层级用描边表达，阴影仅限浮起对象） ----------
    /** FAB 浮起阴影：6dp（cardFloatPressed 档） */
    val FabElevation = 6.dp
    val AppBarElevation = 0.dp
    val AppBarElevationScrolled = 3.dp
    val NavElevation = 3.dp
    val DialogElevation = 6.dp
    val MenuElevation = 3.dp
    val TextButtonElevation = 0.dp
    val FilledButtonElevation = 0.dp

    /** 阴影卡默认 3dp（按下 6dp）；页面内卡片一律 0 用 1dp 描边替代 */
    val CardElevationFloat = 3.dp
    val CardElevationPressed = 6.dp
    val CardElevationDefault = 0.dp
}
