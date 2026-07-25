package com.example.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.AutoVisitHook
import bxxd.hook.Config
import bxxd.hook.NetworkSpoofHook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// ============================================================================
//  私密相册远程浏览 (Compose 玻璃拟态全屏页)
//
//  原理: 调用 Blued 官方完整资料接口, 取出 album 数组直接展示, 免进资料页。
//    API: https://social.blued.cn/users/{uid}?from=nearby&is_living=false
//                        &is_live_flow=1&is_vip_page=0
//    (BluedHttpUrl.u() = "https://social.blued.cn", 与 UserInfoNewPresenter 同源)
//    响应: {"data": { ... , "album": [ {"url":"...", "pid":"...", ...}, ... ] }}
//    BluedAlbum.url 即图片直链, 服务器即便锁定也返回真实地址 (applyStatus 仅控 UI 遮罩)。
//
//  入口: 附近列表相册按钮 (NearbyChatHook, switch_nearby_album)。
//  数据: AutoVisitHook.cachedToken / Config.getAuthToken 取凭证。
// ============================================================================

@Composable
fun PrivateAlbumScreen(activity: Activity, uid: String, name: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()

    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var previewUrl by remember { mutableStateOf<String?>(null) }

    // 拉取相册数据
    LaunchedEffect(uid) {
        loading = true; errorMsg = null
        try {
            val result = withContext(Dispatchers.IO) { fetchPrivateAlbum(ctx, uid) }
            if (result == null) {
                errorMsg = "获取失败\n请先在大厅下拉刷新一次附近列表\n(需捕获登录凭证)"
            } else if (result.isEmpty()) {
                errorMsg = "该用户暂无私密相册\n(或对方未上传 / 已删除)"
            } else {
                items = result
            }
        } catch (e: Throwable) {
            errorMsg = "网络错误: ${e.message}"
        }
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(llhookBackgroundBrush(isDark, listOf(Color(0xFFFFF7ED), Color(0xFFFFEDD5))))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ============ 顶栏 ============
            LlhookTopBar(
                title = "「${name.ifBlank { uid }}」私密相册",
                subtitle = if (loading) "加载中..." else "${items.size} 张",
                onBack = onClose,
                glass = colors.glass, stroke = colors.glassStroke,
                textColor = colors.text, subTextColor = colors.subText
            )

            // ============ 内容 ============
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
                errorMsg != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🖼", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(errorMsg!!, color = colors.subText, fontSize = 13.sp,
                            textAlign = TextAlign.Center, lineHeight = 18.sp)
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(items, key = { it.pid.ifEmpty { it.url } }) { item ->
                        RemotePhotoCell(item, colors,
                            onClick = { previewUrl = item.url },
                            onSave = { scope.launch { saveImage(ctx, item.url) } })
                    }
                }
            }
        }
    }

    // ============ 大图预览 ============
    previewUrl?.let { url ->
        RemotePreviewDialog(url, name, colors) { previewUrl = null }
    }
}

// ============================================================================
//  远程照片单元格 (懒加载 + 长按保存)
// ============================================================================
private data class AlbumItem(val url: String, val pid: String)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RemotePhotoCell(
    item: AlbumItem,
    colors: LlhookColors,
    onClick: () -> Unit,
    onSave: () -> Unit
) {
    var bmp by remember(item.url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(item.url) { mutableStateOf(false) }

    LaunchedEffect(item.url) {
        bmp = withContext(Dispatchers.IO) { loadBitmap(item.url) }
        if (bmp == null) failed = true
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E293B))
            .combinedClickable(onClick = onClick, onLongClick = onSave)
    ) {
        when {
            bmp != null -> Image(
                bitmap = bmp!!.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.BrokenImage, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ============================================================================
//  大图预览 (远程)
// ============================================================================
@Composable
private fun RemotePreviewDialog(url: String, name: String, colors: LlhookColors, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(url) { mutableStateOf(true) }

    LaunchedEffect(url) {
        loading = true
        bmp = withContext(Dispatchers.IO) { loadBitmap(url) }
        loading = false
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                when {
                    bmp != null -> Image(
                        bitmap = bmp!!.asImageBitmap(), contentDescription = "预览",
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    )
                    loading -> CircularProgressIndicator(color = Color.White)
                    else -> {
                        Icon(Icons.Filled.BrokenImage, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("图片加载失败", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                // 保存按钮 (不随点击关闭)
                Surface(
                    shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier.clickable {
                        scope.launch { saveImage(ctx, url) }
                    }
                ) {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Download, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存到秘密相册", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("点击空白处关闭", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
            }
        }
    }
}

// ============================================================================
//  网络与工具
// ============================================================================

/**
 * 调用完整资料接口, 解析 album 数组。
 * 返回 null = 无凭证; empty = 有数据但无私密相册。
 */
private fun fetchPrivateAlbum(ctx: Context, uid: String): List<AlbumItem>? {
    val token = AutoVisitHook.cachedToken.ifEmpty { Config.getAuthToken(ctx) }
    if (token.isEmpty()) return null

    val ua = NetworkSpoofHook.capturedLatestUA.ifEmpty { AutoVisitHook.cachedUserAgent }
    val urlStr = "https://social.blued.cn/users/$uid?from=nearby&is_living=false&is_live_flow=1&is_vip_page=0"

    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("authorization", token)
        setRequestProperty("user-agent", ua)
        connectTimeout = 8000
        readTimeout = 8000
    }
    try {
        if (conn.responseCode != 200) return emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(body)
        // data 可能是对象 (完整资料) 或数组 (兼容 /basic 风格)
        val dataObj = root.opt("data") ?: return emptyList()
        val obj = when (dataObj) {
            is JSONObject -> dataObj
            is org.json.JSONArray -> dataObj.optJSONObject(0) ?: return emptyList()
            else -> return emptyList()
        }
        val albumArr = obj.optJSONArray("album") ?: return emptyList()
        val list = mutableListOf<AlbumItem>()
        for (i in 0 until albumArr.length()) {
            val a = albumArr.optJSONObject(i) ?: continue
            val imgUrl = a.optString("url", "").ifEmpty { a.optString("image", "") }
            if (imgUrl.isNotEmpty()) {
                list.add(AlbumItem(imgUrl, a.optString("pid", "")))
            }
        }
        return list
    } finally {
        conn.disconnect()
    }
}

/** 下载远程图片为 Bitmap (带缩放防爆 OOM)。 */
private fun loadBitmap(urlStr: String): Bitmap? {
    return try {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        val input = conn.inputStream
        // 先解码尺寸
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, opts)
        input.close(); conn.disconnect()
        // 计算采样 (缩略图 ~300px)
        val sample = calcSample(opts.outWidth, opts.outHeight, 300)
        val conn2 = URL(urlStr).openConnection() as HttpURLConnection
        conn2.connectTimeout = 8000; conn2.readTimeout = 8000
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeStream(conn2.inputStream, null, opts2)
        conn2.disconnect()
        bmp
    } catch (_: Throwable) { null }
}

private fun calcSample(w: Int, h: Int, target: Int): Int {
    var s = 1
    while (w / s > target * 2 && h / s > target * 2) s *= 2
    return s
}

/** 下载原图存入秘密相册目录 (复用 ChatSpyHook 存储)。 */
private suspend fun saveImage(ctx: Context, urlStr: String) {
    val ok = withContext(Dispatchers.IO) {
        try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            // 存到 llhook 私有目录下的 saved 子目录
            val dir = File(ctx.filesDir, "llhook_saved_album").apply { mkdirs() }
            val name = "album_${System.currentTimeMillis()}.jpg"
            FileOutputStream(File(dir, name)).use { it.write(bytes) }
            true
        } catch (_: Throwable) { false }
    }
    Toast.makeText(ctx, if (ok) "已保存到秘密相册" else "保存失败", Toast.LENGTH_SHORT).show()
}
