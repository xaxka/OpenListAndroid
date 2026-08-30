package com.xaxka.openlist.qbt

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LocalSocks5Proxy 单测（纯 JVM）：SOCKS5 无鉴权握手 + 域名（SOCKS5h）经代理穿透转发。
 * 契约：nox（musl 静态）的 tracker 域名解析完全依赖该通道，atyp=domain 必须支持。
 */
class LocalSocks5ProxyTest {

    /** 本地回显服务：收到多少字节原样回多少，用于验证双向转发。 */
    private fun startEchoServer(): Pair<ServerSocket, Int> {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use { c ->
                        val input: InputStream = c.getInputStream()
                        val output: OutputStream = c.getOutputStream()
                        val buf = ByteArray(4096)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            output.flush()
                        }
                    }
                }
            }
        }
        return server to server.localPort
    }

    /** SOCKS5 握手 + 发送 payload + 读回显（atyp=域名走 SOCKS5h 语义）。 */
    private fun roundTrip(proxy: LocalSocks5Proxy, host: String, port: Int, payload: String): String {
        val portValue = requireNotNull(proxy.port) { "proxy not started" }
        Socket("127.0.0.1", portValue).use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            // greeting: VER=5 NMETHODS=1 METHOD=0(无鉴权)
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greet = ByteArray(2)
            readFully(input, greet)
            assertEquals(0x05, greet[0].toInt())
            assertEquals(0x00, greet[1].toInt())

            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            // request: VER=5 CMD=1 RSV=0 ATYP=3 LEN HOST PORT
            val req = ByteArray(4 + 1 + hostBytes.size + 2)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
            req[4] = hostBytes.size.toByte()
            hostBytes.copyInto(req, 5)
            req[req.size - 2] = ((port shr 8) and 0xFF).toByte()
            req[req.size - 1] = (port and 0xFF).toByte()
            output.write(req)
            output.flush()
            // reply: VER REP RSV ATYP + 4B addr + 2B port（IPv4 固定 10 字节）
            val reply = ByteArray(10)
            readFully(input, reply)
            assertEquals(0x05, reply[0].toInt())
            assertEquals(0x00, reply[1].toInt(), "REP 应为 0（成功），实际 ${reply[1]}")

            val bytes = payload.toByteArray(Charsets.UTF_8)
            output.write(bytes)
            output.flush()
            val echoed = ByteArray(bytes.size)
            readFully(input, echoed)
            return String(echoed, Charsets.UTF_8)
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw java.io.IOException("unexpected EOF")
            offset += n
        }
    }

    @Test
    fun `域名形式请求经系统解析转发成功`() {
        val (echo, echoPort) = startEchoServer()
        val proxy = LocalSocks5Proxy()
        try {
            proxy.start()
            // 用域名形式（localhost）——正是 nox 发 tracker 域名的方式（SOCKS5h）
            val echoed = roundTrip(proxy, "localhost", echoPort, "hello-qbittorrent")
            assertEquals("hello-qbittorrent", echoed)
        } finally {
            proxy.stop()
            echo.close()
        }
    }

    @Test
    fun `IPv4地址形式同样转发成功`() {
        val (echo, echoPort) = startEchoServer()
        val proxy = LocalSocks5Proxy()
        try {
            proxy.start()
            val echoed = roundTrip(proxy, "127.0.0.1", echoPort, "ip-direct")
            assertEquals("ip-direct", echoed)
        } finally {
            proxy.stop()
            echo.close()
        }
    }

    @Test
    fun `停止后可再启动且端口有效`() {
        val proxy = LocalSocks5Proxy()
        proxy.start()
        val first = proxy.port
        assertTrue(first != null && first > 0)
        proxy.stop()
        // 再启动（端口重新分配）
        proxy.start()
        val second = proxy.port
        assertTrue(second != null && second > 0)
        proxy.stop()
    }
}
