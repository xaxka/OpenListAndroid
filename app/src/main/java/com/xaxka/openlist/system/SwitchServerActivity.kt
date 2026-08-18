package com.xaxka.openlist.system

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.xaxka.openlist.service.ServerManager
import com.xaxka.openlist.service.ServerState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 无 UI 开关入口（透明 Activity，桌面动态/固定快捷方式与外部调用的目标）。
 *
 * 协议（与源工程一致，extra 值为小写）：
 * - `action` = "start"：未运行才启动（幂等）
 * - `action` = "stop"：运行中才停止（幂等）
 * - 缺省/其他：按当前状态切换（toggle）
 *
 * 执行完毕立即同步动态快捷方式并 finish()，不展示任何界面。
 */
@AndroidEntryPoint
class SwitchServerActivity : ComponentActivity() {

    @Inject lateinit var serverManager: ServerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val running = serverManager.state.value != ServerState.STOPPED
        when (intent.getStringExtra(EXTRA_ACTION)) {
            EXTRA_VALUE_START -> if (!running) serverManager.start(this)
            EXTRA_VALUE_STOP -> if (running) serverManager.stop(this)
            else -> serverManager.toggle(this)
        }

        ShortCuts.syncDynamic(this, serverManager.state.value)
        finish()
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_VALUE_START = "start"
        const val EXTRA_VALUE_STOP = "stop"
    }
}
