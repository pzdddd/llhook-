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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// ============================================================================
//  追踪结果卡片 (Compose 玻璃拟态)
//
//  供 RealLocationHook / TrackHook 的追踪成功/失败结果展示。
//  成功: 坐标卡片 + 前往地图 (雷达模式) + 复制坐标 + 关闭
//  失败: 原因 + 关闭
//
//  追踪逻辑 (trilateration 三点定位 / doMathTrackingSilent 数学追踪) 全保留在
//  bxxd.hook.RealLocationHook / TrackHook, 本页只负责展示结果。
// ============================================================================

/** 追踪结果公共入口: 弹出 Compose 结果卡片。供 RealLocationHook / TrackHook 调用。
 *  当 lat==0 && lng==0 时进入「纯信息模式」(不显示坐标卡片/前往地图按钮), 供资料透视等纯文本结果复用。 */
fun showTrackResult(
    activity: Activity,
    success: Boolean,
    msg: String,
    lat: Double = 0.0,
    lng: Double = 0.0,
    title: String? = null
) {
    showHostComposeScreen(activity) { onClose ->
        TrackResultScreen(activity, success, msg, lat, lng, onClose, title)
    }
}

@Composable
fun TrackResultScreen(
    activity: Activity,
    success: Boolean,
    msg: String,
    lat: Double,
    lng: Double,
    onClose: () -> Unit,
    titleOverride: String? = null
) {
    val isDark = isSystemInDarkTheme()
    val accent = Color(0xFF22C55E)
    val danger = Color(0xFFEF4444)
    val radar = Color(0xFF8B5CF6)
    // 文字颜色跟随系统深色模式 (之前硬编码白色 → 浅色模式下看不见)
    val textColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subTextColor = if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF64748B)
    val glass = if (isDark) Color(0xF21E293B) else Color(0xF2FFFFFF)
    val stroke = if (isDark) Color(0x44FFFFFF) else Color(0xCCFFFFFF)
    // 遮罩层: 轻度半透明 (之前 0.85→0.95 全黑, 现在柔和)
    val scrimColor = Color.Black.copy(alpha = if (isDark) 0.6f else 0.4f)
    val hasCoord = success && lat != 0.0 && lng != 0.0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onClose
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = glass,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, (if (success) accent else danger).copy(alpha = 0.5f)
            ),
            shadowElevation = 16.dp,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .clickable(  // 阻止点击卡片本身关闭
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null, onClick = {}
                )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // —— 图标 ——
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background((if (success) accent else danger).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (success) {
                        Text("🎯", fontSize = 32.sp)
                    } else {
                        Text("❌", fontSize = 32.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))

                // —— 标题 ——
                Text(
                    titleOverride ?: if (success) "雷达锁定成功" else "追踪失败",
                    color = if (success) accent else danger,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (success) (if (hasCoord) "已锁定目标位置" else "操作成功") else "未能定位目标",
                    color = subTextColor, fontSize = 12.sp
                )

                if (hasCoord) {
                    Spacer(Modifier.height(16.dp))
                    // —— 坐标卡片 ——
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isDark) Color.Black.copy(alpha = 0.3f) else Color(0xFF0F172A).copy(alpha = 0.04f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, radar.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp)
                                    .clip(CircleShape)
                                    .background(radar.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.LocationOn, null, tint = radar, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("纬度  ${String.format(Locale.US, "%.6f", lat)}",
                                    color = textColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                Text("经度  ${String.format(Locale.US, "%.6f", lng)}",
                                    color = textColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            }
                            Icon(Icons.Filled.CheckCircle, null, tint = accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // —— 详情消息 ——
                if (msg.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isDark) Color.Black.copy(alpha = 0.2f) else Color(0xFF0F172A).copy(alpha = 0.03f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(msg, color = subTextColor, fontSize = 12.sp, lineHeight = 17.sp,
                            modifier = Modifier.padding(12.dp))
                    }
                }

                Spacer(Modifier.height(20.dp))

                // —— 操作按钮 ——
                if (hasCoord) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                onClose()
                                // 下一帧再开地图, 确保结果 Dialog 已关闭
                                activity.findViewById<android.view.View>(android.R.id.content)?.post {
                                    try { bxxd.hook.MapOverlay.showMap(activity, lat, lng) }
                                    catch (e: Throwable) { Toast.makeText(activity, "地图唤起失败: ${e.message}", Toast.LENGTH_LONG).show() }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = radar),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Icon(Icons.Filled.Map, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("前往地图", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("追踪坐标", "$lat, $lng"))
                                Toast.makeText(activity, "坐标已复制", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("复制", color = textColor)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedButton(
                    onClick = onClose,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) { Text("关闭", color = subTextColor) }
            }
        }
    }
}
