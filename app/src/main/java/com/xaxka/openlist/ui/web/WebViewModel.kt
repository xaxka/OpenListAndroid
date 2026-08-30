package com.xaxka.openlist.ui.web

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import com.xaxka.openlist.service.ServerManager
import com.xaxka.openlist.service.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Web 页 ViewModel：
 * - 端口发现照源 web.dart:44-54（初始 http://localhost:5244，serverUrl 就绪后替换为实际地址并触发重载）；
 * - 加载失败自愈照源 web.dart:128-140（服务未运行则启动，500ms×3 轮询，就绪即 reload）。
 */
@HiltViewModel
class WebViewModel @Inject constructor(
    private val serverManager: ServerManager,
    prefsRepository: AppPrefsRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** 静默跳转 APP 开关缓存（供 WebViewClient 回调内同步读取） */
    val silentJumpApp: StateFlow<Boolean> = prefsRepository.silentJumpApp
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 界面「深色模式」偏好缓存（网页页深色渲染 = 系统深色 或 应用深色模式） */
    val darkMode: StateFlow<Boolean> = prefsRepository.darkMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 禁用网页面板（隐藏「网页」标签页并释放 WebView；导航层响应，见 AppNavHost） */
    val webPanelDisabled: StateFlow<Boolean> = prefsRepository.webPanelDisabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 当前应加载地址：跟随 ServerManager.serverUrl，空回退默认 */
    val urlToLoad: StateFlow<String> = serverManager.serverUrl
        .map { it ?: DEFAULT_URL }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = serverManager.serverUrl.value ?: DEFAULT_URL,
        )

    private val _reloadEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 通知 WebView 加载指定地址（端口发现 / 自愈成功 / 重进网页页） */
    val reloadEvents: SharedFlow<String> = _reloadEvents

    init {
        // 端口发现：首次发射与 factory 初始加载同值，跳过；地址变化时重载（源 initState 替换端口语义）
        viewModelScope.launch {
            urlToLoad.drop(1).collect { url ->
                _reloadEvents.tryEmit(url)
            }
        }
    }

    /** 重进网页页（再点当前 tab）触发刷新，照源 web.dart:39-42 onClickNavigationBar */
    fun requestReload() {
        _reloadEvents.tryEmit(urlToLoad.value)
    }

    /** 主文档加载失败自愈：照源 web.dart:128-140 */
    fun recoverFromLoadError() {
        viewModelScope.launch {
            if (serverManager.state.value == ServerState.RUNNING) return@launch
            serverManager.start(appContext)
            repeat(RETRY_TIMES) {
                delay(RETRY_INTERVAL_MS)
                if (serverManager.state.value == ServerState.RUNNING) {
                    _reloadEvents.tryEmit(urlToLoad.value)
                    return@launch
                }
            }
        }
    }

    companion object {
        const val DEFAULT_URL = "http://localhost:5244"
        private const val RETRY_TIMES = 3
        private const val RETRY_INTERVAL_MS = 500L
    }
}
