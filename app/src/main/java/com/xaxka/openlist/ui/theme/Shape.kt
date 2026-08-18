package com.xaxka.openlist.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/*
 * 形状令牌：PIXEL_SPEC §3.3/§6.4，共 8 个。
 * ShapeLinearProgressR2 在 Compose 中由 LinearProgressIndicator 的 strokeCap 表达（R12），此令牌备查。
 */

/** AlertDialog 圆角（M3 28dp） */
val ShapeDialogR28: CornerBasedShape = RoundedCornerShape(28.dp)

/** 主页 FAB 正圆（源显式覆写 CircleBorder） */
val ShapeFABCircle: CornerBasedShape = CircleShape

/** 全局输入框描边圆角（OutlineInputBorder 默认 R4） */
val ShapeInputOutlineR4: CornerBasedShape = RoundedCornerShape(4.dp)

/** PopupMenu 容器圆角 */
val ShapeMenuR4: CornerBasedShape = RoundedCornerShape(4.dp)

/** TextButton / FilledButton 胶囊形（StadiumBorder） */
val ShapeButtonStadium: CornerBasedShape = RoundedCornerShape(50)

/** NavigationBar 选中指示器胶囊（56×32） */
val ShapeNavIndicatorStadium: CornerBasedShape = RoundedCornerShape(50)

/** 线性进度条端部 2dp 圆角 */
val ShapeLinearProgressR2: CornerBasedShape = RoundedCornerShape(2.dp)

/** M3 FAB 默认 R16（本项目已覆写为正圆，保留备查） */
val ShapeFABDefaultR16: CornerBasedShape = RoundedCornerShape(16.dp)
