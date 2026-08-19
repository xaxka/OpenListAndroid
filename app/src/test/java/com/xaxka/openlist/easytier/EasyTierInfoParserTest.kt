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
    fun `listInstances结果判定实例存在`() {
        assertTrue(EasyTierInfoParser.containsInstance("""{"openlist":"some-uuid"}"""))
        assertFalse(EasyTierInfoParser.containsInstance("""{"other":"some-uuid"}"""))
        assertFalse(EasyTierInfoParser.containsInstance("not-json"))
        assertFalse(EasyTierInfoParser.containsInstance("{}"))
    }

    @Test
    fun `端口转发对账仅统计绑定虚拟IP且指向回环同端口的规则`() {
        val virtualIp = 0x0A909002L // 10.144.144.2
        val loopback = EasyTierSpec.LOOPBACK_ADDR // 127.0.0.1
        val json = """
            {"cfgs":[
              {"bind_addr":{"ipv4":{"addr":$virtualIp},"port":5244},
               "dst_addr":{"ipv4":{"addr":$loopback},"port":5244},"socket_type":"TCP"},
              {"bind_addr":{"ipv4":{"addr":$virtualIp},"port":8080},
               "dst_addr":{"ipv4":{"addr":$loopback},"port":8080},"socket_type":"TCP"},
              {"bind_addr":{"ipv4":{"addr":$virtualIp},"port":9000},
               "dst_addr":{"ipv4":{"addr":$loopback},"port":9001},"socket_type":"TCP"},
              {"bind_addr":{"ipv4":{"addr":1886734345},"port":5244},
               "dst_addr":{"ipv4":{"addr":$loopback},"port":5244},"socket_type":"TCP"}
            ]}
        """.trimIndent()
        // 第 3 条 bind/dst 端口不一致、第 4 条绑定在其他 IP，均不计入
        assertEquals(listOf(5244, 8080), EasyTierInfoParser.parseForwardedPorts(json, virtualIp))
    }

    @Test
    fun `端口转发空列表与非法JSON`() {
        assertEquals(emptyList<Int>(), EasyTierInfoParser.parseForwardedPorts("""{"cfgs":[]}""", 1L))
        assertNull(EasyTierInfoParser.parseForwardedPorts("not-json", 1L))
    }
}
