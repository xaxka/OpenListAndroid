package com.xaxka.openlist.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EasyTier 模板单测：
 * TOML（固定字段/可配置字段/空值回退/转义、启动配置不带端口转发——no-tun 隐式直达本机同端口）
 */
class EasyTierSpecTest {

    @Test
    fun `固定字段按需求模板生成`() {
        val toml = EasyTierSpec.buildToml("my-net", "secret", "tcp://192.168.1.10:11010")
        assertTrue(toml.contains("""instance_name = "${EasyTierSpec.INSTANCE_NAME}""""))
        assertTrue(toml.contains("""hostname = "openlist""""))
        assertTrue(toml.contains("dhcp = true"))
        assertTrue(toml.contains("[flags]"))
        assertTrue(toml.contains("no_tun = true"))
    }

    @Test
    fun `启动配置不含端口转发段`() {
        // no-tun 隐式映射：发往虚拟 IP 的流量由核心代理直达本机回环同端口，无需端口转发规则
        val toml = EasyTierSpec.buildToml("my-net", "secret", "")
        assertFalse(toml.contains("[[port_forward]]"))
    }

    @Test
    fun `网络身份使用配置值`() {
        val toml = EasyTierSpec.buildToml("my-net", "s3cret", "")
        assertTrue(toml.contains("[network_identity]"))
        assertTrue(toml.contains("""network_name = "my-net""""))
        assertTrue(toml.contains("""network_secret = "s3cret""""))
    }

    @Test
    fun `空白网络名回退default`() {
        val toml = EasyTierSpec.buildToml("  ", "", "")
        assertTrue(toml.contains("network_name = \"default\""))
        assertTrue(toml.contains("network_secret = \"\""))
    }

    @Test
    fun `空白peer不生成peer段`() {
        val toml = EasyTierSpec.buildToml("net", "", "")
        assertFalse(toml.contains("[[peer]]"))
    }

    @Test
    fun `peer填写后生成peer段`() {
        val toml = EasyTierSpec.buildToml("net", "", "tcp://192.168.1.10:11010")
        assertTrue(toml.contains("[[peer]]"))
        assertTrue(toml.contains("""uri = "tcp://192.168.1.10:11010""""))
    }

    @Test
    fun `含引号与反斜杠的值被转义`() {
        val toml = EasyTierSpec.buildToml("""a"b\c""", "", "")
        assertTrue(toml.contains("""network_name = "a\"b\\c""""))
    }

    @Test
    fun `换行与控制字符被转义或编码`() {
        assertEquals("\"a\\nb\"", EasyTierSpec.tomlString("a\nb"))
        assertEquals("\"a\\rb\"", EasyTierSpec.tomlString("a\rb"))
        assertEquals("\"a\\u0001b\"", EasyTierSpec.tomlString("a\u0001b"))
    }

    @Test
    fun `中文与常规字符保持原样`() {
        assertEquals("\"内网-1\"", EasyTierSpec.tomlString("内网-1"))
    }

    @Test
    fun `uint32大端转点分十进制`() {
        assertEquals("127.0.0.1", EasyTierSpec.formatIpv4(0x7F000001L))
        assertEquals("10.144.144.2", EasyTierSpec.formatIpv4(0x0A909002L))
    }

    @Test
    fun `启用QUIC代理时flags写入enable_quic_proxy`() {
        // 对照 FlagsInConfig.enable_quic_proxy（proto 字段 24，snake_case）
        val on = EasyTierSpec.buildToml("net", "", "", enableQuicProxy = true)
        assertTrue(on.contains("enable_quic_proxy = true"))
        val off = EasyTierSpec.buildToml("net", "", "", enableQuicProxy = false)
        assertFalse(off.contains("enable_quic_proxy"))
    }

    @Test
    fun `QUIC代理参数缺省为关闭`() {
        val toml = EasyTierSpec.buildToml("net", "", "")
        assertFalse(toml.contains("enable_quic_proxy"))
    }

    @Test
    fun `启用安全模式时写入secure_mode段`() {
        // 对照官方文档 secure-mode：[secure_mode] enabled = true
        val on = EasyTierSpec.buildToml("net", "", "", secureMode = true)
        assertTrue(on.contains("[secure_mode]"))
        assertTrue(on.contains("enabled = true"))
    }

    @Test
    fun `安全模式缺省与显式关闭均不写入secure_mode段`() {
        // 缺省关闭：保持旧网络兼容（安全模式客户端连不上旧服务端）
        assertFalse(EasyTierSpec.buildToml("net", "", "").contains("secure_mode"))
        assertFalse(EasyTierSpec.buildToml("net", "", "", secureMode = false).contains("secure_mode"))
    }

    @Test
    fun `安全模式携带节点密钥时写入密钥字段`() {
        // TOML 入口无 normalize_secure_mode_config，密钥必须显式携带
        val toml = EasyTierSpec.buildToml(
            "net", "s3cret", "",
            secureMode = true,
            localPrivateKey = "priv-base64-32bytes",
            localPublicKey = "pub-base64-32bytes",
        )
        assertTrue(toml.contains("""local_private_key = "priv-base64-32bytes""""))
        assertTrue(toml.contains("""local_public_key = "pub-base64-32bytes""""))
        // 字段顺序：enabled 在前
        assertTrue(toml.indexOf("enabled = true") < toml.indexOf("local_private_key"))
    }

    @Test
    fun `安全模式密钥缺省不写入密钥字段`() {
        val toml = EasyTierSpec.buildToml("net", "", "", secureMode = true)
        assertFalse(toml.contains("local_private_key"))
        assertFalse(toml.contains("local_public_key"))
    }

    @Test
    fun `安全模式空白密钥不写入密钥字段`() {
        val toml = EasyTierSpec.buildToml(
            "net", "", "",
            secureMode = true,
            localPrivateKey = "  ",
            localPublicKey = "",
        )
        assertFalse(toml.contains("local_private_key"))
        assertFalse(toml.contains("local_public_key"))
    }

    @Test
    fun `安全模式与QUIC代理可同时开启`() {
        val toml = EasyTierSpec.buildToml("net", "s3cret", "tcp://1.2.3.4:11010", enableQuicProxy = true, secureMode = true)
        assertTrue(toml.contains("[secure_mode]"))
        assertTrue(toml.contains("enabled = true"))
        assertTrue(toml.contains("enable_quic_proxy = true"))
        assertTrue(toml.contains("""uri = "tcp://1.2.3.4:11010""""))
    }

    @Test
    fun `展示配置对密钥脱敏`() {
        val display = EasyTierSpec.buildDisplayToml("net", "s3cret", "", enableQuicProxy = true)
        assertFalse(display.contains("s3cret"))
        assertTrue(display.contains("\"********\""))
        // 空白密钥保持空白（显示为 network_secret = ""）
        val displayBlank = EasyTierSpec.buildDisplayToml("net", "", "", enableQuicProxy = false)
        assertTrue(displayBlank.contains("network_secret = \"\""))
        // 安全模式状态在展示配置中原样透出
        val displaySecure = EasyTierSpec.buildDisplayToml("net", "s3cret", "", enableQuicProxy = false, secureMode = true)
        assertTrue(displaySecure.contains("[secure_mode]"))
        assertFalse(displaySecure.contains("s3cret"))
    }

    @Test
    fun `展示配置对安全模式私钥脱敏而公钥原样展示`() {
        // 私钥脱敏防落屏；公钥可公开，保留便于排查组网问题
        val display = EasyTierSpec.buildDisplayToml(
            "net", "s3cret", "",
            enableQuicProxy = false,
            secureMode = true,
            localPrivateKey = "priv-secret-base64",
            localPublicKey = "pub-open-base64",
        )
        assertTrue(display.contains("""local_private_key = "********""""))
        assertTrue(display.contains("""local_public_key = "pub-open-base64""""))
        assertFalse(display.contains("priv-secret-base64"))
        // 空白私钥保持空白（不显示占位符）
        val displayBlankKey = EasyTierSpec.buildDisplayToml(
            "net", "", "", enableQuicProxy = false,
            secureMode = true, localPrivateKey = "", localPublicKey = "",
        )
        assertFalse(displayBlankKey.contains("local_private_key"))
    }
}
