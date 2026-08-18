package com.xaxka.openlist.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.xaxka.openlist.ui.theme.Dimens

/**
 * 设置页分组标题（Blue Light §4 设置页模式）：
 * outlineVariant 1dp 50% 分割线 + 居中 labelMedium 标题（textSecondary 灰字）。
 */
@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = Dimens.DividerPreferenceHeight,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimens.DividerTitlePaddingH,
                    vertical = Dimens.DividerTitlePaddingV,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 设置页基础条目（照源 preference_widgets.dart BasicPreference，R7）：
 * 两行高 ≥72、start16/end24、leading 区宽 24 + gap 16、
 * 标题 BodyLarge/onSurface、副标题 BodyMedium/onSurfaceVariant。
 */
@Composable
fun SettingsTextRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.ListTileHeight2Line)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = Dimens.ListTilePaddingStart,
                end = Dimens.ListTilePaddingEnd,
                top = Dimens.ListTileMinVerticalPadding,
                bottom = Dimens.ListTileMinVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier.width(Dimens.ListTileMinLeadingWidth),
                contentAlignment = Alignment.Center,
            ) { leading() }
            Spacer(Modifier.width(Dimens.ListTileHorizontalTitleGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Dimens.ListTileHorizontalTitleGap))
            Box(contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

/**
 * 设置页开关条目（照源 preference_widgets.dart SwitchPreference，R8）：
 * trailing 为 Switch（未选中轨道 surfaceVariant、描边 outline、滑块 outlineVariant），整行点击切换。
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    SettingsTextRow(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        leading = leading,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        },
        onClick = { onCheckedChange(!checked) },
    )
}
