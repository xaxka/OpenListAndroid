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
 * 目标：把本机若干端口映射进 EasyTier 虚拟局域网（no-tun 模式，不使用 Android VPN 服务）。
 *
 * 端口转发采用「动态绑定」策略：启动 TOML 不携带 [[port_forward]]（因为 ipv4=DHCP，
 * 启动瞬间虚拟 IP 尚未分配，写死 bind_addr 会绑定失败）；实例连上网络并拿到 DHCP
 * 分配的虚拟 IP 后，通过 ConfigRpc.PatchConfig 动态添加转发规则：
 * <虚拟IP>:<端口> -> 127.0.0.1:<同端口>(tcp)，支持多端口同时映射。
 *
 * TOML 字段对照 easytier-core/src/config/toml.rs（Config 结构）：
 * - 顶层 instance_name / hostname / dhcp
 * - [network_identity] network_name / network_secret
 * - [[peer]] uri（多条可重复，本模板仅暴露一条）
 * - [flags] no_tun / enable_quic_proxy（均位于 FlagsInConfig，非顶层字段）
 */
object EasyTierSpec {

    /** 实例名与主机名（collectNetworkInfos 返回 map 的 key 即 instance_name）。 */
    const val INSTANCE_NAME = "openlist"
    const val HOSTNAME = "openlist"

    /** 通过 DHCP 向 EasyTier 网络申请虚拟 IPv4（不写静态 ipv4）。 */
    const val DHCP = true

    /** no-tun：不创建 TUN 设备、不使用 VpnService，端口转发走核心内部的代理通道。 */
    const val NO_TUN = true

    /** 默认映射端口（OpenList）；端口列表为空时回退仅映射它。 */
    const val PRIMARY_PORT = 5244
    const val DEFAULT_PORTS = "5244"

    /** 转发目标：本机回环地址（OpenList 及同机服务）。 */
    const val LOOPBACK_ADDR = 2130706433L /* 127.0.0.1 */

    /** 网络名为空时回退 EasyTier 默认网络。 */
    const val DEFAULT_NETWORK_NAME = "default"

    /** PatchConfig RPC 坐标（easytier-core instance_rpc 分发名与方法名）。 */
    const val CONFIG_RPC_SERVICE = "api.config.ConfigRpcService"
    const val PATCH_CONFIG_METHOD = "PatchConfig"

    /** ListPortForward RPC 坐标：读取实例当前实际生效的端口转发规则（对账用）。 */
    const val PORT_FORWARD_RPC_SERVICE = "api.instance.PortForwardManageRpcService"
    const val LIST_PORT_FORWARD_METHOD = "ListPortForward"

    /** 密钥脱敏占位（启动配置展示用）。 */
    private const val SECRET_MASKED = "********"

    /**
     * 解析端口列表文本：支持中英文逗号 / 分号 / 空白 / 换行分隔；
     * 自动过滤非数字、越界（1..65535）值，去重并升序排列。
     */
    fun parsePorts(raw: String): List<Int> =
        raw.split(',', '\uFF0C', ';', ' ', '\n', '\t')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }
            .distinct()
            .sorted()

    /** 显示用：端口列表 → "5244, 8080"。 */
    fun formatPorts(ports: List<Int>): String = ports.joinToString(", ")

    /**
     * 生成启动 TOML（不含端口转发，转发在拿到 DHCP 虚拟 IP 后经 RPC 追加）。
     *
     * @param networkName 网络名称（空白回退 "default"）
     * @param networkSecret 网络密钥（允许空字符串）
     * @param peerUri 对等节点 URI；空白则不生成 [[peer]]（单机直连场景也合法）
     * @param enableQuicProxy 是否启用 QUIC 代理（[flags] enable_quic_proxy，把 TCP 流转为 QUIC）
     */
    fun buildToml(
        networkName: String,
        networkSecret: String,
        peerUri: String,
        enableQuicProxy: Boolean = false,
    ): String {
        val effectiveNetwork = networkName.ifBlank { DEFAULT_NETWORK_NAME }
        val sb = StringBuilder()
        sb.append("instance_name = ").append(tomlString(INSTANCE_NAME)).append('\n')
        sb.append("hostname = ").append(tomlString(HOSTNAME)).append('\n')
        sb.append("dhcp = ").append(DHCP).append("\n\n")

        sb.append("[network_identity]\n")
        sb.append("network_name = ").append(tomlString(effectiveNetwork)).append('\n')
        sb.append("network_secret = ").append(tomlString(networkSecret)).append("\n\n")

        sb.append("[flags]\n")
        sb.append("no_tun = ").append(NO_TUN).append('\n')
        if (enableQuicProxy) {
            // 对照 FlagsInConfig.enable_quic_proxy（字段 24）：把 TCP 流转为 QUIC 流
            sb.append("enable_quic_proxy = true").append('\n')
        }
        sb.append('\n')

        val peer = peerUri.trim()
        if (peer.isNotEmpty()) {
            sb.append("[[peer]]\n")
            sb.append("uri = ").append(tomlString(peer)).append("\n\n")
        }
        return sb.toString()
    }

    /**
     * 启动配置的脱敏展示版本：网络密钥以占位符呈现，其余与 [buildToml] 一致。
     * 供设置页「启动配置」只读展示，避免密钥落屏/截图。
     */
    fun buildDisplayToml(
        networkName: String,
        networkSecret: String,
        peerUri: String,
        enableQuicProxy: Boolean,
    ): String {
        val maskedSecret = if (networkSecret.isBlank()) networkSecret else SECRET_MASKED
        return buildToml(networkName, maskedSecret, peerUri, enableQuicProxy)
    }

    /**
     * 生成 ConfigRpc.PatchConfig 的 proto3 JSON 载荷（多端口增量更新）。
     *
     * - [removeAddr]/[removePorts]：需要移除的旧绑定（虚拟 IP 变化或端口删除时传入；
     *   removePorts 为空则不生成 REMOVE 条目）。
     * - [addAddr]/[addPorts]：新增绑定 <addAddr>:<端口> -> 127.0.0.1:<同端口>(tcp)。
     */
    fun buildPortForwardPatchJson(
        removeAddr: Long,
        removePorts: List<Int>,
        addAddr: Long,
        addPorts: List<Int>,
    ): String {
        val payload = buildJsonObject {
            putJsonObject("instance") {
                putJsonObject("instance_selector") {
                    put("name", INSTANCE_NAME)
                }
            }
            putJsonObject("patch") {
                putJsonArray("port_forwards") {
                    removePorts.forEach { port ->
                        add(portForwardPatch(action = "REMOVE", bindAddr = removeAddr, port = port))
                    }
                    addPorts.forEach { port ->
                        add(portForwardPatch(action = "ADD", bindAddr = addAddr, port = port))
                    }
                }
            }
        }
        return payload.toString()
    }

    /** 单条 PortForwardPatch：action + cfg(SocketAddr×2 + SocketType)。 */
    private fun portForwardPatch(action: String, bindAddr: Long, port: Int): JsonObject =
        buildJsonObject {
            put("action", action)
            putJsonObject("cfg") {
                putJsonObject("bind_addr") {
                    putJsonObject("ipv4") { put("addr", bindAddr) }
                    put("port", port)
                }
                putJsonObject("dst_addr") {
                    putJsonObject("ipv4") { put("addr", LOOPBACK_ADDR) }
                    put("port", port)
                }
                put("socket_type", "TCP")
            }
        }

    /**
     * 仅含实例选择器的最小载荷，供 ListPortForward 这类只读 RPC 使用：
     * `{"instance":{"instance_selector":{"name":"openlist"}}}`。
     */
    fun buildInstanceSelectorJson(): String =
        buildJsonObject {
            putJsonObject("instance") {
                putJsonObject("instance_selector") {
                    put("name", INSTANCE_NAME)
                }
            }
        }.toString()

    /** Ipv4Addr.addr（uint32 大端）→ 点分十进制。 */
    fun formatIpv4(addr: Long): String {
        val a = (addr shr 24) and 0xFF
        val b = (addr shr 16) and 0xFF
        val c = (addr shr 8) and 0xFF
        val d = addr and 0xFF
        return "$a.$b.$c.$d"
    }

    /** 字节数 → 人类可读流量（1024 进制，保留一位小数）：0B / 1.2KB / 34.5MB / 1.1GB。 */
    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes.coerceAtLeast(0)}B"
        bytes < 1024L * 1024 -> "${"%.1f".format(bytes / 1024.0)}KB"
        bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))}MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))}GB"
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
