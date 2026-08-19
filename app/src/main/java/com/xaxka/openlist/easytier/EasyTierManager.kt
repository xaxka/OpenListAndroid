package com.xaxka.openlist.easytier

import com.easytier.jni.EasyTierJNI
import com.xaxka.openlist.data.log.LogBuffer
import com.xaxka.openlist.data.log.LoggableLevel
import com.xaxka.openlist.data.log.ServerLog
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

/**
 * EasyTier 内网映射引擎（no-tun，无 Android VPN 服务）。
 *
 * 生命周期由 [com.xaxka.openlist.service.ServerManager] 驱动：
 * OpenList 服务 RUNNING 时按偏好 [startIfEnabled]，STOPPING/STOPPED 时 [stop]。
 *
 * 数据面：EasyTier 核心以原生线程驻留进程内，通过 JNI 调用；
 * 状态轮询 collectNetworkInfos（NetworkInstanceRunningInfoMap proto3 JSON）。
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

    /** UI 快照：phase + 已分得的虚拟 IPv4（可空）+ 说明文本（错误信息/等待原因等）。 */
    data class Status(
        val phase: Phase = Phase.STOPPED,
        val virtualIpv4: String? = null,
        val detail: String = "",
    ) {
        val summary: String
            get() = when (phase) {
                Phase.STOPPED -> "未启动".appendDetail(detail)
                Phase.STARTING -> "启动中".appendDetail(detail)
                Phase.RUNNING -> if (virtualIpv4 != null) "运行中 · $virtualIpv4" else "运行中 · 等待分配虚拟 IP"
                Phase.STOPPING -> "停止中"
                Phase.ERROR -> "错误".appendDetail(detail)
                Phase.UNAVAILABLE -> "不可用".appendDetail(detail)
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
                    "port_forward=${EasyTierSpec.PORT_FORWARD_BIND} -> ${EasyTierSpec.PORT_FORWARD_DST}",
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

    /** collectNetworkInfos → 解析 running / error_msg / 虚拟 IPv4，刷新状态。 */
    private fun pollStatus() {
        val jsonText = runCatching { EasyTierJNI.collectNetworkInfos(COLLECT_MAX) }.getOrElse { e ->
            log(LoggableLevel.WARN, "collectNetworkInfos 调用失败：${e.message}")
            return
        }
        if (jsonText.isNullOrBlank()) return

        val info = runCatching { parseInstanceInfo(jsonText) }.getOrNull() ?: return
        if (info == null) {
            // map 中没有本实例：可能已被核心侧清理；保持 RUNNING 等待自愈，仅降级提示
            if (_state.value.phase == Phase.RUNNING) {
                transition(Status(Phase.RUNNING, detail = "实例信息暂缺"))
            }
            return
        }

        val (running, ipv4, errorMsg) = info
        if (!running) {
            val msg = errorMsg.ifBlank { "实例未运行" }
            if (_state.value.phase != Phase.ERROR || _state.value.detail != msg) {
                transition(Status(Phase.ERROR, detail = msg))
                log(LoggableLevel.ERROR, "EasyTier 实例异常：$msg")
            }
            return
        }
        val next = Status(Phase.RUNNING, virtualIpv4 = ipv4)
        if (next != _state.value) {
            if (ipv4 != null && ipv4 != _state.value.virtualIpv4) {
                log(LoggableLevel.INFO, "EasyTier 虚拟 IP：$ipv4（端口 ${EasyTierSpec.PORT_FORWARD_BIND} 已映射）")
            }
            transition(next)
        }
    }

    /** 提取实例运行信息：Triple(running, ipv4?, errorMsg)。未找到实例返回 null。 */
    internal fun parseInstanceInfo(jsonText: String): Triple<Boolean, String?, String>? {
        val root = json.parseToJsonElement(jsonText) as? JsonObject ?: return null
        val map = objOrCamel(root, "map") ?: return null
        val entry = objOrCamel(map, EasyTierSpec.INSTANCE_NAME) ?: return null

        val running = (entry["running"] as? JsonPrimitive)?.booleanOrNull ?: false
        val errorMsg = (entry["error_msg"] as? JsonPrimitive)?.content.orEmpty()

        var ipv4: String? = null
        val nodeInfo = objOrCamel(entry, "my_node_info")
        val v4 = nodeInfo?.let { objOrCamel(it, "virtual_ipv4") }
        if (v4 != null) {
            val addr = objOrCamel(v4, "address")?.let { (it["addr"] as? JsonPrimitive)?.longOrNull }
            if (addr != null) ipv4 = formatIpv4(addr)
        }
        return Triple(running, ipv4, errorMsg)
    }

    /** pbjson 配置了 preserve_proto_field_names，这里仍做 camelCase 兜底。 */
    private fun objOrCamel(obj: JsonObject, snakeKey: String): JsonObject? {
        val camel = snakeKey.split('_').mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
        return (obj[snakeKey] ?: obj[camel]) as? JsonObject
    }

    /** Ipv4Addr.addr（uint32 大端）→ 点分十进制。 */
    internal fun formatIpv4(addr: Long): String {
        val a = (addr shr 24) and 0xFF
        val b = (addr shr 16) and 0xFF
        val c = (addr shr 8) and 0xFF
        val d = addr and 0xFF
        return "$a.$b.$c.$d"
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
