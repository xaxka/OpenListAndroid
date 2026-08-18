package com.xaxka.openlist.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.ShapeDialogR28

/**
 * 对话框基座：按 Flutter 3.19 AlertDialog 默认值逐项复刻
 * 背景 surface（R2）、圆角 28、elevation 6；
 * title LTRB(24,24,24,0)、content LTRB(24,16,24,24)、actions LTRB(24,0,24,24) 右对齐、按钮间距 8（R3）。
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    content: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = dismissOnClickOutside,
            dismissOnBackPress = dismissOnBackPress,
        ),
    ) {
        DialogContent(
            title = title,
            content = content,
            actions = actions,
            modifier = modifier,
        )
    }
}

/** 对话框内容主体（更新对话框的自绘遮罩版本也复用此布局） */
@Composable
internal fun DialogContent(
    title: @Composable () -> Unit,
    content: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = ShapeDialogR28,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = Dimens.DialogElevation,
        modifier = modifier,
    ) {
        Column {
            Box(
                Modifier.padding(
                    start = Dimens.DialogTitlePaddingHorizontal,
                    top = Dimens.DialogTitlePaddingTop,
                    end = Dimens.DialogTitlePaddingHorizontal,
                )
            ) { title() }
            Box(
                Modifier.padding(
                    start = Dimens.DialogContentPaddingHorizontal,
                    top = Dimens.DialogContentPaddingTop,
                    end = Dimens.DialogContentPaddingHorizontal,
                    bottom = Dimens.DialogContentPaddingBottom,
                )
            ) { content() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.DialogActionsPadding),
                horizontalArrangement = Arrangement.spacedBy(Dimens.DialogButtonSpacing, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/** 对话框文字按钮：min 64×40、H12/V8、胶囊形（R4） */
@Composable
fun DialogTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = Dimens.ButtonMinWidth),
        contentPadding = PaddingValues(
            horizontal = Dimens.TextButtonPaddingH,
            vertical = Dimens.ButtonPaddingV,
        ),
    ) {
        Text(text = text)
    }
}

/** 对话框实心按钮：min 64×40、H24/V8、胶囊形（R4） */
@Composable
fun DialogFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = Dimens.ButtonMinWidth),
        contentPadding = PaddingValues(
            horizontal = Dimens.FilledButtonPaddingH,
            vertical = Dimens.ButtonPaddingV,
        ),
        colors = ButtonDefaults.buttonColors(),
    ) {
        Text(text = text)
    }
}
