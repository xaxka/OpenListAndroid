package com.xaxka.openlist.easytier

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import com.easytier.jni.EasyTierJNI
import com.xaxka.openlist.data.log.LogBuffer
import com.xaxka.openlist.data.log.LoggableLevel
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.xaxka.openlist.data.log.ServerLog

/**
 * EasyTier 内网映射引擎（no-tun，无 Android VPN 服务）。
 *
 * 生命周期由 [com.xaxka.openlist.service.ServerManager] 驱动：
 * OpenList 服务 RUNNING 时按偏好 [startIfEnabled]，STOPPING/STOPPED 时 [stop]；
 * App 回前台时由 [ensureRecovered] 校验实例存活（应对 OPPO 等厂商后台冻结/清理后
 * 实例丢失、恢复前台却无法自愈的问题）。
 *
 * 后台断连自愈：实例 running 但与初始节点静默断连（NAT 超时/网络切换/休眠后
 * 套接字僵死，核心侧重连失败）时状态会长期停留「运行中·0 节点」；
 * 曾连上后持续无节点达到 [PEER_LOST_RESTART_THRESHOLD] 轮即自动重启，
 * 并监听系统网络变化事件（Wi-Fi/移动网络切换）立即轮询加速检测。
 *
 * 无需下发端口转发规则：EasyTier no-tun 模式下，核心的代理引擎会把组网设备发往本机
 * 虚拟 IP 的 TCP/UDP/ICMP 包自动落到本机回环同端口，即 <虚拟IP>:5244 直达 OpenList。
 * 本类只负责实例生命周期、状态轮询与自愈。
 */
@Singleton
class EasyTierManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val prefs: AppPrefsRepository,
    private val logBuffer: LogBuffer,
) {
    companion object {
        private const val TAG = "EasyTier"
        private const val POLL_INTERVAL_MS = 4000L
        private const val COLLECT_MAX = 16

        /** collectNetworkInfos 连续 N 轮找不到本实例 → 判定实例丢失（冻结/清理），自动重启。 */
        private const val MISSING_RESTART_THRESHOLD = 3

        /** 实例连续 N 轮上报异常（running=false）→ 自动重启自愈。 */
        private const val ERROR_RESTART_THRESHOLD = 5

        /**
         * 曾连上节点后连续 N 轮无任何对等节点 → 判定与初始节点静默断连，自动重启自愈。
         * 32 秒（8 轮 × 4 秒）宽限期：网络切换后核心正常重连通常数秒完成，
         * 期间不计入（peerCount > 0 即清零），只有真正僵死才触发重启。
         */
        private const val PEER_LOST_RESTART_THRESHOLD = 8

        /** 事件日志最多保留条数（状态页只读展示）。 */
        private const val EVENTS_KEEP = 200
    }

    enum class Phase { STOPPED, STARTING, RUNNING, ERROR, UNAVAILABLE }

    /**
     * UI 快照：phase + 虚拟 IPv4 + 已连节点数 + 说明文本，
     * 以及状态页所需的只读明细（本节点/对等节点/路由/事件日志/启动配置脱敏文本）。
     */
    data class Status(
        val phase: Phase = Phase.STOPPED,
        val virtualIpv4: String? = null,
        val peerCount: Int = 0,
        val detail: String = "",
        val myNode: MyNodeInfo? = null,
        val peers: List<PeerDetail> = emptyList(),
        val routes: List<RouteDetail> = emptyList(),
        val events: List<String> = emptyList(),
        val startupToml: String = "",
    ) {
        val summary: String
            get() = when (phase) {
                Phase.STOPPED -> "未启动".appendDetail(detail)
                Phase.STARTING -> "启动中".appendDetail(detail)
                Phase.RUNNING -> runningSummary()
                Phase.ERROR -> "错误".appendDetail(detail)
                Phase.UNAVAILABLE -> "不可用".appendDetail(detail)
            }

        private fun runningSummary(): String {
            val head = if (virtualIpv4 != null) {
                "运行中 · $virtualIpv4"
            } else if (peerCount > 0) {
                // 已组网但 DHCP 尚未分配虚拟 IP：明确展示连接状态，避免误以为没连上
                "已连接 $peerCount 个节点 · 等待分配虚拟 IP"
            } else {
                "运行中 · 等待分配虚拟 IP"
            }
            if (detail.isNotEmpty()) return "$head · $detail"
            return head
        }

        private fun String.appendDetail(d: String) = if (d.isEmpty()) this else "$this：$d"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val _state = MutableStateFlow(Status())
    val state: StateFlow<Status> = _state.asStateFlow()

    private val _logs = MutableSharedFlow<ServerLog>(extraBufferCapacity = 64)

    /** EasyTier 运行日志（ServerManager 汇入主页日志流）。 */
    val logs: SharedFlow<ServerLog> = _logs.asSharedFlow()

    @Volatile
    private var instanceStarted = false

    /** collectNetworkInfos 连续未找到本实例的轮数。 */
    private var missingStreak = 0

    /** 实例连续上报 running=false 的轮数。 */
    private var errorStreak = 0

    /** 本次启动是否配置了初始节点（未配置则 0 节点属正常，不做断连自愈）。 */
    @Volatile
    private var peerConfigured = false

    /** 本次启动后是否曾成功连上过对等节点（区分「未连过」与「连过又断」）。 */
    @Volatile
    private var hadConnectedOnce = false

    /** 实例运行中连续无任何对等节点的轮数（仅连过之后才统计）。 */
    private var peerLostStreak = 0

    /** 系统网络变化回调（网络切换 → 立即轮询；实例启动期间注册，停止时注销）。 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 最近一次启动使用的脱敏 TOML（状态页「启动配置」展示）。 */
    private var displayToml = ""

    @Volatile
    private var monitorJob: Job? = null

    /** 服务 RUNNING 时调用：偏好开启则启动实例，否则无动作。 */
    fun startIfEnabled() {
        scope.launch {
            lock.withLock { startLocked() }
        }
    }

    /** 停止实例（未启动时无动作）。 */
    fun stop() {
        scope.launch {
            lock.withLock { stopLocked() }
        }
    }

    /** 重启实例（未启用/原生库不可用时等价于尝试重新启动）；配置类偏好变更后强制重建用。 */
    fun restart() {
        scope.launch {
            lock.withLock {
                if (instanceStarted) {
                    restartLocked("配置变更")
                } else {
                    startLocked()
                }
            }
        }
    }

    /**
     * App 回到前台时调用（ProcessLifecycleOwner ON_START）：
     * OPPO 等厂商后台会冻结/清理进程，解冻后原生实例可能已丢失而 Kotlin 侧状态仍是
     * 「已启动」——这里以 listInstances 的实际结果为准校验，实例不在则立即重启，
     * 仍在则立即轮询一次，尽快重新对齐状态。
     * 另兜底：偏好开启但实例未启动（后台被停止等边缘情况）时按偏好补拉起。
     */
    fun ensureRecovered() {
        scope.launch {
            lock.withLock {
                if (!EasyTierJNI.isAvailable) return@launch
                if (!prefs.easytierEnabled.first()) return@launch
                if (!instanceStarted) {
                    startLocked()
                    return@launch
                }
                val alive = runCatching {
                    val json = EasyTierJNI.listInstances(COLLECT_MAX)
                    json != null && EasyTierInfoParser.containsInstance(json)
                }.getOrDefault(false)
                if (alive) {
                    pollStatusLocked()
                } else {
                    log(LoggableLevel.WARN, "检测到 EasyTier 实例在后台丢失（冻结/清理），自动重启恢复")
                    restartLocked("实例在后台丢失")
                }
            }
        }
    }

    /** 偏好开启且未启动时启动实例；调用方需持有 [lock]。 */
    private suspend fun startLocked() {
        if (instanceStarted || _state.value.phase == Phase.STARTING) return

        if (!prefs.easytierEnabled.first()) return

        if (!EasyTierJNI.isAvailable) {
            val msg = "EasyTier 原生库未打包：${EasyTierJNI.loadError}"
            transition(Status(Phase.UNAVAILABLE, detail = EasyTierJNI.loadError.orEmpty()))
            log(LoggableLevel.WARN, msg)
            return
        }

        val networkName = prefs.easytierNetwork.first()
        val networkSecret = prefs.easytierNetworkSecret.first()
        val peerUri = prefs.easytierPeerUri.first()
        val quicProxy = prefs.easytierQuicProxy.first()
        val secureMode = prefs.easytierSecureMode.first()
        val effectiveNetwork = networkName.ifBlank { EasyTierSpec.DEFAULT_NETWORK_NAME }
        // 安全模式密钥对：TOML 入口不执行 normalize_secure_mode_config（CLI --secure-mode
        // 的自动生成仅存在于 CLI/Web GUI 入口），缺失时核心报 "local private key is not set"，
        // 故 App 侧生成 X25519 密钥对并持久化（身份稳定，优于每次启动的临时密钥）。
        val keyPair = if (secureMode) ensureSecureModeKeyPair() else null
        val toml = EasyTierSpec.buildToml(
            networkName, networkSecret, peerUri,
            enableQuicProxy = quicProxy, secureMode = secureMode,
            localPrivateKey = keyPair?.first.orEmpty(),
            localPublicKey = keyPair?.second.orEmpty(),
        )
        displayToml = EasyTierSpec.buildDisplayToml(
            networkName, networkSecret, peerUri, quicProxy, secureMode,
            localPrivateKey = keyPair?.first.orEmpty(),
            localPublicKey = keyPair?.second.orEmpty(),
        )

        // 密钥不落日志（展示 TOML 已脱敏）
        log(
            LoggableLevel.INFO,
            "启动内网映射：network=$effectiveNetwork, peer=${if (peerUri.isBlank()) "（未配置）" else peerUri}, " +
                "quicProxy=$quicProxy, secureMode=$secureMode" +
                (if (keyPair != null) "（节点密钥已就绪）" else ""),
        )

        transition(Status(Phase.STARTING))

        val error = runCatching {
            if (EasyTierJNI.parseConfig(toml) != 0) {
                return@runCatching lastError("配置解析失败")
            }
            if (EasyTierJNI.runNetworkInstance(toml) != 0) {
                return@runCatching lastError("实例启动失败")
            }
            null
        }.getOrElse { e -> e.message ?: "实例启动异常" }

        if (error != null) {
            transition(Status(Phase.ERROR, detail = error))
            log(LoggableLevel.ERROR, "EasyTier 启动失败：$error")
            return
        }

        instanceStarted = true
        missingStreak = 0
        errorStreak = 0
        peerConfigured = peerUri.isNotBlank()
        hadConnectedOnce = false
        peerLostStreak = 0
        transition(Status(Phase.RUNNING, detail = "正在连接网络"))
        log(LoggableLevel.INFO, "EasyTier 实例已启动（${EasyTierSpec.INSTANCE_NAME}，no-tun）")
        registerNetworkCallback()
        startMonitor()
    }

    /**
     * 确保安全模式密钥对可用（base64 私钥/公钥）：
     * - 首次（无私钥）：生成 X25519 密钥对并持久化
     * - 私钥损坏（非法 base64 / 长度异常）：重新生成
     * - 公钥始终从私钥重新派生——上游会校验 "public key does not match its private key"，
     *   重新派生可自愈存储不一致，同时覆盖持久化的公钥
     */
    private suspend fun ensureSecureModeKeyPair(): Pair<String, String> {
        val storedPrivate = prefs.easytierLocalPrivateKey.first()
        val privateBytes = storedPrivate.takeIf { it.isNotBlank() }
            ?.let { X25519.decodeBase64(it) }

        if (privateBytes == null || privateBytes.size != 32) {
            val fresh = X25519.generatePrivateKey()
            val privateKey = X25519.encodeBase64(fresh)
            val publicKey = X25519.encodeBase64(X25519.publicFromPrivateKey(fresh))
            prefs.setEasytierLocalPrivateKey(privateKey)
            prefs.setEasytierLocalPublicKey(publicKey)
            log(
                LoggableLevel.INFO,
                if (storedPrivate.isBlank()) "已自动生成安全模式节点密钥（X25519，持久化存储）"
                else "存储的安全模式私钥无效，已重新生成",
            )
            return privateKey to publicKey
        }

        val privateKey = X25519.encodeBase64(privateBytes)
        val publicKey = X25519.encodeBase64(X25519.publicFromPrivateKey(privateBytes))
        if (publicKey != prefs.easytierLocalPublicKey.first()) {
            prefs.setEasytierLocalPublicKey(publicKey)
            log(LoggableLevel.WARN, "安全模式公钥与私钥不一致，已按私钥重新派生并修正")
        }
        return privateKey to publicKey
    }

    /** 停止实例；调用方需持有 [lock]。 */
    private suspend fun stopLocked() {
        stopMonitor()
        if (!instanceStarted && _state.value.phase != Phase.RUNNING &&
            _state.value.phase != Phase.STARTING && _state.value.phase != Phase.ERROR
        ) {
            return
        }
        if (EasyTierJNI.isAvailable) {
            val error = runCatching {
                if (EasyTierJNI.stopAllInstances() != 0) lastError("停止实例失败") else null
            }.getOrElse { e -> e.message ?: "停止实例异常" }
            if (error != null) {
                log(LoggableLevel.WARN, "EasyTier 停止返回错误：$error")
            }
        }
        instanceStarted = false
        missingStreak = 0
        errorStreak = 0
        peerConfigured = false
        hadConnectedOnce = false
        peerLostStreak = 0
        displayToml = ""
        unregisterNetworkCallback()
        transition(Status(Phase.STOPPED))
        log(LoggableLevel.INFO, "EasyTier 实例已停止")
    }

    /** 先停后启（用于自愈）；调用方需持有 [lock]。 */
    private suspend fun restartLocked(reason: String) {
        log(LoggableLevel.WARN, "重启 EasyTier 实例：$reason")
        // stopLocked 会把状态复位到 STOPPED；startLocked 重新读取偏好启动
        stopLocked()
        startLocked()
    }

    private fun startMonitor() {
        stopMonitor()
        monitorJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!instanceStarted) break
                lock.withLock {
                    if (instanceStarted) pollStatusLocked()
                }
            }
        }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * 注册系统网络变化回调：Wi-Fi/移动网络切换后旧连接必然失效，
     * onAvailable 时立即轮询一次，加速断连检测与状态对齐（重启判定仍由轮询节拍驱动）。
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    lock.withLock {
                        if (instanceStarted) pollStatusLocked()
                    }
                }
            }
        }
        val request = NetworkRequest.Builder().build()
        runCatching {
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
        }.onFailure {
            log(LoggableLevel.WARN, "网络变化监听注册失败：${it.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            val manager =
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            manager?.unregisterNetworkCallback(callback)
        }
    }

    /**
     * collectNetworkInfos → 刷新状态；实例丢失/异常达到阈值时自动重启（自愈）。
     * 调用方需持有 [lock]。
     */
    private suspend fun pollStatusLocked() {
        val jsonText = runCatching { EasyTierJNI.collectNetworkInfos(COLLECT_MAX) }.getOrElse { e ->
            log(LoggableLevel.WARN, "collectNetworkInfos 调用失败：${e.message}")
            return
        }
        if (jsonText.isNullOrBlank()) return

        val info = runCatching { EasyTierInfoParser.parse(jsonText) }.getOrElse { null }
        if (info == null) {
            // map 中没有本实例：可能已被核心侧清理或后台冻结导致实例丢失。
            // 连续多轮缺失则重启自愈（OPPO 冻结/清理恢复场景）；偶发缺失仅降级提示，
            // 保留上次已知的虚拟 IP/映射状态，避免轮询抖动导致页面闪回「等待分配虚拟 IP」。
            missingStreak++
            if (instanceStarted && missingStreak >= MISSING_RESTART_THRESHOLD) {
                restartLocked("实例信息连续 ${missingStreak} 轮缺失")
                return
            }
            if (_state.value.phase == Phase.RUNNING) {
                // 只改 detail，其余字段（含节点/事件明细）沿用上一轮快照
                transition(_state.value.copy(detail = "实例信息暂缺"))
            }
            return
        }
        missingStreak = 0

        if (!info.running) {
            // 实例自报异常：先透出错误，持续异常则重启自愈
            errorStreak++
            val msg = info.errorMsg.ifBlank { "实例未运行" }
            if (instanceStarted && errorStreak >= ERROR_RESTART_THRESHOLD) {
                restartLocked("实例持续异常：$msg")
                return
            }
            if (_state.value.phase != Phase.ERROR || _state.value.detail != msg) {
                transition(Status(Phase.ERROR, detail = msg, events = info.events.takeLast(EVENTS_KEEP)))
                log(LoggableLevel.ERROR, "EasyTier 实例异常：$msg")
            }
            return
        }
        errorStreak = 0

        // 后台断连自愈：实例 running 但 0 节点。未配置初始节点时 0 节点属正常；
        // 连上后又持续无节点（NAT 超时/网络切换/休眠后套接字僵死，核心重连失败），
        // 达到阈值即重启自愈，避免状态永远停留「运行中·0 节点」。
        if (info.peerCount > 0) {
            hadConnectedOnce = true
            peerLostStreak = 0
        } else if (peerConfigured && hadConnectedOnce) {
            peerLostStreak++
            if (instanceStarted && peerLostStreak >= PEER_LOST_RESTART_THRESHOLD) {
                restartLocked("与初始节点连接持续中断 ${peerLostStreak * POLL_INTERVAL_MS / 1000} 秒")
                return
            }
        } else {
            peerLostStreak = 0
        }
        val lostDetail = if (peerLostStreak > 0) "与初始节点连接中断，自动恢复中" else ""

        val next = Status(
            Phase.RUNNING,
            virtualIpv4 = info.ipv4,
            peerCount = info.peerCount,
            detail = lostDetail,
            myNode = info.myNode,
            peers = info.peers,
            routes = info.routes,
            events = info.events.takeLast(EVENTS_KEEP),
        )
        if (next != _state.value) transition(next)
    }

    private fun transition(status: Status) {
        // 启动配置随所有状态携带（STOPPED 时 startLocked 前已清空 displayToml）
        _state.value = if (status.startupToml.isEmpty() && displayToml.isNotEmpty()) {
            status.copy(startupToml = displayToml)
        } else {
            status
        }
    }

    private fun lastError(fallback: String): String =
        runCatching { EasyTierJNI.getLastError() }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    private fun log(level: LoggableLevel, message: String) {
        logBuffer.append(level, TAG, message)
        _logs.tryEmit(ServerLog(time = System.currentTimeMillis(), level = level, tag = TAG, message = message))
    }
}
