package com.xaxka.openlist.data.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * qBittorrent 事件日记（`qbittorrent/events.log`，保留最近 24h）：
 * 记录进程启动/停止/异常退出与 nox 子进程 stdout 的关键行，
 * 供「导出事件日记」与 adb pull 排查使用（跨进程重启不丢）。
 */
@Singleton
class QBittorrentEventLog @Inject constructor(
    @ApplicationContext context: Context,
) : FileEventLog(context, "qbittorrent")
