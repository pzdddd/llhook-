package com.example.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.Ban2Hook

/**
 * 标记用户面板 (Compose 玻璃拟态 · 大气版)。
 *
 * 长按聊天页 🎁 送礼按钮时, 通过 showHostComposePanel 弹出此面板,
 * 让用户把对方标记为「已注销 / 风险 / 诈骗」、收藏或取消标记。
 * 替代原生 AlertDialog, 与模块整体毛玻璃风格统一。
 */
@Composable
fun MarkUserPanel(
    onClose: () -> Unit,
    uid: String,
    displayName: String
) {
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val activity = ctx as? Activity
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val bg = if (isDark) listOf(Color(0xFF0F172A), Color(0xFF1E293B))
    else listOf(Color(0xFFEFF6FF), Color(0xFFE0E7FF))
    val headerBrush = Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)))

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable { onClose() }
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.88f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(bg))
                    .border(1.dp, colors.glassStroke, RoundedCornerShape(24.dp))
                    .clickable(enabled = false) {}
            ) {
                Column {
                    // ===== 渐变 Header =====
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(headerBrush)
                            .padding(horizontal = 20.dp, vertical = 18.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.Label, null, tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("标记用户", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Text(displayName, color = Color.White, fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("UID $uid", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }

                    // ===== 选项区 =====
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        MarkCard("已注销", "对方已注销 / 消失账号", Color(0xFF94A3B8), Icons.Outlined.PersonRemove) {
                            if (activity != null) { Ban2Hook.markUser(activity, uid, displayName, "消失"); Toast.makeText(activity, "已标记，并加入风险用户窗口", Toast.LENGTH_SHORT).show() }
                            onClose()
                        }
                        MarkCard("风险用户", "标记为风险用户", Color(0xFFEF4444), Icons.Outlined.Warning) {
                            if (activity != null) { Ban2Hook.markUser(activity, uid, displayName, "风险"); Toast.makeText(activity, "已标记，并加入风险用户窗口", Toast.LENGTH_SHORT).show() }
                            onClose()
                        }
                        MarkCard("诈骗风险", "标记为诈骗用户", Color(0xFFF97316), Icons.Outlined.GppBad) {
                            if (activity != null) { Ban2Hook.markUser(activity, uid, displayName, "诈骗"); Toast.makeText(activity, "已标记，并加入风险用户窗口", Toast.LENGTH_SHORT).show() }
                            onClose()
                        }
                        MarkCard("收藏到名单", "加入我的收藏夹", Color(0xFF22C55E), Icons.Outlined.BookmarkAdd) {
                            if (activity != null) { Ban2Hook.addCollectedUser(activity, uid, displayName); Toast.makeText(activity, "已收藏到名单", Toast.LENGTH_SHORT).show() }
                            onClose()
                        }
                        MarkCard("取消标记", "移除该用户的所有标记", Color(0xFF64748B), Icons.Outlined.LinkOff) {
                            if (activity != null) { Ban2Hook.removeRiskUser(activity, true, uid); Toast.makeText(activity, "已取消标记", Toast.LENGTH_SHORT).show() }
                            onClose()
                        }
                    }

                    // ===== 取消按钮 =====
                    Box(
                        Modifier
                            .padding(horizontal = 14.dp)
                            .padding(bottom = 16.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.glass)
                            .border(1.dp, colors.glassStroke, RoundedCornerShape(14.dp))
                            .clickable { onClose() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("取消", color = colors.subText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** 独立卡片式选项: 左侧彩色竖条 + 图标圆 + 标题/副标题 + 右箭头。 */
@Composable
private fun MarkCard(title: String, subtitle: String, accent: Color, icon: ImageVector, onClick: () -> Unit) {
    val colors = llhookColorScheme()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.glass)
            .border(1.dp, colors.glassStroke, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(start = 0.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧 accent 竖条
        Box(Modifier.width(5.dp).height(38.dp).background(accent))
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.subText, fontSize = 11.sp)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = colors.subText.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
    }
}


