package com.xaxka.openlist.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/*
 * 形状令牌：Blue Light UI §2.4。
 * 基础五档：4 / 8 / 12（默认卡片）/ 16 / 28（弹层顶部）；
 * 组件级：chip 6、海报卡 10、匹配类型卡 20、Tab 指示线 2（高 3）。
 */

// ---------- 基础五档 ----------
val ShapeRadius4: CornerBasedShape = RoundedCornerShape(4.dp) // xs：微型元素、输入框、菜单
val ShapeRadius8: CornerBasedShape = RoundedCornerShape(8.dp) // s：小控件
val ShapeCardR12: CornerBasedShape = RoundedCornerShape(12.dp) // m：默认卡片
val ShapeRadius16: CornerBasedShape = RoundedCornerShape(16.dp) // l：大卡
val ShapeDialogR28: CornerBasedShape = RoundedCornerShape(28.dp) // xl：对话框 / 底部弹层顶部

// ---------- 组件级 ----------
/** Chip / 文件条目 6dp 圆角 */
val ShapeChipR6: CornerBasedShape = RoundedCornerShape(6.dp)

/** Tab 指示线 / 进度轨道 2dp 圆角 */
val ShapeLinearProgressR2: CornerBasedShape = RoundedCornerShape(2.dp)

/** 主页 FAB 正圆 */
val ShapeFABCircle: CornerBasedShape = CircleShape

/** TextButton / FilledButton 胶囊形（Stadium） */
val ShapeButtonStadium: CornerBasedShape = RoundedCornerShape(50)

/** NavigationBar 选中指示器胶囊（56×32） */
val ShapeNavIndicatorStadium: CornerBasedShape = RoundedCornerShape(50)

// ---------- 兼容别名（旧名保留，指向新令牌） ----------
val ShapeInputOutlineR4: CornerBasedShape = ShapeRadius4
val ShapeMenuR4: CornerBasedShape = ShapeRadius4
val ShapeFABDefaultR16: CornerBasedShape = ShapeRadius16
