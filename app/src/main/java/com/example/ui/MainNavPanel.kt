package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bxxd.hook.Config

/**
 * 底部导航栏美化面板 (圆角半径 / 宽度 / 边距 / 阴影)。
 *  开关: switch_main_nav_round (圆角+悬浮二合一, 在 HookSettings 列表)
 *  参数: main_nav_radius / main_nav_width / main_nav_height / main_nav_margin_b / main_nav_elevation
 */
@Composable
fun MainNavPanel(textColor: Color, subTextColor: Color) {
    val ctx = LocalContext.current

    var radius by remember { mutableStateOf((Config.getRaw("main_nav_radius", "28", ctx).toIntOrNull() ?: 28).toFloat()) }
    var width by remember { mutableStateOf((Config.getRaw("main_nav_width", "92", ctx).toIntOrNull() ?: 92).toFloat()) }
    var height by remember { mutableStateOf((Config.getRaw("main_nav_height", "50", ctx).toIntOrNull() ?: 50).toFloat()) }
    var marginB by remember { mutableStateOf((Config.getRaw("main_nav_margin_b", "10", ctx).toIntOrNull() ?: 10).toFloat()) }
    var elev by remember { mutableStateOf((Config.getRaw("main_nav_elevation", "8", ctx).toIntOrNull() ?: 8).toFloat()) }
    var savedToast by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        LabelRow("圆角半径", "${radius.toInt()} dp", textColor)
        Slider(value = radius, valueRange = 0f..60f, onValueChange = { radius = it })

        Spacer(Modifier.height(8.dp))
        LabelRow("宽度 (屏宽占比)", "${width.toInt()} %", textColor)
        Slider(value = width, valueRange = 50f..100f, onValueChange = { width = it })

        Spacer(Modifier.height(8.dp))
        LabelRow("高度", "${height.toInt()} dp", textColor)
        Slider(value = height, valueRange = 40f..80f, onValueChange = { height = it })

        Spacer(Modifier.height(8.dp))
        LabelRow("底部边距", "${marginB.toInt()} dp", textColor)
        Slider(value = marginB, valueRange = 0f..64f, onValueChange = { marginB = it })

        Spacer(Modifier.height(8.dp))
        LabelRow("抬升阴影", "${elev.toInt()} dp", textColor)
        Slider(value = elev, valueRange = 0f..24f, onValueChange = { elev = it })

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                Config.setRaw("main_nav_radius", radius.toInt().toString(), ctx)
                Config.setRaw("main_nav_width", width.toInt().toString(), ctx)
                Config.setRaw("main_nav_height", height.toInt().toString(), ctx)
                Config.setRaw("main_nav_margin_b", marginB.toInt().toString(), ctx)
                Config.setRaw("main_nav_elevation", elev.toInt().toString(), ctx)
                savedToast = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
        ) {
            Text("保存并应用", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        if (savedToast) {
            Spacer(Modifier.height(6.dp))
            Text("✓ 已保存, 重启 Blued 后生效 (切换 Tab 后会自动刷新)", color = Color(0xFF22C55E), fontSize = 12.sp)
        }
    }
}
