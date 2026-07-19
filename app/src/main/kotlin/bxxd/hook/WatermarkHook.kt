package bxxd.hook

import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject

object WatermarkHook : BaseHook {
    
    // 获取当前的开关状态（动态读取，无需重启软件即可生效）
    private fun isWatermarkRemovalEnabled(): Boolean {
        return try {
            val ctx = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), 
                "currentApplication"
            ) as? android.content.Context
            if (ctx != null) Config.isFeatureEnabled("switch_watermark", ctx) else false
        } catch (e: Throwable) { false }
    }

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // ==========================================
        // 【控制】第一层：拦截底层数据模型 
        // ==========================================
        val conditionalWatermarkHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isWatermarkRemovalEnabled()) {
                    param.result = "" // 开关打开时，直接强行返回空，去掉水印
                }
            }
        }

        val targetModels = listOf(
            "com.blued.android.module.common.user.model.UserInfoEntity",
            "com.blued.android.module.common.login.model.UserBasicModel",
            "com.blued.community.model.BluedIngSelfFeed",
            "com.blued.community.model.AlbumFlow"
        )

        for (className in targetModels) {
            try {
                val clazz = lpparam.classLoader.loadClass(className)
                for (method in clazz.declaredMethods) {
                    if (method.name.contains("WaterMark", ignoreCase = true) && method.returnType == String::class.java) {
                        XposedBridge.hookMethod(method, conditionalWatermarkHook)
                    }
                }
            } catch (e: Throwable) {}
        }

        // ==========================================
        // 【控制】第二层：底层 Bitmap 渲染拦截 (物理消音)
        // ==========================================
        try {
            val typefaceUtilsClass = lpparam.classLoader.loadClass("com.soft.blued.utils.TypefaceUtils")
            XposedBridge.hookAllMethods(typefaceUtilsClass, "a", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isWatermarkRemovalEnabled()) return
                    
                    val returnType = (param.method as? java.lang.reflect.Method)?.returnType
                    if (returnType == android.graphics.Bitmap::class.java) {
                        param.result = null // 开关打开时，图片不生成水印字
                    }
                }
            })
        } catch (e: Throwable) {}

        // ==========================================
        // 【控制】第三层：页面跳转传参拦截
        // ==========================================
        val intentBundleHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 如果开关没开，直接放行，不进行任何拦截
                if (!isWatermarkRemovalEnabled()) return

                val key = param.args[0] as? String ?: return
                
                // 1. 去水印控制
                if (key.contains("watermark", ignoreCase = true) || key.contains("water_mark", ignoreCase = true)) {
                    if (param.args.size > 1) {
                        when (param.args[1]) {
                            is String -> param.args[1] = ""
                            is Boolean -> param.args[1] = false
                            is Int -> param.args[1] = 0
                        }
                    }
                }
                
                // 2. 解除保存限制 (现在也受开关控制)
                if (key.endsWith("_ban_save", ignoreCase = true)) {
                    if (param.args.size > 1) {
                        when (param.args[1]) {
                            is String -> param.args[1] = "0"
                            is Boolean -> param.args[1] = false
                            is Int -> param.args[1] = 0
                        }
                    }
                }
            }
        }
        
        try {
            XposedBridge.hookAllMethods(Bundle::class.java, "putString", intentBundleHook)
            XposedBridge.hookAllMethods(Bundle::class.java, "putBoolean", intentBundleHook)
            XposedBridge.hookAllMethods(Bundle::class.java, "putInt", intentBundleHook)
            XposedBridge.hookAllMethods(Intent::class.java, "putExtra", intentBundleHook)
        } catch (e: Throwable) {}

        // ==========================================
        // 【控制】第四层：JSON 拦截
        // ==========================================
        val gsonHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 如果开关没开，直接放行
                if (!isWatermarkRemovalEnabled()) return

                val jsonStr = param.args[0] as? String ?: return
                var modified = jsonStr
                
                // 解除保存限制 (现在受开关控制)
                modified = modified.replace(Regex("\"([a-zA-Z0-9_]*_ban_save)\"\\s*:\\s*1"), "\"$1\":0")
                modified = modified.replace(Regex("\"([a-zA-Z0-9_]*_ban_save)\"\\s*:\\s*\"1\""), "\"$1\":\"0\"")
                modified = modified.replace(Regex("\"([a-zA-Z0-9_]*_ban_save)\"\\s*:\\s*true"), "\"$1\":false")

                // 去水印控制
                modified = modified.replace(Regex("\"([a-zA-Z0-9_]*water_?mark[a-zA-Z0-9_]*)\"\\s*:\\s*1", RegexOption.IGNORE_CASE), "\"$1\":0")
                modified = modified.replace(Regex("\"([a-zA-Z0-9_]*water_?mark[a-zA-Z0-9_]*)\"\\s*:\\s*\"1\"", RegexOption.IGNORE_CASE), "\"$1\":\"0\"")
                modified = modified.replace(Regex("\"([a-zA-Z0-9_]*water_?mark[a-zA-Z0-9_]*)\"\\s*:\\s*true", RegexOption.IGNORE_CASE), "\"$1\":false")

                if (modified != jsonStr) {
                    param.args[0] = modified
                }
            }
        }

        try {
            val gsonClass = XposedHelpers.findClassIfExists("com.google.gson.Gson", lpparam.classLoader)
            if (gsonClass != null) {
                XposedBridge.hookAllMethods(gsonClass, "fromJson", gsonHook)
            }
        } catch (e: Throwable) {}
        
        val jsonHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // 如果开关没开，直接放行
                if (!isWatermarkRemovalEnabled()) return

                val key = param.args[0] as? String ?: return
                
                val isBanSave = key.endsWith("_ban_save", ignoreCase = true)
                val isWatermark = key.contains("water_mark", ignoreCase = true) || key.contains("watermark", ignoreCase = true)

                // 此时既然进来了，说明开关已经开启，两者直接触发替换
                if (isBanSave || isWatermark) {
                    when (param.result) {
                        is Int -> if (param.result == 1) param.result = 0
                        is String -> if (param.result == "1") param.result = "0"
                        is Boolean -> if (param.result == true) param.result = false
                    }
                }
            }
        }

        try {
            XposedBridge.hookAllMethods(JSONObject::class.java, "optInt", jsonHook)
            XposedBridge.hookAllMethods(JSONObject::class.java, "getInt", jsonHook)
            XposedBridge.hookAllMethods(JSONObject::class.java, "optString", jsonHook)
            XposedBridge.hookAllMethods(JSONObject::class.java, "getString", jsonHook)
            XposedBridge.hookAllMethods(JSONObject::class.java, "optBoolean", jsonHook)
            XposedBridge.hookAllMethods(JSONObject::class.java, "getBoolean", jsonHook)
        } catch (e: Throwable) {}
    }
}
