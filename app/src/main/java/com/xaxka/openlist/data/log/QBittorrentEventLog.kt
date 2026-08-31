package com.xaxka.openlist.data.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * qBittorrent 事件日记（`qbittorrent/events.log`，保留最近 24h）：
 * 记录进程启动/停止/异常退出等 Manager 侧生命周期事件与 nox 子进程 stdout 的
 * 关键行，供 adb pull 排查使用（跨进程重启不丢）。
 *
 * qb 自身日志见 `qbt-profile/qBittorrent/data/logs/qbittorrent.log`
 * （WebUI「日志」页同源）；设置页已移除 App 侧「导出事件日记」入口
 * （日记以 qbittorrent 自带为准），本类仅保留作 Manager 生命周期排查。
 */
@Singleton
class QBittorrentEventLog @Inject constructor(
    @ApplicationContext context: Context,
) : FileEventLog(context, "qbittorrent")
