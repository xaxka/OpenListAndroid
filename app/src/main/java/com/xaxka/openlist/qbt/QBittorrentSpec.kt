package com.xaxka.openlist.qbt

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * qbittorrent（qbittorrent-enhanced-nox）固定规格与配置模板。
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
 * 凭据策略：默认账号 admin / adminadmin（qb 默认值）；密码可在 App「登录账号」
 * 菜单修改（存 DataStore，种子/运行态两层下发；用户名固定 admin）。
 * 访问模式：默认仅本机（127.0.0.1 + localhost 免认证）；可切换局域网模式
 * （0.0.0.0 监听 + 登录，本机仍免认证）。
 *
 * 内存优化（面向 100M 宽带场景：下载峰值 ≈ 12.5MB/s，磁盘压力极低，
 * 经种子与 setPreferences 双层下发，见 [MEMORY_TUNING_INI]）：
 * libtorrent 1.2 磁盘缓存默认 -1（按物理内存自适应，8GB 设备可膨胀数百 MB～GB 级），
 * 固定上限 16MiB（≈ 100Mbps 下 1.3 秒的写入量，足够合并写盘；继续压到 0 也可行，
 * 但保留小块用户态缓存可减少校验/做种重读的 syscall 放大）；校验内存 8MiB、
 * IO 线程 2 条（100Mbps ≈ 每秒百次磁盘作业，双线程绰绰有余）；磁盘 IO 模式
 * 钉住 EnableOSCache——缓存落在可回收的内核 page cache，RSS 更低且系统压力下
 * 可被内核自由回收。相关键位对照 release-5.2.3.10 sessionimpl.cpp
 * （BITTORRENT_SESSION_KEY）与 appcontroller.cpp。
 *
 * DHT：显式开启并扩展引导路由器列表（qb 默认 3 个，部分网络不可达时路由表
 * 长期近似空 → WebUI 显示仅 1 节点；多路由器冗余提升引导成功率）。
 */
object QBittorrentSpec {

    /** jniLibs 内的二进制名（AGP 仅打包 lib 前缀 .so；useLegacyPackaging 解压后可 exec）。 */
    const val BINARY_LIB_NAME = "libqbittorrent-nox.so"

    /** 内置上游版本（展示用；与 CI 下载的 release tag 一致）。 */
    const val EMBEDDED_VERSION = "5.2.3.10"

    /** WebUI 登录用户名（qb 默认，固定不变；qb 要求 ≥3 字符且不含冒号）。 */
    const val WEBUI_USERNAME = "admin"

    /** WebUI 默认密码明文（qb 默认 adminadmin；App「登录账号」菜单可改，存 DataStore）。 */
    const val DEFAULT_WEBUI_PASSWORD = "adminadmin"

    /** 密码最短长度（qb web_ui_password API 校验：≥6 字符）。 */
    const val WEBUI_PASSWORD_MIN_LENGTH = 6

    /**
     * 默认密码 admin/adminadmin 的 PBKDF2 哈希（qb 兼容格式 `salt:hash`，
     * PBKDF2-HMAC-SHA512、100000 迭代、64 字节输出，见 qbt Utils::Password::PBKDF2::generate）。
     * 盐为固定常量（默认密码本就公开，无保密需求；保持常量可让已部署配置不漂移）。
     * INI 值带引号经 @ByteArray() 包裹（QSettings 的 QByteArray 序列化格式，对齐 nox 实测输出）。
     */
    private const val DEFAULT_WEBUI_PASSWORD_PBKDF2 =
        "\"@ByteArray(QUJFaU0wUlZabmVJbWFxN3pOM3Uvdz09OlgwNEJYd2xDWWxUejJEL0FoQjhzT2JTa05uRElDUDZGcVJDZ1padjdqb1NsdTNEa040aWs0MUc3VitaYVNLbkVFbmQyZVRXSzNPaXVxUWpnTU9HU253PT0=)\""

    /**
     * DHT 引导路由器（qb 默认 3 个 + uTorrent/Vuze 路由器冗余；格式与 qb
     * DEFAULT_DHT_BOOTSTRAP_NODES 一致：逗号分隔 host:port，LT 解析容忍空格）。
     * 既有安装经启动 setPreferences 下发更新；新安装随种子写入。
     */
    const val DHT_BOOTSTRAP_NODES =
        "dht.libtorrent.org:25401, dht.transmissionbt.com:6881, router.bittorrent.com:6881, router.utorrent.com:6881, dht.aelitis.com:6881"

    /** WebUI 默认端口（避免 8080 常见冲突与 OpenList 的 5244）。 */
    const val DEFAULT_WEBUI_PORT = 8085

    /**
     * 内存调优参数（INI 键 = [BitTorrent] Session\*，JSON 键 = setPreferences）。
     *
     * 面向「100M 宽带够用」目标（下载峰值 ≈ 12.5MB/s）的深度裁剪：
     *
     * - DiskCacheSize/disk_cache：libtorrent 1.2 用户态磁盘缓存上限（MiB）。
     *   qb 默认 -1 = 按物理内存自适应，手机大内存下可膨胀数百 MB；16MiB ≈
     *   100Mbps 下 1.3 秒的写入量，足够合并写盘与校验重读（本项为内存优化
     *   主力：相比自适应值可省几十～几百 MB RSS）；
     * - CheckingMemUsageSize/checking_memory_use：校验（hash check）内存 MiB
     *   （qb 默认 32 → 8；校验吞吐受手机存储限制而非内存，8MiB 足够）；
     * - AsyncIOThreadsCount/async_io_threads：磁盘 IO 线程数（qb 默认 10 → 2；
     *   100Mbps ≈ 每秒百次磁盘作业，双线程绰绰有余，省线程栈与调度开销）；
     * - DiskIOReadMode/DiskIOWriteMode = 1（EnableOSCache）：钉住 qb 默认的
     *   系统缓存模式——磁盘缓存交给内核 page cache（可回收、不计入进程 RSS），
     *   避免用户误设 O_DIRECT 造成性能下降；
     * - SendBufferWatermark/send_buffer_watermark：per-peer 发送缓冲上限 KB
     *   （qb 默认 500 → 256；对 100Mbps 无影响，降低多 peer 时的内核内存压力）；
     * - DiskCacheTTL/disk_cache_ttl（qb 默认 60，显式钉住）与
     *   FilePoolSize/file_pool_size（100 → 40，句柄池，再低会引发做种库频繁
     *   开关文件抖动）保留。
     */
    private val MEMORY_TUNING_INI = mapOf(
        "Session\\DiskCacheSize" to "16",
        "Session\\DiskCacheTTL" to "60",
        "Session\\AsyncIOThreadsCount" to "2",
        "Session\\FilePoolSize" to "40",
        "Session\\CheckingMemUsageSize" to "8",
        "Session\\DiskIOReadMode" to "1",
        "Session\\DiskIOWriteMode" to "1",
        "Session\\SendBufferWatermark" to "256",
    )

    /** 同上，setPreferences JSON 键位（appcontroller.cpp，LT 1.2 生效路径）。 */
    internal val MEMORY_TUNING_JSON = mapOf(
        "disk_cache" to "16",
        "disk_cache_ttl" to "60",
        "async_io_threads" to "2",
        "file_pool_size" to "40",
        "checking_memory_use" to "8",
        "disk_io_read_mode" to "1",
        "disk_io_write_mode" to "1",
        "send_buffer_watermark" to "256",
    )

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

    // ---------------------------------------------------------------- 密码哈希

    /**
     * 生成密码的 qb 兼容 INI 值（WebUI\Password_PBKDF2 整行右值）。
     *
     * 格式对齐 qb Utils::Password::PBKDF2::generate：`base64(salt):base64(hash)`，
     * PBKDF2-HMAC-SHA512、100000 迭代、16 字节随机盐、64 字节输出；再整体
     * base64 后包 @ByteArray() 与双引号（QSettings 的 QByteArray 序列化格式）。
     *
     * 默认密码 adminadmin 返回固定常量（盐为 RFC 向量，保持已部署配置稳定）；
     * 自定义密码每次生成新随机盐。仅依赖 JVM 标准 Crypto（无 Android 专属 API，
     * 可在纯 JVM 单测中验证与 qb 算法一致性）。
     */
    fun buildWebUiPasswordIniValue(password: String): String {
        if (password == DEFAULT_WEBUI_PASSWORD) return DEFAULT_WEBUI_PASSWORD_PBKDF2
        val salt = ByteArray(SALT_LEN_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(password, salt)
        val inner = b64(salt) + ":" + b64(hash)
        return "\"@ByteArray(" + b64(inner.toByteArray(Charsets.UTF_8)) + ")\""
    }

    /** PBKDF2-HMAC-SHA512（100k 迭代、64 字节输出），对齐 qb 参数。 */
    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN_BYTES * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(spec).encoded
    }

    private fun b64(bytes: ByteArray): String = base64Encode(bytes)

    private const val PBKDF2_ITERATIONS = 100_000
    private const val SALT_LEN_BYTES = 16
    private const val KEY_LEN_BYTES = 64

    private const val B64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * 纯 Kotlin Base64 编码（RFC 4648 标准字母表、带填充、无换行）。
     *
     * 不用 android.util.Base64（纯 JVM 单测不可用）也不用 java.util.Base64
     * （API 26+，低于 minSdk 26 的设备崩溃）；仅编码方向，实现极小。
     */
    private fun base64Encode(bytes: ByteArray): String = buildString {
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            append(B64_ALPHABET[(n ushr 18) and 0x3F])
            append(B64_ALPHABET[(n ushr 12) and 0x3F])
            append(B64_ALPHABET[(n ushr 6) and 0x3F])
            append(B64_ALPHABET[n and 0x3F])
            i += 3
        }
        val rem = bytes.size - i
        if (rem == 1) {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            append(B64_ALPHABET[(n ushr 18) and 0x3F])
            append(B64_ALPHABET[(n ushr 12) and 0x3F])
            append("==")
        } else if (rem == 2) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8)
            append(B64_ALPHABET[(n ushr 18) and 0x3F])
            append(B64_ALPHABET[(n ushr 12) and 0x3F])
            append(B64_ALPHABET[(n ushr 6) and 0x3F])
            append('=')
        }
    }

    // ---------------------------------------------------------------- 配置模板

    /**
     * 首次启动前的 qBittorrent.conf 种子（仅不存在时写入，之后由 nox 管理）。
     *
     * 字段对照 qb 5.2 实测（profile 下 qBittorrent/config/qBittorrent.conf）：
     * - [Preferences] WebUI\Address/Port/LocalHostAuth/Username：localhost 免认证
     *   （App 内 WebView/系统浏览器直达，无需临时密码）；
     *   Address 按 [lanAccess] 取 0.0.0.0（局域网可访问）或 127.0.0.1（仅本机）；
     * - [Preferences] WebUI\Password_PBKDF2：qb 5.2 凭据为空时 WebUI 直接报错
     *   （"Credentials are not set"，不再回退默认密码），必须随种子写入
     *   admin 与 [webUiPassword]（默认 adminadmin）的哈希；
     * - [BitTorrent] Session\DefaultSavePath：默认保存路径（公共 Download/qbittorrent）；
     * - [BitTorrent] Session\DiskCache* / AsyncIOThreadsCount / FilePoolSize /
     *   CheckingMemUsageSize：手机内存调优（见 [MEMORY_TUNING_INI]）；
     * - [BitTorrent] Session\DHTEnabled / DHTBootstrapNodes：DHT 显式开启 +
     *   扩展引导路由器（对齐 qb 默认 true，多路由器冗余）。
     *
     * ⚠️ qb 的原子保存回退文件 qBittorrent_new.conf 在启动读取时**无条件优先**于
     * 本文件（视为异常退出恢复）；崩溃/被杀残留的 _new 会劫持本种子（凭据丢失
     * → nox 生成一次性随机临时密码 → 登录 401）。由 Manager 在每次启动前删除
     * 残留 _new（见 [com.xaxka.openlist.qbt.QBittorrentManager.ensureConfig]）。
     *
     * 代理不写入：bionic 版 DNS 走系统原生，App 管理策略为无代理；旧版本残留的
     * 代理键由 [updateWebUiConfig] 在每次启动前清理。
     */
    fun buildSeedConfig(
        webUiPort: Int,
        savePath: String,
        lanAccess: Boolean = false,
        webUiPassword: String = DEFAULT_WEBUI_PASSWORD,
    ): String = buildString {
        append("[BitTorrent]\n")
        append("Session\\DefaultSavePath=").append(escapePath(savePath)).append('\n')
        MEMORY_TUNING_INI.forEach { (k, v) -> append(k).append('=').append(v).append('\n') }
        append("Session\\DHTEnabled=true\n")
        append("Session\\DHTBootstrapNodes=").append(DHT_BOOTSTRAP_NODES).append('\n')
        append('\n')
        append("[Preferences]\n")
        append("WebUI\\Address=").append(if (lanAccess) "0.0.0.0" else "127.0.0.1").append('\n')
        append("WebUI\\Port=").append(webUiPort).append('\n')
        append("WebUI\\LocalHostAuth=false\n")
        append("WebUI\\Username=").append(WEBUI_USERNAME).append('\n')
        append("WebUI\\Password_PBKDF2=").append(buildWebUiPasswordIniValue(webUiPassword)).append('\n')
    }

    /**
     * 既有 qBittorrent.conf 的更新（lanAccess/端口/密码变更时，进程已停止的状态下
     * 直接改文件；保留 nox 自行持久化的其他键）：
     * - 对齐 [Preferences] 节内的 Address/Port 行；键不存在则追加到该节末尾，
     *   节不存在则创建（qb 的 QSettings 兼容键序无关）；
     * - 凭据对齐：用户名固定 admin，密码哈希取 [webUiPassword]（默认 adminadmin，
     *   自定义密码时为该密码的哈希——用户改密后配置层与 DataStore 保持一致）；
     * - 对齐内存调优与 DHT 引导键（[BitTorrent] Session\*，与启动 setPreferences
     *   双层互补；用户在 WebUI 关掉 DHT 不被此层覆盖——仅对齐引导节点列表）；
     * - 清理代理键（升级迁移 + 无代理策略）：[Network] Proxy\*（qb 5.2 键位，旧
     *   SOCKS5 方案残留；不清则 DHT/UDP 流量继续交给已不存在的代理被丢弃）与
     *   [Preferences] Session\Proxy*（qb ≤5.0 旧键位，顺手清理）。
     */
    fun updateWebUiConfig(
        content: String,
        webUiPort: Int,
        lanAccess: Boolean,
        webUiPassword: String = DEFAULT_WEBUI_PASSWORD,
    ): String {
        val address = if (lanAccess) "0.0.0.0" else "127.0.0.1"
        val updates = mapOf(
            "WebUI\\Address" to address,
            "WebUI\\Port" to webUiPort.toString(),
            "WebUI\\Username" to WEBUI_USERNAME,
            "WebUI\\Password_PBKDF2" to buildWebUiPasswordIniValue(webUiPassword),
            "WebUI\\LocalHostAuth" to "false",
        )
        val sessionUpdates = MEMORY_TUNING_INI + ("Session\\DHTBootstrapNodes" to DHT_BOOTSTRAP_NODES)
        // 先按节过滤代理键，再做键对齐
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

        fun upsertInSection(sectionHeader: String, sectionUpdates: Map<String, String>) {
            val sectionIdx = lines.indexOfFirst { it.trim() == sectionHeader }
            if (sectionIdx < 0) {
                if (sectionUpdates.isEmpty()) return
                lines.add("")
                lines.add(sectionHeader)
                lines.addAll(sectionUpdates.map { (k, v) -> "$k=$v" })
                return
            }
            // 节结束边界（下一个 [Section] 或文件尾）
            val sectionEnd = lines.drop(sectionIdx + 1)
                .indexOfFirst { it.trim().startsWith('[') && it.trim().endsWith(']') }
                .let { if (it < 0) -1 else sectionIdx + 1 + it }
            for ((key, value) in sectionUpdates) {
                // 键可能落在任一节（旧文件键序漂移），全局查找但优先节内
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

        upsertInSection("[Preferences]", updates)
        upsertInSection("[BitTorrent]", sessionUpdates)
        return lines.joinToString("\n").trimEnd('\n') + "\n"
    }

    // ---------------------------------------------------------------- 运行态偏好

    /**
     * 启动偏好 JSON（setPreferences POST；每轮启动 WebUI 就绪后下发）。
     *
     * 运行态自愈层：本机回环免认证（LocalHostAuth=false）下调用，无论配置文件
     * 处于何种历史状态，都对齐账号 admin、密码 [webUiPassword]（默认
     * adminadmin，自定义密码时为用户所设——qb 侧自行 PBKDF2 哈希落盘）、
     * localhost 免认证、保存路径、内存调优与 DHT 引导节点。
     *
     * 不携带 enable_dht 布尔：尊重用户在 WebUI 的 DHT 开关选择（种子层已按
     * qb 默认值开启）；只对齐引导节点列表（基础设施配置）。
     *
     * 不再携带代理字段：bionic 版 DNS 走系统原生，代理相关的历史残留统一由
     * [updateWebUiConfig] 在启动前清理（App 管理策略为无代理）。
     */
    fun buildStartupPreferencesJson(
        savePath: String,
        webUiPassword: String = DEFAULT_WEBUI_PASSWORD,
    ): String = buildString {
        append("{\"web_ui_username\":\"").append(escapeJson(WEBUI_USERNAME))
        append("\",\"web_ui_password\":\"").append(escapeJson(webUiPassword))
        append("\",\"bypass_local_auth\":true")
        append(",\"save_path\":\"").append(escapeJson(savePath)).append("\"")
        MEMORY_TUNING_JSON.forEach { (k, v) -> append(",\"").append(k).append("\":").append(v) }
        append(",\"dht_bootstrap_nodes\":\"").append(escapeJson(DHT_BOOTSTRAP_NODES)).append("\"}")
    }

    /** 仅改密码的最小 JSON（运行中即时改密；qb 校验 ≥6 字符后哈希落盘）。 */
    fun buildPasswordOnlyJson(webUiPassword: String): String =
        "{\"web_ui_password\":\"" + escapeJson(webUiPassword) + "\"}"

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
