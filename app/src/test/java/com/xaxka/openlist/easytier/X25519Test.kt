package com.xaxka.openlist.easytier

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X25519 单测：RFC 7748 §5.2 标量乘向量、§6.1 Alice 密钥对、钳位规则与 Base64 编解码。
 */
class X25519Test {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun `RFC7748 标量乘向量1`() {
        val scalar = unhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = unhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val result = X25519.scalarMult(scalar, u)
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            hex(result),
        )
    }

    @Test
    fun `RFC7748 标量乘向量2`() {
        // 该向量 u 坐标最高位为 1，验证 bit255 掩码处理
        val scalar = unhex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d")
        val u = unhex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
        val result = X25519.scalarMult(scalar, u)
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            hex(result),
        )
    }

    @Test
    fun `RFC7748 Alice 公钥派生`() {
        // §6.1：Alice 私钥 → 公钥（即 X25519(priv, 基点 9)）
        val alicePrivate = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePublic = X25519.publicFromPrivateKey(alicePrivate)
        assertEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            hex(alicePublic),
        )
    }

    @Test
    fun `私钥钳位规则`() {
        val raw = ByteArray(32) { 0xFF.toByte() }
        val clamped = X25519.clamp(raw.copyOf())
        // k[0] &= 248 → 0xF8；k[31] &= 127 | 64 → 0x7F
        assertEquals(0xF8.toByte(), clamped[0])
        assertEquals(0x7F.toByte(), clamped[31])
        // 已钳位值保持不变
        val again = X25519.clamp(clamped.copyOf())
        assertArrayEquals(clamped, again)
    }

    @Test
    fun `生成私钥满足钳位约束`() {
        repeat(16) {
            val key = X25519.generatePrivateKey()
            assertEquals(32, key.size)
            // k[0] 低 3 位清零；k[31] bit6 置位、bit7 清零
            assertEquals(0, key[0].toInt() and 0x07)
            assertTrue(key[31].toInt() and 0x40 != 0)
            assertEquals(0, key[31].toInt() and 0x80)
        }
    }

    @Test
    fun `相同私钥派生相同公钥`() {
        val private = X25519.generatePrivateKey()
        assertArrayEquals(
            X25519.publicFromPrivateKey(private),
            X25519.publicFromPrivateKey(private.copyOf()),
        )
    }

    @Test
    fun `不同私钥派生不同公钥`() {
        val a = X25519.publicFromPrivateKey(X25519.generatePrivateKey())
        val b = X25519.publicFromPrivateKey(X25519.generatePrivateKey())
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `私钥输入被钳位不影响派生`() {
        val raw = ByteArray(32) { 0xFF.toByte() }
        val clamped = X25519.clamp(raw.copyOf())
        // scalarMult 内部钳位：传入未钳位与已钳位结果一致
        assertArrayEquals(
            X25519.publicFromPrivateKey(clamped),
            X25519.publicFromPrivateKey(raw),
        )
    }

    @Test
    fun `base64编码32字节为44字符`() {
        val alicePrivate = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        assertEquals(44, X25519.encodeBase64(alicePrivate).length)
    }

    @Test
    fun `base64编解码往返一致`() {
        repeat(8) {
            val key = X25519.generatePrivateKey()
            val encoded = X25519.encodeBase64(key)
            val decoded = X25519.decodeBase64(encoded)
            assertNotNull(decoded)
            assertArrayEquals(key, decoded)
        }
    }

    @Test
    fun `base64非法输入返回null`() {
        assertNull(X25519.decodeBase64(""))
        assertNull(X25519.decodeBase64("abc"))
        assertNull(X25519.decodeBase64("a?cdefghijklmnopqrstuvwxyz0123456789+/AAAA"))
    }

    @Test
    fun `base64已知值编码`() {
        // 字节 0x01-0x10（16 字节）→ 标准向量
        val bytes = ByteArray(16) { i -> (i + 1).toByte() }
        assertEquals("AQIDBAUGBwgJCgsMDQ4PEA==", X25519.encodeBase64(bytes))
    }
}
