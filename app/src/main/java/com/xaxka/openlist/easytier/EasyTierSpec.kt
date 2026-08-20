package com.xaxka.openlist.easytier

/**
 * EasyTier 实例固定规格 + TOML 配置模板。
 *
 * 目标：把本机服务暴露给 EasyTier 虚拟局域网（no-tun 模式，不使用 Android VPN 服务）。
 *
 * 无需下发端口转发规则：no-tun 模式下 EasyTier 核心的代理引擎会把组网设备发往本机
 * 虚拟 IP 的 TCP/UDP/ICMP 包自动落到本机回环同端口（如 <虚拟IP>:5244 直达 OpenList），
 * 启动 TOML 不携带 [[port_forward]]。
 *
 * TOML 字段对照 easytier-core/src/config/toml.rs（Config 结构）：
 * - 顶层 instance_name / hostname / dhcp
 * - [network_identity] network_name / network_secret
 * - [secure_mode] enabled / local_private_key / local_public_key
 *   （TOML 入口不执行 normalize_secure_mode_config：enabled = true 必须携带密钥对，
 *   否则核心报 "local private key is not set"；CLI --secure-mode 的自动生成仅存在于
 *   CLI/Web GUI 入口，见 easytier/src/core.rs 与 config/api_input.rs）
 * - [[peer]] uri（多条可重复，本模板仅暴露一条）
 * - [flags] no_tun / enable_quic_proxy（均位于 FlagsInConfig，非顶层字段）
 */
object EasyTierSpec {

    /** 实例名与主机名（collectNetworkInfos 返回 map 的 key 即 instance_name）。 */
    const val INSTANCE_NAME = "openlist"
    const val HOSTNAME = "openlist"

    /** 通过 DHCP 向 EasyTier 网络申请虚拟 IPv4（不写静态 ipv4）。 */
    const val DHCP = true

    /** no-tun：不创建 TUN 设备、不使用 VpnService，发往虚拟 IP 的流量经核心代理直达本机回环。 */
    const val NO_TUN = true

    /** 网络名为空时回退 EasyTier 默认网络。 */
    const val DEFAULT_NETWORK_NAME = "default"

    /** 密钥脱敏占位（启动配置展示用）。 */
    private const val SECRET_MASKED = "********"

    /**
     * 生成启动 TOML。
     *
     * @param networkName 网络名称（空白回退 "default"）
     * @param networkSecret 网络密钥（允许空字符串）
     * @param peerUri 对等节点 URI；空白则不生成 [[peer]]（单机直连场景也合法）
     * @param enableQuicProxy 是否启用 QUIC 代理（[flags] enable_quic_proxy，把 TCP 流转为 QUIC）
     * @param secureMode 是否启用安全模式（[secure_mode] enabled：E2EE + Noise 握手 + 防重放；
     * 对端节点也需开启并升级到支持安全模式的版本，默认关闭保持旧网络兼容）
     * @param localPrivateKey 安全模式本机 X25519 私钥（base64）；secureMode 开启时必填
     * @param localPublicKey 安全模式本机 X25519 公钥（base64，由私钥派生）；secureMode 开启时必填
     */
    fun buildToml(
        networkName: String,
        networkSecret: String,
        peerUri: String,
        enableQuicProxy: Boolean = false,
        secureMode: Boolean = false,
        localPrivateKey: String = "",
        localPublicKey: String = "",
    ): String {
        val effectiveNetwork = networkName.ifBlank { DEFAULT_NETWORK_NAME }
        val sb = StringBuilder()
        sb.append("instance_name = ").append(tomlString(INSTANCE_NAME)).append('\n')
        sb.append("hostname = ").append(tomlString(HOSTNAME)).append('\n')
        sb.append("dhcp = ").append(DHCP).append("\n\n")

        sb.append("[network_identity]\n")
        sb.append("network_name = ").append(tomlString(effectiveNetwork)).append('\n')
        sb.append("network_secret = ").append(tomlString(networkSecret)).append("\n\n")

        if (secureMode) {
            // 对照官方文档 secure-mode + common.proto SecureModeConfig：
            // TOML 入口无自动密钥归一化，enabled = true 必须同时携带密钥对
            sb.append("[secure_mode]\n")
            sb.append("enabled = true\n")
            if (localPrivateKey.isNotBlank()) {
                sb.append("local_private_key = ").append(tomlString(localPrivateKey)).append('\n')
            }
            if (localPublicKey.isNotBlank()) {
                sb.append("local_public_key = ").append(tomlString(localPublicKey)).append('\n')
            }
            sb.append('\n')
        }

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
     * 启动配置的脱敏展示版本：网络密钥与安全模式私钥以占位符呈现，其余与 [buildToml] 一致
     * （公钥可公开，原样展示便于排查组网问题）。供设置页「启动配置」只读展示，避免密钥落屏/截图。
     */
    fun buildDisplayToml(
        networkName: String,
        networkSecret: String,
        peerUri: String,
        enableQuicProxy: Boolean,
        secureMode: Boolean = false,
        localPrivateKey: String = "",
        localPublicKey: String = "",
    ): String {
        val maskedSecret = if (networkSecret.isBlank()) networkSecret else SECRET_MASKED
        val maskedPrivateKey = if (localPrivateKey.isBlank()) localPrivateKey else SECRET_MASKED
        return buildToml(
            networkName, maskedSecret, peerUri, enableQuicProxy, secureMode,
            localPrivateKey = maskedPrivateKey,
            localPublicKey = localPublicKey,
        )
    }

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
