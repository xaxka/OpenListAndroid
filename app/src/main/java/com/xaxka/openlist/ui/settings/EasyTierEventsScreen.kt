package com.xaxka.openlist.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.xaxka.openlist.ui.theme.Dimens

/**
 * 设置子页面：内网映射「事件日志」。
 * 由内网映射页点击「映射状态」进入（内容较多，单独成页）；返回键由顶栏按钮与系统回退支持。
 *
 * 全量展示 EasyTierManager 最近一次快照中的事件（核心侧保留上限 200 条），
 * 倒序呈现（最新在最上），随 4s 轮询自动刷新。
 */
@Composable
fun EasyTierEventsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val events = state.easytierDetail.events

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            SettingsSubPageTopBar(
                title = "事件日志",
                onBack = { navController.popBackStack() }
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                // 顶部保留当前映射状态摘要，便于对照事件时间线
                SettingsBasicPreference(
                    title = "映射状态",
                    subtitle = state.easytierStatus,
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Info) }
                )

                if (events.isEmpty()) {
                    Text(
                        "暂无事件（实例未启动，或尚未上报事件）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = Dimens.PageMargin,
                            vertical = 12.dp
                        )
                    )
                } else {
                    StatusCard("事件日志（${events.size}）") {
                        SelectionContainer {
                            Column {
                                // 最新事件置顶，便于直接看到最近动态
                                events.asReversed().forEach { event ->
                                    Text(
                                        event,
                                        style = MaterialTheme.typography.bodySmall
                                            .copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
