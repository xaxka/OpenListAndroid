package com.xaxka.openlist.net

import android.os.Process
import android.os.SystemClock
import android.net.TrafficStats
import com.xaxka.openlist.service.ServerManager
import com.xaxka.openlist.service.ServerState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用流量统计（设置页「流量统计」数据源）。
 *
 * 背景与口径：OpenList Go 内核经 gomobile 运行于本应用进程内，alist-lib 绑定层
 * 仅暴露 init/start/shutdown/isRunning/setAdminPassword/getOutboundIP，无流量接口；
 * 精确到 OpenList 单服务的字节数必须修改 openlist 内核源码（已明确排除）。
 * 不改内核的可行方案是 UID / 进程级计数：统计本应用进程全部物理网络收发
 * （OpenList 服务流量 + 内网映射 EasyTier + 应用自身请求；
 * 127.0.0.1 回环的 WebView WebUI 流量不计入）。
 *
 * 双引擎采样（适配全部 Android 设备，含部分厂商 ROM TrafficStats 返回 UNSUPPORTED）：
 * - 首选 [TrafficStats] 的 UID 级计数（getUidRxBytes/TxBytes，对本进程 UID 免权限）；
 * - TrafficStats 返回负数（设备不支持/厂商阉割）时，回退读取 /proc/<pid>/net/dev
 *   （本进程网络命名空间网卡计数，procfs 免 root、全设备通用），作为进程流量近似值。
 *
 * 采样策略：
 * - 会话边界：常驻订阅 [ServerManager.state]，进入 RUNNING 记基线，STOPPED 冻结终值；
 * - 计数与速率：仅 UI 订阅（stateIn WhileSubscribed，设置页可见）时每
 *   [POLL_INTERVAL_MS] 采样一次，相邻差分得实时速率，页面离开即停，不耗电；
 * - UID/网卡计数器为内核自开机累计值，设备重启清零；两引擎均不可用时回退
 *   不可用态（supported=false）。
 */
@Singleton
class TrafficStatsMonitor @Inject constructor(
    serverManager: ServerManager,
) {
    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val PROC_SELF_NET_DEV = "/proc/self/net/dev"
    }

    /** 单次计数快照（rx/tx 为自设备开机的累计值，负数 = 当前引擎不可用）。 */
    private data class Counter(val rx: Long, val tx: Long)

    /** OpenList 服务会话流量：active=true 运行中（随轮询增长），false 为停止时冻结的终值。 */
    data class Session(
        val active: Boolean,
        /** 服务本次进入 RUNNING 的时刻（epoch millis，冻结时保留）。 */
        val startedAt: Long,
        val rx: Long,
        val tx: Long,
    )

    /** UI 快照：supported=false 时其余字段无意义。 */
    data class TrafficState(
        val supported: Boolean = true,
        /** true = TrafficStats UID 级；false = /proc 回退（网卡级近似）。 */
        val uidLevel: Boolean = true,
        /** 设备启动以来的累计收发（UID 级或网卡级近似）。 */
        val bootRx: Long = 0,
        val bootTx: Long = 0,
        val session: Session? = null,
        val rxBps: Long = 0,
        val txBps: Long = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 服务进入 RUNNING 时的计数基线（null = 本进程内服务尚未启动过）。 */
    @Volatile
    private var sessionBase: Counter? = null

    /** 服务本次进入 RUNNING 的时刻（epoch millis）。 */
    @Volatile
    private var sessionStartedAt = 0L

    /** 服务停止时冻结的会话终值（null = 运行中或本进程内从未启动过）。 */
    @Volatile
    private var sessionFinal: Session? = null

    /** 首帧快照（设置页打开即有累计值，不闪 0）。 */
    private val initial: TrafficState = buildState(snapshot(), null, 0)

    /** 设置页直接订阅的单一状态流：可见时轮询，不可见 10s 后停。 */
    val state: StateFlow<TrafficState> = flow {
        var prev: Counter? = null
        var prevAt = 0L
        while (true) {
            val cur = snapshot()
            val now = SystemClock.elapsedRealtime()
            emit(buildState(cur, prev, prevAt))
            prev = cur
            prevAt = now
            delay(POLL_INTERVAL_MS)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(10_000), initial)

    init {
        // 常驻订阅服务状态（单例 scope）：无 UI 时也持续记录会话边界，
        // 保证「打开设置页即看到正确会话流量」而不是从打开时刻起算。
        scope.launch {
            serverManager.state.collect { st ->
                when (st) {
                    ServerState.RUNNING -> {
                        sessionBase = snapshot()
                        sessionStartedAt = System.currentTimeMillis()
                        sessionFinal = null
                    }
                    ServerState.STOPPED -> freezeSession()
                    else -> Unit
                }
            }
        }
    }

    /** 服务停止：以当前计数冻结会话终值（基线缺失/设备不支持则不冻结）。 */
    private fun freezeSession() {
        val base = sessionBase ?: return
        val cur = snapshot()
        if (cur.rx < 0 || cur.tx < 0) return
        sessionFinal = Session(
            active = false,
            startedAt = sessionStartedAt,
            rx = (cur.rx - base.rx).coerceAtLeast(0),
            tx = (cur.tx - base.tx).coerceAtLeast(0),
        )
    }

    /**
     * 采样当前累计收发字节。
     *
     * 首选 TrafficStats UID 级计数（免权限）；返回 UNSUPPORTED（负数）时回退
     * /proc/self/net/dev（本进程网络命名空间物理网卡累计），仍失败返回负数。
     */
    private fun snapshot(): Counter {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx >= 0 && tx >= 0) return Counter(rx, tx)
        // TrafficStats 不可用（部分厂商 ROM 阉割）：回退 procfs 进程网卡计数
        return runCatching { readProcNetDev(File(PROC_SELF_NET_DEV)) }
            .getOrElse { Counter(-1, -1) }
    }

    /**
     * 解析 /proc/<pid>/net/dev 文本，累加全部物理网卡接口的 rx/tx 字节数。
     *
     * 过滤 lo（回环，不代表物理网络流量）与常见虚拟接口（tun/dummy/ifb 等）；
     * 网卡名特征：wlan*、eth*、rmnet_data*、ccmni*、cciwlan*、wwan*、pdp*、ppp* 等。
     * 任一接口行解析失败跳过该接口；无任何接口匹配返回负数表示不可用。
     */
    private fun readProcNetDev(file: File): Counter {
        var totalRx = 0L
        var totalTx = 0L
        var anyMatched = false

        file.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx > 0) {
                    val name = trimmed.substring(0, colonIdx).trim()
                    if (isPhysicalInterface(name)) {
                        val fields = trimmed.substring(colonIdx + 1).trim().split(Regex("\\s+"))
                        // fields[0] = rx_bytes, fields[8] = tx_bytes（/proc/net/dev 标准 16 列）
                        if (fields.size >= 9) {
                            val rx = fields[0].toLongOrNull()
                            val tx = fields[8].toLongOrNull()
                            if (rx != null && tx != null) {
                                totalRx += rx
                                totalTx += tx
                                anyMatched = true
                            }
                        }
                    }
                }
                line = reader.readLine()
            }
        }

        return if (anyMatched) Counter(totalRx, totalTx) else Counter(-1, -1)
    }

    /** 判断接口名是否为物理网络接口（排除回环 lo 与 VPN/隧道虚拟接口）。 */
    private fun isPhysicalInterface(name: String): Boolean {
        if (name.isEmpty()) return false
        // 回环与常见虚拟接口
        if (name == "lo") return false
        if (name.startsWith("tun") || name.startsWith("dummy") ||
            name.startsWith("ifb") || name.startsWith("sit") ||
            name.startsWith("ip6tnl") || name.startsWith("gre") ||
            name.startsWith("tunl") || name.startsWith("veth")) return false
        // 已知物理网卡前缀（Wi-Fi / 蜂窝 / 以太 / USB 网络）
        return name.startsWith("wlan") || name.startsWith("eth") ||
               name.startsWith("rmnet_data") || name.startsWith("rmnet") ||
               name.startsWith("ccmni") || name.startsWith("cciwlan") ||
               name.startsWith("wwan") || name.startsWith("pdp") ||
               name.startsWith("ppp") || name.startsWith("usb") ||
               name.startsWith("ncm") || name.startsWith("qmi") ||
               name.startsWith("ipa") || name.startsWith("slip") ||
               name.startsWith("bnep")
    }

    private fun buildState(cur: Counter, prev: Counter?, prevAt: Long): TrafficState {
        if (cur.rx < 0 || cur.tx < 0) {
            return TrafficState(supported = false)
        }
        // 实时速率：相邻采样差分（无上帧/计数回绕清零/时间倒退时为 0）
        var rxBps = 0L
        var txBps = 0L
        if (prev != null && prev.rx >= 0 && prev.tx >= 0 && prevAt > 0) {
            val dtMs = SystemClock.elapsedRealtime() - prevAt
            if (dtMs > 0) {
                rxBps = ((cur.rx - prev.rx).coerceAtLeast(0) * 1000) / dtMs
                txBps = ((cur.tx - prev.tx).coerceAtLeast(0) * 1000) / dtMs
            }
        }
        // 判定当前快照来源：TrafficStats 是否可用
        val uid = Process.myUid()
        val uidLevel = TrafficStats.getUidRxBytes(uid) >= 0 && TrafficStats.getUidTxBytes(uid) >= 0
        return TrafficState(
            supported = true,
            uidLevel = uidLevel,
            bootRx = cur.rx,
            bootTx = cur.tx,
            session = liveSession(cur) ?: sessionFinal,
            rxBps = rxBps,
            txBps = txBps,
        )
    }

    /** 服务运行中的实时会话（基线缺失/设备不支持返回 null，回退 sessionFinal）。 */
    private fun liveSession(cur: Counter): Session? {
        val base = sessionBase ?: return null
        if (sessionFinal != null || cur.rx < 0) return null
        return Session(
            active = true,
            startedAt = sessionStartedAt,
            rx = (cur.rx - base.rx).coerceAtLeast(0),
            tx = (cur.tx - base.tx).coerceAtLeast(0),
        )
    }
}
