package bxxd.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.Outline
import android.util.TypedValue
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.*

object TrackHook : BaseHook {

    private val mainHandler = Handler(Looper.getMainLooper())
    
    data class TrackResult(
        val success: Boolean, 
        val msg: String, 
        val lat: Double, 
        val lng: Double,
        var exactLat: Double? = null,
        var exactLng: Double? = null
    )
    
    data class TargetInfo(val dist: Double, val isHidden: Boolean, val location: String)

    private val trackCache = ConcurrentHashMap<String, TrackResult>()
    private var backupLat: Double = 0.0
    private var backupLng: Double = 0.0
    private var isTrackingMapOpened = false

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // 拦截地图底层构造，防混淆变量导致经纬度对调掉海里
        try {
            val amapLatLng = lpparam.classLoader.loadClass("com.amap.api.maps.model.LatLng")
            XposedBridge.hookAllConstructors(amapLatLng, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size >= 2) {
                        val lat = param.args[0] as? Double ?: return
                        val lon = param.args[1] as? Double ?: return
                        if (abs(lat) > 90.0 && abs(lon) <= 90.0) {
                            param.args[0] = lon
                            param.args[1] = lat
                        }
                    }
                }
            })
        } catch(e: Throwable) {}

        // 【完整保留】关闭地图界面时的还原与清理逻辑
        try {
            val terminalActivityClass = lpparam.classLoader.loadClass("com.blued.android.core.ui.TerminalActivity")
            terminalActivityClass.findMethod { name == "finish" }.hookBefore { param ->
                if (isTrackingMapOpened) {
                    isTrackingMapOpened = false 
                    val activity = param.thisObject as Activity
                    val classLoader = activity.classLoader
                    
                    try {
                        val managerClass = XposedHelpers.findClass("com.soft.blued.ui.find.manager.MapFindManager", classLoader)
                        val managerInstance = XposedHelpers.callStaticMethod(managerClass, "a") 
                        val beanClass = XposedHelpers.findClass("com.soft.blued.ui.find.manager.MapFindManager\$MapFindBean", classLoader)
                        
                        for (f in managerClass.declaredFields) {
                            f.isAccessible = true
                            if (f.type == beanClass) {
                                f.set(managerInstance, null) 
                            } else if (f.type == Boolean::class.java || f.type == Boolean::class.javaPrimitiveType) {
                                f.set(managerInstance, false) 
                            }
                        }
                    } catch (e: Throwable) { e.printStackTrace() }

                    try {
                        val liveEventBusClass = XposedHelpers.findClass("com.jeremyliao.liveeventbus.LiveEventBus", classLoader)
                        val observable = XposedHelpers.callStaticMethod(liveEventBusClass, "get", "map_find_click")
                        XposedHelpers.callMethod(observable, "post", false)
                    } catch(e: Throwable){}
                    
                    if (backupLat != 0.0 && backupLng != 0.0) {
                        val token = Config.getAuthToken(activity)
                        if (token.isNotEmpty()) {
                            thread {
                                try {
                                    val roamUrl = URL("https://argo.blued.cn/users/roam")
                                    val roamConn = roamUrl.openConnection() as HttpURLConnection
                                    roamConn.requestMethod = "DELETE"
                                    roamConn.setRequestProperty("authorization", token)
                                    roamConn.setRequestProperty("user-agent", "Mozilla/5.0 (Linux; U; Android 13; ...) Android/300237_0.23.7_2842_0221 app/1")
                                    roamConn.connectTimeout = 3000
                                    roamConn.inputStream.bufferedReader().readText()
                                } catch(e: Throwable){}
                                
                                updateMyServerLocation(token, backupLat, backupLng)
                            }
                        }
                        
                        try {
                            val userInfoClass = XposedHelpers.findClassIfExists("com.blued.android.module.common.user.model.UserInfo", classLoader)
                            if (userInfoClass != null) {
                                val loginUser = XposedHelpers.callMethod(XposedHelpers.callStaticMethod(userInfoClass, "getInstance"), "getLoginUserInfo")
                                XposedHelpers.setObjectField(loginUser, "lat", backupLat.toString())
                                XposedHelpers.setObjectField(loginUser, "lon", backupLng.toString())
                            }
                        } catch(e: Throwable){}
                    }
                }
            }
        } catch (e: Throwable) {}

        // 【完整保留】个人主页注入逻辑
        try {
            val fragmentClass = lpparam.classLoader.loadClass(Config.TargetClasses.USER_INFO_FRAGMENT_NEW)

            fragmentClass.findMethod { name == "onResume" }.hookAfter { param ->
                val fragmentInstance = param.thisObject
                val activity = XposedHelpers.callMethod(fragmentInstance, "getActivity") as? Activity ?: return@hookAfter
                
                // 🛑 第一道拦截：功能总开关
                if (!Config.isFeatureEnabled("switch_track", activity)) return@hookAfter
                
                // 🛑 核心策略分离：如果未开启虚拟定位，TrackHook 退居幕后，让 RealLocationHook 接管！
                if (!Config.isFeatureEnabled("switch_virtual_location", activity)) {
                    return@hookAfter
                }

                // 注入到 fl_all (fragment 根), 和聊天按钮同一容器
                val fragView = XposedHelpers.callMethod(fragmentInstance, "getView") as? ViewGroup
                val flAllId = activity.resources.getIdentifier("fl_all", "id", activity.packageName)
                val rootLayout = fragView
                    ?: (if (flAllId != 0) activity.findViewById<ViewGroup>(flAllId) else null)
                    ?: activity.findViewById<ViewGroup>(android.R.id.content) ?: return@hookAfter
                rootLayout.findViewWithTag<View>("TrackBtn")?.let { rootLayout.removeView(it) }

                var currentTargetUid: String? = null

                val btnSize = dp(activity, 56f)
                val trackBtn = TextView(activity).apply {
                    tag = "TrackBtn"
                    text = "追踪"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(activity, 18f), 0, dp(activity, 18f))
                    // 圆形绿色背景
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#4CAF50"))
                    }
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                    clipToOutline = true
                    elevation = dp(activity, 8f).toFloat()
                    setPadding(0, dp(activity, 8f), 0, dp(activity, 8f))
                    // 右下角, 聊天按钮上方 (聊天按钮 bottomMargin=90, 56dp高, 间距16 → 162)
                    layoutParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                        rightMargin = dp(activity, 18f)
                        bottomMargin = dp(activity, 162f)
                    }

                    // 【完整保留】短按点击逻辑
                    setOnClickListener {
                        val uid = currentTargetUid
                        if (uid != null) {
                            val res = trackCache[uid]
                            if (res != null) {
                                val showLat = res.exactLat ?: res.lat
                                val showLng = res.exactLng ?: res.lng
                                showResult(activity, res.success, res.msg, showLat, showLng)
                            } else {
                                Toast.makeText(activity, "雷达正在全速解算中，请稍候...", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(activity, "正在初始化数据...", Toast.LENGTH_SHORT).show()
                        }
                    }

                    // 【完整保留】长按空降地图底层注入逻辑
                    setOnLongClickListener {
                        val uid = currentTargetUid
                        if (uid != null) {
                            val res = trackCache[uid]
                            if (res != null && res.success) {
                                val finalLat = res.exactLat ?: res.lat
                                val finalLng = res.exactLng ?: res.lng
                                val isExact = res.exactLat != null

                                try {
                                    val userInfoClass = XposedHelpers.findClassIfExists("com.blued.android.module.common.user.model.UserInfo", activity.classLoader)
                                    if (userInfoClass != null) {
                                        val loginUser = XposedHelpers.callMethod(XposedHelpers.callStaticMethod(userInfoClass, "getInstance"), "getLoginUserInfo")
                                        backupLat = XposedHelpers.getObjectField(loginUser, "lat")?.toString()?.toDoubleOrNull() ?: 0.0
                                        backupLng = XposedHelpers.getObjectField(loginUser, "lon")?.toString()?.toDoubleOrNull() ?: 0.0
                                    }
                                    if (backupLat == 0.0) backupLat = Config.getCustomLat(activity)
                                    if (backupLng == 0.0) backupLng = Config.getCustomLng(activity)
                                } catch(e: Throwable){}

                                thread {
                                    try {
                                        val token = Config.getAuthToken(activity)
                                        if (token.isNotEmpty()) {
                                            updateMyServerLocation(token, finalLat, finalLng)
                                        }
                                        
                                        mainHandler.post {
                                            try {
                                                isTrackingMapOpened = true 
                                                val classLoader = activity.classLoader

                                                if (isExact) {
                                                    Toast.makeText(activity, "定位成功", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(activity, "正在定位", Toast.LENGTH_SHORT).show()
                                                }

                                                val managerClass = XposedHelpers.findClass("com.soft.blued.ui.find.manager.MapFindManager", classLoader)
                                                val beanClass = XposedHelpers.findClass("com.soft.blued.ui.find.manager.MapFindManager\$MapFindBean", classLoader)
                                                
                                                val beanInstance = beanClass.newInstance()
                                                
                                                var latInjected = false
                                                var lngInjected = false
                                                for (f in beanClass.declaredFields) {
                                                    f.isAccessible = true
                                                    val name = f.name.lowercase()
                                                    if (f.type == String::class.java) {
                                                        if (name == "longitude" || name == "lon" || name == "lng") {
                                                            f.set(beanInstance, finalLng.toString())
                                                            lngInjected = true
                                                        } else if (name == "latitude" || name == "lat") {
                                                            f.set(beanInstance, finalLat.toString())
                                                            latInjected = true
                                                        }
                                                    }
                                                }
                                                
                                                if (!latInjected || !lngInjected) {
                                                    try { XposedHelpers.setObjectField(beanInstance, "a", finalLng.toString()) } catch(e:Throwable){}
                                                    try { XposedHelpers.setObjectField(beanInstance, "b", finalLat.toString()) } catch(e:Throwable){}
                                                }
                                                
                                                try { XposedHelpers.setObjectField(beanInstance, "d", "目标精准坐标") } catch(e:Throwable){}
                                                try { XposedHelpers.setObjectField(beanInstance, "c", 0.0) } catch(e:Throwable){}      
                                                
                                                val managerInstance = XposedHelpers.callStaticMethod(managerClass, "a")
                                                XposedHelpers.callMethod(managerInstance, "a", beanInstance)
                                                
                                                val terminalActivityClass = XposedHelpers.findClass("com.blued.android.core.ui.TerminalActivity", classLoader)
                                                val mapFragmentClass = XposedHelpers.findClass("com.soft.blued.ui.find.fragment.FindSearchMapFragment", classLoader)
                                                
                                                val bundle = Bundle().apply {
                                                    putInt("from_page", 2)
                                                    putInt("find_map_tab", 0)
                                                    putBoolean("is_map_find", true)
                                                }
                                                
                                                XposedHelpers.callStaticMethod(terminalActivityClass, "d", activity, mapFragmentClass, bundle)
                                            } catch (e: Exception) {
                                                Toast.makeText(activity, "调用官方地图失败: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } catch (e: Exception) {}
                                }
                            } else {
                                Toast.makeText(activity, "解算中，请稍后再试", Toast.LENGTH_SHORT).show()
                            }
                        }
                        true 
                    }
                }
                rootLayout.addView(trackBtn)

                var animator: android.animation.ObjectAnimator? = null
                mainHandler.post {
                    animator = android.animation.ObjectAnimator.ofFloat(trackBtn, "rotation", 0f, 360f).apply {
                        duration = 800
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        interpolator = android.view.animation.LinearInterpolator()
                        start()
                    }
                }

                thread {
                    var uidFound: String? = null
                    for (i in 0 until 5) {
                        uidFound = extractUid(fragmentInstance)
                        if (!uidFound.isNullOrEmpty()) break
                        Thread.sleep(50)
                    }

                    if (uidFound.isNullOrEmpty()) return@thread
                    currentTargetUid = uidFound

                    val token = Config.getAuthToken(activity)
                    if (token.isEmpty()) return@thread
                    if (trackCache.containsKey(uidFound)) return@thread

                    // 因为有上方的拦截，能走到这里一定开启了虚拟定位，直接使用 Custom 坐标
                    val initialLat = Config.getCustomLat(activity)
                    val initialLng = Config.getCustomLng(activity)
                    val hasValidLocation = initialLat != 0.0 && initialLng != 0.0
                    
                    if (!hasValidLocation) return@thread

                    val info = getInitialDistanceInfo(uidFound, token)
                    var initialDist = info.dist
                    var isHidden = info.isHidden
                    val locationStr = info.location

                    if (initialDist < 0 || initialDist >= 9999.0) {
                        val cachedInfo = ChatSpyHook.leakedDataCache[uidFound]
                        if (cachedInfo != null && cachedInfo.distance > 0.001 && cachedInfo.distance != 99999.0) {
                            initialDist = cachedInfo.distance
                            isHidden = cachedInfo.hideDist
                        }
                    }

                    if (initialDist < 0 || initialDist >= 9999.0) {
                        trackCache[uidFound] = TrackResult(false, "隐身用户暂无法定位", 0.0, 0.0)
                        return@thread
                    }

                    try {
                        // 【完整保留】原版多段跳跃逼近算法
                        val result = doMathTrackingSilent(uidFound, token, initialLat, initialLng, initialDist, isHidden)
                        trackCache[uidFound] = result

                        if (result.success) {
                            updateMyServerLocation(token, result.lat, result.lng)
                            val exactLoc = fetchExactMapLocation(uidFound, token, result.lat, result.lng)
                            if (exactLoc != null) {
                                result.exactLat = exactLoc.first
                                result.exactLng = exactLoc.second
                                trackCache[uidFound] = result.copy(msg = result.msg.replace("正在解算", "解算成功"))
                            }
                        }
                    } catch (t: Throwable) {
                        trackCache[uidFound] = TrackResult(false, "解算算法异常: ${t.message}", 0.0, 0.0)
                    } finally {
                        mainHandler.post {
                            animator?.cancel()
                            trackBtn.rotation = 0f
                            val successAnimator = android.animation.ObjectAnimator.ofFloat(trackBtn, "scaleX", 1f, 1.2f, 1f).apply { duration = 300 }
                            val successAnimatorY = android.animation.ObjectAnimator.ofFloat(trackBtn, "scaleY", 1f, 1.2f, 1f).apply { duration = 300 }
                            android.animation.AnimatorSet().apply {
                                playTogether(successAnimator, successAnimatorY)
                                start()
                            }
                            trackBtn.text = "完成"
                            trackBtn.background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor("#009688"))
                            }
                        }

                        if (hasValidLocation) {
                            updateMyServerLocation(token, initialLat, initialLng)
                        }
                    }
                }
            }

            try {
                fragmentClass.findMethod { name == "onPause" }.hookBefore { param ->
                    val fragmentInstance = param.thisObject
                    val activity = XposedHelpers.callMethod(fragmentInstance, "getActivity") as? Activity ?: return@hookBefore
                    val fragView = XposedHelpers.callMethod(param.thisObject, "getView") as? ViewGroup
                    val rootLayout = fragView ?: activity.findViewById<ViewGroup>(android.R.id.content)
                    rootLayout?.findViewWithTag<View>("TrackBtn")?.let { rootLayout.removeView(it) }
                }
            } catch (e: Throwable) {}
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dp(ctx: Context, dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.resources.displayMetrics).toInt()

    // 【完整保留】带内层保护的提取 UID 方法
    private fun extractUid(fragmentInstance: Any): String? {
        try {
            val fields = fragmentInstance.javaClass.declaredFields
            for (field in fields) {
                try {
                    field.isAccessible = true
                    val obj = field.get(fragmentInstance) ?: continue
                    val tempUid = XposedHelpers.getObjectField(obj, "uid")?.toString()
                    if (!tempUid.isNullOrEmpty()) return tempUid
                } catch (e: Throwable) {}
            }
            val bundle = XposedHelpers.callMethod(fragmentInstance, "getArguments") as? Bundle
            if (bundle != null) {
                return bundle.getString("uid") ?: bundle.getString("user_id")
            }
        } catch (e: Throwable) {}
        return null
    }

    // 【完整保留】原版所有网络请求底层方法
    private fun getInitialDistanceInfo(uid: String, myToken: String): TargetInfo {
        var dist = -1.0
        var isHidden = false
        var locStr = ""
        try {
            val urlString = "https://argo.blued.cn/users/$uid/basic"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("authorization", myToken)
            connection.setRequestProperty("user-agent", "Mozilla/5.0 (Linux; U; Android 13; ...) Android/300237_0.23.7_2842_0221 app/1")
            connection.connectTimeout = 3000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val dataArray = json.optJSONArray("data")
                if (dataArray != null && dataArray.length() > 0) {
                    val userData = dataArray.getJSONObject(0)
                    dist = userData.optDouble("distance", -1.0)
                    isHidden = userData.optInt("is_hide_distance", 0) == 1
                    locStr = userData.optString("location", "")
                }
            }
            
            if (locStr.isEmpty() || locStr == "保密") {
                val socialUrl = URL("https://social.blued.cn/users/$uid?from=private_chatting_photo&is_living=false&is_live_flow=1&is_vip_page=0&is_shadow=0&is_call=0")
                val socialConn = socialUrl.openConnection() as HttpURLConnection
                socialConn.requestMethod = "GET"
                socialConn.setRequestProperty("authorization", myToken)
                socialConn.setRequestProperty("user-agent", "Mozilla/5.0 (Linux; U; Android 13; ...) Android/300237_0.23.7_2842_0221 app/1")
                socialConn.connectTimeout = 3000
                
                if (socialConn.responseCode == 200) {
                    val response = socialConn.inputStream.bufferedReader().use { it.readText() }
                    val dataArray = JSONObject(response).optJSONArray("data")
                    if (dataArray != null && dataArray.length() > 0) {
                        locStr = dataArray.getJSONObject(0).optString("location", "")
                    }
                }
            }
        } catch (e: Throwable) {}
        return TargetInfo(dist, isHidden, locStr)
    }

    private fun updateMyServerLocation(token: String, lat: Double, lng: Double) {
        try {
            val url = URL("https://argo.blued.cn/users?sort_by=nearby&latitude=$lat&longitude=$lng&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("authorization", token)
            conn.setRequestProperty("user-agent", "Mozilla/5.0 (Linux; U; Android 13; ...) Android/300237_0.23.7_2842_0221 app/1")
            conn.connectTimeout = 3000
            conn.inputStream.bufferedReader().use { it.readText() } 
        } catch (e: Exception) {}
    }

    private fun fetchExactMapLocation(targetUid: String, token: String, approxLat: Double, approxLng: Double): Pair<Double, Double>? {
        try {
            val url = URL("https://social.blued.cn/users/avatar_map/index")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("authorization", token)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("user-agent", "Mozilla/5.0 (Linux; U; Android 13; ...) Android/300237_0.23.7_2842_0221 app/1")
            conn.doOutput = true
            conn.connectTimeout = 5000

            val offset = 0.02
            val latA = approxLat + offset
            val lngA = approxLng - offset
            val latB = approxLat - offset
            val lngB = approxLng + offset

            val jsonBody = JSONObject().apply {
                put("self_location", JSONObject().apply {
                    put("altitude", 0.0)
                    put("latitude", approxLat)
                    put("longitude", approxLng)
                })
                put("zoom_scale", "0.5") 
                put("a", JSONObject().apply {
                    put("altitude", 0.0)
                    put("latitude", latA)
                    put("longitude", lngA)
                })
                put("b", JSONObject().apply {
                    put("altitude", 0.0)
                    put("latitude", latB)
                    put("longitude", lngB)
                })
                put("avatar_span", "57")
            }

            conn.outputStream.use { os -> os.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonResp = JSONObject(resp)
                val dataArray = jsonResp.optJSONArray("data")
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val userObj = dataArray.optJSONObject(i) ?: continue
                        if (userObj.optString("uid") == targetUid) {
                            val loc = userObj.optJSONObject("location")
                            if (loc != null) {
                                val exactLat = loc.optString("latitude").toDoubleOrNull()
                                val exactLng = loc.optString("longitude").toDoubleOrNull()
                                if (exactLat != null && exactLng != null) return Pair(exactLat, exactLng)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun fetchDynamicDistance(uid: String, myToken: String, fakeLat: Double, fakeLng: Double, isHidden: Boolean, approxR: Double): Double {
        try {
            updateMyServerLocation(myToken, fakeLat, fakeLng)
            Thread.sleep(150) 

            if (!isHidden) {
                val url = URL("https://argo.blued.cn/users/$uid/basic")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("authorization", myToken)
                connection.setRequestProperty("user-agent", "Mozilla/5.0 (Linux; U; Android 13; ...) Android/300237_0.23.7_2842_0221 app/1")
                connection.connectTimeout = 3000

                if (connection.responseCode == 200) {
                    val json = JSONObject(connection.inputStream.bufferedReader().readText())
                    val dataArray = json.optJSONArray("data")
                    if (dataArray != null && dataArray.length() > 0) {
                        val dist = dataArray.getJSONObject(0).optDouble("distance", -1.0)
                        if (dist > 0.001) return dist
                    }
                }
            }

            val jumpDist = if (approxR > 2.0) approxR - 1.5 else 0.0
            val listUrl = URL("https://argo.blued.cn/users?sort_by=nearby&latitude=$fakeLat&longitude=$fakeLng&limit=100&next_min_dist=$jumpDist")
            val listConn = listUrl.openConnection() as HttpURLConnection
            listConn.requestMethod = "GET"
            listConn.setRequestProperty("authorization", myToken)
            listConn.setRequestProperty("user-agent", "Mozilla/5.0 (Linux; U; Android 13; ...) Android/300237_0.23.7_2842_0221 app/1")
            listConn.connectTimeout = 3000
            
            if (listConn.responseCode == 200) {
                val json = JSONObject(listConn.inputStream.bufferedReader().readText())
                val dataArray = json.optJSONArray("data")
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val userObj = dataArray.optJSONObject(i) ?: continue
                        if (userObj.optString("uid") == uid) {
                            return userObj.optDouble("distance", -1.0)
                        }
                    }
                }
            }
        } catch (t: Throwable) {}
        return -1.0 
    }

    private fun doMathTrackingSilent(uid: String, token: String, initialLat: Double, initialLng: Double, initialDist: Double, isHidden: Boolean): TrackResult {
        var currentLat = initialLat
        var currentLng = initialLng
        var currentDist = initialDist

        var loopCount = 0
        while (currentDist > 20.0 && loopCount < 2) {
            val d1 = when {
                currentDist > 1000.0 -> 50.0  
                currentDist > 500.0 -> 20.0   
                else -> 10.0     
            }

            val latN1 = currentLat + (d1 / 111.32)
            val rN1 = fetchDynamicDistance(uid, token, latN1, currentLng, isHidden, currentDist)
            if (rN1 < 0) return TrackResult(false, "解算失败", 0.0, 0.0)

            val lngE1 = currentLng + (d1 / (111.32 * cos(Math.toRadians(currentLat))))
            val rE1 = fetchDynamicDistance(uid, token, currentLat, lngE1, isHidden, currentDist)
            if (rE1 < 0) return TrackResult(false, "解算失败", 0.0, 0.0)

            val xKm1 = (currentDist * currentDist - rE1 * rE1 + d1 * d1) / (2 * d1)
            val yKm1 = (currentDist * currentDist - rN1 * rN1 + d1 * d1) / (2 * d1)

            currentLat += (yKm1 / 111.32)
            currentLng += (xKm1 / (111.32 * cos(Math.toRadians(currentLat))))

            val newDist = fetchDynamicDistance(uid, token, currentLat, currentLng, isHidden, 0.0)
            if (newDist < 0) return TrackResult(false, "解算失败", 0.0, 0.0)
            
            currentDist = newDist
            loopCount++
        }

        if (currentDist < 0.01) {
            val prefix = if (isHidden) "正在解算\n" else ""
            return TrackResult(true, "${prefix}解算成功\n\n纬度: $currentLat\n经度: $currentLng\n\n误差: 仅 ${currentDist}km", currentLat, currentLng)
        }

        val d2 = if (currentDist > 2.0) 1.0 else (currentDist * 0.5)

        val latN2 = currentLat + (d2 / 111.32)
        val rN2 = fetchDynamicDistance(uid, token, latN2, currentLng, isHidden, currentDist)
        if (rN2 < 0) return TrackResult(false, "解算失败(北向)", 0.0, 0.0)

        val lngE2 = currentLng + (d2 / (111.32 * cos(Math.toRadians(currentLat))))
        val rE2 = fetchDynamicDistance(uid, token, currentLat, lngE2, isHidden, currentDist)
        if (rE2 < 0) return TrackResult(false, "解算失败(东向)", 0.0, 0.0)

        val finalX = (currentDist * currentDist - rE2 * rE2 + d2 * d2) / (2 * d2)
        val finalY = (currentDist * currentDist - rN2 * rN2 + d2 * d2) / (2 * d2)

        val targetLat = currentLat + (finalY / 111.32)
        val targetLng = currentLng + (finalX / (111.32 * cos(Math.toRadians(currentLat))))

        val totalOffsetX = (targetLng - initialLng) * 111.32 * cos(Math.toRadians(initialLat))
        val totalOffsetY = (targetLat - initialLat) * 111.32

        var distanceStr = ""
        distanceStr += if (totalOffsetX > 0) "向东偏: ${String.format("%.0f", totalOffsetX * 1000)}米\n" else "向西偏: ${String.format("%.0f", -totalOffsetX * 1000)}米\n"
        distanceStr += if (totalOffsetY > 0) "向北偏: ${String.format("%.0f", totalOffsetY * 1000)}米" else "向南偏: ${String.format("%.0f", -totalOffsetY * 1000)}米"

        val prefix = if (isHidden) "正在解算\n" else ""
        return TrackResult(true, "${prefix}解算完成！\n\n纬度: $targetLat\n经度: $targetLng\n\n[相对你的距离]\n$distanceStr", targetLat, targetLng)
    }

    private fun showResult(activity: Activity, success: Boolean, msg: String, lat: Double = 0.0, lng: Double = 0.0) {
        mainHandler.post {
            if (success) {
                val isNightMode = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val themeResId = if (isNightMode) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

                AlertDialog.Builder(activity, themeResId)
                    .setTitle("位置锁定成功")
                    .setMessage(msg)
                    .setPositiveButton("前往外置地图") { _, _ ->
                        try {
                            MapOverlay.showMap(activity, lat, lng)
                        } catch (e: Exception) {
                            Toast.makeText(activity, "外置地图唤起失败！", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNeutralButton("复制坐标") { _, _ ->
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Location", "$lat, $lng"))
                        Toast.makeText(activity, "坐标已复制", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            } else {
                Toast.makeText(activity, "追踪失败: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
