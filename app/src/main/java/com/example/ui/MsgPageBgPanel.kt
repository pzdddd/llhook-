package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.Config
import bxxd.hook.MsgPageBgHook

/**
 * 消息页背景面板: 给消息 Tab 整页设置背景。
 *  - 纯色 / 渐变 (调色板) / 图库图片 (base64 跨进程同步)
 *  与 [NearbyPageBgPanel] 同构, 配置键独立 msg_page_bg_*, 不影响身边页。
 */
@Composable
fun MsgPageBgPanel(
    textColor: Color,
    subTextColor: Color,
    glassColor: Color,
    glassBorder: Color
) {
    val ctx = LocalContext.current

    var alpha by remember { mutableStateOf((Config.getRaw("msg_page_bg_alpha", "100", ctx).toIntOrNull() ?: 100).toFloat()) }
    var gradient by remember { mutableStateOf(Config.isFeatureEnabled("msg_page_bg_gradient", ctx)) }
    var color1 by remember { mutableStateOf(parseColorConfig("msg_page_bg_color", "0xFFE0E7FF", ctx)) }
    var color2 by remember { mutableStateOf(parseColorConfig("msg_page_bg_color2", "0xFFDDD6FE", ctx)) }
    var useImage by remember { mutableStateOf(Config.isFeatureEnabled("msg_page_bg_use_image", ctx)) }
    var imgAlpha by remember { mutableStateOf((Config.getRaw("msg_page_bg_image_alpha", "100", ctx).toIntOrNull() ?: 100).toFloat()) }
    var imgB64 by remember { mutableStateOf(try { Config.getRaw("msg_page_bg_image", "", ctx) } catch (_: Throwable) { "" }) }
    var savedToast by remember { mutableStateOf(false) }

    // 选图 Activity 回来后刷新
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                imgB64 = try { Config.getRaw("msg_page_bg_image", "", ctx) } catch (_: Throwable) { "" }
                useImage = Config.isFeatureEnabled("msg_page_bg_use_image", ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxWidth().padding(8.dp)) {

        MsgPreviewPage(alpha, gradient, color1, color2, useImage, imgB64, imgAlpha, subTextColor)
        Spacer(Modifier.height(16.dp))

        SegmentMode(useImage, { useImage = it }, textColor, subTextColor)
        Spacer(Modifier.height(14.dp))

        if (!useImage) {
            LabelRow("背景不透明度", "${alpha.toInt()}%", textColor)
            Slider(value = alpha, valueRange = 0f..100f, onValueChange = { alpha = it })

            Spacer(Modifier.height(14.dp))
            ToggleRow("渐变背景", if (gradient) "主色纵向过渡到副色" else "纯色填充", gradient,
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
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    try {
                        com.example.ImagePickerActivity.launch(ctx, "msg_page_bg_image", "msg_page_bg_use_image")
                    } catch (t: Throwable) {
                        android.widget.Toast.makeText(ctx, "启动失败: ${t.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.weight(1f)) {
                    Text("从图库选择", color = textColor)
                }
                OutlinedButton(onClick = {
                    imgB64 = ""; useImage = false
                    Config.setRaw("msg_page_bg_image", "", ctx)
                    Config.setFeatureEnabled("msg_page_bg_use_image", false, ctx)
                }, modifier = Modifier.weight(1f)) {
                    Text("清除图片", color = textColor)
                }
            }
            Spacer(Modifier.height(12.dp))
            LabelRow("图片不透明度", "${imgAlpha.toInt()}%", textColor)
            Slider(value = imgAlpha, valueRange = 0f..100f, onValueChange = { imgAlpha = it })

            if (imgB64.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("尚未选择图片, 点击「从图库选择」", color = subTextColor, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                val saved = "0x" + Integer.toHexString(color1.toArgb()).uppercase()
                Config.setRaw("msg_page_bg_alpha", alpha.toInt().toString(), ctx)
                Config.setFeatureEnabled("msg_page_bg_gradient", gradient, ctx)
                Config.setRaw("msg_page_bg_color", saved, ctx)
                Config.setRaw("msg_page_bg_color2", "0x" + Integer.toHexString(color2.toArgb()).uppercase(), ctx)
                Config.setFeatureEnabled("msg_page_bg_use_image", useImage, ctx)
                Config.setRaw("msg_page_bg_image", imgB64, ctx)
                Config.setRaw("msg_page_bg_image_alpha", imgAlpha.toInt().toString(), ctx)
                MsgPageBgHook.invalidate()
                savedToast = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
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
private fun MsgPreviewPage(
    alpha: Float, gradient: Boolean, color1: Color, color2: Color,
    useImage: Boolean, imgB64: String, imgAlpha: Float, subTextColor: Color
) {
    val a = (alpha / 100f).coerceIn(0f, 1f)
    val bg: Brush = if (gradient) Brush.verticalGradient(listOf(color1.copy(alpha = a), color2.copy(alpha = a)))
                    else Brush.verticalGradient(listOf(color1.copy(alpha = a), color1.copy(alpha = a)))
    val previewBmp: ImageBitmap? = if (useImage && imgB64.isNotEmpty()) decodeBase64ToImageBitmap(imgB64) else null

    Column(Modifier.fillMaxWidth()) {
        Text("预览", color = subTextColor, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp))
                .then(if (previewBmp != null) Modifier.background(Color.DarkGray) else Modifier.background(bg))
                .border(1.dp, subTextColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (previewBmp != null) {
                Image(
                    bitmap = previewBmp, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = (imgAlpha / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp))
                )
                Text("消息页背景", color = Color.White.copy(alpha = 0.95f), fontSize = 13.sp)
            } else {
                Text("消息页背景", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
            }
        }
    }
}
