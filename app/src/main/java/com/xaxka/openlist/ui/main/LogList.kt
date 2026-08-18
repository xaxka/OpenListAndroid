package com.xaxka.openlist.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.xaxka.openlist.data.log.LoggableLevel
import com.xaxka.openlist.data.log.ServerLog
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.LogGreen
import com.xaxka.openlist.ui.theme.LogInfo
import com.xaxka.openlist.ui.theme.LogRed
import com.xaxka.openlist.ui.theme.LogSubtitle
import com.xaxka.openlist.ui.theme.LogTitle
import com.xaxka.openlist.ui.theme.LogWarn

/** 日志级别显示名（照源 tmp/lib/contant/log_level.dart:27-38 toStr） */
private fun logLevelLabel(level: LoggableLevel): String = when (level) {
    LoggableLevel.ERROR -> "Error"
    LoggableLevel.WARN -> "Warn"
    LoggableLevel.INFO -> "Info"
    LoggableLevel.DEBUG -> "Debug"
}

/** 时间戳格式化（照源 MM-dd HH:mm:ss；ServerLog.time 为 epoch 毫秒） */
private fun formatLogTime(epochMillis: Long): String =
    java.text.SimpleDateFormat(
        "MM-dd HH:mm:ss",
        java.util.Locale.US,
    ).format(java.util.Date(epochMillis))

/** 日志级别文字色（照源 log_level.dart:13-25 → PIXEL_SPEC §1.3 五色令牌） */
private fun logLevelColor(level: LoggableLevel): Color = when (level) {
    LoggableLevel.ERROR -> LogRed
    LoggableLevel.WARN -> LogWarn
    LoggableLevel.INFO -> LogInfo
    LoggableLevel.DEBUG -> LogGreen
}

/**
 * 日志区（照源 tmp/lib/pages/alist/log_list_view.dart）：
 * 直接渲染日志列表，无筛选、无清空按钮（清空仅在 FAB 启停时执行，照源 alist.dart:85-89）。
 */
@Composable
internal fun LogSection(
    logs: List<ServerLog>,
    modifier: Modifier = Modifier,
) {
    // 新日志到达即滚到底部（照源 alist.dart:125 jumpTo maxScrollExtent）
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
    ) {
        items(logs) { log ->
            LogItem(log)
        }
    }
}

/**
 * 单条日志：照源 ListTile dense —— 最小高 64、start16/end24、
 * leading 级别文字 11/w500 级别色、title 内容 13sp、subtitle 时间 12sp、可长按选择。
 */
@Composable
private fun LogItem(log: ServerLog, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.ListTileHeight2LineDense)
            .padding(
                start = Dimens.ListTilePaddingStart,
                end = Dimens.ListTilePaddingEnd,
            ),
    ) {
        // leading：级别徽标（时间戳格式照源 MM-dd HH:mm:ss，由 LogBuffer 提供已格式化 time）
        Text(
            text = logLevelLabel(log.level),
            style = MaterialTheme.typography.labelSmall.copy(
                color = logLevelColor(log.level)
            ),
            modifier = Modifier.widthIn(min = Dimens.ListTileMinLeadingWidth),
        )
        Spacer(Modifier.width(Dimens.ListTileHorizontalTitleGap))
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Column {
                Text(log.message, style = LogTitle)
                Text(formatLogTime(log.time), style = LogSubtitle)
            }
        }
    }
}
