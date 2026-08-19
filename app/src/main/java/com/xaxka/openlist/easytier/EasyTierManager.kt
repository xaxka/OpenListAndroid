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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import com.xaxka.openlist.data.log.ServerLog

/**
 * EasyTier 内网映射引擎（no-tun，无 Android VPN 服务）。
 *
 * 生命周期由 [com.xaxka.openlist.service.ServerManager] 驱动：
 * OpenList 服务 RUNNING 时按偏好 [startIfEnabled]，STOPPING/STOPPED 时 [stop]。
 *
 * 端口转发为「动态绑定」：启动配置不带 [[port_forward]]（ipv4=DHCP，启动瞬间虚拟 IP
 * 尚未分配）；轮询 collectNetworkInfos 拿到 DHCP 分配的虚拟 IP 后，经
 * ConfigRpc.PatchConfig 追加 <虚拟IP>:5244 -> 127.0.0.1:5244(tcp) 转发规则；
 * IP 变化时替换旧绑定。
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

    /** UI 快照：phase + 已分得的虚拟 IPv4（可空）+ 端口是否已映射 + 说明文本。 */
    data class Status(
        val phase: Phase = Phase.STOPPED,
        val virtualIpv4: String? = null,
        val portMapped: Boolean = false,
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
            val head = if (virtualIpv4 != null) "运行中 · $virtualIpv4" else "运行中 · 等待分配虚拟 IP"
            val tail = when {
                portMapped -> "端口 ${EasyTierSpec.PORT} 已映射"
                virtualIpv4 != null -> "端口映射添加中"
                else -> "映射待 DHCP 分配 IP 后自动添加"
            }
            return "$head · $tail"
        }

        private fun String.appendDetail(d: String) = if (d.isEmpty()) this else "$this：$d"
    }

    /** collectNetworkInfos 中本实例的运行信息。 */
    internal data class InstanceInfo(
        val running: Boolean,
        val ipv4: String?,
        val ipv4Addr: Long?,
        val errorMsg: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val _state = MutableStateFlow(Status())
    val state: StateFlow<Status> = _state.asStateFlow()

    private val _logs = MutableSharedFlow<ServerLog>(extraBufferCapacity = 64)

    /** EasyTier 运行日志（ServerManager 汇入主页日志流）。 */
    val logs: SharedFlow<ServerLog> = _logs.asSharedFlow()

    @Volatile
    private var instanceStarted = false

    /** 当前已下发转发规则的绑定地址（uint32 大端），null 表示尚未下发。 */
    @Volatile
    private var forwardedAddr: Long? = null

    @Volatile
    private var monitorJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

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
            val effectiveNetwork = networkName.ifBlank { EasyTierSpec.DEFAULT_NETWORK_NAME }
            val toml = EasyTierSpec.buildToml(networkName, networkSecret, peerUri)

            // 密钥不落日志
            log(
                LoggableLevel.INFO,
                "启动内网映射：network=$effectiveNetwork, peer=${if (peerUri.isBlank()) "（未配置）" else peerUri}, " +
                    "端口 ${EasyTierSpec.PORT} 将在 DHCP 分配虚拟 IP 后动态映射",
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

    /** collectNetworkInfos → 刷新状态；拿到 DHCP 虚拟 IP 后确保端口转发规则已下发。 */
    private fun pollStatus() {
        val jsonText = runCatching { EasyTierJNI.collectNetworkInfos(COLLECT_MAX) }.getOrElse { e ->
            log(LoggableLevel.WARN, "collectNetworkInfos 调用失败：${e.message}")
            return
        }
        if (jsonText.isNullOrBlank()) return

        val info = runCatching { parseInstanceInfo(jsonText) }.getOrElse { null }
        if (info == null) {
            // map 中没有本实例：可能已被核心侧清理；保持 RUNNING 等待自愈，仅降级提示
            if (_state.value.phase == Phase.RUNNING) {
                transition(Status(Phase.RUNNING, detail = "实例信息暂缺"))
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

        // DHCP 虚拟 IP 已分配：确保转发规则与当前 IP 一致（首次 ADD，变更则 REMOVE+ADD）
        val addr = info.ipv4Addr
        if (addr != null && forwardedAddr != addr) {
            if (!applyPortForward(forwardedAddr, addr, info.ipv4)) return
        }

        val next = Status(
            Phase.RUNNING,
            virtualIpv4 = info.ipv4,
            portMapped = forwardedAddr != null && forwardedAddr == addr,
        )
        if (next != _state.value) transition(next)
    }

    /** 通过 ConfigRpc.PatchConfig 下发端口转发规则；成功返回 true，失败保持现状等待下轮重试。 */
    private fun applyPortForward(removeAddr: Long?, addAddr: Long, ipv4: String?): Boolean {
        val payload = EasyTierSpec.buildPortForwardPatchJson(removeAddr, addAddr)
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
        forwardedAddr = addAddr
        log(
            LoggableLevel.INFO,
            "端口转发已生效：$ipLabel:${EasyTierSpec.PORT} -> 127.0.0.1:${EasyTierSpec.PORT} (tcp)",
        )
        return true
    }

    /** 提取实例运行信息；map 中未找到本实例返回 null。 */
    internal fun parseInstanceInfo(jsonText: String): InstanceInfo? {
        val root = json.parseToJsonElement(jsonText) as? JsonObject ?: return null
        val map = objOrCamel(root, "map") ?: return null
        val entry = objOrCamel(map, EasyTierSpec.INSTANCE_NAME) ?: return null

        val running = (entry["running"] as? JsonPrimitive)?.booleanOrNull ?: false
        val errorMsg = (entry["error_msg"] as? JsonPrimitive)?.content.orEmpty()

        var ipv4: String? = null
        var addr: Long? = null
        val nodeInfo = objOrCamel(entry, "my_node_info")
        val v4 = nodeInfo?.let { objOrCamel(it, "virtual_ipv4") }
        if (v4 != null) {
            val raw = objOrCamel(v4, "address")?.let { (it["addr"] as? JsonPrimitive)?.longOrNull }
            if (raw != null) {
                addr = raw
                ipv4 = EasyTierSpec.formatIpv4(raw)
            }
        }
        return InstanceInfo(running, ipv4, addr, errorMsg)
    }

    /** pbjson 配置了 preserve_proto_field_names，这里仍做 camelCase 兜底。 */
    private fun objOrCamel(obj: JsonObject, snakeKey: String): JsonObject? {
        val camel = snakeKey.split('_').mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
        return (obj[snakeKey] ?: obj[camel]) as? JsonObject
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
