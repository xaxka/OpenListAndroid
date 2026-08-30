package com.xaxka.openlist.qbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QBittorrentSpec 单测：配置种子与偏好 JSON 的生成契约。
 * 对照 qbittorrent-enhanced-nox 5.2.3.10 实测（profile/qBittorrent/config/qBittorrent.conf）：
 * - [Preferences] WebUI\Address/Port/LocalHostAuth/Username/Password_PBKDF2
 * - [BitTorrent] Session\DefaultSavePath
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
    fun `种子配置包含回环绑定与免认证与保存路径`() {
        val conf = QBittorrentSpec.buildSeedConfig(18085, "/data/user/0/app/files/dl")
        // WebUI 仅绑定回环 + 本机免认证（安全默认：局域网不可访问）
        assertTrue(conf.contains("WebUI\\Address=127.0.0.1"))
        assertTrue(conf.contains("WebUI\\Port=18085"))
        assertTrue(conf.contains("WebUI\\LocalHostAuth=false"))
        // 固定凭据：admin/adminadmin（qb 5.2 凭据为空时 WebUI 报错，必须随种子写入）
        assertTrue(conf.contains("WebUI\\Username=admin"))
        assertTrue(conf.contains("WebUI\\Password_PBKDF2=\"@ByteArray("))
        // 默认保存路径写入 Session\DefaultSavePath
        assertTrue(conf.contains("Session\\DefaultSavePath=/data/user/0/app/files/dl"))
    }

    @Test
    fun `偏好JSON仅含保存路径不携带代理字段`() {
        val json = QBittorrentSpec.buildSavePathPreferencesJson("/sdcard0/dl")
        // bionic 版 DNS 走系统原生，App 管理策略为无代理：不再下发任何代理字段
        assertTrue(json.contains("\"save_path\":\"/sdcard0/dl\""))
        assertFalse(json.contains("proxy"))
    }

    @Test
    fun `JSON转义处理反斜杠与引号`() {
        val json = QBittorrentSpec.buildSavePathPreferencesJson("a\"b\\c")
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
        // 两种模式都保持 localhost 免认证与固定凭据
        for (conf in listOf(local, lan)) {
            assertTrue(conf.contains("WebUI\\LocalHostAuth=false"))
            assertTrue(conf.contains("WebUI\\Port=8085"))
            assertTrue(conf.contains("WebUI\\Username=admin"))
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
            Session\ProxyType=SOCKS5

            [Network]
            Proxy\Type=SOCKS5
            Proxy\IP=127.0.0.1
            Proxy\Port=39001
            Proxy\Profiles\BitTorrent=true
            Cookie\Name=keep
        """.trimIndent()
        val updated = QBittorrentSpec.updateWebUiConfig(existing, webUiPort = 9090, lanAccess = true)

        // 绑定/端口替换，凭据强制对齐默认 admin/adminadmin（含旧哈希重置）
        assertTrue(updated.contains("WebUI\\Address=0.0.0.0"))
        assertTrue(updated.contains("WebUI\\Port=9090"))
        assertTrue(updated.contains("WebUI\\Username=admin"))
        assertTrue(updated.contains("WebUI\\Password_PBKDF2=\"@ByteArray("))
        assertFalse(updated.contains("bas64hash"))
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
}
