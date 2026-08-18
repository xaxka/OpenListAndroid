package com.xaxka.openlist.bridge

import alistlib.Alistlib
import alistlib.Event
import alistlib.LogCallback
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * gomobile AAR（包 alistlib）封装：唯一调用 alistlib.* 的类。
 * Event/LogCallback 回调线程不确定，统一 tryEmit 转为 Flow 供上层消费。
 */
@Singleton
class OpenListEngine @Inject constructor() : CoreEngine, Event, LogCallback {
    companion object {
        const val TAG = "OpenListEngine"
        private const val LOG_BUFFER_CAPACITY = 256
    }

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val _logs = MutableSharedFlow<EngineLog>(extraBufferCapacity = LOG_BUFFER_CAPACITY)
    override val logs: SharedFlow<EngineLog> = _logs.asSharedFlow()

    /** 设置数据目录并注册回调（幂等，源项目 startup 前置步骤）。成功返回 true。 */
    private fun init(dataDir: String): Boolean =
        runCatching {
            Alistlib.setConfigData(dataDir)
            Alistlib.setConfigLogStd(true)
            Alistlib.init(this, this)
        }.fold(
            onSuccess = { true },
            onFailure = {
                Log.e(TAG, "init:", it)
                false
            },
        )

    override fun startup(dataDir: String) {
        Log.d(TAG, "startup: $dataDir")
        // 数据目录未就绪时不再启动，避免内核在错误目录/未初始化状态下起服务
        if (!init(dataDir)) return
        runCatching { Alistlib.start() }.onFailure {
            Log.e(TAG, "start:", it)
        }
    }

    override fun shutdown(timeoutMs: Long) {
        Log.d(TAG, "shutdown: $timeoutMs")
        runCatching { Alistlib.shutdown(timeoutMs) }.onFailure {
            Log.e(TAG, "shutdown:", it)
        }
    }

    /**
     * 任一 server 存活即视为运行。
     * Go 侧 IsRunning("") 语义为 http&&https&&unix 全部存活（默认仅启用 HTTP，
     * 恒为 false），会导致服务销毁后兜底关闭被跳过、内核成孤儿，故按类型探测取或。
     */
    override fun isRunning(): Boolean =
        Alistlib.isRunning("http") || Alistlib.isRunning("https") || Alistlib.isRunning("unix")

    override fun setAdminPassword(dataDir: String, password: String): Boolean {
        // 与源项目一致：未运行时先初始化内核再改密；初始化失败直接返回
        if (!isRunning() && !init(dataDir)) {
            Log.e(TAG, "setAdminPassword: engine not initialized")
            return false
        }

        Log.d(TAG, "setAdminPassword: $dataDir")
        return runCatching { Alistlib.setAdminPassword(password) }
            .onFailure { Log.e(TAG, "setAdminPassword:", it) }
            .isSuccess
    }

    override fun getOutboundIP(): String = Alistlib.getOutboundIPString()

    override fun onShutdown(type: String) {
        Log.d(TAG, "onShutdown: $type")
        _events.tryEmit(EngineEvent.Shutdown(type))
    }

    override fun onStartError(type: String, err: String) {
        Log.e(TAG, "onStartError: $type, $err")
        _events.tryEmit(EngineEvent.StartError(type, err))
    }

    override fun onProcessExit(code: Long) {
        _events.tryEmit(EngineEvent.ProcessExit(code))
    }

    override fun onLog(level: Short, time: Long, message: String) {
        _logs.tryEmit(EngineLog(level.toInt(), time, message))
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BridgeModule {
    @Binds
    @Singleton
    abstract fun bindCoreEngine(impl: OpenListEngine): CoreEngine
}
