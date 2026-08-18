package com.xaxka.openlist.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxka.openlist.data.log.LogBuffer
import com.xaxka.openlist.data.log.ServerLog
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import com.xaxka.openlist.service.ServerManager
import com.xaxka.openlist.service.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 主页 UI 状态：服务状态/内核版本/日志列表 */
data class HomeUiState(
    val serverState: ServerState = ServerState.STOPPED,
    val coreVersion: String = "",
    val logs: List<ServerLog> = emptyList(),
) {
    val isRunning: Boolean get() = serverState == ServerState.RUNNING
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val serverManager: ServerManager,
    private val logBuffer: LogBuffer,
    prefs: AppPrefsRepository,
) : ViewModel() {

    /**
     * 跨层契约：冷启动时服务已运行且开启了「将网页设为首页」（照源 main.dart 初始索引逻辑），
     * 发射一次 Unit，由主控 AppNavHost 收集后导航到 web 路由。
     */
    private val _openWebEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openWebEvent: SharedFlow<Unit> = _openWebEvent.asSharedFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        serverManager.state,
        serverManager.coreVersion,
        logBuffer.logs,
    ) { state, version, logs ->
        HomeUiState(
            serverState = state,
            coreVersion = version,
            logs = logs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            if (prefs.autoOpenWeb.first() && serverManager.state.value == ServerState.RUNNING) {
                _openWebEvent.emit(Unit)
            }
        }
    }

    /** FAB 启停：照源 alist.dart:85-89，先清空旧日志再切换服务 */
    fun toggleServer(context: Context) {
        logBuffer.clear()
        serverManager.toggle(context)
    }

    /**
     * 设置 admin 密码；照源行为（AList.setAdminPassword 内部 runCatching 吞错）静默容错，
     * 调用方在确认时先行 Snackbar 明文展示密码 1s。
     */
    fun setAdminPassword(password: String) {
        viewModelScope.launch {
            runCatching { serverManager.setAdminPassword(password) }
        }
    }
}
