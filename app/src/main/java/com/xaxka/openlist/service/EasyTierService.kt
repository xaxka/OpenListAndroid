package com.xaxka.openlist.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.xaxka.openlist.R
import com.xaxka.openlist.easytier.EasyTierManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * EasyTier 内网映射前台服务（独立保活壳）。
 *
 * 与 OpenListService 解耦（见 EasyTierManager 类注释）：OpenList 服务停止/崩溃时，
 * 本服务继续把进程保活，EasyTier 实例（进程内 JNI）不随 OpenList 启停，链路不断。
 * 实例生命周期完全由 EasyTierManager 驱动：实例启动时拉起本服务、停止时回收本服务。
 *
 * 自停判定：实例状态落入终态（STOPPED/ERROR/UNAVAILABLE）且驻留超过 [TERMINAL_SETTLE_MS]
 * 才结束服务——容忍两类瞬态：冷启动竞态（BootReceiver 先拉本服务、实例稍后才被
 * EasyTierManager 构造期拉起）与自愈重启的瞬时 STOPPED（restartLocked 先停后启）。
 *
 * 通知与 OpenList 服务器通知一致采用 IMPORTANCE_NONE（不弹、不响、状态栏不占位），
 * 仅作为 Android 前台服务保活要求的最小载体；点按仍可回到应用。
 */
@AndroidEntryPoint
class EasyTierService : Service() {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "easytier_service"
        private const val NOTIFICATION_ID = 5245

        /** 终态驻留阈值：超过该时长仍为终态才判定实例确已停止/失败。 */
        private const val TERMINAL_SETTLE_MS = 3_000L

        /** 状态轮询间隔（轻量读内存 StateFlow.value）。 */
        private const val WATCH_INTERVAL_MS = 500L
    }

    @Inject
    lateinit var easyTier: EasyTierManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // targetSdk 36：必须先于一切耗时操作进入前台
        startForegroundWith(buildNotification())

        scope.launch { watchInstanceState() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次 startForegroundService 调用后都需再次 startForeground
        startForegroundWith(buildNotification())
        easyTier.onKeepAliveServiceAlive()
        return START_NOT_STICKY
    }

    /**
     * 实例状态巡检：终态驻留超过 [TERMINAL_SETTLE_MS] 即移除通知并结束本服务
     * （双保险：EasyTierManager.stopLocked 也会显式 stopService 回收本服务）。
     */
    private suspend fun watchInstanceState() {
        while (scope.isActive) {
            val phase = easyTier.state.value.phase
            if (phase.isTerminal()) {
                delay(TERMINAL_SETTLE_MS)
                if (easyTier.state.value.phase.isTerminal()) {
                    ServiceCompat.stopForeground(
                        this@EasyTierService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    stopSelf()
                    return
                }
            }
            delay(WATCH_INTERVAL_MS)
        }
    }

    private fun EasyTierManager.Phase.isTerminal(): Boolean =
        this == EasyTierManager.Phase.STOPPED ||
            this == EasyTierManager.Phase.ERROR ||
            this == EasyTierManager.Phase.UNAVAILABLE

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        easyTier.onKeepAliveServiceGone()
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        // Android 12(S)+ 必须指定 PendingIntent flag；低版本补 UPDATE_CURRENT 以刷新既有意图
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            pendingFlags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.easytier_service),
                NotificationManager.IMPORTANCE_NONE
            ).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.openlist_logo)
        } else {
            Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher_round)
        }

        return builder
            .setContentTitle(getString(R.string.easytier_service_running))
            .setContentText(getString(R.string.easytier_service_desc))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWith(notification: Notification) {
        // specialUse 类型仅 API 34+；低版本传 0 走 manifest 声明
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }
}
