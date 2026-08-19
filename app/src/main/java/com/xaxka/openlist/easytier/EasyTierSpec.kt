package com.xaxka.openlist.easytier

/**
 * EasyTier 实例固定规格 + TOML 配置模板。
 *
 * 目标：把本机 OpenList 的 5244 端口映射进 EasyTier 虚拟局域网（no-tun 模式，不使用
 * Android VPN 服务）：虚拟网内其他节点访问 <bind> 即转发到本机 127.0.0.1:5244。
 *
 * 字段对照 easytier-core/src/config/toml.rs（Config 结构）：
 * - 顶层 instance_name / hostname / dhcp
 * - [network_identity] network_name / network_secret
 * - [[peer]] uri（多条可重复，本模板仅暴露一条）
 * - [flags] no_tun（位于 FlagsInConfig，非顶层字段）
 * - [[port_forward]] bind_addr / dst_addr / proto
 */
object EasyTierSpec {

    /** 实例名与主机名（collectNetworkInfos 返回 map 的 key 即 instance_name）。 */
    const val INSTANCE_NAME = "openlist"
    const val HOSTNAME = "openlist"

    /** 通过 DHCP 向 EasyTier 网络申请虚拟 IPv4（不写静态 ipv4）。 */
    const val DHCP = true

    /** no-tun：不创建 TUN 设备、不使用 VpnService，端口转发走核心内部的代理通道。 */
    const val NO_TUN = true

    /** 虚拟网监听地址（DHCP 分到的虚拟 IP:5244）。 */
    const val PORT_FORWARD_BIND = "10.144.144.2:5244"

    /** 转发目标：本机 OpenList 服务。 */
    const val PORT_FORWARD_DST = "127.0.0.1:5244"
    const val PORT_FORWARD_PROTO = "tcp"

    /** 网络名为空时回退 EasyTier 默认网络。 */
    const val DEFAULT_NETWORK_NAME = "default"

    /**
     * 生成 TOML 配置。
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

        sb.append("[[port_forward]]\n")
        sb.append("bind_addr = ").append(tomlString(PORT_FORWARD_BIND)).append('\n')
        sb.append("dst_addr = ").append(tomlString(PORT_FORWARD_DST)).append('\n')
        sb.append("proto = ").append(tomlString(PORT_FORWARD_PROTO)).append('\n')
        return sb.toString()
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
