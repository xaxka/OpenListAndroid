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
 * Web 页顶部线性进度条（Blue Light §3.8）：
 * 高 4dp、surfaceVariant 轨道 + primary 进度、2dp 圆角端部、无动画插值。
 * progress 达 100% 时由调用方归零（完成即隐藏）。
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
        strokeCap = StrokeCap.Round, // §2.4 组件级：进度轨道 2dp 圆角
        gapSize = 0.dp, // 显式关闭指示条间隙（关闭性取值，非视觉令牌）
        drawStopIndicator = {},
    )
}
