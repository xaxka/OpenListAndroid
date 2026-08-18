package com.xaxka.openlist.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Go 内核 config.json 数据模型（bridge 侧只读，用于获取 HTTP 端口等启动参数；
 * 完整读写归 agent-data 的 ConfigRepository）。未知字段忽略。
 */
@Serializable
data class CoreConfig(
    @SerialName("bleve_dir")
    val bleveDir: String = "",
    @SerialName("cdn")
    val cdn: String = "",
    @SerialName("delayed_start")
    val delayedStart: Int = 0,
    @SerialName("force")
    val force: Boolean = false,
    @SerialName("jwt_secret")
    val jwtSecret: String = "",
    @SerialName("max_connections")
    val maxConnections: Int = 0,
    @SerialName("min_free_memory")
    val minFreeMemory: Int? = null,
    @SerialName("scheme")
    val scheme: Scheme = Scheme(),
    @SerialName("site_url")
    val siteUrl: String = "",
    @SerialName("temp_dir")
    val tempDir: String = "",
    @SerialName("tls_insecure_skip_verify")
    val tlsInsecureSkipVerify: Boolean = true,
    @SerialName("token_expires_in")
    val tokenExpiresIn: Int = 48,
) {
    @Serializable
    data class Scheme(
        @SerialName("address")
        val address: String = "0.0.0.0",
        @SerialName("cert_file")
        val certFile: String = "",
        @SerialName("force_https")
        val forceHttps: Boolean = false,
        @SerialName("http_port")
        val httpPort: Int = DEFAULT_HTTP_PORT,
        @SerialName("https_port")
        val httpsPort: Int = -1,
        @SerialName("key_file")
        val keyFile: String = "",
        @SerialName("unix_file")
        val unixFile: String = "",
        @SerialName("unix_file_perm")
        val unixFilePerm: String = "",
    )

    companion object {
        /** 与上游 OpenList 一致的默认端口。 */
        const val DEFAULT_HTTP_PORT = 5244

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** 读取 <dataDir>/config.json 的 HTTP 端口，读取失败或文件缺失时返回默认 5244。 */
        fun httpPortOf(dataDir: String): Int = runCatching {
            json.decodeFromString<CoreConfig>(
                File(dataDir, "config.json").readText()
            ).scheme.httpPort
        }.getOrDefault(DEFAULT_HTTP_PORT)
    }
}
