package com.example.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import bxxd.hook.AutoVisitHook
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
//  一键站街 (Compose 玻璃拟态全屏页)
//
//  架构 (b): 宿主内就地弹出 (showHostComposeScreen)。
//  逻辑全用 bxxd.hook.AutoVisitHook:
//    - startAutoVisit(activity, minDist, maxDist, delayMin, delayMax, maxCount, onlineOnly)
//    - stopAutoVisit() / isVisiting / cachedUsers (hook gson 拦截附近列表填充)
//
//  工作流: 先在大厅下拉刷新加载附近用户 → cachedUsers 填充 → 配置参数 → 开始批量访问
//  访问原理: GET https://social.blued.cn/users/<uid>?from=nearby → 在对方访客列表留下脚印
// ============================================================================

@Composable
fun AutoVisitScreen(activity: Activity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()

    // 参数 (持久化到 SharedPreferences, 复用 llhook_blued_local_v2)
    val prefs = remember { ctx.getSharedPreferences("llhook_blued_local_v2", android.content.Context.MODE_PRIVATE) }
    var minDist by remember { mutableStateOf(prefs.getInt("autovisit_min_dist", 0)) }
    var maxDist by remember { mutableStateOf(prefs.getInt("autovisit_max_dist", 5000)) }
    var delayMin by remember { mutableStateOf(prefs.getInt("autovisit_delay_min", 800)) }
    var delayMax by remember { mutableStateOf(prefs.getInt("autovisit_delay_max", 2000)) }
    var maxCount by remember { mutableStateOf(prefs.getInt("autovisit_max_count", 0)) }
    var onlineOnly by remember { mutableStateOf(prefs.getBoolean("autovisit_online_only", false)) }

    // 运行状态
    var isRunning by remember { mutableStateOf(AutoVisitHook.isVisiting) }
    var cachedCount by remember { mutableStateOf(AutoVisitHook.cachedUsers.size) }

    // 轮询状态 (访问进行时)
    LaunchedEffect(Unit) {
        while (true) {
            isRunning = AutoVisitHook.isVisiting
            cachedCount = AutoVisitHook.cachedUsers.size
            delay(500)
        }
    }

    fun saveParams() {
        prefs.edit()
            .putInt("autovisit_min_dist", minDist)
            .putInt("autovisit_max_dist", maxDist)
            .putInt("autovisit_delay_min", delayMin)
            .putInt("autovisit_delay_max", delayMax)
            .putInt("autovisit_max_count", maxCount)
            .putBoolean("autovisit_online_only", onlineOnly)
            .apply()
    }

    fun start() {
        saveParams()
        AutoVisitHook.startAutoVisit(
            activity,
            minDist.toDouble(), maxDist.toDouble(),
            delayMin.toLong(), delayMax.toLong(),
            maxCount, onlineOnly
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                llhookBackgroundBrush(isDark, listOf(Color(0xFFFFFBEB), Color(0xFFFCE7F3)))
            )
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            // ============ 顶栏 (统一组件 + 运行状态指示灯) ============
            LlhookTopBar(
                title = "一键站街",
                subtitle = "批量访问附近用户 · 在对方访客列表留下脚印",
                onBack = onClose,
                glass = colors.glass,
                stroke = colors.glassStroke,
                textColor = colors.text,
                subTextColor = colors.subText
            ) {
                // 运行状态指示灯 (带无障碍语义)
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) Color(0xFF22C55E) else Color.Gray)
                        .semantics { contentDescription = if (isRunning) "访问运行中" else "未运行" }
                )
            }

            // ============ 缓存提示卡 ============
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (cachedCount > 0) Color(0x3322C55E) else Color(0x33FF9800),
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (cachedCount > 0) Icons.Filled.People else Icons.Filled.Refresh,
                        null, tint = colors.text, modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (cachedCount > 0) "已缓存 $cachedCount 名附近用户" else "尚未缓存附近用户",
                            color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (cachedCount > 0) "可开始批量访问 (会按下方条件筛选)"
                            else "请先回到大厅【下拉刷新】几次加载附近列表",
                            color = colors.subText, fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ============ 参数配置 ============
            // 距离范围
            ConfigSection("🎯 距离筛选 (米)", colors) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("最小", minDist, colors, Modifier.weight(1f)) { minDist = it.toIntOrNull() ?: 0 }
                    Text("～", color = colors.text, modifier = Modifier.align(Alignment.CenterVertically))
                    NumField("最大", maxDist, colors, Modifier.weight(1f)) { maxDist = it.toIntOrNull() ?: 5000 }
                }
                Text("例如 0 ~ 5000 米, 只访问 5km 内的用户",
                    color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }

            Spacer(Modifier.height(10.dp))

            // 延迟范围
            ConfigSection("⏱ 访问间隔 (毫秒)", colors) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("最小", delayMin, colors, Modifier.weight(1f)) { delayMin = it.toIntOrNull() ?: 800 }
                    Text("～", color = colors.text, modifier = Modifier.align(Alignment.CenterVertically))
                    NumField("最大", delayMax, colors, Modifier.weight(1f)) { delayMax = it.toIntOrNull() ?: 2000 }
                }
                Text("随机延迟防风控, 建议 800~2000ms (太密集易触发限制)",
                    color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }

            Spacer(Modifier.height(10.dp))

            // 数量上限
            ConfigSection("🔢 数量上限 (0 = 不限)", colors) {
                NumField("访问数量", maxCount, colors, Modifier.fillMaxWidth()) { maxCount = it.toIntOrNull() ?: 0 }
            }

            Spacer(Modifier.height(10.dp))

            // 仅在线
            Surface(
                shape = RoundedCornerShape(14.dp), color = colors.glass,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassStroke.copy(alpha = 0.5f)),
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Visibility, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("仅访问在线用户", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("过滤掉最后活跃超过 15 分钟的用户", color = colors.subText, fontSize = 11.sp)
                    }
                    Switch(checked = onlineOnly, onCheckedChange = { onlineOnly = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accent))
                }
            }

            Spacer(Modifier.height(24.dp))

            // ============ 开始/停止按钮 ============
            if (isRunning) {
                Button(
                    onClick = {
                        AutoVisitHook.stopAutoVisit()
                        Toast.makeText(ctx, "正在停止…", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(54.dp)
                ) {
                    Icon(Icons.Filled.Stop, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("停止访问", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text("访问进行中… 可随时停止, 已访问的会保留脚印",
                    color = colors.subText, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                Button(
                    onClick = { start() },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (cachedCount > 0) Color(0xFF8B5CF6) else Color.Gray
                    ),
                    enabled = cachedCount > 0,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(54.dp)
                ) {
                    Icon(Icons.Filled.RocketLaunch, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("🚀 开始批量访问", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                if (cachedCount == 0) {
                    Text("⚠️ 请先在大厅下拉刷新加载附近用户",
                        color = colors.warning, fontSize = 11.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }

            Spacer(Modifier.height(30.dp))
            Spacer(Modifier.navigationBarsPadding())
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
//  配置区容器
// ---------------------------------------------------------------------------
@Composable
private fun ConfigSection(
    title: String, colors: LlhookColors, content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = colors.glass,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassStroke.copy(alpha = 0.5f)),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp))
            content()
        }
    }
}

@Composable
private fun NumField(
    label: String, value: Int, colors: LlhookColors, modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value.toString(), onValueChange = onValueChange,
        label = { Text(label, color = colors.subText, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.6f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.6f),
            focusedBorderColor = colors.accent, unfocusedBorderColor = Color.Transparent,
            focusedTextColor = colors.text, unfocusedTextColor = colors.text, cursorColor = colors.accent
        ),
        modifier = modifier
    )
}
