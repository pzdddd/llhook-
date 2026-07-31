package com.example.ui

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.Config

/**
 * 「悄悄查看所有消息」开关面板 (Compose 玻璃拟态)。
 *
 * 长按消息页顶部 Tab (child_item_content_layout) 时, 通过 [showHostComposePanel] 弹出。
 * 开关绑定 [SWITCH_KEY] = switch_secret_view_all, 与 ChatAdvanceHook.hookReadReceipt 联动:
 * 开启后拦截已读回执 (com.blued.im.private_chat.Receipt/Read), 对方聊天界面不再显示「已读」。
 *
 * 与模块整体毛玻璃风格统一 (参考 MarkUserPanel)。
 */
@Composable
fun SecretViewAllPanel(onClose: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()
    val ctx = LocalContext.current

    var enabled by rememberConfigBoolean("switch_secret_view_all", false)
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val bg = if (isDark) listOf(Color(0xFF0F172A), Color(0xFF1E293B))
    else listOf(Color(0xFFEFF6FF), Color(0xFFE0E7FF))
    val headerBrush = Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)))

    Box(
        Modifier
            .fillMaxSize()
            // 去掉半透明黑色遮罩: 面板四周直出宿主界面 (透明)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClose() }
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.86f)
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
                                Icon(Icons.Outlined.Visibility, null, tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("悄悄查看", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Text("悄悄查看所有消息", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("查看消息后, 对方聊天界面不会显示「已读」",
                                    color = Color.White.copy(alpha = 0.85f), fontSize = 10.5.sp)
                            }
                        }
                    }

                    // ===== 开关行 =====
                    val accent = if (enabled) colors.accent else Color(0xFF94A3B8)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                enabled = !enabled
                                Toast.makeText(
                                    ctx,
                                    if (enabled) "已开启悄悄查看所有消息" else "已关闭悄悄查看所有消息",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.MarkChatRead, null, tint = accent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("开启悄悄查看所有消息", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("拦截已读回执: com.blued.im.private_chat.Receipt/Read",
                                color = colors.subText, fontSize = 10.5.sp)
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                Toast.makeText(
                                    ctx,
                                    if (it) "已开启悄悄查看所有消息" else "已关闭悄悄查看所有消息",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.accent,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFCBD5E1)
                            )
                        )
                    }

                    // ===== 当前状态提示 =====
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        val (statusText, statusColor) = if (enabled) {
                            "● 已开启: 全部会话进入无痕模式, 你的「已读」不会回传给对方" to colors.accent
                        } else {
                            "○ 已关闭: 查看消息后会正常发送已读回执" to colors.subText
                        }
                        Text(
                            statusText,
                            color = statusColor,
                            fontSize = 11.5.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9))
                                .border(1.dp, colors.glassStroke, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }

                    // ===== 底部关闭按钮 =====
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassButton(
                            onClick = onClose,
                            glass = colors.glass,
                            stroke = colors.glassStroke,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "完成",
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}
