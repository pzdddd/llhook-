package com.example.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.AutoVisitHook
import java.util.Locale

// ============================================================================
//  附近用户列表 (Compose 玻璃拟态全屏页)
//
//  数据源: bxxd.hook.AutoVisitHook.cachedUsers
//    — 「一键站街」(AutoVisitHook) 拦截 Gson.fromJson 抓取的附近交友列表,
//      每条 TargetUser(uid, name, distance, lastOperate)。
//    — 在大厅下拉刷新加载附近用户时自动填充。
//  入口: 模块设置 → 工具 → 「用户列表」
// ============================================================================

@Composable
fun NearbyUsersScreen(activity: Activity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()

    var users by remember { mutableStateOf<List<AutoVisitHook.TargetUser>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var sortByDistance by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }

    // 读取内存缓存 (cachedUsers 在 Blued 进程内, 直接读)
    LaunchedEffect(refreshKey) {
        users = AutoVisitHook.cachedUsers.values.toList()
    }

    val filtered = users.filter { u ->
        searchQuery.isBlank() || u.uid.contains(searchQuery, true) || u.name.contains(searchQuery, true)
    }.let { list ->
        if (sortByDistance) list.sortedBy { it.distance }
        else list.sortedByDescending { it.lastOperate }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(llhookBackgroundBrush(isDark, listOf(Color(0xFFEEF2FF), Color(0xFFE0E7FF))))
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            // ============ 顶栏 ============
            LlhookTopBar(
                title = "附近用户列表",
                subtitle = "已收集 ${users.size} 名 · ${if (sortByDistance) "按距离" else "按活跃"}",
                onBack = onClose,
                glass = colors.glass, stroke = colors.glassStroke,
                textColor = colors.text, subTextColor = colors.subText
            ) {
                // 刷新
                GlassIconButton(onClick = { refreshKey++ }, glass = colors.glass, stroke = colors.glassStroke, size = 42,
                    contentDescription = "刷新") {
                    Icon(Icons.Filled.Refresh, null, tint = colors.text)
                }
                Spacer(Modifier.width(8.dp))
                // 清空
                GlassIconButton(onClick = {
                    AutoVisitHook.cachedUsers.clear()
                    refreshKey++
                    Toast.makeText(ctx, "已清空收集缓存", Toast.LENGTH_SHORT).show()
                }, glass = colors.glass, stroke = colors.glassStroke, size = 42,
                    contentDescription = "清空") {
                    Icon(Icons.Filled.DeleteSweep, null, tint = colors.danger)
                }
                Spacer(Modifier.width(8.dp))
                // 导出
                GlassIconButton(onClick = {
                    val text = buildString {
                        users.sortedBy { it.distance }.forEach {
                            append("${it.uid}\t${it.name}\t%.2fkm\t${fmtActive(it.lastOperate)}\n".format(Locale.US, it.distance))
                        }
                    }
                    copyText(ctx, "附近用户列表(${"${users.size}名"})", text)
                }, glass = colors.glass, stroke = colors.glassStroke, size = 42,
                    contentDescription = "导出") {
                    Icon(Icons.Filled.IosShare, null, tint = colors.text)
                }
            }

            // ============ 搜索框 ============
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                placeholder = { Text("搜索 昵称 / UID", color = colors.subText, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = colors.subText) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Filled.Close, "清除", tint = colors.subText)
                    }
                },
                singleLine = true, shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.glass, unfocusedContainerColor = colors.glass,
                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = colors.text, unfocusedTextColor = colors.text, cursorColor = colors.accent
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )

            // ============ 排序 Tab ============
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabChip("📍 按距离", sortByDistance, colors.glass, colors.glassStroke, colors.subText) { sortByDistance = true }
                TabChip("⏱ 按活跃", !sortByDistance, colors.glass, colors.glassStroke, colors.subText) { sortByDistance = false }
            }

            // ============ 列表 ============
            if (filtered.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (searchQuery.isNotEmpty()) "🔍" else "👥", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (searchQuery.isNotEmpty()) "未找到匹配的用户"
                            else "暂无附近用户\n请在大厅下拉刷新加载附近用户列表",
                            color = colors.subText, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.uid }) { u ->
                        NearbyUserCard(u, colors)
                    }
                }
            }
        }
    }
}

// ============================================================================
//  用户卡片
// ============================================================================
@Composable
private fun NearbyUserCard(u: AutoVisitHook.TargetUser, colors: LlhookColors) {
    val ctx = LocalContext.current
    val avatarColor = avatarColor(u.uid)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.glass)
            .border(1.dp, colors.glassStroke, RoundedCornerShape(18.dp))
            .clickable {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("UID", u.uid))
                Toast.makeText(ctx, "UID 已复制: ${u.uid}", Toast.LENGTH_SHORT).show()
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 圆形首字符头像
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(avatarColor.copy(alpha = 0.18f))
                .border(1.dp, avatarColor.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                u.name.firstOrNull()?.toString() ?: "?",
                color = avatarColor, fontWeight = FontWeight.Bold, fontSize = 18.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        // 信息
        Column(modifier = Modifier.weight(1f)) {
            Text(u.name, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 距离
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(avatarColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(fmtDistance(u.distance), color = avatarColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(8.dp))
                // 活跃时间
                Text(fmtActive(u.lastOperate), color = colors.subText, fontSize = 11.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text("UID: ${u.uid}", color = colors.subText, fontSize = 11.sp, maxLines = 1)
        }
        Icon(Icons.Filled.ContentCopy, "复制UID", tint = colors.subText, modifier = Modifier.size(18.dp))
    }
}

// ============================================================================
//  小工具
// ============================================================================
private fun fmtDistance(km: Double): String =
    when {
        km <= 0.0 || km >= 99998.0 -> "未知"
        km < 1.0 -> "${(km * 1000).toInt()} m"
        else -> "%.1f km".format(Locale.US, km)
    }

private fun fmtActive(ts: Long): String {
    if (ts <= 0) return "未知"
    val ms = if (ts > 1_000_000_000_000L) ts else ts * 1000L
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 0 -> "在线"
        diff < 60_000L -> "刚刚活跃"
        diff < 3_600_000L -> "${diff / 60_000L}分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L}小时前"
        else -> "${diff / 86_400_000L}天前"
    }
}

private fun avatarColor(key: String): Color {
    val palette = listOf(
        Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFFF59E0B),
        Color(0xFFEF4444), Color(0xFF8B5CF6), Color(0xFFEC4899),
        Color(0xFF06B6D4), Color(0xFF84CC16)
    )
    return palette[Math.abs(key.hashCode()) % palette.size]
}

@Composable
private fun TabChip(text: String, selected: Boolean, glass: Color, stroke: Color, unselectedText: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF3B82F6).copy(alpha = 0.15f) else glass)
            .border(1.dp, if (selected) Color(0xFF3B82F6) else stroke, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (selected) Color(0xFF3B82F6) else unselectedText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun copyText(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "$label 已导出到剪贴板", Toast.LENGTH_SHORT).show()
}
