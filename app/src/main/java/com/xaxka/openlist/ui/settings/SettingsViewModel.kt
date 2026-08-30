package com.xaxka.openlist.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxka.openlist.data.log.EasyTierEventLog
import com.xaxka.openlist.data.log.QBittorrentEventLog
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import com.xaxka.openlist.easytier.EasyTierManager
import com.xaxka.openlist.qbt.QBittorrentManager
import com.xaxka.openlist.service.ServerManager
import com.xaxka.openlist.service.ServerState
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
import javax.inject.Inject

/**
 * 设置页 ViewModel：逐组复刻源 _SettingsController。
 *
 * 偏好经 [AppPrefsRepository] Flow 直读（源为 onInit/onResume 拉取）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPrefsRepository,
    private val easyTier: EasyTierManager,
    private val serverManager: ServerManager,
    private val eventLog: EasyTierEventLog,
    private val qBittorrent: QBittorrentManager,
    private val qbtEventLog: QBittorrentEventLog,
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
        val easytierSecureMode: Boolean = false,
        val easytierStatus: String = "",
        val easytierDetail: EasyTierManager.Status = EasyTierManager.Status(),
        // qbittorrent
        val qbtEnabled: Boolean = false,
        val qbtPort: String = "",
        val qbtStatus: String = "",
        val qbtDetail: QBittorrentManager.Status = QBittorrentManager.Status(),
        val qbtLanAccess: Boolean = false
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
        prefs.darkMode,
        prefs.dynamicColor,
        prefs.silentJumpApp,
        prefs.easytierEnabled,
        prefs.easytierNetwork,
        prefs.easytierNetworkSecret,
        prefs.easytierPeerUri,
        prefs.easytierQuicProxy,
        prefs.easytierSecureMode,
        easyTier.state,
        prefs.qbtEnabled,
        prefs.qbtWebUiPort,
        qBittorrent.state,
        prefs.qbtLanAccess
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        UiState(
            permissions = values[0] as PermissionState,
            keepWakeLock = values[1] as Boolean,
            autostartOnBoot = values[2] as Boolean,
            autoOpenWeb = values[3] as Boolean,
            dataDir = values[4] as String,
            noMemoryCache = values[5] as Boolean,
            darkMode = values[6] as Boolean,
            dynamicColor = values[7] as Boolean,
            silentJumpApp = values[8] as Boolean,
            easytierEnabled = values[9] as Boolean,
            easytierNetwork = values[10] as String,
            easytierNetworkSecret = values[11] as String,
            easytierPeerUri = values[12] as String,
            easytierQuicProxy = values[13] as Boolean,
            easytierSecureMode = values[14] as Boolean,
            easytierStatus = (values[15] as EasyTierManager.Status).summary,
            easytierDetail = values[15] as EasyTierManager.Status,
            qbtEnabled = values[16] as Boolean,
            qbtPort = values[17] as String,
            qbtStatus = (values[18] as QBittorrentManager.Status).summary,
            qbtDetail = values[18] as QBittorrentManager.Status,
            qbtLanAccess = values[19] as Boolean
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

    /**
     * 手动重启内网映射实例：掉线/连接异常时的兜底自愈（自愈阈值未触发或场景未覆盖时）。
     * 未启用或 OpenList 服务未运行时仅提示不执行（实例生命周期随服务启停）。
     */
    fun restartEasyTier() {
        viewModelScope.launch {
            if (!prefs.easytierEnabled.first()) {
                snack("内网映射未启用，请先打开总开关")
                return@launch
            }
            if (serverManager.state.value != ServerState.RUNNING) {
                snack("OpenList 服务未运行，启动服务后将自动连接")
                return@launch
            }
            snack("正在重启内网映射…")
            easyTier.restart()
        }
    }

    /** 读取 EasyTier 事件日记（最近 24h，旧→新原始文本），供「导出事件日记」写出。 */
    fun eventDiaryText(): String = eventLog.readRecent()

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

    /**
     * 安全模式（[secure_mode] enabled：E2EE + Noise 握手 + 防重放）：
     * 写入启动 TOML，属启动期配置，运行中切换立即重启实例；
     * 对端节点也需开启并升级到支持安全模式的版本才能互联。
     */
    fun setEasytierSecureMode(value: Boolean) {
        viewModelScope.launch {
            prefs.setEasytierSecureMode(value)
            val running = serverManager.state.value == ServerState.RUNNING &&
                prefs.easytierEnabled.first()
            if (running) {
                snack(if (value) "安全模式已启用，正在重启内网映射…" else "安全模式已停用，正在重启内网映射…")
                easyTier.restart()
            } else {
                snack(if (value) "安全模式已启用，重开内网映射开关或重启服务后生效" else "安全模式已停用，重开内网映射开关或重启服务后生效")
            }
        }
    }

    private fun setEasytierText(setter: suspend (String) -> Unit, value: String, label: String) {
        viewModelScope.launch {
            setter(value)
            snack("${label}已保存，重开内网映射开关或重启服务后生效")
        }
    }

    // ---------- qbittorrent ----------

    /** 总开关：立即启停（服务未运行时保存待服务启动生效） */
    fun setQbtEnabled(value: Boolean) {
        viewModelScope.launch {
            prefs.setQbtEnabled(value)
            val running = serverManager.state.value == ServerState.RUNNING
            when {
                running && value -> {
                    snack("正在启动 qbittorrent…")
                    qBittorrent.restart()
                }
                running && !value -> {
                    snack("正在停止 qbittorrent…")
                    qBittorrent.stop()
                }
                else -> snack(if (value) "已启用，OpenList 服务启动后自动运行" else "已停用")
            }
        }
    }

    /** WebUI 端口：保存后运行中立即重启生效 */
    fun setQbtPort(value: String) {
        viewModelScope.launch {
            prefs.setQbtWebUiPort(value)
            if (serverManager.state.value == ServerState.RUNNING && qBittorrent.state.value.phase == QBittorrentManager.Phase.RUNNING) {
                snack("端口已保存，正在重启 qbittorrent…")
                qBittorrent.restart()
            } else {
                snack("端口已保存，重开 qbittorrent 开关或重启服务后生效")
            }
        }
    }

    /** 手动重启（异常掉线自愈兜底）；开关已关或服务未启动时等价于按偏好尝试拉起 */
    fun restartQbt() {
        viewModelScope.launch {
            qBittorrent.restart()
            snack("正在重启 qbittorrent…")
        }
    }

    /**
     * 局域网访问开关（绑定切换经重启 nox 生效）。登录凭据固定 admin/adminadmin
     * （配置种子下发，无需设置）。
     */
    fun setQbtLanAccess(value: Boolean) {
        viewModelScope.launch {
            prefs.setQbtLanAccess(value)
            if (serverManager.state.value == ServerState.RUNNING && qBittorrent.state.value.phase != QBittorrentManager.Phase.STOPPED) {
                snack(if (value) "已开启局域网访问，正在重启 qbittorrent…" else "已关闭局域网访问，正在重启 qbittorrent…")
                qBittorrent.restart()
            } else {
                snack(if (value) "已开启，重启 qbittorrent 后生效（其他设备用 admin/adminadmin 登录）" else "已关闭，重启 qbittorrent 后生效")
            }
        }
    }

    /** 读取 qBittorrent 事件日记（最近 24h，旧→新原始文本），供「导出事件日记」写出。 */
    fun qbtEventDiaryText(): String = qbtEventLog.readRecent()

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
