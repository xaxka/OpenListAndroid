package com.xaxka.openlist.qbt

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 本机 SOCKS5 代理（无鉴权，仅监听 127.0.0.1 的**随机高位端口**，不涉及 53 端口）。
 *
 * 背景与选型说明：qbittorrent-enhanced-nox 为 musl 静态二进制，自行读 /etc/resolv.conf
 * 解析域名，而 Android 上无此文件（/etc 只读）——曾考虑的替代方案「自建 DNS 服务」
 * 需监听 53 端口，但 <1024 为特权端口（需 root），不可行。最终方案即本类：
 * SOCKS5 代理绑定随机端口（ServerSocket(0)），接收 nox 的 SOCKS5h 请求
 * （ATYP=域名），域名由 Android 系统解析（InetAddress → bionic → netd，
 * 走当前网络的系统 DNS 且自动跟随 VPN/私网），再以普通 socket 直连目标；
 * nox 侧配置 proxy_hostname_lookup 后所有域名解析均经此通道。
 *
 * 线程模型：每连接两个小栈工作线程（128KB，DNS 阻塞解析必须独立线程）；
 * 转发量级仅域名类流量（tracker/DHT/RSS），peer 裸 IP 由 nox 直连不经代理。
 */
class LocalSocks5Proxy {

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    private val tracked = CopyOnWriteArraySet<Socket>()

    /** 实际监听端口（[start] 成功后有效；未启动为 null）。 */
    val port: Int?
        get() = server?.localPort

    val isRunning: Boolean
        get() = server?.isClosed == false

    /** 启动监听（重复调用幂等：已运行直接返回）。失败抛 [IOException]。 */
    @Synchronized
    fun start() {
        if (isRunning) return
        val sock = ServerSocket()
        try {
            sock.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 128)
            sock.reuseAddress = true
        } catch (e: IOException) {
            runCatching { sock.close() }
            throw e
        }
        server = sock
        acceptThread = Thread({ acceptLoop(sock) }, "qbt-socks5-accept").apply {
            isDaemon = true
            start()
        }
    }

    /** 停止监听并断开全部现存连接（nox 退出路径上一并调用，防 fd 泄漏）。 */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        tracked.forEach { runCatching { it.close() } }
        tracked.clear()
    }

    private fun acceptLoop(sock: ServerSocket) {
        while (!sock.isClosed) {
            val client = try {
                sock.accept()
            } catch (e: IOException) {
                // server 关闭（stop）或异常：退出循环
                break
            }
            tracked.add(client)
            // 每连接独立线程（128KB 小栈，DNS 阻塞解析必须与其他连接并行）
            Thread(null, { handle(client) }, "qbt-socks5-conn", STACK_SIZE).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(client: Socket) {
        var target: Socket? = null
        try {
            client.use { c ->
                c.tcpNoDelay = true
                val input = c.getInputStream()
                val output = c.getOutputStream()

                // --- 握手：VER NMETHODS METHODS... → 仅支持无鉴权 05 00 ---
                val ver = input.read()
                if (ver != 0x05) return
                val nMethods = input.read()
                if (nMethods <= 0) return
                val methods = ByteArray(nMethods)
                readFully(input, methods)
                if (0x00 !in methods) {
                    output.write(byteArrayOf(0x05, 0xFF.toByte()))
                    output.flush()
                    return
                }
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // --- 请求：VER CMD RSV ATYP DST.ADDR DST.PORT ---
                if (input.read() != 0x05) return
                val cmd = input.read()
                if (input.read() < 0) return // RSV
                val atyp = input.read()
                val host: String = when (atyp) {
                    0x01 -> { // IPv4
                        val addr = ByteArray(4)
                        readFully(input, addr)
                        addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    }
                    0x03 -> { // 域名（SOCKS5h：由本端解析）
                        val len = input.read()
                        if (len <= 0) return
                        val domain = ByteArray(len)
                        readFully(input, domain)
                        String(domain, Charsets.US_ASCII)
                    }
                    0x04 -> { // IPv6
                        val addr = ByteArray(16)
                        readFully(input, addr)
                        formatIpv6(addr)
                    }
                    else -> return
                }
                val portBytes = ByteArray(2)
                readFully(input, portBytes)
                val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

                if (cmd != 0x01) { // 仅 CONNECT
                    reply(output, 0x07)
                    return
                }

                // --- 域名经系统解析（bionic → netd，自动跟随 VPN/当前网络） ---
                target = Socket()
                try {
                    val address = InetAddress.getAllByName(host).firstOrNull()
                        ?: throw IOException("no address for $host")
                    target.tcpNoDelay = true
                    target.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                } catch (e: Exception) {
                    runCatching { target.close() }
                    reply(output, if (e is IOException && e.message?.startsWith("no address") == true) 0x04 else 0x05)
                    return
                }
                tracked.add(target)

                reply(output, 0x00) // 成功
                target.tcpNoDelay = true
                // 双向转发必须并行：单线程串行 pump 会先堵死一个方向（对端无 EOF 永不切换）
                val up = Thread(null, { pump(c.getInputStream(), target.getOutputStream()) }, "qbt-socks5-up", STACK_SIZE).apply {
                    isDaemon = true
                }
                up.start()
                pump(target.getInputStream(), c.getOutputStream())
                up.join(2_000)
            }
        } catch (_: IOException) {
            // 连接中断：静默结束（客户端断开属常态）
        } finally {
            runCatching { target?.close() }
            tracked.remove(target)
            runCatching { client.close() }
            tracked.remove(client)
        }
    }

    /** 单向流转发（读端 EOF/异常时双向关闭由 use/finally 兜底）。 */
    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                if (n > 0) {
                    to.write(buf, 0, n)
                    to.flush()
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun reply(output: OutputStream, rep: Int) {
        // VER REP RSV ATYP=IPv4 BND.ADDR=0.0.0.0 BND.PORT=0
        output.write(byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw IOException("unexpected EOF")
            offset += n
        }
    }

    private fun formatIpv6(bytes: ByteArray): String {
        // 8 组 16bit 十六进制（未压缩的规范形式，足够 InetSocketAddress 使用）
        return (0 until 8).joinToString(":") {
            val hi = bytes[it * 2].toInt() and 0xFF
            val lo = bytes[it * 2 + 1].toInt() and 0xFF
            ((hi shl 8) or lo).toString(16)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val STACK_SIZE = 128L * 1024
    }
}
