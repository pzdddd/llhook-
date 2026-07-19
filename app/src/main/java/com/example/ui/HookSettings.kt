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
import androidx.compose.ui.viewinterop.AndroidView
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
                            // 桌面图标入口: 右上角“启动 Blued”按钮
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
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Filled.PlayArrow, "启动 Blued", tint = accentBlue, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("启动", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { -it / 2 }
                ) {
                    val hostHint = if (mineEntry) "“我的”页面模块入口·完整配置中心。"
                        else if (inHost) "悬浮球·快捷设置 (完整配置请从「我的」页面模块入口进入)。"
                        else "桌面图标进入。点击底部「重启 Blued 生效」应用改动。"
                    Text(
                        "为 Blued 极速版 / 标准版 提供清爽、沉浸的使用体验。\n$hostHint",
                        color = subTextColor,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                // ====== 基础功能 (「我的」入口与桌面图标显示, 悬浮球不显示) ======
                if (mineEntry || !inHost) AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 100)) + slideInVertically(tween(500, delayMillis = 100)) { 100 }) {
                    SettingsSection("基础功能", glassColor, glassBorder, subTextColor) {
                        var switchLite by rememberConfigBoolean("switch_lite")
                        var riskBlock by rememberConfigBoolean("switch_risk_user_block")
                        var spoofLite by rememberConfigBoolean("switch_spoof_lite")

                        SettingsSwitchItem("一键 lite (减负提速)", "精简不必要的功能和服务", Icons.Outlined.Speed,
                            switchLite, { switchLite = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("风险用户拦截", "自动识别并屏蔽风险用户", Icons.Outlined.Shield,
                            riskBlock, { riskBlock = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("属性透视", "伪装极速版网络获取更多数据", Icons.Outlined.Visibility,
                            spoofLite, { spoofLite = it }, textColor, subTextColor)
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
                        var removeDiscover by rememberConfigBoolean("purify_tab_feed", false)
                        var qqHome by rememberConfigBoolean("switch_qq_home", false)
                        var chatBtnStyle by rememberConfigBoolean("switch_chat_button_style", false)

                        SettingsSwitchItem("拦截广告 SDK 请求", "底层 OkHttp/Socket 直接断网", Icons.Outlined.Block,
                            removeAds, { removeAds = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("拦截直播请求", "屏蔽底部导航栏的直播相关请求", Icons.Outlined.Videocam,
                            removeLive, { removeLive = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("隐藏发现入口", "移除底部导航栏的发现按钮", Icons.Outlined.Explore,
                            removeDiscover, { removeDiscover = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("QQ 风格首页", "左上角圆形头像 + 右滑打开我的", Icons.Outlined.Dashboard,
                            qqHome, { qqHome = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("资料页聊天按钮改圆形右下角", "UI 净化: 圆形悬浮聊天按钮", Icons.Outlined.ChatBubbleOutline,
                            chatBtnStyle, { chatBtnStyle = it }, textColor, subTextColor)
                    }
                }

                // ====== 聊天增强 (「我的」入口与桌面图标显示) ======
                if (mineEntry || !inHost) AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 200)) + slideInVertically(tween(500, delayMillis = 200)) { 100 }) {
                    SettingsSection("聊天增强", glassColor, glassBorder, subTextColor) {
                        var preventRecall by rememberConfigBoolean("switch_anti_recall", true)
                        var flashPhoto by rememberConfigBoolean("switch_flash_photo", false)
                        var screenshot by rememberConfigBoolean("switch_screenshot", false)
                        var stealthRead by rememberConfigBoolean("switch_read_receipt", false)
                        var chatWatermark by rememberConfigBoolean("switch_chat_watermark", true)

                        SettingsSwitchItem("防撤回", "拦截并显示对方撤回的消息", Icons.Outlined.Message,
                            preventRecall, { preventRecall = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("闪照转照片", "把闪照直接当普通图片查看", Icons.Outlined.Photo,
                            flashPhoto, { flashPhoto = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("去除截屏限制", "聊天页禁止截屏时强制允许", Icons.Outlined.Screenshot,
                            screenshot, { screenshot = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("消息已读防隐身", "即使对方开启隐藏已读, 依然显示", Icons.Outlined.MarkChatRead,
                            stealthRead, { stealthRead = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("聊天页水印", "对方聊天页注入水印信息 (反截图泄露)", Icons.Outlined.BrandingWatermark,
                            chatWatermark, { chatWatermark = it }, textColor, subTextColor)
                    }
                }

                // ====== 隐私与特权 (「我的」入口与桌面图标显示) ======
                if (mineEntry || !inHost) AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 230)) + slideInVertically(tween(500, delayMillis = 230)) { 100 }) {
                    SettingsSection("隐私与特权", glassColor, glassBorder, subTextColor) {
                        var privatePhoto by rememberConfigBoolean("switch_private_photo", false)
                        var crackVip by rememberConfigBoolean("switch_local_vip", false)

                        SettingsSwitchItem("查看私密相册", "绕过私密相册权限校验", Icons.Outlined.PhotoLibrary,
                            privatePhoto, { privatePhoto = it }, textColor, subTextColor)
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
                        SettingsSwitchItem("位置追踪", "个人主页右上角显示追踪按钮 (实景雷达)", Icons.Outlined.GpsFixed,
                            locationTracking, { locationTracking = it }, textColor, subTextColor)
                    }
                }

                // ====== 消息推送 / 保活 ======
                AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 290)) + slideInVertically(tween(500, delayMillis = 290)) { 100 }) {
                    SettingsSection("消息推送 / 保活", glassColor, glassBorder, subTextColor) {
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
                    }
                }

                // ====== 风控控制 (「我的」入口与桌面图标显示) ======
                if (mineEntry || !inHost) AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 320)) + slideInVertically(tween(500, delayMillis = 320)) { 100 }) {
                    SettingsSection("风控控制\n（非必要别打开。仅登录提示风险时尝试。）", glassColor, glassBorder, subTextColor) {
                        var deviceFake by rememberConfigBoolean("switch_device_fake", false)
                        var deviceEmpty by rememberConfigBoolean("switch_device_empty", false)
                        var deviceIntercept by rememberConfigBoolean("switch_device_intercept", false)

                        SettingsSwitchItem("伪装设备指纹", "反射篡改数美 SmAntiFraud 指纹", Icons.Outlined.Fingerprint,
                            deviceFake, { deviceFake = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("清空返回值", "getDeviceId 等直接置空", Icons.Outlined.NoEncryption,
                            deviceEmpty, { deviceEmpty = it }, textColor, subTextColor)
                        SettingsDivider(glassBorder)
                        SettingsSwitchItem("拦截机器码上传", "阻断设备信息上传请求", Icons.Outlined.Block,
                            deviceIntercept, { deviceIntercept = it }, textColor, subTextColor)
                    }
                }

                // ====== 工具入口 (仅在 Blued 内悬浮球渲染时显示, 这些功能依赖宿主 Activity) ======
                if (inHost && hostActivity != null) {
                    AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 350)) + slideInVertically(tween(500, delayMillis = 350)) { 100 }) {
                        ToolsSection(hostActivity, glassColor, glassBorder, subTextColor)
                    }
                }

                // ====== 备份目录 ======
                AnimatedVisibility(visible, enter = fadeIn(tween(500, delayMillis = 380)) + slideInVertically(tween(500, delayMillis = 380)) { 100 }) {
                    BackupDirSection(glassColor, glassBorder, textColor, subTextColor)
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  净化中心 (总开关 + 逐项列表, 数据源与 AdsHook.PURIFY_ITEMS 一致)
// ---------------------------------------------------------------------------

@Composable
private fun PurifySection(
    glassColor: Color, glassBorder: Color, textColor: Color, subTextColor: Color
) {
    var expanded by remember { mutableStateOf(false) }
    val purifyItems = remember {
        bxxd.hook.AdsHook.PURIFY_ITEMS
    }

    SettingsSection("净化中心", glassColor, glassBorder, subTextColor) {
        var master by rememberConfigBoolean("switch_remove_ads", false)
        SettingsSwitchItem(
            title = "净化总开关 (全部生效)",
            subtitle = "打开则下列所有净化项一律生效; 点击整行展开逐项",
            icon = Icons.Outlined.AutoFixHigh,
            checked = master,
            onCheckedChange = { master = it },
            textColor = textColor,
            subTextColor = subTextColor,
            onClickRow = { expanded = !expanded }
        )
        SettingsDivider(glassBorder)
        if (expanded) {
            purifyItems.forEachIndexed { idx, (label, key) ->
                var on by rememberConfigBoolean(key, false)
                SettingsSwitchItem(
                    title = "└ $label",
                    subtitle = null,
                    icon = null,
                    checked = on,
                    onCheckedChange = { on = it },
                    textColor = textColor,
                    subTextColor = subTextColor
                )
                if (idx < purifyItems.size - 1) SettingsDivider(glassBorder)
            }
        }
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
        ToolRow("🔍 设备检测 (Blued 视角)", "查看 Blued 采集的设备数据与风控", Icons.Outlined.BugReport, subTextColor) {
            // 阶段 2 已完成: 直接弹 Compose 全屏检测报告页
            try { showHostComposeScreen(activity) { onClose -> DetectScreen(activity, onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "检测页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("🛡 风控用户列表", "拦截记录 / 手动收藏 / 拉黑 / 跳转", Icons.Outlined.Shield, subTextColor) {
            // 阶段 2 已完成: 直接弹 Compose 全屏风控用户列表页
            try { showHostComposeScreen(activity) { onClose -> RiskUsersScreen(activity, onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "风控列表唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("🔒 秘密相册", "闪照/照片自动入库 · 物理隐身", Icons.Outlined.PhotoLibrary, subTextColor) {
            // 阶段 2 已完成: 弹 Compose 全屏秘密相册页 (总览模式)
            try { showHostComposeScreen(activity) { onClose -> SecretAlbumScreen(activity, onClose = onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "秘密相册唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("💾 聊天备份与恢复", "备份/恢复 Blued 聊天数据库", Icons.Outlined.Backup, subTextColor) {
            // 阶段 2 已完成: 直接弹 Compose 全屏备份页
            try { showHostComposeScreen(activity) { onClose -> ChatBackupScreen(activity, onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "备份页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("🧹 净化中心 (逐项开关)", "独立控制每个净化项", Icons.Outlined.CleaningServices, subTextColor) {
            // 净化中心已在主页「净化中心」分区完整实现 (总开关+29项逐项), 此处引导用户
            Toast.makeText(activity, "净化中心请在本页「净化中心」分区配置\n(总开关 + 29 项逐项开关)", Toast.LENGTH_LONG).show()
        }
        SettingsDivider(glassBorder)
        ToolRow("🔖 坐标收藏夹管理", "批量管理 / 一键设为虚拟定位 / 导入导出", Icons.Outlined.Bookmark, subTextColor) {
            // 新功能: 独立管理地图收藏夹 (CRUD + 一键设为虚拟定位)
            try { showHostComposeScreen(activity) { onClose -> FavoritesScreen(activity, onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "收藏夹页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("⚡ 一键站街", "按距离/在线批量访问", Icons.Outlined.Bolt, subTextColor) {
            // 阶段 2 已完成: 直接弹 Compose 全屏站街配置页
            try { showHostComposeScreen(activity) { onClose -> AutoVisitScreen(activity, onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "站街页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("⛔ 停止站街", "中止正在进行的批量访问", Icons.Outlined.StopCircle, subTextColor) {
            if (AutoVisitHook.isVisiting) {
                AutoVisitHook.stopAutoVisit()
                Toast.makeText(activity, "已下达停止站街指令", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "当前未在站街", Toast.LENGTH_SHORT).show()
            }
        }
        SettingsDivider(glassBorder)
        ToolRow("🌐 网络抓包查看器", "实时记录 Blued 解密明文 API 响应", Icons.Outlined.NetworkCheck, subTextColor) {
            // 新功能: 捕获/浏览 Blued 解密后的明文 API 响应 (hook AES-GCM 解密函数)
            try { showHostComposeScreen(activity) { onClose -> NetworkCaptureScreen(activity, onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "抓包页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("🔑 凭证与运行状态", "Authorization / UA / 坐标 / API Key 查看", Icons.Outlined.VpnKey, subTextColor) {
            // 新功能: 集中展示 + 编辑所有关键运行时凭证
            try { showHostComposeScreen(activity) { onClose -> CredentialViewerScreen(activity, onClose) } }
            catch (e: Throwable) { Toast.makeText(activity, "凭证页唤起失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        SettingsDivider(glassBorder)
        ToolRow("🗑 清理角色缓存并重启", "粉碎 mmkv 角色缓存后重启宿主", Icons.Outlined.DeleteSweep, subTextColor) {
            val ok = MmkvCacheClearHook.clearMmkvCache(activity)
            Toast.makeText(activity, if (ok) "缓存已粉碎, 正在重启..." else "无旧缓存, 直接重启...", Toast.LENGTH_SHORT).show()
            FloatingUI.restartHostApp(activity)
        }
    }
}

@Composable
private fun ToolRow(
    title: String, subtitle: String?, icon: ImageVector?, subTextColor: Color, onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                Icon(icon, contentDescription = null, tint = subTextColor, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = subTextColor)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, fontSize = 13.sp, color = subTextColor.copy(alpha = 0.8f))
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = subTextColor)
    }
}

// ---------------------------------------------------------------------------
//  备份目录配置
// ---------------------------------------------------------------------------

@Composable
private fun BackupDirSection(glassColor: Color, glassBorder: Color, textColor: Color, subTextColor: Color) {
    val ctx = LocalContext.current
    var dir by rememberConfigString("backup_dir", "")

    SettingsSection("聊天数据备份目录", glassColor, glassBorder, subTextColor) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "留空 = 默认 Download/bluedbackups。保存后重启 Blued 生效。实际 备份/恢复 操作请在 Blued 内点击「工具 → 聊天备份与恢复」。",
                fontSize = 13.sp, color = subTextColor
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = dir,
                onValueChange = { dir = it },
                label = { Text("备份目录 (绝对路径)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    Config.setBackupDir(dir.trim(), ctx)
                    Toast.makeText(ctx, "已保存, 重启 Blued 后生效", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存备份目录") }
        }
    }
}

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
    onClickRow: (() -> Unit)? = null
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
                    if (onClickRow != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClickRow()
                    } else if (onClickTrailing == null) {
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
                Icon(imageVector = icon, contentDescription = null, tint = textColor, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = textColor)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, fontSize = 13.sp, color = subTextColor)
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        if (onClickTrailing != null) {
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
        modifier = Modifier.padding(start = 72.dp),
        color = glassBorder,
        thickness = 0.5.dp
    )
}

// ---------------------------------------------------------------------------
//  宿主内工具对话框 (架构 b: Compose 全屏对话框)
// ---------------------------------------------------------------------------
//  阶段 2 完成: HostToolDialog / HostToolDialogHost / HostToolPlaceholder 中间层已移除。
//  所有工具按钮直接弹 llhook Compose 全屏页 (showHostComposeScreen + XxxScreen),
//  无需 Dialog 状态机。
