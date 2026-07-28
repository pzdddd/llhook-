package com.example.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import bxxd.hook.AutoVisitHook
import bxxd.hook.ChatBackupManager
import bxxd.hook.Config
import bxxd.hook.DetectHook
import bxxd.hook.FloatingUI
import bxxd.hook.MmkvCacheClearHook
import bxxd.hook.MapOverlay
import kotlinx.coroutines.delay

// ============================================================================
//  llhook Compose UI —— 继承 llhook 的 iOS / MIUI 玻璃拟态视觉风格。
//
//  设计目标:
//   1. 【界面全部继承 llhook】: 沿用原 SettingsContent/MapPicker/SettingsSection/
//      SettingsSwitchItem 等组件, 视觉零变化 (圆角玻璃卡片 + iOS 绿开关 + 入场动画)。
//   2. 【功能对齐 hook 项目】: 把 bxxd.hook.* 全部模块的开关/工具入口都搬到这个界面,
//      每个开关直接读写 Config (跨进程同步到 Blued)。
//   3. 【双入口复用】: 桌面 MainActivity 和 Blued 内悬浮球 都渲染同一套 Compose UI;
//      Blued 内 (inHost=true) 时多显示一组「工具」分组 (检测/备份/地图/站街/清缓存)。
// ============================================================================

// ---------------------------------------------------------------------------
//  Config ↔ Compose 状态桥
// ---------------------------------------------------------------------------

/** Boolean 开关, 直接读写 Config (跨进程同步)。 */
@Composable
fun rememberConfigBoolean(key: String, default: Boolean = false): MutableState<Boolean> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(Config.isFeatureEnabled(key, ctx)) }
    return remember {
        object : MutableState<Boolean> {
            override var value: Boolean
                get() = state.value
                set(value) {
                    state.value = value
                    Config.setFeatureEnabled(key, value, ctx)
                }
            override fun component1() = state.value
            override fun component2(): (Boolean) -> Unit = { this.value = it }
        }
    }
}

/** 字符串配置, 直接读写 Config。 */
@Composable
fun rememberConfigString(key: String, default: String = ""): MutableState<String> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(Config.getRaw(key, default, ctx)) }
    return remember {
        object : MutableState<String> {
            override var value: String
                get() = state.value
                set(value) {
                    state.value = value
                    Config.setRaw(key, value, ctx)
                }
            override fun component1() = state.value
            override fun component2(): (String) -> Unit = { this.value = it }
        }
    }
}

// 兼容旧调用 (MainActivity / 历史代码 仍可能传入 SharedPreferences) —— 内部直接转 Config。
@Composable
fun rememberBooleanPreference(prefs: SharedPreferences, key: String, defaultValue: Boolean): MutableState<Boolean> =
    rememberConfigBoolean(key, defaultValue)
@Composable
fun rememberStringPreference(prefs: SharedPreferences, key: String, defaultValue: String): MutableState<String> =
    rememberConfigString(key, defaultValue)


enum class Screen {
    SETTINGS
}

/**
 * @param prefs 仅作向后兼容, 实际配置一律走 Config (跨模块/跨进程)。
 * @param hostActivity 当 UI 注入在 Blued 进程内 (悬浮球入口) 时, 传入宿主 Activity,
 *                     用来唤起设备检测 / 地图选点 / 聊天备份 / 站街 等需要宿主 Context 的工具。
 * @param inHost true = Blued 内悬浮球渲染; false = 桌面图标渲染。
 */
@Composable
fun MainScreen(
    prefs: SharedPreferences? = null,
    hostActivity: Activity? = null,
    inHost: Boolean = false,
    panelMode: Boolean = false,
    /** 是否从 Blued 内「我的」页面田字格的「模块入口」进入。某些详细配置分区仅在此入口显示。 */
    mineEntry: Boolean = false
) {
    SettingsContent(hostActivity, inHost, panelMode, mineEntry) { /* 预留导航 (地图选点已改为独立弹窗, 不再走屏内路由) */ }
}

// ---------------------------------------------------------------------------
//  主设置页
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(hostActivity: Activity?, inHost: Boolean, panelMode: Boolean = false, mineEntry: Boolean = false, onNavigate: (Screen) -> Unit) {
    val isDark = isSystemInDarkTheme()

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
    LaunchedEffect(Unit) { visible = true }

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
                    actions = {
                        val accentBlue = Color(0xFF3B82F6)
                        if (!inHost) {
                            // 桌面图标入口: 右上角“启动 Blued”按钮 (绿色背景)
                            val ctx = LocalContext.current
                            TextButton(
                                onClick = {
                                    val pkg = Config.currentBluedPackage
                                    runCatching {
                                        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                                        if (intent != null) {
                                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            ctx.startActivity(intent)
                                        } else {
                                            Toast.makeText(ctx, "未检测到 Blued ($pkg)", Toast.LENGTH_SHORT).show()
                                        }
                                    }.onFailure { Toast.makeText(ctx, "启动失败: ${it.message}", Toast.LENGTH_SHORT).show() }
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .offset(x = (-5).dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF22C55E))
                            ) {
                                Icon(Icons.Filled.PlayArrow, "启动 Blued", tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("启动", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            // Blued 内 (悬浮窗 / “我的”入口): 右上角“重启 Blued”按钮
                            val a = hostActivity
                            if (a != null) {
                                TextButton(
                                    onClick = {
                                        Toast.makeText(a, "正在重启 Blued...", Toast.LENGTH_SHORT).show()
                                        FloatingUI.restartHostApp(a)
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Filled.Refresh, "重启 Blued", tint = accentBlue, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("重启", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    },
                    // 浮窗模式不需要状态栏 padding (窗口本身已居中, 不与状态 bar 重叠)
                    windowInsets = if (panelMode) WindowInsets(0) else TopAppBarDefaults.windowInsets,
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

                // ====== LSPosed 激活状态卡片 (仅桌面图标入口显示) ======
                if (!inHost) AnimatedVisibility(visible, enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { -it / 2 }) {
                    val activated = com.example.MainActivity.moduleActivated
                    val brush = if (activated) Brush.linearGradient(listOf(Color(0xFF22C55E), Color(0xFF16A34A)))
                                else Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFF97316)))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(brush)
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (activated) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                                    null, tint = Color.White, modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (activated) "LSPosed 已激活" else "LSPosed 未激活",
                                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (activated) "模块运行正常，所有功能均已生效"
                                     else "请在 LSPosed 中勾选本模块后强制停止 Blued",
                                    color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // ====== 基础功能 (「我的」入口与桌面图标显示, 悬浮球不显示) ======
                if (mineEntry || !inHost) AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 100)) + slideInVertically(tween(500, delayMillis = 100)) { 100 }) {
                    SettingsSection("基础功能", glassColor, glassBorder, subTextColor) {
                        // 悬浮球开关 (仅在 Blued 宿主内显示, 桌面图标入口无悬浮球)
                        if (inHost) {
                            val ctx = LocalContext.current
                            var showFloat by remember { mutableStateOf(!bxxd.hook.FloatButtonInjector.isCurrentlyHidden(ctx)) }
                            SettingsSwitchItem("显示悬浮球", "主页右下角悬浮入口球; 关闭后可在此重新开启", Icons.Outlined.Circle,
                                showFloat, { on ->
                                    showFloat = on
                                    val a = hostActivity
                                    if (a != null) {
                                        if (on) bxxd.hook.FloatButtonInjector.unhide(a)
                                        else bxxd.hook.FloatButtonInjector.hide(a)
                                    }
                                }, textColor, subTextColor)
                            SettingsDivider(glassBorder)
                        }
                        var switchLite by rememberConfigBoolean("switch_lite")
                        var riskBlock by rememberConfigBoolean("switch_risk_user_block")
                        var spoofLite by rememberConfigBoolean(bxxd.hook.NetworkSpoofHook.KEY_ENABLED)

                        SettingsSwitchItem("一键 lite (减负提速)", "精简不必要的功能和服务", Icons.Outlined.Speed,
                            switchLite, { switchLite = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("风险用户拦截", "自动识别并屏蔽风险用户", Icons.Outlined.Shield,
                            riskBlock, { riskBlock = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("属性透视", "联网查详情接口为附近/在线列表补全真实 role (不影响 IP/访客/闪照)",
                            Icons.Outlined.Visibility,
                            spoofLite, { spoofLite = it }, textColor, subTextColor
                        )
                    }
                }

                // ====== 一键净化 (总开关 + 逐项) ======
                AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 130)) + slideInVertically(tween(500, delayMillis = 130)) { 100 }) {
                    PurifySection(glassColor, glassBorder, textColor, subTextColor)
                }

                // ====== 界面净化 (其余总开关) ======
                AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 160)) + slideInVertically(tween(500, delayMillis = 160)) { 100 }) {
                    SettingsSection("界面净化", glassColor, glassBorder, subTextColor) {
                        var removeAds by rememberConfigBoolean("switch_block_ads", true)
                        var removeLive by rememberConfigBoolean("switch_block_live", false)

                        SettingsSwitchItem("拦截广告 SDK 请求", "底层 OkHttp/Socket 直接断网", Icons.Outlined.Block,
                            removeAds, { removeAds = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("拦截直播请求", "屏蔽底部导航栏的直播相关请求", Icons.Outlined.Videocam,
                            removeLive, { removeLive = it }, textColor, subTextColor)
                    }
                }

                // ====== 个性化 (首页/资料/导航/卡片/背景) ======
                AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 175)) + slideInVertically(tween(500, delayMillis = 175)) { 100 }) {
                    SettingsFolder(
                        title = "个性化",
                        subtitle = "首页 / 资料 / 导航 / 卡片 / 背景等外观定制",
                        icon = Icons.Outlined.Palette,
                        glassColor = glassColor,
                        glassBorder = glassBorder,
                        textColor = textColor,
                        subTextColor = subTextColor
                    ) {
                        var qqHome by rememberConfigBoolean("switch_qq_home", false)
                        var showQqMenu by remember { mutableStateOf(false) }
                        var qqCoord by rememberConfigBoolean("switch_qq_coord", true)
                        SettingsSwitchItem(
                            title = "QQ 风格首页",
                            subtitle = "左上角圆形头像 + 右滑打开我的",
                            icon = Icons.Outlined.Dashboard,
                            checked = qqHome,
                            onCheckedChange = { qqHome = it },
                            textColor = textColor,
                            subTextColor = subTextColor,
                            trailingSlot = {
                                Box {
                                    IconButton(onClick = { showQqMenu = true }) {
                                        Icon(Icons.Outlined.Tune, contentDescription = "QQ首页设置")
                                    }
                                    if (showQqMenu) {
                                        CenteredPanelDialog(
                                            onDismissRequest = { showQqMenu = false },
                                            title = "QQ 风格首页设置",
                                            subtitle = "调整首页布局细节"
                                        ) {
                                            SettingsSwitchItem(
                                                "左上角坐标显示",
                                                "头像右侧显示当前虚拟定位坐标 + 真实地名, 点击可跳转地图选点",
                                                Icons.Outlined.MyLocation, qqCoord, { qqCoord = it }, textColor, subTextColor
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        SettingsDivider(glassBorder)
                        var chatBtnStyle by rememberConfigBoolean("switch_chat_button_style", false)
                        SettingsSwitchItem("资料页聊天按钮改圆形右下角", "UI 净化: 圆形悬浮聊天按钮", Icons.Outlined.ChatBubbleOutline,
                            chatBtnStyle, { chatBtnStyle = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        // 底部导航栏: 圆角 + 悬浮 合并 (main_navigation)
                        var navRound by rememberConfigBoolean("switch_main_nav_round", false)
                        var showNavPanel by remember { mutableStateOf(false) }
                        SettingsSwitchItem(
                            title = "导航栏圆角悬浮",
                            subtitle = "底部 Tab 栏圆角 + 上抬悬浮, 可调半径/宽度/边距/阴影, 移除分割线",
                            icon = Icons.Outlined.DashboardCustomize,
                            checked = navRound,
                            onCheckedChange = { navRound = it },
                            textColor = textColor,
                            subTextColor = subTextColor,
                            trailingSlot = {
                                Box {
                                    IconButton(onClick = { showNavPanel = true }) {
                                        Icon(Icons.Outlined.Tune, contentDescription = "导航栏设置")
                                    }
                                    if (showNavPanel) {
                                        CenteredPanelDialog(
                                            onDismissRequest = { showNavPanel = false },
                                            title = "底部导航栏设置",
                                            subtitle = "圆角半径 / 宽度 / 高度 / 边距 / 阴影"
                                        ) {
                                            MainNavPanel(textColor, subTextColor)
                                        }
                                    }
                                }
                            }
                        )
                        // 附近列表卡片化: fl_main 圆角背景 + 自定义颜色/渐变
                        var nearbyCard by rememberConfigBoolean("switch_nearby_card", false)
                        var showCardPanel by remember { mutableStateOf(false) }
                        SettingsSwitchItem(
                            title = "附近列表卡片化",
                            subtitle = "每条列表项改为圆角卡片背景, 可自定义颜色/透明度/渐变",
                            icon = Icons.Outlined.DashboardCustomize,
                            checked = nearbyCard,
                            onCheckedChange = { nearbyCard = it },
                            textColor = textColor,
                            subTextColor = subTextColor,
                            trailingSlot = {
                                Box {
                                    IconButton(onClick = { showCardPanel = true }) {
                                        Icon(Icons.Outlined.Palette, contentDescription = "卡片样式")
                                    }
                                    if (showCardPanel) {
                                        CenteredPanelDialog(
                                            onDismissRequest = { showCardPanel = false },
                                            title = "附近列表卡片样式",
                                            subtitle = "实时调整圆角与背景"
                                        ) {
                                            NearbyCardPanel(textColor, subTextColor, glassColor, glassBorder)
                                        }
                                    }
                                }
                            }
                        )
                        SettingsDivider(glassBorder)
                        // 身边页背景: recycler_view 整页背景 颜色/渐变/图库图片
                        var nearbyPageBg by rememberConfigBoolean("switch_nearby_page_bg", false)
                        var showPageBgPanel by remember { mutableStateOf(false) }
                        SettingsSwitchItem(
                            title = "身边页背景",
                            subtitle = "身边页整页背景 (recycler_view), 可调色板或图库图片自定义",
                            icon = Icons.Outlined.Wallpaper,
                            checked = nearbyPageBg,
                            onCheckedChange = { nearbyPageBg = it },
                            textColor = textColor,
                            subTextColor = subTextColor,
                            trailingSlot = {
                                Box {
                                    IconButton(onClick = { showPageBgPanel = true }) {
                                        Icon(Icons.Outlined.Palette, contentDescription = "页面背景样式")
                                    }
                                    if (showPageBgPanel) {
                                        CenteredPanelDialog(
                                            onDismissRequest = { showPageBgPanel = false },
                                            title = "身边页背景样式",
                                            subtitle = "整页背景颜色或图片"
                                        ) {
                                            NearbyPageBgPanel(textColor, subTextColor, glassColor, glassBorder)
                                        }
                                    }
                                }
                            }
                        )
                        SettingsDivider(glassBorder)
                        // 消息列表卡片化: ll_msg_f_root 圆角背景 + 自定义颜色/渐变
                        var msgCard by rememberConfigBoolean("switch_msg_card", false)
                        var showMsgCardPanel by remember { mutableStateOf(false) }
                        SettingsSwitchItem(
                            title = "消息列表卡片化",
                            subtitle = "每条会话项改为圆角卡片背景, 可自定义颜色/透明度/渐变",
                            icon = Icons.Outlined.DashboardCustomize,
                            checked = msgCard,
                            onCheckedChange = { msgCard = it },
                            textColor = textColor,
                            subTextColor = subTextColor,
                            trailingSlot = {
                                Box {
                                    IconButton(onClick = { showMsgCardPanel = true }) {
                                        Icon(Icons.Outlined.Palette, contentDescription = "消息卡片样式")
                                    }
                                    if (showMsgCardPanel) {
                                        CenteredPanelDialog(
                                            onDismissRequest = { showMsgCardPanel = false },
                                            title = "消息列表卡片样式",
                                            subtitle = "实时调整圆角与背景"
                                        ) {
                                            MsgCardPanel(textColor, subTextColor, glassColor, glassBorder)
                                        }
                                    }
                                }
                            }
                        )
                        SettingsDivider(glassBorder)
                        // 消息页背景: 整页背景 颜色/渐变/图库图片
                        var msgPageBg by rememberConfigBoolean("switch_msg_page_bg", false)
                        var showMsgPageBgPanel by remember { mutableStateOf(false) }
                        SettingsSwitchItem(
                            title = "消息页背景",
                            subtitle = "消息页整页背景, 可调色板或图库图片自定义",
                            icon = Icons.Outlined.Wallpaper,
                            checked = msgPageBg,
                            onCheckedChange = { msgPageBg = it },
                            textColor = textColor,
                            subTextColor = subTextColor,
                            trailingSlot = {
                                Box {
                                    IconButton(onClick = { showMsgPageBgPanel = true }) {
                                        Icon(Icons.Outlined.Palette, contentDescription = "消息页背景样式")
                                    }
                                    if (showMsgPageBgPanel) {
                                        CenteredPanelDialog(
                                            onDismissRequest = { showMsgPageBgPanel = false },
                                            title = "消息页背景样式",
                                            subtitle = "整页背景颜色或图片"
                                        ) {
                                            MsgPageBgPanel(textColor, subTextColor, glassColor, glassBorder)
                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                // ====== 聊天增强 (「我的」入口与桌面图标显示) ======
                if (mineEntry || !inHost) AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 200)) + slideInVertically(tween(500, delayMillis = 200)) { 100 }) {
                    SettingsFolder(
                        title = "聊天增强",
                        subtitle = "防撒回 / 闪照 / 已读防隐身 / 资料透视 等",
                        icon = Icons.Outlined.Forum,
                        glassColor = glassColor,
                        glassBorder = glassBorder,
                        textColor = textColor,
                        subTextColor = subTextColor
                    ) {
                        var preventRecall by rememberConfigBoolean("switch_anti_recall", true)
                        var flashPhoto by rememberConfigBoolean("switch_flash_photo", false)
                        var flashAdSkip by rememberConfigBoolean("switch_flash_ad_skip", false)
                        var nearbyChat by rememberConfigBoolean("switch_nearby_chat", false)
                        var nearbyAlbum by rememberConfigBoolean("switch_nearby_album", false)
                        var nearbySort by rememberConfigBoolean("switch_nearby_sort", false)
                        var screenshot by rememberConfigBoolean("switch_screenshot", false)
                        var stealthRead by rememberConfigBoolean("switch_read_receipt", false)
                        var secretViewAll by rememberConfigBoolean("switch_secret_view_all", false)
                        var chatWatermark by rememberConfigBoolean("switch_chat_watermark", true)
                        var deletedMark by rememberConfigBoolean("switch_deleted_mark", true)
                        var msgTimestamp by rememberConfigBoolean("switch_msg_timestamp", false)

                        SettingsSwitchItem("防撤回", "拦截并显示对方撤回的消息", Icons.Outlined.Message,
                            preventRecall, { preventRecall = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("闪照转照片", "把闪照直接当普通图片查看", Icons.Outlined.Photo,
                            flashPhoto, { flashPhoto = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("闪照免看广告", "闪照次数用尽后, 点「看视频获得一次机会」直接发放奖励, 跳过广告视频", Icons.Outlined.VideoCall,
                            flashAdSkip, { flashAdSkip = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("附近列表一键聊天", "附近/访客列表项: 距离左移, 右侧加💬按钮直达聊天", Icons.Outlined.Forum,
                            nearbyChat, { nearbyChat = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("附近列表查看私密相册", "聊天按钮下方加🖼按钮, 免进资料页直接查看对方私密相册", Icons.Outlined.PhotoLibrary,
                            nearbyAlbum, { nearbyAlbum = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("首页增加筛选", "附近页排序栏末尾追加筛选按钮 (按角色/VIP/相册/真人等客户端筛选, 抓包不可见), 筛选条件自动持久化", Icons.Outlined.FilterAlt,
                            nearbySort, { nearbySort = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("去除截屏限制", "聊天页禁止截屏时强制允许", Icons.Outlined.Screenshot,
                            screenshot, { screenshot = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("消息已读防隐身", "即使对方开启隐藏已读, 依然显示", Icons.Outlined.MarkChatRead,
                            stealthRead, { stealthRead = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("悄悄查看所有消息", "查看消息后对方聊天界面不显示已读 (长按消息页顶部「聊天」Tab 可快捷开关)", Icons.Outlined.Visibility,
                            secretViewAll, { secretViewAll = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("已注销/风险用户标记", "对方注销账号/风险用户时, 消息列表名字旁显示已注销·风险·诈骗标记, 打开资料页弹提示", Icons.Outlined.PersonOff,
                            deletedMark, { deletedMark = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("消息显示具体时间", "聊天页每条消息下方显示发送时间 (HH:mm)", Icons.Outlined.Schedule,
                            msgTimestamp, { msgTimestamp = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("聊天页资料透视", "聊天页半透明显示对方资料 (年龄/身高/距离破译/最后在线, 反截图泄露)", Icons.Outlined.PersonSearch,
                            chatWatermark, { chatWatermark = it }, textColor, subTextColor)
                    }
                }

                // ====== 隐私与特权 (「我的」入口与桌面图标显示) ======
                if (mineEntry || !inHost) AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 230)) + slideInVertically(tween(500, delayMillis = 230)) { 100 }) {
                    SettingsSection("隐私与特权", glassColor, glassBorder, subTextColor) {
                        var privatePhoto by rememberConfigBoolean("switch_private_photo", false)
                        var noWatermark by rememberConfigBoolean("switch_watermark", false)
                        var crackVip by rememberConfigBoolean("switch_local_vip", false)

                        SettingsSwitchItem("查看私密相册", "绕过私密相册权限校验", Icons.Outlined.PhotoLibrary,
                            privatePhoto, { privatePhoto = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("无水印保存图片", "保存照片/动态时去除水印, 并解除保存限制", Icons.Outlined.WaterDrop,
                            noWatermark, { noWatermark = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("本地 VIP (仅本地)", "解锁本地部分 VIP 功能如高级筛选", Icons.Outlined.Star,
                            crackVip, { crackVip = it }, textColor, subTextColor)
                    }
                }

                // ====== 定位与追踪 ======
                AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 260)) + slideInVertically(tween(500, delayMillis = 260)) { 100 }) {
                    SettingsSection("定位与追踪", glassColor, glassBorder, subTextColor) {
                        var virtualLocation by rememberConfigBoolean("switch_virtual_location", false)
                        var locationTracking by rememberConfigBoolean("switch_track", false)

                        SettingsSwitchItem("虚拟定位", "深度篡改 GPS / WiFi / 基站 / 地图 SDK", Icons.Outlined.LocationOn,
                            virtualLocation, { virtualLocation = it }, textColor, subTextColor,
                            onClickTrailing = {
                                // 合并: 直接弹 Compose 地图精准选点 (与工具栏入口同一页面)
                                val a = hostActivity
                                if (a != null) {
                                    try { bxxd.hook.MapOverlay.showMap(a) }
                                    catch (e: Throwable) { Toast.makeText(a, "地图唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                                }
                            })
                        SettingsDivider(glassBorder)
                        var trackMenuExpanded by remember { mutableStateOf(false) }
                        val trackManual by rememberConfigBoolean("switch_track_manual", false)
                        SettingsSwitchItem(
                            title = "位置追踪",
                            subtitle = if (trackManual)
                                "手动模式：进入主页不自动解算，点击「追踪」才解算"
                            else
                                "个人主页显示追踪按钮，进入主页即自动后台解算",
                            icon = Icons.Outlined.GpsFixed,
                            checked = locationTracking,
                            onCheckedChange = { locationTracking = it },
                            textColor = textColor,
                            subTextColor = subTextColor,
                            trailingSlot = {
                                Box {
                                    IconButton(onClick = { trackMenuExpanded = true }) {
                                        Icon(Icons.Outlined.Tune, contentDescription = "追踪设置")
                                    }
                                    if (trackMenuExpanded) {
                                        CenteredPanelDialog(
                                            onDismissRequest = { trackMenuExpanded = false },
                                            title = "位置追踪设置",
                                            subtitle = "调整追踪行为模式"
                                        ) {
                                            TrackManualMenuContent()
                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                // ====== 实验性功能 (消息推送/保活 + 风控控制 + 调试诊断工具, 折叠收纳) ======
                AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 290)) + slideInVertically(tween(500, delayMillis = 290)) { 100 }) {
                    SettingsFolder(
                        title = "实验性功能",
                        subtitle = "消息推送 / 保活 · 设备风控 · 抓包解密等进阶与诊断工具",
                        icon = Icons.Outlined.Science,
                        glassColor = glassColor,
                        glassBorder = glassBorder,
                        textColor = textColor,
                        subTextColor = subTextColor
                    ) {
                        // —— 消息推送 / 保活 ——
                        var forcePush by rememberConfigBoolean("switch_force_push", false)
                        var forcePushGroup by rememberConfigBoolean("switch_force_push_group", false)
                        var pushTakeover by rememberConfigBoolean("switch_push_takeover", false)
                        var keepAlive by rememberConfigBoolean("switch_keep_alive", false)
                        var keepAliveRelaunch by rememberConfigBoolean("switch_keep_alive_relaunch", false)
                        SettingsSwitchItem("强制消息推送 (私聊)", "手机收不到 Blued 推送时打开", Icons.Outlined.NotificationsActive,
                            forcePush, { forcePush = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("└ 同时推送群聊消息", "强制推送也覆盖群聊", Icons.Outlined.GroupWork,
                            forcePushGroup, { forcePushGroup = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("接管原始推送 (实验)", "替换 Blued 原生推送通道", Icons.Outlined.SyncAlt,
                            pushTakeover, { pushTakeover = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("Blued 进程保活", "维持实时推送, 绕开厂商延迟", Icons.Outlined.Memory,
                            keepAlive, { keepAlive = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("└ 断开后自动重开 Blued", "保活心跳超时自动拉起", Icons.Outlined.RestartAlt,
                            keepAliveRelaunch, { keepAliveRelaunch = it }, textColor, subTextColor)

                        // —— 风控控制 (保留原可见性: 「我的」入口或桌面端才显示) ——
                        if (mineEntry || !inHost) {
                            var deviceFake by rememberConfigBoolean("switch_device_fake", false)
                            var deviceEmpty by rememberConfigBoolean("switch_device_empty", false)
                            var deviceIntercept by rememberConfigBoolean("switch_device_intercept", false)
                            SettingsDivider(glassBorder)
                            SettingsSwitchItem("伪装设备指纹", "反射篡改数美 SmAntiFraud 指纹", Icons.Outlined.Fingerprint,
                                deviceFake, { deviceFake = it }, textColor, subTextColor)
                            SettingsDivider(glassBorder)
                            SettingsSwitchItem("清空返回值", "getDeviceId 等直接置空", Icons.Outlined.NoEncryption,
                                deviceEmpty, { deviceEmpty = it }, textColor, subTextColor)
                            SettingsDivider(glassBorder)
                            SettingsSwitchItem("拦截机器码上传", "阻断设备信息上传请求", Icons.Outlined.Block,
                                deviceIntercept, { deviceIntercept = it }, textColor, subTextColor)
                        }

                        // —— 调试 / 诊断工具 (仅 Blued 内, 依赖宿主 Activity) ——
                        if (inHost && hostActivity != null) {
                            val activity = hostActivity!!
                            SettingsDivider(glassBorder)
                            ToolRow("设备检测 (Blued 视角)", "查看 Blued 采集的设备数据与风控", Icons.Outlined.BugReport, subTextColor) {
                                try { showHostComposeScreen(activity) { onClose -> DetectScreen(activity, onClose) } }
                                catch (e: Throwable) { Toast.makeText(activity, "检测页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                            }
                            SettingsDivider(glassBorder)
                            ToolRow("用户列表", "已收集 ${AutoVisitHook.cachedUsers.size} 名附近用户 · 点击查看", Icons.Outlined.Group, subTextColor) {
                                try { showHostComposeScreen(activity) { onClose -> NearbyUsersScreen(activity, onClose) } }
                                catch (e: Throwable) { Toast.makeText(activity, "用户列表唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                            }
                            SettingsDivider(glassBorder)
                            ToolRow("网络抓包查看器", "实时记录解密明文 · 自动解密 en_data · 改 UA 重放", Icons.Outlined.NetworkCheck, subTextColor) {
                                try { showHostComposeScreen(activity) { onClose -> NetworkCaptureScreen(activity, onClose) } }
                                catch (e: Throwable) { Toast.makeText(activity, "抓包页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                            }
                            SettingsDivider(glassBorder)
                            ToolRow("endata 解密器", "手动粘贴 en_data 加密数据 → 还原明文 (AES-GCM)", Icons.Outlined.EnhancedEncryption, subTextColor) {
                                try { showHostComposeScreen(activity) { onClose -> EndataDecryptScreen(activity, onClose) } }
                                catch (e: Throwable) { Toast.makeText(activity, "解密器唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                            }
                            SettingsDivider(glassBorder)
                            ToolRow("屏蔽广告接口", "自定义广告/追踪接口黑名单 · 逐条开关 · 导入导出", Icons.Outlined.Block, subTextColor) {
                                try { showHostComposeScreen(activity) { onClose -> AdApiBlockScreen(activity, onClose) } }
                                catch (e: Throwable) { Toast.makeText(activity, "广告接口黑名单唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                            }
                            SettingsDivider(glassBorder)
                            ToolRow("凭证与运行状态", "Authorization / UA / 坐标 / API Key 查看", Icons.Outlined.VpnKey, subTextColor) {
                                try { showHostComposeScreen(activity) { onClose -> CredentialViewerScreen(activity, onClose) } }
                                catch (e: Throwable) { Toast.makeText(activity, "凭证页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                            }
                            SettingsDivider(glassBorder)
                            ToolRow("清理角色缓存并重启", "粉碎 mmkv 角色缓存后重启宿主", Icons.Outlined.DeleteSweep, subTextColor) {
                                val ok = MmkvCacheClearHook.clearMmkvCache(activity)
                                Toast.makeText(activity, if (ok) "缓存已粉碎, 正在重启..." else "无旧缓存, 直接重启...", Toast.LENGTH_SHORT).show()
                                FloatingUI.restartHostApp(activity)
                            }
                        }
                    }
                }

                // ====== 工具入口 (仅在 Blued 内悬浮球渲染时显示, 这些功能依赖宿主 Activity) ======
                if (inHost && hostActivity != null) {
                    AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 350)) + slideInVertically(tween(500, delayMillis = 350)) { 100 }) {
                        ToolsSection(hostActivity, glassColor, glassBorder, subTextColor)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  净化中心 (总开关 + 居中面板逐项配置, 数据源与 AdsHook.PURIFY_ITEMS 一致)
// ---------------------------------------------------------------------------

@Composable
private fun PurifySection(
    glassColor: Color, glassBorder: Color, textColor: Color, subTextColor: Color
) {
    var showPanel by remember { mutableStateOf(false) }
    val itemCount = remember { bxxd.hook.AdsHook.PURIFY_ITEMS.size }

    SettingsSection("净化中心", glassColor, glassBorder, subTextColor) {
        var master by rememberConfigBoolean("switch_remove_ads", false)
        SettingsSwitchItem(
            title = "净化总开关 (全部生效)",
            subtitle = "打开则所有净化项一律生效; 点右侧⚙按钮逐项配置",
            icon = Icons.Outlined.AutoFixHigh,
            checked = master,
            onCheckedChange = { master = it },
            textColor = textColor,
            subTextColor = subTextColor,
            trailingSlot = {
                Box {
                    IconButton(onClick = { showPanel = true }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "净化项配置")
                    }
                    if (showPanel) {
                        CenteredPanelDialog(
                            onDismissRequest = { showPanel = false },
                            title = "净化项配置",
                            subtitle = "共 $itemCount 项, 逐项开关 (总开关优先于单项)"
                        ) {
                            PurifyItemsPanelContent(textColor, subTextColor)
                        }
                    }
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
//  净化项居中面板内容: 可滚动列表, 每项一个开关 (读写 purify_* 键)
// ---------------------------------------------------------------------------
@Composable
private fun PurifyItemsPanelContent(textColor: Color, subTextColor: Color) {
    val purifyItems = remember { bxxd.hook.AdsHook.PURIFY_ITEMS }
    val dividerColor = subTextColor.copy(alpha = 0.25f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
    ) {
        purifyItems.forEachIndexed { idx, (label, key) ->
            PurifyItemRow(label, key, textColor, dividerColor, isLast = idx == purifyItems.size - 1)
        }
    }
}

@Composable
private fun PurifyItemRow(
    label: String, key: String, textColor: Color, dividerColor: Color, isLast: Boolean
) {
    var on by rememberConfigBoolean(key, false)
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                on = !on
            }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = textColor, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = on,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                on = it
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
    if (!isLast) {
        HorizontalDivider(color = dividerColor, thickness = 0.5.dp)
    }
}


// ---------------------------------------------------------------------------
//  工具入口分组 (仅 Blued 内)
// ---------------------------------------------------------------------------

@Composable
private fun ToolsSection(
    activity: Activity, glassColor: Color, glassBorder: Color, subTextColor: Color
) {
    // 阶段 2 完成: 所有工具按钮点击直接弹 llhook Compose 全屏页 (showHostComposeScreen),
    // 不再需要 HostToolDialog 中间状态机。
    SettingsSection("工具 (Blued 内)", glassColor, glassBorder, subTextColor) {
        ToolRow("拦截用户列表", "拦截记录 / 手动收藏 / 拉黑 / 跳转", Icons.Outlined.Shield, subTextColor) {
            try { showHostComposeScreen(activity) { onClose -> RiskUsersScreen(activity, onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "拦截列表唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("秘密相册", "闪照/照片自动入库 · 物理隐身", Icons.Outlined.PhotoLibrary, subTextColor) {
            try { showHostComposeScreen(activity) { onClose -> SecretAlbumScreen(activity, onClose = onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "秘密相册唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("聊天备份与恢复", "备份/恢复 Blued 聊天数据库", Icons.Outlined.Backup, subTextColor) {
            try { showHostComposeScreen(activity) { onClose -> ChatBackupScreen(activity, onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "备份页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("坐标收藏夹管理", "批量管理 / 一键设为虚拟定位 / 导入导出", Icons.Outlined.Bookmark, subTextColor) {
            try { showHostComposeScreen(activity) { onClose -> FavoritesScreen(activity, onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "收藏夹页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("一键站街", "按距离/在线批量访问", Icons.Outlined.Bolt, subTextColor) {
            try { showHostComposeScreen(activity) { onClose -> AutoVisitScreen(activity, onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "站街页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("停止站街", "中止正在进行的批量访问", Icons.Outlined.StopCircle, subTextColor) {
            if (AutoVisitHook.isVisiting) {
                AutoVisitHook.stopAutoVisit()
                Toast.makeText(activity, "已下达停止站街指令", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "当前未在站街", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun ToolRow(
    title: String, subtitle: String?, icon: ImageVector?, subTextColor: Color, onClick: () -> Unit
) {
    // 文件夹内紧凑渲染 (与 SettingsSwitchItem 紧凑密度一致)
    val compact = LocalFolderCompact.current
    val titleSp = if (compact) 14.5.sp else 17.sp
    val subSp = if (compact) 11.5.sp else 13.sp
    val vPad = if (compact) 10.dp else 14.dp
    val hPad = if (compact) 12.dp else 16.dp
    val iconBox = if (compact) 34.dp else 40.dp
    val iconSz = if (compact) 20.dp else 24.dp
    val iconSp = if (compact) 12.dp else 16.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = hPad, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(iconBox)
                    .background(Color(0x1A000000), shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = subTextColor, modifier = Modifier.size(iconSz))
            }
            Spacer(modifier = Modifier.width(iconSp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = titleSp, fontWeight = FontWeight.Medium, color = subTextColor)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, fontSize = subSp, color = subTextColor.copy(alpha = 0.8f), lineHeight = if (compact) 14.sp else TextUnit.Unspecified)
            }
        }
        Spacer(modifier = Modifier.width(iconSp))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = subTextColor)
    }
}

// ---------------------------------------------------------------------------
//  备份目录配置
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
//  通用 Composable (沿用 llhook 原视觉)
// ---------------------------------------------------------------------------

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
            Column { content() }
        }
    }
}

// ---------------------------------------------------------------------------
//  「文件夹」式分组: 列表里只占一行卡片, 点击弹出居中弹窗展示内部项。
//  用于项数较多的分组 (个性化 / 聊天增强), 避免主列表过长。
//  弹窗内容可滚动 (heightIn max 460dp), 内部项与普通 SettingsSwitchItem 完全一致。
// ---------------------------------------------------------------------------

/** 文件夹内紧凑渲染开关: SettingsFolder 内部 provide=true,
 *  SettingsSwitchItem 读取后自动缩小字号/间距/图标, 减少标题副标题换行与占用空间。 */
val LocalFolderCompact = staticCompositionLocalOf { false }

@Composable
fun SettingsFolder(
    title: String,
    subtitle: String,
    icon: ImageVector,
    glassColor: Color,
    glassBorder: Color,
    textColor: Color,
    subTextColor: Color,
    content: @Composable () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "folderScale"
    )

    Column {
        Text(title, color = subTextColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = glassColor,
            border = BorderStroke(1.dp, glassBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(scale)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            open = true
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图标徽章
                Surface(
                    shape = CircleShape,
                    color = textColor.copy(alpha = 0.08f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(icon, contentDescription = title, tint = textColor, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = subTextColor, fontSize = 11.sp, lineHeight = 14.sp)
                }
                Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = "展开",
                    tint = subTextColor, modifier = Modifier.size(24.dp))
            }
        }
    }

    if (open) {
        CenteredPanelDialog(
            onDismissRequest = { open = false },
            title = title,
            subtitle = subtitle
        ) {
            CompositionLocalProvider(LocalFolderCompact provides true) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    content()
                }
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
    onClickTrailing: (() -> Unit)? = null,
    /** 整行点击回调 (用于 PurifySection 展开等); 与普通开关点击互斥。 */
    onClickRow: (() -> Unit)? = null,
    /** 尾部按钮图标 (仅 onClickTrailing 生效时使用)。 */
    trailingIcon: ImageVector = Icons.Outlined.Map,
    trailingContentDescription: String = "Pick location",
    /**
     * 自定义尾部内容 (优先级高于 onClickTrailing): 用于在尾部嵌入弹出菜单等复杂交互。
     * 传入后, 整行点击不再切换开关 (需用开关本体或菜单内部控件操作)。
     */
    trailingSlot: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    // 文件夹内紧凑渲染 (缩小字号/间距/图标, 减少换行与占用空间)
    val compact = LocalFolderCompact.current
    val titleSp = if (compact) 14.5.sp else 17.sp
    val subSp = if (compact) 11.5.sp else 13.sp
    val vPad = if (compact) 10.dp else 14.dp
    val hPad = if (compact) 12.dp else 16.dp
    val iconBox = if (compact) 34.dp else 40.dp
    val iconSz = if (compact) 20.dp else 24.dp
    val iconSp = if (compact) 12.dp else 16.dp

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
                    if (onClickRow != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClickRow()
                    } else if (onClickTrailing == null && trailingSlot == null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCheckedChange(!checked)
                    }
                }
            )
            .padding(horizontal = hPad, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(iconBox)
                    .background(Color(0x1A000000), shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = textColor, modifier = Modifier.size(iconSz))
            }
            Spacer(modifier = Modifier.width(iconSp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = titleSp, fontWeight = FontWeight.Medium, color = textColor)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, fontSize = subSp, color = subTextColor, lineHeight = if (compact) 14.sp else TextUnit.Unspecified)
            }
        }

        Spacer(modifier = Modifier.width(iconSp))

        if (trailingSlot != null) {
            trailingSlot()
        } else if (onClickTrailing != null) {
            IconButton(onClick = onClickTrailing) {
                Icon(trailingIcon, contentDescription = trailingContentDescription)
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
        modifier = Modifier.padding(start = 72.dp),
        color = glassBorder,
        thickness = 0.5.dp
    )
}

// ---------------------------------------------------------------------------
//  居中弹出菜单/设置面板 (玻璃拟态卡片 + 系统居中, 点遮罩或返回键关闭)
//  比 DropdownMenu 更醒目、更像独立设置页; 供「位置追踪设置」等按钮复用。
// ---------------------------------------------------------------------------
@Composable
fun CenteredPanelDialog(
    onDismissRequest: () -> Unit,
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val glass = if (isDark) Color(0xF21E293B) else Color(0xF2FFFFFF)
    val stroke = if (isDark) Color(0x44FFFFFF) else Color(0x14000000)
    val titleColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF64748B)

    // usePlatformDefaultWidth=false → 突破平台默认窄宽度, fillMaxWidth() 拉满 (与净化面板一致)
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = glass,
            border = BorderStroke(1.dp, stroke),
            shadowElevation = 16.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(title, color = titleColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, color = subColor, fontSize = 12.sp)
                }
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  位置追踪弹出菜单内容: 「手动位置追踪」开关
//   开启后进入个人主页不再自动后台解算, 只有点击「追踪」按钮才发起解算。
//   读写同一个 switch_track_manual 配置键 (与 TrackHook 跨进程同步)。
//   文案/颜色跟随系统深浅色, 在居中面板内显示清晰。
// ---------------------------------------------------------------------------
@Composable
private fun TrackManualMenuContent() {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val titleColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF64748B)
    val rowBg = if (isDark) Color.Black.copy(alpha = 0.25f) else Color(0xFF0F172A).copy(alpha = 0.04f)

    var manual by rememberConfigBoolean("switch_track_manual", false)
    val haptic = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = rowBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    manual = !manual
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("手动位置追踪", color = titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (manual)
                        "已开启：进入主页不自动解算，点击「追踪」按钮才发起解算"
                    else
                        "已关闭：进入主页即自动后台解算目标位置",
                    color = subColor,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = manual,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    manual = it
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
}

// ---------------------------------------------------------------------------
//  宿主内工具对话框 (架构 b: Compose 全屏对话框)
// ---------------------------------------------------------------------------
//  阶段 2 完成: HostToolDialog / HostToolDialogHost / HostToolPlaceholder 中间层已移除。
//  所有工具按钮直接弹 llhook Compose 全屏页 (showHostComposeScreen + XxxScreen),
//  无需 Dialog 状态机。
