package com.xaxka.openlist.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// DataStore 单例（文件名 openlist_prefs，RENAME_MAP B27）
private val Context.openlistPrefs by preferencesDataStore(name = "openlist_prefs")

/** 应用偏好快照（一次性读取全部键） */
data class AppPrefs(
    val silentJumpApp: Boolean = false,
    val keepWakeLock: Boolean = false,
    val autostartOnBoot: Boolean = false,
    val autoOpenWeb: Boolean = false,
    val noMemoryCache: Boolean = true,
    val darkMode: Boolean = false,
    val dynamicColor: Boolean = false,
    val dataDir: String = "",
    val videoHashSuffix: String = "HashMod",
    val videoHashDirs: List<String> = emptyList(),
    val videoHashRunning: Boolean = false,
    val videoHashStatus: String = "",
)

/**
 * 应用配置仓储：DataStore Preferences。
 * 键名与默认值严格对照 FEATURE_MATRIX §6（源 config/AppConfig.kt），另增界面 darkMode/dynamicColor。
 */
@Singleton
class AppPrefsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        val SILENT_JUMP_APP = booleanPreferencesKey("isSilentJumpAppEnabled")
        val KEEP_WAKE_LOCK = booleanPreferencesKey("isWakeLockEnabled")
        val START_AT_BOOT = booleanPreferencesKey("isStartAtBootEnabled")
        val AUTO_OPEN_WEB_PAGE = booleanPreferencesKey("isAutoOpenWebPageEnabled")
        val NO_MEMORY_CACHE = booleanPreferencesKey("isNoMemoryCacheEnabled")
        val DARK_MODE = booleanPreferencesKey("darkMode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamicColor")
        val DATA_DIR = stringPreferencesKey("dataDir")
        val VIDEO_HASH_SUFFIX = stringPreferencesKey("videoHashSuffix")
        val VIDEO_HASH_DIRS = stringPreferencesKey("videoHashDirs")
        val VIDEO_HASH_RUNNING = booleanPreferencesKey("videoHashRunning")
        val VIDEO_HASH_STATUS = stringPreferencesKey("videoHashStatus")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 默认数据目录：getExternalFilesDir("data") 绝对路径（不可用时回退 filesDir） */
    val defaultDataDir: String =
        context.getExternalFilesDir("data")?.absolutePath ?: context.filesDir.absolutePath

    // 读取损坏时回退空配置（DataStore 惯例）
    private val data = context.openlistPrefs.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    // ---------- Flow 读取 ----------

    /** WebView 静默跳转外部 App */
    val silentJumpApp: Flow<Boolean> = data.map { it[Keys.SILENT_JUMP_APP] ?: false }

    /** 前台服务 WakeLock 开关 */
    val keepWakeLock: Flow<Boolean> = data.map { it[Keys.KEEP_WAKE_LOCK] ?: false }

    /** 开机自启动服务 */
    val autostartOnBoot: Flow<Boolean> = data.map { it[Keys.START_AT_BOOT] ?: false }

    /** 服务运行时自动打开网页（网页设为首页） */
    val autoOpenWeb: Flow<Boolean> = data.map { it[Keys.AUTO_OPEN_WEB_PAGE] ?: false }

    /** 不使用内存缓存（联动 config.json min_free_memory = -1），默认开启 */
    val noMemoryCache: Flow<Boolean> = data.map { it[Keys.NO_MEMORY_CACHE] ?: true }

    /** 深色模式（false = 跟随系统亮色表现） */
    val darkMode: Flow<Boolean> = data.map { it[Keys.DARK_MODE] ?: false }

    /** Material You 动态取色（默认 false，保持固定主题） */
    val dynamicColor: Flow<Boolean> = data.map { it[Keys.DYNAMIC_COLOR] ?: false }

    /** OpenList 数据目录；空白回退默认值（源 AppConfig.kt 行为） */
    val dataDir: Flow<String> = data.map { raw ->
        (raw[Keys.DATA_DIR] ?: defaultDataDir).ifBlank { defaultDataDir }
    }

    /** 视频洗码追加文字 */
    val videoHashSuffix: Flow<String> = data.map { it[Keys.VIDEO_HASH_SUFFIX] ?: "HashMod" }

    /** 视频洗码监听目录（JSON 数组存储，解析失败回空列表） */
    val videoHashDirs: Flow<List<String>> = data.map { raw ->
        decodeDirs(raw[Keys.VIDEO_HASH_DIRS] ?: "")
    }

    /** 洗码任务运行标志（Worker 维护，供 UI 轮询） */
    val videoHashRunning: Flow<Boolean> = data.map { it[Keys.VIDEO_HASH_RUNNING] ?: false }

    /** 最近一次洗码/还原结果文本 */
    val videoHashStatus: Flow<String> = data.map { it[Keys.VIDEO_HASH_STATUS] ?: "" }

    // ---------- 快照 ----------

    /** 一次性读取全部偏好 */
    suspend fun snapshot(): AppPrefs {
        val prefs = data.first()
        return AppPrefs(
            silentJumpApp = prefs[Keys.SILENT_JUMP_APP] ?: false,
            keepWakeLock = prefs[Keys.KEEP_WAKE_LOCK] ?: false,
            autostartOnBoot = prefs[Keys.START_AT_BOOT] ?: false,
            autoOpenWeb = prefs[Keys.AUTO_OPEN_WEB_PAGE] ?: false,
            noMemoryCache = prefs[Keys.NO_MEMORY_CACHE] ?: true,
            darkMode = prefs[Keys.DARK_MODE] ?: false,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            dataDir = (prefs[Keys.DATA_DIR] ?: defaultDataDir).ifBlank { defaultDataDir },
            videoHashSuffix = prefs[Keys.VIDEO_HASH_SUFFIX] ?: "HashMod",
            videoHashDirs = decodeDirs(prefs[Keys.VIDEO_HASH_DIRS] ?: ""),
            videoHashRunning = prefs[Keys.VIDEO_HASH_RUNNING] ?: false,
            videoHashStatus = prefs[Keys.VIDEO_HASH_STATUS] ?: "",
        )
    }

    // ---------- 写入 ----------

    suspend fun setSilentJumpApp(value: Boolean) = edit { it[Keys.SILENT_JUMP_APP] = value }

    suspend fun setKeepWakeLock(value: Boolean) = edit { it[Keys.KEEP_WAKE_LOCK] = value }

    suspend fun setAutostartOnBoot(value: Boolean) = edit { it[Keys.START_AT_BOOT] = value }

    suspend fun setAutoOpenWeb(value: Boolean) = edit { it[Keys.AUTO_OPEN_WEB_PAGE] = value }

    suspend fun setNoMemoryCache(value: Boolean) = edit { it[Keys.NO_MEMORY_CACHE] = value }

    suspend fun setDarkMode(value: Boolean) = edit { it[Keys.DARK_MODE] = value }

    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = value }

    /** 写入数据目录；空白回退默认值（源 AppConfig.kt setter 行为） */
    suspend fun setDataDir(value: String) = edit { it[Keys.DATA_DIR] = value.ifBlank { defaultDataDir } }

    suspend fun setVideoHashSuffix(value: String) = edit { it[Keys.VIDEO_HASH_SUFFIX] = value }

    suspend fun setVideoHashDirs(value: List<String>) = edit {
        it[Keys.VIDEO_HASH_DIRS] = json.encodeToString(ListSerializer(String.serializer()), value)
    }

    suspend fun setVideoHashRunning(value: Boolean) = edit { it[Keys.VIDEO_HASH_RUNNING] = value }

    suspend fun setVideoHashStatus(value: String) = edit { it[Keys.VIDEO_HASH_STATUS] = value }

    // ---------- 内部 ----------

    private fun decodeDirs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.openlistPrefs.edit { block(it) }
    }
}
