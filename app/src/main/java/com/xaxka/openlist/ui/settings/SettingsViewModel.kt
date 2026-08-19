package com.xaxka.openlist.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import com.xaxka.openlist.easytier.EasyTierManager
import com.xaxka.openlist.service.ServerManager
import com.xaxka.openlist.service.ServerState
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.work.WorkManager
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
    private val easyTier: EasyTierManager,
    private val serverManager: ServerManager,
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
        val dataDir: String = "",
        val noMemoryCache: Boolean = true,
        // 视频洗码
        val videoHashDirs: List<String> = emptyList(),
        val videoHashSuffix: String = "",
        val videoHashRunning: Boolean = false,
        val videoHashStatus: String = "",
        // 界面
        val darkMode: Boolean = false,
        val dynamicColor: Boolean = false,
        val silentJumpApp: Boolean = false,
        // 内网映射（EasyTier）
        val easytierEnabled: Boolean = false,
        val easytierNetwork: String = "",
        val easytierNetworkSecret: String = "",
        val easytierPeerUri: String = "",
        val easytierQuicProxy: Boolean = true,
        val easytierStatus: String = "",
        val easytierDetail: EasyTierManager.Status = EasyTierManager.Status()
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
        prefs.dataDir,
        prefs.noMemoryCache,
        prefs.videoHashDirs,
        prefs.videoHashSuffix,
        prefs.videoHashRunning,
        prefs.videoHashStatus,
        prefs.darkMode,
        prefs.dynamicColor,
        prefs.silentJumpApp,
        prefs.easytierEnabled,
        prefs.easytierNetwork,
        prefs.easytierNetworkSecret,
        prefs.easytierPeerUri,
        prefs.easytierQuicProxy,
        easyTier.state
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        UiState(
            permissions = values[0] as PermissionState,
            keepWakeLock = values[1] as Boolean,
            autostartOnBoot = values[2] as Boolean,
            autoOpenWeb = values[3] as Boolean,
            dataDir = values[4] as String,
            noMemoryCache = values[5] as Boolean,
            videoHashDirs = values[6] as List<String>,
            videoHashSuffix = values[7] as String,
            videoHashRunning = values[8] as Boolean,
            videoHashStatus = values[9] as String,
            darkMode = values[10] as Boolean,
            dynamicColor = values[11] as Boolean,
            silentJumpApp = values[12] as Boolean,
            easytierEnabled = values[13] as Boolean,
            easytierNetwork = values[14] as String,
            easytierNetworkSecret = values[15] as String,
            easytierPeerUri = values[16] as String,
            easytierQuicProxy = values[17] as Boolean,
            easytierStatus = (values[18] as EasyTierManager.Status).summary,
            easytierDetail = values[18] as EasyTierManager.Status
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        refreshPermissions()
        viewModelScope.launch(Dispatchers.IO) { repairRunningFlag() }
    }

    /**
     * 进程被杀导致 Worker 未能复位时，videoHashRunning 会残留 true（设置页永远转圈）。
     * 打开设置页时查询唯一任务实际状态：无在途（RUNNING/ENQUEUED）任务则复位；
     * 查询失败保守不复位。
     */
    private suspend fun repairRunningFlag() {
        if (!prefs.videoHashRunning.first()) return
        val hasActiveWork = runCatching {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWork(VideoHashWorker.WORK_NAME_ONETIME)
                .get()
                .any { !it.state.isFinished }
        }.getOrDefault(true)
        if (!hasActiveWork) {
            prefs.setVideoHashRunning(false)
            prefs.setVideoHashStatus("上次任务未正常结束，已重置")
        }
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
    fun setNoMemoryCache(value: Boolean) = set(value, prefs::setNoMemoryCache)
    fun setDarkMode(value: Boolean) = set(value, prefs::setDarkMode)
    fun setDynamicColor(value: Boolean) = set(value, prefs::setDynamicColor)
    fun setSilentJumpApp(value: Boolean) = set(value, prefs::setSilentJumpApp)

    private fun set(value: Boolean, setter: suspend (Boolean) -> Unit) {
        viewModelScope.launch { setter(value) }
    }

    // ---------- 数据目录 ----------

    /** 空串回退默认目录（回退逻辑在 Repository，照源 AppConfig.dataDir setter）；重启服务后生效 */
    fun setDataDir(path: String) {
        viewModelScope.launch {
            prefs.setDataDir(path)
            snack("数据目录已保存，重启 OpenList 服务后生效")
        }
    }

    // ---------- 内网映射（EasyTier） ----------

    /** 总开关：开启且服务正在运行时立即拉起实例，关闭立即停止；否则随下次服务启动生效。 */
    fun setEasytierEnabled(value: Boolean) {
        viewModelScope.launch {
            prefs.setEasytierEnabled(value)
            if (serverManager.state.value == ServerState.RUNNING) {
                if (value) easyTier.startIfEnabled() else easyTier.stop()
            }
            snack(if (value) "内网映射已启用" else "内网映射已停用")
        }
    }

    /** 网络名称/密钥/对端 URI：保存后需重启服务（或重开总开关）生效。 */
    fun setEasytierNetwork(value: String) = setEasytierText(prefs::setEasytierNetwork, value, "网络名称")

    fun setEasytierNetworkSecret(value: String) = setEasytierText(prefs::setEasytierNetworkSecret, value, "网络密钥")

    fun setEasytierPeerUri(value: String) = setEasytierText(prefs::setEasytierPeerUri, value, "对等节点")

    /** QUIC 代理（enable_quic_proxy）：写入启动 TOML，属启动期配置，需重启实例生效。 */
    fun setEasytierQuicProxy(value: Boolean) {
        viewModelScope.launch {
            prefs.setEasytierQuicProxy(value)
            val running = serverManager.state.value == ServerState.RUNNING &&
                prefs.easytierEnabled.first()
            if (running) {
                snack(if (value) "QUIC 代理已启用，正在重启内网映射…" else "QUIC 代理已停用，正在重启内网映射…")
                easyTier.restart()
            } else {
                snack(if (value) "QUIC 代理已启用，重开内网映射开关或重启服务后生效" else "QUIC 代理已停用，重开内网映射开关或重启服务后生效")
            }
        }
    }

    private fun setEasytierText(setter: suspend (String) -> Unit, value: String, label: String) {
        viewModelScope.launch {
            setter(value)
            snack("${label}已保存，重开内网映射开关或重启服务后生效")
        }
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
