package com.xaxka.openlist.qbt

/**
 * qBittorrent Enhanced（qbittorrent-enhanced-nox）固定规格与配置模板。
 *
 * 内置方式：CI 交叉编译 bionic 动态链接二进制（NDK r27c + Qt6 静态 + openssl-linked
 * + libtorrent 1.2，见 .github/scripts/build-qbt-nox-bionic.sh），改名
 * `libqbittorrent-nox.so` 打进 jniLibs（nativeLibraryDir 允许 exec；动态依赖为
 * bionic 系统库 + libc++_shared.so，后者随 jniLibs 一并打包，由
 * [QBittorrentManager] 经 LD_LIBRARY_PATH 提供），运行期经 ProcessBuilder 以
 * 子进程方式拉起，随 OpenList 服务启停。
 *
 * 上游：c0re100/qBittorrent-Enhanced-Edition release-5.2.3.10
 * （arm64-v8a / armeabi-v7a / x86_64，需 Android 7.0/API 24+）。
 *
 * DNS 关键点：bionic 动态链接版 getaddrinfo → netd，原生继承系统 Private DNS
 * (DoT)/DNS64/VPN DNS；tracker/DHT 引导节点域名直解，DHT(UDP)/peer 全部直连，
 * 无需任何代理/转发组件（旧 musl 静态版的 LocalSocks5Proxy 方案已废弃）。
 * [QBittorrentManager] 启动时会清理旧版本残留的代理配置（见 [updateWebUiConfig]）。
 *
 * 访问模式：默认仅本机（127.0.0.1 + localhost 免认证）；可切换局域网模式
 * （0.0.0.0 监听 + 用户名/密码登录，本机仍免认证）。
 */
object QBittorrentSpec {

    /** jniLibs 内的二进制名（AGP 仅打包 lib 前缀 .so；useLegacyPackaging 解压后可 exec）。 */
    const val BINARY_LIB_NAME = "libqbittorrent-nox.so"

    /** 内置上游版本（展示用；与 CI 下载的 release tag 一致）。 */
    const val EMBEDDED_VERSION = "5.2.3.10"

    /** WebUI 默认端口（避免 8080 常见冲突与 OpenList 的 5244）。 */
    const val DEFAULT_WEBUI_PORT = 8085

    /** WebUI 启动等待超时（进程拉起 → API 可用的最长时间）。 */
    const val WEBUI_BOOT_TIMEOUT_MS = 20_000L

    /** WebUI 轮询间隔（对齐 EasyTier 的 5s 状态轮询节奏）。 */
    const val POLL_INTERVAL_MS = 5_000L

    /** 优雅停止等待：SIGTERM 后等退出，超时再 destroyForcibly。 */
    const val STOP_GRACE_MS = 5_000L

    /** 快速失败判定阈值：启动后存活不足此时长视为启动失败。 */
    const val FAST_FAIL_UPTIME_MS = 10_000L

    /** 连续快速失败达到此次数后停止自动重启（避免重启风暴），等待手动/前台恢复重试。 */
    const val FAST_FAIL_MAX = 3

    /**
     * 首次启动前的 qBittorrent.conf 种子（仅不存在时写入，之后由 nox 管理）。
     *
     * 字段对照 qb 5.2 实测（profile 下 qBittorrent/config/qBittorrent.conf）：
     * - [Preferences] WebUI\Address/Port/LocalHostAuth/Username：localhost 免认证
     *   （App 内 WebView/系统浏览器直达，无需临时密码）；
     *   Address 按 [lanAccess] 取 0.0.0.0（局域网可访问）或 127.0.0.1（仅本机）；
     * - [BitTorrent] Session\DefaultSavePath：默认保存路径（应用专属外部目录）。
     *
     * 代理不写入：bionic 版 DNS 走系统原生，App 管理策略为无代理；旧版本残留的
     * 代理键由 [updateWebUiConfig] 在每次启动前清理。
     * 密码不写入种子：PBKDF2 哈希由 nox 生成，统一走 setPreferences（web_ui_password）。
     */
    fun buildSeedConfig(webUiPort: Int, savePath: String, lanAccess: Boolean = false): String = buildString {
        append("[BitTorrent]\n")
        append("Session\\DefaultSavePath=").append(escapePath(savePath)).append('\n')
        append('\n')
        append("[Preferences]\n")
        append("WebUI\\Address=").append(if (lanAccess) "0.0.0.0" else "127.0.0.1").append('\n')
        append("WebUI\\Port=").append(webUiPort).append('\n')
        append("WebUI\\LocalHostAuth=false\n")
        append("WebUI\\Username=openlist\n")
    }

    /**
     * 既有 qBittorrent.conf 的更新（lanAccess/端口/用户名变更时，进程已停止的状态下
     * 直接改文件；保留 nox 自行持久化的其他键，例如密码哈希）：
     * - 对齐 [Preferences] 节内的 Address/Port/Username 行；键不存在则追加到该节末尾，
     *   节不存在则创建（qb 的 QSettings 兼容键序无关）；
     * - 清理代理键（升级迁移 + 无代理策略）：[Network] Proxy\*（qb 5.2 键位，旧
     *   SOCKS5 方案残留；不清则 DHT/UDP 流量继续交给已不存在的代理被丢弃）与
     *   [Preferences] Session\Proxy*（qb ≤5.0 旧键位，顺手清理）。
     */
    fun updateWebUiConfig(content: String, webUiPort: Int, username: String, lanAccess: Boolean): String {
        val address = if (lanAccess) "0.0.0.0" else "127.0.0.1"
        val updates = mapOf(
            "WebUI\\Address" to address,
            "WebUI\\Port" to webUiPort.toString(),
            "WebUI\\Username" to escapePath(username).ifEmpty { "openlist" },
        )
        // 先按节过滤代理键，再做 WebUI 键对齐
        val stripped = mutableListOf<String>()
        var section = ""
        for (raw in content.lines()) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed
                stripped += raw
                continue
            }
            val isProxyKey =
                (section == "[Network]" && trimmed.startsWith("Proxy\\")) ||
                    (section == "[Preferences]" && trimmed.startsWith("Session\\Proxy"))
            if (!isProxyKey) stripped += raw
        }
        val lines = stripped.toMutableList()
        val preferencesIdx = lines.indexOfFirst { it.trim() == "[Preferences]" }

        fun upsert(sectionEnd: Int) {
            for ((key, value) in updates) {
                val idx = lines.indexOfFirst { it.startsWith(key) }
                if (idx >= 0 && (sectionEnd == -1 || idx < sectionEnd)) {
                    lines[idx] = "$key=$value"
                } else if (sectionEnd == -1) {
                    lines.add("$key=$value")
                } else {
                    lines.add(sectionEnd, "$key=$value")
                }
            }
        }

        if (preferencesIdx < 0) {
            lines.add("")
            lines.add("[Preferences]")
            lines.addAll(updates.map { (k, v) -> "$k=$v" })
        } else {
            // 找 [Preferences] 节的结束边界（下一个 [Section] 或文件尾）
            val sectionEnd = lines.drop(preferencesIdx + 1)
                .indexOfFirst { it.trim().startsWith('[') && it.trim().endsWith(']') }
                .let { if (it < 0) -1 else preferencesIdx + 1 + it }
            upsert(sectionEnd)
        }
        return lines.joinToString("\n").trimEnd('\n') + "\n"
    }

    /**
     * 局域网模式的认证偏好 JSON（setPreferences POST）。
     *
     * web_ui_password 传明文由 qb 侧做 PBKDF2 哈希并持久化（每次启动重下发，
     * 哈希盐随机刷新，幂等）；web_ui_username 同步对齐 App 侧设置。
     */
    fun buildAuthJson(username: String, password: String): String =
        "{\"web_ui_username\":\"${escapeJson(username)}\"," +
            "\"web_ui_password\":\"${escapeJson(password)}\"}"

    /**
     * 保存路径偏好 JSON（setPreferences POST；每轮启动下发，与旧行为一致）。
     *
     * 不再携带代理字段：bionic 版 DNS 走系统原生，代理相关的历史残留统一由
     * [updateWebUiConfig] 在启动前清理（App 管理策略为无代理）。
     */
    fun buildSavePathPreferencesJson(savePath: String): String =
        "{\"save_path\":\"${escapeJson(savePath)}\"}"

    /** INI 路径值转义：反斜杠不转（Linux 风格路径），仅去换行保证单行。 */
    internal fun escapePath(value: String): String =
        value.replace("\r", " ").replace("\n", " ").trim()

    /** JSON 字符串值转义（路径等简单字段的足够子集）。 */
    internal fun escapeJson(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    /** 端口字符串解析（1-65535），非法/空白回退 [DEFAULT_WEBUI_PORT]。 */
    fun parsePort(raw: String): Int =
        raw.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_WEBUI_PORT
}
