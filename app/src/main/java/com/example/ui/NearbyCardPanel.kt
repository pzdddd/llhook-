package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.Config

/**
 * 附近(身边)列表卡片样式面板 (纯色/渐变)。
 *  - 圆角半径 / 背景色 / 透明度 / 渐变
 *  - 左右边距 / 项间距
 *  - 底部「保存并应用」按钮
 */
@Composable
fun NearbyCardPanel(
    textColor: Color,
    subTextColor: Color,
    glassColor: Color,
    glassBorder: Color
) {
    val ctx = LocalContext.current

    var radius by remember { mutableStateOf((Config.getRaw("nearby_card_radius", "16", ctx).toIntOrNull() ?: 16).toFloat()) }
    var alpha by remember { mutableStateOf((Config.getRaw("nearby_card_alpha", "100", ctx).toIntOrNull() ?: 100).toFloat()) }
    var gradient by remember { mutableStateOf(Config.isFeatureEnabled("nearby_card_gradient", ctx)) }
    var color1 by remember { mutableStateOf(parseColorConfig("nearby_card_color", "0xFFE2E8F0", ctx)) }
    var color2 by remember { mutableStateOf(parseColorConfig("nearby_card_color2", "0xFFE0E7FF", ctx)) }
    var marginH by remember { mutableStateOf((Config.getRaw("nearby_card_margin_h", "8", ctx).toIntOrNull() ?: 8).toFloat()) }
    var gap by remember { mutableStateOf((Config.getRaw("nearby_card_gap", "6", ctx).toIntOrNull() ?: 6).toFloat()) }
    var savedToast by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(8.dp)) {

        // ===== 实时预览 =====
        PreviewCard(radius, alpha, gradient, color1, color2, subTextColor)
        Spacer(Modifier.height(16.dp))

        // —— 圆角半径 ——
        LabelRow("圆角半径", "${radius.toInt()} dp", textColor)
        Slider(value = radius, valueRange = 0f..40f, onValueChange = { radius = it })

        Spacer(Modifier.height(8.dp))
        // —— 不透明度 ——
        LabelRow("背景不透明度", "${alpha.toInt()}%", textColor)
        Slider(value = alpha, valueRange = 0f..100f, onValueChange = { alpha = it })

        Spacer(Modifier.height(14.dp))
        // —— 渐变开关 ——
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
        // —— 卡片边距 ——
        LabelRow("左右外边距", "${marginH.toInt()} dp", textColor)
        Slider(value = marginH, valueRange = 0f..30f, onValueChange = { marginH = it })
        Spacer(Modifier.height(8.dp))
        LabelRow("项间距", "${gap.toInt()} dp", textColor)
        Slider(value = gap, valueRange = 0f..20f, onValueChange = { gap = it })

        Spacer(Modifier.height(20.dp))

        // ===== 保存并应用 =====
        Button(
            onClick = {
                val saved = "0x" + Integer.toHexString(color1.toArgb()).uppercase()
                Config.setRaw("nearby_card_radius", radius.toInt().toString(), ctx)
                Config.setRaw("nearby_card_alpha", alpha.toInt().toString(), ctx)
                Config.setRaw("nearby_card_margin_h", marginH.toInt().toString(), ctx)
                Config.setRaw("nearby_card_gap", gap.toInt().toString(), ctx)
                Config.setFeatureEnabled("nearby_card_gradient", gradient, ctx)
                Config.setRaw("nearby_card_color", saved, ctx)
                Config.setRaw("nearby_card_color2", "0x" + Integer.toHexString(color2.toArgb()).uppercase(), ctx)
                savedToast = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
        ) {
            Text("保存并应用", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        if (savedToast) {
            Spacer(Modifier.height(6.dp))
            Text("✓ 已保存, 下拉刷新附近列表即可生效", color = Color(0xFF22C55E), fontSize = 12.sp)
        }
    }
}

@Composable
fun SegmentMode(useImage: Boolean, onChange: (Boolean) -> Unit, textColor: Color, subTextColor: Color) {
    Text("背景类型", color = textColor, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "纯色/渐变", true to "图库图片").forEach { (mode, label) ->
            val sel = useImage == mode
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (sel) Color(0xFF6366F1) else Color.Gray.copy(alpha = 0.15f),
                modifier = Modifier.weight(1f).clickable { onChange(mode) }
            ) {
                Text(label, color = if (sel) Color.White else textColor,
                    fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PreviewCard(
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
            Text("附近用户卡片", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
        }
    }
}

@Composable
fun LabelRow(label: String, value: String, textColor: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = textColor, fontSize = 13.sp)
        Text(value, color = textColor, fontSize = 13.sp)
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, textColor: Color, subTextColor: Color) {
    val rowBg = if (isSystemInDarkTheme()) Color.Black.copy(alpha = 0.25f) else Color(0xFF0F172A).copy(alpha = 0.04f)
    Surface(shape = RoundedCornerShape(14.dp), color = rowBg, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = subTextColor, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
fun ColorSwatches(selected: Color, onPick: (Color) -> Unit) {
    val palette = listOf(
        Color(0xFFFFFFFF), Color(0xFFF1F5F9), Color(0xFF1E293B), Color(0xFF0F172A),
        Color(0xFFE0E7FF), Color(0xFFDDD6FE), Color(0xFFFCE7F3), Color(0xFFDCFCE7),
        Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF22C55E)
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        palette.chunked(6).forEach { rowColors ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                rowColors.forEach { c ->
                    val isSel = c.toArgb() == selected.toArgb()
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(c).then(
                            if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                            else Modifier.border(1.dp, Color.Black.copy(alpha = 0.15f), RoundedCornerShape(50))
                        ).clickable { onPick(c) }
                    )
                }
            }
        }
    }
}

// ==================== 共享工具 ====================

fun parseColorConfig(key: String, default: String, ctx: android.content.Context? = null): Color {
    val raw = try { Config.getRaw(key, default, ctx) } catch (_: Throwable) { default }
    return try {
        // ★ 修复: "0x..." 按 16 进制解析。旧实现 raw.toLong() 默认 radix=10,
        //   "0xFF22C55E" 不是合法十进制 → 抛异常 → 兜底 Color.White,
        //   导致重开面板时色板回显错误(始终白色)。
        val t = raw.trim()
        val v = when {
            t.startsWith("0x", true) -> t.substring(2).toLong(16).toInt()
            t.startsWith("#") -> t.substring(1).toLong(16).toInt()
            t.startsWith("-") -> t.toLong(10).toInt()
            else -> t.toLong(16).toInt()
        }
        Color(v)
    } catch (_: Throwable) { Color.White }
}

fun decodeBase64ToImageBitmap(b64: String): ImageBitmap? = try {
    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (_: Throwable) { null }
