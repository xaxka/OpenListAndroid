package com.xaxka.openlist.service

import com.xaxka.openlist.data.log.LoggableLevel

/** 服务生命周期状态（ARCHITECTURE §4.1 契约）。 */
enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
}

/** 推送给 UI 的内核日志（logs Flow 元素）。 */
data class ServerLog(
    val level: LoggableLevel,
    val time: String,
    val message: String,
)
