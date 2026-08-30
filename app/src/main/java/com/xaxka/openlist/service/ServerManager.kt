package com.xaxka.openlist.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.xaxka.openlist.R
import com.xaxka.openlist.bridge.CoreConfig
import com.xaxka.openlist.bridge.CoreConfigSync
import com.xaxka.openlist.bridge.CoreEngine
import com.xaxka.openlist.bridge.EngineEvent
import com.xaxka.openlist.data.log.LogBuffer
import com.xaxka.openlist.data.log.LoggableLevel
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import com.xaxka.openlist.easytier.EasyTierManager
import com.xaxka.openlist.qbt.QBittorrentManager
import com.xaxka.openlist.system.ShortCuts
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "OpenList"
private const val CLIP_LABEL = "OpenList"
private const val MSG_ADDRESS_COPIED = "已复制地址"
private const val SHUTDOWN_TIMEOUT_MS = 5000L
private const val CORE_VERSION_ASSET = "openlist_version"

/**
 * 服务状态机中枢（ARCHITECTURE §4.1）。
 * Tile/BootReceiver/SwitchServerActivity/主页 FAB 一律经本类驱动；
 * 引擎回调与日志转发为 Flow，替代源项目的 LocalBroadcast/pigeon 双通道。
 */
@Singleton
class ServerManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val engine: CoreEngine,
    private val logBuffer: LogBuffer,
    private val prefs: AppPrefsRepository,
    private val easyTier: EasyTierManager,
    private val qBittorrent: QBittorrentManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val core = ServerCore(engine, scope)

    /** 内核数据目录：跟随设置页「data 文件夹路径」偏好（空白回退默认），下次启动生效。 */
    private val _dataDir = MutableStateFlow(prefs.defaultDataDir)
    val dataDir: String get() = _dataDir.value

    val state: StateFlow<ServerState> = core.state.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)

    /** http://<出站IP>:<端口>，仅服务活跃期间非空。 */
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private val _coreVersion = MutableStateFlow("")
    val coreVersion: StateFlow<String> = _coreVersion.asStateFlow()

    private val _logs = MutableSharedFlow<ServerLog>(extraBufferCapacity = 256)
    val logs: SharedFlow<ServerLog> = _logs.asSharedFlow()

    init {
        // 「data 文件夹路径」偏好 → 内核数据目录（引擎启动/改密/端口探测均取本值）
        scope.launch {
            prefs.dataDir.collect { _dataDir.value = it }
        }
        // 到达终态（RUNNING/STOPPED）时同步动态快捷方式：
        // 覆盖 FAB/磁贴/快捷方式/开机自启全部启停入口，替代各入口手动同步（曾漏更新）
        scope.launch {
            core.state.collect { st ->
                if (st == ServerState.RUNNING || st == ServerState.STOPPED) {
                    runCatching { ShortCuts.syncDynamic(appContext, st) }
                }
            }
        }
        // 内核日志 → LogBuffer + logs Flow
        scope.launch {
            engine.logs.collect { entry ->
                val level = entry.level.toLoggableLevel()
                logBuffer.append(level, TAG, entry.message)
                _logs.tryEmit(
                    ServerLog(level, formatTime(entry.timeMillis), entry.message)
                )
            }
        }
        // 启动失败事件 → ERROR 日志（状态语义与源项目一致：不回退状态）
        scope.launch {
            engine.events.collect { event ->
                if (event is EngineEvent.StartError) {
                    val msg = "start error [${event.type}]: ${event.message}"
                    logBuffer.append(LoggableLevel.ERROR, TAG, msg)
                    _logs.tryEmit(
                        ServerLog(LoggableLevel.ERROR, formatTime(System.currentTimeMillis()), msg)
                    )
                }
            }
        }
        // 停止后清空地址
        scope.launch {
            core.state.collect { st ->
                if (st == ServerState.STOPPED) _serverUrl.value = null
            }
        }
        // EasyTier 内网映射 / qbittorrent 随服务启停：RUNNING 时按偏好拉起，
        // 进入停止流程即回收。偏好开关由各 Manager 自行读取，这里只转发服务状态。
        scope.launch {
            core.state.collect { st ->
                when (st) {
                    ServerState.RUNNING -> {
                        easyTier.startIfEnabled()
                        qBittorrent.startIfEnabled()
                    }
                    ServerState.STOPPING, ServerState.STOPPED -> {
                        easyTier.stop()
                        qBittorrent.stop()
                    }
                    else -> Unit
                }
            }
        }
        // 内核版本（assets/openlist_version，当前 v4.2.5）
        scope.launch(Dispatchers.IO) {
            val version = runCatching {
                appContext.assets.open(CORE_VERSION_ASSET).bufferedReader().use {
                    it.readLine()?.trim()
                }
            }.getOrNull().orEmpty()
            _coreVersion.value = version
        }
    }

    /** 启动服务：置 STARTING 并拉起前台服务（引擎由服务侧启动）。 */
    fun start(context: Context) {
        if (!core.markStarting()) return
        refreshServerUrl()
        toast(context, appContext.getString(R.string.openlist_starting))
        ContextCompat.startForegroundService(
            context,
            Intent(context, OpenListService::class.java).setAction(OpenListService.ACTION_START)
        )
    }

    /** 停止服务：置 STOPPING，由前台服务展示「关闭中」直到内核确认退出。 */
    fun stop(context: Context) {
        if (!core.requestStop()) return
        toast(context, appContext.getString(R.string.openlist_shut_downing))
        ContextCompat.startForegroundService(
            context,
            Intent(context, OpenListService::class.java).setAction(OpenListService.ACTION_SHUTDOWN)
        )
    }

    fun toggle(context: Context) {
        if (state.value == ServerState.STOPPED) start(context) else stop(context)
    }

    /** 前台服务 onStartCommand 回调（非 ACTION_SHUTDOWN 时）。 */
    fun onServiceStartCommand(context: Context) {
        when (state.value) {
            ServerState.STOPPED -> start(context) // START_STICKY 重建场景
            ServerState.STARTING -> core.onEngineStartRequested(dataDir, prepare = ::prepareCoreConfig)
            else -> Unit
        }
    }

    /**
     * 内核启动前置：把应用偏好同步进 <dataDir>/config.json。
     * 必须先于 Alistlib.init()（bootstrap.InitConfig 读文件）执行，
     * 失败不阻断启动（内核按现有 config.json 继续）。
     */
    private suspend fun prepareCoreConfig() {
        // 用户自选目录可能尚不存在（SAF 选择仅授权不创建），内核落盘前先建好
        val dir = File(dataDir)
        if (!dir.exists() && !dir.mkdirs()) {
            val msg = "数据目录创建失败：$dataDir"
            Log.w(TAG, msg)
            logBuffer.append(LoggableLevel.WARN, TAG, msg)
        }
        val noMemoryCache = prefs.noMemoryCache.first()
        when (CoreConfigSync.syncNoMemoryCache(dataDir, noMemoryCache)) {
            CoreConfigSync.Outcome.UPDATED -> {
                val msg = "min_free_memory=${if (noMemoryCache) "-1" else "default"}（不使用内存缓存=$noMemoryCache）"
                Log.i(TAG, "config.json updated: $msg")
                logBuffer.append(LoggableLevel.INFO, TAG, msg)
            }
            CoreConfigSync.Outcome.NO_CHANGE -> Unit
            CoreConfigSync.Outcome.FAILED -> {
                Log.w(TAG, "sync config.json failed")
                logBuffer.append(LoggableLevel.WARN, TAG, "config.json 偏好同步失败，按现有配置启动")
            }
        }
    }

    /** 前台服务销毁回调：兜底关闭仍在运行的内核。 */
    fun onServiceDestroyed() {
        core.onServiceDestroyed()
    }

    /**
     * App 回前台回调（ProcessLifecycleOwner ON_START，由 Application 转发）。
     * OPPO 等厂商后台会冻结/清理进程：解冻回前台后 OpenList 内核通常仍在，
     * 但 EasyTier 原生实例可能已丢失——这里以原生侧实际状态为准校验并按需恢复。
     */
    fun onAppForegrounded() {
        if (state.value == ServerState.RUNNING) {
            easyTier.ensureRecovered()
            qBittorrent.ensureRecovered()
        }
    }

    /** 设置管理员密码；失败（内核未初始化/调用异常）返回 false。 */
    suspend fun setAdminPassword(password: String): Boolean =
        withContext(Dispatchers.IO) {
            engine.setAdminPassword(dataDir, password)
        }

    /** 复制服务器地址到剪贴板（标签 OpenList），并提示。 */
    fun copyServerAddress(context: Context) {
        if (_serverUrl.value == null) refreshServerUrl()
        val url = _serverUrl.value ?: return
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, url))
        toast(context, MSG_ADDRESS_COPIED)
    }

    private fun refreshServerUrl() {
        _serverUrl.value = runCatching {
            "http://${engine.getOutboundIP()}:${CoreConfig.httpPortOf(dataDir)}"
        }.getOrNull()
    }

    /** 日志时间格式（线程安全，替代每条日志 new SimpleDateFormat） */
    private val logTimeFormat =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())

    private fun formatTime(millis: Long): String = logTimeFormat.format(Instant.ofEpochMilli(millis))

    private fun toast(context: Context, text: String) {
        val appCtx = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(appCtx, text, Toast.LENGTH_SHORT).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appCtx, text, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * 纯状态机（无 Android/agent-data 依赖，便于单测）：
 * STOPPED → STARTING → RUNNING → STOPPING → STOPPED。
 */
internal class ServerCore(
    private val engine: CoreEngine,
    private val scope: CoroutineScope,
) {
    val state = MutableStateFlow(ServerState.STOPPED)

    /** 引擎启动协程（在途则拒绝重复 start 请求，防 Alistlib.Start 双重执行） */
    @Volatile
    private var startJob: Job? = null

    /** 状态置位锁：markStarting 与 onEngineStartRequested 的 check-then-set 原子化 */
    private val stateLock = Any()

    init {
        // 内核确认退出（onShutdown）→ STOPPED
        scope.launch {
            engine.events.collect { event ->
                if (event is EngineEvent.Shutdown && !engine.isRunning()) {
                    transitionToStopped()
                }
            }
        }
    }

    /** STOPPED → STARTING；非 STOPPED 返回 false。 */
    fun markStarting(): Boolean {
        synchronized(stateLock) {
            if (state.value != ServerState.STOPPED) return false
            state.value = ServerState.STARTING
        }
        return true
    }

    /** 服务侧就绪后启动引擎；startup 返回即 RUNNING（源项目语义）。 */
    fun onEngineStartRequested(dataDir: String, prepare: suspend () -> Unit = {}) {
        synchronized(stateLock) {
            when (state.value) {
                ServerState.STOPPED -> state.value = ServerState.STARTING
                // 已在启动流程中（前台服务多次 onStartCommand 重入）：拒绝，防双启
                ServerState.STARTING -> if (startJob?.isActive == true) return
                else -> return
            }
            startJob = scope.launch {
                try {
                    // 启动前置（写 config.json 等）：失败不阻断，内核按现有配置启动
                    runCatching { prepare() }
                    engine.startup(dataDir)
                    if (state.value == ServerState.STARTING) {
                        state.value = ServerState.RUNNING
                    } else {
                        // 启动期间收到停止/销毁：补一次关闭，防止孤儿内核
                        engine.shutdown(SHUTDOWN_TIMEOUT_MS)
                        transitionToStopped()
                    }
                } finally {
                    startJob = null
                }
            }
        }
    }

    /** STARTING/RUNNING → STOPPING 并关闭内核；其他状态返回 false。 */
    fun requestStop(): Boolean {
        synchronized(stateLock) {
            if (state.value != ServerState.RUNNING && state.value != ServerState.STARTING) return false
            state.value = ServerState.STOPPING
        }
        scope.launch {
            engine.shutdown(SHUTDOWN_TIMEOUT_MS)
            if (engine.isRunning()) {
                // 超时兜底：重试一轮，仍存活也强制复位状态，避免 UI 永卡「关闭中」
                engine.shutdown(SHUTDOWN_TIMEOUT_MS)
                if (engine.isRunning()) {
                    Log.e("OpenList", "engine still running after shutdown timeout")
                }
            }
            transitionToStopped()
        }
        return true
    }

    /** 服务销毁：立即 STOPPED，引擎若在跑则异步兜底关闭（5000ms）。 */
    fun onServiceDestroyed() {
        state.value = ServerState.STOPPED
        scope.launch {
            if (engine.isRunning()) engine.shutdown(SHUTDOWN_TIMEOUT_MS)
        }
    }

    private fun transitionToStopped() {
        state.value = ServerState.STOPPED
    }
}

/** logrus 级别（0=PANIC 1=FATAL 2=ERROR 3=WARN 4=INFO 5=DEBUG 6=TRACE）→ LoggableLevel。 */
internal fun Int.toLoggableLevel(): LoggableLevel = when (this) {
    in 0..2 -> LoggableLevel.ERROR
    3 -> LoggableLevel.WARN
    4 -> LoggableLevel.INFO
    else -> LoggableLevel.DEBUG
}
