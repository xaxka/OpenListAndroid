package com.xaxka.openlist.data.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EasyTier 事件日记（`easytier/events.log`，保留最近 24h）。
 *
 * 排查「掉线看门狗重启」等场景时，实例被 EasyTierManager.restartLocked 重建后，
 * 内存 LogBuffer 与 prevEvents（增量去重游标）都会复位，但本文件保留重启前 24h
 * 的事件可供回看/导出（adb pull 或文件管理器读取）。
 */
@Singleton
class EasyTierEventLog @Inject constructor(
    @ApplicationContext context: Context,
) : FileEventLog(context, "easytier")
