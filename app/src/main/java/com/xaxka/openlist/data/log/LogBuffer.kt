package com.xaxka.openlist.data.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内日志环形缓冲：容量固定 [CAPACITY] 条，满后挤出最旧。
 * [logs] 只增不删（除 [clear]），线程安全；由 ServerManager 等注入写入。
 */
@Singleton
class LogBuffer @Inject constructor() {

    private val lock = Any()
    private val buffer = ArrayDeque<ServerLog>(CAPACITY)
    private val _logs = MutableStateFlow<List<ServerLog>>(emptyList())

    /** 当前日志快照（旧 → 新），最多 [CAPACITY] 条 */
    val logs: StateFlow<List<ServerLog>> = _logs.asStateFlow()

    /** 追加一条日志，时间戳取当前时刻 */
    fun append(level: LoggableLevel, tag: String, message: String) {
        val entry = ServerLog(
            time = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
        )
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            _logs.value = buffer.toList()
        }
    }

    /** 清空全部日志 */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _logs.value = emptyList()
        }
    }

    companion object {
        const val CAPACITY: Int = 500
    }
}
