package com.example.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    private var isToastShown = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        if (packageName == "com.blued.international.lite" || packageName == "com.soft.blued.lite" || packageName == "com.danlan.xiaolan") {
            XposedBridge.log("Hooking Initialized for: $packageName")
            
            // 为了应对加壳应用，我们劫持 Instrumentation 的 callApplicationOnCreate，
            // 此时真正的 Dex 已经解压并加载到 ClassLoader
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.Instrumentation",
                    lpparam.classLoader,
                    "callApplicationOnCreate",
                    Application::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val app = param.args[0] as Application
                            val realClassLoader = app.classLoader
                            
                            XposedBridge.log("Application created. Real ClassLoader obtained for: $packageName")
                            
                            // 在这里继续 Hook 真实的业务代码
                            hookRealActivity(realClassLoader)
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }
        }
    }
    
    private fun hookRealActivity(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                classLoader,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        try {
                            if (!isToastShown) {
                                isToastShown = true
                                Toast.makeText(activity, "加载成功", Toast.LENGTH_SHORT).show()
                                XposedBridge.log("Toast shown and injected Float UI into: ${activity.localClassName}")
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log(e)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }
    }
}
