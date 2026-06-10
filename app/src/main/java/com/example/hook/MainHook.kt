package com.example.hook

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        if (packageName == "com.blued.international.lite" || packageName == "com.soft.blued.lite") {
            XposedBridge.log("Blued Lite Hooking Initialized for: $packageName")
            
            // 劫持主界面的 onCreate，将我们的设置 UI 注入到它的里面，或者添加一个悬浮窗
            // 真实使用时，可以在此劫持 Activity 并弹出 Dialog 或者添加悬浮窗 (FloatView)。
            XposedHelpers.findAndHookMethod(
                "android.app.Activity", // 这里替换为 Blued 实际的 MainActivity 类名
                lpparam.classLoader,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        try {
                            // 在这里将我们的 Compose Settings 或者普通的 View 注入进去
                            // 或者触发悬浮按钮
                            XposedBridge.log("Injected Float UI into: ${activity.localClassName}")
                        } catch (e: Throwable) {
                            XposedBridge.log(e)
                        }
                    }
                }
            )
        }
    }
}
