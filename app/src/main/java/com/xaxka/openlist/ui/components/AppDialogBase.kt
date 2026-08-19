package com.xaxka.openlist.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xaxka.openlist.ui.theme.Dimens

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
