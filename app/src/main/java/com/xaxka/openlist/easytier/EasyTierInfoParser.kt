package com.xaxka.openlist.easytier

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * collectNetworkInfos 返回的本实例运行信息快照。
 *
 * @param running 实例是否处于运行状态
 * @param ipv4 DHCP 分配的虚拟 IPv4（点分十进制；未分配为 null）
 * @param ipv4Addr 虚拟 IPv4 的 uint32 大端表示（未分配为 null）
 * @param peerCount 当前已连接的对等节点数量（未连接/未组网为 0）
 * @param errorMsg 最近一次错误信息（无错误为空串）
 * @param myNode 本节点信息（peer_id / 主机名 / 版本 / 监听器 / NAT；未就绪为 null）
 * @param peers 已连接对等节点明细（含各连接的隧道/延迟/丢包）
 * @param routes 虚拟网络路由表（到各节点的路径/跳数/延迟）
 * @param events 实例事件日志（核心侧文本，按发生顺序）
 */
data class InstanceInfo(
    val running: Boolean,
    val ipv4: String?,
    val ipv4Addr: Long?,
    val peerCount: Int,
    val errorMsg: String,
    val myNode: MyNodeInfo? = null,
    val peers: List<PeerDetail> = emptyList(),
    val routes: List<RouteDetail> = emptyList(),
    val events: List<String> = emptyList(),
)

/** 本节点信息（MyNodeInfo）。 */
data class MyNodeInfo(
    val peerId: Long,
    val hostname: String,
    val version: String,
    val virtualIpv4: String?,
    val listeners: List<String>,
    val stun: StunInfo?,
)

/** STUN / NAT 探测摘要。 */
data class StunInfo(
    val udpNatType: String,
    val tcpNatType: String,
    val publicIps: List<String>,
)

/** 单个对等节点及其连接明细。 */
data class PeerDetail(
    val peerId: Long,
    val conns: List<PeerConn>,
)

/** 对等节点的一条连接（隧道）信息。 */
data class PeerConn(
    val tunnelType: String,
    val remoteAddr: String?,
    val lossRate: Float,
    val latencyMs: Long,
    val isClient: Boolean,
)

/** 虚拟网络路由表条目（到某一节点）。 */
data class RouteDetail(
    val peerId: Long,
    val ipv4: String?,
    val hostname: String,
    val nextHopPeerId: Long,
    val cost: Int,
    val pathLatencyMs: Int,
    val version: String,
)

/**
 * 解析 collectNetworkInfos 返回的 JSON。
 *
 * 结构与字段名对照 EasyTier（easytier-proto，pbjson preserve_proto_field_names，snake_case）：
 * - 顶层 NetworkInstanceRunningInfoMap { map<instance_name, NetworkInstanceRunningInfo> }
 * - NetworkInstanceRunningInfo { running, error_msg, my_node_info, peers, routes, events, ... }
 * - MyNodeInfo.virtual_ipv4 = Ipv4Inet { address: Ipv4Addr { addr: uint32 大端 } }
 *
 * pbjson 按 proto 字段名输出（snake_case），这里仍做 camelCase 兜底。
 */
internal object EasyTierInfoParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** listInstances 结果（`{"<实例名>":"<uuid>"}`）中是否包含指定实例。 */
    fun containsInstance(jsonText: String, instanceName: String = EasyTierSpec.INSTANCE_NAME): Boolean {
        val root = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() as? JsonObject
            ?: return false
        return root.containsKey(instanceName)
    }

    /** 提取实例运行信息；JSON 非法或 map 中未找到本实例返回 null。 */
    fun parse(jsonText: String, instanceName: String = EasyTierSpec.INSTANCE_NAME): InstanceInfo? {
        val root = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() as? JsonObject
            ?: return null
        val map = objOrCamel(root, "map") ?: return null
        val entry = objOrCamel(map, instanceName) ?: return null

        val running = (entry["running"] as? JsonPrimitive)?.booleanOrNull ?: false
        val errorMsg = (entry["error_msg"] as? JsonPrimitive)?.content.orEmpty()
        val peersArray = (entry["peers"] as? JsonArray)
        val peerCount = peersArray?.size ?: 0

        var ipv4: String? = null
        var addr: Long? = null
        val nodeInfo = objOrCamel(entry, "my_node_info")
        val v4 = nodeInfo?.let { objOrCamel(it, "virtual_ipv4") }
        if (v4 != null) {
            val raw = objOrCamel(v4, "address")?.let { longOf(it, "addr") }
            if (raw != null) {
                addr = raw
                ipv4 = EasyTierSpec.formatIpv4(raw)
            }
        }

        return InstanceInfo(
            running = running,
            ipv4 = ipv4,
            ipv4Addr = addr,
            peerCount = peerCount,
            errorMsg = errorMsg,
            myNode = nodeInfo?.let(::parseMyNode),
            peers = peersArray?.mapNotNull { (it as? JsonObject)?.let(::parsePeer) } ?: emptyList(),
            routes = (entry["routes"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(::parseRoute) } ?: emptyList(),
            events = (entry["events"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
        )
    }

    /**
     * 解析 ListPortForward 响应（PortForwardManageRpcService.ListPortForward）：
     * `{"cfgs":[{bind_addr,dst_addr,socket_type}]}` → 绑定的端口列表。
     *
     * 仅保留「绑定在指定虚拟 IP、且转发到回环同端口」的规则（即本组件下发的规则），
     * 避免把用户/核心侧的其他转发计入对账。解析失败返回 null（调用方按读取失败处理）。
     */
    fun parseForwardedPorts(
        jsonText: String,
        bindAddr: Long,
        loopback: Long = EasyTierSpec.LOOPBACK_ADDR,
    ): List<Int>? {
        val root = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() as? JsonObject
            ?: return null
        val cfgs = (root["cfgs"] as? JsonArray) ?: return emptyList()
        val ports = mutableListOf<Int>()
        for (cfg in cfgs) {
            val obj = cfg as? JsonObject ?: continue
            val bind = objOrCamel(obj, "bind_addr") ?: continue
            val dst = objOrCamel(obj, "dst_addr") ?: continue
            val bindIp = objOrCamel(bind, "ipv4")?.let { longOf(it, "addr") } ?: continue
            val bindPort = (bind["port"] as? JsonPrimitive)?.intOrNull ?: continue
            val dstIp = objOrCamel(dst, "ipv4")?.let { longOf(it, "addr") } ?: continue
            val dstPort = (dst["port"] as? JsonPrimitive)?.intOrNull ?: continue
            if (bindIp == bindAddr && dstIp == loopback && bindPort == dstPort) {
                ports.add(bindPort)
            }
        }
        return ports.distinct().sorted()
    }

    private fun parseMyNode(node: JsonObject): MyNodeInfo {
        val v4 = objOrCamel(node, "virtual_ipv4")
            ?.let { objOrCamel(it, "address") }
            ?.let { longOf(it, "addr") }
            ?.let(EasyTierSpec::formatIpv4)
        val stun = objOrCamel(node, "stun_info")?.let {
            StunInfo(
                udpNatType = strOf(it, "udp_nat_type"),
                tcpNatType = strOf(it, "tcp_nat_type"),
                publicIps = (it["public_ip"] as? JsonArray)
                    ?.mapNotNull { p -> (p as? JsonPrimitive)?.content } ?: emptyList(),
            )
        }
        return MyNodeInfo(
            peerId = longOf(node, "peer_id") ?: 0L,
            hostname = strOf(node, "hostname"),
            version = strOf(node, "version"),
            virtualIpv4 = v4,
            listeners = (node["listeners"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let { u -> strOf(u, "url") } }
                ?.filter { it.isNotEmpty() } ?: emptyList(),
            stun = stun,
        )
    }

    private fun parsePeer(peer: JsonObject): PeerDetail {
        val conns = (peer["conns"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseConn) }
            ?: emptyList()
        return PeerDetail(peerId = longOf(peer, "peer_id") ?: 0L, conns = conns)
    }

    private fun parseConn(conn: JsonObject): PeerConn {
        val tunnel = objOrCamel(conn, "tunnel")
        val remoteUrl = tunnel?.let { objOrCamel(it, "remote_addr") }?.let { strOf(it, "url") }
        val latencyUs = objOrCamel(conn, "stats")?.let { longOf(it, "latency_us") } ?: 0L
        return PeerConn(
            tunnelType = tunnel?.let { strOf(it, "tunnel_type") }.orEmpty(),
            remoteAddr = remoteUrl,
            lossRate = primOf(conn, "loss_rate")?.floatOrNull ?: 0f,
            latencyMs = latencyUs / 1000,
            isClient = primOf(conn, "is_client")?.booleanOrNull ?: false,
        )
    }

    private fun parseRoute(route: JsonObject): RouteDetail {
        val v4 = objOrCamel(route, "ipv4_addr")
            ?.let { objOrCamel(it, "address") }
            ?.let { longOf(it, "addr") }
            ?.let(EasyTierSpec::formatIpv4)
        return RouteDetail(
            peerId = longOf(route, "peer_id") ?: 0L,
            ipv4 = v4,
            hostname = strOf(route, "hostname"),
            nextHopPeerId = longOf(route, "next_hop_peer_id") ?: 0L,
            cost = (route["cost"] as? JsonPrimitive)?.intOrNull ?: 0,
            pathLatencyMs = (route["path_latency"] as? JsonPrimitive)?.intOrNull ?: 0,
            version = strOf(route, "version"),
        )
    }

    private fun primOf(obj: JsonObject, snakeKey: String): JsonPrimitive? {
        val camel = snakeKey.split('_').mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
        return (obj[snakeKey] ?: obj[camel]) as? JsonPrimitive
    }

    private fun strOf(obj: JsonObject, key: String): String =
        primOf(obj, key)?.content.orEmpty()

    private fun longOf(obj: JsonObject, key: String): Long? =
        primOf(obj, key)?.longOrNull

    private fun objOrCamel(obj: JsonObject, snakeKey: String): JsonObject? {
        val camel = snakeKey.split('_').mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
        return (obj[snakeKey] ?: obj[camel]) as? JsonObject
    }
}
