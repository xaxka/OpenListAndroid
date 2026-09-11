package com.xaxka.openlist.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import com.xaxka.openlist.service.EasyTierService
import com.xaxka.openlist.service.ServerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启接收器：BOOT_COMPLETED 时按偏好拉起服务。
 * - 「开机自启动服务」开启 → 启动 OpenList 服务（EasyTier 实例随进程存活自动拉起）；
 * - 服务自启关闭但内网映射开启 → 直接拉起 EasyTier 保活前台服务，把进程带起来，
 *   EasyTierManager 单例构造期自动启动实例（与 OpenList 服务完全解耦）。
 * 偏好值为 DataStore 异步流，goAsync() 保活接收器直至首值读取完成（广播 10s 限额内）。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: AppPrefsRepository

    @Inject lateinit var serverManager: ServerManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // 读取失败视为未开启，避免崩溃导致重复分发
                val autostart = runCatching { prefs.autostartOnBoot.first() }.getOrDefault(false)
                // 个别 ROM 拦截后台启动前台服务会抛异常，吞掉避免开机即崩
                if (autostart) {
                    runCatching { serverManager.start(context) }
                } else {
                    val easytier = runCatching { prefs.easytierEnabled.first() }.getOrDefault(false)
                    if (easytier) {
                        runCatching {
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, EasyTierService::class.java)
                            )
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
