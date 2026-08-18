package com.xaxka.openlist.video

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * 视频洗码处理器单测：按 tmp/test/video_hash_test.dart 用例移植，
 * 并覆盖 scan/restore 全流程、防重复与记录清理（FEATURE_MATRIX §8.2 验收点）。
 */
class VideoHashProcessorTest {

    private lateinit var tempDir: File
    private val store = InMemoryRecordStore()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("video_hash_test_").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ---------- 扩展名识别 ----------

    @Test
    fun `isVideoFile 应识别常见视频扩展名`() {
        val extensions = listOf(
            "mp4", "mkv", "avi", "flv", "mov", "wmv", "ts", "m4v",
            "rmvb", "rm", "3gp", "webm", "vob", "f4v", "mpeg", "mpg"
        )
        extensions.forEach { ext ->
            assertTrue("扩展名 $ext 应被识别为视频", VideoHashProcessor.isVideoFile(File("/sdcard/video.$ext")))
        }
    }

    @Test
    fun `isVideoFile 应忽略非视频扩展名`() {
        listOf("txt", "jpg", "pdf", "zip").forEach { ext ->
            assertFalse("扩展名 $ext 不应被识别为视频", VideoHashProcessor.isVideoFile(File("/sdcard/file.$ext")))
        }
        assertFalse("无扩展名文件不应被识别为视频", VideoHashProcessor.isVideoFile(File("/sdcard/file")))
    }

    @Test
    fun `isVideoFile 应支持大写扩展名`() {
        assertTrue(VideoHashProcessor.isVideoFile(File("/sdcard/VIDEO.MP4")))
        assertTrue(VideoHashProcessor.isVideoFile(File("/sdcard/Video.Mkv")))
    }

    // ---------- 追加文字编码 ----------

    @Test
    fun `ASCII 文字应按 UTF-8 编码为 7 字节`() {
        val bytes = "HashMod".toByteArray(Charsets.UTF_8)
        assertArrayEquals(byteArrayOf(72, 97, 115, 104, 77, 111, 100), bytes)
    }

    @Test
    fun `中文文字应按 UTF-8 编码为 6 字节`() {
        // "测" = E6 B5 8B, "试" = E8 AF 95
        val bytes = "测试".toByteArray(Charsets.UTF_8)
        assertEquals(6, bytes.size)
        assertEquals(0xE6.toByte(), bytes[0])
        assertEquals(0xB5.toByte(), bytes[1])
        assertEquals(0x8B.toByte(), bytes[2])
        assertEquals(0xE8.toByte(), bytes[3])
        assertEquals(0xAF.toByte(), bytes[4])
        assertEquals(0x95.toByte(), bytes[5])
    }

    // ---------- 追加 / 检测 / 还原 ----------

    @Test
    fun `appendSuffix 应在文件尾部追加字节`() {
        val file = File(tempDir, "test.mp4")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val suffix = byteArrayOf(72, 97, 115, 104, 77, 111, 100) // "HashMod"
        VideoHashProcessor.appendSuffix(file, suffix)

        val content = file.readBytes()
        assertEquals(12, content.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), content.copyOfRange(0, 5))
        assertArrayEquals(suffix, content.copyOfRange(5, content.size))
    }

    @Test
    fun `isAlreadyProcessed 应检测已处理的文件`() {
        val file = File(tempDir, "test.mp4")
        val original = ByteArray(1000) { (it % 256).toByte() }
        val suffix = "HashMod".toByteArray()
        file.writeBytes(original)
        VideoHashProcessor.appendSuffix(file, suffix)

        assertTrue(VideoHashProcessor.isAlreadyProcessed(file, suffix))
    }

    @Test
    fun `isAlreadyProcessed 应检测未处理的文件`() {
        val file = File(tempDir, "test.mp4")
        file.writeBytes(ByteArray(1000) { (it % 256).toByte() })

        assertFalse(VideoHashProcessor.isAlreadyProcessed(file, "HashMod".toByteArray()))
    }

    @Test
    fun `isAlreadyProcessed 文件长度小于后缀长度时返回 false`() {
        val file = File(tempDir, "small.mp4")
        file.writeBytes(byteArrayOf(1, 2))

        assertFalse(VideoHashProcessor.isAlreadyProcessed(file, "HashMod".toByteArray()))
    }

    @Test
    fun `isAlreadyProcessed 空后缀返回 false`() {
        val file = File(tempDir, "test.mp4")
        file.writeBytes(byteArrayOf(1, 2, 3))

        assertFalse(VideoHashProcessor.isAlreadyProcessed(file, ByteArray(0)))
    }

    @Test
    fun `完整流程 追加后检测为已处理且原始数据不变`() {
        val file = File(tempDir, "flow_test.mp4")
        val original = ByteArray(5000) { (it % 256).toByte() }
        val suffix = "HashMod".toByteArray()
        file.writeBytes(original)

        assertFalse(VideoHashProcessor.isAlreadyProcessed(file, suffix))

        VideoHashProcessor.appendSuffix(file, suffix)

        assertTrue(VideoHashProcessor.isAlreadyProcessed(file, suffix))

        val content = file.readBytes()
        assertArrayEquals(original, content.copyOfRange(0, 5000))
        assertArrayEquals(suffix, content.copyOfRange(5000, content.size))
    }

    @Test
    fun `大文件追加不应读取整个文件到内存`() {
        val file = File(tempDir, "large.mp4")
        val largeData = ByteArray(1024 * 1024) { (it % 256).toByte() } // 1MB
        file.writeBytes(largeData)

        val suffix = "HashMod".toByteArray()
        VideoHashProcessor.appendSuffix(file, suffix)

        assertEquals((1024 * 1024 + suffix.size).toLong(), file.length())
        assertTrue(VideoHashProcessor.isAlreadyProcessed(file, suffix))

        // 头部 100 字节保持不变
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(100)
            raf.seek(0)
            raf.readFully(head)
            assertArrayEquals(largeData.copyOfRange(0, 100), head)
        }
    }

    @Test
    fun `removeSuffix 尾部匹配时截断还原`() {
        val file = File(tempDir, "restore.mp4")
        val original = ByteArray(2048) { (it % 256).toByte() }
        val suffix = "HashMod".toByteArray()
        file.writeBytes(original)
        VideoHashProcessor.appendSuffix(file, suffix)

        assertTrue(VideoHashProcessor.removeSuffix(file, suffix))
        assertEquals(original.size.toLong(), file.length())
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `removeSuffix 尾部不匹配时不截断`() {
        val file = File(tempDir, "restore_mismatch.mp4")
        val original = ByteArray(2048) { (it % 256).toByte() }
        file.writeBytes(original)

        assertFalse(VideoHashProcessor.removeSuffix(file, "HashMod".toByteArray()))
        assertEquals(original.size.toLong(), file.length())
    }

    // ---------- 前置校验 ----------

    @Test
    fun `scanAndProcess 目录为空时返回未设置监听目录`() {
        val result = VideoHashProcessor.scanAndProcess(store, emptyList(), "HashMod")
        assertEquals("未设置监听目录", result)
    }

    @Test
    fun `scanAndProcess 后缀为空时返回提示`() {
        val result = VideoHashProcessor.scanAndProcess(store, listOf(tempDir.absolutePath), " ")
        assertEquals("追加文字为空，请先设置", result)
    }

    // ---------- scan / restore 全流程 ----------

    @Test
    fun `scanAndProcess 处理稳定文件且二次扫描防重复`() {
        val file = File(tempDir, "video.mp4")
        val original = ByteArray(4096) { (it % 256).toByte() }
        file.writeBytes(original)
        val suffix = "HashMod"

        // 第一次扫描：存在候选 → 3s 稳定确认后追加
        val first = VideoHashProcessor.scanAndProcess(store, listOf(tempDir.absolutePath), suffix)
        assertTrue(first.contains("已处理: 1 个文件"))
        assertEquals((original.size + suffix.length).toLong(), file.length())
        assertEquals((original.size + suffix.length).toLong(), store.map[file.absolutePath])

        // 第二次扫描：记录命中（路径+大小）→ 跳过，不再追加
        val second = VideoHashProcessor.scanAndProcess(store, listOf(tempDir.absolutePath), suffix)
        assertTrue(second.contains("已跳过(已处理过): 1 个"))
        assertEquals((original.size + suffix.length).toLong(), file.length())

        // 原始媒体数据未被修改
        assertArrayEquals(original, file.readBytes().copyOfRange(0, original.size))
    }

    @Test
    fun `scanAndProcess 扫描期间增长的文件记为等待稳定`() {
        val file = File(tempDir, "growing.mp4")
        file.writeBytes(ByteArray(1024) { (it % 256).toByte() })

        // 在 3s 稳定确认窗口内追加数据，模拟下载中的文件
        val grower = Thread {
            try {
                Thread.sleep(1000)
                VideoHashProcessor.appendSuffix(file, ByteArray(512) { 1 })
            } catch (_: InterruptedException) {
            }
        }
        grower.start()
        val result = VideoHashProcessor.scanAndProcess(store, listOf(tempDir.absolutePath), "HashMod")
        grower.join()

        assertTrue(result.contains("等待稳定: 1 个"))
        assertFalse(VideoHashProcessor.isAlreadyProcessed(file, "HashMod".toByteArray()))
    }

    @Test
    fun `scanAndProcess 仅处理3层子目录内的视频`() {
        val suffix = "HashMod"
        // 源语义：walkTopDown().maxDepth(3)，监听目录自身计第 0 层 ——
        // 根文件(1)、d1 内(2)、d2 内(3) 均收集；d3 内(4) 不收集
        val depth1 = File(tempDir, "d1").apply { mkdirs() }
        val depth2 = File(depth1, "d2").apply { mkdirs() }
        val depth3 = File(depth2, "d3").apply { mkdirs() }   // 其内文件超出 3 层（不含）
        File(tempDir, "a.mp4").writeBytes(ByteArray(64) { it.toByte() })
        File(depth2, "b.mp4").writeBytes(ByteArray(64) { it.toByte() })
        File(depth3, "c.mp4").writeBytes(ByteArray(64) { it.toByte() })

        val result = VideoHashProcessor.scanAndProcess(store, listOf(tempDir.absolutePath), suffix)

        assertTrue(result.contains("已处理: 2 个文件"))
        assertTrue(VideoHashProcessor.isAlreadyProcessed(File(depth2, "b.mp4"), suffix.toByteArray()))
        // 超出 3 层未被收集处理
        assertEquals(64L, File(depth3, "c.mp4").length())
    }

    @Test
    fun `scanAndRestore 还原已洗码文件并清记录`() {
        val file = File(tempDir, "restored.mp4")
        val original = ByteArray(4096) { (it % 256).toByte() }
        val suffix = "HashMod"
        file.writeBytes(original)
        VideoHashProcessor.appendSuffix(file, suffix.toByteArray())
        store.save(mapOf(file.absolutePath to file.length()))

        val result = VideoHashProcessor.scanAndRestore(store, listOf(tempDir.absolutePath), suffix)

        assertTrue(result.contains("已还原: 1 个文件"))
        assertEquals(original.size.toLong(), file.length())
        assertArrayEquals(original, file.readBytes())
        assertFalse(store.map.containsKey(file.absolutePath))
    }

    @Test
    fun `记录表在文件删除后清理`() {
        val file = File(tempDir, "deleted.mp4")
        file.writeBytes(ByteArray(64) { it.toByte() })
        VideoHashProcessor.scanAndProcess(store, listOf(tempDir.absolutePath), "HashMod")
        assertTrue(store.map.containsKey(file.absolutePath))

        file.delete()
        val result = VideoHashProcessor.scanAndProcess(store, listOf(tempDir.absolutePath), "HashMod")

        assertFalse(store.map.containsKey(file.absolutePath))
        assertTrue(result.contains("已处理: 0 个文件"))
    }

    /** 与 SharedPreferences 语义一致的全量内存实现 */
    private class InMemoryRecordStore : VideoHashRecordStore {
        val map = mutableMapOf<String, Long>()

        override fun load(): MutableMap<String, Long> = map.toMutableMap()

        override fun save(records: Map<String, Long>) {
            map.clear()
            map.putAll(records)
        }
    }
}
