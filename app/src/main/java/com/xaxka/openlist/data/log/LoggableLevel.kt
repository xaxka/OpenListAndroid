package com.xaxka.openlist.data.log

/**
 * 可显示日志级别（对齐源 ServerLog.evalLog 的 D/I/W/E 四级归并）。
 * Go 内核 7 级（panic..trace）经 bridge 归并后落入此枚举。
 */
enum class LoggableLevel {
    DEBUG, INFO, WARN, ERROR;

    /** 中文显示名 */
    val label: String
        get() = when (this) {
            DEBUG -> "调试"
            INFO -> "信息"
            WARN -> "警告"
            ERROR -> "错误"
        }
}
