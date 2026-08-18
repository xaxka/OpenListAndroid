package com.xaxka.openlist.video

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视频洗码处理器：在视频文件尾部追加自定义文字以改变文件 Hash。
 *
 * - 不重编码、不修改原有媒体数据；仅读写文件尾部少量字节，支持 GB 级文件
 * - 强制监听 3 层子目录；两遍扫描 + 3000ms 间隔确认文件大小稳定，跳过写入中的文件
 * - 「已处理记录（路径+大小）+ 尾部字节比对」双重防重复追加
 * - 支持还原：尾部字节与追加文字一致时截断，校验失败绝不截断
 *
 * 算法、常量与统计文案逐行对齐源实现（tmp/android/.../video/VideoHashProcessor.kt），
 * 差异仅两处：记录表经 [VideoHashRecordStore] 注入；移除 android.util.Log（纯 JVM 可测）。
 */
object VideoHashProcessor {

    /** 强制监听的子目录层数（含监听目录本身） */
    const val MAX_DEPTH = 3

    /** 两遍扫描之间的等待时长（毫秒），用于确认文件大小稳定 */
    const val STABLE_CHECK_INTERVAL_MS = 3000L

    /** 支持的视频文件扩展名（16 种） */
    val videoExtensions = setOf(
        "mp4", "mkv", "avi", "flv", "mov", "wmv", "ts", "m4v",
        "rmvb", "rm", "3gp", "webm", "vob", "f4v", "mpeg", "mpg"
    )

    /** 并发保护：同一时刻仅允许一个洗码/还原任务 */
    @Volatile
    private var isScanning = false

    /**
     * 扫描并处理监听目录（含 3 层子目录）中的视频文件。
     *
     * 流程：第一遍记录所有视频文件大小 → 存在候选时等待 3 秒 → 第二遍复查，
     * 大小未变化（稳定）且未洗码的文件立即追加文字。
     *
     * @return 处理结果统计文本
     */
    fun scanAndProcess(
        store: VideoHashRecordStore,
        dirs: List<String>,
        suffix: String
    ): String {
        if (dirs.isEmpty()) {
            return "未设置监听目录"
        }
        if (suffix.isBlank()) {
            return "追加文字为空，请先设置"
        }

        // 并发保护：避免多个 Worker 同时处理
        if (isScanning) {
            return "已有处理任务在运行中"
        }
        isScanning = true

        try {
            val suffixBytes = suffix.toByteArray(Charsets.UTF_8)
            val processedFiles = store.load()

            // 第一遍：收集所有视频文件及初始大小
            val files = collectVideoFiles(dirs)
            val firstSizes = HashMap<String, Long>(files.size)
            files.forEach { firstSizes[it.absolutePath] = it.length() }

            val currentFiles = files.map { it.absolutePath }.toMutableSet()

            // 只有存在需要洗码的候选文件时才等待稳定性确认
            val hasCandidates = files.any {
                val size = firstSizes[it.absolutePath] ?: 0L
                size > 0 &&
                        processedFiles[it.absolutePath] != size &&
                        !isAlreadyProcessed(it, suffixBytes)
            }
            if (hasCandidates) {
                try {
                    Thread.sleep(STABLE_CHECK_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return "任务被中断"
                }
            }

            var processedCount = 0
            var skippedCount = 0
            var waitingCount = 0
            var errorCount = 0
            val processedFileNames = mutableListOf<String>()

            for (file in files) {
                try {
                    when (processFile(file, suffixBytes, firstSizes[file.absolutePath], processedFiles)) {
                        ProcessResult.PROCESSED -> {
                            processedCount++
                            processedFileNames.add(file.name)
                        }
                        ProcessResult.ALREADY_PROCESSED -> skippedCount++
                        ProcessResult.WAITING -> waitingCount++
                        ProcessResult.ERROR -> errorCount++
                    }
                } catch (e: Exception) {
                    errorCount++
                }
            }

            // 清理已删除文件的记录，避免记录无限增长
            cleanUpRecords(processedFiles, currentFiles)
            store.save(processedFiles)

            return buildString {
                append("扫描时间: ${timestamp()}\n")
                append("已处理: $processedCount 个文件\n")
                append("已跳过(已处理过): $skippedCount 个\n")
                append("等待稳定: $waitingCount 个\n")
                if (errorCount > 0) {
                    append("错误: $errorCount 个\n")
                }
                if (processedFileNames.isNotEmpty() && processedFileNames.size <= 10) {
                    append("已处理文件:\n")
                    processedFileNames.forEach { append("  - $it\n") }
                }
            }.trimEnd()
        } finally {
            isScanning = false
        }
    }

    /**
     * 扫描并还原监听目录（含 3 层子目录）中的视频文件：
     * 尾部已含追加文字的文件移除追加部分（截断），其余跳过。
     *
     * @return 还原结果统计文本
     */
    fun scanAndRestore(
        store: VideoHashRecordStore,
        dirs: List<String>,
        suffix: String
    ): String {
        if (dirs.isEmpty()) {
            return "未设置监听目录"
        }
        if (suffix.isBlank()) {
            return "追加文字为空，请先设置"
        }

        if (isScanning) {
            return "已有处理任务在运行中"
        }
        isScanning = true

        try {
            val suffixBytes = suffix.toByteArray(Charsets.UTF_8)
            val processedFiles = store.load()
            val files = collectVideoFiles(dirs)
            val currentFiles = files.map { it.absolutePath }.toMutableSet()

            var restoredCount = 0
            var skippedCount = 0
            var errorCount = 0
            val restoredFileNames = mutableListOf<String>()

            for (file in files) {
                val path = file.absolutePath
                try {
                    if (!isAlreadyProcessed(file, suffixBytes)) {
                        // 未洗码（或追加文字不同），无需还原
                        skippedCount++
                        continue
                    }
                    if (removeSuffix(file, suffixBytes)) {
                        restoredCount++
                        restoredFileNames.add(file.name)
                        // 还原后清除已处理记录
                        processedFiles.remove(path)
                    } else {
                        skippedCount++
                    }
                } catch (e: Exception) {
                    errorCount++
                }
            }

            cleanUpRecords(processedFiles, currentFiles)
            store.save(processedFiles)

            return buildString {
                append("还原时间: ${timestamp()}\n")
                append("已还原: $restoredCount 个文件\n")
                append("无需还原: $skippedCount 个\n")
                if (errorCount > 0) {
                    append("错误: $errorCount 个\n")
                }
                if (restoredFileNames.isNotEmpty() && restoredFileNames.size <= 10) {
                    append("已还原文件:\n")
                    restoredFileNames.forEach { append("  - $it\n") }
                }
            }.trimEnd()
        } finally {
            isScanning = false
        }
    }

    /** 收集所有监听目录（含 3 层子目录）中的视频文件 */
    private fun collectVideoFiles(dirs: List<String>): List<File> {
        val files = mutableListOf<File>()
        for (dirPath in dirs) {
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) {
                continue
            }
            dir.walkTopDown().maxDepth(MAX_DEPTH)
                .filter { it.isFile && isVideoFile(it) }
                .forEach { files.add(it) }
        }
        return files
    }

    /**
     * 处理单个视频文件，判定顺序：
     * 1. 大小为 0 → WAITING；
     * 2. 已处理记录命中（路径 + 大小一致）→ ALREADY_PROCESSED（后缀文字变了也不重复追加）；
     * 3. 尾部字节等于后缀 → 补写记录 + ALREADY_PROCESSED（跨重启的备份检查）；
     * 4. 与第一遍大小不一致（仍在写入）→ WAITING；
     * 5. 追加后缀并记录新大小 → PROCESSED。
     */
    private fun processFile(
        file: File,
        suffixBytes: ByteArray,
        firstSize: Long?,
        processedFiles: MutableMap<String, Long>
    ): ProcessResult {
        val path = file.absolutePath
        val currentSize = file.length()

        if (currentSize == 0L) {
            return ProcessResult.WAITING
        }

        val processedSize = processedFiles[path]
        if (processedSize != null && processedSize == currentSize) {
            return ProcessResult.ALREADY_PROCESSED
        }

        if (isAlreadyProcessed(file, suffixBytes)) {
            processedFiles[path] = currentSize
            return ProcessResult.ALREADY_PROCESSED
        }

        if (firstSize == null || firstSize != currentSize) {
            return ProcessResult.WAITING
        }

        return try {
            appendSuffix(file, suffixBytes)
            val newSize = file.length()
            processedFiles[path] = newSize
            ProcessResult.PROCESSED
        } catch (e: Exception) {
            ProcessResult.ERROR
        }
    }

    /** 是否为受支持的视频文件（扩展名忽略大小写） */
    fun isVideoFile(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.getDefault())
        return ext in videoExtensions
    }

    /**
     * 检查文件尾部是否已包含追加的文字。
     * 仅读取最后 N 字节，不加载整个文件到内存。
     */
    fun isAlreadyProcessed(file: File, suffixBytes: ByteArray): Boolean {
        if (suffixBytes.isEmpty()) return false
        val fileLength = file.length()
        if (fileLength < suffixBytes.size) return false

        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(fileLength - suffixBytes.size)
                val tailBytes = ByteArray(suffixBytes.size)
                raf.readFully(tailBytes)
                tailBytes.contentEquals(suffixBytes)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 在文件尾部追加字节（append 模式，不读取整个文件） */
    fun appendSuffix(file: File, suffixBytes: ByteArray) {
        FileOutputStream(file, true).use { fos ->
            fos.write(suffixBytes)
            fos.flush()
        }
    }

    /**
     * 移除文件尾部已追加的字节（还原）。
     * 先校验尾部字节与追加文字一致再截断，确保不会误删视频内容。
     * @return true 还原成功；false 文件未洗码或校验失败
     */
    fun removeSuffix(file: File, suffixBytes: ByteArray): Boolean {
        if (suffixBytes.isEmpty()) return false
        val fileLength = file.length()
        if (fileLength < suffixBytes.size) return false

        return try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(fileLength - suffixBytes.size)
                val tailBytes = ByteArray(suffixBytes.size)
                raf.readFully(tailBytes)
                if (!tailBytes.contentEquals(suffixBytes)) {
                    // 尾部不是追加文字，不还原，避免破坏视频
                    return false
                }
                raf.setLength(fileLength - suffixBytes.size)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 清理已删除文件的记录，避免记录无限增长 */
    private fun cleanUpRecords(
        processedFiles: MutableMap<String, Long>,
        currentFiles: Set<String>
    ) {
        val stalePaths = processedFiles.keys.filter { it !in currentFiles }
        stalePaths.forEach { processedFiles.remove(it) }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    /** 单文件处理结果分类 */
    enum class ProcessResult {
        PROCESSED,
        ALREADY_PROCESSED,
        WAITING,
        ERROR
    }
}
