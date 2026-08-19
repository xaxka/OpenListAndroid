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
 * OpenList 服务 RUNNING 时按偏好 [startIfEnabled]，STOPPING/STOPPED 时 [stop]。
 *
 * 端口转发为「动态绑定」：启动配置不带 [[port_forward]]（ipv4=DHCP，启动瞬间虚拟 IP
 * 尚未分配）；轮询 collectNetworkInfos 拿到 DHCP 分配的虚拟 IP 后，经
 * ConfigRpc.PatchConfig 追加 <虚拟IP>:<端口> -> 127.0.0.1:<同端口>(tcp) 转发规则，
 * 支持多端口；IP 变化或端口列表变化时增量替换绑定（REMOVE 旧 + ADD 新）。
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
    }

    enum class Phase { STOPPED, STARTING, RUNNING, STOPPING, ERROR, UNAVAILABLE }

    /** UI 快照：phase + 虚拟 IPv4 + 映射端口列表 + 已连节点数 + 说明文本。 */
    data class Status(
        val phase: Phase = Phase.STOPPED,
        val virtualIpv4: String? = null,
        val portMapped: Boolean = false,
        val mappedPorts: List<Int> = emptyList(),
        val peerCount: Int = 0,
        val detail: String = "",
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

    /** 当前已下发转发规则的绑定地址与端口（uint32 大端 / 端口列表），null/空表示尚未下发。 */
    @Volatile
    private var forwardedAddr: Long? = null

    @Volatile
    private var forwardedPorts: List<Int> = emptyList()

    @Volatile
    private var monitorJob: Job? = null

    /** 服务 RUNNING 时调用：偏好开启则启动实例，否则无动作。 */
    fun startIfEnabled() {
        scope.launch { startInternal() }
    }

    /** 停止实例（未启动时无动作）。 */
    fun stop() {
        scope.launch { stopInternal() }
    }

    private suspend fun startInternal() {
        lock.withLock {
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
            val effectiveNetwork = networkName.ifBlank { EasyTierSpec.DEFAULT_NETWORK_NAME }
            val toml = EasyTierSpec.buildToml(networkName, networkSecret, peerUri)

            // 密钥不落日志
            log(
                LoggableLevel.INFO,
                "启动内网映射：network=$effectiveNetwork, peer=${if (peerUri.isBlank()) "（未配置）" else peerUri}, " +
                    "端口 ${EasyTierSpec.formatPorts(desiredPorts(portsRaw))} 将在 DHCP 分配虚拟 IP 后动态映射",
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
            transition(Status(Phase.RUNNING, detail = "正在连接网络"))
            log(LoggableLevel.INFO, "EasyTier 实例已启动（${EasyTierSpec.INSTANCE_NAME}，no-tun）")
            startMonitor()
        }
    }

    private suspend fun stopInternal() {
        lock.withLock {
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
            transition(Status(Phase.STOPPED))
            log(LoggableLevel.INFO, "EasyTier 实例已停止")
        }
    }

    private fun startMonitor() {
        stopMonitor()
        monitorJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!instanceStarted) break
                pollStatus()
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

    /** collectNetworkInfos → 刷新状态；拿到 DHCP 虚拟 IP 后确保转发规则与当前 IP/端口列表一致。 */
    private suspend fun pollStatus() {
        val jsonText = runCatching { EasyTierJNI.collectNetworkInfos(COLLECT_MAX) }.getOrElse { e ->
            log(LoggableLevel.WARN, "collectNetworkInfos 调用失败：${e.message}")
            return
        }
        if (jsonText.isNullOrBlank()) return

        val info = runCatching { EasyTierInfoParser.parse(jsonText) }.getOrElse { null }
        if (info == null) {
            // map 中没有本实例：可能已被核心侧清理；保持 RUNNING 等待自愈，仅降级提示。
            // 保留上次已知的虚拟 IP/映射状态，避免轮询抖动导致页面闪回「等待分配虚拟 IP」。
            if (_state.value.phase == Phase.RUNNING) {
                val prev = _state.value
                transition(
                    Status(
                        Phase.RUNNING,
                        virtualIpv4 = prev.virtualIpv4,
                        portMapped = prev.portMapped,
                        mappedPorts = prev.mappedPorts,
                        peerCount = prev.peerCount,
                        detail = "实例信息暂缺",
                    )
                )
            }
            return
        }

        if (!info.running) {
            val msg = info.errorMsg.ifBlank { "实例未运行" }
            if (_state.value.phase != Phase.ERROR || _state.value.detail != msg) {
                transition(Status(Phase.ERROR, detail = msg))
                log(LoggableLevel.ERROR, "EasyTier 实例异常：$msg")
            }
            return
        }

        val addr = info.ipv4Addr
        val desired = desiredPorts(prefs.easytierPorts.first())

        // DHCP 虚拟 IP 已分配：确保转发规则与当前 IP 及端口列表一致（首次/变更时增量下发）。
        // 注意：转发下发结果不能阻塞状态刷新——否则一旦下发失败提前返回，页面会一直
        // 停在「等待分配虚拟 IP」，即便实例已连接并拿到 IP。
        var forwardDetail = ""
        if (addr != null) {
            val oldAddr = forwardedAddr
            val oldPorts = forwardedPorts
            val ipChanged = oldAddr != null && oldAddr != addr
            val needApply = oldAddr == null || ipChanged || oldPorts != desired
            if (needApply) {
                // IP 变化：在旧地址移除全部旧规则；IP 不变：仅移除被删掉的端口（仍在当前地址）
                val removeAddr = if (ipChanged) oldAddr!! else addr
                val removePorts = if (ipChanged) oldPorts else (oldPorts - desired.toSet())
                val addPorts = if (oldAddr == null || ipChanged) desired else (desired - oldPorts.toSet())
                if (!applyPortForward(removeAddr, removePorts, addr, addPorts, desired, info.ipv4)) {
                    forwardDetail = "端口映射下发失败，稍后自动重试"
                }
            }
        }

        // 状态始终反映已知的虚拟 IP（与端口映射下发结果解耦）：
        // 拿到 DHCP IP 即显示「运行中 · <IP>」，映射失败以 detail 提示并在下一轮重试。
        val next = Status(
            Phase.RUNNING,
            virtualIpv4 = info.ipv4,
            portMapped = forwardedAddr != null && forwardedPorts.isNotEmpty(),
            mappedPorts = forwardedPorts,
            peerCount = info.peerCount,
            detail = forwardDetail,
        )
        if (next != _state.value) transition(next)
    }

    /**
     * 通过 ConfigRpc.PatchConfig 下发端口转发规则；成功返回 true 并记录已下发状态。
     * 失败保持现状等待下轮重试（REMOVE 未执行也无副作用，最多多余旧规则）。
     *
     * @param removeAddr 需移除规则所在地址（removePorts 为空时忽略）
     * @param addAddr 新增绑定地址；[finalPorts] 为下发成功后应记录的完整端口列表
     */
    private fun applyPortForward(
        removeAddr: Long,
        removePorts: List<Int>,
        addAddr: Long,
        addPorts: List<Int>,
        finalPorts: List<Int>,
        ipv4: String?,
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

        val ipLabel = ipv4 ?: EasyTierSpec.formatIpv4(addAddr)
        if (error != null) {
            log(LoggableLevel.WARN, "端口转发下发失败（稍后重试）：$error")
            return false
        }

        val removedText = if (removePorts.isNotEmpty())
            "，移除 ${EasyTierSpec.formatPorts(removePorts)}"
        else ""
        val addedText = if (addPorts.isNotEmpty())
            "，新增 ${EasyTierSpec.formatPorts(addPorts)}"
        else ""

        forwardedAddr = addAddr
        forwardedPorts = finalPorts
        log(
            LoggableLevel.INFO,
            "端口转发已更新：$ipLabel -> 127.0.0.1（tcp）$removedText$addedText，" +
                "当前映射 ${EasyTierSpec.formatPorts(forwardedPorts)}",
        )
        return true
    }

    private fun transition(status: Status) {
        _state.value = status
    }

    private fun lastError(fallback: String): String =
        runCatching { EasyTierJNI.getLastError() }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    private fun log(level: LoggableLevel, message: String) {
        logBuffer.append(level, TAG, message)
        _logs.tryEmit(ServerLog(time = System.currentTimeMillis(), level = level, tag = TAG, message = message))
    }
}
