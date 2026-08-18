package com.xaxka.openlist.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.WebProgressActive
import com.xaxka.openlist.ui.theme.WebProgressTrack

/**
 * Web 页顶部线性进度条（照源 web.dart:74-78）：
 * 高 4dp、轨道 0xFFEEEEEE、前景 0xFF2196F3、无动画插值、无端部圆点/间隙（R12）。
 * progress 达 100% 时由调用方归零（源逻辑：完成即隐藏）。
 */
@Composable
fun FlatLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier.height(Dimens.ProgressLinearHeight),
        color = WebProgressActive,
        trackColor = WebProgressTrack,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp, // R12：显式关闭指示条间隙（关闭性取值，非视觉令牌）
        drawStopIndicator = {},
    )
}
