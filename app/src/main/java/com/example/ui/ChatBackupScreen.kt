package com.example.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.ChatBackupManager
import bxxd.hook.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
//  聊天备份与恢复 (Compose 玻璃拟态全屏页)
//
//  架构 (b): 宿主内就地弹出 (showHostComposeScreen)。
//  逻辑全用 bxxd.hook.ChatBackupManager:
//    - backup(ctx): 打包 blued2015.db (+WAL三件套) + 元信息 → zip
//    - restore(ctx, zip): 解压覆盖数据库
//    - listBackups / deleteBackup / readMeta / checkCompatibility / formatSize
//    - getBackupRoot: 自定义目录优先, 默认 Download/bluedbackups
// ============================================================================

@Composable
fun ChatBackupScreen(activity: Activity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()

    var backups by remember { mutableStateOf<List<File>>(emptyList()) }
    var backupRoot by remember { mutableStateOf<File?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var customDir by remember { mutableStateOf(Config.getBackupDir(ctx)) }
    var showDirDialog by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<Pair<File, ChatBackupManager.CompatibilityResult>?>(null) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }

    // 加载备份列表
    LaunchedEffect(refreshKey) {
        loading = true
        withContext(Dispatchers.IO) {
            backupRoot = ChatBackupManager.getBackupRoot(ctx)
            backups = ChatBackupManager.listBackups(ctx)
        }
        loading = false
    }

    fun doBackup() {
        scope.launch {
            busy = true; resultMsg = null
            val r = withContext(Dispatchers.IO) { ChatBackupManager.backup(ctx) }
            busy = false
            refreshKey++
            resultMsg = if (r.success) "✅ ${r.msg}\n文件: ${r.file?.absolutePath}"
                        else "❌ 备份失败: ${r.msg}"
        }
    }

    fun doRestore(zip: File) {
        scope.launch {
            busy = true; resultMsg = null
            val n = withContext(Dispatchers.IO) { ChatBackupManager.restore(ctx, zip) }
            busy = false
            resultMsg = if (n >= 0) "✅ 恢复成功, 还原 $n 个数据库文件\n请重启 Blued 生效"
                        else "❌ 恢复失败, 请检查文件完整性"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                llhookBackgroundBrush(isDark, listOf(Color(0xFFEFF6FF), Color(0xFFDBEAFE)))
            )
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            // ============ 顶栏 (统一组件: 状态栏内边距 / 无障碍 / 返回按钮 / 文字对比度) ============
            LlhookTopBar(
                title = "聊天备份与恢复",
                subtitle = "${backups.size} 个备份 · ${backupRoot?.absolutePath ?: ""}",
                onBack = onClose,
                glass = colors.glass,
                stroke = colors.glassStroke,
                textColor = colors.text,
                subTextColor = colors.subText
            )

            // ============ 操作按钮区 ============
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { if (!busy) doBackup() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Icon(Icons.Filled.CloudUpload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("立即备份", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { showDirDialog = true },
                    shape = RoundedCornerShape(14.dp),
                    enabled = !busy,
                    modifier = Modifier.height(50.dp)
                ) {
                    Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp), tint = colors.text)
                    Spacer(Modifier.width(6.dp))
                    Text("目录", color = colors.text)
                }
            }

            // ============ 结果提示 ============
            resultMsg?.let { msg ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (msg.startsWith("✅")) Color(0xCC22C55E) else Color(0xCCEF4444),
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                ) {
                    Text(msg, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp))
                }
            }

            // ============ 备份列表 ============
            if (loading) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            } else if (backups.isEmpty()) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💾", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("暂无备份", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text("点击「立即备份」打包当前聊天数据库\n(WAL 三件套 + 登录账号元信息)",
                            color = colors.subText, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 16.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(backups, key = { _, zip -> zip.absolutePath }) { index, zip ->
                        LlhookAnimatedItem(index = index) {
                            BackupCard(
                                zip = zip, ctx = ctx, colors = colors,
                                onRestore = {
                                    confirmRestore = zip to ChatBackupManager.checkCompatibility(
                                        ChatBackupManager.readMeta(zip)?.sourcePackage,
                                        Config.currentBluedPackage
                                    )
                                },
                                onDelete = { confirmDelete = zip }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(60.dp)) }
                }
            }
        }

        // ============ Busy 遮罩 ============
        if (busy) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(shape = RoundedCornerShape(16.dp), color = colors.glass) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = colors.accent)
                        Spacer(Modifier.height(10.dp))
                        Text("处理中…", color = colors.text, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    // ============ 目录设置对话框 ============
    if (showDirDialog) {
        DirSettingDialog(customDir, colors) { newDir ->
            if (newDir != null) {
                Config.setBackupDir(newDir, ctx)
                customDir = newDir
                refreshKey++
                Toast.makeText(ctx, "目录已更新", Toast.LENGTH_SHORT).show()
            }
            showDirDialog = false
        }
    }

    // ============ 恢复确认 (含兼容性) ============
    confirmRestore?.let { (zip, compat) ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            confirmButton = {
                TextButton(onClick = {
                    val allow = compat.level != ChatBackupManager.CompatLevel.DANGER
                    confirmRestore = null
                    if (allow) doRestore(zip)
                    else Toast.makeText(ctx, "危险: 跨版本恢复已阻止", Toast.LENGTH_LONG).show()
                }) {
                    Text(if (compat.level == ChatBackupManager.CompatLevel.DANGER) "仍要恢复" else "恢复",
                        color = if (compat.level == ChatBackupManager.CompatLevel.DANGER) colors.danger else colors.accent,
                        fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("取消", color = colors.subText) } },
            containerColor = colors.glass, titleContentColor = colors.text,
            title = { Text("恢复聊天数据") },
            text = {
                Column {
                    val (bgColor, _) = when (compat.level) {
                        ChatBackupManager.CompatLevel.SAFE -> Color(0xFF22C55E) to "✅"
                        ChatBackupManager.CompatLevel.WARN -> Color(0xFFFF9800) to "⚠️"
                        ChatBackupManager.CompatLevel.DANGER -> Color(0xFFEF4444) to "🚫"
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = bgColor.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(compat.message, color = colors.text, fontSize = 12.sp, lineHeight = 16.sp,
                            modifier = Modifier.padding(10.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("将从 ${zip.name} 恢复数据库，覆盖当前数据。建议先备份当前数据。",
                        color = colors.subText, fontSize = 12.sp)
                }
            }
        )
    }

    // ============ 删除确认 ============
    confirmDelete?.let { zip ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = { TextButton(onClick = {
                ChatBackupManager.deleteBackup(zip); confirmDelete = null; refreshKey++
                Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
            }) { Text("删除", color = colors.danger, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消", color = colors.subText) } },
            containerColor = colors.glass, titleContentColor = colors.text,
            title = { Text("删除备份") },
            text = { Text("确定删除 ${zip.name}？此操作不可恢复。", color = colors.subText) }
        )
    }
}

// ---------------------------------------------------------------------------
//  单个备份卡片
// ---------------------------------------------------------------------------
@Composable
private fun BackupCard(
    zip: File, ctx: android.content.Context, colors: LlhookColors,
    onRestore: () -> Unit, onDelete: () -> Unit
) {
    val meta = remember(zip) { ChatBackupManager.readMeta(zip) }
    val compat = remember(zip) {
        ChatBackupManager.checkCompatibility(meta?.sourcePackage, Config.currentBluedPackage)
    }
    val compatColor = when (compat.level) {
        ChatBackupManager.CompatLevel.SAFE -> Color(0xFF22C55E)
        ChatBackupManager.CompatLevel.WARN -> Color(0xFFFF9800)
        ChatBackupManager.CompatLevel.DANGER -> Color(0xFFEF4444)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.glass,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassStroke.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF3B82F6).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Archive, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(zip.name, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                    Text("${ChatBackupManager.formatSize(zip.length())} · " +
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(zip.lastModified())),
                        color = colors.subText, fontSize = 11.sp)
                }
                // 兼容性指示
                Surface(shape = RoundedCornerShape(4.dp), color = compatColor.copy(alpha = 0.15f)) {
                    Text(compat.level.name, color = compatColor, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            // 元信息
            if (meta != null) {
                Spacer(Modifier.height(6.dp))
                Text(buildString {
                    meta.sourcePackage?.let { append("来源: ${it.substringAfterLast('.')}") }
                    meta.sourceUid?.let { if (it.isNotBlank()) append(" · UID $it") }
                    if (meta.dbUserVersion > 0) append(" · DB v${meta.dbUserVersion}")
                }, color = colors.subText.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRestore, shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Restore, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("恢复", color = Color.White, fontSize = 13.sp)
                }
                OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.semantics { contentDescription = "删除此备份" }) {
                    Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(16.dp), tint = colors.danger)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  目录设置对话框
// ---------------------------------------------------------------------------
@Composable
private fun DirSettingDialog(current: String, colors: LlhookColors, onConfirm: (String?) -> Unit) {
    var dir by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onConfirm(null) },
        confirmButton = { TextButton(onClick = { onConfirm(dir.trim()) }) {
            Text("保存", color = colors.accent, fontWeight = FontWeight.Bold)
        } },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm("") }) { Text("恢复默认", color = colors.subText) }
                TextButton(onClick = { onConfirm(null) }) { Text("取消", color = colors.subText) }
            }
        },
        containerColor = colors.glass, titleContentColor = colors.text,
        title = { Text("备份目录") },
        text = {
            Column {
                Text("留空 = 使用默认 Download/bluedbackups\n自定义路径需确保可写 (SAF 目录请填实际路径)",
                    color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = dir, onValueChange = { dir = it }, singleLine = true,
                    placeholder = { Text("如 /storage/emulated/0/Download/bluedbackups", color = colors.subText, fontSize = 12.sp) },
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}
