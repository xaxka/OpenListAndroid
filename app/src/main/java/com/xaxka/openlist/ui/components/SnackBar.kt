package com.xaxka.openlist.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import com.xaxka.openlist.ui.theme.Dimens
import com.xaxka.openlist.ui.theme.SnackBarTitle
import com.xaxka.openlist.ui.theme.SnackBackground
import com.xaxka.openlist.ui.theme.SnackBarMessage
import com.xaxka.openlist.ui.theme.SnackOnBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.sqrt

/** Snackbar 主按钮（get snackbar mainButton 等价物） */
class SnackAction(val label: String, val onClick: () -> Unit)

/**
 * Snackbar 数据：全宽贴底、固定深灰背景、白字、可选标题与右侧竖排按钮。
 * 时长由调用方给定（源各调用点 1s/3s/5s）。
 */
class SnackData(
    val message: String,
    val title: String? = null,
    val durationMs: Long = 3000L,
    val actions: List<SnackAction> = emptyList(),
    val onTap: (() -> Unit)? = null,
)

/** Snackbar 状态：同一时刻至多一条，新条目直接覆盖旧条目（照源 closeCurrentSnackbar + show 语义） */
class SnackBarState {
    val current = MutableStateFlow<SnackData?>(null)
    internal var last: SnackData? = null
        private set

    fun show(data: SnackData) {
        last = data
        current.value = data
    }

    fun dismiss() {
        current.value = null
    }
}

/** easeOutCirc：t → sqrt(1-(t-1)^2)（Blue Light §2.6 AnimGetSnack） */
private val EaseOutCirc = object : Easing {
    override fun transform(fraction: Float): Float {
        val x = fraction - 1f
        return sqrt(1f - x * x)
    }
}

private fun <T> snackSpec(durationMillis: Int = 1000) = TweenSpec<T>(durationMillis, easing = EaseOutCirc)

/**
 * 贴底全宽 Snackbar 宿主（复刻 get snackbar 渲染）：
 * 背景 0xFF303030、圆角 0、内边距 16、标题 16/w700 白 + 正文 14/w400 白（间距 6），
 * 主按钮列右距 4，进出动画 1000ms easeOutCirc。
 */
@Composable
fun SnackBarHost(state: SnackBarState, modifier: Modifier = Modifier) {
    val data by state.current.collectAsState()

    // 到时自动消失
    LaunchedEffect(data) {
        if (data != null) {
            delay(data!!.durationMs)
            state.dismiss()
        }
    }

    AnimatedVisibility(
        visible = data != null,
        enter = slideInVertically(animationSpec = snackSpec()) { it } + fadeIn(animationSpec = snackSpec()),
        exit = slideOutVertically(animationSpec = snackSpec()) { it } + fadeOut(animationSpec = snackSpec()),
        modifier = modifier,
    ) {
        val snack = state.last ?: return@AnimatedVisibility
        val interaction = remember { MutableInteractionSource() }
        Surface(
            color = SnackBackground,
            shape = RectangleShape,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { snack.onTap?.invoke() },
                ),
        ) {
            Row(
                modifier = Modifier.padding(Dimens.SnackBarPaddingAll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    snack.title?.let { title ->
                        Text(
                            text = title,
                            style = SnackBarTitle,
                            color = SnackOnBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(Dimens.SnackBarTitleMessageGap))
                    }
                    Text(
                        text = snack.message,
                        style = SnackBarMessage,
                        color = SnackOnBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (snack.actions.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(end = Dimens.SnackBarMainButtonPaddingR),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SnackBarMainButtonPaddingR),
                        horizontalAlignment = Alignment.End,
                    ) {
                        snack.actions.forEach { action ->
                            TextButton(
                                onClick = action.onClick,
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                            ) {
                                Text(text = action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.width(Dimens.SnackBarMainButtonPaddingR))
                }
            }
        }
    }
}
