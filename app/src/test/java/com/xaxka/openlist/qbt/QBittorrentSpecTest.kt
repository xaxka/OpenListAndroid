package com.xaxka.openlist.qbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * QBittorrentSpec 单测：配置种子与启动偏好 JSON 的生成契约。
 * 对照 qbittorrent-enhanced-nox 5.2.3.10 实测（profile/qBittorrent/config/qBittorrent.conf）：
 * - [Preferences] WebUI\Address/Port/LocalHostAuth/Username/Password_PBKDF2
 * - [BitTorrent] Session\DefaultSavePath、内存调优（DiskCache* 等）与 DHT 引导键
 * - 旧代理键（[Network] Proxy\*、[Preferences] Session\Proxy*）由 updateWebUiConfig 清理
 */
class QBittorrentSpecTest {

    @Test
    fun `端口解析合法值与回退`() {
        assertEquals(8085, QBittorrentSpec.parsePort("8085"))
        assertEquals(1, QBittorrentSpec.parsePort("1"))
        assertEquals(65535, QBittorrentSpec.parsePort(" 65535 "))
        // 非法/空白回退默认端口
        assertEquals(QBittorrentSpec.DEFAULT_WEBUI_PORT, QBittorrentSpec.parsePort(""))
        assertEquals(QBittorrentSpec.DEFAULT_WEBUI_PORT, QBittorrentSpec.parsePort("abc"))
        assertEquals(QBittorrentSpec.DEFAULT_WEBUI_PORT, QBittorrentSpec.parsePort("0"))
        assertEquals(QBittorrentSpec.DEFAULT_WEBUI_PORT, QBittorrentSpec.parsePort("65536"))
        assertEquals(QBittorrentSpec.DEFAULT_WEBUI_PORT, QBittorrentSpec.parsePort("-1"))
    }

    @Test
    fun `种子配置包含回环绑定免认证保存路径与内存调优DHT键`() {
        val conf = QBittorrentSpec.buildSeedConfig(18085, "/storage/emulated/0/Download/qbittorrent")
        // WebUI 仅绑定回环 + 本机免认证（安全默认：局域网不可访问）
        assertTrue(conf.contains("WebUI\\Address=127.0.0.1"))
        assertTrue(conf.contains("WebUI\\Port=18085"))
        assertTrue(conf.contains("WebUI\\LocalHostAuth=false"))
        // 默认凭据：admin/adminadmin（qb 5.2 凭据为空时 WebUI 报错，必须随种子写入）
        assertTrue(conf.contains("WebUI\\Username=admin"))
        assertTrue(conf.contains("WebUI\\Password_PBKDF2=\"@ByteArray("))
        // 默认保存路径写入 Session\DefaultSavePath（公共下载目录）
        assertTrue(conf.contains("Session\\DefaultSavePath=/storage/emulated/0/Download/qbittorrent"))
        // 内存调优（100M 宽带深度裁剪：磁盘缓存 16MiB 上限为主力）
        assertTrue(conf.contains("Session\\DiskCacheSize=16"))
        assertTrue(conf.contains("Session\\DiskCacheTTL=60"))
        assertTrue(conf.contains("Session\\AsyncIOThreadsCount=2"))
        assertTrue(conf.contains("Session\\FilePoolSize=40"))
        assertTrue(conf.contains("Session\\CheckingMemUsageSize=8"))
        // 磁盘 IO 模式钉住 EnableOSCache=1（缓存交给可回收的内核 page cache）
        assertTrue(conf.contains("Session\\DiskIOReadMode=1"))
        assertTrue(conf.contains("Session\\DiskIOWriteMode=1"))
        // per-peer 发送缓冲上限（qb 默认 500KB → 256KB）
        assertTrue(conf.contains("Session\\SendBufferWatermark=256"))
        // DHT：显式开启 + 扩展引导路由器（默认 3 个 → 5 个冗余）
        assertTrue(conf.contains("Session\\DHTEnabled=true"))
        assertTrue(conf.contains("Session\\DHTBootstrapNodes=dht.libtorrent.org:25401"))
        assertTrue(conf.contains("router.bittorrent.com:6881"))
        assertTrue(conf.contains("router.utorrent.com:6881"))
        assertTrue(conf.contains("dht.aelitis.com:6881"))
    }

    @Test
    fun `默认密码哈希与qb算法一致`() {
        // 常量哈希必须是 adminadmin 的正确 PBKDF2-HMAC-SHA512（100k 迭代/16B 盐/64B key）
        val conf = QBittorrentSpec.buildSeedConfig(8085, "/dl")
        val line = conf.lineSequence().first { it.startsWith("WebUI\\Password_PBKDF2=") }
        val value = line.removePrefix("WebUI\\Password_PBKDF2=")
        assertTrue(verifyQbHash(QBittorrentSpec.DEFAULT_WEBUI_PASSWORD, value))
        // 与已部署版本相同的常量（不漂移，升级用户凭据稳定）
        assertEquals(
            "\"@ByteArray(QUJFaU0wUlZabmVJbWFxN3pOM3Uvdz09OlgwNEJYd2xDWWxUejJEL0FoQjhzT2JTa05uRElDUDZGcVJDZ1padjdqb1NsdTNEa040aWs0MUc3VitaYVNLbkVFbmQyZVRXSzNPaXVxUWpnTU9HU253PT0=)\"",
            value,
        )
    }

    @Test
    fun `自定义密码哈希可被qb算法校验且与默认不同`() {
        val value = QBittorrentSpec.buildWebUiPasswordIniValue("mySecret7")
        assertTrue(verifyQbHash("mySecret7", value))
        assertFalse(verifyQbHash("adminadmin", value))
        // 每次生成随机盐 → 两次结果不同（盐不固定）
        assertNotEquals(value, QBittorrentSpec.buildWebUiPasswordIniValue("mySecret7"))

        // 种子与更新均写入自定义密码哈希
        val seed = QBittorrentSpec.buildSeedConfig(8085, "/dl", webUiPassword = "mySecret7")
        val seedLine = seed.lineSequence().first { it.startsWith("WebUI\\Password_PBKDF2=") }
        assertTrue(verifyQbHash("mySecret7", seedLine.removePrefix("WebUI\\Password_PBKDF2=")))

        val updated = QBittorrentSpec.updateWebUiConfig(
            "[Preferences]\nWebUI\\Username=admin\nWebUI\\Password_PBKDF2=\"@ByteArray(oldhash)\"\n",
            webUiPort = 8085,
            lanAccess = false,
            webUiPassword = "mySecret7",
        )
        val updatedLine = updated.lineSequence().first { it.startsWith("WebUI\\Password_PBKDF2=") }
        assertTrue(verifyQbHash("mySecret7", updatedLine.removePrefix("WebUI\\Password_PBKDF2=")))
        assertFalse(updated.contains("oldhash"))
    }

    /** 用 qb 的校验算法（Utils::Password::PBKDF2）验证 INI 值。 */
    private fun verifyQbHash(password: String, iniValue: String): Boolean = runCatching {
        // 去引号与 @ByteArray() 包裹 → base64 → "salt:hash"
        val inner = iniValue.trim('"').removePrefix("@ByteArray(").removeSuffix(")")
        val decoded = String(Base64.getDecoder().decode(inner), Charsets.UTF_8)
        val (saltB64, hashB64) = decoded.split(":")
        val salt = Base64.getDecoder().decode(saltB64)
        val expected = Base64.getDecoder().decode(hashB64)
        assertEquals(16, salt.size)
        assertEquals(64, expected.size)
        val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 64 * 8)
        val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        actual.contentEquals(expected)
    }.getOrDefault(false)

    @Test
    fun `启动偏好JSON含凭据保存路径内存调优与DHT引导`() {
        val json = QBittorrentSpec.buildStartupPreferencesJson("/storage/emulated/0/Download/qbittorrent")
        // 凭据：WebUI 就绪后运行态自愈对齐（qb setPreferences 明文入参，
        // qb 侧自行 PBKDF2 哈希落盘）
        assertTrue(json.contains("\"web_ui_username\":\"admin\""))
        assertTrue(json.contains("\"web_ui_password\":\"adminadmin\""))
        // localhost 免认证（qb 侧参数为 bypass_local_auth，与种子 LocalHostAuth=false 互补）
        assertTrue(json.contains("\"bypass_local_auth\":true"))
        // 保存路径下发
        assertTrue(json.contains("\"save_path\":\"/storage/emulated/0/Download/qbittorrent\""))
        // 内存调优（LT 1.2 生效路径；disk_cache 单位 MiB）
        assertTrue(json.contains("\"disk_cache\":16"))
        assertTrue(json.contains("\"disk_cache_ttl\":60"))
        assertTrue(json.contains("\"async_io_threads\":2"))
        assertTrue(json.contains("\"file_pool_size\":40"))
        assertTrue(json.contains("\"checking_memory_use\":8"))
        assertTrue(json.contains("\"disk_io_read_mode\":1"))
        assertTrue(json.contains("\"disk_io_write_mode\":1"))
        assertTrue(json.contains("\"send_buffer_watermark\":256"))
        // DHT 引导节点对齐（不携带 enable_dht 布尔：尊重用户 WebUI 开关）
        assertTrue(json.contains("\"dht_bootstrap_nodes\":\"dht.libtorrent.org:25401"))
        assertTrue(json.contains("router.bittorrent.com:6881"))
        assertFalse(json.contains("\"dht\":"))
        // bionic 版 DNS 走系统原生，App 管理策略为无代理：不下发任何代理字段
        assertFalse(json.contains("proxy"))

        // 自定义密码运行态对齐
        val custom = QBittorrentSpec.buildStartupPreferencesJson("/dl", webUiPassword = "mySecret7")
        assertTrue(custom.contains("\"web_ui_password\":\"mySecret7\""))
    }

    @Test
    fun `仅改密JSON只含密码字段`() {
        val json = QBittorrentSpec.buildPasswordOnlyJson("mySecret7")
        assertEquals("{\"web_ui_password\":\"mySecret7\"}", json)
    }

    @Test
    fun `JSON转义处理反斜杠与引号`() {
        val json = QBittorrentSpec.buildStartupPreferencesJson("a\"b\\c")
        assertTrue(json.contains("a\\\"b\\\\c"))
        assertFalse(json.contains("a\"b"))
    }

    @Test
    fun `种子配置按局域网开关切换监听地址`() {
        val save = "/data/dl"
        val local = QBittorrentSpec.buildSeedConfig(8085, save, lanAccess = false)
        assertTrue(local.contains("WebUI\\Address=127.0.0.1"))
        val lan = QBittorrentSpec.buildSeedConfig(8085, save, lanAccess = true)
        assertTrue(lan.contains("WebUI\\Address=0.0.0.0"))
        // 两种模式都保持 localhost 免认证、凭据、内存调优与 DHT 引导
        for (conf in listOf(local, lan)) {
            assertTrue(conf.contains("WebUI\\LocalHostAuth=false"))
            assertTrue(conf.contains("WebUI\\Port=8085"))
            assertTrue(conf.contains("WebUI\\Username=admin"))
            assertTrue(conf.contains("Session\\DefaultSavePath=$save"))
            assertTrue(conf.contains("Session\\DiskCacheSize=16"))
            assertTrue(conf.contains("Session\\SendBufferWatermark=256"))
            assertTrue(conf.contains("Session\\DHTBootstrapNodes="))
        }
    }

    @Test
    fun `更新既有配置保留其他键并替换WebUI键与调优键`() {
        val existing = """
            [BitTorrent]
            Session\DefaultSavePath=/old/path
            Session\Port=55599
            Session\DiskCacheSize=-1
            Session\DHTEnabled=false

            [Preferences]
            WebUI\Address=127.0.0.1
            WebUI\Port=18085
            WebUI\LocalHostAuth=false
            WebUI\Username=openlist
            WebUI\Password_PBKDF2="@ByteArray(bas64hash)"
            Session\ProxyType=SOCKS5

            [Network]
            Proxy\Type=SOCKS5
            Proxy\IP=127.0.0.1
            Proxy\Port=39001
            Proxy\Profiles\BitTorrent=true
            Cookie\Name=keep
        """.trimIndent()
        val updated = QBittorrentSpec.updateWebUiConfig(existing, webUiPort = 9090, lanAccess = true)

        // 绑定/端口替换，凭据对齐默认 admin/adminadmin（含旧哈希重置）
        assertTrue(updated.contains("WebUI\\Address=0.0.0.0"))
        assertTrue(updated.contains("WebUI\\Port=9090"))
        assertTrue(updated.contains("WebUI\\Username=admin"))
        assertTrue(updated.contains("WebUI\\Password_PBKDF2=\"@ByteArray("))
        assertFalse(updated.contains("bas64hash"))
        // 内存调优对齐（DiskCacheSize -1 → 16；IO 模式/水位线钉住）
        assertTrue(updated.contains("Session\\DiskCacheSize=16"))
        assertTrue(updated.contains("Session\\AsyncIOThreadsCount=2"))
        assertTrue(updated.contains("Session\\DiskIOReadMode=1"))
        assertTrue(updated.contains("Session\\SendBufferWatermark=256"))
        // DHT 引导节点对齐；DHTEnabled 保持用户选择（false，不强制覆盖）
        assertTrue(updated.contains("Session\\DHTBootstrapNodes="))
        assertTrue(updated.contains("Session\\DHTEnabled=false"))
        // 其他节与键原样保留
        assertTrue(updated.contains("Session\\Port=55599"))
        assertTrue(updated.contains("Cookie\\Name=keep"))
        // 代理键全部清理（升级迁移：旧 SOCKS5 方案残留，不清则 DHT/UDP 流量继续被丢弃）
        assertFalse(updated.contains("Proxy\\Type"))
        assertFalse(updated.contains("Proxy\\IP"))
        assertFalse(updated.contains("39001"))
        assertFalse(updated.contains("Proxy\\Profiles"))
        assertFalse(updated.contains("Session\\ProxyType"))
        assertFalse(updated.contains("WebUI\\Port=18085"))
        assertFalse(updated.contains("WebUI\\Address=127.0.0.1"))
    }

    @Test
    fun `更新无Preferences节的配置时创建该节`() {
        val existing = "[BitTorrent]\nSession\\Port=1\n"
        val updated = QBittorrentSpec.updateWebUiConfig(existing, webUiPort = 8085, lanAccess = false)
        assertTrue(updated.contains("[Preferences]"))
        assertTrue(updated.contains("WebUI\\Address=127.0.0.1"))
        assertTrue(updated.contains("WebUI\\Port=8085"))
        assertTrue(updated.contains("WebUI\\Username=admin"))
        assertTrue(updated.contains("Session\\Port=1"))
    }

    @Test
    fun `更新无BitTorrent节的配置时创建该节`() {
        val existing = "[Preferences]\nWebUI\\Port=1\n"
        val updated = QBittorrentSpec.updateWebUiConfig(existing, webUiPort = 8085, lanAccess = false)
        assertTrue(updated.contains("Session\\DiskCacheSize=16"))
        assertTrue(updated.contains("Session\\DHTBootstrapNodes="))
        assertTrue(updated.contains("WebUI\\Port=8085"))
        assertFalse(updated.contains("WebUI\\Port=1"))
    }
}
