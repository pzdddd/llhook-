package bxxd.hook

import android.util.Log
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.callbacks.XC_LoadPackage

object LicenseBypassHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // ==========================================
        // 🚀 1. 破解商汤科技 (SenseTime) 美颜 SDK 授权
        // ==========================================
        try {
            val stLicenseClass = lpparam.classLoader.loadClass("com.blued.android.module.external_sense_library.utils.STLicenseUtils")
            
            // 暴力通杀：遍历该类下所有返回 boolean 的方法，强行返回 true
            stLicenseClass.declaredMethods.filter { 
                it.returnType == Boolean::class.java || it.returnType == Boolean::class.javaPrimitiveType
            }.forEach { method ->
                method.hookBefore { param ->
                    param.result = true
                    Log.d("BluedHook", "🔥 成功绕过商汤 SDK 签名/License 校验: ${method.name}")
                }
            }
        } catch (e: Throwable) {
            Log.e("BluedHook", "商汤 License 绕过失败", e)
        }

        // ==========================================
        // 🚀 2. 破解七牛云 (QiNiu) 短视频 SDK 授权
        // ==========================================
        try {
            val qiniuEnvClass = lpparam.classLoader.loadClass("com.qiniu.pili.droid.shortvideo.PLShortVideoEnv")
            
            // 同样暴力通杀所有 boolean 校验方法 (如 checkAuthentication 等)
            qiniuEnvClass.declaredMethods.filter {
                it.returnType == Boolean::class.java || it.returnType == Boolean::class.javaPrimitiveType
            }.forEach { method ->
                method.hookBefore { param ->
                    param.result = true
                    Log.d("BluedHook", "🔥 成功绕过七牛云 SDK License 校验: ${method.name}")
                }
            }
        } catch (e: Throwable) {}
        
        // ==========================================
        // 🚀 3. 兜底策略：干掉 "请检查license授权" 的 Toast
        // ==========================================
        try {
            val toastClass = lpparam.classLoader.loadClass("android.widget.Toast")
            toastClass.declaredMethods.filter { it.name == "show" }.forEach { method ->
                method.hookBefore { param ->
                    val toast = param.thisObject as? android.widget.Toast
                    val view = toast?.view
                    if (view is android.widget.TextView) {
                        if (view.text.toString().contains("license")) {
                            param.result = null // 强行把这个提示给吞了
                        }
                    }
                }
            }
        } catch (e: Throwable) {}
    }
}
