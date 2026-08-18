package com.xaxka.openlist.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * SAF 目录选择辅助：OpenDocumentTree 结果的持久化授权与 treeUri → 真实路径转换。
 * 逻辑照源 MainActivity.kt 的 pickDir / treeUriToPath 移植。
 */
object SafHelper {

    /** 对用户选中的 treeUri 持久化读 + 写授权（重启后仍可访问） */
    fun takePersistableUriPermission(context: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
    }

    /**
     * 将 SAF tree URI 转换为文件系统路径。
     * docId 形如 `primary:Download/videos`（仅支持主存储卷），返回
     * `/storage/emulated/0/Download/videos`；非 primary 卷或解析失败返回 null。
     */
    fun treeUriToPath(treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val split = docId.split(":")
            if (split.size >= 2 && split[0] == "primary") {
                val storagePath = Environment.getExternalStorageDirectory().absolutePath
                val relativePath = split[1]
                if (relativePath.isBlank()) {
                    storagePath
                } else {
                    "$storagePath/$relativePath"
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
