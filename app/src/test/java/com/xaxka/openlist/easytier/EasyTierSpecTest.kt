package com.xaxka.openlist.easytier

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EasyTier 模板与 RPC 载荷单测：
 * TOML（固定字段/可配置字段/空值回退/转义、启动配置不带端口转发）
 * PatchConfig 载荷（多端口 ADD/REMOVE、实例选择器、SocketAddr 编码）
 * 端口列表解析（去重/排序/越界过滤/多分隔符）
 */
class EasyTierSpecTest {

    private val json = Json { ignoreUnknownKeys = true }

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
        // 端口转发在 DHCP 分配虚拟 IP 后经 ConfigRpc.PatchConfig 动态追加
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
    fun `端口解析去重排序并过滤越界`() {
        assertEquals(listOf(80, 5244, 8080), EasyTierSpec.parsePorts("8080, 5244, 80, 5244"))
        assertEquals(listOf(5244, 8080), EasyTierSpec.parsePorts("5244，8080")) // 中文逗号
        assertEquals(listOf(5244), EasyTierSpec.parsePorts("0, 70000, abc, 5244")) // 越界/非法被过滤
        assertEquals(emptyList<Int>(), EasyTierSpec.parsePorts(""))
        assertEquals(emptyList<Int>(), EasyTierSpec.parsePorts("abc def"))
    }

    @Test
    fun `端口格式化以逗号空格连接`() {
        assertEquals("5244, 8080", EasyTierSpec.formatPorts(listOf(5244, 8080)))
    }

    @Test
    fun `单端口补丁载荷包含ADD规则与实例选择器`() {
        val payload = json.parseToJsonElement(
            EasyTierSpec.buildPortForwardPatchJson(
                removeAddr = 0L, removePorts = emptyList(),
                addAddr = 0x0A909002L, addPorts = listOf(5244)
            )
        ).jsonObject

        // 实例选择器按名称定位
        assertEquals(
            EasyTierSpec.INSTANCE_NAME,
            payload["instance"]!!.jsonObject["instance_selector"]!!.jsonObject["name"]!!.jsonPrimitive.content
        )

        val forwards = payload["patch"]!!.jsonObject["port_forwards"]!!.jsonArray
        assertEquals(1, forwards.size)
        val entry = forwards[0].jsonObject
        assertEquals("ADD", entry["action"]!!.jsonPrimitive.content)

        val cfg = entry["cfg"]!!.jsonObject
        val bind = cfg["bind_addr"]!!.jsonObject
        assertEquals(0x0A909002L, bind["ipv4"]!!.jsonObject["addr"]!!.jsonPrimitive.long)
        assertEquals(5244, bind["port"]!!.jsonPrimitive.long)

        val dst = cfg["dst_addr"]!!.jsonObject
        assertEquals(EasyTierSpec.LOOPBACK_ADDR, dst["ipv4"]!!.jsonObject["addr"]!!.jsonPrimitive.long)
        assertEquals(5244, dst["port"]!!.jsonPrimitive.long)
        assertEquals("TCP", cfg["socket_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `多端口各生成一条ADD规则`() {
        val payload = json.parseToJsonElement(
            EasyTierSpec.buildPortForwardPatchJson(
                removeAddr = 0L, removePorts = emptyList(),
                addAddr = 0x0A909002L, addPorts = listOf(5244, 8080, 8443)
            )
        ).jsonObject

        val forwards = payload["patch"]!!.jsonObject["port_forwards"]!!.jsonArray
        assertEquals(3, forwards.size)
        val ports = forwards.map {
            it.jsonObject["cfg"]!!.jsonObject["bind_addr"]!!.jsonObject["port"]!!.jsonPrimitive.long
        }
        assertEquals(listOf(5244L, 8080L, 8443L), ports)
    }

    @Test
    fun `IP变化时补丁先REMOVE旧绑定再ADD新绑定`() {
        val payload = json.parseToJsonElement(
            EasyTierSpec.buildPortForwardPatchJson(
                removeAddr = 0x0A909009L, removePorts = listOf(5244),
                addAddr = 0x0A909002L, addPorts = listOf(5244)
            )
        ).jsonObject

        val forwards = payload["patch"]!!.jsonObject["port_forwards"]!!.jsonArray
        assertEquals(2, forwards.size)

        val remove = forwards[0].jsonObject
        assertEquals("REMOVE", remove["action"]!!.jsonPrimitive.content)
        assertEquals(
            0x0A909009L,
            remove["cfg"]!!.jsonObject["bind_addr"]!!.jsonObject["ipv4"]!!.jsonObject["addr"]!!.jsonPrimitive.long
        )

        val add = forwards[1].jsonObject
        assertEquals("ADD", add["action"]!!.jsonPrimitive.content)
        assertEquals(
            0x0A909002L,
            add["cfg"]!!.jsonObject["bind_addr"]!!.jsonObject["ipv4"]!!.jsonObject["addr"]!!.jsonPrimitive.long
        )
    }
}
