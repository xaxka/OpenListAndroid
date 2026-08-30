package com.xaxka.openlist.qbt

import android.content.Context
import android.os.Build
import android.os.Environment
import com.xaxka.openlist.data.log.LoggableLevel
import com.xaxka.openlist.data.log.QBittorrentEventLog
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * qbittorrent（qbittorrent-enhanced-nox）进程管理。
 *
 * 内置二进制为 bionic 动态链接构建（见 [QBittorrentSpec]），随 OpenList 服务启停：
 * 生命周期由 [com.xaxka.openlist.service.ServerManager] 驱动（RUNNING 时按偏好拉起，
 * 停止流程即回收），与 EasyTier 内网映射同一模式。
 *
 * 启动流程：配置准备（删除崩溃残留的 qBittorrent_new.conf（否则其读取优先级高于
 * 正式配置，凭据会被旧状态劫持）；写入/对齐凭据 admin + 密码（默认 adminadmin，
 * 可经「登录账号」菜单修改，存 DataStore）与内存调优/DHT 引导键；顺带清理旧版
 * 残留代理键）→ 拉起子进程（--profile 独立目录，TMPDIR/HOME/TZ 指向应用
 * 目录/UTC）→ 轮询 WebUI 就绪 → setPreferences 强制对齐凭据/保存路径/内存调优
 * （域名解析由 bionic getaddrinfo→netd 原生完成，DHT(UDP)/peer/tracker 全部直连，
 * 无需代理组件）→ 进入运行态周期巡检（进程存活 + WebUI 版本 + 内存采样）。
 *
 * 内存占用：巡检周期读 /proc/<pid>/status 的 VmRSS（常驻内存）。PID 经
 * /proc/<pid>/cmdline 扫描解析（Java Process 无公开 PID 接口，反射依赖内部
 * 实现；/proc 同 UID 可读，cmdline 匹配二进制名，跨 Android 版本稳定）。
 *
 * TZ=UTC：内置 Qt 的时区后端为 tzfile 版（读 TZ/POSIX 规则，Android 无
 * /etc/localtime），显式指定避免无效时区告警；WebUI 时间戳由浏览器端格式化
 * （epoch 秒），不受影响，仅 qbittorrent.log 文件内为 UTC 时间。
 *
 * 自愈：进程意外退出（OOM/厂商冻结后杀）时自动重启，连续快速失败 3 次转 ERROR
 * 不再自动重试（避免风暴），回前台时 ensureRecovered 兜底再试。
 */
@Singleton
class QBittorrentManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val prefs: AppPrefsRepository,
    private val eventLog: QBittorrentEventLog,
) {
    enum class Phase { STOPPED, STARTING, RUNNING, ERROR, UNAVAILABLE }

    data class Status(
        val phase: Phase = Phase.STOPPED,
        val detail: String = "",
        val version: String = "",
        val webUiPort: Int = QBittorrentSpec.DEFAULT_WEBUI_PORT,
        val savePath: String = "",
        /** 局域网访问（0.0.0.0 监听 + 登录；本机仍免认证）。 */
        val lanAccess: Boolean = false,
        /** nox 进程常驻内存（VmRSS，KB；-1 = 未知/未采样）。 */
        val memUsageKb: Long = -1L,
    ) {
        val summary: String
            get() = when (phase) {
                Phase.STOPPED -> "未启动"
                Phase.STARTING -> "启动中"
                Phase.RUNNING -> buildString {
                    append("运行中")
                    if (version.isNotBlank()) append(" · v$version")
                    append(" · ")
                    append(if (lanAccess) "局域网:$webUiPort" else "127.0.0.1:$webUiPort")
                    if (memUsageKb > 0) append(" · ").append(formatMemUsage(memUsageKb))
                }.appendDetail(detail)
                Phase.ERROR -> "错误".appendDetail(detail)
                Phase.UNAVAILABLE -> "不可用".appendDetail(detail)
            }

        val webUiUrl: String get() = "http://127.0.0.1:$webUiPort"

        /** 内存占用展示文案（MB，一位小数；<0.1MB 显示 0.1）。 */
        val memUsageText: String
            get() = if (memUsageKb > 0) formatMemUsage(memUsageKb) + "（常驻）" else ""

        private fun formatMemUsage(kb: Long): String {
            val mb = kb / 1024.0
            return if (mb >= 100) "${(mb + 0.5).toLong()} MB" else "${"%.1f".format(mb)} MB"
        }

        private fun String.appendDetail(d: String) = if (d.isEmpty()) this else "$this：$d"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val _state = MutableStateFlow(Status())
    val state: StateFlow<Status> = _state.asStateFlow()

    /** 二进制路径（jniLibs 解压后的 nativeLibraryDir；不存在则不可用）。 */
    private val binaryFile: File?
        get() = appContext.applicationInfo?.nativeLibraryDir
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it, QBittorrentSpec.BINARY_LIB_NAME) }
            ?.takeIf { it.isFile }

    @Volatile
    private var process: Process? = null

    /** nox 子进程 PID（/proc 扫描解析；-1 = 未知；仅用于内存采样展示）。 */
    @Volatile
    private var noxPid: Int = -1

    @Volatile
    private var instanceStarted = false

    /** 巡检任务。 */
    @Volatile
    private var monitorJob: Job? = null

    /** 本次启动时间戳（快速失败判定）。 */
    @Volatile
    private var startedAt = 0L

    /** 连续快速失败次数（存活 <10s 即退出），达 3 次转 ERROR 停止自动重试。 */
    @Volatile
    private var fastFailCount = 0

    /** 服务 RUNNING 时调用：偏好开启则拉起 nox，否则无动作。 */
    fun startIfEnabled() {
        scope.launch {
            lock.withLock { startLocked() }
        }
    }

    /** 停止 nox（未启动时无动作）。 */
    fun stop() {
        scope.launch {
            lock.withLock { stopLocked() }
        }
    }

    /** 端口等配置变更后重启生效。 */
    fun restart() {
        scope.launch {
            lock.withLock {
                if (instanceStarted) {
                    stopLocked()
                    startLocked()
                } else {
                    startLocked()
                }
            }
        }
    }

    /**
     * App 回到前台时调用：厂商后台冻结/查杀后进程可能已死而 Kotlin 状态未对齐；
     * 以进程实际存活为准校验，异常退出且偏好开启则重启，正常时刷新一次巡检。
     */
    fun ensureRecovered() {
        scope.launch {
            lock.withLock {
                val proc = process
                if (instanceStarted && (proc == null || !proc.isAlive)) {
                    log(LoggableLevel.WARN, "检测到 nox 进程不在（后台被回收），尝试恢复")
                    startLocked()
                }
            }
        }
    }

    // ---------------------------------------------------------------- internal

    private suspend fun startLocked() {
        val enabled = prefs.qbtEnabled.first()
        if (!enabled) return
        if (instanceStarted && process?.isAlive == true) return

        // bionic 动态链接二进制需 API 24+（Qt 6.8 最低支持版本）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            log(LoggableLevel.WARN, "内置 qbittorrent-enhanced-nox 为 bionic 构建，需 Android 7.0+（当前 API ${Build.VERSION.SDK_INT}）")
            transition(Status(Phase.UNAVAILABLE, detail = "需 Android 7.0+"))
            return
        }

        val binary = binaryFile
        if (binary == null) {
            log(LoggableLevel.WARN, "未找到内置 qbittorrent-enhanced-nox（当前构建未打包）")
            transition(Status(Phase.UNAVAILABLE, detail = "当前构建未内置二进制"))
            return
        }

        val port = QBittorrentSpec.parsePort(prefs.qbtWebUiPort.first())
        val lanAccess = prefs.qbtLanAccess.first()
        val webUiPassword = prefs.qbtWebUiPassword.first().ifBlank { QBittorrentSpec.DEFAULT_WEBUI_PASSWORD }
        val profileDir = File(appContext.filesDir, "qbt-profile")
        val saveDir = resolveSaveDir()

        // 配置：先清崩溃残留（否则劫持种子/更新），首次写种子；已存在则对齐 WebUI
        // 绑定/端口与凭据（密码取自定义或默认）及内存调优/DHT 引导键（保留 nox
        // 持久化的其他键）
        runCatching { ensureConfig(profileDir, port, lanAccess, saveDir.absolutePath, webUiPassword) }
            .onFailure { log(LoggableLevel.WARN, "写入/更新配置失败：${it.message}") }

        transition(
            Status(Phase.STARTING, webUiPort = port, savePath = saveDir.absolutePath, lanAccess = lanAccess)
        )
        log(
            LoggableLevel.INFO,
            "启动 qbittorrent-enhanced-nox v${QBittorrentSpec.EMBEDDED_VERSION}（WebUI 端口 $port，" +
                "监听 ${if (lanAccess) "0.0.0.0（局域网需登录）" else "127.0.0.1（仅本机）"}，" +
                "保存路径 ${saveDir.absolutePath}）",
        )

        val proc = try {
            withContext(Dispatchers.IO) {
                val cacheDir = appContext.cacheDir
                val pb = ProcessBuilder(
                    binary.absolutePath,
                    "--confirm-legal-notice",
                    "--profile=${profileDir.absolutePath}",
                    "--webui-port=$port",
                ).apply {
                    redirectErrorStream(true)
                    environment().apply {
                        // bionic 动态链接：libc++_shared.so 与二进制同目录（jniLibs 解压后的
                        // nativeLibraryDir），经 LD_LIBRARY_PATH 提供给动态链接器
                        appContext.applicationInfo?.nativeLibraryDir
                            ?.takeIf { it.isNotBlank() }
                            ?.let { put("LD_LIBRARY_PATH", it) }
                        // Qt 默认找 /tmp（Android 不存在）与 $HOME，全部指到应用目录
                        put("TMPDIR", cacheDir.absolutePath)
                        put("TEMP", cacheDir.absolutePath)
                        put("TMP", cacheDir.absolutePath)
                        put("HOME", profileDir.absolutePath)
                        // 内置 Qt 时区后端为 tzfile 版（Android 无 /etc/localtime），
                        // 显式 UTC 保证有效时区（POSIX 规则，零偏移）；WebUI 时间
                        // 由浏览器端格式化 epoch，不受影响
                        put("TZ", "UTC")
                    }
                }
                pb.start()
            }
        } catch (e: Exception) {
            log(LoggableLevel.ERROR, "nox 启动异常：${e.message}")
            transition(Status(Phase.ERROR, detail = "启动异常：${e.message}"))
            return
        }

        process = proc
        instanceStarted = true
        startedAt = System.currentTimeMillis()
        noxPid = -1

        // stdout 消费（防管道阻塞）→ 事件 Diary
        scope.launch(Dispatchers.IO) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) eventLog.append(trimmed.take(300))
                }
            }
        }

        // 就绪等待：WebUI 版本接口可达即运行态
        val ready = waitForWebUi(port, WEBUI_READY_TIMEOUT_MS)
        if (!instanceStarted || proc !== process) return // 等待期间被停止/替换
        if (ready == null) {
            log(LoggableLevel.WARN, "WebUI 就绪超时（$WEBUI_READY_TIMEOUT_MS ms），转入巡检重试")
            transition(
                Status(Phase.RUNNING, detail = "WebUI 未就绪", webUiPort = port,
                    savePath = saveDir.absolutePath, lanAccess = lanAccess)
            )
        } else {
            transition(
                Status(Phase.RUNNING, version = ready, webUiPort = port,
                    savePath = saveDir.absolutePath, lanAccess = lanAccess)
            )
            log(LoggableLevel.INFO, "WebUI 已就绪（$ready）")
            // 强制对齐凭据/保存路径/内存调优（每轮启动都下发：自愈历史遗留的任意
            // 配置状态；密码为自定义或默认 adminadmin）
            applyStartupPreferences(port, saveDir.absolutePath, webUiPassword)
        }
        fastFailCount = 0
        startMonitor()
    }

    private suspend fun stopLocked() {
        stopMonitor()
        instanceStarted = false
        noxPid = -1
        process?.let { proc ->
            runCatching {
                withContext(Dispatchers.IO) {
                    proc.destroy() // SIGTERM → 优雅退出（保存 resume/配置）
                    if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        proc.destroyForcibly()
                    }
                }
            }
            log(LoggableLevel.INFO, "nox 进程已退出（code=${runCatching { proc.exitValue() }.getOrDefault(-1)}）")
        }
        process = null
        transition(Status(Phase.STOPPED))
        log(LoggableLevel.INFO, "qbittorrent 已停止")
    }

    private fun startMonitor() {
        stopMonitor()
        monitorJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!instanceStarted) break
                lock.withLock {
                    if (!instanceStarted) return@withLock
                    val proc = process
                    if (proc == null || !proc.isAlive) {
                        onProcessDied(proc)
                    } else if (_state.value.phase == Phase.RUNNING) {
                        refreshWebUiVersion(proc)
                        refreshMemUsage()
                    }
                }
            }
        }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /** 进程意外退出：快速失败计数 → 重启或转 ERROR。调用方需持有 [lock]。 */
    private suspend fun onProcessDied(proc: Process?) {
        val code = proc?.let { runCatching { it.exitValue() }.getOrDefault(-1) } ?: -1
        val uptime = System.currentTimeMillis() - startedAt
        log(LoggableLevel.WARN, "nox 进程意外退出（code=$code，存活 ${uptime / 1000}s）")
        instanceStarted = false
        process = null
        noxPid = -1
        if (uptime < FAST_FAIL_MS) fastFailCount++ else fastFailCount = 0
        if (fastFailCount >= FAST_FAIL_LIMIT) {
            log(LoggableLevel.ERROR, "连续 $fastFailCount 次快速失败，停止自动重启（详见事件日记）")
            transition(Status(Phase.ERROR, detail = "进程反复退出（code=$code），已停止自动重启"))
            fastFailCount = 0
            return
        }
        log(LoggableLevel.WARN, "尝试自动重启 nox…")
        startLocked()
    }

    private suspend fun refreshWebUiVersion(proc: Process) {
        val port = _state.value.webUiPort
        val version = withContext(Dispatchers.IO) { httpGet("http://127.0.0.1:$port/api/v2/app/version", 3000) }
        if (version != null) {
            transition(_state.value.copy(version = version.trim().removePrefix("v"), detail = ""))
        } else if (_state.value.detail.isEmpty()) {
            transition(_state.value.copy(detail = "WebUI 暂不可达"))
        }
    }

    /**
     * 采样 nox 常驻内存（VmRSS）并写入状态（仅展示，不影响生命周期）。
     *
     * PID 懒解析：首次采样时扫描 /proc 匹配 cmdline（二进制名），失败静默重试
     * 下轮；/proc 对同 UID 进程可读，其他 App 的 cmdline 读取失败自然跳过。
     */
    private fun refreshMemUsage() {
        if (noxPid <= 0) noxPid = findNoxPid()
        val kb = if (noxPid > 0) readMemKb(noxPid) else -1L
        if (kb != _state.value.memUsageKb) {
            transition(_state.value.copy(memUsageKb = kb))
        }
    }

    /** 扫描 /proc 找 cmdline 含二进制名的进程 PID（找不到返回 -1）。 */
    private fun findNoxPid(): Int {
        val dirs = runCatching { File("/proc").listFiles() }.getOrNull() ?: return -1
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val cmdline = runCatching {
                // cmdline 为 NUL 分隔；整读后 contains 匹配二进制名即可
                File(dir, "cmdline").readText()
            }.getOrNull() ?: continue // 其他 UID 的进程读不到，自然过滤
            if (cmdline.contains(QBittorrentSpec.BINARY_LIB_NAME)) return pid
        }
        return -1
    }

    /** 读 /proc/<pid>/status 的 VmRSS（KB）；不可得返回 -1。 */
    private fun readMemKb(pid: Int): Long {
        val status = runCatching { File("/proc/$pid/status").readText() }.getOrNull() ?: return -1L
        val match = VmRssPattern.find(status) ?: return -1L
        return match.groupValues[1].toLongOrNull() ?: -1L
    }

    /** 轮询 WebUI 版本接口直到返回或超时；返回版本文本（如 v5.2.3.10）或 null。 */
    private suspend fun waitForWebUi(port: Int, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
            val proc = process
            if (proc != null && !proc.isAlive) return null // 途中退出按超时处理
            val version = withContext(Dispatchers.IO) {
                httpGet("http://127.0.0.1:$port/api/v2/app/version", 2000)
            }
            if (version != null) return version.trim()
            delay(500)
        }
        return null
    }

    /**
     * 保存路径解析：公共下载目录 /storage/emulated/0/Download/qbittorrent。
     *
     * App 已声明 MANAGE_EXTERNAL_STORAGE（OpenList 本地存储功能同源），nox 与 App 同
     * UID 继承该授权；未授权（或目录不可写，如无所有文件权限的低版本设备）时回退
     * 应用专属外部目录并告警，避免下载全部失败。
     */
    @Suppress("DEPRECATION")
    private fun resolveSaveDir(): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "qbittorrent",
        )
        if (publicDir.isDirectory || publicDir.mkdirs()) return publicDir
        val fallback = File(
            appContext.getExternalFilesDir(null) ?: appContext.filesDir,
            "qBittorrent/Downloads",
        )
        runCatching { fallback.mkdirs() }
        log(
            LoggableLevel.WARN,
            "公共下载目录不可写（未授予所有文件权限？），保存路径回退：${fallback.absolutePath}",
        )
        return fallback
    }

    /**
     * 凭据/保存路径/内存调优经 WebUI API 下发（localhost 免认证；失败不影响运行态）。
     *
     * 与配置文件层互补的运行态自愈：无论历史配置处于何种状态（如旧版本残留），
     * WebUI 就绪后都对齐 admin + 密码（自定义或默认 adminadmin）、localhost 免认证、
     * 保存路径、内存调优与 DHT 引导节点；qb 会把结果持久化回配置文件。
     */
    private suspend fun applyStartupPreferences(port: Int, savePath: String, webUiPassword: String) {
        val json = QBittorrentSpec.buildStartupPreferencesJson(savePath, webUiPassword)
        val ok = withContext(Dispatchers.IO) {
            httpPostForm(
                "http://127.0.0.1:$port/api/v2/app/setPreferences",
                "json=${URLEncoder.encode(json, "UTF-8")}",
            )
        }
        if (!ok) {
            log(LoggableLevel.WARN, "下发凭据/保存路径/内存调优失败（将沿用配置文件内的设置）")
        } else {
            val passwordNote =
                if (webUiPassword == QBittorrentSpec.DEFAULT_WEBUI_PASSWORD) "默认 adminadmin" else "自定义密码"
            val cacheMiB = QBittorrentSpec.MEMORY_TUNING_JSON["disk_cache"] ?: "?"
            log(
                LoggableLevel.INFO,
                "凭据、保存路径与内存调优已生效（磁盘缓存上限 ${cacheMiB}MiB，100M 宽带深度裁剪；密码：$passwordNote）",
            )
        }
    }

    /**
     * 运行中实例即时改密（setPreferences web_ui_password，qb 哈希后立即生效并落盘）。
     *
     * 供「登录账号」菜单调用：DataStore 已保存新密码，本方法把运行态也同步过去；
     * 实例未运行返回 false（下次启动时 ensureConfig 会写新哈希，无需失败处理）。
     */
    suspend fun applyWebUiPasswordAtRuntime(webUiPassword: String): Boolean {
        val port = _state.value.webUiPort
        if (_state.value.phase != Phase.RUNNING || process?.isAlive != true) return false
        val json = QBittorrentSpec.buildPasswordOnlyJson(webUiPassword)
        return withContext(Dispatchers.IO) {
            httpPostForm(
                "http://127.0.0.1:$port/api/v2/app/setPreferences",
                "json=${URLEncoder.encode(json, "UTF-8")}",
            )
        }
    }

    /**
     * 配置文件准备（进程启动前调用，无并发问题）：
     * - 先删 qBittorrent_new.conf：qb 原子保存的回退文件，正常退出时已被重命名
     *   消失；残留即崩溃/被杀现场（旧状态），且其**启动读取优先级高于正式配置**
     *   ——不清会把种子/对齐的凭据劫持掉（凭据丢失 → nox 每次会话生成随机
     *   临时密码 → 登录 401）；
     * - 不存在正式配置：写种子（含 WebUI 绑定，按 lanAccess 取 0.0.0.0/127.0.0.1，
     *   凭据 admin + 密码 [webUiPassword]（默认 adminadmin，自定义时为其哈希），
     *   保存路径为公共下载目录，内存调优与 DHT 引导键）；
     * - 已存在：对齐 Address/Port/LocalHostAuth/Username/Password_PBKDF2 五键
     *   （QSettings 语义，键序无关）与内存调优/DHT 引导键，保留 nox 自行持久化
     *   的其他键——用户在 WebUI 改的设置不丢（凭据/调优除外：每轮启动对齐）。
     */
    private fun ensureConfig(
        profileDir: File,
        port: Int,
        lanAccess: Boolean,
        savePath: String,
        webUiPassword: String = QBittorrentSpec.DEFAULT_WEBUI_PASSWORD,
    ) {
        val configDir = File(profileDir, "qBittorrent/config")
        val fallbackNew = File(configDir, "qBittorrent_new.conf")
        if (fallbackNew.isFile && runCatching { fallbackNew.delete() }.getOrDefault(false)) {
            log(LoggableLevel.INFO, "已清理崩溃残留的配置回退文件（qBittorrent_new.conf）")
        }
        val confFile = File(configDir, "qBittorrent.conf")
        if (!confFile.isFile) {
            confFile.parentFile?.mkdirs()
            confFile.writeText(
                QBittorrentSpec.buildSeedConfig(port, savePath, lanAccess, webUiPassword)
            )
            return
        }
        confFile.writeText(
            QBittorrentSpec.updateWebUiConfig(confFile.readText(), port, lanAccess, webUiPassword)
        )
    }

    private fun transition(next: Status) {
        _state.value = next
    }

    private fun log(level: LoggableLevel, message: String) {
        eventLog.append("[${level.label}] $message")
    }

    // ---------------------------------------------------------------- HTTP

    private fun httpGet(url: String, timeoutMs: Int): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun httpPostForm(url: String, body: String): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
        const val WEBUI_READY_TIMEOUT_MS = 20_000L
        const val FAST_FAIL_MS = 10_000L
        const val FAST_FAIL_LIMIT = 3

        /** /proc/<pid>/status 中 VmRSS 行（单位固定 kB）。 */
        val VmRssPattern = Regex("VmRSS:\\s+(\\d+) kB")
    }
}
