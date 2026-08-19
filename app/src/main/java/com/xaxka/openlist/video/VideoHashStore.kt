package com.xaxka.openlist.video

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 洗码记录表存取接口：path -> 追加后文件大小。
 * 抽象成接口便于 [VideoHashProcessor] 在 JVM 单测中替换为内存实现。
 */
interface VideoHashRecordStore {
    /** 读取全量记录（返回可变副本） */
    fun load(): MutableMap<String, Long>

    /** 全量保存记录 */
    fun save(records: Map<String, Long>)
}

/**
 * 已洗码文件记录表：SharedPreferences `video_hash`，键 `video_hash_processed_files`，
 * 值为 JSON `Map<path, 追加后大小>`。存储名与键结构照源项目（RENAME_MAP §6：名字不变）。
 */
@Singleton
class VideoHashStore @Inject constructor(
    @ApplicationContext context: Context
) : VideoHashRecordStore {

    companion object {
        const val PREFS_NAME = "video_hash"
        const val KEY_PROCESSED_FILES = "video_hash_processed_files"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val serializer = MapSerializer(String.serializer(), Long.serializer())

    override fun load(): MutableMap<String, Long> {
        return try {
            val json = prefs.getString(KEY_PROCESSED_FILES, "") ?: ""
            if (json.isBlank()) {
                mutableMapOf()
            } else {
                Json.decodeFromString(serializer, json).toMutableMap()
            }
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    override fun save(records: Map<String, Long>) {
        try {
            val json = Json.encodeToString(serializer, records)
            prefs.edit().putString(KEY_PROCESSED_FILES, json).apply()
        } catch (e: Exception) {
            // 序列化失败仅丢本次快照，不影响洗码流程
        }
    }
}
