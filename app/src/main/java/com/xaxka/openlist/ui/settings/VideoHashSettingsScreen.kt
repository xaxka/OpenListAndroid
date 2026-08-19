package com.xaxka.openlist.ui.settings

import android.net.Uri
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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Restore
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.InputHint
import com.xaxka.openlist.ui.theme.InputLabel
import com.xaxka.openlist.ui.theme.ShapeInputOutlineR4
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 设置子页面：视频洗码（仅手动触发，无后台自动洗码）。
 * 内容自设置主页面拆分而来；返回键由顶栏按钮与系统回退共同支持（NavHost 栈）。
 */
@Composable
fun VideoHashSettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showSuffixDialog by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }

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
                title = "视频洗码",
                onBack = { navController.popBackStack() }
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
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
            }
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
