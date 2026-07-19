package bxxd.hook

import android.app.Activity
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object AutoVisitHook : BaseHook {
    
    // 猎物数据结构：新增 lastOperate（最后活跃时间戳）
    data class TargetUser(val uid: String, val name: String, val distance: Double, val lastOperate: Long)
    
    val cachedUsers = mutableMapOf<String, TargetUser>()
    var cachedToken = ""
    var cachedUserAgent = "Mozilla/5.0 (Linux; U; Android 13; Build/TKQ1.220829.002) Android/300237_0.23.7_2842_0221 app/7"
    
    @Volatile
    var isVisiting = false

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // ==========================================
        // 1. 猎物收集器：白嫖附近列表的数据 (抓取距离与活跃时间)
        // ==========================================
        val gsonHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val jsonStr = param.args[0] as? String ?: return
                // 指纹识别：交友列表
                if (jsonStr.contains("\"data\":[{") && jsonStr.contains("\"uid\"") && jsonStr.contains("\"distance\"")) {
                    try {
                        val jsonObject = JSONObject(jsonStr)
                        val dataArray = jsonObject.optJSONArray("data")
                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                val userObj = dataArray.optJSONObject(i)
                                if (userObj != null) {
                                    val uid = userObj.optString("uid", "")
                                    val name = userObj.optString("name", "")
                                    
                                    val distanceStr = userObj.optString("distance", "99999")
                                    val distance = distanceStr.toDoubleOrNull() ?: 99999.0
                                    
                                    // 提取最后的活跃时间 (有些版本是 long，有些是 string)
                                    val lastOperateStr = userObj.optString("last_operate", "0")
                                    val lastOperate = lastOperateStr.toLongOrNull() ?: userObj.optLong("last_operate", 0L)
                                    
                                    if (uid.isNotEmpty() && name.isNotEmpty()) {
                                        cachedUsers[uid] = TargetUser(uid, name, distance, lastOperate)
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {}
                }
            }
        }

        try {
            val gsonClass = XposedHelpers.findClassIfExists("com.google.gson.Gson", lpparam.classLoader)
            if (gsonClass != null) XposedBridge.hookAllMethods(gsonClass, "fromJson", gsonHook)
        } catch (e: Throwable) {}

        // ==========================================
        // 2. HTTP 头部窃取器
        // ==========================================
        val okHttpHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return
                val value = param.args[1] as? String ?: return
                if (key.equals("authorization", ignoreCase = true) && value.startsWith("Basic ")) {
                    cachedToken = value
                } else if (key.equals("user-agent", ignoreCase = true)) {
                    cachedUserAgent = value
                }
            }
        }

        try {
            val okhttpBuilderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", lpparam.classLoader)
            if (okhttpBuilderClass != null) {
                XposedBridge.hookAllMethods(okhttpBuilderClass, "header", okHttpHook)
                XposedBridge.hookAllMethods(okhttpBuilderClass, "addHeader", okHttpHook)
            }
        } catch (e: Throwable) {}
    }

    // ==========================================
    // 3. 后台访问引擎 (新增 onlineOnly 参数)
    // ==========================================
    fun startAutoVisit(activity: Activity, minDistance: Double, maxDistance: Double, delayMin: Long, delayMax: Long, maxCount: Int, onlineOnly: Boolean) {
        if (isVisiting) {
            Toast.makeText(activity, "正在疯狂访问中，请勿重复点击！", Toast.LENGTH_SHORT).show()
            return
        }
        
        val finalToken = if (cachedToken.isNotEmpty()) cachedToken else Config.getAuthToken(activity)
        if (finalToken.isEmpty()) {
            Toast.makeText(activity, "未获取到凭证！请先在大厅【下拉刷新】一次附近列表。", Toast.LENGTH_LONG).show()
            return
        }

        val currentTimeMillis = System.currentTimeMillis()

        // 核心过滤逻辑：距离 + 在线状态
        var targetList = cachedUsers.values.filter { user ->
            // 1. 距离匹配
            val distMatch = user.distance in minDistance..maxDistance
            
            // 2. 在线匹配 (如果开启了该功能)
            val onlineMatch = if (onlineOnly) {
                val opTime = user.lastOperate
                if (opTime <= 0) {
                    false // 如果隐藏了时间或者获取失败，视为不在线
                } else if (opTime > 1000000000000L) {
                    // 如果时间戳是毫秒级，计算差值是否小于等于 15 分钟 (15 * 60 * 1000)
                    (currentTimeMillis - opTime) <= 900000L
                } else {
                    // 如果时间戳是秒级，计算差值是否小于等于 15 分钟 (15 * 60)
                    (currentTimeMillis / 1000L - opTime) <= 900L
                }
            } else {
                true // 不限制在线状态
            }
            
            distMatch && onlineMatch
        }

        if (targetList.isEmpty()) {
            Toast.makeText(activity, "缓存中没有符合条件的用户！\n请在大厅多滑动刷新几下加载更多人！", Toast.LENGTH_LONG).show()
            return
        }

        // 数量限制
        if (maxCount > 0 && targetList.size > maxCount) {
            targetList = targetList.take(maxCount)
        }

        Toast.makeText(activity, "🚀 筛选出 ${targetList.size} 名猎物\n开始在后台全自动访问...", Toast.LENGTH_LONG).show()
        isVisiting = true

        thread {
            var successCount = 0
            for (user in targetList) {
                if (!isVisiting) {
                    activity.runOnUiThread { Toast.makeText(activity, "🛑 已手动停止访问。", Toast.LENGTH_SHORT).show() }
                    break
                }
                
                try {
                    val urlString = "https://social.blued.cn/users/${user.uid}?from=nearby&is_living=false&is_live_flow=1"
                    val url = URL(urlString)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Host", "social.blued.cn")
                    conn.setRequestProperty("authorization", finalToken)
                    conn.setRequestProperty("user-agent", cachedUserAgent)
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    
                    if (conn.responseCode == 200) {
                        successCount++
                    }
                    conn.disconnect()
                } catch (e: Throwable) { }
                
                if (!isVisiting) break
                
                val sleepTime = if (delayMin >= delayMax) delayMin else (delayMin..delayMax).random()
                Thread.sleep(sleepTime)
            }
            
            isVisiting = false
            activity.runOnUiThread {
                Toast.makeText(activity, "✅ 批量访问完成！成功留下访客脚印 $successCount 人。", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    fun stopAutoVisit() {
        isVisiting = false
    }
}
