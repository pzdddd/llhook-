package bxxd.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object VipHook : BaseHook {
    
    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // ==========================================
        // 1. 内存注入：安全地只让你自己变成本地 VIP，提供基础特权标识
        // ==========================================
        try {
            val userInfoClass = XposedHelpers.findClassIfExists(
                "com.blued.android.module.common.user.model.UserInfo", 
                lpparam.classLoader
            )
            if (userInfoClass != null) {
                XposedBridge.hookAllMethods(userInfoClass, "getLoginUserInfo", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val loginResult = param.result ?: return
                        try {
                            if (Config.isFeatureEnabled("switch_local_vip")) {
                                XposedHelpers.setIntField(loginResult, "vip_grade", 2)
                                XposedHelpers.setIntField(loginResult, "is_vip_annual", 1)
                                XposedHelpers.setIntField(loginResult, "vip_exp_lvl", 8)
                                XposedHelpers.setIntField(loginResult, "vbadge", 4)
                            }
                        } catch (e: Throwable) {}
                    }
                })
            }
        } catch (e: Throwable) {}

        // ==========================================
        // 2. Gson 拦截：仅保留绝对安全的筛选权限（已彻底删除距离、时间和等级的替换）
        // ==========================================
        val gsonHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val jsonStr = param.args[0] as? String ?: return
                if (!Config.isFeatureEnabled("switch_local_vip")) return
                
                var modified = jsonStr
                
                // 仅保留最基础、绝对不会污染别人数据的权限锁
                val safeFilterKeys = listOf(
                    "is_advanced_filter", "is_vip_filter", "is_filter_vip", 
                    "is_show_vip_page", "is_filter_ads"
                )
                
                var changed = false
                for (key in safeFilterKeys) {
                    if (modified.contains("\"$key\":0") || modified.contains("\"$key\":\"0\"")) {
                        modified = modified.replace("\"$key\"\\s*:\\s*0".toRegex(), "\"$key\":1")
                        modified = modified.replace("\"$key\"\\s*:\\s*\"0\"".toRegex(), "\"$key\":\"1\"")
                        changed = true
                    }
                }

                // 只有发生了安全替换时才修改参数，最大程度保证性能和稳定
                if (changed) {
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
    }
}
