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

    @Test
    fun `事件日志与本节点明细解析`() {
        val json = networkInfoJson(
            """
            {
              "running": true,
              "events": ["peer added: 2", "listener added: tcp://0.0.0.0:11010"],
              "my_node_info": {
                "virtual_ipv4": {"address": {"addr": $ipAddr}, "network_length": 24},
                "hostname": "openlist",
                "version": "2.6.4",
                "peer_id": 7,
                "listeners": [{"url": "tcp://0.0.0.0:11010"}],
                "stun_info": {
                  "udp_nat_type": "FullCone",
                  "tcp_nat_type": "Unknown",
                  "public_ip": ["1.2.3.4"]
                }
              }
            }
            """.trimIndent()
        )
        val info = EasyTierInfoParser.parse(json)!!
        assertEquals(listOf("peer added: 2", "listener added: tcp://0.0.0.0:11010"), info.events)
        val node = info.myNode!!
        assertEquals("openlist", node.hostname)
        assertEquals("2.6.4", node.version)
        assertEquals(7L, node.peerId)
        assertEquals("10.144.144.2", node.virtualIpv4)
        assertEquals(listOf("tcp://0.0.0.0:11010"), node.listeners)
        assertEquals("FullCone", node.stun?.udpNatType)
        assertEquals(listOf("1.2.3.4"), node.stun?.publicIps)
    }

    @Test
    fun `路由与对等连接明细解析`() {
        val json = networkInfoJson(
            """
            {
              "running": true,
              "peers": [{
                "peer_id": 2,
                "conns": [{
                  "peer_id": 2,
                  "tunnel": {
                    "tunnel_type": "udp",
                    "local_addr": {"url": "udp://192.168.1.2:50000"},
                    "remote_addr": {"url": "udp://192.168.1.3:50001"}
                  },
                  "stats": {"latency_us": 12000, "rx_bytes": 100},
                  "loss_rate": 0.01,
                  "is_client": true
                }]
              }],
              "routes": [{
                "peer_id": 2,
                "ipv4_addr": {"address": {"addr": 167969537}, "network_length": 24},
                "hostname": "phone",
                "next_hop_peer_id": 2,
                "cost": 1,
                "path_latency": 12,
                "version": "2.6.4"
              }]
            }
            """.trimIndent()
        )
        val info = EasyTierInfoParser.parse(json)!!
        assertEquals(1, info.peers.size)
        val conn = info.peers[0].conns[0]
        assertEquals("udp", conn.tunnelType)
        assertEquals("udp://192.168.1.3:50001", conn.remoteAddr)
        assertEquals(12L, conn.latencyMs)
        assertEquals(100L, conn.rxBytes)
        assertEquals(0L, conn.txBytes)
        assertEquals("", conn.connId)
        assertFalse(conn.isDirect)
        assertTrue(conn.isClient)

        assertEquals(1, info.routes.size)
        val route = info.routes[0]
        assertEquals("phone", route.hostname)
        assertEquals("10.3.3.1", route.ipv4)
        assertEquals(2L, route.peerId)
        assertEquals(1, route.cost)
        assertEquals(12, route.pathLatencyMs)
        assertEquals("2.6.4", route.version)
    }

    @Test
    fun `双栈链路无地址的连接remoteAddr归一为null`() {
        // pbjson 省略空串字段：remote_addr 为空 URL 时序列化为 {}，或字段整体缺失。
        // 空串若原样保留，Kotlin 渲染端 ?: 不兜底 → 双栈 IPv4/IPv6 行之间出现无地址空行。
        val json = networkInfoJson(
            """
            {
              "running": true,
              "peers": [{
                "peer_id": 2,
                "conns": [
                  {"conn_id": "a", "tunnel": {"tunnel_type": "tcp", "remote_addr": {"url": "tcp://1.2.3.4:11010"}}},
                  {"conn_id": "b", "tunnel": {"tunnel_type": "tcp", "remote_addr": {}}},
                  {"conn_id": "c", "tunnel": {"tunnel_type": "tcp"}},
                  {"conn_id": "d", "tunnel": {"tunnel_type": "udp", "remote_addr": {"url": "udp://[2408:8207::1]:11010"}}}
                ]
              }]
            }
            """.trimIndent()
        )
        val conns = EasyTierInfoParser.parse(json)!!.peers[0].conns
        assertEquals("tcp://1.2.3.4:11010", conns[0].remoteAddr)
        assertNull(conns[1].remoteAddr)
        assertNull(conns[2].remoteAddr)
        assertEquals("udp://[2408:8207::1]:11010", conns[3].remoteAddr)
    }

    @Test
    fun `空白监听与公网IP条目被丢弃不渲染空段`() {
        // 空串监听 URL 会导致「监听」多行文本出现空行；空串公网 IP 会导致逗号拼接出现空段
        val json = networkInfoJson(
            """
            {
              "running": true,
              "my_node_info": {
                "hostname": "openlist",
                "listeners": [{"url": ""}, {"url": "  "}, {"url": "ring://abc"}],
                "stun_info": {
                  "udp_nat_type": "FullCone",
                  "public_ip": ["", "1.2.3.4", "  ", "2408:8207::1"]
                }
              }
            }
            """.trimIndent()
        )
        val node = EasyTierInfoParser.parse(json)!!.myNode!!
        assertEquals(listOf("ring://abc"), node.listeners)
        assertEquals(listOf("1.2.3.4", "2408:8207::1"), node.stun!!.publicIps)
    }

    @Test
    fun `主机名内嵌换行被清洗避免断行渲染`() {
        val json = networkInfoJson(
            """
            {
              "running": true,
              "routes": [{
                "peer_id": 2,
                "hostname": "bad\nhost",
                "next_hop_peer_id": 2,
                "cost": 1
              }]
            }
            """.trimIndent()
        )
        val route = EasyTierInfoParser.parse(json)!!.routes[0]
        assertEquals("bad host", route.hostname)
    }

    @Test
    fun `listInstances结果判定实例存在`() {
        assertTrue(EasyTierInfoParser.containsInstance("""{"openlist":"some-uuid"}"""))
        assertFalse(EasyTierInfoParser.containsInstance("""{"other":"some-uuid"}"""))
        assertFalse(EasyTierInfoParser.containsInstance("not-json"))
        assertFalse(EasyTierInfoParser.containsInstance("{}"))
    }

    @Test
    fun `directly_connected_conns匹配conn_id判定P2P直连`() {
        // UUID{part1..4} = 0x11111111 22223333 44445555 55555555 → 与 conn_id 字符串一致
        val json = networkInfoJson(
            """
            {
              "running": true,
              "peers": [{
                "peer_id": 2,
                "conns": [{
                  "conn_id": "11111111-2222-3333-4444-555555555555",
                  "tunnel": {"tunnel_type": "udp"},
                  "stats": {"latency_us": 8000, "rx_bytes": 2048, "tx_bytes": 1024}
                }],
                "directly_connected_conns": [
                  {"part1": 286331153, "part2": 573676403, "part3": 1147421668, "part4": 1431655765}
                ]
              }]
            }
            """.trimIndent()
        )
        val conn = EasyTierInfoParser.parse(json)!!.peers[0].conns[0]
        assertTrue(conn.isDirect)
        assertEquals(2048L, conn.rxBytes)
        assertEquals(1024L, conn.txBytes)
    }

    @Test
    fun `中继连接不误判直连且UUID零值分片省略可还原`() {
        val json = networkInfoJson(
            """
            {
              "running": true,
              "peers": [{
                "peer_id": 2,
                "conns": [
                  {"conn_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeffff0000", "tunnel": {"tunnel_type": "tcp"}},
                  {"conn_id": "00000000-0000-0000-0000-00000000000a", "tunnel": {"tunnel_type": "udp"}}
                ],
                "directly_connected_conns": [{"part4": 10}]
              }]
            }
            """.trimIndent()
        )
        val conns = EasyTierInfoParser.parse(json)!!.peers[0].conns
        // 第一条不在直连集合内（中继）；第二条命中仅含 part4 的 UUID（pbjson 省略零值分片）
        assertFalse(conns[0].isDirect)
        assertTrue(conns[1].isDirect)
        assertEquals(0L, conns[0].rxBytes)
        assertEquals(0L, conns[0].txBytes)
    }

}
