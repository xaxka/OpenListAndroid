package com.xaxka.openlist.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.xaxka.openlist.easytier.EasyTierManager
import com.xaxka.openlist.easytier.MyNodeInfo

/**
 * 设置子页面：内网映射「映射状态」详情页。
 * 由内网映射页点击「映射状态」条目进入（内容较多，单独成页）；返回由顶栏按钮与系统回退支持。
 *
 * 承载本节点信息：来自 collectNetworkInfos 的最近一次解析快照，随轮询自动刷新。
 * 事件日志不再在此面板展示，改由 EasyTierManager 增量导出到应用日志（见主页日志流）。
 */
@Composable
fun EasyTierStatusDetailScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val detail = state.easytierDetail
    val phase = detail.phase

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            SettingsSubPageTopBar(
                title = "映射状态",
                onBack = { navController.popBackStack() }
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                // ---- 本节点（与设置页状态区一致：未启动/不可用不展示） ----
                if (phase != EasyTierManager.Phase.STOPPED &&
                    phase != EasyTierManager.Phase.UNAVAILABLE
                ) {
                    detail.myNode?.let { node -> MyNodeBlock(node) }
                }
            }
        }
    }
}

/** 本节点信息：主机名 / 虚拟 IP / Peer ID / 版本 / 监听 / NAT 与公网 IP。 */
@Composable
private fun MyNodeBlock(node: MyNodeInfo) {
    StatusCard("本节点") {
        StatusKV("主机名", node.hostname)
        StatusKV("虚拟 IP", node.virtualIpv4.orEmpty())
        StatusKV("Peer ID", if (node.peerId != 0L) node.peerId.toString() else "")
        StatusKV("版本", node.version)
        if (node.listeners.isNotEmpty()) {
            StatusKV("监听", node.listeners.joinToString("\n"))
        }
        node.stun?.let { stun ->
            if (stun.udpNatType.isNotBlank()) StatusKV("UDP NAT", stun.udpNatType)
            if (stun.tcpNatType.isNotBlank()) StatusKV("TCP NAT", stun.tcpNatType)
            if (stun.publicIps.isNotEmpty()) StatusKV("公网 IP", stun.publicIps.joinToString(", "))
        }
    }
}

/** 只读键值行；value 为空则不渲染。 */
@Composable
private fun StatusKV(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
