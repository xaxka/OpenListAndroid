package com.xaxka.openlist.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.xaxka.openlist.qbt.QBittorrentManager
import com.xaxka.openlist.qbt.QBittorrentSpec
import com.xaxka.openlist.ui.theme.InputHint
import com.xaxka.openlist.ui.theme.InputLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置子页面：BT 下载（内置 qBittorrent Enhanced nox）。
 *
 * 内置 musl 静态二进制随 APK 打包（jniLibs 改名 libqbittorrent-nox.so），
 * 随 OpenList 服务启停；WebUI 默认仅监听 127.0.0.1 且 localhost 免鉴权，
 * 由「打开 WebUI」跳系统浏览器管理。可开启「局域网访问」：监听切 0.0.0.0，
 * 其他设备经 http://<本机IP>:<端口> 登录（用户名/密码；本机仍免登录），
 * 开启前强制设置密码，凭据经 localhost 通道下发。
 * musl 静态二进制无法读 Android 的 DNS 配置，tracker/DHT 域名经 App 内置
 * 本机 SOCKS5 代理（随机高位端口，由 Android 系统解析）转发，详见 QBittorrentManager。
 */
@Composable
fun QBittorrentSettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPortDialog by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    // 密码框确认后是否自动开启局域网访问（开关守卫流程复用同一对话框）
    var pendingLanEnable by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 导出事件日记：系统「另存为」选择目标，把最近 24h 事件写到用户指定文件
    val exportDiaryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val text = viewModel.qbtEventDiaryText()
                if (text.isEmpty()) return@withContext false
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(text.toByteArray())
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
            }
            viewModel.snack(if (ok) "事件日记已导出" else "导出失败或无日记")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackEvents.collect { event ->
            val showJob = launch {
                snackbarHostState.showSnackbar(event.message)
            }
            delay(event.durationMillis)
            snackbarHostState.currentSnackbarData?.dismiss()
            showJob.join()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            SettingsSubPageTopBar(
                title = "BT 下载（qBittorrent Enhanced）",
                onBack = { navController.popBackStack() }
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                SettingsSwitchPreference(
                    title = "启用 BT 下载",
                    subtitle = "随服务启停；内置 qbittorrent-enhanced-nox v${QBittorrentSpec.EMBEDDED_VERSION}",
                    icon = Icons.Outlined.CloudDownload,
                    value = state.qbtEnabled,
                    onCheckedChange = viewModel::setQbtEnabled
                )
                SettingsBasicPreference(
                    title = "WebUI 端口",
                    subtitle = if (state.qbtLanAccess) {
                        "本机 127.0.0.1:${state.qbtPort.ifBlank { QBittorrentSpec.DEFAULT_WEBUI_PORT.toString() }}；其他设备用本机局域网 IP 同端口"
                    } else {
                        "127.0.0.1:${state.qbtPort.ifBlank { QBittorrentSpec.DEFAULT_WEBUI_PORT.toString() }}（仅本机可访问）"
                    },
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Settings) },
                    onTap = { showPortDialog = true }
                )

                SettingsDividerPreference("局域网访问")

                SettingsSwitchPreference(
                    title = "允许局域网访问 WebUI",
                    subtitle = if (state.qbtLanAccess) {
                        "已开放：其他设备访问 http://<本机IP>:${state.qbtPort.ifBlank { QBittorrentSpec.DEFAULT_WEBUI_PORT.toString() }} 并登录（本机仍免登录）"
                    } else {
                        "默认关闭（仅本机 127.0.0.1 可访问）；开启需设置登录密码"
                    },
                    icon = Icons.Outlined.Lan,
                    value = state.qbtLanAccess,
                    onCheckedChange = { value ->
                        if (value && state.qbtPassword.isBlank()) {
                            // 无密码不开放 0.0.0.0：先引导设置密码（确认后自动开启）
                            pendingLanEnable = true
                            showPasswordDialog = true
                        } else {
                            viewModel.setQbtLanAccess(value)
                        }
                    }
                )
                SettingsBasicPreference(
                    title = "登录用户名",
                    subtitle = state.qbtUsername,
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Person) },
                    onTap = { showUsernameDialog = true }
                )
                SettingsBasicPreference(
                    title = "登录密码",
                    subtitle = if (state.qbtPassword.isBlank()) "未设置（开启局域网访问前必须设置）" else "已设置（点击修改）",
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Password) },
                    onTap = {
                        pendingLanEnable = false
                        showPasswordDialog = true
                    }
                )

                SettingsDividerPreference("运行状态")

                SettingsBasicPreference(
                    title = "重启 BT 下载",
                    subtitle = "异常退出或无响应时手动重启恢复",
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Refresh) },
                    onTap = viewModel::restartQbt
                )

                QbtStatusSection(
                    detail = state.qbtDetail,
                    onOpenWebUi = {
                        val url = state.qbtDetail.webUiUrl
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )

                SettingsBasicPreference(
                    title = "导出事件日记",
                    subtitle = "保存最近 24 小时事件到文件（跨重启不丢）",
                    leading = { SettingsPreferenceIcon(Icons.Outlined.SaveAlt) },
                    onTap = { exportDiaryLauncher.launch("qbittorrent-events.txt") }
                )
            }
        }
        SnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showPortDialog) {
        QbtTextDialog(
            title = "WebUI 端口",
            initial = state.qbtPort,
            placeholder = QBittorrentSpec.DEFAULT_WEBUI_PORT.toString(),
            onDismiss = { showPortDialog = false },
            onConfirm = { text ->
                showPortDialog = false
                viewModel.setQbtPort(text.trim())
            }
        )
    }

    if (showUsernameDialog) {
        QbtTextDialog(
            title = "登录用户名",
            initial = state.qbtUsername,
            placeholder = "admin",
            onDismiss = { showUsernameDialog = false },
            onConfirm = { text ->
                showUsernameDialog = false
                viewModel.setQbtUsername(text.trim().ifBlank { "admin" })
            }
        )
    }

    if (showPasswordDialog) {
        QbtPasswordDialog(
            title = if (pendingLanEnable) "设置登录密码并开启局域网访问" else "登录密码",
            onDismiss = {
                showPasswordDialog = false
                pendingLanEnable = false
            },
            onConfirm = { password ->
                showPasswordDialog = false
                viewModel.setQbtPassword(password)
                if (pendingLanEnable) {
                    viewModel.setQbtLanAccess(true)
                }
                pendingLanEnable = false
            }
        )
    }
}

/** 只读运行状态区：进程状态 / 版本 / WebUI / 保存路径 / DNS 代理。 */
@Composable
private fun QbtStatusSection(
    detail: QBittorrentManager.Status,
    onOpenWebUi: () -> Unit
) {
    if (detail.phase == QBittorrentManager.Phase.STOPPED) return

    StatusCard("运行状态") {
        QbtStatusKV("状态", detail.summary)
        if (detail.version.isNotBlank()) QbtStatusKV("版本", detail.version)
        QbtStatusKV("WebUI", detail.webUiUrl)
        if (detail.phase == QBittorrentManager.Phase.RUNNING) {
            QbtStatusKV(
                "监听",
                if (detail.lanAccess) "0.0.0.0（局域网需登录，本机 localhost 免登录）" else "仅本机（localhost 免鉴权）",
            )
            if (detail.proxyPort != 0) QbtStatusKV("DNS 代理", "127.0.0.1:${detail.proxyPort}（SOCKS5，tracker 域名经系统解析）")
        }
        if (detail.savePath.isNotBlank()) QbtStatusKV("保存路径", detail.savePath)
        if (detail.detail.isNotBlank()) QbtStatusKV("说明", detail.detail)

        if (detail.phase == QBittorrentManager.Phase.RUNNING) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(onClick = onOpenWebUi) {
                    Icon(
                        Icons.Outlined.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("打开 WebUI")
                }
            }
        }
    }
}

/** 只读键值行；value 为空则不渲染。 */
@Composable
private fun QbtStatusKV(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 密码输入对话框：掩码输入，空确认时提示（不关闭）。 */
@Composable
private fun QbtPasswordDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }
    SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = false
                },
                label = { Text(title, style = InputLabel) },
                placeholder = { Text("至少 6 位，避免弱密码", style = InputHint) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                isError = error,
                supportingText = if (error) {
                    { Text("密码过短，至少 6 位", style = InputHint) }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        actions = {
            SettingsDialogTextButton(text = "取消", onClick = onDismiss)
            SettingsDialogTextButton(
                text = "确定",
                onClick = {
                    if (text.length >= 6) onConfirm(text)
                    else error = true
                }
            )
        }
    )
}

/** 端口/用户名等单行文本输入对话框（无密码掩码；空值提交回退默认端口）。 */
@Composable
private fun QbtTextDialog(
    title: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                label = { Text(title, style = InputLabel) },
                placeholder = { Text(placeholder, style = InputHint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        actions = {
            SettingsDialogTextButton(text = "取消", onClick = onDismiss)
            SettingsDialogTextButton(text = "确定", onClick = { onConfirm(text) })
        }
    )
}
