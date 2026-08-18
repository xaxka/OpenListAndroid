package com.xaxka.openlist.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** httpPortOf 容错读取单测：正常值 / 缺文件 / 损坏 JSON / 缺 scheme / 字符串端口。 */
class CoreConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dataDir() = tmp.newFolder("data").absolutePath

    private fun writeConfig(content: String): String {
        val dir = dataDir()
        File(dir, "config.json").writeText(content)
        return dir
    }

    @Test
    fun `读取scheme中的http端口`() {
        val dir = writeConfig("""{"scheme":{"http_port":5245}}""")
        assertEquals(5245, CoreConfig.httpPortOf(dir))
    }

    @Test
    fun `文件不存在回退默认端口`() {
        assertEquals(CoreConfig.DEFAULT_HTTP_PORT, CoreConfig.httpPortOf(dataDir()))
    }

    @Test
    fun `损坏JSON回退默认端口`() {
        val dir = writeConfig("{ not valid json")
        assertEquals(CoreConfig.DEFAULT_HTTP_PORT, CoreConfig.httpPortOf(dir))
    }

    @Test
    fun `缺失scheme字段回退默认端口`() {
        val dir = writeConfig("""{"jwt_secret":"abc"}""")
        assertEquals(CoreConfig.DEFAULT_HTTP_PORT, CoreConfig.httpPortOf(dir))
    }

    @Test
    fun `带引号端口字符串可容错解析`() {
        val dir = writeConfig("""{"scheme":{"http_port":"5246"}}""")
        assertEquals(5246, CoreConfig.httpPortOf(dir))
    }

    @Test
    fun `未知字段不影响解析`() {
        val dir = writeConfig(
            """{"min_free_memory":-1,"unknown_key":{"a":1},"scheme":{"address":"0.0.0.0","http_port":5244}}"""
        )
        assertEquals(5244, CoreConfig.httpPortOf(dir))
    }
}
