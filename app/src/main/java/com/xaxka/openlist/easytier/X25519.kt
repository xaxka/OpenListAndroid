package com.xaxka.openlist.easytier

import java.math.BigInteger
import java.security.SecureRandom

/**
 * 纯 Kotlin X25519（RFC 7748）：安全模式节点密钥生成。
 *
 * 背景：EasyTier TOML 配置入口不执行 normalize_secure_mode_config（CLI/Web GUI 入口才有），
 * [secure_mode] enabled = true 时必须显式携带 local_private_key / local_public_key，
 * 否则核心报 "local private key is not set"。本类补齐 App 侧密钥生成能力。
 *
 * 实现：Montgomery 阶梯（BigInteger，255 轮），标量钳位与 u 坐标最高位掩码均按 RFC 7748。
 * 已对照 RFC 7748 §5.2 标量乘测试向量与 §6.1 Alice 密钥对向量验证。
 * minSdk 21 无系统 XDH（API 33 才有）且不引第三方库，故 BigInteger 实现足够：
 * 私钥仅由本机 SecureRandom 生成，不做常量时间防护（无外部可控标量输入）。
 */
object X25519 {

    /** 素域 p = 2^255 - 19。 */
    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /** Montgomery 曲线参数 (A-2)/4 = 121665。 */
    private val A24: BigInteger = BigInteger.valueOf(121665)

    /** 基点 u = 9（小端 32 字节）。 */
    private val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 生成本机私钥：32 字节 SecureRandom 随机 + RFC 7748 钳位。 */
    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val key = ByteArray(32)
        random.nextBytes(key)
        return clamp(key)
    }

    /** 由私钥派生公钥：X25519(priv, 基点 9)。 */
    fun publicFromPrivateKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        return scalarMult(privateKey, BASE_POINT)
    }

    /**
     * X25519 标量乘（RFC 7748 §5）：标量钳位 + u 坐标 bit255 掩码 + Montgomery 阶梯。
     * 输入输出均为 32 字节小端。
     */
    fun scalarMult(scalar: ByteArray, uCoordinate: ByteArray): ByteArray {
        require(scalar.size == 32 && uCoordinate.size == 32) { "inputs must be 32 bytes" }
        val k = decodeLittleEndian(clamp(scalar.copyOf()))
        val u = decodeLittleEndian(maskHighBit(uCoordinate.copyOf()))
        return encodeLittleEndian(montgomeryLadder(k, u))
    }

    /** RFC 7748 私钥钳位：k[0] &= 248; k[31] &= 127; k[31] |= 64。 */
    fun clamp(key: ByteArray): ByteArray {
        key[0] = (key[0].toInt() and 248).toByte()
        key[31] = (key[31].toInt() and 127 or 64).toByte()
        return key
    }

    /** u 坐标最高位（bit 255）掩码：RFC 7748 要求接收方忽略该位。 */
    private fun maskHighBit(u: ByteArray): ByteArray {
        u[31] = (u[31].toInt() and 0x7F).toByte()
        return u
    }

    /** Montgomery 阶梯（255 轮，bit254 → bit0），输入输出已 mod p。 */
    private fun montgomeryLadder(k: BigInteger, u: BigInteger): BigInteger {
        var x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            x3 = da.add(cb).mod(P)
            x3 = x3.multiply(x3).mod(P)
            val dacb = da.subtract(cb).mod(P)
            z3 = x1.multiply(dacb.multiply(dacb).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P)
        }
        if (swap == 1) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }
        // 归一化 z2^-1 = z2^(p-2) mod p（p 为素数）
        return x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P)
    }

    // ---------- 编解码 ----------

    /** 小端字节序 → 非负 BigInteger。 */
    private fun decodeLittleEndian(bytes: ByteArray): BigInteger {
        val reversed = ByteArray(bytes.size) { bytes[bytes.size - 1 - it] }
        return BigInteger(1, reversed)
    }

    /** 非负 BigInteger → 定长 32 字节小端（高位截断保护）。 */
    private fun encodeLittleEndian(value: BigInteger): ByteArray {
        val magnitude = value.toByteArray()
        val out = ByteArray(32)
        for (i in magnitude.indices) {
            val target = magnitude.size - 1 - i
            if (target < 32) out[target] = magnitude[i]
        }
        return out
    }

    /** 标准 Base64 编码（含填充）。32 字节 → 44 字符。 */
    fun encodeBase64(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(BASE64_ALPHABET[(n ushr 18) and 63])
                .append(BASE64_ALPHABET[(n ushr 12) and 63])
                .append(BASE64_ALPHABET[(n ushr 6) and 63])
                .append(BASE64_ALPHABET[n and 63])
            i += 3
        }
        val remaining = data.size - i
        if (remaining == 1) {
            val n = (data[i].toInt() and 0xFF) shl 16
            out.append(BASE64_ALPHABET[(n ushr 18) and 63])
                .append(BASE64_ALPHABET[(n ushr 12) and 63])
                .append("==")
        } else if (remaining == 2) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
            out.append(BASE64_ALPHABET[(n ushr 18) and 63])
                .append(BASE64_ALPHABET[(n ushr 12) and 63])
                .append(BASE64_ALPHABET[(n ushr 6) and 63])
                .append('=')
        }
        return out.toString()
    }

    /** 标准 Base64 解码；非法输入返回 null（存储损坏时由调用方重新生成密钥）。 */
    fun decodeBase64(text: String): ByteArray? {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length % 4 != 0) return null
        val out = ArrayList<Byte>(clean.length / 4 * 3)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val value = BASE64_ALPHABET.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
