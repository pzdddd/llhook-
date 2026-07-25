package com.example.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.ChatSpyHook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 附近列表「私密相册」自构浏览面板 (替代 Blued 官方 ShowPhotoFragment)。
 *
 *  - 网格展示所有图片直链 (后台 HttpURLConnection + BitmapFactory 解码)
 *  - 点击单张 → 全屏可缩放预览
 *  - 底部「保留到相册库」按钮 → ChatSpyHook.saveAlbumFromUrls 入库 (含昵称/uid)
 *
 * 调用方: NearbyChatHook.bindAlbumClick (在拉黑→拉取→解除拉黑 之后弹出)
 */
@Composable
fun NearbyAlbumViewerPanel(
    activity: Activity,
    uid: String,
    name: String,
    urls: List<String>,
    onClose: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val accent = if (isDark) Color(0xFFA78BFA) else Color(0xFF6366F1)
    val accent2 = if (isDark) Color(0xFF60A5FA) else Color(0xFF3B82F6)
    val accentBrush = Brush.horizontalGradient(listOf(accent, accent2))
    val text = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A)
    val subText = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val cardBg = if (isDark) Color(0xFF1E293B) else Color.White

    // 缓存: url → Bitmap (按需后台加载)
    val bitmaps = remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var previewIdx by remember { mutableStateOf(-1) }

    // 保存状态
    var saving by remember { mutableStateOf(false) }
    var savedCount by remember { mutableStateOf(0) }
    var savedTotal by remember { mutableStateOf(0) }
    var savedDone by remember { mutableStateOf<Int?>(null) }

    // 后台逐张加载 (URL → Bitmap), 进 grid 时再触发更省
    LaunchedEffect(urls) {
        val map = HashMap<String, Bitmap>()
        for (u in urls) {
            if (map.containsKey(u)) continue
            val bmp = withContext(Dispatchers.IO) { decodeUrl(u) }
            if (bmp != null) { map[u] = bmp; bitmaps.value = map.toMap() }
        }
    }

    val bg = if (isDark) Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B)))
        else Brush.verticalGradient(listOf(Color(0xFFEFF6FF), Color(0xFFE0E7FF)))

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize()) {
            // ===== 顶栏 =====
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(accentBrush),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Bookmark, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (name.isBlank()) "私密相册" else name, color = text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("UID: $uid · ${urls.size} 张", color = subText, fontSize = 11.sp, maxLines = 1)
                }
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(cardBg.copy(alpha = 0.6f))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Close, null, tint = text, modifier = Modifier.size(18.dp)) }
            }

            // ===== 网格 =====
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(urls, key = { it }) { u ->
                        val bmp = bitmaps.value[u]
                        Box(
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                .background(if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9))
                                .clickable { previewIdx = urls.indexOf(u) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (bmp != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmp.asImageBitmap(), contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }

            // ===== 底栏: 保留按钮 =====
            Row(
                Modifier.fillMaxWidth().background(cardBg.copy(alpha = 0.5f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("保留到相册库", color = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val sub = when {
                        savedDone != null -> "已保留 ${savedDone}/${urls.size} 张 (含昵称/UID)"
                        saving -> "下载中 $savedCount/$savedTotal..."
                        else -> "下载全部图片入库, 保留昵称与 UID"
                    }
                    Text(sub, color = subText, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(accentBrush)
                        .clickable(enabled = !saving) {
                            if (saving) return@clickable
                            saving = true; savedCount = 0; savedTotal = urls.size; savedDone = null
                            ChatSpyHook.saveAlbumFromUrls(
                                activity = activity, uid = uid, name = name, urls = urls,
                                onProgress = { done, total -> savedCount = done; savedTotal = total },
                                onDone = { n -> saving = false; savedDone = n }
                            )
                        }.padding(horizontal = 18.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (savedDone != null) "已保留" else "保留",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // ===== 全屏预览 =====
    if (previewIdx >= 0 && previewIdx < urls.size) {
        val u = urls[previewIdx]
        val bmp = bitmaps.value[u]
        Box(
            Modifier.fillMaxSize().background(AColor.argb(220, 0, 0, 0).let { Color(it) })
                .clickable { previewIdx = -1 },
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                var scale by remember(u) { mutableStateOf(1f) }
                var offset by remember(u) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize().pointerInput(u) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offset += pan
                        }
                    }.graphicsLayer(scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y)
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
            // 关闭角标
            Box(
                Modifier.align(Alignment.TopEnd).padding(16.dp).size(36.dp)
                    .clip(RoundedCornerShape(50)).background(AColor.argb(120, 0, 0, 0).let { Color(it) })
                    .clickable { previewIdx = -1 },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}

private fun decodeUrl(url: String): Bitmap? = try {
    // ★ 转原图: Blued CDN 缩略图 !Head.jpg → 原图 !Head.jpg!original.png
    val originalUrl = bxxd.hook.ChatSpyHook.toOriginalUrl(url)
    val conn = (URL(originalUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 10000; readTimeout = 15000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
    }
    val bytes = conn.inputStream.use { it.readBytes() }
    conn.disconnect()
    if (bytes.isEmpty()) null
    else {
        // 限制解码尺寸, 避免巨大图 OOM
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val opt = BitmapFactory.Options().apply {
            inSampleSize = if (o.outWidth > 1080 || o.outHeight > 1080) 2 else 1
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
    }
} catch (_: Throwable) { null }
