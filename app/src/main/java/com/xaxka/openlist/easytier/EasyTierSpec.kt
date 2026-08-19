package com.xaxka.openlist.easytier

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * EasyTier 实例固定规格 + TOML 配置模板 + 端口转发 RPC 载荷。
 *
 * 目标：把本机 OpenList 的 5244 端口映射进 EasyTier 虚拟局域网（no-tun 模式，不使用
 * Android VPN 服务）。
 *
 * 端口转发采用「动态绑定」策略：启动 TOML 不携带 [[port_forward]]（因为 ipv4=DHCP，
 * 启动瞬间虚拟 IP 尚未分配，写死 bind_addr 会绑定失败）；实例连上网络并拿到 DHCP
 * 分配的虚拟 IP 后，通过 ConfigRpc.PatchConfig 动态添加转发规则：
 * <虚拟IP>:5244 -> 127.0.0.1:5244(tcp)。
 *
 * TOML 字段对照 easytier-core/src/config/toml.rs（Config 结构）：
 * - 顶层 instance_name / hostname / dhcp
 * - [network_identity] network_name / network_secret
 * - [[peer]] uri（多条可重复，本模板仅暴露一条）
 * - [flags] no_tun（位于 FlagsInConfig，非顶层字段）
 */
object EasyTierSpec {

    /** 实例名与主机名（collectNetworkInfos 返回 map 的 key 即 instance_name）。 */
    const val INSTANCE_NAME = "openlist"
    const val HOSTNAME = "openlist"

    /** 通过 DHCP 向 EasyTier 网络申请虚拟 IPv4（不写静态 ipv4）。 */
    const val DHCP = true

    /** no-tun：不创建 TUN 设备、不使用 VpnService，端口转发走核心内部的代理通道。 */
    const val NO_TUN = true

    /** 对外映射端口与目标（本机 OpenList 回环地址）。 */
    const val PORT = 5244
    const val LOOPBACK_ADDR = 2130706433L /* 127.0.0.1 */

    /** 网络名为空时回退 EasyTier 默认网络。 */
    const val DEFAULT_NETWORK_NAME = "default"

    /** PatchConfig RPC 坐标（easytier-core instance_rpc 分发名与方法名）。 */
    const val CONFIG_RPC_SERVICE = "api.config.ConfigRpcService"
    const val PATCH_CONFIG_METHOD = "PatchConfig"

    /**
     * 生成启动 TOML（不含端口转发，转发在拿到 DHCP 虚拟 IP 后经 RPC 追加）。
     *
     * @param networkName 网络名称（空白回退 "default"）
     * @param networkSecret 网络密钥（允许空字符串）
     * @param peerUri 对等节点 URI；空白则不生成 [[peer]]（单机直连场景也合法）
     */
    fun buildToml(networkName: String, networkSecret: String, peerUri: String): String {
        val effectiveNetwork = networkName.ifBlank { DEFAULT_NETWORK_NAME }
        val sb = StringBuilder()
        sb.append("instance_name = ").append(tomlString(INSTANCE_NAME)).append('\n')
        sb.append("hostname = ").append(tomlString(HOSTNAME)).append('\n')
        sb.append("dhcp = ").append(DHCP).append("\n\n")

        sb.append("[network_identity]\n")
        sb.append("network_name = ").append(tomlString(effectiveNetwork)).append('\n')
        sb.append("network_secret = ").append(tomlString(networkSecret)).append("\n\n")

        sb.append("[flags]\n")
        sb.append("no_tun = ").append(NO_TUN).append("\n\n")

        val peer = peerUri.trim()
        if (peer.isNotEmpty()) {
            sb.append("[[peer]]\n")
            sb.append("uri = ").append(tomlString(peer)).append("\n\n")
        }
        return sb.toString()
    }

    /**
     * 生成 ConfigRpc.PatchConfig 的 proto3 JSON 载荷：
     * ADD <addAddr>:5244 -> 127.0.0.1:5244(tcp)；若 [removeAddr] 非空则先 REMOVE 旧规则
     * （虚拟 IP 变化时替换绑定）。
     *
     * @param removeAddr 需要移除的旧绑定地址（uint32 大端），null 表示不移除
     * @param addAddr 新绑定地址（DHCP 分到的虚拟 IP，uint32 大端）
     */
    fun buildPortForwardPatchJson(removeAddr: Long?, addAddr: Long): String {
        val payload = buildJsonObject {
            putJsonObject("instance") {
                putJsonObject("instance_selector") {
                    put("name", INSTANCE_NAME)
                }
            }
            putJsonObject("patch") {
                putJsonArray("port_forwards") {
                    if (removeAddr != null) {
                        add(portForwardPatch(action = "REMOVE", bindAddr = removeAddr))
                    }
                    add(portForwardPatch(action = "ADD", bindAddr = addAddr))
                }
            }
        }
        return payload.toString()
    }

    /** 单条 PortForwardPatch：action + cfg(SocketAddr×2 + SocketType)。 */
    private fun portForwardPatch(action: String, bindAddr: Long): JsonObject =
        buildJsonObject {
            put("action", action)
            putJsonObject("cfg") {
                putJsonObject("bind_addr") {
                    putJsonObject("ipv4") { put("addr", bindAddr) }
                    put("port", PORT)
                }
                putJsonObject("dst_addr") {
                    putJsonObject("ipv4") { put("addr", LOOPBACK_ADDR) }
                    put("port", PORT)
                }
                put("socket_type", "TCP")
            }
        }

    /** Ipv4Addr.addr（uint32 大端）→ 点分十进制。 */
    fun formatIpv4(addr: Long): String {
        val a = (addr shr 24) and 0xFF
        val b = (addr shr 16) and 0xFF
        val c = (addr shr 8) and 0xFF
        val d = addr and 0xFF
        return "$a.$b.$c.$d"
    }

    /** TOML basic-string 转义：反斜杠、双引号与控制字符。 */
    internal fun tomlString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20 || ch.code == 0x7F) {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
