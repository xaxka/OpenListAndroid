package com.xaxka.openlist.data

import com.xaxka.openlist.data.log.LoggableLevel
import com.xaxka.openlist.data.log.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日志环形缓冲容量与快照行为 */
class LogBufferTest {

    @Test
    fun `初始为空`() {
        val buffer = LogBuffer()
        assertEquals(emptyList<Any>(), buffer.logs.value)
    }

    @Test
    fun `未超容量全部保留`() {
        val buffer = LogBuffer()
        repeat(100) { buffer.append(LoggableLevel.INFO, "T", "msg$it") }
        assertEquals(100, buffer.logs.value.size)
        assertEquals("msg0", buffer.logs.value.first().message)
        assertEquals("msg99", buffer.logs.value.last().message)
    }

    @Test
    fun `超过容量 500 挤出最旧`() {
        val buffer = LogBuffer()
        repeat(600) { buffer.append(LoggableLevel.DEBUG, "T", "msg$it") }
        val logs = buffer.logs.value
        assertEquals(LogBuffer.CAPACITY, logs.size)
        // 最旧的 100 条被挤出，首条应为 msg100
        assertEquals("msg100", logs.first().message)
        assertEquals("msg599", logs.last().message)
    }

    @Test
    fun `恰好容量不挤出`() {
        val buffer = LogBuffer()
        repeat(LogBuffer.CAPACITY) { buffer.append(LoggableLevel.WARN, "T", "msg$it") }
        assertEquals(LogBuffer.CAPACITY, buffer.logs.value.size)
        assertEquals("msg0", buffer.logs.value.first().message)
    }

    @Test
    fun `clear 清空全部`() {
        val buffer = LogBuffer()
        repeat(10) { buffer.append(LoggableLevel.ERROR, "T", "msg$it") }
        buffer.clear()
        assertEquals(0, buffer.logs.value.size)
    }

    @Test
    fun `字段完整落位`() {
        val buffer = LogBuffer()
        buffer.append(LoggableLevel.ERROR, "OpenListService", "启动失败")
        val log = buffer.logs.value.single()
        assertEquals(LoggableLevel.ERROR, log.level)
        assertEquals("OpenListService", log.tag)
        assertEquals("启动失败", log.message)
        assertTrue(log.time > 0)
    }
}
