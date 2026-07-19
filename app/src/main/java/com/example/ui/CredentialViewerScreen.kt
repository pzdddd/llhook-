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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.AutoVisitHook
import bxxd.hook.Config
import kotlinx.coroutines.delay

// ============================================================================
//  凭证与状态查看器 (Compose 玻璃拟态全屏页)
//
//  架构 (b): 宿主内就地弹出 (showHostComposeScreen)。
//  数据源:
//    - Config: auth token / 当前虚拟坐标 / API key / 备份目录 / 当前宿主包名
//    - AutoVisitHook: cachedUserAgent (实时拦截到的真实 UA) / cachedUsers.size / cachedToken
//
//  功能: 展示所有关键运行时凭证 + 一键复制 + 可编辑项 (token/UA/坐标)
//       + 实时状态 (站街缓存用户数)
// ============================================================================

@Composable
fun CredentialViewerScreen(activity: Activity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()

    // 凭证数据
    var authToken by remember { mutableStateOf(Config.getAuthToken(ctx)) }
    var cachedUA by remember { mutableStateOf(AutoVisitHook.cachedUserAgent) }
    var cachedToken by remember { mutableStateOf(AutoVisitHook.cachedToken) }
    var customLat by remember { mutableStateOf(Config.getCustomLat(ctx)) }
    var customLng by remember { mutableStateOf(Config.getCustomLng(ctx)) }
    var apiKey by remember { mutableStateOf(Config.getApiKey(ctx)) }
    var backupDir by remember { mutableStateOf(Config.getBackupDir(ctx)) }
    var cachedUserCount by remember { mutableStateOf(AutoVisitHook.cachedUsers.size) }
    var isVisiting by remember { mutableStateOf(AutoVisitHook.isVisiting) }
    var editTarget by remember { mutableStateOf<EditTarget>(EditTarget.None) }

    // 实时刷新 (动态状态)
    LaunchedEffect(Unit) {
        while (true) {
            cachedUA = AutoVisitHook.cachedUserAgent
            cachedToken = AutoVisitHook.cachedToken
            cachedUserCount = AutoVisitHook.cachedUsers.size
            isVisiting = AutoVisitHook.isVisiting
            delay(1500)
        }
    }

    fun copy(label: String, value: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(ctx, "$label 已复制", Toast.LENGTH_SHORT).show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(llhookBackgroundBrush(isDark, listOf(Color(0xFF1E1B2E), Color(0xFF0F172A))))
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            // ============ 顶栏 ============
            LlhookTopBar(
                title = "凭证与运行状态",
                subtitle = "Authorization / UA / 坐标 / API Key 等关键运行时数据",
                onBack = onClose,
                glass = colors.glass, stroke = colors.glassStroke,
                textColor = Color.White, subTextColor = Color.White.copy(alpha = 0.7f)
            )

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ============ 实时状态卡 ============
                StatusCard(colors, isVisiting, cachedUserCount, cachedToken.isNotEmpty())

                // ============ Authorization Token ============
                CredentialCard(
                    title = "🔑 Authorization Token",
                    subtitle = "从 OkHttp 请求头实时拦截, 一键站街/抓包等网络功能依赖此凭证",
                    value = authToken,
                    colors = colors, monospace = true, mask = true,
                    onCopy = { copy("Token", authToken) },
                    onEdit = { editTarget = EditTarget.Token }
                )

                // ============ 缓存 Token (运行时) ============
                if (cachedToken.isNotEmpty()) {
                    CredentialCard(
                        title = "🔄 运行时缓存 Token",
                        subtitle = "当前会话内存中实际使用的 Token (优先于持久化值)",
                        value = cachedToken,
                        colors = colors, monospace = true, mask = true,
                        onCopy = { copy("缓存Token", cachedToken) }
                    )
                }

                // ============ User-Agent ============
                CredentialCard(
                    title = "🌐 User-Agent",
                    subtitle = "请求头 UA; 站街默认用拦截到的真实值, 未拦截时用硬编码默认",
                    value = cachedUA,
                    colors = colors, monospace = true,
                    onCopy = { copy("UA", cachedUA) },
                    onEdit = { editTarget = EditTarget.UA }
                )

                // ============ 当前虚拟坐标 ============
                CredentialCard(
                    title = "📍 当前虚拟定位",
                    subtitle = "虚拟定位/位置追踪当前使用的经纬度",
                    value = "$customLat, $customLng",
                    colors = colors, monospace = true,
                    onCopy = { copy("坐标", "$customLat,$customLng") },
                    onEdit = { editTarget = EditTarget.Coord }
                )

                // ============ 高德 API Key ============
                CredentialCard(
                    title = "🗺 高德地图 API Key",
                    subtitle = "地图选点页搜索/逆地理编码所用 (申请: lbs.amap.com)",
                    value = apiKey.ifBlank { "(未设置, 地图搜索不可用)" },
                    colors = colors, monospace = apiKey.isNotBlank(),
                    onCopy = if (apiKey.isNotBlank()) { { copy("API Key", apiKey) } } else null,
                    onEdit = { editTarget = EditTarget.ApiKey }
                )

                // ============ 备份目录 ============
                CredentialCard(
                    title = "💾 备份目录",
                    subtitle = "聊天备份的存储位置; 留空则用默认 Download/bluedbackups",
                    value = backupDir.ifBlank { "(默认: Download/bluedbackups)" },
                    colors = colors, monospace = backupDir.isNotBlank(),
                    onCopy = if (backupDir.isNotBlank()) { { copy("备份目录", backupDir) } } else null,
                    onEdit = { editTarget = EditTarget.BackupDir }
                )

                // ============ 宿主信息 ============
                CredentialCard(
                    title = "📦 当前宿主",
                    subtitle = "已激活的 Blued 包名 (决定功能兼容性判断)",
                    value = Config.currentBluedPackage,
                    colors = colors,
                    onCopy = { copy("宿主包名", Config.currentBluedPackage) }
                )

                Spacer(Modifier.height(20.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    // ============ 编辑对话框 ============
    when (val t = editTarget) {
        EditTarget.Token -> EditDialog("Authorization Token", authToken, true, colors) {
            if (it.isNotBlank()) { Config.setAuthToken(it.trim(), ctx); authToken = it.trim() }
            editTarget = EditTarget.None
        }
        EditTarget.UA -> EditDialog("User-Agent", cachedUA, false, colors) {
            if (it.isNotBlank()) { AutoVisitHook.cachedUserAgent = it.trim(); cachedUA = it.trim() }
            editTarget = EditTarget.None
        }
        EditTarget.Coord -> EditDialog("虚拟定位 (lat,lng)", "$customLat,$customLng", false, colors) {
            val parts = it.split(",", "，").map { p -> p.trim().toDoubleOrNull() }
            if (parts.size == 2 && parts[0] != null && parts[1] != null) {
                Config.setCustomLocation(parts[0]!!, parts[1]!!, ctx)
                customLat = parts[0]!!; customLng = parts[1]!!
                Toast.makeText(ctx, "坐标已更新", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "格式错误, 例: 39.9042,116.4074", Toast.LENGTH_SHORT).show()
            }
            editTarget = EditTarget.None
        }
        EditTarget.ApiKey -> EditDialog("高德 API Key", apiKey, true, colors) {
            Config.setApiKey(it.trim(), ctx); apiKey = it.trim()
            editTarget = EditTarget.None
        }
        EditTarget.BackupDir -> EditDialog("备份目录", backupDir, false, colors) {
            Config.setBackupDir(it.trim(), ctx); backupDir = it.trim()
            editTarget = EditTarget.None
        }
        EditTarget.None -> {}
    }
}

private enum class EditTarget { None, Token, UA, Coord, ApiKey, BackupDir }

// ---------------------------------------------------------------------------
//  实时状态卡
// ---------------------------------------------------------------------------
@Composable
private fun StatusCard(colors: LlhookColors, isVisiting: Boolean, userCount: Int, tokenReady: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = colors.glass,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassStroke.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("⚙️ 运行状态", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatusDot("站街", if (isVisiting) "运行中" else "停止", isVisiting, colors)
                StatusDot("附近缓存", "$userCount 人", userCount > 0, colors)
                StatusDot("凭证", if (tokenReady) "就绪" else "缺失", tokenReady, colors)
            }
        }
    }
}

@Composable
private fun StatusDot(label: String, value: String, active: Boolean, colors: LlhookColors) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = if (active) Color(0xFF22C55E) else Color(0xFF64748B),
                        shape = androidx.compose.foundation.shape.CircleShape
                    ).size(8.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(value, color = if (active) colors.accent else colors.subText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = colors.subText, fontSize = 10.sp)
    }
}

// ---------------------------------------------------------------------------
//  凭证卡片
// ---------------------------------------------------------------------------
@Composable
private fun CredentialCard(
    title: String, subtitle: String, value: String,
    colors: LlhookColors, monospace: Boolean = false, mask: Boolean = false,
    onCopy: (() -> Unit)? = null, onEdit: (() -> Unit)? = null
) {
    var revealed by remember { mutableStateOf(!mask) }

    Surface(
        shape = RoundedCornerShape(14.dp), color = colors.glass,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassStroke.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = colors.subText, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
                }
                // 显示/隐藏 (mask 模式)
                if (mask) {
                    GlassIconButton(onClick = { revealed = !revealed }, glass = Color.White.copy(alpha = 0.1f),
                        stroke = Color.White.copy(alpha = 0.2f), size = 32,
                        contentDescription = if (revealed) "隐藏" else "显示") {
                        Icon(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            null, tint = colors.text, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            // 值
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (mask && !revealed) "•".repeat(value.length.coerceAtMost(40)) else value,
                    color = Color(0xFFE2E8F0),
                    fontSize = 11.sp,
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                    maxLines = if (mask) 3 else 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp).fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onCopy != null) {
                    OutlinedButton(onClick = onCopy, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(14.dp), tint = colors.accent)
                        Spacer(Modifier.width(4.dp))
                        Text("复制", color = colors.text, fontSize = 12.sp)
                    }
                }
                if (onEdit != null) {
                    OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(14.dp), tint = colors.warning)
                        Spacer(Modifier.width(4.dp))
                        Text("编辑", color = colors.text, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  编辑对话框
// ---------------------------------------------------------------------------
@Composable
private fun EditDialog(
    title: String, current: String, monospace: Boolean, colors: LlhookColors,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onConfirm(current) },  // 取消=不变
        confirmButton = { TextButton(onClick = { onConfirm(text) }) {
            Text("保存", color = colors.accent, fontWeight = FontWeight.Bold)
        } },
        dismissButton = { TextButton(onClick = { onConfirm(current) }) { Text("取消", color = colors.subText) } },
        containerColor = colors.glass, titleContentColor = colors.text,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                singleLine = false, minLines = 2,
                shape = RoundedCornerShape(8.dp),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                    fontSize = 11.sp, color = colors.text
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
            )
        }
    )
}
