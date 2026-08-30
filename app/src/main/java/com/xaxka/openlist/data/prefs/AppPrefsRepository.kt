package com.xaxka.openlist.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.xaxka.openlist.qbt.QBittorrentSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// DataStore 单例（文件名 openlist_prefs，RENAME_MAP B27）
private val Context.openlistPrefs by preferencesDataStore(name = "openlist_prefs")

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
        val EASYTIER_ENABLED = booleanPreferencesKey("isEasyTierEnabled")
        val EASYTIER_NETWORK = stringPreferencesKey("easyTierNetwork")
        val EASYTIER_NETWORK_SECRET = stringPreferencesKey("easyTierNetworkSecret")
        val EASYTIER_PEER_URI = stringPreferencesKey("easyTierPeerUri")
        val EASYTIER_QUIC_PROXY = booleanPreferencesKey("easyTierQuicProxy")
        val EASYTIER_SECURE_MODE = booleanPreferencesKey("easyTierSecureMode")
        val EASYTIER_LOCAL_PRIVATE_KEY = stringPreferencesKey("easyTierLocalPrivateKey")
        val EASYTIER_LOCAL_PUBLIC_KEY = stringPreferencesKey("easyTierLocalPublicKey")
        val QBT_ENABLED = booleanPreferencesKey("isQbtEnabled")
        val QBT_WEBUI_PORT = stringPreferencesKey("qbtWebUiPort")
        val QBT_LAN_ACCESS = booleanPreferencesKey("qbtLanAccess")
    }

    /** 默认数据目录：getExternalFilesDir("data") 绝对路径（不可用时回退 filesDir/data） */
    val defaultDataDir: String =
        context.getExternalFilesDir("data")?.absolutePath
            ?: File(context.filesDir, "data").absolutePath

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

    /** EasyTier 内网映射总开关（默认关闭） */
    val easytierEnabled: Flow<Boolean> = data.map { it[Keys.EASYTIER_ENABLED] ?: false }

    /** EasyTier 网络名称（空白回退默认网络） */
    val easytierNetwork: Flow<String> = data.map { it[Keys.EASYTIER_NETWORK] ?: "" }

    /** EasyTier 网络密钥 */
    val easytierNetworkSecret: Flow<String> = data.map { it[Keys.EASYTIER_NETWORK_SECRET] ?: "" }

    /** EasyTier 对等节点 URI（空白则不配置 peer） */
    val easytierPeerUri: Flow<String> = data.map { it[Keys.EASYTIER_PEER_URI] ?: "" }

    /** EasyTier QUIC 代理（enable_quic_proxy；把 TCP 流转为 QUIC 传输），默认开启 */
    val easytierQuicProxy: Flow<Boolean> = data.map { it[Keys.EASYTIER_QUIC_PROXY] ?: true }

    /** EasyTier 安全模式（[secure_mode] enabled：E2EE + Noise 握手 + 防重放），默认关闭保持旧网络兼容 */
    val easytierSecureMode: Flow<Boolean> = data.map { it[Keys.EASYTIER_SECURE_MODE] ?: false }

    /** EasyTier 安全模式本机 X25519 私钥（base64，首次开启安全模式时自动生成并持久化，保持节点身份稳定） */
    val easytierLocalPrivateKey: Flow<String> = data.map { it[Keys.EASYTIER_LOCAL_PRIVATE_KEY] ?: "" }

    /** EasyTier 安全模式本机 X25519 公钥（base64，由私钥派生） */
    val easytierLocalPublicKey: Flow<String> = data.map { it[Keys.EASYTIER_LOCAL_PUBLIC_KEY] ?: "" }

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

    suspend fun setEasytierEnabled(value: Boolean) = edit { it[Keys.EASYTIER_ENABLED] = value }

    suspend fun setEasytierNetwork(value: String) = edit { it[Keys.EASYTIER_NETWORK] = value }

    suspend fun setEasytierNetworkSecret(value: String) = edit { it[Keys.EASYTIER_NETWORK_SECRET] = value }

    suspend fun setEasytierPeerUri(value: String) = edit { it[Keys.EASYTIER_PEER_URI] = value }

    suspend fun setEasytierQuicProxy(value: Boolean) = edit { it[Keys.EASYTIER_QUIC_PROXY] = value }

    suspend fun setEasytierSecureMode(value: Boolean) = edit { it[Keys.EASYTIER_SECURE_MODE] = value }

    suspend fun setEasytierLocalPrivateKey(value: String) = edit { it[Keys.EASYTIER_LOCAL_PRIVATE_KEY] = value }

    suspend fun setEasytierLocalPublicKey(value: String) = edit { it[Keys.EASYTIER_LOCAL_PUBLIC_KEY] = value }

    // ---------- qbittorrent ----------

    /** qbittorrent 内置 nox 总开关（默认关闭） */
    val qbtEnabled: Flow<Boolean> = data.map { it[Keys.QBT_ENABLED] ?: false }

    /** WebUI 端口（字符串存储；空白回退默认端口，解析见 QBittorrentSpec.parsePort） */
    val qbtWebUiPort: Flow<String> = data.map { raw ->
        (raw[Keys.QBT_WEBUI_PORT] ?: "").ifBlank { QBittorrentSpec.DEFAULT_WEBUI_PORT.toString() }
    }

    suspend fun setQbtEnabled(value: Boolean) = edit { it[Keys.QBT_ENABLED] = value }

    suspend fun setQbtWebUiPort(value: String) = edit { it[Keys.QBT_WEBUI_PORT] = value }

    /** 局域网访问 WebUI（0.0.0.0 监听 + 登录 admin/adminadmin；本机仍免认证），默认关闭 */
    val qbtLanAccess: Flow<Boolean> = data.map { it[Keys.QBT_LAN_ACCESS] ?: false }

    suspend fun setQbtLanAccess(value: Boolean) = edit { it[Keys.QBT_LAN_ACCESS] = value }

    // ---------- 内部 ----------

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.openlistPrefs.edit { block(it) }
    }
}
