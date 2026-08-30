package com.xaxka.openlist.qbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QBittorrentSpec 单测：配置种子与偏好 JSON 的生成契约。
 * 对照 qbittorrent-enhanced-nox 5.2.3.10 实测（profile/qBittorrent/config/qBittorrent.conf）：
 * - [Preferences] WebUI\Address/Port/LocalHostAuth/Username
 * - [BitTorrent] Session\DefaultSavePath
 * - setPreferences 接受 proxy_type 为字符串枚举 "SOCKS5"（数字 2 不生效）
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
    fun `种子配置包含回环绑定与免认证与保存路径`() {
        val conf = QBittorrentSpec.buildSeedConfig(18085, "/data/user/0/app/files/dl")
        // WebUI 仅绑定回环 + 本机免认证（安全默认：局域网不可访问）
        assertTrue(conf.contains("WebUI\\Address=127.0.0.1"))
        assertTrue(conf.contains("WebUI\\Port=18085"))
        assertTrue(conf.contains("WebUI\\LocalHostAuth=false"))
        // 默认保存路径写入 Session\DefaultSavePath
        assertTrue(conf.contains("Session\\DefaultSavePath=/data/user/0/app/files/dl"))
    }

    @Test
    fun `偏好JSON使用SOCKS5字符串枚举并携带域名解析`() {
        val json = QBittorrentSpec.buildPreferencesJson(39001, "/sdcard0/dl")
        // proxy_type 必须是字符串 "SOCKS5"（数字 2 在 5.2.3.10 不生效，实测）
        assertTrue(json.contains("\"proxy_type\":\"SOCKS5\""))
        assertTrue(json.contains("\"proxy_ip\":\"127.0.0.1\""))
        assertTrue(json.contains("\"proxy_port\":39001"))
        // 域名解析经代理（musl 无 resolv.conf，直连 DNS 必挂）
        assertTrue(json.contains("\"proxy_hostname_lookup\":true"))
        // peer 裸 IP 不走代理（降低转发开销）
        assertTrue(json.contains("\"proxy_peer_connections\":false"))
        assertTrue(json.contains("\"proxy_auth_enabled\":false"))
        assertTrue(json.contains("\"save_path\":\"/sdcard0/dl\""))
    }

    @Test
    fun `JSON转义处理反斜杠与引号`() {
        val json = QBittorrentSpec.buildPreferencesJson(1, "a\"b\\c")
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
        // 两种模式都保持 localhost 免认证与初始用户名
        for (conf in listOf(local, lan)) {
            assertTrue(conf.contains("WebUI\\LocalHostAuth=false"))
            assertTrue(conf.contains("WebUI\\Port=8085"))
            assertTrue(conf.contains("Session\\DefaultSavePath=$save"))
        }
    }

    @Test
    fun `更新既有配置保留其他键并替换WebUI键`() {
        val existing = """
            [BitTorrent]
            Session\DefaultSavePath=/old/path
            Session\Port=55599

            [Preferences]
            WebUI\Address=127.0.0.1
            WebUI\Port=18085
            WebUI\LocalHostAuth=false
            WebUI\Username=openlist
            WebUI\Password_PBKDF2="@ByteArray(bas64hash)"

            [Network]
            Proxy\Type=SOCKS5
        """.trimIndent()
        val updated = QBittorrentSpec.updateWebUiConfig(existing, webUiPort = 9090, username = "xaxka", lanAccess = true)

        // 三个键被替换/更新
        assertTrue(updated.contains("WebUI\\Address=0.0.0.0"))
        assertTrue(updated.contains("WebUI\\Port=9090"))
        assertTrue(updated.contains("WebUI\\Username=xaxka"))
        // 其他节与键原样保留（含 nox 持久化的密码哈希与代理设置）
        assertTrue(updated.contains("Session\\Port=55599"))
        assertTrue(updated.contains("WebUI\\Password_PBKDF2=\"@ByteArray(bas64hash)\""))
        assertTrue(updated.contains("Proxy\\Type=SOCKS5"))
        assertFalse(updated.contains("WebUI\\Port=18085"))
        assertFalse(updated.contains("WebUI\\Address=127.0.0.1"))
    }

    @Test
    fun `更新无Preferences节的配置时创建该节`() {
        val existing = "[BitTorrent]\nSession\\Port=1\n"
        val updated = QBittorrentSpec.updateWebUiConfig(existing, webUiPort = 8085, username = "admin", lanAccess = false)
        assertTrue(updated.contains("[Preferences]"))
        assertTrue(updated.contains("WebUI\\Address=127.0.0.1"))
        assertTrue(updated.contains("WebUI\\Port=8085"))
        assertTrue(updated.contains("Session\\Port=1"))
    }

    @Test
    fun `认证JSON包含用户名与明文密码`() {
        val json = QBittorrentSpec.buildAuthJson("admin", "s3cret\"pw")
        assertTrue(json.contains("\"web_ui_username\":\"admin\""))
        assertTrue(json.contains("\"web_ui_password\":\"s3cret\\\"pw\""))
    }
}
