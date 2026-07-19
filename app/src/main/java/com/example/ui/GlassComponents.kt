package com.example.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
//  llhook 玻璃拟态公共组件
//  供所有宿主内 Compose 全屏页 (MapPickerScreen / DetectScreen / ...) 复用。
//  统一: 配色 / 顶栏 / 卡片 / 背景 / 入场动画 / 无障碍 / 最小触控目标。
// ============================================================================

/** 通用配色方案 (浅/深色自适应)。所有宿主内 Compose 页统一调用。 */
@Composable
fun llhookColorScheme(): LlhookColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        LlhookColors(
            text = Color.White,
            subText = Color(0xFFA0AEC0),
            glass = Color(0xCC1E293B),
            glassStroke = Color(0x33FFFFFF),
            accent = Color(0xFF4CAF50),
            danger = Color(0xFFEF4444),
            warning = Color(0xFFFF9800)
        )
    } else {
        LlhookColors(
            text = Color(0xFF1E293B),
            subText = Color(0xFF64748B),
            glass = Color(0xE6FFFFFF),
            glassStroke = Color(0x99FFFFFF),
            accent = Color(0xFF4CAF50),
            danger = Color(0xFFEF4444),
            warning = Color(0xFFFF9800)
        )
    }
}

data class LlhookColors(
    val text: Color,
    val subText: Color,
    val glass: Color,
    val glassStroke: Color,
    val accent: Color,
    val danger: Color,
    val warning: Color
)

// ---------------------------------------------------------------------------
//  背景: 统一的深色基底 + 各页可选的 accent 主题色微染。
//  深色模式基色统一为 slate-900→slate-800 (保证对比度), 浅色模式由调用方决定。
// ---------------------------------------------------------------------------

/** 深色模式统一基色 (slate), 所有页面共用, 保证对比度与一致性。 */
private val DARK_BG_BASE = listOf(Color(0xFF0F172A), Color(0xFF1E293B))

/**
 * 生成页面背景渐变。深色模式基色固定 (保证对比度), 浅色模式调用方传入主题色。
 * @param lightColors 浅色模式渐变两端颜色 (各页可自定义主题氛围)
 */
fun llhookBackgroundBrush(isDark: Boolean, lightColors: List<Color> = listOf(Color(0xFFEFF6FF), Color(0xFFDBEAFE))): Brush =
    if (isDark) Brush.verticalGradient(DARK_BG_BASE) else Brush.verticalGradient(lightColors)

// ---------------------------------------------------------------------------
//  入场动画: 统一的错峰淡入 + 上滑, 用于分区/卡片入场。
// ---------------------------------------------------------------------------

/** 统一的卡片入场动画 (淡入 + 轻微上滑), index 用于错峰延迟。 */
@Composable
fun LlhookAnimatedItem(
    index: Int = 0,
    visible: Boolean = true,
    content: @Composable () -> Unit
) {
    val delay = (index * 40).coerceAtMost(320)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400, delayMillis = delay)) +
                slideInVertically(tween(400, delayMillis = delay), initialOffsetY = { it / 12 }),
        exit = fadeOut(tween(150)),
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

// ---------------------------------------------------------------------------
//  玻璃拟态按钮: 圆形图标按钮 + 任意形状容器。
//  无障碍: 支持 contentDescription + Role.Button + 最小 48dp 触控目标。
// ---------------------------------------------------------------------------

/**
 * 圆形玻璃拟态图标按钮 (返回 / 设置 / 操作)。
 * 自带: contentDescription / 48dp 最小触控区 / disabled 态降透明 / Role.Button 语义。
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    glass: Color,
    stroke: Color,
    modifier: Modifier = Modifier,
    size: Int = 42,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Surface(
        shape = CircleShape,
        color = glass.copy(alpha = alpha * (if (glass.alpha == 0f) 1f else glass.alpha)),
        border = BorderStroke(if (enabled) 1.dp else 0.dp, stroke.copy(alpha = alpha)),
        shadowElevation = if (enabled) 4.dp else 0.dp,
        modifier = modifier
            .size(size.dp)
            .semantics { role = Role.Button; if (contentDescription != null) this.contentDescription = contentDescription }
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** 任意形状的玻璃拟态按钮容器 (带无障碍语义 + enabled 态)。 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    glass: Color,
    stroke: Color,
    shape: Shape = RoundedCornerShape(12.dp),
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Surface(
        shape = shape,
        color = glass.copy(alpha = alpha),
        border = BorderStroke(if (enabled) 1.dp else 0.dp, stroke.copy(alpha = alpha)),
        shadowElevation = if (enabled) 4.dp else 0.dp,
        modifier = modifier
            .semantics { role = Role.Button; if (contentDescription != null) this.contentDescription = contentDescription }
            .clickable(enabled = enabled, onClick = onClick)
    ) { content() }
}

// ---------------------------------------------------------------------------
//  统一顶栏: 返回按钮 + 标题 + 副标题 + 右侧动作槽。
//  所有宿主内功能页复用, 保证交互/间距/无障碍完全一致。
// ---------------------------------------------------------------------------

/**
 * llhook 统一顶栏。
 * @param title 主标题
 * @param onBack 返回回调 (渲染圆形返回箭头按钮)
 * @param actions 右侧动作按钮槽 (GlassIconButton 等)
 */
@Composable
fun LlhookTopBar(
    title: String,
    onBack: () -> Unit,
    glass: Color,
    stroke: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subTextColor: Color = textColor.copy(alpha = 0.7f),
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(
                onClick = onBack, glass = glass, stroke = stroke, size = 42,
                contentDescription = "关闭页面"
            ) {
                Icon(arrowBackVector(), contentDescription = null, tint = textColor)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, color = subTextColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp))
                }
            }
            actions()
        }
    }
}

/** 统一的返回箭头 vector (避免每个页面重复 import)。 */
private fun arrowBackVector(): ImageVector = Icons.Filled.ArrowBack

// ---------------------------------------------------------------------------
//  统一玻璃卡片容器: 替代各页面重复的 Surface + border 写法。
// ---------------------------------------------------------------------------

/**
 * llhook 统一玻璃拟态卡片。带可选标题 + 无障碍 role。
 */
@Composable
fun LlhookSectionCard(
    glass: Color,
    stroke: Color,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = glass,
        border = BorderStroke(1.dp, stroke.copy(alpha = 0.5f)),
        shadowElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.semantics { role = Role.Button }.clickable(onClick = onClick) else it }
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(title, color = titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
            content()
        }
    }
}
