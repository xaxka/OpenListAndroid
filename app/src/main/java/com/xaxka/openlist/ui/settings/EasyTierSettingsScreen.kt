package com.xaxka.openlist.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Numbers
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.xaxka.openlist.ui.theme.InputHint
import com.xaxka.openlist.ui.theme.InputLabel
import com.xaxka.openlist.ui.theme.ShapeInputOutlineR4
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 设置子页面：内网映射（EasyTier，no-tun 不使用 VPN）。
 * 内容自设置主页面拆分而来；返回键由顶栏按钮与系统回退共同支持（NavHost 栈）。
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
    var showPortsDialog by remember { mutableStateOf(false) }

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
            ) {
                SettingsSwitchPreference(
                    title = "启用内网映射",
                    subtitle = "随服务启停；no-tun 模式（不使用 VPN），把本机配置的端口映射进 EasyTier 虚拟网络",
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
                SettingsBasicPreference(
                    title = "映射端口",
                    subtitle = state.easytierPorts.ifBlank { "5244" } + "（逗号分隔可填多个，均映射到本机同端口）",
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Numbers) },
                    onTap = { showPortsDialog = true }
                )
                SettingsBasicPreference(
                    title = "映射状态",
                    subtitle = state.easytierStatus,
                    leading = { SettingsPreferenceIcon(Icons.Outlined.Info) }
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
    if (showPortsDialog) {
        EasyTierTextDialog(
            title = "映射端口",
            initial = state.easytierPorts,
            placeholder = "如 5244 或 5244, 8080（逗号分隔，1-65535）",
            onDismiss = { showPortsDialog = false },
            onConfirm = {
                viewModel.setEasytierPorts(it.trim())
                showPortsDialog = false
            }
        )
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
