package com.xaxka.openlist.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import com.xaxka.openlist.video.VideoHashStore
import com.xaxka.openlist.video.VideoHashWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页 ViewModel：逐组复刻源 _SettingsController。
 *
 * 偏好经 [AppPrefsRepository] Flow 直读（源为 onInit/onResume 拉取）；
 * 洗码运行/状态由 Worker 写入偏好后经 Flow 自动回流（等价源 2s×150 次轮询，时延更优）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPrefsRepository,
    private val videoHashStore: VideoHashStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /** 动态权限条目显隐（照源按 SDK 判定，未授权才显示） */
    data class PermissionState(
        val needManagerStorage: Boolean = false,
        val needStorage: Boolean = false,
        val needNotification: Boolean = false
    ) {
        val anyPending: Boolean get() = needManagerStorage || needStorage || needNotification
    }

    data class UiState(
        val permissions: PermissionState = PermissionState(),
        // 通用
        val keepWakeLock: Boolean = false,
        val autostartOnBoot: Boolean = false,
        val autoOpenWeb: Boolean = false,
        val dynamicColor: Boolean = false,
        val dataDir: String = "",
        val noMemoryCache: Boolean = false,
        // 视频洗码
        val videoHashDirs: List<String> = emptyList(),
        val videoHashSuffix: String = "",
        val videoHashRunning: Boolean = false,
        val videoHashStatus: String = "",
        // 界面
        val silentJumpApp: Boolean = false
    )

    /** 一次性 Snackbar 事件（文案/时长/动作照源 GetSnackBar 调用点） */
    data class SnackEvent(
        val message: String,
        val durationMillis: Long = 2000,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null
    )

    private val _snackEvents = MutableSharedFlow<SnackEvent>(extraBufferCapacity = 8)
    val snackEvents = _snackEvents.asSharedFlow()

    private val permissions = MutableStateFlow(PermissionState())

    val uiState: StateFlow<UiState> = combine(
        permissions,
        prefs.keepWakeLock,
        prefs.autostartOnBoot,
        prefs.autoOpenWeb,
        prefs.dynamicColor,
        prefs.dataDir,
        prefs.noMemoryCache,
        prefs.videoHashDirs,
        prefs.videoHashSuffix,
        prefs.videoHashRunning,
        prefs.videoHashStatus,
        prefs.silentJumpApp
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        UiState(
            permissions = values[0] as PermissionState,
            keepWakeLock = values[1] as Boolean,
            autostartOnBoot = values[2] as Boolean,
            autoOpenWeb = values[3] as Boolean,
            dynamicColor = values[4] as Boolean,
            dataDir = values[5] as String,
            noMemoryCache = values[6] as Boolean,
            videoHashDirs = values[7] as List<String>,
            videoHashSuffix = values[8] as String,
            videoHashRunning = values[9] as Boolean,
            videoHashStatus = values[10] as String,
            silentJumpApp = values[11] as Boolean
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        refreshPermissions()
    }

    /** 权限授权状态刷新（照源 updateData 的 SDK 分支判定；从系统设置返回后由 ON_RESUME 触发） */
    fun refreshPermissions() {
        val sdk = Build.VERSION.SDK_INT
        viewModelScope.launch(Dispatchers.IO) {
            val manager = sdk >= 30 && !Environment.isExternalStorageManager()
            @Suppress("DEPRECATION")
            val storage = sdk < 30 && (
                    !isGranted(android.Manifest.permission.READ_EXTERNAL_STORAGE) ||
                            !isGranted(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    )
            val notification = sdk >= 32 && !isGranted(android.Manifest.permission.POST_NOTIFICATIONS)
            permissions.value = PermissionState(manager, storage, notification)
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    // ---------- 通用开关 ----------

    fun setKeepWakeLock(value: Boolean) = set(value, prefs::setKeepWakeLock)
    fun setAutostartOnBoot(value: Boolean) = set(value, prefs::setAutostartOnBoot)
    fun setAutoOpenWeb(value: Boolean) = set(value, prefs::setAutoOpenWeb)
    fun setDynamicColor(value: Boolean) = set(value, prefs::setDynamicColor)
    fun setNoMemoryCache(value: Boolean) = set(value, prefs::setNoMemoryCache)
    fun setSilentJumpApp(value: Boolean) = set(value, prefs::setSilentJumpApp)

    private fun set(value: Boolean, setter: suspend (Boolean) -> Unit) {
        viewModelScope.launch { setter(value) }
    }

    // ---------- 数据目录 ----------

    /** 空串回退默认目录（回退逻辑在 Repository，照源 AppConfig.dataDir setter） */
    fun setDataDir(path: String) {
        viewModelScope.launch { prefs.setDataDir(path) }
    }

    // ---------- 视频洗码 ----------

    /** 仅支持单监听目录：新选目录替换旧目录（照源） */
    fun setVideoHashDirs(dirs: List<String>) {
        viewModelScope.launch { prefs.setVideoHashDirs(dirs) }
    }

    fun setVideoHashSuffix(suffix: String) {
        viewModelScope.launch { prefs.setVideoHashSuffix(suffix) }
    }

    /** 手动触发洗码（照源 startScan：目录/文字前置校验 + Snackbar + 置运行中） */
    fun startScan() {
        val state = uiState.value
        if (state.videoHashDirs.isEmpty()) {
            snack("未设置监听目录")
            return
        }
        if (state.videoHashSuffix.isBlank()) {
            snack("请输入追加文字")
            return
        }
        viewModelScope.launch {
            prefs.setVideoHashRunning(true)
            VideoHashWorker.enqueue(appContext, VideoHashWorker.MODE_SCAN, state.videoHashDirs, state.videoHashSuffix)
            snack("已开始洗码处理")
        }
    }

    /** 手动触发还原（照源 startRestore） */
    fun startRestore() {
        val state = uiState.value
        if (state.videoHashDirs.isEmpty()) {
            snack("未设置监听目录")
            return
        }
        if (state.videoHashSuffix.isBlank()) {
            snack("请输入追加文字")
            return
        }
        viewModelScope.launch {
            prefs.setVideoHashRunning(true)
            VideoHashWorker.enqueue(appContext, VideoHashWorker.MODE_RESTORE, state.videoHashDirs, state.videoHashSuffix)
            snack("已开始还原处理")
        }
    }

    /** 清除洗码防重复记录表（重置） */
    fun clearVideoHashRecords() {
        videoHashStore.clear()
    }

    fun snack(
        message: String,
        durationMillis: Long = 2000,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            _snackEvents.emit(SnackEvent(message, durationMillis, actionLabel, onAction))
        }
    }
}
