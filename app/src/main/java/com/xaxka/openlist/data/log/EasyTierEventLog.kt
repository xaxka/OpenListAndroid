package com.xaxka.openlist.data.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EasyTier 事件日记持久化：追加写入应用外置私有目录下的 `easytier/events.log`，
 * 仅保留最近 [RETAIN_MS]（24 小时）内的条目，跨实例重启与进程重启不丢失。
 *
 * 排查「掉线看门狗重启」等场景时，实例被 EasyTierManager.restartLocked 重建后，
 * 内存 LogBuffer 与 prevEvents（增量去重游标）都会复位，但本文件保留重启前 24h
 * 的事件可供回看/导出（adb pull 或文件管理器读取）。
 *
 * 线程安全：所有读写经 [lock] 串行；裁剪按追加计数懒触发（每 [PRUNE_EVERY] 条一次），
 * 并在单例初始化时清理上一会话遗留的过期条目，避免每条都重写文件。
 */
@Singleton
class EasyTierEventLog @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file = File(
        (context.getExternalFilesDir("easytier") ?: File(context.filesDir, "easytier")),
        "events.log",
    )

    /** 事件日记文件绝对路径（供导出提示/排查定位）。 */
    val path: String get() = file.absolutePath

    private val lock = Any()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var appendSincePrune = 0

    init {
        // 进程启动时清理上一会话遗留的过期条目（24h 外）
        synchronized(lock) {
            runCatching { prune() }
        }
    }

    /**
     * 追加一条事件（调用方已做增量去重，此处只负责落盘与裁剪）。
     * 事件文本中的换行替换为空格，保证一行一条、裁剪解析可靠。
     */
    fun append(event: String) {
        if (event.isBlank()) return
        val safe = event.replace("\r", " ").replace("\n", " ").trim()
        if (safe.isEmpty()) return
        synchronized(lock) {
            val line = "${timeFmt.format(Date())}\t$safe\n"
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line)
            }
            if (++appendSincePrune >= PRUNE_EVERY) {
                appendSincePrune = 0
                runCatching { prune() }
            }
        }
    }

    /** 读取当前保留的全部事件（24h 内，旧→新，原始文本），供导出/回看。 */
    fun readRecent(): String = synchronized(lock) {
        runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
    }

    /**
     * 删除早于 24h 的行；解析失败的行保留（避免误删）。
     * 同时以 [MAX_LINES] 作为硬上限兜底（极端高频事件下防止文件无限增长）。
     */
    private fun prune() {
        if (!file.isFile) return
        val cutoff = System.currentTimeMillis() - RETAIN_MS
        val lines = runCatching { file.readLines() }.getOrNull() ?: return
        // 事件按时间顺序追加，尾部为最新；保留 24h 内的行
        val kept = ArrayList<String>(lines.size)
        for (line in lines) {
            val ts = parseTs(line)
            if (ts == null || ts >= cutoff) kept.add(line)
        }
        // 硬上限：超出则只保留最后 MAX_LINES 条
        val trimmed = if (kept.size > MAX_LINES) kept.takeLast(MAX_LINES) else kept
        if (trimmed.size != lines.size) {
            runCatching { file.writeText(trimmed.joinToString("") { "$it\n" }) }
        }
    }

    /** 解析行首 `yyyy-MM-dd HH:mm:ss\t` 时间戳为 epoch 毫秒；非法返回 null。 */
    private fun parseTs(line: String): Long? {
        val tab = line.indexOf('\t')
        if (tab < TIMESTAMP_LEN) return null
        val date = runCatching { timeFmt.parse(line.substring(0, tab)) }.getOrNull() ?: return null
        return date.time
    }

    companion object {
        private const val RETAIN_MS = 24L * 60 * 60 * 1000
        private const val PRUNE_EVERY = 32
        private const val MAX_LINES = 5000
        private const val TIMESTAMP_LEN = 19 // "yyyy-MM-dd HH:mm:ss" 长度
    }
}
