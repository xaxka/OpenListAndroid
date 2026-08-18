package com.xaxka.openlist.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.ShapeDialogR28
import com.xaxka.openlist.ui.theme.SnackBackground
import com.xaxka.openlist.ui.theme.SnackBarMessage
import com.xaxka.openlist.ui.theme.SnackOnBackground

/**
 * 设置页分组标题（Blue Light §4 设置页模式）：分割线（1dp outlineVariant 50%）+
 * 居中标题（labelMedium + textSecondary 灰字，不抢条目视觉）。
 */
@Composable
internal fun SettingsDividerPreference(title: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = Dimens.DividerPreferenceHeight,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimens.DividerTitlePaddingH,
                    vertical = Dimens.DividerTitlePaddingV
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 设置页基础条目：ListTile 复刻（两行高 72、start16/end24、leading 区 24 + gap16）。
 * 对应源 preference_widgets.dart BasicPreference。
 */
@Composable
internal fun SettingsBasicPreference(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onTap: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.ListTileHeight2Line)
            .clickable(enabled = onTap != null, onClick = { onTap?.invoke() })
            .padding(
                start = Dimens.ListTilePaddingStart,
                end = Dimens.ListTilePaddingEnd
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier.width(Dimens.ListTileMinLeadingWidth),
                contentAlignment = Alignment.Center
            ) { leading() }
            Spacer(Modifier.width(Dimens.ListTileHorizontalTitleGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(Dimens.ListTileHorizontalTitleGap))
            trailing()
        }
    }
}

/** leading 区图标：24dp、onSurfaceVariant（§1.4 BasicPreference） */
@Composable
internal fun SettingsPreferenceIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .width(Dimens.IconDefault)
            .height(Dimens.IconDefault)
    )
}

/**
 * 设置页开关条目：整行点击同样触发切换；Switch 52×32，
 * 未选中轨道 surfaceVariant / 描边 outline 2dp / 滑块 outlineVariant（§1.4、R8）。
 */
@Composable
internal fun SettingsSwitchPreference(
    title: String,
    subtitle: String,
    icon: ImageVector,
    value: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsBasicPreference(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        leading = { SettingsPreferenceIcon(icon) },
        trailing = {
            Switch(
                checked = value,
                onCheckedChange = onCheckedChange,
                modifier = Modifier
                    .width(Dimens.SwitchTrackWidth)
                    .height(Dimens.SwitchTrackHeight),
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    // 打开态滑块用纯白而非 onPrimary（深藏青 #001D36）：浅蓝轨道
                    // (#91C6FF) 上深色滑块观感近乎黑点，白滑块与 M3 常规观感一致
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outlineVariant,
                    // Flutter M3 Switch 无图标装饰
                    uncheckedIconColor = Color.Transparent
                )
            )
        },
        onTap = { onCheckedChange(!value) }
    )
}

/** 处理状态条目运行中的 24×24 转圈（strokeWidth 2、无轨道） */
@Composable
internal fun SettingsProgressSpinner() {
    CircularProgressIndicator(
        modifier = Modifier
            .width(Dimens.ProgressCircularSize)
            .height(Dimens.ProgressCircularSize),
        strokeWidth = Dimens.ProgressCircularStrokeWidth,
        trackColor = Color.Transparent
    )
}

/**
 * AlertDialog 像素复刻（§1.4/§3.1、R2/R3）：surface 背景、R28 圆角；
 * title LTRB(24,24,24,0)、content LTRB(24,16,24,24)、actions LTRB(24,0,24,24) + 按钮间距 8。
 */
@Composable
internal fun SettingsAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    content: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    dismissOnClickOutside: Boolean = true
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = dismissOnClickOutside,
            dismissOnBackPress = true
        )
    ) {
        Surface(shape = ShapeDialogR28, color = MaterialTheme.colorScheme.surface) {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = Dimens.DialogTitlePaddingHorizontal,
                        top = Dimens.DialogTitlePaddingTop,
                        end = Dimens.DialogTitlePaddingHorizontal
                    )
                )
                Box(
                    Modifier.padding(
                        start = Dimens.DialogContentPaddingHorizontal,
                        top = Dimens.DialogContentPaddingTop,
                        end = Dimens.DialogContentPaddingHorizontal,
                        bottom = Dimens.DialogContentPaddingBottom
                    )
                ) { content() }
                Row(
                    modifier = Modifier
                        .padding(Dimens.DialogActionsPadding)
                        .align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.DialogButtonSpacing)
                ) { actions() }
            }
        }
    }
}

/** 对话框按钮：min 64×40、H12/V8、胶囊、LabelLarge + primary（§3.1/§3.2） */
@Composable
internal fun SettingsDialogTextButton(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = Dimens.ButtonMinWidth)
            .heightIn(min = Dimens.ButtonHeight),
        contentPadding = PaddingValues(
            horizontal = Dimens.TextButtonPaddingH,
            vertical = Dimens.ButtonPaddingV
        ),
        shape = CircleShape
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * GetSnackBar 复刻（§4.7）：0xFF303030 底、无圆角、内边距 16、
 * 正文 14/w400 白、主按钮 TextButton（右距 4、LabelLarge + primary）。
 */
@Composable
internal fun SettingsSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier = modifier) { data: SnackbarData ->
        Surface(color = SnackBackground, shape = RectangleShape) {
            Row(
                modifier = Modifier.padding(Dimens.SnackBarPaddingAll),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    data.visuals.message,
                    style = SnackBarMessage,
                    color = SnackOnBackground,
                    modifier = Modifier.weight(1f)
                )
                data.visuals.actionLabel?.let { label ->
                    TextButton(
                        onClick = { data.performAction() },
                        contentPadding = PaddingValues(
                            start = Dimens.TextButtonPaddingH,
                            end = Dimens.SnackBarMainButtonPaddingR,
                            top = Dimens.ButtonPaddingV,
                            bottom = Dimens.ButtonPaddingV
                        )
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
