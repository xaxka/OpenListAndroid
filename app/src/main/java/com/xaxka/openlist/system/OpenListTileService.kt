package com.xaxka.openlist.system

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.xaxka.openlist.R
import com.xaxka.openlist.service.ServerManager
import com.xaxka.openlist.service.ServerState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 快捷设置磁贴：点击切换服务启停；监听期间收集 [ServerManager.state] 实时同步磁贴状态。
 * 运行判定与源工程一致：非 STOPPED（含启动中/关闭中）视为运行。
 */
@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.N)
class OpenListTileService : TileService() {

    @Inject lateinit var serverManager: ServerManager

    private var scope: CoroutineScope? = null

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile(serverManager.state.value)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile(serverManager.state.value)
        scope?.cancel()
        val listeningScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = listeningScope
        listeningScope.launch { serverManager.state.collect { updateTile(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        serverManager.toggle(this)
        // 动态快捷方式随目标状态同步（等价源工程服务状态翻转时更新）
        ShortCuts.syncDynamic(this, serverManager.state.value)
    }

    private fun updateTile(state: ServerState) {
        val tile = qsTile ?: return
        val running = state != ServerState.STOPPED
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.openlist_logo)
        tile.contentDescription = getString(
            if (running) R.string.openlist_server_running else R.string.shutdown
        )
        tile.updateTile()
    }
}
