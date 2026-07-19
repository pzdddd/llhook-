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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.Ban2Hook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
//  风控用户列表 (Compose 玻璃拟态全屏页)
//
//  架构 (b): 宿主内就地弹出 (showHostComposeScreen)。
//  逻辑全用 bxxd.hook.Ban2Hook:
//    - 数据: getInterceptedUsers / getCollectedUsers / addCollectedUser / removeRiskUser
//           / renameRiskUser / moveInterceptedToCollected / clearAll / exportAll
//    - 操作: operateBlacklist (拉黑/解除) / jumpToUserProfile / jumpToChatRoom
//           / resolveUnionIdAndJump (unionUid → realUid → 跳转) / fetchRealUid
//  UI 全部 llhook Compose 玻璃拟态。原 1053 行的传统 Dialog + LinearLayout 实现已弃用。
// ============================================================================

@Composable
fun RiskUsersScreen(activity: Activity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()

    var selectedTab by remember { mutableIntStateOf(0) } // 0=拦截, 1=收藏
    var intercepted by remember { mutableStateOf<List<Ban2Hook.RiskUser>>(emptyList()) }
    var collected by remember { mutableStateOf<List<Ban2Hook.RiskUser>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var renamingUser by remember { mutableStateOf<Pair<Ban2Hook.RiskUser, Boolean>?>(null) } // (user, isIntercepted)
    var actionUser by remember { mutableStateOf<Pair<Ban2Hook.RiskUser, Boolean>?>(null) }    // 操作菜单

    // 加载数据
    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) {
            intercepted = Ban2Hook.getInterceptedUsers(ctx)
            collected = Ban2Hook.getCollectedUsers(ctx)
        }
    }

    fun refresh() { refreshKey++ }

    val currentList = (if (selectedTab == 0) intercepted else collected).filter { u ->
        searchQuery.isBlank() || u.uid.contains(searchQuery, true) ||
        u.name.contains(searchQuery, true) || u.source.contains(searchQuery, true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                llhookBackgroundBrush(isDark, listOf(Color(0xFFEEF2FF), Color(0xFFE0E7FF)))
            )
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            // ============ 顶栏 (统一组件) ============
            LlhookTopBar(
                title = "风控用户列表",
                subtitle = "拦截 ${intercepted.size} · 收藏 ${collected.size}",
                onBack = onClose,
                glass = colors.glass,
                stroke = colors.glassStroke,
                textColor = colors.text,
                subTextColor = colors.subText
            ) {
                // 添加
                GlassIconButton(onClick = { showAddDialog = true }, glass = colors.glass, stroke = colors.glassStroke, size = 42,
                    contentDescription = "手动添加用户") {
                    Icon(Icons.Filled.PersonAdd, null, tint = Color(0xFF3B82F6))
                }
                Spacer(Modifier.width(8.dp))
                // 导出
                GlassIconButton(onClick = {
                    copy(ctx, "风控用户列表", Ban2Hook.exportAll(ctx))
                    Toast.makeText(ctx, "已导出到剪贴板", Toast.LENGTH_SHORT).show()
                }, glass = colors.glass, stroke = colors.glassStroke, size = 42,
                    contentDescription = "导出列表") {
                    Icon(Icons.Filled.IosShare, null, tint = colors.text)
                }
            }

            // ============ 搜索框 ============
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                placeholder = { Text("搜索 UID / 备注名 / 来源", color = colors.subText, fontSize = 13.sp) },
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

            // ============ Tab ============
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabChip("🛡 拦截记录 (${intercepted.size})", selectedTab == 0, colors.glass, colors.glassStroke) { selectedTab = 0 }
                TabChip("⭐ 我的收藏 (${collected.size})", selectedTab == 1, colors.glass, colors.glassStroke) { selectedTab = 1 }
            }

            // ============ 列表 ============
            if (currentList.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (searchQuery.isNotEmpty()) "🔍" else if (selectedTab == 0) "🛡" else "⭐", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (searchQuery.isNotEmpty()) "未找到匹配的用户"
                            else if (selectedTab == 0) "暂无拦截记录\n当 Blued 拦截到风险用户时会自动记录在此"
                            else "暂无收藏\n点右上角 + 手动添加, 或长按拦截记录转存",
                            color = colors.subText, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(currentList, key = { it.uid + it.time }) { u ->
                        RiskUserCard(
                            u = u, isIntercepted = selectedTab == 0, colors = colors,
                            onTap = { actionUser = u to (selectedTab == 0) },
                            onRename = { renamingUser = u to (selectedTab == 0) }
                        )
                    }
                    item {
                        // 清空按钮
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                Ban2Hook.clearAll(ctx, selectedTab == 0); refresh()
                                Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.danger.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.DeleteSweep, null, tint = colors.danger, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("清空${if (selectedTab == 0) "拦截记录" else "收藏"}", color = colors.danger)
                        }
                        Spacer(Modifier.height(80.dp))
                    }
                }
            }
        }
    }

    // ============ 添加用户对话框 ============
    if (showAddDialog) {
        AddUserDialog(colors) { uid, name ->
            if (uid.isNotBlank()) {
                Ban2Hook.addCollectedUser(ctx, uid.trim(), name.trim())
                refresh(); Toast.makeText(ctx, "已添加到收藏", Toast.LENGTH_SHORT).show()
            }
            showAddDialog = false
        }
    }

    // ============ 备注对话框 ============
    renamingUser?.let { (u, isInt) ->
        RenameDialog(u.name, colors) { newName ->
            if (newName.isNotBlank()) {
                Ban2Hook.renameRiskUser(ctx, isInt, u.uid, newName.trim())
                refresh()
            }
            renamingUser = null
        }
    }

    // ============ 操作菜单 ============
    actionUser?.let { (u, isInt) ->
        ActionSheet(
            u = u, isIntercepted = isInt, colors = colors,
            onDismiss = { actionUser = null },
            onAction = { action ->
                actionUser = null
                handleRiskUserAction(action, u, isInt, activity, ctx, scope) { refresh() }
            }
        )
    }
}

// ---------------------------------------------------------------------------
//  单条用户卡片
// ---------------------------------------------------------------------------
@Composable
private fun RiskUserCard(
    u: Ban2Hook.RiskUser, isIntercepted: Boolean, colors: LlhookColors,
    onTap: () -> Unit, onRename: () -> Unit
) {
    val sourceColor = when {
        u.source.contains("消失") -> Color(0xFF9CA3AF)
        u.source.contains("离线") -> Color(0xFF6B7280)
        u.source.contains("风险") -> Color(0xFFEF4444)
        u.source.contains("诈骗") -> Color(0xFFDC2626)
        u.source.contains("手动") -> Color(0xFF3B82F6)
        else -> Color(0xFF8B5CF6)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.glass,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassStroke.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // 头像占位
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(sourceColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(u.name.take(1).ifEmpty { "?" }, color = sourceColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (u.source.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = sourceColor.copy(alpha = 0.15f)) {
                            Text(u.source, color = sourceColor, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text("UID: ${u.uid}", color = colors.subText, fontSize = 11.sp, maxLines = 1)
                if (u.time > 0) {
                    Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(u.time)),
                        color = colors.subText.copy(alpha = 0.7f), fontSize = 10.sp)
                }
            }
            Icon(Icons.Filled.ChevronRight, null, tint = colors.subText, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------------------
//  操作菜单 (底部弹窗风格)
// ---------------------------------------------------------------------------
@Composable
private fun ActionSheet(
    u: Ban2Hook.RiskUser, isIntercepted: Boolean, colors: LlhookColors,
    onDismiss: () -> Unit,
    onAction: (RiskAction) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        containerColor = colors.glass,
        titleContentColor = colors.text,
        title = { Text(u.name, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("UID: ${u.uid}", color = colors.subText, fontSize = 12.sp)
                if (u.unionUid.isNotEmpty()) Text("Union: ${u.unionUid}", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))
                val actions = if (isIntercepted) listOf(
                    RiskAction.BLOCK_AND_VIEW to "🚫 拉黑并访问主页",
                    RiskAction.UNBLOCK to "✅ 解除拉黑",
                    RiskAction.VIEW_PROFILE to "👤 查看主页",
                    RiskAction.COPY_UID to "📋 复制 UID",
                    RiskAction.MOVE_TO_COLLECTED to "⭐ 转存到收藏",
                    RiskAction.RENAME to "✏️ 备注",
                    RiskAction.DELETE to "🗑 删除"
                ) else listOf(
                    RiskAction.VIEW_PROFILE to "👤 跳转主页",
                    RiskAction.VIEW_CHAT to "💬 跳转聊天",
                    RiskAction.COPY_UID to "📋 复制 UID",
                    RiskAction.RENAME to "✏️ 备注",
                    RiskAction.DELETE to "🗑 删除"
                )
                actions.forEach { (action, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onAction(action) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = if (action == RiskAction.DELETE) colors.danger else colors.text, fontSize = 14.sp)
                    }
                    if (action != actions.last().first) HorizontalDivider(color = colors.glassStroke.copy(alpha = 0.3f))
                }
            }
        }
    )
}

private enum class RiskAction {
    BLOCK_AND_VIEW, UNBLOCK, VIEW_PROFILE, VIEW_CHAT, COPY_UID, MOVE_TO_COLLECTED, RENAME, DELETE
}

private fun handleRiskUserAction(
    action: RiskAction, u: Ban2Hook.RiskUser, isIntercepted: Boolean,
    activity: Activity, ctx: Context, scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit
) {
    when (action) {
        RiskAction.COPY_UID -> {
            copy(ctx, "UID", u.uid)
            Toast.makeText(ctx, "UID 已复制", Toast.LENGTH_SHORT).show()
        }
        RiskAction.DELETE -> {
            Ban2Hook.removeRiskUser(ctx, isIntercepted, u.uid)
            onDone(); Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
        }
        RiskAction.RENAME -> { /* 由 renamingUser 状态处理, 这里不应到达 */ }
        RiskAction.MOVE_TO_COLLECTED -> {
            Ban2Hook.moveInterceptedToCollected(ctx, u.uid)
            onDone(); Toast.makeText(ctx, "已转存到收藏", Toast.LENGTH_SHORT).show()
        }
        RiskAction.VIEW_PROFILE -> {
            // unionUid → fetchRealUid → 跳转; 已是 realUid 则直接跳
            scope.launch {
                val realUid = withContext(Dispatchers.IO) {
                    if (u.unionUid.isNotEmpty() && u.uid != u.unionUid) Ban2Hook.fetchRealUid(activity, u.unionUid) ?: u.uid
                    else u.uid
                }
                Ban2Hook.jumpToUserProfile(activity, realUid, false)
            }
        }
        RiskAction.VIEW_CHAT -> {
            scope.launch {
                val realUid = withContext(Dispatchers.IO) {
                    if (u.unionUid.isNotEmpty() && u.uid != u.unionUid) Ban2Hook.fetchRealUid(activity, u.unionUid) ?: u.uid
                    else u.uid
                }
                Ban2Hook.jumpToChatRoom(activity, realUid)
            }
        }
        RiskAction.BLOCK_AND_VIEW -> {
            Toast.makeText(ctx, "正在拉黑…", Toast.LENGTH_SHORT).show()
            Ban2Hook.operateBlacklist(activity, u.uid, true) { ok ->
                if (ok) {
                    Toast.makeText(ctx, "已拉黑, 跳转主页", Toast.LENGTH_SHORT).show()
                    Ban2Hook.jumpToUserProfile(activity, u.uid, false)
                } else Toast.makeText(ctx, "拉黑失败", Toast.LENGTH_SHORT).show()
            }
        }
        RiskAction.UNBLOCK -> {
            Toast.makeText(ctx, "正在解除…", Toast.LENGTH_SHORT).show()
            Ban2Hook.operateBlacklist(activity, u.uid, false) { ok ->
                Toast.makeText(ctx, if (ok) "已解除拉黑" else "操作失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  对话框: 添加用户
// ---------------------------------------------------------------------------
@Composable
private fun AddUserDialog(colors: LlhookColors, onConfirm: (String, String) -> Unit) {
    var uid by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onConfirm("", "") },
        confirmButton = { TextButton(onClick = { onConfirm(uid, name) }) { Text("添加", color = colors.accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { onConfirm("", "") }) { Text("取消", color = colors.subText) } },
        containerColor = colors.glass, titleContentColor = colors.text,
        title = { Text("添加用户到收藏") },
        text = {
            Column {
                OutlinedTextField(value = uid, onValueChange = { uid = it },
                    label = { Text("UID", color = colors.subText) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("备注名 (可空)", color = colors.subText) }, singleLine = true,
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
            }
        }
    )
}

@Composable
private fun RenameDialog(current: String, colors: LlhookColors, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onConfirm("") },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("保存", color = colors.accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { onConfirm("") }) { Text("取消", color = colors.subText) } },
        containerColor = colors.glass, titleContentColor = colors.text,
        title = { Text("修改备注") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) }
    )
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

private fun copy(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
