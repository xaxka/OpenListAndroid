package com.xaxka.openlist.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
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
 * 设置子页：qbittorrent（内置 qBittorrent Enhanced nox）。
 *
 * 内置 bionic 动态链接二进制随 APK 打包（jniLibs 改名 libqbittorrent-nox.so），
 * 随 OpenList 服务启停；WebUI 默认仅监听 127.0.0.1 且 localhost 免鉴权，
 * 由「打开 WebUI」跳系统浏览器管理。可开启「局域网访问」：监听切 0.0.0.0，
 * 其他设备经 http://<本机IP>:<端口> 登录（默认 admin/adminadmin，可在
 * 「登录账号」中修改密码；本机仍免登录）。
 * 内存：启动时下发手机场景调优（磁盘缓存上限 64MiB 等）；运行状态卡实时
 * 展示 nox 进程常驻内存（VmRSS）。
 */
@Composable
fun QBittorrentSettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPortDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }

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
                title = "qbittorrent",
                onBack = { navController.popBackStack() }
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                SettingsSwitchPreference(
                    title = "启用 qbittorrent",
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
                        "默认关闭（仅本机 127.0.0.1 可访问）"
                    },
                    icon = Icons.Outlined.Lan,
                    value = state.qbtLanAccess,
                    onCheckedChange = viewModel::setQbtLanAccess
                )
                SettingsBasicPreference(
                    title = "登录账号",
                    subtitle = if (state.qbtPasswordCustom) {
                        "admin / 已设自定义密码（点此修改）"
                    } else {
                        "admin / adminadmin（默认密码，点此修改）"
                    },
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Person) },
                    onTap = { showPasswordDialog = true }
                )

                SettingsDividerPreference("运行状态")

                SettingsBasicPreference(
                    title = "重启 qbittorrent",
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

    if (showPasswordDialog) {
        QbtPasswordDialog(
            hasCustomPassword = state.qbtPasswordCustom,
            onDismiss = { showPasswordDialog = false },
            onConfirm = { password ->
                showPasswordDialog = false
                viewModel.setQbtWebUiPassword(password)
            },
            onResetDefault = {
                showPasswordDialog = false
                viewModel.resetQbtWebUiPassword()
            }
        )
    }
}

/** 只读运行状态区：进程状态 / 版本 / WebUI / 保存路径 / 内存占用。 */
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
            // nox 进程常驻内存（VmRSS，5s 巡检采样；未采样到则不显示）
            QbtStatusKV("内存占用", detail.memUsageText)
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

/** 端口等单行文本输入对话框（无密码掩码；空值提交回退默认端口）。 */
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

/**
 * 登录账号改密对话框：新密码 + 确认两栏（qb 要求 ≥6 字符）；已设自定义密码时
 * 提供「恢复默认密码 adminadmin」入口。确认后运行中实例即时生效（qb 侧哈希
 * 落盘），未运行时下次启动随配置写入。
 */
@Composable
private fun QbtPasswordDialog(
    hasCustomPassword: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onResetDefault: () -> Unit
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }

    fun validate(): String {
        if (password.length < QBittorrentSpec.WEBUI_PASSWORD_MIN_LENGTH)
            return "密码至少 ${QBittorrentSpec.WEBUI_PASSWORD_MIN_LENGTH} 个字符"
        if (password != password.trim()) return "密码不能以空格开头或结尾"
        if (password != confirm) return "两次输入的密码不一致"
        return ""
    }

    SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = "修改 WebUI 密码",
        content = {
            Column {
                QbtPasswordField(
                    label = "新密码（≥${QBittorrentSpec.WEBUI_PASSWORD_MIN_LENGTH} 位）",
                    value = password,
                    onValueChange = {
                        password = it
                        error = ""
                    }
                )
                QbtPasswordField(
                    label = "确认新密码",
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        error = ""
                    },
                    modifier = Modifier.padding(top = 12.dp)
                )
                if (error.isNotEmpty()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Text(
                    "用户名固定 admin；默认密码 adminadmin。运行中修改立即生效，本机访问（localhost）始终免登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (hasCustomPassword) {
                    TextButton(
                        onClick = onResetDefault,
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("恢复默认密码 adminadmin")
                    }
                }
            }
        },
        actions = {
            SettingsDialogTextButton(text = "取消", onClick = onDismiss)
            SettingsDialogTextButton(
                text = "确定",
                onClick = {
                    val err = validate()
                    if (err.isEmpty()) onConfirm(password) else error = err
                }
            )
        }
    )
}

/** 密码输入栏（带明文/密文切换）。 */
@Composable
private fun QbtPasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = InputLabel) },
        singleLine = true,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏密码" else "显示密码"
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}
