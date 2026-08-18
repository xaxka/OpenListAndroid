package com.xaxka.openlist.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xaxka.openlist.R
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 手动触发的视频洗码 / 还原任务（无后台周期洗码）。
 *
 * - MODE_SCAN：扫描监听目录（含 3 层子目录），对稳定且未洗码的视频追加文字
 * - MODE_RESTORE：扫描监听目录，检测已洗码的视频并移除追加文字还原
 *
 * 目录 / 追加文字从 InputData 读取，缺省时回退读偏好（兼容仅传 mode 的入队方式）；
 * 进度与完成经 `video_hash` 通知渠道提示。
 */
@HiltWorker
class VideoHashWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val prefs: AppPrefsRepository,
    private val store: VideoHashStore
) : CoroutineWorker(context, params) {

    companion object {
        /** 现行一次性任务唯一名（照源，RENAME_MAP B28 值不变） */
        const val WORK_NAME_ONETIME = "video_hash_onetime"

        /** 旧版遗留周期任务名：仅用于取消升级用户在途的遗留任务（值保留不改） */
        const val LEGACY_WORK_NAME_PERIODIC = "video_hash_periodic"

        const val KEY_MODE = "mode"
        const val KEY_DIRS = "dirs"
        const val KEY_SUFFIX = "suffix"
        const val MODE_SCAN = "scan"
        const val MODE_RESTORE = "restore"

        /** 洗码通知渠道（照源新增独立渠道） */
        const val CHANNEL_ID = "video_hash"
        private const val CHANNEL_NAME = "视频洗码"
        private const val NOTIFICATION_TITLE = "视频洗码"
        private const val NOTIFICATION_ID = 5230

        /**
         * 以唯一任务名入队：APPEND_OR_REPLACE 排队串行执行，同一时刻仅一个洗码/还原任务；
         * 同时取消旧版遗留的周期任务（照源 VideoHashBridge.init）。
         */
        fun enqueue(context: Context, mode: String, dirs: List<String>, suffix: String) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME_PERIODIC)

            val request = OneTimeWorkRequestBuilder<VideoHashWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MODE to mode,
                        KEY_DIRS to dirs.toTypedArray(),
                        KEY_SUFFIX to suffix
                    )
                )
                .build()
            workManager.enqueueUniqueWork(WORK_NAME_ONETIME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    override suspend fun doWork(): Result {
        val mode = inputData.getString(KEY_MODE) ?: MODE_SCAN
        val dirs = inputData.getStringArray(KEY_DIRS)
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: prefs.videoHashDirs.first()
        val suffix = inputData.getString(KEY_SUFFIX) ?: prefs.videoHashSuffix.first()

        if (dirs.isEmpty()) {
            prefs.setVideoHashRunning(false)
            prefs.setVideoHashStatus("未设置监听目录")
            return Result.success()
        }
        if (suffix.isBlank()) {
            prefs.setVideoHashRunning(false)
            prefs.setVideoHashStatus("追加文字为空，请先设置")
            return Result.success()
        }

        prefs.setVideoHashRunning(true)
        ensureChannel()
        val scanning = if (mode == MODE_RESTORE) "正在还原视频文件…" else "正在扫描视频文件…"
        showNotification(scanning, ongoing = true)

        return try {
            val status = withContext(Dispatchers.IO) {
                if (mode == MODE_RESTORE) {
                    VideoHashProcessor.scanAndRestore(store, dirs, suffix)
                } else {
                    VideoHashProcessor.scanAndProcess(store, dirs, suffix)
                }
            }
            prefs.setVideoHashStatus(status)
            val doneTitle = if (mode == MODE_RESTORE) "还原完成" else "洗码完成"
            showNotification("$doneTitle：${status.lineSequence().firstOrNull().orEmpty()}", ongoing = false)
            Result.success()
        } catch (e: Exception) {
            prefs.setVideoHashStatus("处理失败: ${e.message}")
            showNotification("处理失败: ${e.message}", ongoing = false)
            Result.failure()
        } finally {
            prefs.setVideoHashRunning(false)
        }
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** 进度 / 完成通知；未授予通知权限时静默跳过 */
    private fun showNotification(text: String, ongoing: Boolean) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.openlist_logo)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 权限被中途收回时忽略
        }
    }
}
