package com.example.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import bxxd.hook.Config
import com.example.ui.theme.MyApplicationTheme
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * llhook 新版 Xposed 入口 (io.github.libxposed.api)。
 *
 * 职责 (与 bxxd.hook.MainHook 老版入口共存, 互不冲突):
 *  - 宿主启动时弹 Toast 提示加载成功;
 *  - 在首页注入「蓝钩」悬浮球, 点击弹出 llhook 风格的 Compose 设置界面;
 *  - 实际功能 (去广告/防撤回/虚拟定位/追踪/VIP...) 全部由 bxxd.hook.* 模块实现,
 *    经 Config + SettingsProvider + 广播在 模块进程 ↔ Blued 进程 之间同步开关。
 *
 * 注意: 定位 (虚拟定位/位置追踪) 由 bxxd.hook.VirtualLocationHook / RealLocationHook /
 *       TrackHook 统一接管, 本类不再 hook LocationManager, 避免重复 hook 互相打架。
 */
class MainHook : XposedModule() {

    private var isToastShown = false

    private fun logMsg(msg: String) {
        // Priority 4 = Log.INFO
        log(4, "MainHook", msg)
    }

    private fun logError(msg: String, tr: Throwable? = null) {
        // Priority 6 = Log.ERROR
        log(6, "MainHook", msg, tr)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        val packageName = param.packageName
        // 统一使用 Config 维护的宿主包名清单 (正式版 / 极速版 / 国际极速版 ...)
        if (!Config.isBluedPackage(packageName)) return

        logMsg("Hooking Initialized for: $packageName")

        try {
            val instrumentationClass = Class.forName("android.app.Instrumentation", false, param.defaultClassLoader)
            val method = instrumentationClass.getDeclaredMethod("callApplicationOnCreate", Application::class.java)

            hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()

                    val app = chain.args[0] as Application
                    val realClassLoader = app.classLoader

                    logMsg("Application created. Real ClassLoader obtained for: $packageName")

                    hookRealActivity(realClassLoader, param)

                    return result
                }
            })
        } catch (e: Throwable) {
            logError(e.stackTraceToString(), e)
        }
    }

    private fun hookRealActivity(classLoader: ClassLoader, param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val activityClass = Class.forName("android.app.Activity", false, classLoader)
            val method = activityClass.getDeclaredMethod("onResume")

            hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()

                    val activity = chain.thisObject as Activity
                    try {
                        if (!isToastShown) {
                            isToastShown = true
                            Toast.makeText(activity, "蓝钩加载成功", Toast.LENGTH_SHORT).show()
                            logMsg("Toast shown")
                        }

                        val className = activity.javaClass.name
                        if (className == "com.soft.blued.ui.home.HomeActivity" || className.endsWith("HomeActivity")) {
                            injectFloatButton(activity)
                        }
                    } catch (e: Throwable) {
                        logError(e.stackTraceToString(), e)
                    }

                    return result
                }
            })
        } catch (e: Throwable) {
            logError(e.stackTraceToString(), e)
        }
    }

    private fun injectFloatButton(activity: Activity) {
        // 阶段 3: 复用公共注入器 (bxxd.hook.FloatButtonInjector), 与老 API 入口逻辑完全一致
        bxxd.hook.FloatButtonInjector.inject(activity)
        logMsg("Injected Float Button into: ${activity.localClassName}")
    }
}
