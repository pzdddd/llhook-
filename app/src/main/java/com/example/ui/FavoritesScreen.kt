package com.example.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.Config
import bxxd.hook.MapHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
//  坐标收藏夹管理 (Compose 玻璃拟态全屏页)
//
//  架构 (b): 宿主内就地弹出 (showHostComposeScreen)。
//  数据源: bxxd.hook.MapHelper (SharedPreferences("llhook_blued_local_v2") → "fav_locations")
//    - getFavArray / saveFavArray / addFavorite / removeFavorite
//
//  功能: 列表 / 搜索 / 手动添加(经纬度) / 重命名 / 删除 / 一键设为虚拟定位 /
//       一键导航(打开地图) / 导出导入(JSON 剪贴板)
// ============================================================================

private data class FavLocation(val name: String, val lat: Double, val lng: Double)

@Composable
fun FavoritesScreen(activity: Activity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val colors = llhookColorScheme()

    var favs by remember { mutableStateOf(parseFavLocations(MapHelper.getFavArray(ctx))) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Int>(-1) }   // 索引
    var exportText by remember { mutableStateOf<String?>(null) }

    fun reload() { favs = parseFavLocations(MapHelper.getFavArray(ctx)) }

    fun persist(newList: List<FavLocation>) {
        val arr = JSONArray()
        newList.forEach { arr.put(JSONObject().apply { put("name", it.name); put("lat", it.lat); put("lng", it.lng) }) }
        MapHelper.saveFavArray(ctx, arr)
        favs = newList
    }

    val filtered = remember(favs, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) favs
        else favs.filter { it.name.contains(q, true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(llhookBackgroundBrush(isDark, listOf(Color(0xFFECFEFF), Color(0xFFCFFAFE))))
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            // ============ 顶栏 ============
            LlhookTopBar(
                title = "坐标收藏夹",
                subtitle = "${favs.size} 个收藏 · 可用于虚拟定位快速切换",
                onBack = onClose,
                glass = colors.glass, stroke = colors.glassStroke,
                textColor = colors.text, subTextColor = colors.subText
            ) {
                GlassIconButton(onClick = { showAddDialog = true }, glass = colors.glass, stroke = colors.glassStroke,
                    size = 40, contentDescription = "手动添加收藏") {
                    Icon(Icons.Filled.Add, null, tint = colors.accent)
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(onClick = {
                    val arr = JSONArray()
                    favs.forEach { arr.put(JSONObject().apply { put("name", it.name); put("lat", it.lat); put("lng", it.lng) }) }
                    val json = arr.toString(2)
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("llhook_favorites", json))
                    Toast.makeText(ctx, "已导出 ${favs.size} 条到剪贴板", Toast.LENGTH_SHORT).show()
                }, glass = colors.glass, stroke = colors.glassStroke, size = 40, contentDescription = "导出 JSON") {
                    Icon(Icons.Filled.IosShare, null, tint = colors.text)
                }
            }

            // ============ 搜索框 ============
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                placeholder = { Text("搜索收藏名称", color = colors.subText, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = colors.subText) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        Icon(Icons.Filled.Close, "清除",
                            modifier = Modifier.clickable { searchQuery = "" }, tint = colors.subText)
                    }
                },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text, unfocusedTextColor = colors.text,
                    cursorColor = colors.accent, focusedBorderColor = colors.accent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.06f)
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ============ 列表 ============
            if (favs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📍", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("暂无收藏", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text("点右上角 + 手动添加, 或在地图选点页「收藏当前」",
                            color = colors.subText, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("无匹配结果", color = colors.subText, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(filtered, key = { _, it -> "${it.name}_${it.lat}_${it.lng}" }) { idxInFiltered, item ->
                        // 找到在原列表中的真实索引 (删除/重命名用)
                        val realIdx = favs.indexOf(item)
                        FavCard(
                            item = item, colors = colors,
                            onUse = {
                                // 设为虚拟定位
                                Config.setCustomLocation(item.lat, item.lng, ctx)
                                Toast.makeText(ctx, "✅ 虚拟定位已设为「${item.name}」\n${item.lat}, ${item.lng}", Toast.LENGTH_LONG).show()
                            },
                            onNav = {
                                // 打开地图导航
                                bxxd.hook.MapOverlay.showMap(activity)
                                Toast.makeText(ctx, "已打开地图, 点「前往此处」导航", Toast.LENGTH_SHORT).show()
                            },
                            onRename = { renameTarget = realIdx },
                            onDelete = {
                                persist(favs.toMutableList().also { it.removeAt(realIdx) })
                                Toast.makeText(ctx, "已删除「${item.name}」", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // ============ 添加对话框 ============
    if (showAddDialog) {
        FavoriteEditDialog(ctx, null, colors, title = "添加收藏") { name, lat, lng ->
            if (name.isNotBlank() && lat != null && lng != null) {
                MapHelper.addFavorite(ctx, name.trim(), lat, lng)
                reload()
                Toast.makeText(ctx, "已添加「$name」", Toast.LENGTH_SHORT).show()
            }
            showAddDialog = false
        }
    }

    // ============ 重命名对话框 ============
    if (renameTarget >= 0 && renameTarget < favs.size) {
        val target = favs[renameTarget]
        FavoriteEditDialog(ctx, target, colors, title = "编辑「${target.name}」") { name, _, _ ->
            if (name.isNotBlank()) {
                val newList = favs.toMutableList()
                newList[renameTarget] = target.copy(name = name.trim())
                persist(newList)
                Toast.makeText(ctx, "已更新", Toast.LENGTH_SHORT).show()
            }
            renameTarget = -1
        }
    }
}

// ---------------------------------------------------------------------------
//  单条收藏卡片
// ---------------------------------------------------------------------------
@Composable
private fun FavCard(
    item: FavLocation, colors: LlhookColors,
    onUse: () -> Unit, onNav: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.glass,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassStroke.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Place, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${String.format("%.5f", item.lat)}, ${String.format("%.5f", item.lng)}",
                        color = colors.subText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onUse, shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.MyLocation, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("设为定位", color = Color.White, fontSize = 12.sp)
                }
                OutlinedButton(onClick = onNav, shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Map, null, modifier = Modifier.size(14.dp), tint = Color(0xFF3B82F6))
                    Spacer(Modifier.width(4.dp))
                    Text("导航", color = colors.text, fontSize = 12.sp)
                }
                OutlinedButton(onClick = onRename, shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Filled.Edit, "重命名", modifier = Modifier.size(14.dp), tint = colors.warning)
                }
                OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(14.dp), tint = colors.danger)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  添加/编辑对话框
// ---------------------------------------------------------------------------
@Composable
private fun FavoriteEditDialog(
    ctx: android.content.Context,
    existing: FavLocation?, colors: LlhookColors, title: String,
    onConfirm: (name: String, lat: Double?, lng: Double?) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var latStr by remember { mutableStateOf(existing?.lat?.toString() ?: "") }
    var lngStr by remember { mutableStateOf(existing?.lng?.toString() ?: "") }
    var isImporting by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { onConfirm("", null, null) },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, latStr.toDoubleOrNull(), lngStr.toDoubleOrNull()) }) {
                Text("保存", color = colors.accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = { onConfirm("", null, null) }) { Text("取消", color = colors.subText) } },
        containerColor = colors.glass, titleContentColor = colors.text,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称", color = colors.subText, fontSize = 11.sp) },
                    singleLine = true, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                if (existing == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = latStr, onValueChange = { latStr = it },
                            label = { Text("纬度", color = colors.subText, fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = lngStr, onValueChange = { lngStr = it },
                            label = { Text("经度", color = colors.subText, fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // 批量导入
                    TextButton(onClick = { isImporting = !isImporting }) {
                        Text(if (isImporting) "▲ 收起导入" else "▼ 从剪贴板批量导入 JSON",
                            color = colors.accent, fontSize = 12.sp)
                    }
                    if (isImporting) {
                        OutlinedTextField(
                            value = importText, onValueChange = { importText = it },
                            label = { Text("[{\"name\":\"x\",\"lat\":0,\"lng\":0}, ...]", color = colors.subText, fontSize = 10.sp) },
                            shape = RoundedCornerShape(8.dp), minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                importText = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                Toast.makeText(ctx, "已粘贴剪贴板内容", Toast.LENGTH_SHORT).show()
                            }) { Text("粘贴", color = colors.subText, fontSize = 12.sp) }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                val imported = parseFavLocations(runCatching { JSONArray(importText.trim()) }.getOrNull() ?: JSONArray())
                                if (imported.isEmpty()) {
                                    Toast.makeText(ctx, "解析失败或为空", Toast.LENGTH_SHORT).show()
                                } else {
                                    val arr = MapHelper.getFavArray(ctx)
                                    imported.forEach { arr.put(JSONObject().apply { put("name", it.name); put("lat", it.lat); put("lng", it.lng) }) }
                                    MapHelper.saveFavArray(ctx, arr)
                                    Toast.makeText(ctx, "✅ 导入 ${imported.size} 条", Toast.LENGTH_SHORT).show()
                                    onConfirm("", null, null) // 触发外层 reload
                                }
                            }) { Text("导入", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    )
}

// ---------------------------------------------------------------------------
//  工具
// ---------------------------------------------------------------------------

private fun parseFavLocations(arr: JSONArray): List<FavLocation> = buildList {
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val lat = o.optDouble("lat", Double.NaN)
        val lng = o.optDouble("lng", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) continue
        add(FavLocation(o.optString("name", "未命名"), lat, lng))
    }
}
