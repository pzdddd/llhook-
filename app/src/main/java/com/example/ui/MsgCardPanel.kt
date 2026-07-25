package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.Config

/**
 * 消息列表卡片样式面板 (纯色/渐变)。
 *  - 圆角半径 / 背景色 / 透明度 / 渐变 / 左右边距 / 项间距
 *  与 [NearbyCardPanel] 同构, 配置键独立 msg_card_*, 不影响附近列表。
 */
@Composable
fun MsgCardPanel(
    textColor: Color,
    subTextColor: Color,
    glassColor: Color,
    glassBorder: Color
) {
    val ctx = LocalContext.current

    var radius by remember { mutableStateOf((Config.getRaw("msg_card_radius", "16", ctx).toIntOrNull() ?: 16).toFloat()) }
    var alpha by remember { mutableStateOf((Config.getRaw("msg_card_alpha", "100", ctx).toIntOrNull() ?: 100).toFloat()) }
    var gradient by remember { mutableStateOf(Config.isFeatureEnabled("msg_card_gradient", ctx)) }
    var color1 by remember { mutableStateOf(parseColorConfig("msg_card_color", "0xFFE2E8F0", ctx)) }
    var color2 by remember { mutableStateOf(parseColorConfig("msg_card_color2", "0xFFE0E7FF", ctx)) }
    var marginH by remember { mutableStateOf((Config.getRaw("msg_card_margin_h", "8", ctx).toIntOrNull() ?: 8).toFloat()) }
    var gap by remember { mutableStateOf((Config.getRaw("msg_card_gap", "6", ctx).toIntOrNull() ?: 6).toFloat()) }
    var savedToast by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(8.dp)) {

        // ===== 实时预览 =====
        MsgPreviewCard(radius, alpha, gradient, color1, color2, subTextColor)
        Spacer(Modifier.height(16.dp))

        LabelRow("圆角半径", "${radius.toInt()} dp", textColor)
        Slider(value = radius, valueRange = 0f..40f, onValueChange = { radius = it })

        Spacer(Modifier.height(8.dp))
        LabelRow("背景不透明度", "${alpha.toInt()}%", textColor)
        Slider(value = alpha, valueRange = 0f..100f, onValueChange = { alpha = it })

        Spacer(Modifier.height(14.dp))
        ToggleRow("渐变背景", if (gradient) "主色横向过渡到副色" else "纯色填充", gradient,
            { gradient = it }, textColor, subTextColor)

        Spacer(Modifier.height(14.dp))
        Text("背景主色", color = textColor, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        ColorSwatches(selected = color1) { color1 = it }

        if (gradient) {
            Spacer(Modifier.height(12.dp))
            Text("渐变副色", color = textColor, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            ColorSwatches(selected = color2) { color2 = it }
        }

        Spacer(Modifier.height(16.dp))
        LabelRow("左右外边距", "${marginH.toInt()} dp", textColor)
        Slider(value = marginH, valueRange = 0f..30f, onValueChange = { marginH = it })
        Spacer(Modifier.height(8.dp))
        LabelRow("项间距", "${gap.toInt()} dp", textColor)
        Slider(value = gap, valueRange = 0f..20f, onValueChange = { gap = it })

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                val saved = "0x" + Integer.toHexString(color1.toArgb()).uppercase()
                Config.setRaw("msg_card_radius", radius.toInt().toString(), ctx)
                Config.setRaw("msg_card_alpha", alpha.toInt().toString(), ctx)
                Config.setRaw("msg_card_margin_h", marginH.toInt().toString(), ctx)
                Config.setRaw("msg_card_gap", gap.toInt().toString(), ctx)
                Config.setFeatureEnabled("msg_card_gradient", gradient, ctx)
                Config.setRaw("msg_card_color", saved, ctx)
                Config.setRaw("msg_card_color2", "0x" + Integer.toHexString(color2.toArgb()).uppercase(), ctx)
                savedToast = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
        ) {
            Text("保存并应用", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        if (savedToast) {
            Spacer(Modifier.height(6.dp))
            Text("✓ 已保存, 切换消息页或重新进入即可生效", color = Color(0xFF22C55E), fontSize = 12.sp)
        }
    }
}

@Composable
private fun MsgPreviewCard(
    radius: Float, alpha: Float, gradient: Boolean,
    color1: Color, color2: Color, subTextColor: Color
) {
    val a = (alpha / 100f).coerceIn(0f, 1f)
    val bg: Brush = if (gradient) Brush.horizontalGradient(listOf(color1.copy(alpha = a), color2.copy(alpha = a)))
    else Brush.horizontalGradient(listOf(color1.copy(alpha = a), color1.copy(alpha = a)))
    Column(Modifier.fillMaxWidth()) {
        Text("预览", color = subTextColor, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(72.dp)
                .clip(RoundedCornerShape(radius.dp)).background(bg)
                .border(1.dp, subTextColor.copy(alpha = 0.2f), RoundedCornerShape(radius.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("消息会话卡片", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
        }
    }
}
