package com.xaxka.openlist.system

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.xaxka.openlist.R
import com.xaxka.openlist.service.ServerState

/**
 * 动态快捷方式管理：随服务状态二选一启用（长按应用图标可见）。
 * 桌面 pin 快捷方式功能已按需求移除。
 */
object ShortCuts {

    private const val ID_START = "openlist_start"
    private const val ID_STOP = "openlist_stop"

    private const val ACTION_START_SERVICE = "START_OPENLIST_SERVICE"
    private const val ACTION_STOP_SERVICE = "STOP_OPENLIST_SERVICE"

    // 短标签资源未入 strings.xml（res 归主控），按 ADR#3 以中文常量承载
    private const val SHORT_LABEL_START = "启动服务"
    private const val SHORT_LABEL_STOP = "停止服务"

    /**
     * 按服务状态整体替换动态快捷方式：
     * 运行中（非 STOPPED）→ 仅「停止服务」；停止 → 仅「启动服务」。
     */
    fun syncDynamic(context: Context, state: ServerState) {
        val shortcuts = if (state != ServerState.STOPPED) {
            listOf(buildStopShortcut(context))
        } else {
            listOf(buildStartShortcut(context))
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    private fun buildStartShortcut(context: Context): ShortcutInfoCompat {
        val intent = Intent(context, SwitchServerActivity::class.java).apply {
            action = ACTION_START_SERVICE
            putExtra(SwitchServerActivity.EXTRA_ACTION, SwitchServerActivity.EXTRA_VALUE_START)
        }
        return ShortcutInfoCompat.Builder(context, ID_START)
            .setShortLabel(SHORT_LABEL_START)
            .setLongLabel(context.getString(R.string.start_openlist_service))
            .setIcon(IconCompat.createWithResource(context, R.drawable.openlist_start))
            .setIntent(intent)
            .build()
    }

    private fun buildStopShortcut(context: Context): ShortcutInfoCompat {
        val intent = Intent(context, SwitchServerActivity::class.java).apply {
            action = ACTION_STOP_SERVICE
            putExtra(SwitchServerActivity.EXTRA_ACTION, SwitchServerActivity.EXTRA_VALUE_STOP)
        }
        return ShortcutInfoCompat.Builder(context, ID_STOP)
            .setShortLabel(SHORT_LABEL_STOP)
            .setLongLabel(context.getString(R.string.stop_openlist_service))
            .setIcon(IconCompat.createWithResource(context, R.drawable.openlist_stop))
            .setIntent(intent)
            .build()
    }
}
