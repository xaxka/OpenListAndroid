package com.xaxka.openlist.bridge

import kotlinx.coroutines.flow.SharedFlow

/** Go 内核事件，对应 gomobile Event 回调（onShutdown/onStartError/onProcessExit）。 */
sealed interface EngineEvent {
    data class Shutdown(val type: String) : EngineEvent

    data class StartError(val type: String, val message: String) : EngineEvent

    data class ProcessExit(val code: Long) : EngineEvent
}

/** Go 内核日志条目，level 为 logrus 级别（0..6）。 */
data class EngineLog(
    val level: Int,
    val timeMillis: Long,
    val message: String,
)

/** 引擎抽象：供 ServerManager 消费，单测可注入假实现。 */
interface CoreEngine {
    val events: SharedFlow<EngineEvent>
    val logs: SharedFlow<EngineLog>

    /** 初始化并启动内核（等价源项目 init + start）。 */
    fun startup(dataDir: String)

    /** 关闭内核，timeoutMs 为内核等待超时。 */
    fun shutdown(timeoutMs: Long)

    /** 任一 server 存活即运行（http/https/unix 取或）。 */
    fun isRunning(): Boolean

    /** 设置管理员密码；返回是否成功（内核未初始化/调用失败均返回 false）。 */
    fun setAdminPassword(dataDir: String, password: String): Boolean

    fun getOutboundIP(): String
}
