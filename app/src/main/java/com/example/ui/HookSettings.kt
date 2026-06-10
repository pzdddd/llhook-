package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    
    // Liquid Glass background colors (clean and pristine iOS/MIUI style)
    val bgColors = if (isDark) {
        listOf(Color(0xFF181820), Color(0xFF0F1520), Color(0xFF0B0B0E))
    } else {
        listOf(Color(0xFFF3F6FD), Color(0xFFE8F0FE), Color(0xFFFFFFFF))
    }
    
    val bgBrush = Brush.verticalGradient(colors = bgColors)
    
    // Frosted glass appearance (MIUI / iOS translucent)
    val glassColor = if (isDark) Color(0x50252535) else Color(0xB3FFFFFF)
    val glassBorder = if (isDark) Color(0x20FFFFFF) else Color(0x80FFFFFF)
    val textColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF1C1C1E)
    val subTextColor = if (isDark) Color(0xAAFFFFFF) else Color(0x993C3C43)

    Box(modifier = Modifier.fillMaxSize().background(bgBrush)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            "极速小助手", 
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
                
                // Header Description
                Text(
                    "为 Blued 极速版 提供清爽、沉浸的使用体验。\n此界面通常通过注入方式在软件内部显示，\n当前仅为开发环境预览。",
                    color = subTextColor,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                // Section 1: 界面净化
                SettingsSection(title = "界面净化", glassColor = glassColor, glassBorder = glassBorder, titleColor = subTextColor) {
                    var removeAds by remember { mutableStateOf(true) }
                    var removeLive by remember { mutableStateOf(false) }
                    var removeDiscover by remember { mutableStateOf(false) }

                    SettingsSwitchItem(
                        title = "移除开屏广告", subtitle = "去除应用启动时的开屏广告",
                        checked = removeAds, onCheckedChange = { removeAds = it }, textColor = textColor, subTextColor = subTextColor
                    )
                    SettingsDivider(glassBorder)
                    SettingsSwitchItem(
                        title = "隐藏直播入口", subtitle = "移除底部导航栏的直播按钮",
                        checked = removeLive, onCheckedChange = { removeLive = it }, textColor = textColor, subTextColor = subTextColor
                    )
                    SettingsDivider(glassBorder)
                    SettingsSwitchItem(
                        title = "隐藏发现入口", subtitle = "移除底部导航栏的发现按钮",
                        checked = removeDiscover, onCheckedChange = { removeDiscover = it }, textColor = textColor, subTextColor = subTextColor
                    )
                }

                // Section 2: 聊天增强
                SettingsSection(title = "聊天增强", glassColor = glassColor, glassBorder = glassBorder, titleColor = subTextColor) {
                    var preventRecall by remember { mutableStateOf(true) }
                    var stealthRead by remember { mutableStateOf(false) }

                    SettingsSwitchItem(
                        title = "防撤回", subtitle = "拦截并显示对方撤回的消息",
                        checked = preventRecall, onCheckedChange = { preventRecall = it }, textColor = textColor, subTextColor = subTextColor
                    )
                    SettingsDivider(glassBorder)
                    SettingsSwitchItem(
                        title = "消息已读防隐身", subtitle = "即使对方开启隐藏已读，依然显示",
                        checked = stealthRead, onCheckedChange = { stealthRead = it }, textColor = textColor, subTextColor = subTextColor
                    )
                }

                // Section 3: 水印与杂项
                SettingsSection(title = "其他设置", glassColor = glassColor, glassBorder = glassBorder, titleColor = subTextColor) {
                    var removeWatermark by remember { mutableStateOf(true) }
                    var crackVip by remember { mutableStateOf(false) }

                    SettingsSwitchItem(
                        title = "去图片水印", subtitle = "保存图片时移除水印标识",
                        checked = removeWatermark, onCheckedChange = { removeWatermark = it }, textColor = textColor, subTextColor = subTextColor
                    )
                    SettingsDivider(glassBorder)
                    SettingsSwitchItem(
                        title = "本地 VIP (仅本地)", subtitle = "解锁本地部分VIP功能如高级筛选",
                        checked = crackVip, onCheckedChange = { crackVip = it }, textColor = textColor, subTextColor = subTextColor
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
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
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    textColor: Color,
    subTextColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 17.sp,
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF34C759), // iOS Green
                uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun SettingsDivider(glassBorder: Color) {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = glassBorder,
        thickness = 0.5.dp
    )
}
