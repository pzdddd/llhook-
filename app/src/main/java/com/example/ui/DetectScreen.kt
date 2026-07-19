package com.example.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.DetectHook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
//  设备检测报告 (Compose 玻璃拟态全屏页)
//
//  架构 (b): 宿主内就地弹出 (showHostComposeScreen)。
//  逻辑全用 bxxd.hook.DetectHook:
//    - generateReport(ctx): 本地环境检测(模拟器/Root/调试器/Hook App/Frida/VPN/代理) +
//                           Blued 风控判定(解析采集 JSON 的 rootFlag/emulatorFlag/hookFlag/...)
//    - triggerCollectAndReport(activity, ctx): 主动调用 Blued 采集器采集一次再生成报告
//  UI 全部 llhook Compose 玻璃拟态。原 iOS 风格 AlertDialog 已删 (见 .legacy-ui/DetectHook.kt.disabled)。
// ============================================================================

@Composable
fun DetectScreen(activity: Activity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val isDark = isSystemInDarkTheme() // 页面为固定深色仪表盘设计, 配色不随系统切换 (背景恒为深色)
    val textColor = Color.White
    val subTextColor = Color(0xFFA0AEC0)
    val glassLight = Color(0xE6FFFFFF)
    val glassDark = Color(0xCC1E293B)
    val glass = glassDark
    val glassStroke = Color(0x33FFFFFF)

    val danger = Color(0xFFEF4444)
    val warning = Color(0xFFFF9800)
    val safe = Color(0xFF22C55E)

    // 报告状态
    var report by remember { mutableStateOf<DetectHook.DetectReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var collectMsg by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    // 展开的原始 JSON
    var showRawJson by remember { mutableStateOf(false) }

    // 首次加载: 生成报告
    LaunchedEffect(Unit) {
        loading = true
        report = withContext(Dispatchers.IO) { DetectHook.generateReport(ctx) }
        loading = false
    }

    fun refresh(trigger: Boolean) {
        scope.launch {
            loading = true
            collectMsg = null
            val r = withContext(Dispatchers.IO) {
                if (trigger) DetectHook.triggerCollectAndReport(activity, ctx)
                else DetectHook.generateReport(ctx)
            }
            report = r
            loading = false
            collectMsg = when {
                trigger && r == null -> "采集器未就绪 (Hook 未生效或 App 未调用采集), 请先正常使用 App 一会"
                trigger && r != null && r.captureTime > 0 -> "✅ 已主动采集 (${r.capturedJson?.length ?: 0} 字节)"
                trigger -> "采集器调用成功但无数据"
                else -> null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0F172A), Color(0xFF1E293B))
                )
            )
    ) {
        // ============ 顶栏 ============
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(onClick = onClose, glass = glass, stroke = glassStroke, size = 42) {
                    Icon(Icons.Filled.ArrowBack, "关闭", tint = textColor)
                }
                Spacer(Modifier.width(12.dp))
                Text("设备检测", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // 主动采集
                GlassIconButton(onClick = { refresh(true) }, glass = glass, stroke = glassStroke, size = 42) {
                    Icon(Icons.Filled.BugReport, "主动采集", tint = Color(0xFF3B82F6))
                }
            }
            Text(
                "模拟器 / Root / Hook / 多开 / 调试 / VPN… 本地自查 + Blued 对你设备的真实风控判定",
                color = subTextColor, fontSize = 12.sp, modifier = Modifier.padding(start = 54.dp, top = 2.dp)
            )
        }

        // ============ 内容 ============
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(if (report == null) "正在检测…" else "正在采集…", color = Color.White, fontSize = 14.sp)
                }
            }
        } else if (report == null) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("检测失败", color = Color.White)
            }
        } else {
            val r = report!!
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 110.dp, bottom = 100.dp)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // —— 风险概览卡 ——
                item { RiskOverviewCard(r, glass, glassStroke) }

                // —— 提示条 ——
                collectMsg?.let { msg ->
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (msg.startsWith("✅")) Color(0xCC22C55E) else Color(0xCCFF9800)
                        ) {
                            Text(msg, color = Color.White, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().padding(12.dp))
                        }
                    }
                }

                // —— 切换 Tab ——
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TabChip("本地环境 (${r.localChecks.size})", selectedTab == 0, glass, glassStroke) { selectedTab = 0 }
                        TabChip("Blued 判定 (${r.bluedChecks.size})", selectedTab == 1, glass, glassStroke) { selectedTab = 1 }
                    }
                }

                // —— 检查项列表 ——
                val checks = if (selectedTab == 0) r.localChecks else r.bluedChecks
                if (checks.isEmpty() && selectedTab == 1) {
                    item {
                        Surface(shape = RoundedCornerShape(16.dp), color = glass, modifier = Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("⏳", fontSize = 32.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("尚未捕获 Blued 采集数据", color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(4.dp))
                                Text("点右上角 🐛 主动采集, 或正常使用 App 一会再回来检测",
                                    color = subTextColor, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                } else {
                    // 按类目分组
                    val grouped = checks.groupBy { it.category }
                    grouped.forEach { (cat, itemList) ->
                        item {
                            Text("  $cat", color = subTextColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                        }
                        items(itemList) { c ->
                            CheckRow(c, glass, glassStroke, textColor, subTextColor, danger, warning, safe)
                        }
                    }
                }

                // —— 原始 JSON 展开 ——
                if (r.capturedJson != null) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp), color = glass,
                            modifier = Modifier.fillMaxWidth().clickable { showRawJson = !showRawJson }
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (showRawJson) "▼ 收起原始采集数据" else "▶ 查看原始采集数据 (${r.capturedJson!!.length} 字节)",
                                    color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.ContentCopy, "复制JSON", tint = subTextColor, modifier = Modifier.size(18.dp).clickable {
                                    copy(ctx, "Blued采集数据", r.capturedPretty ?: r.capturedJson!!)
                                    Toast.makeText(ctx, "JSON 已复制", Toast.LENGTH_SHORT).show()
                                })
                            }
                        }
                    }
                    item {
                        AnimatedVisibility(showRawJson) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xF2112837),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    r.capturedPretty ?: r.capturedJson!!,
                                    color = Color(0xFFA7F3D0),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ============ 底部操作栏 ============
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { refresh(false) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新检测", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        copy(ctx, "llhook设备检测报告", DetectHook.buildReportText(r))
                        Toast.makeText(ctx, "完整报告已复制", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制报告", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  风险概览卡
// ---------------------------------------------------------------------------
@Composable
private fun RiskOverviewCard(
    r: DetectHook.DetectReport, glass: Color, stroke: Color
) {
    val (levelColor, levelIcon, levelDesc) = when (r.riskLevel) {
        DetectHook.RiskLevel.SAFE -> Triple(Color(0xFF22C55E), Icons.Filled.CheckCircle,
            "未检测到明显风险, 设备环境干净")
        DetectHook.RiskLevel.WARNING -> Triple(Color(0xFFFF9800), Icons.Filled.Warning,
            "检测到 ${r.hitCount} 项可解释风险 (VPN/调试等)")
        DetectHook.RiskLevel.DANGER -> Triple(Color(0xFFEF4444), Icons.Filled.Dangerous,
            "高危! 命中模拟器/Root/Hook 等关键项, 极易被风控")
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = glass.copy(alpha = 0.95f),
        border = androidx.compose.foundation.BorderStroke(1.dp, levelColor.copy(alpha = 0.4f)),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(levelColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(levelIcon, null, tint = levelColor, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("风险等级: ${r.riskLevel.label}", color = levelColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(levelDesc, color = Color(0xFF475569), fontSize = 12.sp)
                }
                Text("${r.hitCount}", color = levelColor, fontSize = 34.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(12.dp))
            // 统计条
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("本地 ${r.localChecks.size}", Color(0xFF3B82F6))
                StatChip("Blued ${r.bluedChecks.size}", Color(0xFF8B5CF6))
                StatChip("命中 ${r.hitCount}", Color(0xFFEF4444))
                if (r.captureTime > 0) StatChip("已采集", Color(0xFF22C55E))
            }
        }
    }
}

@Composable
private fun StatChip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.14f)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, glass: Color, stroke: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) Color(0xFF4F46E5) else glass,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Color.Transparent else stroke),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(text, color = if (selected) Color.White else Color(0xFF64748B),
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

// ---------------------------------------------------------------------------
//  单条检查项
// ---------------------------------------------------------------------------
@Composable
private fun CheckRow(
    c: DetectHook.Check, glass: Color, stroke: Color,
    textColor: Color, subTextColor: Color, danger: Color, warning: Color, safe: Color
) {
    val color = if (c.hit) danger else safe
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = glass,
        border = androidx.compose.foundation.BorderStroke(1.dp, stroke.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (c.hit) Icons.Filled.Dangerous else Icons.Filled.Security,
                    null, tint = color, modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.item, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = color.copy(alpha = 0.14f)
                    ) {
                        Text(
                            if (c.hit) "命中" else "安全",
                            color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(c.detail, color = subTextColor, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  工具
// ---------------------------------------------------------------------------
private fun copy(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
