package com.xaxka.openlist.easytier

import com.easytier.jni.EasyTierJNI
import com.xaxka.openlist.data.log.LogBuffer
import com.xaxka.openlist.data.log.LoggableLevel
import com.xaxka.openlist.data.prefs.AppPrefsRepository
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
 * 端口转发为「动态绑定 + 实际生效对账」：启动配置不带 [[port_forward]]（ipv4=DHCP，
 * 启动瞬间虚拟 IP 尚未分配）；轮询 collectNetworkInfos 拿到 DHCP 分配的虚拟 IP 后，
 * 先经 PortForwardManageRpc.ListPortForward 读取实例当前实际生效的转发规则，
 * 与期望端口列表求差集，再经 ConfigRpc.PatchConfig 增量下发（REMOVE 多余 + ADD 缺失）。
 * 状态页的「已映射端口」以实际生效规则为准，单次下发失败时透出核心返回的真实错误并
 * 在下轮轮询自动重试，不再依赖单次的乐观记录。
 */
@Singleton
class EasyTierManager @Inject constructor(
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

        /** 事件日志最多保留条数（状态页只读展示）。 */
        private const val EVENTS_KEEP = 200
    }

    enum class Phase { STOPPED, STARTING, RUNNING, STOPPING, ERROR, UNAVAILABLE }

    /**
     * UI 快照：phase + 虚拟 IPv4 + 映射端口列表 + 已连节点数 + 说明文本，
     * 以及状态页所需的只读明细（本节点/对等节点/路由/事件日志/启动配置脱敏文本）。
     */
    data class Status(
        val phase: Phase = Phase.STOPPED,
        val virtualIpv4: String? = null,
        val portMapped: Boolean = false,
        val mappedPorts: List<Int> = emptyList(),
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
                Phase.STOPPING -> "停止中"
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
            // 端口转发下发失败等异常通过 detail 承载，优先于通用进度文案展示
            if (detail.isNotEmpty()) return "$head · $detail"
            val tail = when {
                portMapped && mappedPorts.isNotEmpty() ->
                    "端口 ${EasyTierSpec.formatPorts(mappedPorts)} 已映射"
                virtualIpv4 != null -> "端口映射添加中"
                else -> "映射待 DHCP 分配 IP 后自动添加"
            }
            return "$head · $tail"
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

    /** 最近一次成功下发后认为应存在的绑定地址与端口（虚拟 IP 变化时用于移除旧规则）。 */
    @Volatile
    private var forwardedAddr: Long? = null

    @Volatile
    private var forwardedPorts: List<Int> = emptyList()

    /** 最近一次端口转发下发的真实错误（来自核心 RPC），成功后清空。 */
    @Volatile
    private var lastForwardError: String? = null

    /** 上一条已写日志的下发错误（同一错误不重复刷屏）。 */
    private var lastLoggedForwardError: String? = null

    /** collectNetworkInfos 连续未找到本实例的轮数。 */
    private var missingStreak = 0

    /** 实例连续上报 running=false 的轮数。 */
    private var errorStreak = 0

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
     * 仍在则立即轮询一次，尽快重新对齐状态与端口转发。
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
        val portsRaw = prefs.easytierPorts.first()
        val quicProxy = prefs.easytierQuicProxy.first()
        val effectiveNetwork = networkName.ifBlank { EasyTierSpec.DEFAULT_NETWORK_NAME }
        val toml = EasyTierSpec.buildToml(networkName, networkSecret, peerUri, enableQuicProxy = quicProxy)
        displayToml = EasyTierSpec.buildDisplayToml(networkName, networkSecret, peerUri, quicProxy)

        // 密钥不落日志（展示 TOML 已脱敏）
        log(
            LoggableLevel.INFO,
            "启动内网映射：network=$effectiveNetwork, peer=${if (peerUri.isBlank()) "（未配置）" else peerUri}, " +
                "quicProxy=$quicProxy, 端口 ${EasyTierSpec.formatPorts(desiredPorts(portsRaw))} 将在 DHCP 分配虚拟 IP 后动态映射",
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
        forwardedAddr = null
        forwardedPorts = emptyList()
        lastForwardError = null
        missingStreak = 0
        errorStreak = 0
        transition(Status(Phase.RUNNING, detail = "正在连接网络"))
        log(LoggableLevel.INFO, "EasyTier 实例已启动（${EasyTierSpec.INSTANCE_NAME}，no-tun）")
        startMonitor()
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
        forwardedAddr = null
        forwardedPorts = emptyList()
        lastForwardError = null
        missingStreak = 0
        errorStreak = 0
        displayToml = ""
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

    /** 解析偏好端口列表；空白/全部非法时回退仅映射默认端口。 */
    internal fun desiredPorts(raw: String): List<Int> =
        EasyTierSpec.parsePorts(raw).ifEmpty { listOf(EasyTierSpec.PRIMARY_PORT) }

    /**
     * collectNetworkInfos → 刷新状态；实例丢失/异常达到阈值时自动重启（自愈）；
     * 拿到 DHCP 虚拟 IP 后对账并校正端口转发规则。调用方需持有 [lock]。
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

        val addr = info.ipv4Addr
        val desired = desiredPorts(prefs.easytierPorts.first())

        // DHCP 虚拟 IP 已分配：以实际生效的转发规则为准做对账。
        // 注意：转发下发结果不能阻塞状态刷新——否则一旦下发失败提前返回，页面会一直
        // 停在「等待分配虚拟 IP」，即便实例已连接并拿到 IP。
        if (addr != null) {
            reconcilePortForwards(addr, desired)
        }

        // 已映射端口以「实际生效 ∩ 期望」为准；read-back 失败时退回乐观记录。
        val actualPorts = if (addr != null) readActualPorts(addr) else null
        val effectivePorts: List<Int>
        if (actualPorts != null) {
            val actualSet = actualPorts.toSet()
            effectivePorts = desired.filter { it in actualSet }
        } else {
            effectivePorts = if (forwardedAddr == addr) forwardedPorts else emptyList()
        }

        val forwardDetail = buildString {
            if (effectivePorts != desired) {
                val missing = desired - effectivePorts.toSet()
                append("端口 ${EasyTierSpec.formatPorts(missing)} 映射中")
                val err = lastForwardError
                if (!err.isNullOrEmpty()) append(" · 下发失败：${err.take(120)}")
            }
        }

        // 状态始终反映已知的虚拟 IP（与端口映射下发结果解耦）：
        // 拿到 DHCP IP 即显示「运行中 · <IP>」，映射失败以 detail 提示并在下一轮重试。
        val next = Status(
            Phase.RUNNING,
            virtualIpv4 = info.ipv4,
            portMapped = effectivePorts == desired && desired.isNotEmpty(),
            mappedPorts = effectivePorts,
            peerCount = info.peerCount,
            detail = forwardDetail,
            myNode = info.myNode,
            peers = info.peers,
            routes = info.routes,
            events = info.events.takeLast(EVENTS_KEEP),
        )
        if (next != _state.value) transition(next)
    }

    /**
     * 端口转发对账：读取实例当前实际生效规则，与期望端口求差集后增量下发。
     * 虚拟 IP 变化时先移除旧地址上的旧规则（尽力而为，失败不阻塞新地址下发）。
     * 成功后更新本地乐观记录（read-back 失败时的降级依据），并清空残留错误。
     */
    private suspend fun reconcilePortForwards(addr: Long, desired: List<Int>) {
        val oldAddr = forwardedAddr
        val oldPorts = forwardedPorts
        val ipChanged = oldAddr != null && oldAddr != addr

        // IP 变化：先移除旧地址上的全部旧规则
        if (ipChanged && oldPorts.isNotEmpty()) {
            sendPortForwardPatch(oldAddr!!, oldPorts, addr, emptyList())
        }

        val actualPorts = readActualPorts(addr)
        val removePorts: List<Int>
        val addPorts: List<Int>
        if (actualPorts != null) {
            val desiredSet = desired.toSet()
            removePorts = actualPorts.filter { it !in desiredSet }
            addPorts = desired.filter { it !in actualPorts.toSet() }
        } else {
            // read-back 失败：退回「本地乐观记录」的增量同步（旧逻辑）
            val base = if (ipChanged) emptyList() else oldPorts
            removePorts = base - desired.toSet()
            addPorts = desired - base.toSet()
        }

        if (removePorts.isEmpty() && addPorts.isEmpty()) {
            forwardedAddr = addr
            forwardedPorts = desired
            lastForwardError = null
            return
        }

        if (sendPortForwardPatch(addr, removePorts, addr, addPorts)) {
            forwardedAddr = addr
            forwardedPorts = desired
            lastForwardError = null
            val removedText = if (removePorts.isNotEmpty())
                "，移除 ${EasyTierSpec.formatPorts(removePorts)}" else ""
            val addedText = if (addPorts.isNotEmpty())
                "，新增 ${EasyTierSpec.formatPorts(addPorts)}" else ""
            log(
                LoggableLevel.INFO,
                "端口转发已更新：${infoIpv4(addr)} -> 127.0.0.1（tcp）$removedText$addedText，" +
                    "当前应映射 ${EasyTierSpec.formatPorts(desired)}",
            )
        }
        // 失败保持现状等待下轮重试（REMOVE 未执行也无副作用，最多多余旧规则）
    }

    /**
     * 通过 PortForwardManageRpc.ListPortForward 读取实例实际生效的转发规则，
     * 返回绑定在本虚拟 IP 且指向回环同端口的规则端口；RPC 失败/解析失败返回 null。
     */
    private fun readActualPorts(addr: Long): List<Int>? {
        val resp = runCatching {
            EasyTierJNI.callJsonRpc(
                EasyTierSpec.PORT_FORWARD_RPC_SERVICE,
                EasyTierSpec.LIST_PORT_FORWARD_METHOD,
                EasyTierSpec.buildInstanceSelectorJson(),
            )
        }.getOrNull() ?: return null
        return runCatching { EasyTierInfoParser.parseForwardedPorts(resp, addr) }.getOrNull()
    }

    /** 通过 ConfigRpc.PatchConfig 下发端口转发增量补丁；成功返回 true，失败记录真实错误。 */
    private fun sendPortForwardPatch(
        removeAddr: Long,
        removePorts: List<Int>,
        addAddr: Long,
        addPorts: List<Int>,
    ): Boolean {
        if (addPorts.isEmpty() && removePorts.isEmpty()) return true

        val payload = EasyTierSpec.buildPortForwardPatchJson(removeAddr, removePorts, addAddr, addPorts)
        val error = runCatching {
            EasyTierJNI.callJsonRpc(
                EasyTierSpec.CONFIG_RPC_SERVICE,
                EasyTierSpec.PATCH_CONFIG_METHOD,
                payload,
            )
            null
        }.getOrElse { e -> e.message ?: lastError("端口转发下发失败") }

        if (error != null) {
            lastForwardError = error
            // 同一错误只打一次日志，避免 4s 一轮刷屏；错误变化时再提示
            if (error != lastLoggedForwardError) {
                lastLoggedForwardError = error
                log(LoggableLevel.WARN, "端口转发下发失败（稍后重试）：$error")
            }
            return false
        }
        return true
    }

    private fun infoIpv4(addr: Long): String = EasyTierSpec.formatIpv4(addr)

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
