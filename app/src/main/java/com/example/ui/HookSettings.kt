package com.example.ui

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Composable
fun rememberBooleanPreference(prefs: SharedPreferences, key: String, defaultValue: Boolean): MutableState<Boolean> {
    val state = remember { mutableStateOf(prefs.getBoolean(key, defaultValue)) }
    return remember {
        object : MutableState<Boolean> {
            override var value: Boolean
                get() = state.value
                set(value) {
                    state.value = value
                    prefs.edit().putBoolean(key, value).apply()
                }
            override fun component1() = state.value
            override fun component2(): (Boolean) -> Unit = { this.value = it }
        }
    }
}

@Composable
fun rememberStringPreference(prefs: SharedPreferences, key: String, defaultValue: String): MutableState<String> {
    val state = remember { mutableStateOf(prefs.getString(key, defaultValue) ?: defaultValue) }
    return remember {
        object : MutableState<String> {
            override var value: String
                get() = state.value
                set(value) {
                    state.value = value
                    prefs.edit().putString(key, value).apply()
                }
            override fun component1() = state.value
            override fun component2(): (String) -> Unit = { this.value = it }
        }
    }
}

enum class Screen {
    SETTINGS,
    MAP_PICKER
}

@Composable
fun MainScreen(prefs: SharedPreferences) {
    var currentScreen by remember { mutableStateOf(Screen.SETTINGS) }
    
    when (currentScreen) {
        Screen.SETTINGS -> SettingsContent(prefs) { currentScreen = it }
        Screen.MAP_PICKER -> MapPickerContent(prefs) { currentScreen = Screen.SETTINGS }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(prefs: SharedPreferences, onNavigate: (Screen) -> Unit) {
    val isDark = false 
    
    val bgColors = if (isDark) {
        listOf(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF0F172A))
    } else {
        listOf(Color(0xFFF1F5F9), Color(0xFFE2E8F0), Color(0xFFF1F5F9))
    }
    
    val bgBrush = Brush.linearGradient(colors = bgColors)
    
    val textColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subTextColor = if (isDark) Color(0xFFA0AEC0) else Color(0xFF64748B)
    
    val glassColor = if (isDark) Color(0x33FFFFFF) else Color(0x66FFFFFF)
    val glassBorder = if (isDark) Color(0x1AFFFFFF) else Color(0x99FFFFFF)

    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    Box(modifier = Modifier.fillMaxSize().background(bgBrush)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            "蓝钩", 
                            fontWeight = FontWeight.Bold, 
                            fontSize = 24.sp,
                            color = textColor
                        ) 
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { -it / 2 }
                ) {
                    Text(
                        "为 Blued 极速版 提供清爽、沉浸的使用体验。\n当前可通过桌面图标或软件内悬浮球进入此界面进行设置。",
                        color = subTextColor,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500, delayMillis = 100)) + slideInVertically(tween(500, delayMillis = 100)) { 100 }
                ) {
                    SettingsSection(title = "基础功能", glassColor = glassColor, glassBorder = glassBorder, titleColor = subTextColor) {
                        var switchLite by rememberBooleanPreference(prefs, "switch_lite", false)
                        var riskBlock by rememberBooleanPreference(prefs, "switch_risk_user_block", false)
                        var spoofLite by rememberBooleanPreference(prefs, "switch_spoof_lite", false)

                        SettingsSwitchItem(
                            title = "一键 lite (减负提速)", subtitle = "精简不必要的功能和服务", icon = Icons.Outlined.Speed,
                            checked = switchLite, onCheckedChange = { switchLite = it }, textColor = textColor, subTextColor = subTextColor
                        )
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem(
                            title = "风险用户拦截", subtitle = "自动识别并屏蔽风险用户", icon = Icons.Outlined.Shield,
                            checked = riskBlock, onCheckedChange = { riskBlock = it }, textColor = textColor, subTextColor = subTextColor
                        )
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem(
                            title = "属性透视", subtitle = "伪装极速版网络获取更多数据", icon = Icons.Outlined.Visibility,
                            checked = spoofLite, onCheckedChange = { spoofLite = it }, textColor = textColor, subTextColor = subTextColor
                        )
                    }
                }

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500, delayMillis = 150)) + slideInVertically(tween(500, delayMillis = 150)) { 100 }
                ) {
                    SettingsSection(title = "界面净化", glassColor = glassColor, glassBorder = glassBorder, titleColor = subTextColor) {
                        var removeAds by rememberBooleanPreference(prefs, "removeAds", true)
                        var removeLive by rememberBooleanPreference(prefs, "switch_block_live", false)
                        var removeDiscover by rememberBooleanPreference(prefs, "removeDiscover", false)

                        SettingsSwitchItem(
                            title = "移除开屏广告", subtitle = "去除应用启动时的开屏广告", icon = Icons.Outlined.Block,
                            checked = removeAds, onCheckedChange = { removeAds = it }, textColor = textColor, subTextColor = subTextColor
                        )
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem(
                            title = "拦截直播请求", subtitle = "屏蔽底部导航栏的直播相关请求", icon = Icons.Outlined.Videocam,
                            checked = removeLive, onCheckedChange = { removeLive = it }, textColor = textColor, subTextColor = subTextColor
                        )
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem(
                            title = "隐藏发现入口", subtitle = "移除底部导航栏的发现按钮", icon = Icons.Outlined.Explore,
                            checked = removeDiscover, onCheckedChange = { removeDiscover = it }, textColor = textColor, subTextColor = subTextColor
                        )
                    }
                }

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500, delayMillis = 200)) + slideInVertically(tween(500, delayMillis = 200)) { 100 }
                ) {
                    SettingsSection(title = "聊天增强", glassColor = glassColor, glassBorder = glassBorder, titleColor = subTextColor) {
                        var preventRecall by rememberBooleanPreference(prefs, "preventRecall", true)
                        var stealthRead by rememberBooleanPreference(prefs, "stealthRead", false)

                        SettingsSwitchItem(
                            title = "防撤回", subtitle = "拦截并显示对方撤回的消息", icon = Icons.Outlined.Message,
                            checked = preventRecall, onCheckedChange = { preventRecall = it }, textColor = textColor, subTextColor = subTextColor
                        )
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem(
                            title = "消息已读防隐身", subtitle = "即使对方开启隐藏已读，依然显示", icon = Icons.Outlined.MarkChatRead,
                            checked = stealthRead, onCheckedChange = { stealthRead = it }, textColor = textColor, subTextColor = subTextColor
                        )
                    }
                }

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500, delayMillis = 300)) + slideInVertically(tween(500, delayMillis = 300)) { 100 }
                ) {
                    SettingsSection(title = "定位与追踪", glassColor = glassColor, glassBorder = glassBorder, titleColor = subTextColor) {
                        var virtualLocation by rememberBooleanPreference(prefs, "switch_virtual_location", false)
                        var locationTracking by rememberBooleanPreference(prefs, "switch_track", false)

                        SettingsSwitchItem(
                            title = "虚拟定位", subtitle = "修改设备的GPS位置信息", icon = Icons.Outlined.LocationOn,
                            checked = virtualLocation, onCheckedChange = { virtualLocation = it }, textColor = textColor, subTextColor = subTextColor,
                            onClickTrailing = { onNavigate(Screen.MAP_PICKER) }
                        )
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem(
                            title = "位置追踪", subtitle = "个人主页右上角显示追踪按钮", icon = Icons.Outlined.GpsFixed,
                            checked = locationTracking, onCheckedChange = { locationTracking = it }, textColor = textColor, subTextColor = subTextColor
                        )
                    }
                }

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500, delayMillis = 400)) + slideInVertically(tween(500, delayMillis = 400)) { 100 }
                ) {
                    SettingsSection(title = "其他设置", glassColor = glassColor, glassBorder = glassBorder, titleColor = subTextColor) {
                        var removeWatermark by rememberBooleanPreference(prefs, "switch_watermark", true)
                        var crackVip by rememberBooleanPreference(prefs, "crackVip", false)

                        SettingsSwitchItem(
                            title = "去图片水印", subtitle = "保存图片相册时移除水印标识", icon = Icons.Outlined.Image,
                            checked = removeWatermark, onCheckedChange = { removeWatermark = it }, textColor = textColor, subTextColor = subTextColor
                        )
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem(
                            title = "本地 VIP (仅本地)", subtitle = "解锁本地部分VIP功能如高级筛选", icon = Icons.Outlined.Star,
                            checked = crackVip, onCheckedChange = { crackVip = it }, textColor = textColor, subTextColor = subTextColor
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerContent(prefs: SharedPreferences, onBack: () -> Unit) {
    val lat = prefs.getFloat("loc_lat", 39.9042f)
    val lng = prefs.getFloat("loc_lng", 116.4074f)
    
    var simulatedLat by remember { mutableStateOf(lat.toString()) }
    var simulatedLng by remember { mutableStateOf(lng.toString()) }

    val mapProviders = listOf("高德地图", "腾讯地图", "百度地图")
    var selectedProvider by rememberStringPreference(prefs, "map_provider", "高德地图")
    var apiKey by rememberStringPreference(prefs, "map_api_key", "")
    
    val mapHtml = remember(selectedProvider, apiKey, simulatedLat, simulatedLng) {
        when (selectedProvider) {
            "高德地图" -> """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <script type="text/javascript" src="https://webapi.amap.com/maps?v=2.0&key=${if (apiKey.isEmpty()) "demo" else apiKey}"></script>
                    <style>html, body, #container { margin: 0; padding: 0; width: 100%; height: 100%; }</style>
                </head>
                <body>
                    <div id="container"></div>
                    <script>
                        var map = new AMap.Map('container', { zoom: 14, center: [$simulatedLng, $simulatedLat] });
                        var marker = new AMap.Marker({ position: [$simulatedLng, $simulatedLat] });
                        map.add(marker);
                        map.on('click', function(e) {
                            marker.setPosition(e.lnglat);
                            Android.onLocationSelected(e.lnglat.getLat(), e.lnglat.getLng());
                        });
                    </script>
                </body>
                </html>
            """.trimIndent()
            "腾讯地图" -> """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <script charset="utf-8" src="https://map.qq.com/api/gljs?v=1.exp&key=${if (apiKey.isEmpty()) "OB4BZ-D4W3U-B7VVO-4PJWW-6TKDJ-WPB77" else apiKey}"></script>
                    <style>html, body, #container { margin: 0; padding: 0; width: 100%; height: 100%; }</style>
                </head>
                <body>
                    <div id="container"></div>
                    <script>
                        var map = new TMap.Map('container', { zoom: 14, center: new TMap.LatLng($simulatedLat, $simulatedLng) });
                        var marker = new TMap.MultiMarker({
                            map: map,
                            geometries: [{ id: '1', position: new TMap.LatLng($simulatedLat, $simulatedLng) }]
                        });
                        map.on('click', function(evt) {
                            marker.updateGeometries([{ id: '1', position: evt.latLng }]);
                            Android.onLocationSelected(evt.latLng.lat, evt.latLng.lng);
                        });
                    </script>
                </body>
                </html>
            """.trimIndent()
            else -> """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <script type="text/javascript" src="https://api.map.baidu.com/api?type=webgl&v=1.0&ak=${if (apiKey.isEmpty()) "E4805d16520de693a3fe707cdc962045" else apiKey}"></script>
                    <style>html, body, #container { margin: 0; padding: 0; width: 100%; height: 100%; }</style>
                </head>
                <body>
                    <div id="container"></div>
                    <script>
                        var map = new BMapGL.Map('container');
                        var point = new BMapGL.Point($simulatedLng, $simulatedLat);
                        map.centerAndZoom(point, 15);
                        var marker = new BMapGL.Marker(point);
                        map.addOverlay(marker);
                        map.addEventListener('click', function(e) {
                            marker.setPosition(e.latlng);
                            Android.onLocationSelected(e.latlng.lat, e.latlng.lng);
                        });
                    </script>
                </body>
                </html>
            """.trimIndent()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF1F5F9))) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("地图选点") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Settings for Map
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    mapProviders.forEach { provider ->
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = { selectedProvider = provider },
                            label = { Text(provider) }
                        )
                    }
                }
                
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key (为空使用默认测试 Key)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = simulatedLat,
                        onValueChange = { simulatedLat = it },
                        label = { Text("纬度 (Latitude)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = simulatedLng,
                        onValueChange = { simulatedLng = it },
                        label = { Text("经度 (Longitude)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Button(
                    onClick = {
                        val parsedLat = simulatedLat.toFloatOrNull() ?: 39.9042f
                        val parsedLng = simulatedLng.toFloatOrNull() ?: 116.4074f
                        prefs.edit()
                            .putFloat("loc_lat", parsedLat)
                            .putFloat("loc_lng", parsedLng)
                            .apply()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存坐标并返回")
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, Color.LightGray, RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webChromeClient = WebChromeClient()
                                webViewClient = WebViewClient()
                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun onLocationSelected(lat: Double, lng: Double) {
                                        simulatedLat = String.format("%.6f", lat)
                                        simulatedLng = String.format("%.6f", lng)
                                    }
                                }, "Android")
                            }
                        },
                        update = { webView ->
                            webView.loadDataWithBaseURL("https://example.com", mapHtml, "text/html", "UTF-8", null)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}


@Composable
fun SettingsSection(
    title: String,
    glassColor: Color,
    glassBorder: Color,
    titleColor: Color,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            color = titleColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = glassColor,
            border = BorderStroke(1.dp, glassBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    textColor: Color,
    subTextColor: Color,
    onClickTrailing: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { 
                    if (onClickTrailing == null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCheckedChange(!checked)
                    }
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0x1A000000), shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = subTextColor
                )
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        if (onClickTrailing != null) {
            // When there's a trailing action, show both icon and switch, or just switch, but isolate switch clicks
            IconButton(onClick = onClickTrailing) {
                 Icon(Icons.Outlined.Map, contentDescription = "Pick location")
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF34C759), 
                uncheckedTrackColor = Color.Gray.copy(alpha = 0.2f),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun SettingsDivider(glassBorder: Color) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (true) 72.dp else 16.dp), 
        color = glassBorder,
        thickness = 0.5.dp
    )
}
