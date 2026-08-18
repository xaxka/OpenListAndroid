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

    /** 设置数据目录并注册回调（幂等，源项目 startup 前置步骤）。 */
    private fun init(dataDir: String) {
        runCatching {
            Alistlib.setConfigData(dataDir)
            Alistlib.setConfigLogStd(true)
            Alistlib.init(this, this)
        }.onFailure {
            Log.e(TAG, "init:", it)
        }
    }

    override fun startup(dataDir: String) {
        Log.d(TAG, "startup: $dataDir")
        init(dataDir)
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

    override fun isRunning(): Boolean = Alistlib.isRunning("")

    override fun setAdminPassword(dataDir: String, password: String) {
        // 与源项目一致：未运行时先初始化内核再改密
        if (!isRunning()) init(dataDir)

        Log.d(TAG, "setAdminPassword: $dataDir")
        Alistlib.setConfigData(dataDir)
        Alistlib.setAdminPassword(password)
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
