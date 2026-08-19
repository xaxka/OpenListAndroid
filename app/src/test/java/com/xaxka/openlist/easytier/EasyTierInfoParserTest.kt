package com.xaxka.openlist.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EasyTierInfoParser 单测：collectNetworkInfos 返回 JSON 的解析契约。
 *
 * 载荷结构对照 EasyTier easytier-proto（pbjson，preserve_proto_field_names → snake_case）：
 * NetworkInstanceRunningInfoMap { map<instance_name, NetworkInstanceRunningInfo> }；
 * NetworkInstanceRunningInfo { running, error_msg, my_node_info, peers }；
 * MyNodeInfo.virtual_ipv4 = Ipv4Inet { address: Ipv4Addr { addr: uint32 大端 } }。
 */
class EasyTierInfoParserTest {

    /** 地址 10.144.144.2 的 uint32 大端表示。 */
    private val ipAddr = 0x0A909002L

    private fun networkInfoJson(entryBody: String): String =
        """{"map":{"openlist":$entryBody}}"""

    @Test
    fun `运行中且已分配虚拟IP时解析出IP与节点数`() {
        val json = networkInfoJson(
            """
            {
              "dev_name": "",
              "my_node_info": {
                "virtual_ipv4": {"address": {"addr": $ipAddr}, "network_length": 24},
                "hostname": "openlist",
                "version": "2.0.0",
                "peer_id": 7
              },
              "peers": [{"peer_id": 2}, {"peer_id": 3}],
              "running": true
            }
            """.trimIndent()
        )
        val info = EasyTierInfoParser.parse(json)
        assertEquals(true, info?.running)
        assertEquals("10.144.144.2", info?.ipv4)
        assertEquals(ipAddr, info?.ipv4Addr)
        assertEquals(2, info?.peerCount)
        assertEquals("", info?.errorMsg)
    }

    @Test
    fun `已组网但DHCP未分配IP时ipv4为空且节点数可见`() {
        val json = networkInfoJson(
            """
            {
              "my_node_info": {"hostname": "openlist", "peer_id": 7},
              "peers": [{"peer_id": 2}],
              "running": true
            }
            """.trimIndent()
        )
        val info = EasyTierInfoParser.parse(json)
        assertEquals(true, info?.running)
        assertNull(info?.ipv4)
        assertNull(info?.ipv4Addr)
        assertEquals(1, info?.peerCount)
    }

    @Test
    fun `camelCase字段名兜底解析`() {
        val json = """
            {"map":{"openlist":{
              "myNodeInfo": {"virtualIpv4": {"address": {"addr": $ipAddr}}},
              "running": true
            }}}
        """.trimIndent()
        val info = EasyTierInfoParser.parse(json)
        assertEquals("10.144.144.2", info?.ipv4)
    }

    @Test
    fun `运行字段缺省按未运行处理`() {
        // proto3 默认值省略：running=false 时 pbjson 不输出该字段
        val json = networkInfoJson("""{"error_msg": "peer unreachable"}""")
        val info = EasyTierInfoParser.parse(json)
        assertFalse(info!!.running)
        assertEquals("peer unreachable", info.errorMsg)
        assertNull(info.ipv4)
    }

    @Test
    fun `实例不在map中返回null`() {
        val json = """{"map":{"other-instance":{"running":true}}}"""
        assertNull(EasyTierInfoParser.parse(json))
    }

    @Test
    fun `非法JSON与空map返回null`() {
        assertNull(EasyTierInfoParser.parse("not-json"))
        assertNull(EasyTierInfoParser.parse("{}"))
        assertNull(EasyTierInfoParser.parse("""{"map":{}}"""))
        assertNull(EasyTierInfoParser.parse(""))
    }

    @Test
    fun `实例名可自定义定位`() {
        val json = """{"map":{"openlist":{"running":true}}}"""
        assertTrue(EasyTierInfoParser.parse(json, "openlist")!!.running)
        assertNull(EasyTierInfoParser.parse(json, "another"))
    }
}
