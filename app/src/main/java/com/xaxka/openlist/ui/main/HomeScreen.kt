package com.xaxka.openlist.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.ShapeFABCircle
import com.xaxka.openlist.ui.theme.ShapeMenuR4
import kotlin.math.roundToInt

/** FAB 旋转动画：200ms 线性、每次半圈（Blue Light §2.6 AnimFabRotate） */
private const val FAB_ROTATE_ANIM_MS = 200
private const val HALF_TURN = 0.5f

/**
 * 标题光学补偿（em）：拉丁字形墨迹中心（cap 顶 ~ 下延底的中点）比字体度量框中心
 * 低约 0.108em（Roboto：度量框中心在基线上 0.3415em，墨迹中心在基线上 0.2335em）。
 * 负值 = 向上抬，使墨迹中心对齐栏中心（=右侧图标视觉中心）。
 * 前提：标题行盒必须已被几何居中（见 HomeTopBar 的 Box align），补偿只修字形墨迹。
 */
private const val OPTICAL_LIFT_EM = -0.108f

/**
 * 服务主页（照源 tmp/lib/pages/alist/alist.dart）：
 * AppBar「OpenList - v…」+ 纯日志列表 + 启停 FAB + 密码/关于对话框。
 */
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
 * 顶栏：primary 主题色底、56dp 固定高、标题靠左 + 右侧两个图标按钮。
 * 手写 Box 布局，不走 M3 TopAppBar：标题槽位的实际摆放受 M3 内部实现（内边距、
 * 高度约束、windowInsets 处理）影响，从外部只能靠字体度量间接修补——此前三次
 * 度量级修补后实机截图标题墨迹中心仍比右侧按钮高约 11dp。Box 的
 * align(CenterStart/CenterEnd) 让标题行盒与图标按钮严格共享同一条水平中线，
 * 对齐成为布局结构保证，再叠加 OPTICAL_LIFT_EM 修字形墨迹（约 2dp）。
 * 状态栏 inset 由外层 statusBarsPadding 承担，56dp 全部用于标题行。
 * actions 顺序：password（admin 密码）→ more_vert（更多菜单）。
 */
@Composable
private fun HomeTopBar(
    coreVersion: String,
    onSetPassword: () -> Unit,
    onAbout: () -> Unit,
) {
    Box(
        modifier = Modifier
            // 铺满横向：Box 默认包裹内容宽，Scaffold topBar 槽位传松约束时会缩到
            // 标题宽度，右侧露出白底（实测蓝底只到 866px / 全宽 1272px）。
            // 先 fillMaxWidth 再画背景，主题色覆盖整行含状态栏区域。
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .statusBarsPadding()
            .height(Dimens.AppBarHeight),
    ) {
        val style = MaterialTheme.typography.titleLarge
        Text(
            "OpenList - $coreVersion",
            style = style.copy(
                // 度量框确定化：行框=字号 + 关闭字体填充 + 墨迹度量居中，
                // 基线位置稳定，与 ROM 字体无关
                lineHeight = style.fontSize,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None,
                ),
            ),
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                // 右侧预留两个按钮宽 + 间隙，长标题省略号截断不压到按钮
                .padding(
                    start = Dimens.PageMargin,
                    end = Dimens.IconButtonSize * 2 + Dimens.PageMargin / 2,
                )
                .offset {
                    // 光学补偿：度量框上下不对称（上为重音大写预留 ascent、下为深下延
                    // 预留 descent），拉丁墨迹中心低 0.108em。按字号比例上抬该差值，
                    // 墨迹中心 == 图标字形中心，严格共线。offset 不参与测量，只影响摆放。
                    IntOffset(0, (style.fontSize.toPx() * OPTICAL_LIFT_EM).roundToInt())
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            IconButton(
                onClick = onSetPassword,
                modifier = Modifier.size(Dimens.IconButtonSize),
            ) {
                Icon(
                    Icons.Outlined.Password,
                    contentDescription = Strings.SET_ADMIN_PASSWORD,
                    tint = MaterialTheme.colorScheme.onPrimary,
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
                        tint = MaterialTheme.colorScheme.onPrimary,
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
        }
    }
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
