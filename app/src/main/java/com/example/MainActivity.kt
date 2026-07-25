package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** 模块在 LSPosed 中是否已激活。由 MainHook 的自检 hook 在加载时翻转。 */
        @Volatile
        var moduleActivated: Boolean = false
            private set

        /** 供 Xposed 自检 hook 调用: 标记模块已激活。 */
        @JvmStatic
        fun markActivated() { moduleActivated = true }
    }

    // 供 Xposed 自检 hook (bxxd.hook.MainHook) 反射调用:
    // 未激活时返回 false (companion 默认值); 激活后被整个替换为返回 true。
    @Suppress("unused")
    fun isModuleActive(): Boolean = moduleActivated

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
