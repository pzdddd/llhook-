package bxxd.hook

import android.app.AndroidAppHelper
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object TokenHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 找到 OkHttp 的请求构建器
            val builderClass = XposedHelpers.findClass("okhttp3.Request\$Builder", lpparam.classLoader)

            // Blued 可能会用 addHeader，也可能会用 header，我们两个都拦截！
            val hookLogic = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args[0] as? String ?: return
                    val value = param.args[1] as? String ?: return

                    if (name.equals("authorization", ignoreCase = true)) {
                        val context = AndroidAppHelper.currentApplication()
                        
                        // 获取旧的 Token
                        val oldToken = Config.getAuthToken(context)
                        
                        // 只有当 Token 发生变化时才保存（避免频繁读写耗费性能）
                        if (value.isNotEmpty() && value != oldToken) {
                            Config.setAuthToken(value, context)
                            XposedBridge.log("llhook: 成功截获并保存最新 Authorization -> ${value.take(15)}...")
                        }
                    }
                }
            }

            // 拦截 header 方法
            XposedHelpers.findAndHookMethod(builderClass, "header", String::class.java, String::class.java, hookLogic)
            // 拦截 addHeader 方法
            XposedHelpers.findAndHookMethod(builderClass, "addHeader", String::class.java, String::class.java, hookLogic)

        } catch (e: Throwable) {
            XposedBridge.log("llhook: 拦截 Token 失败 -> ${e.message}")
        }
    }
}
