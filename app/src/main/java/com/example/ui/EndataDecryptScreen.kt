package com.example.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.BluedDecryptHook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
//  endata 解密器 (Compose 玻璃拟态全屏页)
//
//  用途: 用户手动粘贴一段 en_data (或含 en_data 的完整响应体) + 对应 URL,
//        就地调用宿主内存里的 AES 密钥 + c.java 解密函数还原明文。
//
//  数据源: bxxd.hook.BluedDecryptHook
//    - bClassRef / cClassRef  : Init 时缓存的宿主类引用
//    - l1l1l1l1 (ByteArray)   : b.java 内存协商密钥
//    - c.I111I1lI1I1(enData, key, url) → 明文
//
//  注意: URL 是 AES-GCM 的 AAD, 必须与抓包时该接口的完整 URL 一致, 否则解密失败。
//        密钥随会话握手生成, 冷启动后需重新打开蓝蓝让它完成握手。
// ============================================================================

@Composable
fun EndataDecryptScreen(activity: Activity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 本页背景画刷硬编码为深色, 颜色方案也强制深色 (避免浅色模式下对比度 bug)
    val colors = llhookDarkColors()

    var enDataInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<BluedDecryptHook.DecryptResult?>(null) }
    var decrypting by remember { mutableStateOf(false) }
    var beautified by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var keyReadyTick by remember { mutableStateOf(0) }
    val keyReady by remember(keyReadyTick) { mutableStateOf(BluedDecryptHook.isKeyReady()) }

    // 进入页面 + 解密后刷新一次密钥就绪状态
    LaunchedEffect(Unit) { keyReadyTick++ }

    fun doDecrypt() {
        if (enDataInput.isBlank()) {
            Toast.makeText(ctx, "请输入 en_data", Toast.LENGTH_SHORT).show()
            return
        }
        decrypting = true
        result = null
        beautified = null
        showRaw = false
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                BluedDecryptHook.manualDecrypt(enDataInput, urlInput.trim())
            }
            result = r
            keyReadyTick++   // 解密后同步密钥状态
            beautified = r.plaintext?.let { tryBeautifyPub(it) }
            decrypting = false
            if (r.success) {
                Toast.makeText(ctx, "🔓 解密成功", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun pasteFromClipboard(target: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isBlank()) {
            Toast.makeText(ctx, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (target == "endata") enDataInput = text else urlInput = text
        Toast.makeText(ctx, "已粘贴 ${text.length} 字符", Toast.LENGTH_SHORT).show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                // 背景恒深色 (lightColors 传入深色渐变, 与强制深色配色一致)
                llhookBackgroundBrush(
                    isDark = true,
                    listOf(Color(0xFF1E1B2E), Color(0xFF0F172A))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
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
                    Text("🔓 endata 解密器", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("手动解密 Blued en_data 加密响应",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                }
                // 密钥就绪指示灯
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = (if (keyReady) Color(0xFF22C55E) else Color(0xFFEF4444)).copy(alpha = 0.18f),
                    border = androidx.compose.foundation.BorderStroke(1.dp,
                        (if (keyReady) Color(0xFF22C55E) else Color(0xFFEF4444)).copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp).clip(CircleShape)
                                .background(if (keyReady) Color(0xFF22C55E) else Color(0xFFEF4444))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (keyReady) "密钥就绪" else "密钥未就绪",
                            color = if (keyReady) Color(0xFF22C55E) else Color(0xFFEF4444),
                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ============ 密钥未就绪提示 ============
            if (!keyReady) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFF59E0B).copy(alpha = 0.14f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("内存中没有 AES 密钥", color = Color(0xFFFBD38D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("请到手机设置 → 应用管理 → 强行停止蓝蓝, 重新打开让它完成握手后再来。",
                                color = Color(0xFFFBD38D).copy(alpha = 0.85f), fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }

            // ============ URL 输入 ============
            SectionLabel("① 请求 URL (AES-GCM AAD, 强烈建议填写)")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = urlInput, onValueChange = { urlInput = it },
                    placeholder = { Text("https://app.blued.cn/users/xxxx?...", color = colors.subText, fontSize = 12.sp) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = colors.accent, focusedBorderColor = colors.accent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f)
                    ),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ContentPaste, "粘贴",
                                tint = colors.subText,
                                modifier = Modifier
                                    .clickable { pasteFromClipboard("url") }
                                    .padding(6.dp).size(18.dp)
                                    .semantics { contentDescription = "粘贴URL" })
                            if (urlInput.isNotEmpty()) {
                                Icon(Icons.Filled.Close, "清空",
                                    tint = colors.subText,
                                    modifier = Modifier
                                        .clickable { urlInput = "" }
                                        .padding(6.dp).size(18.dp))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            HelperText("URL 是解密的关联数据 (AAD), 必须与抓包时该接口的完整 URL 完全一致, 否则 GCM 校验失败。")

            // ============ en_data 输入 ============
            SectionLabel("② en_data 加密内容 (值 或 含 en_data 的完整响应体)")
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = enDataInput, onValueChange = { enDataInput = it },
                    placeholder = {
                        Text("粘贴 en_data 的值, 或整段 {\"en_data\":\"...\"} 响应体",
                            color = colors.subText, fontSize = 12.sp)
                    },
                    minLines = 4, maxLines = 8,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE2E8F0), unfocusedTextColor = Color(0xFFE2E8F0),
                        cursorColor = colors.accent, focusedBorderColor = colors.accent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp
                    ),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.ContentPaste, "粘贴",
                                modifier = Modifier.clickable { pasteFromClipboard("endata") }.padding(4.dp),
                                tint = colors.subText)
                            if (enDataInput.isNotEmpty()) {
                                Icon(Icons.Filled.Close, "清空",
                                    modifier = Modifier.clickable { enDataInput = "" }.padding(4.dp), tint = colors.subText)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            HelperText("支持两种输入: ① 仅 en_data 的值 (base64 串);  ② 含 \"en_data\" 字段的完整 JSON 响应体。")

            // ============ 解密按钮 ============
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassButton(
                    onClick = { doDecrypt() },
                    glass = Color(0xFF4CAF50).copy(alpha = 0.22f),
                    stroke = Color(0xFF4CAF50).copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !decrypting && enDataInput.isNotBlank(),
                    contentDescription = "解密",
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    // GlassButton 默认内容不居中, 需用 Row(居中) 包裹
                    Row(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (decrypting) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp, modifier = Modifier.size(18.dp),
                                color = Color.White
                            )
                        } else {
                            Icon(Icons.Filled.LockOpen, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("🔓 解密", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ============ 结果区 ============
            result?.let { r ->
                if (r.success) {
                    val display = if (showRaw) r.plaintext!! else (beautified ?: r.plaintext!!)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF22C55E).copy(alpha = 0.1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF22C55E), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("解密成功", color = Color(0xFF4ADE80), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text("${r.enDataLength} → ${r.plaintext?.length ?: 0} 字符",
                                    color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.height(8.dp))
                            // 结果工具条
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (beautified != null) {
                                    MiniChip(if (showRaw) "原始" else "美化") { showRaw = !showRaw }
                                }
                                MiniChip("复制") {
                                    copyClip(ctx, "endata-明文", r.plaintext!!)
                                    Toast.makeText(ctx, "明文已复制", Toast.LENGTH_SHORT).show()
                                }
                                MiniChip("复制美化") {
                                    copyClip(ctx, "endata-明文(美化)", beautified ?: r.plaintext!!)
                                    Toast.makeText(ctx, "美化明文已复制", Toast.LENGTH_SHORT).show()
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Black.copy(alpha = 0.32f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 380.dp)
                            ) {
                                Text(
                                    display,
                                    color = Color(0xFFE2E8F0), fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .horizontalScroll(rememberScrollState())
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Error, null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("解密失败", color = Color(0xFFFCA5A5), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text("en_data=${r.enDataLength}字符 · 密钥${if (r.keyReady) "就绪" else "缺失"}",
                                    color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(r.error ?: "未知错误",
                                color = Color(0xFFFCA5A5).copy(alpha = 0.92f), fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }

            // ============ 说明卡片 ============
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.05f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("工作原理", color = Color(0xFFBFDBFE), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    InfoLine("1. 蓝蓝对部分接口返回 {\"en_data\":\"...\"} 加密响应 (AES-GCM)。")
                    InfoLine("2. 密钥由客户端与服务端握手协商, 存在 b.java 静态字段 l1l1l1l1。")
                    InfoLine("3. 解密 = c.java#I111I1lI1I1(en_data, key, url) → 明文 JSON。")
                    InfoLine("4. URL 作为 GCM 关联数据 (AAD), 必须与抓包时完全一致。")
                    InfoLine("5. 抓包页已对所有含 en_data 的响应自动强制解密 (琥珀色“强解”徽章)。")
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ---------------------------------------------------------------------------
//  小组件
// ---------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp))
}

@Composable
private fun HelperText(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, lineHeight = 14.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

@Composable
private fun MiniChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
    ) {
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun InfoLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text("• ", color = Color(0xFF60A5FA), fontSize = 11.sp)
        Text(text, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, lineHeight = 15.sp)
    }
}

// ---------------------------------------------------------------------------
//  工具函数
// ---------------------------------------------------------------------------

private fun tryBeautifyPub(raw: String): String? = try {
    val t = raw.trim()
    when {
        t.startsWith("{") -> JSONObject(t).toString(2)
        t.startsWith("[") -> JSONArray(t).toString(2)
        else -> null
    }
} catch (e: Throwable) { null }

private fun copyClip(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
