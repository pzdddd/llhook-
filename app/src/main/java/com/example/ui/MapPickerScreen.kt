package com.example.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import bxxd.hook.Config
import bxxd.hook.MapEngineController
import bxxd.hook.MapHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ============================================================================
//  地图精准选点 (Compose 玻璃拟态全屏页)
//
//  架构 (b): 宿主内就地弹出。宿主原生地图 SDK 的 MapView 是反射创建的传统 View,
//  不可用 Compose 重写 (就像 WebView), 故用 AndroidView 包裹 [MapEngineController.mapView];
//  其余所有 UI (搜索框 / 坐标叠层 / 收藏 / 经纬度面板 / 缩放 / 确认按钮) 全部 llhook Compose 风格。
//
//  数据逻辑复用 bxxd.hook: MapEngineController (三引擎反射), MapHelper (收藏 / 逆地理 / 搜索)。
// ============================================================================

private const val PREFS_NAME = "llhook_blued_local_v2"
private const val PREF_ENGINE = "map_engine_pref"
private const val DEFAULT_LAT = 39.9042
private const val DEFAULT_LNG = 116.4074

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerScreen(
    activity: Activity,
    radarLat: Double = 0.0,
    radarLng: Double = 0.0,
    onClose: () -> Unit
) {
    val isRadarMode = radarLat != 0.0 && radarLng != 0.0
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subTextColor = if (isDark) Color(0xFFA0AEC0) else Color(0xFF64748B)
    val accent = Color(0xFF4CAF50)
    val danger = Color(0xFFEF4444)
    val warning = Color(0xFFFF9800)
    val glassLight = Color(0xE6FFFFFF)
    val glassDark = Color(0xCC1E293B)
    val glass = if (isDark) glassDark else glassLight
    val glassStroke = if (isDark) Color(0x33FFFFFF) else Color(0x99FFFFFF)

    // 地图引擎控制器 (反射宿主 SDK)
    val controller = remember {
        bxxd.hook.FloatingUI.hostClassLoader?.let { MapEngineController(it) }
    }
    var engineReady by remember { mutableStateOf(false) }
    var engineName by remember { mutableStateOf("检测中…") }

    // 偏好
    val prefs = remember { ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var enginePref by remember { mutableStateOf(prefs.getInt(PREF_ENGINE, 0)) }

    // 中心坐标 (地图拖动 / 经纬度输入 / 搜索点击 都更新它)
    var centerLat by remember { mutableStateOf(if (isRadarMode) radarLat else Config.getCustomLat(ctx)) }
    var centerLng by remember { mutableStateOf(if (isRadarMode) radarLng else Config.getCustomLng(ctx)) }
    var placeName by remember { mutableStateOf("") }
    var geocodeBusy by remember { mutableStateOf(false) }

    // 搜索
    var searchQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<MapSuggestion>>(emptyList()) }

    // 经纬度面板 / 设置面板 / 收藏夹 显示开关
    var showCoordPanel by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showFavList by remember { mutableStateOf(false) }
    var favRefreshTrigger by remember { mutableStateOf(0) }
    // 收藏备注输入对话框
    var showFavNameDialog by remember { mutableStateOf(false) }

    // —— 地图引擎初始化 ——
    LaunchedEffect(controller, enginePref) {
        if (controller == null) {
            engineReady = false
            engineName = "无宿主 ClassLoader"
            return@LaunchedEffect
        }
        engineReady = withContext(Dispatchers.Main) { controller.detectAndInit(activity, enginePref) }
        engineName = controller.engineName
        if (engineReady) {
            // 初始定位
            delay(300)
            val (lat, lng) = if (isRadarMode) radarLat to radarLng else Config.getCustomLat(ctx) to Config.getCustomLng(ctx)
            controller.moveCameraTo(lat, lng, 18f)
            if (isRadarMode) controller.addMarker(radarLat, radarLng, "雷达锁定目标")
            centerLat = lat
            centerLng = lng
            // 初始逆地理编码: 获取真实地名并显示
            delay(200)
            val name = withContext(Dispatchers.IO) { MapHelper.reverseGeocode(lat, lng, Config.getApiKey(ctx)) }
            placeName = name
        }
    }

    // —— 逆地理防抖刷新 (与上次成功请求的坐标比较, 避免重复请求同一位置) ——
    var lastGeocodedLat by remember { mutableStateOf(Double.NaN) }
    var lastGeocodedLng by remember { mutableStateOf(Double.NaN) }
    fun refreshPlaceName(lat: Double, lng: Double) {
        if (geocodeBusy) return
        // 与上次已请求的坐标比较 (而非当前 centerLat, 因为 onMapGestureUp 已更新过它)
        if (String.format(Locale.US, "%.4f", lat) == String.format(Locale.US, "%.4f", lastGeocodedLat) &&
            String.format(Locale.US, "%.4f", lng) == String.format(Locale.US, "%.4f", lastGeocodedLng)
        ) return
        geocodeBusy = true
        lastGeocodedLat = lat; lastGeocodedLng = lng
        scope.launch(Dispatchers.IO) {
            val name = MapHelper.reverseGeocode(lat, lng, Config.getApiKey(ctx))
            withContext(Dispatchers.Main) {
                placeName = name
                geocodeBusy = false
            }
        }
    }

    // 地图手势抬起 → 刷新中心坐标 + 逆地理
    fun onMapGestureUp() {
        val c = controller?.getCenterCoordinate() ?: return
        centerLat = c.first
        centerLng = c.second
        refreshPlaceName(c.first, c.second)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF222222))
    ) {
        // ============ 地图层: AndroidView 包裹反射 MapView ============
        if (controller != null && controller.mapView != null) {
            AndroidView(
                factory = { context ->
                    val container = MapTouchFrameLayout(context).apply {
                        setOnTouchUpListener { onMapGestureUp() }
                        val mv = controller.mapView!!
                        addView(
                            mv,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                    container
                },
                modifier = Modifier.fillMaxSize()
            )
            // 中心十字准星 (非雷达模式显示)
            if (!isRadarMode && engineReady) {
                Text(
                    text = "📍",
                    fontSize = 36.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 18.dp)
                )
            }
        } else {
            // 盲狙模式: 无引擎, 仅经纬度
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("⚠️ 未检测到地图引擎", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("已进入盲狙模式, 请手动输入经纬度", color = Color.White, fontSize = 13.sp)
            }
        }

        // ============ 顶部搜索栏 (玻璃拟态, 雷达模式隐藏) ============
        if (!isRadarMode) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 返回
                    GlassIconButton(onClick = onClose, glass = glass, stroke = glassStroke, size = 42) {
                        Icon(Icons.Filled.ArrowBack, "关闭", tint = textColor)
                    }
                    // 搜索框
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            scope.launch {
                                delay(350) // 防抖
                                val q = it
                                if (q.isBlank()) { suggestions = emptyList(); return@launch }
                                val apiKey = Config.getApiKey(ctx)
                                if (apiKey.isEmpty()) { suggestions = emptyList(); return@launch }
                                val arr = withContext(Dispatchers.IO) {
                                    MapHelper.searchAMapSuggestion(q, apiKey)
                                }
                                suggestions = parseSuggestions(arr)
                            }
                        },
                        placeholder = { Text("搜索地名", color = subTextColor, fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = subTextColor) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    suggestions = emptyList()
                                }) { Icon(Icons.Filled.Close, "清除", tint = subTextColor) }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = glass,
                            unfocusedContainerColor = glass,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                            cursorColor = accent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    // 设置 (内核切换 / API Key)
                    GlassIconButton(onClick = { showSettingsPanel = true }, glass = glass, stroke = glassStroke, size = 42) {
                        Icon(Icons.Filled.Settings, "设置", tint = textColor)
                    }
                }

                // 搜索建议下拉
                AnimatedVisibility(
                    visible = suggestions.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = glass,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                            items(suggestions) { s ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            searchQuery = s.name
                                            suggestions = emptyList()
                                            centerLat = s.lat
                                            centerLng = s.lng
                                            controller?.moveCameraTo(s.lat, s.lng, 18f)
                                            placeName = s.address
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.LocationOn, null, tint = accent, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.name, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (s.address.isNotEmpty()) {
                                            Text(s.address, color = subTextColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                HorizontalDivider(color = glassStroke, thickness = 0.5.dp)
                            }
                        }
                    }
                }

                // 坐标显示卡片 (引擎模式, 点击复制) — 从原左上叠层迁入顶部 Column,
                // 经纬度面板展开时会随 Column 自然下推, 不再被遮挡
                if (engineReady) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = glass,
                        border = BorderStroke(1.dp, glassStroke.copy(alpha = 0.5f)),
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("coord", "$centerLat, $centerLng"))
                                Toast.makeText(ctx, "坐标已复制", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.LocationOn, null, tint = accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                // 地名主显示 (大字, 醒目)
                                Text(
                                    placeName.ifEmpty { if (geocodeBusy) "名称获取中…" else "未命名位置 (移动地图后刷新)" },
                                    color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                // 坐标次要 (小字 等宽)
                                Text(
                                    "${String.format(Locale.US, "%.6f", centerLat)}, ${String.format(Locale.US, "%.6f", centerLng)}",
                                    color = subTextColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // 经纬度输入切换按钮
                GlassButton(
                    onClick = { showCoordPanel = !showCoordPanel },
                    glass = glass, stroke = glassStroke, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        if (showCoordPanel) "▼ 收起经纬度面板" else "📍 输入经纬度定位",
                        color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                AnimatedVisibility(showCoordPanel) {
                    CoordInputPanel(
                        glass = glass, stroke = glassStroke, textColor = textColor, subTextColor = subTextColor, accent = accent,
                        onLocate = { lat, lng ->
                            centerLat = lat; centerLng = lng
                            controller?.moveCameraTo(lat, lng, 18f)
                            refreshPlaceName(lat, lng)
                        }
                    )
                }
            }
        } else {
            // 雷达模式: 仅返回按钮
            GlassIconButton(
                onClick = onClose, glass = glass, stroke = glassStroke, size = 42,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
            ) { Icon(Icons.Filled.ArrowBack, "关闭", tint = textColor) }
        }

        // ============ 右下角缩放控件 (小尺寸, llhook 玻璃风格) ============
        if (engineReady) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 168.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GlassIconButton(
                    onClick = { controller?.zoomStep(true) },
                    glass = glass, stroke = glassStroke, size = 36,
                    contentDescription = "放大"
                ) {
                    Icon(Icons.Filled.Add, null, tint = accent, modifier = Modifier.size(20.dp))
                }
                GlassIconButton(
                    onClick = { controller?.zoomStep(false) },
                    glass = glass, stroke = glassStroke, size = 36,
                    contentDescription = "缩小"
                ) {
                    Icon(Icons.Filled.Minimize, null, tint = textColor, modifier = Modifier.size(20.dp))
                }
            }
        }

        // ============ 底部收藏按钮行 (非雷达模式, llhook 玻璃风格) ============
        if (!isRadarMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassButton(
                    onClick = { showFavNameDialog = true },
                    glass = glass, stroke = glassStroke, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Filled.Bookmark, null, tint = warning, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("收藏当前", color = warning, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                GlassButton(
                    onClick = { showFavList = true },
                    glass = glass, stroke = glassStroke, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Filled.BookmarkBorder, null, tint = accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("坐标收藏夹", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ============ 雷达模式: 左上角坐标+地名显示卡片 ============
        if (isRadarMode) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = glass,
                border = BorderStroke(1.dp, glassStroke.copy(alpha = 0.5f)),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.LocationOn, null, tint = danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        // 地名主显示
                        Text(
                            placeName.ifEmpty { if (geocodeBusy) "名称获取中…" else "雷达锁定目标 (正在获取地名…)" },
                            color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        // 坐标次要 (等宽)
                        Text(
                            String.format(Locale.US, "%.6f, %.6f", radarLat, radarLng),
                            color = subTextColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // ============ 底部主操作按钮 ============
        if (isRadarMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 定位到此 (主操作: 白底黑字 + 绿边框)
                MapActionButton(
                    text = "定位到此",
                    onClick = {
                        saveLocation(ctx, radarLat, radarLng)
                        Toast.makeText(ctx, "已锁定至雷达目标！", Toast.LENGTH_LONG).show()
                        onClose()
                    },
                    style = MapButtonStyle.PRIMARY,
                    modifier = Modifier.weight(1f)
                )
                // 关闭雷达 (危险: 白底黑字 + 红边框)
                MapActionButton(
                    text = "关闭雷达",
                    onClick = onClose,
                    style = MapButtonStyle.DANGER,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            MapActionButton(
                text = "确认并保存位置",
                onClick = {
                    saveLocation(ctx, centerLat, centerLng)
                    Toast.makeText(ctx, "已定位", Toast.LENGTH_LONG).show()
                    onClose()
                },
                style = MapButtonStyle.PRIMARY,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 30.dp)
                    .fillMaxWidth()
            )
        }

        // ============ 设置面板 (内核切换 / API Key) ============
        if (showSettingsPanel) {
            MapSettingsSheet(
                isDark = isDark, glass = glass, stroke = glassStroke, textColor = textColor, subTextColor = subTextColor,
                enginePref = enginePref,
                apiKey = Config.getApiKey(ctx),
                onEngineChange = { which ->
                    enginePref = which
                    prefs.edit().putInt(PREF_ENGINE, which).apply()
                },
                onApiKeyChange = { key ->
                    Config.setApiKey(key, ctx)
                    prefs.edit().putString("amap_api_key", key).apply()
                    // 同步给模块层
                    ctx.sendBroadcast(Intent("bxxd.hook.MAIN_SYNC_PUSH").apply {
                        putExtra("key", "amap_api_key"); putExtra("value", key)
                    })
                },
                onDismiss = {
                    showSettingsPanel = false
                    if (enginePref != prefs.getInt(PREF_ENGINE, 0)) {
                        Toast.makeText(ctx, "内核已更改, 关闭后重新打开生效", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        // ============ 收藏夹列表 ============
        if (showFavList) {
            FavoriteListSheet(
                ctx = ctx,
                refreshTrigger = favRefreshTrigger,
                isDark = isDark, glass = glass, stroke = glassStroke, textColor = textColor, subTextColor = subTextColor,
                onSelect = { lat, lng ->
                    centerLat = lat; centerLng = lng
                    controller?.moveCameraTo(lat, lng, 18f)
                    refreshPlaceName(lat, lng)
                    showFavList = false
                },
                onDismiss = { showFavList = false }
            )
        }

        // ============ 收藏备注输入对话框 ============
        if (showFavNameDialog) {
            FavoriteNameDialog(
                glass = glass, stroke = glassStroke, textColor = textColor, subTextColor = subTextColor, accent = accent,
                defaultName = placeName.ifEmpty { String.format(Locale.US, "%.4f, %.4f", centerLat, centerLng) },
                onConfirm = { name ->
                    MapHelper.addFavorite(ctx, name.ifBlank { "未命名位置" }, centerLat, centerLng)
                    Toast.makeText(ctx, "已收藏: $name", Toast.LENGTH_SHORT).show()
                    favRefreshTrigger++
                    showFavNameDialog = false
                },
                onDismiss = { showFavNameDialog = false }
            )
        }
    }

    // 引擎销毁
    DisposableEffect(controller) {
        onDispose {
            controller?.onPause()
            controller?.onDestroy()
        }
    }
}

// ---------------------------------------------------------------------------
//  数据保存: 写 Config + 离线文件 + 广播同步
// ---------------------------------------------------------------------------
private fun saveLocation(ctx: Context, lat: Double, lng: Double) {
    Config.setCustomLocation(lat, lng, ctx)
    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putString("custom_lat", lat.toString())
        .putString("custom_lng", lng.toString())
        .putLong("master_timestamp", System.currentTimeMillis())
        .apply()
    MapHelper.pushOfflineLocation(lat, lng)
    ctx.sendBroadcast(Intent("bxxd.hook.MAIN_SYNC_PUSH").apply {
        putExtra("key", "custom_lat"); putExtra("value", lat.toString())
    })
    ctx.sendBroadcast(Intent("bxxd.hook.MAIN_SYNC_PUSH").apply {
        putExtra("key", "custom_lng"); putExtra("value", lng.toString())
    })
}

// ---------------------------------------------------------------------------
//  经纬度输入面板 (含粘贴智能识别)
// ---------------------------------------------------------------------------
@Composable
private fun CoordInputPanel(
    glass: Color, stroke: Color, textColor: Color, subTextColor: Color, accent: Color,
    onLocate: (Double, Double) -> Unit
) {
    val ctx = LocalContext.current
    var latText by remember { mutableStateOf("") }
    var lngText by remember { mutableStateOf("") }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = glass,
        border = BorderStroke(1.dp, stroke.copy(alpha = 0.5f)),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latText, onValueChange = { latText = it },
                    label = { Text("纬度", color = subTextColor, fontSize = 12.sp) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                        focusedBorderColor = accent, unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = textColor, unfocusedTextColor = textColor, cursorColor = accent
                    ),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lngText, onValueChange = { lngText = it },
                    label = { Text("经度", color = subTextColor, fontSize = 12.sp) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                        focusedBorderColor = accent, unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = textColor, unfocusedTextColor = textColor, cursorColor = accent
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 定位 (主操作: 绿边框)
                MapActionButton(
                    text = "定位",
                    onClick = {
                        val lat = latText.toDoubleOrNull()
                        val lng = lngText.toDoubleOrNull()
                        if (lat != null && lng != null && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180) {
                            onLocate(lat, lng)
                        } else {
                            Toast.makeText(ctx, "请输入有效经纬度", Toast.LENGTH_SHORT).show()
                        }
                    },
                    style = MapButtonStyle.PRIMARY,
                    modifier = Modifier.weight(1f)
                )
                // 粘贴 (智能识别, 普通样式)
                MapActionButton(
                    text = "粘贴",
                    onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                        val nums = Regex("(-?\\d+\\.\\d+)").findAll(text).toList()
                        if (nums.size >= 2) {
                            var n1 = nums[0].value.toDouble()
                            var n2 = nums[1].value.toDouble()
                            var la = n1; var ln = n2
                            if (kotlin.math.abs(n1) > 90.0 && kotlin.math.abs(n2) <= 90.0) { la = n2; ln = n1 }
                            latText = la.toString(); lngText = ln.toString()
                            onLocate(la, ln)
                            Toast.makeText(ctx, "坐标已粘贴并锁定", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, "剪贴板未找到有效经纬度", Toast.LENGTH_SHORT).show()
                        }
                    },
                    style = MapButtonStyle.NORMAL,
                    leadingIcon = Icons.Filled.ContentPaste,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  设置面板: 内核切换 + API Key
// ---------------------------------------------------------------------------
@Composable
private fun MapSettingsSheet(
    isDark: Boolean, glass: Color, stroke: Color, textColor: Color, subTextColor: Color,
    enginePref: Int,
    apiKey: String,
    onEngineChange: (Int) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var key by remember(apiKey) { mutableStateOf(apiKey) }
    val engineOptions = listOf("自动检测 (推荐)" to 0, "强制腾讯地图" to 1, "强制高德地图" to 2, "强制百度地图" to 3)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = glass,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("地图设置", color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "关闭", tint = textColor) }
                }
                Spacer(Modifier.height(12.dp))
                Text("地图内核", color = subTextColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                engineOptions.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEngineChange(value) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = enginePref == value, onClick = { onEngineChange(value) }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF4CAF50)))
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = textColor, fontSize = 14.sp)
                    }
                }
                HorizontalDivider(color = stroke, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
                Text("高德 Web 服务 Key (地点联想 / 逆地理)", color = subTextColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    placeholder = { Text("粘贴高德 Web 服务 Key", color = subTextColor, fontSize = 13.sp) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                        focusedBorderColor = Color(0xFF4CAF50), unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = textColor, unfocusedTextColor = textColor, cursorColor = Color(0xFF4CAF50)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "前往 lbs.amap.com 免费申请【Web服务】类型 Key",
                    color = subTextColor, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(14.dp))
                MapActionButton(
                    text = "保存",
                    onClick = { onApiKeyChange(key.trim()); onDismiss() },
                    style = MapButtonStyle.PRIMARY,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  收藏备注名称输入对话框 (自定义备注名 + 预览坐标)
// ---------------------------------------------------------------------------
@Composable
private fun FavoriteNameDialog(
    glass: Color, stroke: Color, textColor: Color, subTextColor: Color, accent: Color,
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = glass,
            border = BorderStroke(1.dp, stroke.copy(alpha = 0.5f)),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bookmark, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加收藏", color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "关闭", tint = textColor) }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("备注名称", color = subTextColor, fontSize = 12.sp) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                        focusedBorderColor = accent, unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = textColor, unfocusedTextColor = textColor, cursorColor = accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) { Text("取消", color = subTextColor) }
                    Button(
                        onClick = { onConfirm(name.trim()) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) { Text("收藏", color = Color.White, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  收藏夹列表
// ---------------------------------------------------------------------------
@Composable
private fun FavoriteListSheet(
    ctx: Context,
    refreshTrigger: Int,
    isDark: Boolean, glass: Color, stroke: Color, textColor: Color, subTextColor: Color,
    onSelect: (Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    val favs by remember(refreshTrigger) {
        mutableStateOf(parseFavs(MapHelper.getFavArray(ctx)))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = glass,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("坐标收藏夹", color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "关闭", tint = textColor) }
                }
                if (favs.isEmpty()) {
                    Text(
                        "暂无收藏, 点底部「收藏当前」添加",
                        color = subTextColor, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(favs) { fav ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(fav.lat, fav.lng) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.LocationOn, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(fav.name, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("${fav.lat}, ${fav.lng}", color = subTextColor, fontSize = 11.sp)
                                }
                                IconButton(onClick = {
                                    // 删除: 按 name+坐标匹配
                                    val arr = MapHelper.getFavArray(ctx)
                                    for (i in 0 until arr.length()) {
                                        val o = arr.getJSONObject(i)
                                        if (o.getString("name") == fav.name &&
                                            kotlin.math.abs(o.getDouble("lat") - fav.lat) < 1e-6 &&
                                            kotlin.math.abs(o.getDouble("lng") - fav.lng) < 1e-6
                                        ) {
                                            MapHelper.removeFavorite(ctx, i); break
                                        }
                                    }
                                    onDismiss() // 简单刷新: 关闭重开
                                }) { Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp)) }
                            }
                            HorizontalDivider(color = stroke, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  通用玻璃拟态组件 → 已提取到 GlassComponents.kt (GlassIconButton / GlassButton / llhookColorScheme)
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
//  数据模型 / 解析
// ---------------------------------------------------------------------------
private data class MapSuggestion(val name: String, val address: String, val lat: Double, val lng: Double)
private data class FavItem(val name: String, val lat: Double, val lng: Double)

private fun parseFavs(arr: JSONArray): List<FavItem> {
    val out = mutableListOf<FavItem>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(FavItem(o.optString("name", "未命名"), o.optDouble("lat", 0.0), o.optDouble("lng", 0.0)))
    }
    return out
}

// ---------------------------------------------------------------------------
//  地图界面统一按钮: 白底黑字 + 可选边框颜色区分操作类型 (llhook Compose 风格)
//  - style = PRIMARY:   accent 绿边框 (主操作: 确认/定位)
//  - style = DANGER:    danger 红边框 (危险: 关闭雷达)
//  - style = NORMAL:    灰色细边框 (普通: 粘贴/保存等)
// ---------------------------------------------------------------------------
private enum class MapButtonStyle { PRIMARY, DANGER, NORMAL }

@Composable
private fun MapActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MapButtonStyle = MapButtonStyle.NORMAL,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true
) {
    val borderColor = when (style) {
        MapButtonStyle.PRIMARY -> Color(0xFF4CAF50)
        MapButtonStyle.DANGER -> Color(0xFFEF4444)
        MapButtonStyle.NORMAL -> Color(0xFFCBD5E1)
    }
    val alpha = if (enabled) 1f else 0.5f
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = BorderStroke(1.5.dp, borderColor.copy(alpha = alpha)),
        shadowElevation = if (enabled) 3.dp else 0.dp,
        modifier = modifier
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 解析高德 inputtips JSONArray → List<MapSuggestion> (过滤无坐标项)。 */
private fun parseSuggestions(arr: JSONArray): List<MapSuggestion> {
    val out = mutableListOf<MapSuggestion>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val loc = o.optString("location", "")
        if (!loc.contains(",")) continue
        val parts = loc.split(",")
        if (parts.size != 2) continue
        val lng = parts[0].toDoubleOrNull() ?: continue
        val lat = parts[1].toDoubleOrNull() ?: continue
        if (lat == 0.0 || lng == 0.0) continue
        val address = (o.optString("district", "") + o.optString("address", "")).replace("[]", "").trim()
        out.add(MapSuggestion(o.optString("name", ""), address, lat, lng))
    }
    return out
}

/**
 * 宿主进程里反射创建的 MapView 通常被 SDK 包了一层自己的触摸处理。
 * 这个 FrameLayout 拦截手势抬起事件, 通知 Compose 层刷新中心坐标。
 * (三引擎通用: View 级监听, 不依赖引擎 SDK 接口)
 */
private class MapTouchFrameLayout(context: Context) : FrameLayout(context) {
    private var touchUpListener: (() -> Unit)? = null
    fun setOnTouchUpListener(l: () -> Unit) { touchUpListener = l }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val r = super.dispatchTouchEvent(ev)
        if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
            touchUpListener?.invoke()
        }
        return r
    }
}
