package com.xaxka.openlist.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

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
     * docId 形如 `primary:Download/videos`（主存储卷）或 `1234-ABCD:Dir`（外置卡卷）。
     * 仅按第一个冒号切分卷与相对路径（目录名本身可含冒号）；
     * 卷不存在（如卡已拔出）或解析失败返回 null。
     */
    fun treeUriToPath(treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val separator = docId.indexOf(':')
            if (separator < 0) return null

            val volumeId = docId.substring(0, separator)
            val relativePath = docId.substring(separator + 1)

            val volumeRoot = if (volumeId == "primary") {
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                // 外置/OTG 卷：docId 卷名即 /storage 下挂载点目录名
                File("/storage", volumeId)
            }
            if (!volumeRoot.exists()) return null

            // File 拼接自动消化相对路径的前导斜杠，避免出现双斜杠
            if (relativePath.isBlank()) volumeRoot.absolutePath
            else File(volumeRoot, relativePath).absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
