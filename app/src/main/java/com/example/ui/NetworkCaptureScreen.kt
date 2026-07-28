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
    // 本页背景画刷硬编码为深色, 故颜色方案也强制深色, 避免“浅色卡片落在深色背景上”的对比度 bug
    val isDark = true
    val colors = llhookDarkColors()

    var captureOn by remember { mutableStateOf(BluedDecryptHook.isCaptureEnabled()) }
    var packets by remember { mutableStateOf(BluedDecryptHook.getCapturedPackets()) }
    var searchQuery by remember { mutableStateOf("") }
    var totalCount by remember { mutableStateOf(BluedDecryptHook.getCaptureCount()) }
    var totalBytes by remember { mutableStateOf(0L) }
    var selectedPacket by remember { mutableStateOf<BluedDecryptHook.Packet?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    // 实时轮询缓冲 (捕获开启 + 未在搜索时, 每 800ms 刷新一次)
    // 搜索时暂停轮询: 避免新包不断涌入导致列表跳动/重组, 干扰用户检视过滤结果
    // (这是之前“搜索看似失效”的根因 —— 过滤本身是对的, 但列表每 800ms 被新快照覆盖)
    LaunchedEffect(captureOn, refreshTick, searchQuery) {
        if (captureOn && searchQuery.isBlank()) {
            delay(800)
            val list = BluedDecryptHook.getCapturedPackets()
            packets = list
            totalCount = list.size
            totalBytes = list.sumOf { it.body.length.toLong() }
            refreshTick++
        }
    }

    // 拉取最新缓冲快照 (开关切换 / 重发完成后立即刷新, 不必等轮询)
    fun refreshPackets() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { BluedDecryptHook.getCapturedPackets() }
            packets = list
            totalCount = list.size
            totalBytes = list.sumOf { it.body.length.toLong() }
        }
    }

    fun toggleCapture(on: Boolean) {
        BluedDecryptHook.setCaptureEnabled(on, ctx)
        captureOn = on
        if (on) {
            // 立即拉一次
            refreshPackets()
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
                            + "(hook AES-GCM 解密 + 原始响应 + 请求头关联)\n"
                            + "点任意包详情 → \"🔁 改UA重发\" 可换 UA 重放并自动解密 en_data",
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
        PacketDetailDialog(pkt, colors, onListChanged = { refreshPackets() }) { selectedPacket = null }
    }
}

// ---------------------------------------------------------------------------
//  单条抓包卡片
// ---------------------------------------------------------------------------
@Composable
private fun PacketCard(pkt: BluedDecryptHook.Packet, onClick: () -> Unit) {
    // 页面背景恒深色, 卡片也恒深色 (避免浅色模式下对比度问题)
    val colors = llhookDarkColors()
    val (badgeColor, badgeText) = sourceBadge(pkt.source)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 来源徽章
                Surface(shape = RoundedCornerShape(4.dp), color = badgeColor.copy(alpha = 0.2f)) {
                    Text(badgeText, color = badgeColor, fontSize = 9.sp,
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
    pkt: BluedDecryptHook.Packet, colors: LlhookColors,
    onListChanged: () -> Unit,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var beautified by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var decryptResult by remember { mutableStateOf<String?>(null) }
    var decryptError by remember { mutableStateOf<String?>(null) }
    var decrypting by remember { mutableStateOf(false) }
    var replayMode by remember { mutableStateOf(false) }   // 改UA重发面板开关

    val hasEnData = pkt.body.contains("en_data")

    // 尝试美化 JSON
    LaunchedEffect(pkt.id) {
        beautified = tryBeautify(pkt.body)
    }

    val displayBody = if (showRaw) pkt.body else (beautified ?: pkt.body)

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // 改UA重发切换 (所有包都可重发)
                TextButton(onClick = {
                    replayMode = !replayMode
                    if (!replayMode) { decryptResult = null; decryptError = null }
                }) {
                    Text(if (replayMode) "返回详情" else "🔁 改UA重发",
                        color = Color(0xFF60A5FA), fontSize = 12.sp)
                }
                if (hasEnData && !replayMode) {
                    TextButton(onClick = {
                        decrypting = true; decryptResult = null; decryptError = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                BluedDecryptHook.manualDecrypt(pkt.body, pkt.url)
                            }
                            if (r.success) decryptResult = r.plaintext
                            else decryptError = r.error
                            decrypting = false
                        }
                    }) {
                        Text(if (decrypting) "解密中…" else "🔓 解密en_data",
                            color = colors.warning, fontSize = 12.sp)
                    }
                }
                if (beautified != null && !replayMode) {
                    TextButton(onClick = { showRaw = !showRaw }) {
                        Text(if (showRaw) "美化" else "原始", color = colors.accent, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = {
                    copyToClipboard(ctx, "URL", pkt.url)
                    Toast.makeText(ctx, "URL 已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制URL", color = colors.accent, fontSize = 12.sp) }
                if (!replayMode) {
                    TextButton(onClick = {
                        copyToClipboard(ctx, "Body", pkt.body)
                        Toast.makeText(ctx, "Body 已复制", Toast.LENGTH_SHORT).show()
                    }) { Text("复制Body", color = colors.accent, fontSize = 12.sp) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭", color = colors.subText) } },
        containerColor = colors.glass, titleContentColor = colors.text,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (badgeColor, _) = sourceBadge(pkt.source)
                Surface(shape = RoundedCornerShape(4.dp), color = badgeColor.copy(alpha = 0.2f)) {
                    Text(pkt.source, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("请求详情", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            // 整列可滚动: 重发面板(UA输入+结果)很高时, 避免重发按钮被挤出可视区
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                if (replayMode) {
                    // ===== 改UA重发面板 =====
                    ReplayPanel(pkt, colors, onListChanged)
                } else {
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
                    // 强制解密结果 (点“解密en_data”后展示)
                    decryptResult?.let { plain ->
                        Spacer(Modifier.height(8.dp))
                        Text("🔓 强制解密明文", color = colors.warning, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF22C55E).copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                        ) {
                            Text(tryBeautify(plain) ?: plain,
                                color = Color(0xFFE2E8F0), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(8.dp)
                                    .horizontalScroll(rememberScrollState())
                                    .verticalScroll(rememberScrollState()))
                        }
                    }
                    decryptError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text("⚠️ $err", color = colors.danger, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
    )
}

// ---------------------------------------------------------------------------
//  改UA重发面板 (单条 Packet)
// ---------------------------------------------------------------------------

@Composable
private fun ReplayPanel(
    pkt: BluedDecryptHook.Packet, colors: LlhookColors,
    onListChanged: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 默认 UA / Auth: 优先原请求捕获值, 兼底用全局缓存
    var ua by remember {
        mutableStateOf(pkt.userAgent.ifBlank { BluedDecryptHook.getCachedUserAgent() })
    }
    var auth by remember {
        mutableStateOf(pkt.authToken.ifBlank { BluedDecryptHook.getCachedAuth() })
    }
    var showAuth by remember { mutableStateOf(false) }
    var showReqBody by remember { mutableStateOf(false) }
    var replaying by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BluedDecryptHook.ReplayResult?>(null) }
    var showRawResp by remember { mutableStateOf(false) }

    Column {
        // 原请求信息提示
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF60A5FA).copy(alpha = 0.18f)) {
                Text(pkt.method, color = Color(0xFF93C5FD), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
            }
            Spacer(Modifier.width(6.dp))
            if (pkt.contentType.isNotBlank()) {
                Text(pkt.contentType, color = colors.subText, fontSize = 9.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else {
                Text("点击重发 → 拿到原始响应并自动解密 en_data",
                    color = colors.subText, fontSize = 9.sp, modifier = Modifier.weight(1f))
            }
            if (pkt.requestBody.isNotEmpty()) {
                TextButton(onClick = { showReqBody = !showReqBody },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(if (showReqBody) "隐藏Body" else "请求Body(${pkt.requestBody.length})",
                        color = colors.subText, fontSize = 9.sp)
                }
            }
        }
        if (showReqBody && pkt.requestBody.isNotEmpty()) {
            MonoBox(pkt.requestBody, Color.Black.copy(alpha = 0.3f), maxHeight = 120)
            Spacer(Modifier.height(6.dp))
        }

        // UA 编辑框
        Text("User-Agent (可编辑后重发)", color = colors.subText, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp, bottom = 3.dp))
        OutlinedTextField(
            value = ua, onValueChange = { ua = it },
            minLines = 2, maxLines = 4,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFE2E8F0), unfocusedTextColor = Color(0xFFE2E8F0),
                cursorColor = colors.accent, focusedBorderColor = colors.accent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = Color.White.copy(alpha = 0.06f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.06f)
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { showAuth = !showAuth },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                Text(if (showAuth) "▼ 隐藏Authorization" else "▶ Authorization (${auth.length}字符)",
                    color = colors.subText, fontSize = 9.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                ua = pkt.userAgent.ifBlank { BluedDecryptHook.getCachedUserAgent() }
                auth = pkt.authToken.ifBlank { BluedDecryptHook.getCachedAuth() }
                Toast.makeText(ctx, "已重置为原值", Toast.LENGTH_SHORT).show()
            }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                Text("↺ 重置", color = colors.subText, fontSize = 9.sp)
            }
        }
        if (showAuth) {
            OutlinedTextField(
                value = auth, onValueChange = { auth = it },
                minLines = 2, maxLines = 4,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFE2E8F0), unfocusedTextColor = Color(0xFFE2E8F0),
                    cursorColor = colors.accent, focusedBorderColor = colors.accent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.06f)
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 12.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 重发按钮
        Spacer(Modifier.height(8.dp))
        GlassButton(
            onClick = {
                replaying = true; result = null; showRawResp = false
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        BluedDecryptHook.replayRequest(pkt, ua, auth)
                    }
                    result = r
                    replaying = false
                    // ★ 把重发结果存入抓包缓冲: 用户可在列表中点击查看
                    //   (重发原始响应 + 若含 en_data 则额外存一条重发明文)
                    BluedDecryptHook.appendReplayResult(pkt, r, ua)
                    onListChanged()
                    Toast.makeText(ctx,
                        if (r.success) "重发完成 HTTP ${r.httpCode} (${r.latencyMs}ms) · 已存入列表" else "重发失败: ${r.error}",
                        Toast.LENGTH_SHORT).show()
                }
            },
            glass = Color(0xFF60A5FA).copy(alpha = 0.2f),
            stroke = Color(0xFF60A5FA).copy(alpha = 0.55f),
            shape = RoundedCornerShape(10.dp),
            enabled = !replaying,
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) {
            // GlassButton 默认内容不居中, 需用 Row(居中) 包裹, 否则图标+文字会错位
            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (replaying) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = Color.White)
                } else {
                    Icon(Icons.Filled.Replay, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("🔁 用此 UA 重发", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 重发结果
        result?.let { r ->
            Spacer(Modifier.height(8.dp))
            // 状态行
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ok = r.success && r.httpCode == 200
                Surface(shape = RoundedCornerShape(4.dp),
                    color = (if (ok) Color(0xFF22C55E) else Color(0xFFEF4444)).copy(alpha = 0.18f)) {
                    Text("HTTP ${r.httpCode}", color = if (ok) Color(0xFF4ADE80) else Color(0xFFFCA5A5),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("${r.latencyMs}ms", color = colors.subText, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                r.rawBody?.let { Text("${it.length} 字符", color = colors.subText, fontSize = 10.sp) }
            }
            r.error?.let {
                Text("⚠️ $it", color = colors.danger, fontSize = 10.sp, lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            // 解密结果
            r.decrypted?.let { dec ->
                Spacer(Modifier.height(6.dp))
                if (dec.success) {
                    Text("🔓 重发响应解密成功", color = colors.warning, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    MonoBox(tryBeautify(dec.plaintext!!) ?: dec.plaintext!!,
                        Color(0xFF22C55E).copy(alpha = 0.1f), maxHeight = 280)
                    Row {
                        TextButton(onClick = {
                            copyToClipboard(ctx, "重发明文", dec.plaintext!!)
                            Toast.makeText(ctx, "明文已复制", Toast.LENGTH_SHORT).show()
                        }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                            Text("复制明文", color = colors.accent, fontSize = 9.sp)
                        }
                    }
                } else {
                    Text("⚠️ 解密: ${dec.error}", color = colors.danger, fontSize = 9.sp, lineHeight = 13.sp)
                }
            }
            // 原始响应体 (可折叠)
            r.rawBody?.let { raw ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showRawResp = !showRawResp },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                        Text(if (showRawResp) "▼ 隐藏原始响应" else "▶ 原始响应 (服务器返回)",
                            color = colors.subText, fontSize = 9.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        copyToClipboard(ctx, "重发原始响应", raw)
                        Toast.makeText(ctx, "原始响应已复制", Toast.LENGTH_SHORT).show()
                    }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                        Text("复制", color = colors.accent, fontSize = 9.sp)
                    }
                }
                if (showRawResp) {
                    MonoBox(raw, Color.Black.copy(alpha = 0.3f), maxHeight = 220)
                }
            }
        }
    }
}

/** 等宽字体内容框 (横向+纵向滚动)。 */
@Composable
private fun MonoBox(content: String, bg: Color, maxHeight: Int) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight.dp)
    ) {
        Text(content, color = Color(0xFFE2E8F0), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp,
            modifier = Modifier.padding(8.dp)
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState()))
    }
}

// ---------------------------------------------------------------------------
//  工具函数
// ---------------------------------------------------------------------------

/** 抓包来源 → (徽章颜色, 短标签)。 */
private fun sourceBadge(source: String): Pair<Color, String> = when (source) {
    "解密明文" -> Color(0xFF22C55E) to "解密"    // 绿: AES-GCM 解密后明文
    "强制解密" -> Color(0xFFF59E0B) to "强解"    // 琥珀: 主动强制解密 en_data
    "重发明文" -> Color(0xFFA855F7) to "重发明"  // 紫: 改UA重发后解密的明文
    "重发原始" -> Color(0xFF06B6D4) to "重发原"  // 青: 改UA重发的原始响应
    else       -> Color(0xFF3B82F6) to "原始"    // 蓝: 原始响应 (未解密)
}

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
