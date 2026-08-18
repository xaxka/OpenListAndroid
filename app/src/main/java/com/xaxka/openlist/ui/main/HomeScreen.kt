package com.xaxka.openlist.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.ShapeFABCircle
import com.xaxka.openlist.ui.theme.ShapeMenuR4

/** FAB 旋转动画：200ms 线性、每次半圈（Blue Light §2.6 AnimFabRotate） */
private const val FAB_ROTATE_ANIM_MS = 200
private const val HALF_TURN = 0.5f

/**
 * 服务主页（照源 tmp/lib/pages/alist/alist.dart）：
 * AppBar「OpenList - v…」+ 纯日志列表 + 启停 FAB + 密码/关于对话框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { GetXSnackbarState() }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // 首次启动申请通知权限（Android 13+，前台服务通知必需）
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 密码设置失败提示（内核未初始化/底层异常），替代原先的静默吞错
    LaunchedEffect(viewModel) {
        viewModel.passwordSetEvents.collect {
            snackbar.show(
                message = "管理员密码设置失败，请确认服务可用后重试",
                durationMs = 2_000,
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            HomeTopBar(
                coreVersion = uiState.coreVersion,
                onSetPassword = { showPasswordDialog = true },
                onAbout = { showAboutDialog = true },
            )
        },
        floatingActionButton = {
            SwitchServerFab(
                isRunning = uiState.isRunning,
                onToggle = { viewModel.toggleServer(context) },
            )
        },
        snackbarHost = { GetXSnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LogSection(
                logs = uiState.logs,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showPasswordDialog) {
        PasswordDialog(
            onDismissRequest = { showPasswordDialog = false },
            onConfirm = { password ->
                showPasswordDialog = false
                // 照源 alist.dart:41-47：确认后 Snackbar 明文展示密码 1s，再下发设置
                snackbar.show(
                    title = Strings.SET_ADMIN_PASSWORD,
                    message = password,
                    durationMs = 1_000,
                )
                viewModel.setAdminPassword(password)
            },
        )
    }
    if (showAboutDialog) {
        AboutDialog(
            coreVersion = uiState.coreVersion,
            onDismissRequest = { showAboutDialog = false },
            onCopiedToClipboard = {
                // 照源 about_dialog.dart:61-64：长按复制链接 → Snackbar 1s
                snackbar.show(message = Strings.COPIED_TO_CLIPBOARD, durationMs = 1_000)
            },
        )
    }
}

/**
 * 顶栏：Blue Light 列表页模式——surface 冷白底与页面融为一体、TitleLarge 标题。
 * 状态栏 inset 由外层 statusBarsPadding 承担（TopAppBar 自身 windowInsets 置零），
 * 保证 56dp 全部用于标题行，不被状态栏挤压。
 * actions 顺序：password（admin 密码）→ more_vert（更多菜单）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    coreVersion: String,
    onSetPassword: () -> Unit,
    onAbout: () -> Unit,
) {
    TopAppBar(
        modifier = Modifier
            .statusBarsPadding()
            .height(Dimens.AppBarHeight),
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
            Text(
                "OpenList - $coreVersion",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            scrolledContainerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        actions = {
            IconButton(
                onClick = onSetPassword,
                modifier = Modifier.size(Dimens.IconButtonSize),
            ) {
                Icon(
                    Icons.Outlined.Password,
                    contentDescription = Strings.SET_ADMIN_PASSWORD,
                )
            }
            Box {
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(Dimens.IconButtonSize),
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = Strings.MORE_OPTIONS,
                    )
                }
                // PopupMenu：surface / R4 / elevation 3 / 项高 48 / 水平内边距 12（R13）
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = ShapeMenuR4,
                    tonalElevation = Dimens.MenuElevation,
                    shadowElevation = Dimens.MenuElevation,
                ) {
                    DropdownMenuItem(
                        text = { Text(Strings.ABOUT) },
                        onClick = {
                            menuExpanded = false
                            onAbout()
                        },
                        modifier = Modifier.height(Dimens.PopupMenuItemHeight),
                        contentPadding = PaddingValues(
                            horizontal = Dimens.PopupMenuItemPaddingH,
                        ),
                    )
                }
            }
        },
    )
}

/**
 * 启停 FAB（照源 widgets/switch_floating_action_button.dart，全量覆写）：
 * 56dp 正圆、elevation 8、运行=inversePrimary+stop(48)、停止=primaryContainer+send(32)、
 * 前景恒为 onPrimaryContainer（源未单独覆写）、每次点击 200ms 线性半圈旋转。
 */
@Composable
private fun SwitchServerFab(
    isRunning: Boolean,
    onToggle: () -> Unit,
) {
    // 照源 switch_floating_action_button.dart:37-43 —— 启动 forward(from:0.5)、停止 reverse(from:0.5)，等价于目标转数 ±0.5
    var turns by remember { mutableFloatStateOf(0f) }
    val animatedTurns by animateFloatAsState(
        targetValue = turns,
        animationSpec = tween(FAB_ROTATE_ANIM_MS, easing = LinearEasing),
        label = "fabRotation",
    )

    FloatingActionButton(
        onClick = {
            turns = if (isRunning) turns - HALF_TURN else turns + HALF_TURN
            onToggle()
        },
        modifier = Modifier.size(Dimens.FABSize),
        shape = ShapeFABCircle,
        containerColor = if (isRunning) {
            MaterialTheme.colorScheme.inversePrimary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = Dimens.FabElevation,
            pressedElevation = Dimens.FabElevation,
            focusedElevation = Dimens.FabElevation,
            hoveredElevation = Dimens.FabElevation,
        ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.rotate(animatedTurns * 360f),
        ) {
            if (isRunning) {
                // 运行态：白点指示器（照源 switch_floating_action_button 运行态）
                Box(
                    modifier = Modifier
                        .size(Dimens.FabIconDot)
                        .background(Color.White, ShapeFABCircle)
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = Strings.TOGGLE_SERVER,
                    modifier = Modifier.size(Dimens.FabIconSend),
                )
            }
        }
    }
}
