package bxxd.hook

import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object NetworkSpoofHook : BaseHook {

    // 🚀 全局共享变量：用来偷偷保存改造后的极速版 UA，供 ChatSpyHook 透视模块复用
    @Volatile
    var capturedLatestUA: String = ""

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val requestBuilderClass = lpparam.classLoader.loadClass("okhttp3.Request\$Builder")

            val hookLogic = { param: XC_MethodHook.MethodHookParam ->
                val key = param.args[0] as? String
                val value = param.args[1] as? String
                
                if (key != null && value != null) {
                    if (key.equals("User-Agent", ignoreCase = true) || key.equals("ua", ignoreCase = true)) {
                        var finalUa = value
                        
                        // 🚀 核心逻辑：检测到是普通版的请求
                        if (value.contains("app/1")) {
                            
                            // 1. 把结尾的 app/1 替换成极速版的 app/7
                            finalUa = finalUa.replace("app/1", "app/7")
                            
                            // 强制替换成你图一抓到的纯正极速版版本号
                            val versionRegex = Regex("Android/[\\d_\\.]+")
                            finalUa = finalUa.replace(versionRegex, "Android/070647_7.64.7_2842_0221")
                            
                            param.args[1] = finalUa
                            Log.d("替换成极速版已生效")
                        }
                        
                        // 关键步骤：把这串完美的极速版 UA 存下来！
                        if (finalUa.contains("app/7")) {
                            capturedLatestUA = finalUa
                        }
                    }
                }
            }

            // 监听 OkHttp 的请求头设置方法
            requestBuilderClass.findMethod { name == "header" && parameterTypes.size == 2 }.hookBefore { hookLogic(it) }
            requestBuilderClass.findMethod { name == "addHeader" && parameterTypes.size == 2 }.hookBefore { hookLogic(it) }

            Log.d("【蓝蓝hook】动态网络伪装（正则全套换皮版）已部署！")

        } catch (t: Throwable) {
            Log.e("【蓝蓝hook】网络请求头伪装模块加载失败", t)
        }
    }
}
