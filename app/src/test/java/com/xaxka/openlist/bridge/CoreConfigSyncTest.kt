package com.xaxka.openlist.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** config.json 偏好同步单测：-1 写入/移除、未知字段保留、损坏文件不抛异常。 */
class CoreConfigSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dataDir() = tmp.newFolder("data").absolutePath
    private fun configFile(dir: String) = java.io.File(dir, "config.json")
    private fun readMinFreeMemory(dir: String): Int? {
        val root = Json.parseToJsonElement(configFile(dir).readText()).jsonObject
        return (root["min_free_memory"] as? JsonPrimitive)?.intOrNull
    }

    @Test
    fun `config不存在且开启时创建仅含min_free_memory的配置`() {
        val dir = dataDir()
        val outcome = CoreConfigSync.syncNoMemoryCache(dir, noMemoryCache = true)

        assertEquals(CoreConfigSync.Outcome.UPDATED, outcome)
        assertEquals(-1, readMinFreeMemory(dir))
    }

    @Test
    fun `config不存在且关闭时不创建文件`() {
        val dir = dataDir()
        val outcome = CoreConfigSync.syncNoMemoryCache(dir, noMemoryCache = false)

        assertEquals(CoreConfigSync.Outcome.NO_CHANGE, outcome)
        assertFalse(configFile(dir).exists())
    }

    @Test
    fun `开启时写入-1且保留其他字段`() {
        val dir = dataDir()
        configFile(dir).writeText("""{"scheme":{"http_port":5244},"jwt_secret":"abc"}""")

        val outcome = CoreConfigSync.syncNoMemoryCache(dir, noMemoryCache = true)

        assertEquals(CoreConfigSync.Outcome.UPDATED, outcome)
        assertEquals(-1, readMinFreeMemory(dir))
        val root = Json.parseToJsonElement(configFile(dir).readText()).jsonObject
        assertEquals("abc", root["jwt_secret"]?.toString()?.trim('"'))
        assertTrue(root.toString().contains("5244"))
    }

    @Test
    fun `已是-1时开启为NO_CHANGE`() {
        val dir = dataDir()
        configFile(dir).writeText("""{"min_free_memory":-1}""")

        assertEquals(
            CoreConfigSync.Outcome.NO_CHANGE,
            CoreConfigSync.syncNoMemoryCache(dir, noMemoryCache = true),
        )
        assertEquals(-1, readMinFreeMemory(dir))
    }

    @Test
    fun `关闭时移除本应用写入的-1`() {
        val dir = dataDir()
        configFile(dir).writeText("""{"min_free_memory":-1,"jwt_secret":"abc"}""")

        val outcome = CoreConfigSync.syncNoMemoryCache(dir, noMemoryCache = false)

        assertEquals(CoreConfigSync.Outcome.UPDATED, outcome)
        assertEquals(null, readMinFreeMemory(dir))
        val root = Json.parseToJsonElement(configFile(dir).readText()).jsonObject
        assertTrue(root.containsKey("jwt_secret"))
    }

    @Test
    fun `关闭时保留用户手工配置的正值`() {
        val dir = dataDir()
        configFile(dir).writeText("""{"min_free_memory":64}""")

        assertEquals(
            CoreConfigSync.Outcome.NO_CHANGE,
            CoreConfigSync.syncNoMemoryCache(dir, noMemoryCache = false),
        )
        assertEquals(64, readMinFreeMemory(dir))
    }

    @Test
    fun `损坏的config返回FAILED不抛异常`() {
        val dir = dataDir()
        configFile(dir).writeText("{ not valid json")

        assertEquals(
            CoreConfigSync.Outcome.FAILED,
            CoreConfigSync.syncNoMemoryCache(dir, noMemoryCache = true),
        )
    }

    @Test
    fun `根节点非对象返回FAILED`() {
        val dir = dataDir()
        configFile(dir).writeText("[1,2,3]")

        assertEquals(
            CoreConfigSync.Outcome.FAILED,
            CoreConfigSync.syncNoMemoryCache(dir, noMemoryCache = true),
        )
    }
}
