package bxxd.hook

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object ChatWatermarkHook : BaseHook {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ================= 核心：数据寄存器 (内存缓存) =================
    // 随 App 进程生灭，退出杀后台自动销毁。只保存被隐藏的用户真实数据。
    data class UserLeakedInfo(val distance: Double, val lastOperate: Long)
    private val leakedDataCache = ConcurrentHashMap<String, UserLeakedInfo>()

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // 1. 全局拦截 Gson 解析，充当雷达将隐身用户的真实数据截获进寄存器
        val gsonHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val jsonStr = param.args[0] as? String ?: return
                
                // 【极速过滤】：只处理大厅列表等包含隐身用户的 JSON，屏蔽其余 99% 的无关请求，零性能损耗
                if (jsonStr.contains("\"is_hide_distance\":1") || jsonStr.contains("\"is_hide_last_operate\":1")) {
                    thread {
                        try {
                            val jsonObject = JSONObject(jsonStr)
                            val dataArray = jsonObject.optJSONArray("data")
                            if (dataArray != null) {
                                for (i in 0 until dataArray.length()) {
                                    val userObj = dataArray.optJSONObject(i) ?: continue
                                    val uid = userObj.optString("uid", "")
                                    if (uid.isEmpty()) continue

                                    val hideDist = userObj.optInt("is_hide_distance", 0) == 1
                                    val hideTime = userObj.optInt("is_hide_last_operate", 0) == 1

                                    // 【条件过滤】只把开启了隐藏的人扔进寄存器
                                    if (hideDist || hideTime) {
                                        val dist = userObj.optDouble("distance", -1.0)
                                        val op = userObj.optLong("last_operate", 0L)
                                        
                                        // 提取寄存器里的旧数据做对比，保留最新且有效的真实数据
                                        val existing = leakedDataCache[uid]
                                        val bestDist = if (dist > 0.001 && dist != 99999.0) dist else (existing?.distance ?: dist)
                                        val bestOp = if (op > 1000000000L) op else (existing?.lastOperate ?: op)

                                        leakedDataCache[uid] = UserLeakedInfo(bestDist, bestOp)
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            // 忽略解析异常，不影响主线程
                        }
                    }
                }
            }
        }
        
        try {
            val gsonClass = XposedHelpers.findClassIfExists("com.google.gson.Gson", lpparam.classLoader)
            if (gsonClass != null) {
                XposedBridge.hookAllMethods(gsonClass, "fromJson", gsonHook)
            }
        } catch (e: Throwable) {}


        // 2. 聊天界面水印渲染逻辑
        try {
            val chatFragmentClass = lpparam.classLoader.loadClass(Config.TargetClasses.MSG_CHATTING_FRAGMENT)

            chatFragmentClass.findMethod { name == "onResume" }.hookAfter { param ->
                val fragmentInstance = param.thisObject
                val activity = XposedHelpers.callMethod(fragmentInstance, "getActivity") as? Activity ?: return@hookAfter

                val (targetUid, _) = extractUidAndName(fragmentInstance)
                if (targetUid.isEmpty() || targetUid == "0") return@hookAfter
                val token = Config.getAuthToken(activity)

                // 注入到完美测试通过的 content_layout
                val resId = activity.resources.getIdentifier("content_layout", "id", activity.packageName)
                if (resId == 0) return@hookAfter
                val rootViewContainer = activity.findViewById<ViewGroup>(resId) ?: return@hookAfter

                val tagStr = "SpyWatermark_content_layout"
                rootViewContainer.findViewWithTag<View>(tagStr)?.let { rootViewContainer.removeView(it) }

                val watermarkView = TextView(activity).apply {
                    tag = tagStr
                    textSize = 15f
                    // 颜色：浅灰色
                    setTextColor(Color.parseColor("#40888888")) 
                    // 字体：加粗
                    setTypeface(null, Typeface.BOLD) 
                    setLineSpacing(18f, 1f)
                    gravity = Gravity.CENTER
                    
                    // 彻底禁用交互事件穿透
                    isClickable = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    elevation = 0f 

                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, 
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER
                        bottomMargin = dp2px(activity, 80f)
                    }
                    text = ""
                }

                try {
                    // index 设为 0，挂在最底层，当做列表的底层水印，不覆盖聊天气泡
                    rootViewContainer.addView(watermarkView, 0)
                } catch (e: Throwable) {}

                // 开始请求并填充数据
                if (token.isNotEmpty()) {
                    thread { fetchAndSetWatermarkInfo(watermarkView, targetUid, token) }
                }
            }
        } catch (e: Throwable) {}
    }

    private fun fetchAndSetWatermarkInfo(textView: TextView, uid: String, token: String) {
        try {
            val jsonResponse = doHttpGet("https://argo.blued.cn/users/$uid/basic", token)
            val dataObj = jsonResponse?.optJSONArray("data")?.optJSONObject(0)

            if (dataObj != null) {
                // 基础资料提取
                val age = dataObj.optInt("age", 0)
                val height = dataObj.optInt("height", 0)
                val weight = dataObj.optInt("weight", 0)
                
                var role = dataObj.optString("role", "未知")
                role = when (role) { 
                    "-1", "-1.0" -> "其他"; "1", "1.0" -> "1"; "-2", "-2.0" -> "side"; 
                    "0", "0.0" -> "0"; "0.5" -> "0.5"; "0.75" -> "0.75"; 
                    "0.25" -> "0.25"; "~" -> "保密"; else -> role 
                }

                // ============ 核心：尝试从内部寄存器提取该用户 ============
                val cachedInfo = leakedDataCache[uid]

                // ============ 距离处理 ============
                val hideDist = dataObj.optInt("is_hide_distance", 0) == 1
                val apiDistance = dataObj.optDouble("distance", -1.0)
                var distanceStr = "未知"

                if (cachedInfo != null && cachedInfo.distance > 0.001) {
                    // 寄存器里有缓存距离，直接使用并打上破译标记
                    distanceStr = "${cachedInfo.distance}km (破译)"
                } else if (!hideDist && apiDistance >= 0) {
                    // 对方没隐藏，接口给了数据
                    distanceStr = "${apiDistance}km"
                } else if (hideDist) {
                    // 隐藏了且雷达没扫到
                    distanceStr = "未找到"
                }

                // ============ 在线时间处理 ============
                val lastLoginTs = dataObj.optLong("last_login", 0L)
                val hideLastOperate = dataObj.optInt("is_hide_last_operate", 0) == 1
                val apiLastOperateTs = dataObj.optLong("last_operate", 0L)
                
                // 优先取寄存器里的时间，没有再用接口的
                val bestOperateTs = if (cachedInfo != null && cachedInfo.lastOperate > 1000000000L) {
                    cachedInfo.lastOperate
                } else {
                    apiLastOperateTs
                }

                val activeTs = if (bestOperateTs > 0) bestOperateTs else lastLoginTs
                val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                var timeStr = if (activeTs > 1000000000L) sdf.format(Date(activeTs * 1000)) else "未知"

                // 追加状态提示
                if (hideLastOperate) {
                    if (cachedInfo != null && cachedInfo.lastOperate > 0) {
                        timeStr += " (保密)"
                    } else {
                        timeStr += " (保密)"
                    }
                }

                val displayText = "年龄：$age\n" +
                                  "身高：$height\n" +
                                  "体重：$weight\n" +
                                  "型号：$role\n" +
                                  "距离：$distanceStr\n" +
                                  "最后在线：$timeStr"

                mainHandler.post { textView.text = displayText }
            } else {
                mainHandler.post { textView.text = "暂无公开资料" }
            }
        } catch (e: Throwable) {
            mainHandler.post { textView.text = "" }
        }
    }

    // ================= 网络请求与辅助函数区 =================

    private fun doHttpGet(urlStr: String, token: String): JSONObject? {
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("authorization", token)
            
            val targetUA = if (NetworkSpoofHook.capturedLatestUA.isNotEmpty()) {
                NetworkSpoofHook.capturedLatestUA
            } else {
                getDynamicUniversalUserAgent() 
            }
            
            conn.setRequestProperty("user-agent", targetUA)
            conn.connectTimeout = 5000
            if (conn.responseCode == 200) {
                return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            }
        } catch (e: Throwable) {}
        return null
    }

    private fun getDynamicUniversalUserAgent(): String {
        val release = android.os.Build.VERSION.RELEASE ?: "13"
        val model = android.os.Build.MODEL ?: "Unknown"
        val id = android.os.Build.ID ?: "Unknown"
        return "Mozilla/5.0 (Linux; U; Android $release; $model Build/$id) Android/070647_7.64.7_2842_0221 (Asia/Shanghai) Dalvik/2.1.0 app/7"
    }

    private fun extractUidAndName(fragmentInstance: Any): Pair<String, String> {
        var targetUid = ""; var targetName = "未命名"
        try {
            val bundle = XposedHelpers.callMethod(fragmentInstance, "getArguments") as? Bundle
            if (bundle != null) {
                for (key in listOf("passby_session_id", "sessionId", "session_id", "uid", "target_uid")) {
                    val value = bundle.get(key)?.toString()
                    if (!value.isNullOrEmpty() && value != "0" && value.matches(Regex("\\d+"))) { targetUid = value; break }
                }
                if (targetUid.isEmpty() || targetUid == "0") {
                    for (key in bundle.keySet()) {
                        val value = bundle.get(key)?.toString() ?: ""
                        if (value.matches(Regex("\\d{5,}"))) { targetUid = value; break }
                    }
                }
                targetName = bundle.getString("passby_nick_name") ?: bundle.getString("session_name") ?: bundle.getString("name") ?: "神秘人"
            }
        } catch (e: Throwable) {}
        return Pair(targetUid, targetName)
    }

    private fun dp2px(activity: Activity, dp: Float): Int = (dp * activity.resources.displayMetrics.density + 0.5f).toInt()
}
