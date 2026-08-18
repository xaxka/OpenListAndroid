package com.xaxka.openlist.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.ShapeDialogR28
import com.xaxka.openlist.ui.theme.ShapeInputOutlineR4

/**
 * 密码对话框（照源 tmp/lib/pages/alist/pwd_edit_dialog.dart）：
 * AlertDialog（surface、R28）、标题「修改admin密码」headlineSmall、
 * OutlineTextField R4/无填充/label「admin密码」、取消 TextButton + 确定 FilledButton。
 */
@Composable
fun PasswordDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = ShapeDialogR28,
        title = {
            Text(
                Strings.EDIT_ADMIN_PASSWORD,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(Strings.ADMIN_PASSWORD_LABEL) },
                    singleLine = true,
                    shape = ShapeInputOutlineR4,
                    // R9：无填充（filled=false），描边默认 outline 1dp / 聚焦 primary 2dp
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
            }
        },
        dismissButton = {
            DialogTextButton(Strings.CANCEL, onClick = onDismissRequest)
        },
        confirmButton = {
            DialogTextButton(
                Strings.OK,
                onClick = { onConfirm(password) },
                filled = true,
            )
        },
    )
}

/**
 * 对话框按钮（照源 pwd_edit_dialog.dart:41-51 + SDK 默认，R4）：
 * TextButton H12/V8、FilledButton H24/V8、min 64×40、Stadium 默认形状、LabelLarge 默认文字。
 */
@Composable
internal fun DialogTextButton(
    text: String,
    onClick: () -> Unit,
    filled: Boolean = false,
) {
    val modifier = Modifier.defaultMinSize(
        minWidth = Dimens.ButtonMinWidth,
        minHeight = Dimens.ButtonHeight,
    )
    if (filled) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(
                horizontal = Dimens.FilledButtonPaddingH,
                vertical = Dimens.ButtonPaddingV,
            ),
        ) { Text(text) }
    } else {
        TextButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(
                horizontal = Dimens.TextButtonPaddingH,
                vertical = Dimens.ButtonPaddingV,
            ),
        ) { Text(text) }
    }
}
