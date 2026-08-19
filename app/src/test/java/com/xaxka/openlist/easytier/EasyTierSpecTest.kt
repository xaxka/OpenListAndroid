package com.xaxka.openlist.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** EasyTier TOML 模板单测：固定字段 / 可配置字段 / 空值回退 / 转义。 */
class EasyTierSpecTest {

    @Test
    fun `固定字段按需求模板生成`() {
        val toml = EasyTierSpec.buildToml("my-net", "secret", "tcp://192.168.1.10:11010")
        assertTrue(toml.contains("""instance_name = "${EasyTierSpec.INSTANCE_NAME}""""))
        assertTrue(toml.contains("""hostname = "openlist""""))
        assertTrue(toml.contains("dhcp = true"))
        assertTrue(toml.contains("[flags]"))
        assertTrue(toml.contains("no_tun = true"))
        assertTrue(toml.contains("[[port_forward]]"))
        assertTrue(toml.contains("""bind_addr = "10.144.144.2:5244""""))
        assertTrue(toml.contains("""dst_addr = "127.0.0.1:5244""""))
        assertTrue(toml.contains("""proto = "tcp""""))
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
}
