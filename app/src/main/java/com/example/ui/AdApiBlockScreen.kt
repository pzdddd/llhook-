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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.AdBlockHook
import bxxd.hook.Config
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
//  屏蔽广告接口 —— 自定义广告/追踪接口黑名单管理 (Compose 玻璃拟态全屏页)
//
//  数据源: bxxd.hook.AdBlockHook (JSON: [{p,e}]) → 三层网络拦截 (OkHttp/DNS/URL)
//  总开关: switch_block_ad_api (关闭则黑名单不生效)
//
//  功能:
//   · 总开关 + 实时生效 (规则改动写回 Config, 跨进程同步到 Blued, 无需重启)
//   · 添加 (输入 域名/URL片段, 自动去重)
//   · 修改 (点✏ 弹编辑框改 pattern)
//   · 删除 (点🗑 移除单条)
//   · 逐条独立开关 (关闭某条只暂停, 不丢规则)
//   · 导入 / 导出 (剪贴板 JSON, 便于备份与跨设备迁移)
//
//  规则匹配: 小写子串模糊匹配 (域名型三层全命中; 路径型 OkHttp/URL 层命中)
// ============================================================================

@Composable
fun AdApiBlockScreen(activity: Activity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()

    var rules by remember { mutableStateOf(AdBlockHook.getUserRules(ctx)) }
    var masterOn by remember { mutableStateOf(Config.isFeatureEnabled(AdBlockHook.KEY_USER_BLOCK_SWITCH, ctx)) }
    var input by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }   // (index, oldPattern)
    var showImportDialog by remember { mutableStateOf(false) }

    fun persist(newRules: List<AdBlockHook.AdRule>) {
        rules = newRules
        AdBlockHook.setUserRules(newRules, ctx)
    }

    fun clipboardText(): String =
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty().trim()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(llhookBackgroundBrush(isDark, listOf(Color(0xFF1E1B2E), Color(0xFF0F172A))))
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // ============ 顶栏 ============
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(onClick = onClose, glass = colors.glass.copy(alpha = 0.5f), stroke = colors.glassStroke.copy(alpha = 0.5f), size = 40,
                    contentDescription = "关闭页面") {
                    Icon(Icons.Filled.ArrowBack, null, tint = Color.White.copy(alpha = 0.7f))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("屏蔽广告接口", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    val on = rules.count { it.enabled }
                    Text("共 ${rules.size} 条 · 启用 $on 条${if (masterOn) " · 已开启拦截" else " · 未开启"}",
                        color = colors.subText, fontSize = 12.sp)
                }
                // 导出 / 导入 (次要按钮, 低饱和背景)
                GlassIconButton(onClick = { exportRules(ctx, rules) }, glass = colors.glass.copy(alpha = 0.5f), stroke = colors.glassStroke.copy(alpha = 0.5f),
                    size = 38, contentDescription = "导出") {
                    Icon(Icons.Filled.IosShare, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(onClick = { showImportDialog = true }, glass = colors.glass.copy(alpha = 0.5f), stroke = colors.glassStroke.copy(alpha = 0.5f),
                    size = 38, contentDescription = "导入") {
                    Icon(Icons.Outlined.FileDownload, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            }

            // ============ 总开关卡片 ============
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.glass,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassStroke),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("总开关", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("关闭后黑名单整体不生效; 逐条开关仍可单独控制",
                            color = colors.subText, fontSize = 11.sp)
                    }
                    Switch(
                        checked = masterOn,
                        onCheckedChange = {
                            masterOn = it
                            Config.setFeatureEnabled(AdBlockHook.KEY_USER_BLOCK_SWITCH, it, ctx)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF34C759),
                            uncheckedTrackColor = Color.Gray.copy(alpha = 0.2f),
                            uncheckedThumbColor = Color.White
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ============ 添加行 ============
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("接口域名或路径, 如 ads.x.com / /v1/ad/banner",
                        color = colors.subText, fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF34C759),
                        focusedBorderColor = Color(0xFF34C759),
                        unfocusedBorderColor = colors.glassStroke,
                        focusedContainerColor = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                GlassButton(
                    onClick = {
                        val p = input.trim()
                        if (p.isEmpty()) {
                            Toast.makeText(ctx, "请输入接口域名或路径", Toast.LENGTH_SHORT).show(); return@GlassButton
                        }
                        if (rules.any { it.pattern.equals(p, ignoreCase = true) }) {
                            Toast.makeText(ctx, "已存在相同规则", Toast.LENGTH_SHORT).show(); return@GlassButton
                        }
                        persist(rules + AdBlockHook.AdRule(p, true))
                        input = ""
                        Toast.makeText(ctx, "已添加", Toast.LENGTH_SHORT).show()
                    },
                    glass = Color(0xFF34C759), stroke = Color(0xFF34C759), shape = RoundedCornerShape(12.dp),
                    contentDescription = "添加规则",
                    modifier = Modifier.height(54.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
                        Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "规则说明: 域名型 (ads.xxx.com) 三层全部命中; 路径型 (/v1/ad/banner) 在 OkHttp/URL 层命中。" +
                    "不区分大小写, 子串模糊匹配。改动即时生效, 无需重启。",
                color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))

            // ============ 规则列表 ============
            if (rules.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🚫", fontSize = 44.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("暂无规则", color = colors.subText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text("在上方输入框添加, 或点右上角导入按钮批量导入",
                            color = colors.subText, fontSize = 12.sp)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    rules.forEachIndexed { idx, r ->
                        RuleCard(
                            rule = r, colors = colors,
                            onToggle = {
                                persist(rules.toMutableList().also {
                                    it[idx] = it[idx].copy(enabled = !it[idx].enabled)
                                })
                            },
                            onEdit = { editTarget = idx to r.pattern },
                            onDelete = {
                                persist(rules.toMutableList().also { it.removeAt(idx) })
                                Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                            }
                        )
                        if (idx < rules.size - 1) Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    // 清空全部
                    TextButton(
                        onClick = {
                            persist(emptyList())
                            Toast.makeText(ctx, "已清空全部规则", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text("🗑 清空全部", color = colors.danger, fontSize = 13.sp) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // ============ 编辑对话框 ============
        editTarget?.let { (idx, oldPattern) ->
            EditRuleDialog(
                initialPattern = oldPattern,
                colors = colors,
                onDismiss = { editTarget = null },
                onConfirm = { newP ->
                    val p = newP.trim()
                    if (p.isEmpty()) { Toast.makeText(ctx, "不能为空", Toast.LENGTH_SHORT).show(); return@EditRuleDialog }
                    if (rules.anyIndexed { i, r -> i != idx && r.pattern.equals(p, ignoreCase = true) }) {
                        Toast.makeText(ctx, "与其它规则重复", Toast.LENGTH_SHORT).show(); return@EditRuleDialog
                    }
                    persist(rules.toMutableList().also { it[idx] = it[idx].copy(pattern = p) })
                    editTarget = null
                    Toast.makeText(ctx, "已修改", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // ============ 导入对话框 ============
        if (showImportDialog) {
            ImportDialog(
                colors = colors,
                onDismiss = { showImportDialog = false },
                onImport = { text ->
                    val parsed = parseRulesJson(text)
                    if (parsed.isEmpty()) {
                        Toast.makeText(ctx, "解析失败或为空 (需 [{\"p\":\"...\",\"e\":true}] 格式)", Toast.LENGTH_LONG).show()
                        return@ImportDialog
                    }
                    // 合并去重 (忽略大小写), 新增的默认 enabled
                    val merged = rules.toMutableList()
                    var added = 0
                    parsed.forEach { pr ->
                        if (merged.none { it.pattern.equals(pr.pattern, ignoreCase = true) }) {
                            merged.add(pr); added++
                        }
                    }
                    persist(merged)
                    showImportDialog = false
                    Toast.makeText(ctx, "导入完成, 新增 $added 条 (共 ${merged.size} 条)", Toast.LENGTH_LONG).show()
                },
                onPaste = { clipboardText() }
            )
        }
    }
}

// ---------------------------------------------------------------------------
//  单条规则卡片: pattern + 逐条开关 + 编辑 + 删除
// ---------------------------------------------------------------------------
@Composable
private fun RuleCard(
    rule: AdBlockHook.AdRule,
    colors: LlhookColors,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.glass.copy(alpha = if (rule.enabled) 0.9f else 0.55f),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassStroke),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态圆点 (启用=绿, 禁用=灰)
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (rule.enabled) Color(0xFF34C759) else Color.Gray.copy(alpha = 0.5f))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                rule.pattern,
                color = if (rule.enabled) colors.text else colors.subText,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 编辑
            GlassIconButton(onClick = onEdit, glass = Color.Transparent, stroke = Color.Transparent, size = 34,
                contentDescription = "修改") {
                Icon(Icons.Outlined.Edit, null, tint = colors.subText, modifier = Modifier.size(18.dp))
            }
            // 删除
            GlassIconButton(onClick = onDelete, glass = Color.Transparent, stroke = Color.Transparent, size = 34,
                contentDescription = "删除") {
                Icon(Icons.Outlined.Delete, null, tint = colors.danger, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = rule.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF34C759),
                    uncheckedTrackColor = Color.Gray.copy(alpha = 0.2f),
                    uncheckedThumbColor = Color.White
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------
//  修改规则对话框
// ---------------------------------------------------------------------------
@Composable
private fun EditRuleDialog(
    initialPattern: String,
    colors: LlhookColors,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialPattern) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.glass,
        titleContentColor = colors.text,
        title = { Text("修改规则", color = colors.text, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("接口域名或路径", color = colors.subText, fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text, unfocusedTextColor = colors.text,
                    cursorColor = Color(0xFF34C759),
                    focusedBorderColor = Color(0xFF34C759),
                    unfocusedBorderColor = colors.glassStroke
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("保存", color = Color(0xFF34C759), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = colors.subText) } }
    )
}

// ---------------------------------------------------------------------------
//  导入对话框: 粘贴 JSON → 合并去重
// ---------------------------------------------------------------------------
@Composable
private fun ImportDialog(
    colors: LlhookColors,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    onPaste: () -> String
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.glass,
        titleContentColor = colors.text,
        title = { Text("导入规则", color = colors.text, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("粘贴导出的 JSON (格式 [{\"p\":\"...\",\"e\":true}])\n与现有规则自动合并去重, 新增默认启用。",
                    color = colors.subText, fontSize = 11.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("[{\"p\":\"ads.x.com\",\"e\":true}]", color = colors.subText, fontSize = 11.sp) },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.text, unfocusedTextColor = colors.text,
                        cursorColor = Color(0xFF34C759),
                        focusedBorderColor = Color(0xFF34C759),
                        unfocusedBorderColor = colors.glassStroke
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { text = onPaste() }) {
                        Text("📋 粘贴剪贴板", color = Color(0xFF34C759), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }) { Text("导入", color = Color(0xFF34C759), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = colors.subText) } }
    )
}

// ---------------------------------------------------------------------------
//  工具函数
// ---------------------------------------------------------------------------

/** 导出: 规则 → JSON → 剪贴板。 */
private fun exportRules(ctx: Context, rules: List<AdBlockHook.AdRule>) {
    if (rules.isEmpty()) {
        Toast.makeText(ctx, "当前无规则, 无可导出", Toast.LENGTH_SHORT).show(); return
    }
    val arr = JSONArray()
    rules.forEach { r ->
        arr.put(JSONObject().apply { put("p", r.pattern); put("e", r.enabled) })
    }
    val json = arr.toString(2)
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("llhook_ad_blocklist", json))
    Toast.makeText(ctx, "已导出 ${rules.size} 条到剪贴板", Toast.LENGTH_SHORT).show()
}

/** 解析导入的 JSON → 规则列表 (容错: 空pattern丢弃)。失败返回空列表。 */
private fun parseRulesJson(text: String): List<AdBlockHook.AdRule> {
    return try {
        val arr = JSONArray(text.trim())
        (0 until arr.length()).mapNotNull { i ->
            when (val el = arr.get(i)) {
                is JSONObject -> {
                    val p = el.optString("p").trim()
                    if (p.isEmpty()) null else AdBlockHook.AdRule(p, el.optBoolean("e", true))
                }
                is String -> {  // 兼容纯字符串数组 ["ads.x.com", ...]
                    val p = el.trim(); if (p.isEmpty()) null else AdBlockHook.AdRule(p, true)
                }
                else -> null
            }
        }
    } catch (e: Throwable) { emptyList() }
}

/** list.any 带索引 (避免在 lambda 里显式持有 idx 变量)。 */
private inline fun <T> List<T>.anyIndexed(predicate: (index: Int, T) -> Boolean): Boolean {
    for (i in indices) if (predicate(i, this[i])) return true
    return false
}
