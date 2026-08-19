package com.xaxka.openlist.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PanToolAlt
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.InputHint
import com.xaxka.openlist.ui.theme.InputLabel
import com.xaxka.openlist.ui.theme.ShapeInputOutlineR4
import com.xaxka.openlist.video.SafHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 文案照源 intl_zh.arb 迁移（OpenList 化）
private const val TEXT_IMPORTANT = "重要"
private const val TEXT_GENERAL = "通用"
private const val TEXT_VIDEO_HASH = "视频洗码"
private const val TEXT_UI = "界面"

private const val TEXT_GRANT_MANAGER_STORAGE = "申请【所有文件访问权限】"
private const val TEXT_GRANT_STORAGE = "申请【读写外置存储权限】"
private const val TEXT_STORAGE_DESC = "挂载本地存储时必须授予，否则无权限读写文件"
private const val TEXT_GRANT_NOTIFICATION = "申请【通知权限】"
private const val TEXT_NOTIFICATION_DESC = "用于前台服务保活"

private const val TEXT_CANCEL = "取消"
private const val TEXT_CONFIRM = "确认"

private const val TEXT_NO_DIRS = "未设置监听目录"
private const val TEXT_ENTER_SUFFIX = "请输入追加文字"
private const val TEXT_RUNNING = "运行中"
private const val TEXT_IDLE = "空闲"
private const val DEFAULT_SUFFIX = "HashMod"

private const val SNACK_DURATION_SHORT = 2000L
private const val SNACK_DURATION_LONG = 3000L

/**
 * 设置页（源 settings.dart）：权限组（动态显隐）→ 通用 → 视频洗码 → 界面。
 * 无 AppBar，Scaffold 背景与底部导航由主框架提供。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showSuffixDialog by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }
    var showEasytierNetworkDialog by remember { mutableStateOf(false) }
    var showEasytierSecretDialog by remember { mutableStateOf(false) }
    var showEasytierPeerDialog by remember { mutableStateOf(false) }

    // 从系统设置/权限弹窗返回后刷新权限条目（源 AppLifecycleListener.onResume → updateData）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 运行时权限申请（源 permission_handler）
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshPermissions() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }

    // SAF 数据目录选择（照源 pickDir 链路：persistable 权限 + treeUri → 路径）
    val pickDataDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) {
            // 取消选择：询问是否恢复初始目录（源 GetSnackBar 3s + 确认）
            viewModel.snack(
                message = "是否设为初始目录？",
                durationMillis = SNACK_DURATION_LONG,
                actionLabel = TEXT_CONFIRM
            ) { viewModel.setDataDir("") }
        } else {
            handlePickedDir(context, uri, onError = { viewModel.snack(it, SNACK_DURATION_LONG) }) { path ->
                viewModel.setDataDir(path)
            }
        }
    }

    // SAF 洗码监听目录选择（仅单目录，新选替换旧；取消不改原值）
    val pickVideoDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            handlePickedDir(context, uri, onError = { viewModel.snack(it, SNACK_DURATION_LONG) }) { path ->
                viewModel.setVideoHashDirs(listOf(path))
            }
        }
    }

    // Snackbar 事件：GetSnackBar 复刻（自动按时长关闭，点动作执行回调）
    LaunchedEffect(viewModel) {
        viewModel.snackEvents.collect { event ->
            val showJob = launch {
                val result = snackbarHostState.showSnackbar(
                    event.message,
                    event.actionLabel,
                    false,
                    SnackbarDuration.Indefinite
                )
                if (result == SnackbarResult.ActionPerformed) event.onAction?.invoke()
            }
            delay(event.durationMillis)
            snackbarHostState.currentSnackbarData?.dismiss()
            showJob.join()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // 无 AppBar，页面自管状态栏 inset（外层 Scaffold 已关闭 contentWindowInsets）
            .statusBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ---------- 权限组（动态可见） ----------
            val permissions = state.permissions
            if (permissions.anyPending) {
                SettingsDividerPreference(TEXT_IMPORTANT)
            }
            if (permissions.needManagerStorage) {
                SettingsBasicPreference(
                    title = TEXT_GRANT_MANAGER_STORAGE,
                    subtitle = TEXT_STORAGE_DESC,
                    onTap = {
                        // 「所有文件访问」无法运行时弹窗，跳系统设置页（源 permission_handler 同效）
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                )
            }
            if (permissions.needStorage) {
                SettingsBasicPreference(
                    title = TEXT_GRANT_STORAGE,
                    subtitle = TEXT_STORAGE_DESC,
                    onTap = {
                        @Suppress("DEPRECATION")
                        storagePermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    }
                )
            }
            if (permissions.needNotification) {
                SettingsBasicPreference(
                    title = TEXT_GRANT_NOTIFICATION,
                    subtitle = TEXT_NOTIFICATION_DESC,
                    onTap = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                )
            }

            // ---------- 通用 ----------
            SettingsDividerPreference(TEXT_GENERAL)
            SettingsSwitchPreference(
                title = "唤醒锁",
                subtitle = "开启防止锁屏后CPU休眠，保持进程在后台运行。（部分系统可能导致杀后台）",
                icon = Icons.Outlined.ScreenLockPortrait,
                value = state.keepWakeLock,
                onCheckedChange = viewModel::setKeepWakeLock
            )
            SettingsSwitchPreference(
                title = "开机自启动服务",
                subtitle = "在开机后自动启动OpenList服务。（请确保授予自启动权限）",
                icon = Icons.Outlined.PowerSettingsNew,
                value = state.autostartOnBoot,
                onCheckedChange = viewModel::setAutostartOnBoot
            )
            SettingsSwitchPreference(
                title = "将网页设置为打开首页",
                subtitle = "打开主界面时的首页",
                icon = Icons.Outlined.OpenInBrowser,
                value = state.autoOpenWeb,
                onCheckedChange = viewModel::setAutoOpenWeb
            )
            // Blue Light UI：固定浅色、禁动态取色（原则 5），无主题开关项
            SettingsBasicPreference(
                title = "data 文件夹路径",
                subtitle = state.dataDir,
                leading = { SettingsPreferenceIcon(Icons.Outlined.Folder) },
                onTap = { pickDataDirLauncher.launch(null) }
            )
            SettingsSwitchPreference(
                title = "不使用内存缓存",
                subtitle = "仅使用文件缓存，不占用内存。修改后需重启OpenList生效。",
                icon = Icons.Outlined.Memory,
                value = state.noMemoryCache,
                onCheckedChange = viewModel::setNoMemoryCache
            )

            // ---------- 内网映射（EasyTier，no-tun 不使用 VPN） ----------
            SettingsDividerPreference("内网映射（EasyTier）")
            SettingsSwitchPreference(
                title = "启用内网映射",
                subtitle = "随服务启停；no-tun 模式（不使用 VPN），把本机 5244 端口映射进 EasyTier 虚拟网络",
                icon = Icons.Outlined.Lan,
                value = state.easytierEnabled,
                onCheckedChange = viewModel::setEasytierEnabled
            )
            SettingsBasicPreference(
                title = "网络名称",
                subtitle = state.easytierNetwork.ifBlank { "（留空使用默认网络 default）" },
                leading = { SettingsPreferenceIcon(Icons.Outlined.Hub) },
                onTap = { showEasytierNetworkDialog = true }
            )
            SettingsBasicPreference(
                title = "网络密钥",
                subtitle = if (state.easytierNetworkSecret.isNotBlank()) "已设置" else "（留空）",
                leading = { SettingsPreferenceIcon(Icons.Outlined.Key) },
                onTap = { showEasytierSecretDialog = true }
            )
            SettingsBasicPreference(
                title = "对等节点",
                subtitle = state.easytierPeerUri.ifBlank { "（留空不配置 peer）" },
                leading = { SettingsPreferenceIcon(Icons.Outlined.Link) },
                onTap = { showEasytierPeerDialog = true }
            )
            SettingsBasicPreference(
                title = "映射状态",
                subtitle = state.easytierStatus,
                leading = { SettingsPreferenceIcon(Icons.Outlined.Info) }
            )

            // ---------- 视频洗码（仅手动触发，无后台自动洗码） ----------
            SettingsDividerPreference(TEXT_VIDEO_HASH)
            SettingsBasicPreference(
                title = "立即处理",
                subtitle = "立即洗码监听目录中的视频文件",
                leading = { SettingsPreferenceIcon(Icons.Outlined.PlayCircleOutline) },
                onTap = viewModel::startScan
            )
            SettingsBasicPreference(
                title = "立即还原",
                subtitle = "检测已洗码的视频文件并还原",
                leading = { SettingsPreferenceIcon(Icons.Outlined.Restore) },
                onTap = viewModel::startRestore
            )
            SettingsBasicPreference(
                title = "监听目录",
                subtitle = state.videoHashDirs.firstOrNull() ?: TEXT_NO_DIRS,
                leading = { SettingsPreferenceIcon(Icons.Outlined.FolderOpen) },
                onTap = { pickVideoDirLauncher.launch(null) }
            )
            SettingsBasicPreference(
                title = "追加文字",
                subtitle = state.videoHashSuffix.ifEmpty { DEFAULT_SUFFIX },
                leading = { SettingsPreferenceIcon(Icons.Outlined.TextFields) },
                onTap = { showSuffixDialog = true }
            )
            SettingsBasicPreference(
                title = "处理状态",
                subtitle = when {
                    state.videoHashRunning -> TEXT_RUNNING
                    state.videoHashDirs.isEmpty() -> TEXT_NO_DIRS
                    else -> TEXT_IDLE
                },
                leading = {
                    if (state.videoHashRunning) SettingsProgressSpinner()
                    else SettingsPreferenceIcon(Icons.Outlined.Info)
                },
                trailing = {
                    if (state.videoHashStatus.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .width(Dimens.ChevronIconSize)
                                .height(Dimens.ChevronIconSize)
                        )
                    }
                },
                onTap = { showStatusDialog = true }
            )

            // ---------- 界面 ----------
            SettingsDividerPreference(TEXT_UI)
            SettingsSwitchPreference(
                title = "深色模式",
                subtitle = "开启后应用使用深色主题，网页页同步深色渲染",
                icon = Icons.Outlined.DarkMode,
                value = state.darkMode,
                onCheckedChange = viewModel::setDarkMode
            )
            SettingsSwitchPreference(
                title = "动态取色",
                subtitle = "跟随系统壁纸取色（Android 12+；深色模式下取深色动态色）",
                icon = Icons.Outlined.Palette,
                value = state.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor
            )
            SettingsSwitchPreference(
                title = "静默跳转APP",
                subtitle = "跳转APP时，不弹出提示框",
                icon = Icons.Outlined.PanToolAlt,
                value = state.silentJumpApp,
                onCheckedChange = viewModel::setSilentJumpApp
            )
        }

        SettingsSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showSuffixDialog) {
        SuffixEditDialog(
            initial = state.videoHashSuffix,
            onDismiss = { showSuffixDialog = false },
            onConfirm = { suffix ->
                viewModel.setVideoHashSuffix(suffix)
                showSuffixDialog = false
            }
        )
    }
    if (showStatusDialog) {
        StatusDialog(
            status = state.videoHashStatus.ifEmpty { TEXT_IDLE },
            onDismiss = { showStatusDialog = false }
        )
    }
    if (showEasytierNetworkDialog) {
        EasyTierTextDialog(
            title = "网络名称",
            initial = state.easytierNetwork,
            placeholder = "留空使用默认网络 default",
            onDismiss = { showEasytierNetworkDialog = false },
            onConfirm = {
                viewModel.setEasytierNetwork(it.trim())
                showEasytierNetworkDialog = false
            }
        )
    }
    if (showEasytierSecretDialog) {
        EasyTierTextDialog(
            title = "网络密钥",
            initial = state.easytierNetworkSecret,
            placeholder = "与对端一致的网络密钥",
            password = true,
            onDismiss = { showEasytierSecretDialog = false },
            onConfirm = {
                viewModel.setEasytierNetworkSecret(it)
                showEasytierSecretDialog = false
            }
        )
    }
    if (showEasytierPeerDialog) {
        EasyTierTextDialog(
            title = "对等节点 URI",
            initial = state.easytierPeerUri,
            placeholder = "如 tcp://host:11010 或公共服务器 URI",
            onDismiss = { showEasytierPeerDialog = false },
            onConfirm = {
                viewModel.setEasytierPeerUri(it.trim())
                showEasytierPeerDialog = false
            }
        )
    }
}

/** SAF 选目录结果统一处理：持久化授权 + treeUri→路径；失败文案照源 MethodChannel error(code: message) */
private fun handlePickedDir(
    context: android.content.Context,
    uri: Uri,
    onError: (String) -> Unit,
    onSuccess: (String) -> Unit
) {
    try {
        SafHelper.takePersistableUriPermission(context, uri)
        val path = SafHelper.treeUriToPath(uri)
        if (path != null) {
            onSuccess(path)
        } else {
            onError("convert_failed: 无法获取目录路径")
        }
    } catch (e: Exception) {
        onError("error: ${e.message}")
    }
}

/** 追加文字对话框（源 settings.dart L329-359） */
@Composable
private fun SuffixEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = "追加文字",
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("请输入追加文字", style = InputLabel) },
                placeholder = { Text(DEFAULT_SUFFIX, style = InputHint) },
                singleLine = true,
                shape = ShapeInputOutlineR4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        actions = {
            SettingsDialogTextButton(text = TEXT_CANCEL, onClick = onDismiss)
            SettingsDialogTextButton(text = TEXT_CONFIRM, onClick = { onConfirm(text) })
        }
    )
}

/** 处理状态对话框（源 settings.dart L430-446） */
@Composable
private fun StatusDialog(
    status: String,
    onDismiss: () -> Unit
) {
    SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = "处理状态",
        content = {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        actions = {
            SettingsDialogTextButton(text = TEXT_CONFIRM, onClick = onDismiss)
        }
    )
}

/** EasyTier 配置文本输入对话框（网络名称/密钥/对端 URI 复用；密钥走密码掩码）。 */
@Composable
private fun EasyTierTextDialog(
    title: String,
    initial: String,
    placeholder: String,
    password: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(title, style = InputLabel) },
                placeholder = { Text(placeholder, style = InputHint) },
                singleLine = true,
                shape = ShapeInputOutlineR4,
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        actions = {
            SettingsDialogTextButton(text = TEXT_CANCEL, onClick = onDismiss)
            SettingsDialogTextButton(text = TEXT_CONFIRM, onClick = { onConfirm(text) })
        }
    )
}
