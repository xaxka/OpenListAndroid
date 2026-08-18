package com.xaxka.openlist.data.log

/** 单条服务日志（time 为 epoch 毫秒） */
data class ServerLog(
    val time: Long,
    val level: LoggableLevel,
    val tag: String,
    val message: String,
)
