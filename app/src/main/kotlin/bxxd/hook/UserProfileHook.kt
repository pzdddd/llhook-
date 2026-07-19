package bxxd.hook // 保持你的包名不变

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONObject

object UserProfileHook {

    fun Init(lpparam: LoadPackageParam) {
        if (!lpparam.packageName.contains("blued")) return

        // 满血版 UA
        val targetUA = "Mozilla/5.0 (Linux; U; Android 13; 22081212C Build/TKQ1.220829.002) Android/300237_0.23.7_2842_0221 (Asia/Shanghai) Dalvik/2.1.0 app/7"
        // 缩短关键字，防止 URL 带有随机参数导致匹配失败
        val targetUrlKeyword = "users/95207747" 

        XposedBridge.log("🔵 [蓝蓝Hook] 启动终极抓取与强制解密引擎...")

        // ==========================================
        // 1. UA 伪装 (绕过风控)
        // ==========================================
        try {
            XposedHelpers.findAndHookMethod(
                "okhttp3.Request\$Builder", lpparam.classLoader, "header",
                String::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if ((param.args[0] as? String).equals("User-Agent", ignoreCase = true)) {
                            param.args[1] = targetUA
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("🔵 UA伪装异常: ${e.message}")
        }

        // ==========================================
        // 2. 拦截原始响应并【当场强制解密】
        // ==========================================
        try {
            val bClass = XposedHelpers.findClass("com.blued.android.http.encode.utils.b", lpparam.classLoader)
            val cClass = XposedHelpers.findClass("com.blued.android.http.encode.utils.c", lpparam.classLoader)

            XposedBridge.hookAllMethods(bClass, "I111I1lI1I1", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size == 3 && param.args[0] is String && param.args[2] is String) {
                        val url = param.args[0] as String
                        val rawJson = param.args[2] as String

                        if (url.contains(targetUrlKeyword)) {
                            XposedBridge.log("====== 🎯 捕获目标接口 ======")
                            XposedBridge.log("👉 URL: $url")

                            try {
                                val jsonObject = JSONObject(rawJson)
                                // 判断服务器是否返回了加密数据
                                if (jsonObject.has("en_data")) {
                                    val enData = jsonObject.getString("en_data")
                                    
                                    // 核心杀招：反射去 b.java 内存里掏出当前协商好的 AES 密钥
                                    val secretKey = XposedHelpers.getStaticObjectField(bClass, "l1l1l1l1") as? ByteArray
                                    
                                    if (secretKey != null) {
                                        // 密钥存在！主动呼叫 c.java 的核心解密算法
                                        val plainText = XposedHelpers.callStaticMethod(cClass, "I111I1lI1I1", enData, secretKey, url) as? String
                                        XposedBridge.log("✅ 【强制解密成功】: $plainText")
                                    } else {
                                        // 没密钥解不了，提示冷启动
                                        XposedBridge.log("❌ 【解密失败】: 内存中未找到密钥(l1l1l1l1为空)！")
                                        XposedBridge.log("⚠️ 请去手机设置 -> 应用管理 -> 强行停止蓝蓝，然后重新打开让它重新握手！")
                                    }
                                } else {
                                    // 如果服务器大发慈悲没加密，直接打印
                                    XposedBridge.log("ℹ️ 响应未加密(明文): $rawJson")
                                }
                            } catch (e: Exception) {
                                XposedBridge.log("⚠️ JSON解析或解密出错: ${e.message}")
                            }
                            XposedBridge.log("===============================")
                        } // 👈 补上 url.contains 的大括号
                    } // 👈 补上 if args.size 的大括号
                } 
            }) // 👈 完美闭合 hookAllMethods
        } catch (e: Throwable) {
            XposedBridge.log("🔵 拦截器挂载失败: ${e.message}")
        }
    }
}
