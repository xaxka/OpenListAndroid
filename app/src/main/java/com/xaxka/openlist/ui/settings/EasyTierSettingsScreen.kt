package com.xaxka.openlist.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.xaxka.openlist.easytier.EasyTierManager
import com.xaxka.openlist.easytier.EasyTierSpec
import com.xaxka.openlist.easytier.PeerConn
import com.xaxka.openlist.easytier.PeerDetail
import com.xaxka.openlist.easytier.RouteDetail
import com.xaxka.openlist.ui.nav.Routes
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.InputHint
import com.xaxka.openlist.ui.theme.InputLabel
import com.xaxka.openlist.ui.theme.ShapeInputOutlineR4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 网络节点在设置页的最大展示条数。 */
private const val NODES_DISPLAY_LIMIT = 20

/**
 * 设置子页面：内网映射（EasyTier，no-tun 不使用 VPN）。
 * 内容自设置主页面拆分而来；返回键由顶栏按钮与系统回退共同支持（NavHost 栈）。
 *
 * 下半部分为只读「运行状态」区：映射状态（点击进入详情页：本节点）、网络节点、启动配置。
 * 事件日志不再展示，改由 EasyTierManager 增量导出到应用日志（汇入主页日志流）。
 */
@Composable
fun EasyTierSettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showNetworkDialog by remember { mutableStateOf(false) }
    var showSecretDialog by remember { mutableStateOf(false) }
    var showPeerDialog by remember { mutableStateOf(false) }
    var showTomlDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 导出事件日记：系统「另存为」选择目标，把最近 24h 事件写到用户指定文件
    val exportDiaryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val text = viewModel.eventDiaryText()
                if (text.isEmpty()) return@withContext false
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(text.toByteArray())
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
            }
            viewModel.snack(if (ok) "事件日记已导出" else "暂无事件日记可导出")
        }
    }

    // Snackbar 事件：与设置主页面相同的 GetSnackBar 复刻
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
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            SettingsSubPageTopBar(
                title = "内网映射（EasyTier）",
                onBack = { navController.popBackStack() }
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                SettingsSwitchPreference(
                    title = "启用内网映射",
                    subtitle = "随服务启停；no-tun 模式（不使用 VPN）",
                    icon = Icons.Outlined.Lan,
                    value = state.easytierEnabled,
                    onCheckedChange = viewModel::setEasytierEnabled
                )
                SettingsBasicPreference(
                    title = "网络名称",
                    subtitle = state.easytierNetwork.ifBlank { "（留空使用默认网络 default）" },
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Hub) },
                    onTap = { showNetworkDialog = true }
                )
                SettingsBasicPreference(
                    title = "网络密钥",
                    subtitle = if (state.easytierNetworkSecret.isNotBlank()) "已设置" else "（留空）",
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Key) },
                    onTap = { showSecretDialog = true }
                )
                SettingsBasicPreference(
                    title = "对等节点",
                    subtitle = state.easytierPeerUri.ifBlank { "（留空不配置 peer）" },
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Link) },
                    onTap = { showPeerDialog = true }
                )
                SettingsSwitchPreference(
                    title = "启用 QUIC 代理",
                    subtitle = "TCP 流转为 QUIC 传输，弱网更稳；变更需重启",
                    icon = Icons.Outlined.Speed,
                    value = state.easytierQuicProxy,
                    onCheckedChange = viewModel::setEasytierQuicProxy
                )
                SettingsSwitchPreference(
                    title = "启用安全模式",
                    subtitle = "节点间端到端加密；对端需同开启并升级版本",
                    icon = Icons.Outlined.Security,
                    value = state.easytierSecureMode,
                    onCheckedChange = viewModel::setEasytierSecureMode
                )

                SettingsDividerPreference("运行状态")

                SettingsBasicPreference(
                    title = "重启内网映射",
                    subtitle = "掉线或连接异常时手动重启实例恢复",
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Refresh) },
                    onTap = viewModel::restartEasyTier
                )

                SettingsBasicPreference(
                    title = "映射状态",
                    subtitle = state.easytierStatus,
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Info) },
                    trailing = { SettingsChevron() },
                    onTap = { navController.navigate(Routes.SETTINGS_EASYTIER_STATUS) }
                )

                EasyTierStatusSection(detail = state.easytierDetail)

                SettingsBasicPreference(
                    title = "导出事件日记",
                    subtitle = "保存最近 24 小时事件到文件（跨重启不丢）",
                    leading = { SettingsPreferenceIcon(Icons.Outlined.SaveAlt) },
                    onTap = { exportDiaryLauncher.launch("easytier-events.txt") }
                )

                SettingsBasicPreference(
                    title = "启动配置",
                    subtitle = if (state.easytierDetail.startupToml.isBlank()) {
                        "（未启动，暂无配置）"
                    } else {
                        "查看本实例的启动 TOML（密钥已脱敏）"
                    },
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Description) },
                    onTap = if (state.easytierDetail.startupToml.isNotBlank()) {
                        { showTomlDialog = true }
                    } else null
                )
            }
        }

        SettingsSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showNetworkDialog) {
        EasyTierTextDialog(
            title = "网络名称",
            initial = state.easytierNetwork,
            placeholder = "留空使用默认网络 default",
            onDismiss = { showNetworkDialog = false },
            onConfirm = {
                viewModel.setEasytierNetwork(it.trim())
                showNetworkDialog = false
            }
        )
    }
    if (showSecretDialog) {
        EasyTierTextDialog(
            title = "网络密钥",
            initial = state.easytierNetworkSecret,
            placeholder = "与对端一致的网络密钥",
            password = true,
            onDismiss = { showSecretDialog = false },
            onConfirm = {
                viewModel.setEasytierNetworkSecret(it)
                showSecretDialog = false
            }
        )
    }
    if (showPeerDialog) {
        EasyTierTextDialog(
            title = "对等节点 URI",
            initial = state.easytierPeerUri,
            placeholder = "如 tcp://host:11010 或公共服务器 URI",
            onDismiss = { showPeerDialog = false },
            onConfirm = {
                viewModel.setEasytierPeerUri(it.trim())
                showPeerDialog = false
            }
        )
    }
    if (showTomlDialog) {
        SettingsAlertDialog(
            onDismissRequest = { showTomlDialog = false },
            title = "启动配置",
            content = {
                SelectionContainer {
                    Text(
                        state.easytierDetail.startupToml,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            actions = {
                SettingsDialogTextButton(text = TEXT_CLOSE, onClick = { showTomlDialog = false })
            }
        )
    }
}

/**
 * 只读运行状态区：网络节点（路由/对等合并）。
 * 本节点内容较多，拆到「映射状态」详情页（见 EasyTierStatusDetailScreen）；
 * 事件日志已改为增量导出到应用日志，不再在界面展示。
 * 数据均来自 collectNetworkInfos 的最近一次解析快照，随轮询自动刷新。
 */
@Composable
private fun EasyTierStatusSection(detail: EasyTierManager.Status) {
    val phase = detail.phase
    if (phase == EasyTierManager.Phase.STOPPED || phase == EasyTierManager.Phase.UNAVAILABLE) {
        return
    }

    // ---- 网络节点（路由表为主，对等连接明细并入） ----
    val peersByPeerId = detail.peers.associateBy(PeerDetail::peerId)
    // peer_id → 主机名（同快照路由表），下一跳展示用主机名替代裸 ID
    val hostnameByPeerId = detail.routes.associate { it.peerId to it.hostname }
    if (detail.routes.isNotEmpty()) {
        StatusCard("网络节点（${detail.routes.size}）") {
            detail.routes.take(NODES_DISPLAY_LIMIT).forEach { route ->
                RouteNodeBlock(route, peersByPeerId[route.peerId], hostnameByPeerId)
            }
            if (detail.routes.size > NODES_DISPLAY_LIMIT) {
                Text(
                    "…仅显示前 $NODES_DISPLAY_LIMIT 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    } else if (detail.peers.isNotEmpty()) {
        // 尚未形成路由表（如刚连接）：仅按对等连接展示
        StatusCard("网络节点（${detail.peers.size}）") {
            detail.peers.take(NODES_DISPLAY_LIMIT).forEach { peer ->
                PeerOnlyBlock(peer)
            }
        }
    }
}

/**
 * 路由表中的一个节点 + 对应的连接明细（若有）。
 * 标题行：主机名 · 虚拟 IP。
 * 摘要行：下一跳（主机名，路由表中查不到时兜底 Peer ID）· cost · 路径延迟 ·
 * 累计收发流量（流量紧随第一个延迟之后展示，多连接按节点汇总）。
 */
@Composable
private fun RouteNodeBlock(
    route: RouteDetail,
    peer: PeerDetail?,
    hostnameByPeerId: Map<Long, String>
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        val title = buildString {
            append(if (route.hostname.isNotBlank()) route.hostname else "Peer ${route.peerId}")
            route.ipv4?.let { append(" · ").append(it) }
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        val parts = buildList {
            if (route.nextHopPeerId != 0L && route.nextHopPeerId != route.peerId) {
                val hop = hostnameByPeerId[route.nextHopPeerId].orEmpty()
                add("下一跳 ${if (hop.isNotBlank()) hop else "Peer ${route.nextHopPeerId}"}")
            }
            add("cost ${route.cost}")
            if (route.pathLatencyMs != 0) add("延迟 ${route.pathLatencyMs}ms")
            // 流量统计紧随第一个延迟之后（自连接建立起累计，多连接按节点汇总）
            peer?.conns?.takeIf { it.isNotEmpty() }?.let { conns ->
                add("↓${EasyTierSpec.formatBytes(conns.sumOf { it.rxBytes })} ↑${EasyTierSpec.formatBytes(conns.sumOf { it.txBytes })}")
            }
        }
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        peer?.conns?.forEach { conn -> ConnDetailLine(conn) }
    }
}

/** 无路由条目时的纯对等连接展示。 */
@Composable
private fun PeerOnlyBlock(peer: PeerDetail) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("Peer ${peer.peerId}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        peer.conns.forEach { conn -> ConnDetailLine(conn) }
    }
}

/**
 * 单条连接明细行：对端地址 · 延迟 · 丢包（行首缩进 2 空格）。
 * 字体与标题/摘要行保持一致（默认 sans）——等宽字体的空格与中文均为全宽，
 * 视觉间隙偏大，与上两行不同步；地址已含协议前缀，无需等宽对齐。
 * 累计收发流量位于摘要行第一个延迟之后（按节点汇总）；直连/中继由 cost 体现。
 * 地址为空白（双栈下个别连接可能无 remote_addr，pbjson 省略空串）时以隧道类型兜底，绝不渲染无地址的空行样式。
 */
@Composable
private fun ConnDetailLine(conn: PeerConn) {
    val text = buildString {
        append("  ")
        append(conn.remoteAddr?.takeIf { it.isNotBlank() } ?: conn.tunnelType.ifBlank { "tunnel" })
        if (conn.latencyMs > 0) append(" · ${conn.latencyMs}ms")
        append(" · 丢包 ${"%.1f".format(conn.lossRate * 100)}%")
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 状态卡片：浅色底 + R8 圆角 + 标题（primary labelLarge）。 */
@Composable
internal fun StatusCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.PageMargin, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(Dimens.CardPadding)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** EasyTier 配置文本输入对话框（网络名称/密钥/对端 URI 复用；密钥默认掩码 + 眼睛切换可见）。 */
@Composable
private fun EasyTierTextDialog(
    title: String,
    initial: String,
    placeholder: String,
    password: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var visible by rememberSaveable { mutableStateOf(false) }
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
                visualTransformation = if (password && !visible) PasswordVisualTransformation()
                else VisualTransformation.None,
                trailingIcon = if (password) {
                    {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (visible) "隐藏输入内容" else "显示输入内容",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else null,
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

private const val TEXT_CLOSE = "关闭"
