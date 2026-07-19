package com.example.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.util.Locale
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.BluedDecryptHook
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

// ============================================================================
//  网络抓包查看器 (Compose 玻璃拟态全屏页)
//
//  架构 (b): 宿主内就地弹出 (showHostComposeScreen)。
//  数据源: bxxd.hook.BluedDecryptHook 内存环形缓冲 (最多 300 条)
//    - 监控点1 解密明文 (AES-GCM 解密后)
//    - 监控点2 原始响应 (未解密的原始体)
//
//  功能: 捕获开关 / 实时列表 / URL 搜索过滤 / 点击查看明文详情
//       JSON 一键美化 / 复制 URL / 复制 body / 清空 / 来源徽章 / 大小统计
// ============================================================================

@Composable
fun NetworkCaptureScreen(activity: Activity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()

    var captureOn by remember { mutableStateOf(BluedDecryptHook.isCaptureEnabled()) }
    var packets by remember { mutableStateOf(BluedDecryptHook.getCapturedPackets()) }
    var searchQuery by remember { mutableStateOf("") }
    var totalCount by remember { mutableStateOf(BluedDecryptHook.getCaptureCount()) }
    var totalBytes by remember { mutableStateOf(0L) }
    var selectedPacket by remember { mutableStateOf<BluedDecryptHook.Packet?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    // 实时轮询缓冲 (捕获开启时每 800ms 刷新一次)
    LaunchedEffect(captureOn, refreshTick) {
        if (captureOn) {
            delay(800)
            val list = BluedDecryptHook.getCapturedPackets()
            packets = list
            totalCount = list.size
            totalBytes = list.sumOf { it.body.length.toLong() }
            refreshTick++
        }
    }

    fun toggleCapture(on: Boolean) {
        BluedDecryptHook.setCaptureEnabled(on, ctx)
        captureOn = on
        if (on) {
            // 立即拉一次
            scope.launch {
                val list = withContext(Dispatchers.IO) { BluedDecryptHook.getCapturedPackets() }
                packets = list; totalCount = list.size; totalBytes = list.sumOf { it.body.length.toLong() }
            }
            Toast.makeText(ctx, "🔴 抓包已开启, 实时记录中", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "⏸ 抓包已暂停 (已记录的仍保留)", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearAll() {
        BluedDecryptHook.clearCaptured()
        packets = emptyList(); totalCount = 0; totalBytes = 0
        Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show()
    }

    // 搜索过滤
    val filtered = remember(packets, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) packets
        else packets.filter { it.url.contains(q, true) || it.body.contains(q, true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                llhookBackgroundBrush(isDark, listOf(Color(0xFF0F172A), Color(0xFF1E1B2E)))
            )
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // ============ 顶栏 ============
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(onClick = onClose, glass = colors.glass, stroke = colors.glassStroke, size = 42,
                    contentDescription = "关闭页面") {
                    Icon(Icons.Filled.ArrowBack, null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 实时指示灯
                        Box(
                            modifier = Modifier
                                .size(8.dp).clip(CircleShape)
                                .background(if (captureOn) Color(0xFFEF4444) else Color.Gray)
                                .semantics { contentDescription = if (captureOn) "抓包中" else "已暂停" }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("网络抓包", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("$totalCount 条 · ${formatBytes(totalBytes)}" +
                        (if (captureOn) " · 实时刷新" else " · 已暂停"),
                        color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                }
                // 捕获开关
                Switch(
                    checked = captureOn, onCheckedChange = { toggleCapture(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFEF4444),
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF475569)
                    )
                )
            }

            // ============ 搜索 + 清空 ============
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索 URL 或 body", color = colors.subText, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = colors.subText) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            Icon(Icons.Filled.Close, "清除搜索",
                                modifier = Modifier.clickable { searchQuery = "" }, tint = colors.subText)
                        }
                    },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = colors.accent, focusedBorderColor = colors.accent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f)
                    ),
                    modifier = Modifier.weight(1f)
                )
                GlassIconButton(onClick = { clearAll() }, glass = colors.glass, stroke = colors.glassStroke, size = 42,
                    enabled = totalCount > 0, contentDescription = "清空所有抓包") {
                    Icon(Icons.Filled.DeleteSweep, null, tint = if (totalCount > 0) colors.danger else Color.Gray)
                }
            }

            // ============ 提示条 (捕获关闭时) ============
            if (!captureOn && totalCount == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🌐", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("抓包未开启", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text("打开右上角开关, 即可实时记录 Blued 的解密明文 API 响应\n"
                            + "(hook 了 AES-GCM 解密函数 + 原始响应分发器)",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp)
                    }
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (searchQuery.isNotEmpty()) "无匹配结果" else "等待请求…",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                }
            } else {
                // ============ 抓包列表 ============
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { pkt ->
                        PacketCard(pkt) { selectedPacket = pkt }
                    }
                }
            }
        }
    }

    // ============ 详情对话框 ============
    selectedPacket?.let { pkt ->
        PacketDetailDialog(pkt, colors) { selectedPacket = null }
    }
}

// ---------------------------------------------------------------------------
//  单条抓包卡片
// ---------------------------------------------------------------------------
@Composable
private fun PacketCard(pkt: BluedDecryptHook.Packet, onClick: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()
    val isDecrypted = pkt.source == "解密明文"
    val badgeColor = if (isDecrypted) Color(0xFF22C55E) else Color(0xFF3B82F6)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = if (isDark) 0.06f else 0.75f),
        border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 来源徽章
                Surface(shape = RoundedCornerShape(4.dp), color = badgeColor.copy(alpha = 0.2f)) {
                    Text(if (isDecrypted) "解密" else "原始", color = badgeColor, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(pkt.wallTime, color = colors.subText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text(formatBytes(pkt.body.length.toLong()), color = colors.subText, fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            // URL: host + path (去掉 query 更易读)
            val shortUrl = pkt.url.substringBefore("?").let {
                it.removePrefix("https://").removePrefix("http://")
            }
            Text(shortUrl, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            if (pkt.body.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(pkt.body.take(120).replace("\n", " "),
                    color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  抓包详情对话框 (完整 URL + 完整 body + 美化 / 复制)
// ---------------------------------------------------------------------------
@Composable
private fun PacketDetailDialog(
    pkt: BluedDecryptHook.Packet, colors: LlhookColors, onClose: () -> Unit
) {
    val ctx = LocalContext.current
    var beautified by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }

    // 尝试美化 JSON
    LaunchedEffect(pkt.id) {
        beautified = tryBeautify(pkt.body)
    }

    val displayBody = if (showRaw) pkt.body else (beautified ?: pkt.body)

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (beautified != null) {
                    TextButton(onClick = { showRaw = !showRaw }) {
                        Text(if (showRaw) "美化" else "原始", color = colors.accent, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = {
                    copyToClipboard(ctx, "URL", pkt.url)
                    Toast.makeText(ctx, "URL 已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制URL", color = colors.accent, fontSize = 12.sp) }
                TextButton(onClick = {
                    copyToClipboard(ctx, "Body", pkt.body)
                    Toast.makeText(ctx, "Body 已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制Body", color = colors.accent, fontSize = 12.sp) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭", color = colors.subText) } },
        containerColor = colors.glass, titleContentColor = colors.text,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val badgeColor = if (pkt.source == "解密明文") Color(0xFF22C55E) else Color(0xFF3B82F6)
                Surface(shape = RoundedCornerShape(4.dp), color = badgeColor.copy(alpha = 0.2f)) {
                    Text(pkt.source, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("请求详情", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                // 时间 + 大小
                Text("${pkt.wallTime}  ·  ${formatBytes(pkt.body.length.toLong())}  ·  ${pkt.body.length} 字符",
                    color = colors.subText, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
                // URL
                Text("URL", color = colors.subText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(pkt.url, color = Color(0xFF60A5FA), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp))
                }
                // Body
                Text("Body ${if (beautified != null && !showRaw) "(已美化)" else "(原始)"}",
                    color = colors.subText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                ) {
                    Box {
                        Text(
                            displayBody,
                            color = Color(0xFFE2E8F0), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp,
                            modifier = Modifier
                                .padding(8.dp)
                                .horizontalScroll(rememberScrollState())
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    )
}

// ---------------------------------------------------------------------------
//  工具函数
// ---------------------------------------------------------------------------

/** 尝试美化 JSON; 非 JSON 返回 null。 */
private fun tryBeautify(raw: String): String? = try {
    val trimmed = raw.trim()
    when {
        trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
        trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
        else -> null
    }
} catch (e: Throwable) { null }

/** 复制到剪贴板。 */
private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** 字节数人类可读格式。 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1fKB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.2fMB", bytes / (1024.0 * 1024.0))
}
