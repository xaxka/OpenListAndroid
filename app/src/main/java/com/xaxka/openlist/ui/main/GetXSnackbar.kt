package com.xaxka.openlist.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.SnackBackground
import com.xaxka.openlist.ui.theme.SnackBarMessage
import com.xaxka.openlist.ui.theme.SnackOnBackground
import com.xaxka.openlist.ui.theme.SnackBarTitle
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/** GetSnackBar 数据：标题可选 + 展示时长（动画/结构照 PIXEL_SPEC §4.7、R11） */
internal data class GetXSnackData(val title: String?, val message: String, val durationMs: Long)

/** 时长/曲线常量照 PIXEL_SPEC §5：1000ms easeOutCirc */
private const val SNACK_ANIM_MS = 1_000
private const val SNACK_DEFAULT_DURATION_MS = 1_000L

/** easeOutCirc：t → sqrt(1-(t-1)^2)（get-4.6.6 snackbar.dart:274-276） */
private val EaseOutCirc = Easing { fraction -> sqrt(1f - (fraction - 1f) * (fraction - 1f)) }

/** GetX Snackbar 状态持有者（本包私有） */
internal class GetXSnackbarState {
    var current by mutableStateOf<GetXSnackData?>(null)
        private set

    fun show(title: String? = null, message: String, durationMs: Long = SNACK_DEFAULT_DURATION_MS) {
        current = GetXSnackData(title, message, durationMs)
    }

    fun dismiss() {
        current = null
    }
}

/**
 * 复刻 get-4.6.6 GetSnackBar：通栏贴底、背景 0xFF303030、圆角 0、
 * 标题 16/w700 白 + 间距 6 + 正文 14/w400 白、内边距 16、进出场 1000ms easeOutCirc。
 */
@Composable
internal fun GetXSnackbarHost(state: GetXSnackbarState, modifier: Modifier = Modifier) {
    val current = state.current
    // 退出动画期间 current 已为 null，保留最后一条数据用于渲染
    var lastData by remember { mutableStateOf<GetXSnackData?>(null) }
    SideEffect { if (current != null) lastData = current }
    val data = current ?: lastData

    AnimatedVisibility(
        visible = current != null,
        enter = fadeIn(tween(SNACK_ANIM_MS, easing = EaseOutCirc)) +
            slideInVertically(tween(SNACK_ANIM_MS, easing = EaseOutCirc)) { it },
        exit = fadeOut(tween(SNACK_ANIM_MS, easing = EaseOutCirc)) +
            slideOutVertically(tween(SNACK_ANIM_MS, easing = EaseOutCirc)) { it },
        modifier = modifier,
    ) {
        data?.let { d ->
            LaunchedEffect(d) {
                delay(d.durationMs)
                state.dismiss()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SnackBackground)
                    .padding(all = Dimens.SnackBarPaddingAll),
            ) {
                d.title?.let { title ->
                    Text(title, style = SnackBarTitle, color = SnackOnBackground)
                    Spacer(Modifier.height(Dimens.SnackBarTitleMessageGap))
                }
                Text(d.message, style = SnackBarMessage, color = SnackOnBackground)
            }
        }
    }
}
