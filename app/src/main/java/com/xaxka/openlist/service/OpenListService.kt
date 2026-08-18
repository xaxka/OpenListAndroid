package com.xaxka.openlist.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xaxka.openlist.R
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Go 内核前台服务：通知 + WakeLock + 状态展示（引擎驱动在 ServerManager）。 */
@AndroidEntryPoint
class OpenListService : Service() {
    companion object {
        const val TAG = "OpenListService"

        const val ACTION_START =
            "com.xaxka.openlist.service.OpenListService.ACTION_START"
        const val ACTION_SHUTDOWN =
            "com.xaxka.openlist.service.OpenListService.ACTION_SHUTDOWN"
        const val ACTION_COPY_ADDRESS =
            "com.xaxka.openlist.service.OpenListService.ACTION_COPY_ADDRESS"

        /** 契约保留常量（RENAME_MAP B9）：状态订阅已改用 ServerManager.state Flow。 */
        const val ACTION_STATUS_CHANGED =
            "com.xaxka.openlist.service.OpenListService.ACTION_STATUS_CHANGED"

        const val NOTIFICATION_CHANNEL_ID = "openlist_server"
        const val NOTIFICATION_ID = 5224
        const val WAKE_LOCK_TAG = "openlist::service"
        private const val TEXT_ACTION_COPY_ADDRESS = "复制地址"
    }

    @Inject
    lateinit var serverManager: ServerManager

    @Inject
    lateinit var prefsRepository: AppPrefsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    private val copyAddressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_COPY_ADDRESS) {
                serverManager.copyServerAddress(applicationContext)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // targetSdk 36：必须先于一切耗时操作进入前台
        startForegroundWith(buildNotification(serverManager.state.value))

        registerCopyAddressReceiver()
        observeState()
        observeKeepWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次 startForegroundService 调用后都需再次 startForeground
        startForegroundWith(buildNotification(serverManager.state.value))

        when (intent?.action) {
            ACTION_SHUTDOWN -> serverManager.stop(this)
            else -> serverManager.onServiceStartCommand(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        scope.cancel()
        releaseWakeLock()
        runCatching { unregisterReceiver(copyAddressReceiver) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        serverManager.onServiceDestroyed()
    }

    /** 状态 → 通知文本；STOPPED 时移除通知并结束服务。 */
    private fun observeState() {
        scope.launch {
            serverManager.state.collect { state ->
                if (state == ServerState.STOPPED) {
                    ServiceCompat.stopForeground(
                        this@OpenListService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    stopSelf()
                } else {
                    notifyUpdate(buildNotification(state))
                }
            }
        }
    }

    /** 按 keepWakeLock 开关获取/释放 PARTIAL WakeLock（无超时）。 */
    private fun observeKeepWakeLock() {
        scope.launch {
            prefsRepository.keepWakeLock.collect { enabled ->
                if (enabled) acquireWakeLock() else releaseWakeLock()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun registerCopyAddressReceiver() {
        ContextCompat.registerReceiver(
            this,
            copyAddressReceiver,
            IntentFilter(ACTION_COPY_ADDRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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

    private fun notifyUpdate(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(state: ServerState): Notification {
        // Android 12(S)+ 必须指定 PendingIntent flag；低版本补 UPDATE_CURRENT 以刷新既有意图
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        // 通知点击 → 启动入口（Manifest MAIN/LAUNCHER），等价源 MainActivity 显式 Intent
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            pendingFlags
        )
        val shutdownAction = PendingIntent.getService(
            this,
            1,
            Intent(this, OpenListService::class.java).setAction(ACTION_SHUTDOWN),
            pendingFlags
        )
        val copyAddressAction = PendingIntent.getBroadcast(
            this,
            2,
            Intent(ACTION_COPY_ADDRESS).setPackage(packageName),
            pendingFlags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.openlist_server),
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

        val titleRes = when (state) {
            ServerState.STARTING -> R.string.openlist_starting
            ServerState.RUNNING -> R.string.openlist_server_running
            else -> R.string.openlist_shut_downing
        }

        return builder
            .setContentTitle(getString(titleRes))
            .setContentText(serverManager.serverUrl.value.orEmpty())
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.shutdown), shutdownAction)
            .addAction(0, TEXT_ACTION_COPY_ADDRESS, copyAddressAction)
            .build()
    }
}
