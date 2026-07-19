package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    // 供 Xposed 自检 hook (com.example.hook.bxxd.MainHook) 反射调用, 返回 true 表示模块已激活
    @Suppress("unused")
    fun isModuleActive(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                // 桌面图标入口: inHost=false, 仅渲染设置界面 (工具入口在 Blued 内悬浮球才显示)
                MainScreen(inHost = false)
            }
        }
    }
}
