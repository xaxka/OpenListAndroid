package com.xaxka.openlist.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PanToolAlt
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.xaxka.openlist.ui.nav.Routes
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.system.SafHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 文案照源 intl_zh.arb 迁移（OpenList 化）
internal const val TEXT_IMPORTANT = "重要"
internal const val TEXT_GENERAL = "通用"
internal const val TEXT_FEATURES = "扩展功能"
internal const val TEXT_UI = "界面"

internal const val TEXT_GRANT_MANAGER_STORAGE = "申请【所有文件访问权限】"
internal const val TEXT_GRANT_STORAGE = "申请【读写外置存储权限】"
internal const val TEXT_STORAGE_DESC = "挂载本地存储时必须授予，否则无权限读写文件"
internal const val TEXT_GRANT_NOTIFICATION = "申请【通知权限】"
internal const val TEXT_NOTIFICATION_DESC = "用于前台服务保活"

internal const val TEXT_CANCEL = "取消"
internal const val TEXT_CONFIRM = "确认"

internal const val SNACK_DURATION_LONG = 3000L

/**
 * 设置页（源 settings.dart）：权限组（动态显隐）→ 通用 → 扩展功能入口 → 界面。
 * 内网映射（EasyTier）拆为子页面（见 EasyTierSettingsScreen）。
 * 无 AppBar，Scaffold 背景与底部导航由主框架提供。
 */
@Composable
fun SettingsScreen(
    navController: NavHostController? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

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
            SettingsSwitchPreference(
                title = "禁用网页面板",
                subtitle = if (state.webPanelDisabled) {
                    "已禁用：「网页」标签页已隐藏，WebView 已释放，可省出可观内存；OpenList 网页可用系统浏览器访问"
                } else {
                    "隐藏「网页」标签页并释放 WebView 占用的内存；如需访问 OpenList 网页改用系统浏览器"
                },
                icon = Icons.Outlined.VisibilityOff,
                value = state.webPanelDisabled,
                onCheckedChange = viewModel::setWebPanelDisabled
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

            // ---------- 扩展功能（子页面入口） ----------
            SettingsDividerPreference(TEXT_FEATURES)
            SettingsBasicPreference(
                title = "内网映射（EasyTier）",
                subtitle = state.easytierStatus,
                leading = { SettingsPreferenceIcon(Icons.Outlined.Lan) },
                trailing = { SettingsChevron() },
                onTap = { navController?.navigate(Routes.SETTINGS_EASYTIER) }
            )
            SettingsBasicPreference(
                title = "qbittorrent",
                subtitle = state.qbtStatus,
                leading = { SettingsPreferenceIcon(Icons.Outlined.CloudDownload) },
                trailing = { SettingsChevron() },
                onTap = { navController?.navigate(Routes.SETTINGS_QBT) }
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
}

/** SAF 选目录结果统一处理：持久化授权 + treeUri→路径；失败文案照源 MethodChannel error(code: message) */
internal fun handlePickedDir(
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

/** 子页面统一顶栏：返回按钮 + 标题（无 AppBar 风格延续，自管状态栏 inset 由页面提供）。 */
@Composable
internal fun SettingsSubPageTopBar(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.DividerTitlePaddingH - Dimens.RowPaddingH, end = Dimens.RowPaddingH)
            .padding(vertical = Dimens.RowPaddingV / 2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(Dimens.IconButtonSize)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.width(Dimens.CardSpacing))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 子页面入口右侧箭头。 */
@Composable
internal fun SettingsChevron() {
    Icon(
        imageVector = Icons.Outlined.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .width(Dimens.ChevronIconSize)
            .height(Dimens.ChevronIconSize)
    )
}
