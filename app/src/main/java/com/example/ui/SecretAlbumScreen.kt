package com.example.ui

import android.app.Activity
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
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
import bxxd.hook.ChatSpyHook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
//  秘密相册 (Compose 玻璃拟态全屏页)
//
//  架构 (b): 宿主内就地弹出 (showHostComposeScreen)。
//  逻辑全用 bxxd.hook.ChatSpyHook:
//    - 数据: listAlbumUsers / listUserPhotos / deletePhoto / destroyAlbum / setAutoSaveEnabled
//    - 缩略图: getThumbnail (Bitmap), Compose 用 asImageBitmap 显示
//  存储: Pictures/.llhook_blued/<uid>_<name>/*.jpg (.nomedia 物理隐身, 系统图库不可见)
//
//  两种入口模式:
//    - 总览 (uid 为空): 列出所有用户相册 → 点进某用户网格
//    - 单用户 (聊天页 openSecretAlbum 调入): 直接某用户网格
// ============================================================================

@Composable
fun SecretAlbumScreen(
    activity: Activity,
    initialUid: String? = null,
    initialName: String? = null,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()

    // 当前查看的用户 (null = 总览列表)
    var currentUser by remember { mutableStateOf<Pair<String, String>?>(initialUid?.let { it to (initialName ?: "") }) }
    var users by remember { mutableStateOf<List<ChatSpyHook.AlbumUser>>(emptyList()) }
    var photos by remember { mutableStateOf<List<File>>(emptyList()) }
    var thumbnails by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    var confirmDestroy by remember { mutableStateOf<ChatSpyHook.AlbumUser?>(null) }

    fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    // 加载数据
    LaunchedEffect(currentUser, refreshKey) {
        loading = true
        if (currentUser == null) {
            users = withContext(Dispatchers.IO) { ChatSpyHook.listAlbumUsers() }
            photos = emptyList(); thumbnails = emptyMap()
        } else {
            val (uid, name) = currentUser!!
            photos = withContext(Dispatchers.IO) { ChatSpyHook.listUserPhotos(uid, safeName(name)) }
            // 预生成缩略图
            val tm = mutableMapOf<String, Bitmap>()
            photos.take(60).forEach { f ->
                ChatSpyHook.getThumbnail(f, 256)?.let { tm[f.absolutePath] = it }
            }
            thumbnails = tm
        }
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                llhookBackgroundBrush(isDark, listOf(Color(0xFFFFF7ED), Color(0xFFFFEDD5)))
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ============ 顶栏 (统一组件, 自带状态栏内边距) ============
            LlhookTopBar(
                title = if (currentUser == null) "秘密相册" else "「${currentUser!!.second.ifBlank { "UID " + currentUser!!.first }}」",
                subtitle = when {
                    currentUser == null -> "${users.size} 位用户 · 共 ${users.sumOf { it.photoCount }} 张"
                    else -> "${photos.size} 张 · ${if (users.find { it.uid == currentUser!!.first }?.autoSaveEnabled == true) "自动入库中" else "⚠️ 已停止入库"}"
                },
                onBack = { if (currentUser != null) { currentUser = null } else onClose() },
                glass = colors.glass,
                stroke = colors.glassStroke,
                textColor = colors.text,
                subTextColor = colors.subText
            ) {
                if (currentUser != null) {
                    val u = users.find { it.uid == currentUser!!.first }
                    if (u != null) {
                        GlassIconButton(onClick = {
                            val newEn = !u.autoSaveEnabled
                            ChatSpyHook.setAutoSaveEnabled(u.uid, safeName(u.displayName), newEn)
                            refreshKey++; Toast.makeText(ctx,
                                if (newEn) "已恢复自动入库" else "已停止自动入库", Toast.LENGTH_SHORT).show()
                        }, glass = colors.glass, stroke = colors.glassStroke, size = 42,
                            contentDescription = "自动入库开关") {
                            Icon(if (u.autoSaveEnabled) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
                                null, tint = if (u.autoSaveEnabled) Color(0xFF22C55E) else colors.warning)
                        }
                        Spacer(Modifier.width(8.dp))
                        GlassIconButton(onClick = { confirmDestroy = u }, glass = colors.glass, stroke = colors.glassStroke, size = 42,
                            contentDescription = "销毁此用户相册") {
                            Icon(Icons.Filled.DeleteForever, null, tint = colors.danger)
                        }
                    }
                }
            }

            // ============ 提示条 ============
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0x33FFC107),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    if (currentUser == null) "🔒 照片物理隐身，系统图库不可见。点击用户查看"
                    else "点击大图预览 · 长按删除 · 销毁将清空并停止入库",
                    color = colors.subText, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // ============ 内容 ============
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            } else if (currentUser == null) {
                // —— 用户列表 ——
                if (users.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🔒", fontSize = 48.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("暂无秘密相册", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            Text("聊天时收到的照片/闪照会自动入库到这里\n（需要打开「闪照自动保存」开关）",
                                color = colors.subText, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 16.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        lazyItems(users) { u ->
                            AlbumUserCard(u, colors) { currentUser = u.uid to u.displayName }
                        }
                    }
                }
            } else {
                // —— 照片网格 ——
                if (photos.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📭", fontSize = 48.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("暂无照片", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            Text("聊天时的照片和闪照会自动出现在这里", color = colors.subText, fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(photos) { f ->
                            val bmp = thumbnails[f.absolutePath]
                            PhotoCell(bmp, f, colors,
                                onClick = { previewFile = f },
                                onLong = { confirmDelete = f })
                        }
                    }
                }
            }
        }
    }

    // ============ 大图预览 ============
    previewFile?.let { f ->
        PreviewDialog(f, colors) { previewFile = null }
    }

    // ============ 删除确认 ============
    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = { TextButton(onClick = {
                ChatSpyHook.deletePhoto(f); confirmDelete = null; refreshKey++
                Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
            }) { Text("删除", color = colors.danger, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消", color = colors.subText) } },
            containerColor = colors.glass, titleContentColor = colors.text,
            title = { Text("删除照片") }, text = { Text("确定要删除这张照片吗？此操作不可恢复。", color = colors.subText) }
        )
    }

    // ============ 销毁确认 ============
    confirmDestroy?.let { u ->
        AlertDialog(
            onDismissRequest = { confirmDestroy = null },
            confirmButton = { TextButton(onClick = {
                val n = ChatSpyHook.destroyAlbum(u.uid, safeName(u.displayName))
                confirmDestroy = null; currentUser = null; refreshKey++
                Toast.makeText(ctx, "已销毁 $n 张照片并停止入库", Toast.LENGTH_LONG).show()
            }) { Text("销毁", color = colors.danger, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmDestroy = null }) { Text("取消", color = colors.subText) } },
            containerColor = colors.glass, titleContentColor = colors.text,
            title = { Text("⚠️ 销毁相册") },
            text = { Text("将清空「${u.displayName}」的全部 ${u.photoCount} 张照片，并停止后续自动入库。此操作不可恢复！",
                color = colors.subText) }
        )
    }
}

// ---------------------------------------------------------------------------
//  用户卡片
// ---------------------------------------------------------------------------
@Composable
private fun AlbumUserCard(u: ChatSpyHook.AlbumUser, colors: LlhookColors, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.glass,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassStroke.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF8B5CF6).copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(u.displayName.take(1).ifEmpty { "?" }, color = Color(0xFF8B5CF6), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.displayName.ifBlank { "UID ${u.uid}" }, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!u.autoSaveEnabled) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = colors.warning.copy(alpha = 0.15f)) {
                            Text("已停", color = colors.warning, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text("${u.photoCount} 张照片 · UID ${u.uid}", color = colors.subText, fontSize = 11.sp)
            }
            Text("${u.photoCount}", color = Color(0xFF8B5CF6).copy(alpha = 0.6f), fontSize = 22.sp, fontWeight = FontWeight.Black)
            Icon(Icons.Filled.ChevronRight, null, tint = colors.subText, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------------------
//  照片单元格
// ---------------------------------------------------------------------------
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    bmp: Bitmap?, file: File, colors: LlhookColors,
    onClick: () -> Unit, onLong: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E293B))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    ) {
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = file.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.BrokenImage, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  大图预览
// ---------------------------------------------------------------------------
@Composable
private fun PreviewDialog(file: File, colors: LlhookColors, onDismiss: () -> Unit) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file) {
        bmp = withContext(Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bmp != null) {
                    Image(bitmap = bmp!!.asImageBitmap(), contentDescription = "预览",
                        modifier = Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp)))
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
                Spacer(Modifier.height(12.dp))
                Text(file.name, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                val size = file.length() / 1024
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
                Text("${size}KB · $date", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                Text("点击任意处关闭", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
