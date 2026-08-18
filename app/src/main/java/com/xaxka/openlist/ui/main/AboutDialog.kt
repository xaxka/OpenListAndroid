package com.xaxka.openlist.ui.main

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import com.xaxka.openlist.BuildConfig
import com.xaxka.openlist.R
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.ShapeDialogR28

/** 内核版本 releases 链接（RENAME_MAP A36：URL 归属上游 OpenListTeam，保留） */
private const val CORE_RELEASE_URL = "https://github.com/OpenListTeam/OpenList/releases/tag/"

/** 应用版本 releases 链接（RENAME_MAP A37：owner 统一 xaxka） */
private const val APP_RELEASE_URL = "https://github.com/xaxka/OpenListFlutter/releases/tag/"

/** 许可证页（源为 Flutter 应用内 LicensePage，原生以仓库 LICENSE 页等价替代） */
private const val LICENSE_URL = "https://github.com/xaxka/OpenListFlutter/blob/main/LICENSE"

/**
 * 关于对话框（照源 tmp/lib/pages/alist/about_dialog.dart + Flutter AboutDialog 结构，PIXEL_SPEC §4.6）：
 * 图标 48 + 应用名 headlineSmall + 版本「名 (码)」bodyMedium；
 * 两个链接按钮：点击 chooser 打开、长按复制并回调提示；操作区「查看许可 / 关闭」。
 */
@Composable
fun AboutDialog(
    coreVersion: String,
    onDismissRequest: () -> Unit,
    onCopiedToClipboard: () -> Unit,
) {
    val context = LocalContext.current
    val appVersionName = BuildConfig.VERSION_NAME
    val appVersionCode = BuildConfig.VERSION_CODE
    val coreUrl = CORE_RELEASE_URL + coreVersion
    val appUrl = APP_RELEASE_URL + appVersionName

    fun launchChooser(url: String, title: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(Intent.createChooser(intent, title))
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = ShapeDialogR28,
        text = {
            Column {
                // 头部：图标 48 + 间距 24 + 应用名/版本/法律行空位（SDK about.dart 结构）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.openlist_logo),
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.AboutIconSize),
                    )
                    Spacer(Modifier.width(Dimens.AboutRowTextGapH))
                    Column {
                        Text(
                            Strings.APP_NAME,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "$appVersionName ($appVersionCode)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 源 legalese 为空串（BodySmall），无可见内容不渲染
                        Spacer(Modifier.height(Dimens.AboutTextVerticalGap))
                    }
                }
                AboutLinkButton(
                    label = Strings.ABOUT_LINK_CORE,
                    url = coreUrl,
                    chooserTitle = Strings.ABOUT_LINK_CORE,
                    onCopiedToClipboard = onCopiedToClipboard,
                )
                AboutLinkButton(
                    label = Strings.ABOUT_LINK_APP,
                    url = appUrl,
                    chooserTitle = Strings.ABOUT_LINK_APP,
                    onCopiedToClipboard = onCopiedToClipboard,
                )
            }
        },
        dismissButton = {
            DialogTextButton(
                Strings.VIEW_LICENSES,
                onClick = { launchChooser(LICENSE_URL, Strings.ABOUT_LINK_CORE) },
            )
        },
        confirmButton = {
            DialogTextButton(Strings.CLOSE, onClick = onDismissRequest)
        },
    )
}

/** 版本链接按钮（ListBody 内整行拉伸、LabelLarge/primary；点击 chooser、长按复制，A38） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutLinkButton(
    label: String,
    url: String,
    chooserTitle: String,
    onCopiedToClipboard: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(
                minWidth = Dimens.ButtonMinWidth,
                minHeight = Dimens.ButtonHeight,
            )
            .combinedClickable(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(Intent.createChooser(intent, chooserTitle))
                },
                onLongClick = {
                    clipboard.setText(AnnotatedString(url))
                    onCopiedToClipboard()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
