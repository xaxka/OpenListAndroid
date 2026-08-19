package com.xaxka.openlist.easytier

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * collectNetworkInfos 返回的本实例运行信息快照。
 *
 * @param running 实例是否处于运行状态
 * @param ipv4 DHCP 分配的虚拟 IPv4（点分十进制；未分配为 null）
 * @param ipv4Addr 虚拟 IPv4 的 uint32 大端表示（未分配为 null）
 * @param peerCount 当前已连接的对等节点数量（未连接/未组网为 0）
 * @param errorMsg 最近一次错误信息（无错误为空串）
 */
internal data class InstanceInfo(
    val running: Boolean,
    val ipv4: String?,
    val ipv4Addr: Long?,
    val peerCount: Int,
    val errorMsg: String,
)

/**
 * 解析 collectNetworkInfos 返回的 JSON。
 *
 * 结构与字段名对照 EasyTier（easytier-proto，pbjson preserve_proto_field_names）：
 * - 顶层 NetworkInstanceRunningInfoMap { map<intance_name, NetworkInstanceRunningInfo> }
 * - NetworkInstanceRunningInfo { running, error_msg, my_node_info, peers, ... }
 * - MyNodeInfo.virtual_ipv4 = Ipv4Inet { address: Ipv4Addr { addr: uint32 大端 } }
 *
 * pbjson 按 proto 字段名输出（snake_case），这里仍做 camelCase 兜底。
 */
internal object EasyTierInfoParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 提取实例运行信息；JSON 非法或 map 中未找到本实例返回 null。 */
    fun parse(jsonText: String, instanceName: String = EasyTierSpec.INSTANCE_NAME): InstanceInfo? {
        val root = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() as? JsonObject
            ?: return null
        val map = objOrCamel(root, "map") ?: return null
        val entry = objOrCamel(map, instanceName) ?: return null

        val running = (entry["running"] as? JsonPrimitive)?.booleanOrNull ?: false
        val errorMsg = (entry["error_msg"] as? JsonPrimitive)?.content.orEmpty()
        val peerCount = (entry["peers"] as? JsonArray)?.size ?: 0

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
        return InstanceInfo(running, ipv4, addr, peerCount, errorMsg)
    }

    private fun objOrCamel(obj: JsonObject, snakeKey: String): JsonObject? {
        val camel = snakeKey.split('_').mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
        return (obj[snakeKey] ?: obj[camel]) as? JsonObject
    }
}
