package com.doncc.scrcpygo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 浅色系清新调色板
val LightBackground = Color(0xFFF5F7FA)    // 柔和灰底色
val LightSurface = Color(0xFFFFFFFF)       // 纯白卡片底面
val LightPrimary = Color(0xFF4A90E2)       // 天空蓝（主色）
val LightPrimaryHover = Color(0xFF357ABD)  // 主色悬停态
val LightSecondary = Color(0xFF00B4D8)     // 蒂芙尼绿/蓝（辅色）
val LightTextMain = Color(0xFF2C3E50)      // 深灰蓝（主文本，规避死黑）
val LightTextSub = Color(0xFF7F8C8D)       // 雅灰（次要文本）
val LightBorder = Color(0xFFE2E8F0)        // 清晰边界色
val ColorSuccess = Color(0xFF2ECC71)       // 成功绿
val ColorError = Color(0xFFE74C3C)         // 警告/错误红

val AppColors = lightColors(
    primary = LightPrimary,
    primaryVariant = LightPrimaryHover,
    secondary = LightSecondary,
    background = LightBackground,
    surface = LightSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = LightTextMain,
    onSurface = LightTextMain,
    error = ColorError
)

// 排版规范
val AppTypography = Typography(
    h1 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        color = LightTextMain
    ),
    h2 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = LightTextMain
    ),
    body1 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = LightTextMain
    ),
    body2 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = LightTextSub
    ),
    button = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp
    )
)

// 圆角尺寸规范
val AppShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

/**
 * 主题包裹器
 */
@Composable
fun ScrcpyGoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = AppColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

/**
 * 自定义带悬停渐变动效的卡片组件
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HoverCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    border: BorderStroke? = BorderStroke(1.dp, LightBorder),
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    
    val cardBgColor = if (isHovered) Color(0xFFF8FAFC) else LightSurface
    val elevation = if (isHovered) 3.dp else 1.dp

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = AppShapes.medium,
            backgroundColor = cardBgColor,
            border = border,
            elevation = elevation,
            interactionSource = interactionSource,
            content = content
        )
    } else {
        Card(
            modifier = modifier,
            shape = AppShapes.medium,
            backgroundColor = cardBgColor,
            border = border,
            elevation = elevation,
            content = content
        )
    }
}
