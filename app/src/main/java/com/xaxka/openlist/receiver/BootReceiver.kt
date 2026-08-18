package com.xaxka.openlist.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xaxka.openlist.data.prefs.AppPrefsRepository
import com.xaxka.openlist.service.ServerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启接收器：BOOT_COMPLETED 且用户开启「开机自启动服务」时启动服务。
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
                val enabled = runCatching { prefs.autostartOnBoot.first() }.getOrDefault(false)
                if (enabled) serverManager.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
