package com.xaxka.openlist.service

import com.xaxka.openlist.bridge.CoreEngine
import com.xaxka.openlist.bridge.EngineEvent
import com.xaxka.openlist.bridge.EngineLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ServerCore 状态机单测：STOPPED→STARTING→RUNNING→STOPPING→STOPPED。 */
class ServerCoreTest {

    /** 急切派发：launch 立即执行，状态断言确定性（背景 scope 任务不随 advanceUntilIdle 执行）。 */
    private fun kotlinx.coroutines.test.TestScope.eagerScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())


    private class FakeCoreEngine : CoreEngine {
        override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
        override val logs = MutableSharedFlow<EngineLog>(extraBufferCapacity = 64)
        var running = false
        var startupCount = 0
            private set
        var shutdownCount = 0
            private set
        var lastShutdownTimeout = -1L
            private set

        override fun startup(dataDir: String) {
            startupCount++
            running = true
            onStartup?.invoke()
        }

        /** 模拟启动过程中外部请求停止（重入） */
        var onStartup: (() -> Unit)? = null

        override fun shutdown(timeoutMs: Long) {
            shutdownCount++
            lastShutdownTimeout = timeoutMs
            if (shutdownStopsEngine) {
                running = false
                events.tryEmit(EngineEvent.Shutdown("http"))
            }
        }

        override fun isRunning(): Boolean = running

        /** false 模拟 shutdown 超时未能停掉内核（孤儿场景） */
        var shutdownStopsEngine = true

        override fun setAdminPassword(dataDir: String, password: String): Boolean = true

        override fun getOutboundIP(): String = "192.168.1.2"
    }

    @Test
    fun `启动请求后进入RUNNING`() = runTest {
        val engine = FakeCoreEngine()
        val core = ServerCore(engine, eagerScope())

        assertTrue(core.markStarting())
        core.onEngineStartRequested("/data/dir")
        advanceUntilIdle()

        assertEquals(ServerState.RUNNING, core.state.value)
        assertEquals(1, engine.startupCount)
    }

    @Test
    fun `markStarting在非STOPPED时返回false`() = runTest {
        val core = ServerCore(FakeCoreEngine(), eagerScope())

        assertTrue(core.markStarting())
        assertFalse(core.markStarting())
    }

    @Test
    fun `停止请求经STOPPING到STOPPED`() = runTest {
        val engine = FakeCoreEngine()
        val core = ServerCore(engine, eagerScope())
        val history = mutableListOf<ServerState>()
        val recorder = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
            .launch { core.state.collect { history += it } }
        core.markStarting()
        core.onEngineStartRequested("/data/dir")

        assertTrue(core.requestStop())
        advanceUntilIdle()

        // STOPPING 为中间态（急切派发下已瞬时经过），以历史序列验证
        assertEquals(
            listOf(
                ServerState.STOPPED,
                ServerState.STARTING,
                ServerState.RUNNING,
                ServerState.STOPPING,
                ServerState.STOPPED,
            ),
            history,
        )
        assertEquals(ServerState.STOPPED, core.state.value)
        assertEquals(5000L, engine.lastShutdownTimeout)
        recorder.cancel()
    }

    @Test
    fun `STOPPED时停止请求返回false`() = runTest {
        val core = ServerCore(FakeCoreEngine(), eagerScope())

        assertFalse(core.requestStop())
    }

    @Test
    fun `onShutdown事件驱动到STOPPED`() = runTest {
        val engine = FakeCoreEngine()
        val core = ServerCore(engine, eagerScope())
        core.markStarting()
        core.onEngineStartRequested("/data/dir")
        advanceUntilIdle()

        engine.running = false
        engine.events.tryEmit(EngineEvent.Shutdown("http"))
        advanceUntilIdle()

        assertEquals(ServerState.STOPPED, core.state.value)
    }

    @Test
    fun `STARTING期间请求停止则启动完成后不进入RUNNING`() = runTest {
        val engine = FakeCoreEngine()
        val core = ServerCore(engine, eagerScope())

        core.markStarting()
        // 启动回调中重入请求停止：模拟 STARTING 期间收到 stop
        engine.onStartup = { core.requestStop() }
        core.onEngineStartRequested("/data/dir")
        advanceUntilIdle()

        assertEquals(ServerState.STOPPED, core.state.value)
        // requestStop 一次 + startup 补偿关闭一次
        assertEquals(2, engine.shutdownCount)
    }

    @Test
    fun `服务销毁后立即STOPPED并兜底关闭引擎`() = runTest {
        val engine = FakeCoreEngine()
        val core = ServerCore(engine, eagerScope())
        core.markStarting()
        core.onEngineStartRequested("/data/dir")
        advanceUntilIdle()

        core.onServiceDestroyed()
        assertEquals(ServerState.STOPPED, core.state.value)
        advanceUntilIdle()

        assertEquals(1, engine.shutdownCount)
    }

    @Test
    fun `STARTING期间重复start请求被拒绝不双重启动`() = runTest {
        val engine = FakeCoreEngine()
        val core = ServerCore(engine, eagerScope())
        core.markStarting()

        // prepare 挂起点模拟 DataStore 读取耗时，营造 STARTING + 在途启动协程窗口
        val gate = CompletableDeferred<Unit>()
        core.onEngineStartRequested("/data/dir") { gate.await() }
        core.onEngineStartRequested("/data/dir") { gate.await() }

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ServerState.RUNNING, core.state.value)
        assertEquals(1, engine.startupCount)
    }

    @Test
    fun `shutdown超时未能停内核时强制复位STOPPED`() = runTest {
        val engine = FakeCoreEngine()
        engine.shutdownStopsEngine = false // shutdown 不生效，内核仍在跑
        val core = ServerCore(engine, eagerScope())
        core.markStarting()
        core.onEngineStartRequested("/data/dir")
        advanceUntilIdle()

        assertTrue(core.requestStop())
        advanceUntilIdle()

        // 重试一轮后仍存活：状态也必须复位，避免 UI 永卡「关闭中」
        assertEquals(ServerState.STOPPED, core.state.value)
        assertEquals(2, engine.shutdownCount)
    }
}
