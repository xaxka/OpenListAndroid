package com.xaxka.openlist.bridge

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * config.json 应用偏好同步（App 偏好 → Go 内核启动参数的唯一写入口）。
 *
 * 「不使用内存缓存」联动 `min_free_memory`：
 * - 开启 → 写入 -1，内核启动时输出 `disable memory cache`，HybridCache 全部走文件后备；
 * - 关闭 → 仅移除本应用写入的 -1，恢复内核默认（自动内存缓存）；用户手工配置的其他值不动。
 *
 * 必须在 `Alistlib.init()` 之前调用：内核 `bootstrap.InitConfig()` 只在读 config.json 时
 * 应用该值。全量 JsonObject 复制保留未知字段；任何失败静默返回 FAILED，不阻断启动。
 */
object CoreConfigSync {

    private const val KEY_MIN_FREE_MEMORY = "min_free_memory"
    private const val VALUE_DISABLE_MEMORY_CACHE = -1

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    enum class Outcome { UPDATED, NO_CHANGE, FAILED }

    /**
     * 把「不使用内存缓存」偏好写入 <dataDir>/config.json。
     * 首次启动文件不存在时创建仅含该键的最小配置，内核读取后会补全其余默认字段。
     */
    fun syncNoMemoryCache(dataDir: String, noMemoryCache: Boolean): Outcome {
        val file = File(dataDir, "config.json")
        val root: JsonObject = when {
            !file.exists() -> JsonObject(emptyMap())
            else -> runCatching { json.parseToJsonElement(file.readText()).jsonObject }
                .getOrElse { return Outcome.FAILED }
        }

        val current = (root[KEY_MIN_FREE_MEMORY] as? JsonPrimitive)?.intOrNull
        val updated: JsonObject = if (noMemoryCache) {
            if (current == VALUE_DISABLE_MEMORY_CACHE) return Outcome.NO_CHANGE
            JsonObject(
                LinkedHashMap(root).apply {
                    put(KEY_MIN_FREE_MEMORY, JsonPrimitive(VALUE_DISABLE_MEMORY_CACHE))
                }
            )
        } else {
            // 仅移除本应用写入的 -1；用户手工设置的正值（MB）保持原样
            if (current != VALUE_DISABLE_MEMORY_CACHE) return Outcome.NO_CHANGE
            JsonObject(
                LinkedHashMap(root).apply { remove(KEY_MIN_FREE_MEMORY) }
            )
        }

        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(JsonObject.serializer(), updated))
            Outcome.UPDATED
        }.getOrElse { Outcome.FAILED }
    }
}
