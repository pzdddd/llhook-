package bxxd.hook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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

object RealLocationHook : BaseHook {

    private val mainHandler = Handler(Looper.getMainLooper())
    data class TrackResult(val success: Boolean, val msg: String, val lat: Double, val lng: Double)
    private val trackCache = ConcurrentHashMap<String, TrackResult>()

    // 用于判断实景雷达是否唤起了官方地图，以便在退出时恢复坐标
    private var isRealTrackingMapOpened = false

    @Volatile private var sniffedLat: Double = 0.0
    @Volatile private var sniffedLng: Double = 0.0

    // 追踪期间记录的「真实坐标」, 退出官方地图时用它把服务器位置拉回原地, 防止坐标飘走。
    // (原版用 sniffedLat/Lng 还原, 但 App 走 POST 上报时 URL 嗅探不到 -> sniffed=0 -> 不还原 -> 飘走)
    @Volatile private var myRealLat: Double = 0.0
    @Volatile private var myRealLng: Double = 0.0

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // 1. 独立抓包嗅探器：偷偷记录系统的真实 URL 坐标
        try {
            val urlHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val urlStr = param.args[0] as? String ?: return
                    if (urlStr.contains("blued.cn") && urlStr.contains("latitude=") && urlStr.contains("longitude=")) {
                        if (urlStr.contains("llhook_ignore=1")) return // 忽略探测包
                        try {
                            val latMatch = Regex("""(?:latitude|lat)=([-\d.]+)""").find(urlStr)
                            val lngMatch = Regex("""(?:longitude|lon)=([-\d.]+)""").find(urlStr)
                            if (latMatch != null && lngMatch != null) {
                                sniffedLat = latMatch.groupValues[1].toDouble()
                                sniffedLng = lngMatch.groupValues[1].toDouble()
                            }
                        } catch (e: Throwable) {}
                    }
                }
            }
            XposedBridge.hookAllConstructors(URL::class.java, urlHook)
            try { XposedBridge.hookAllConstructors(java.net.URI::class.java, urlHook) } catch (e: Throwable) {}
        } catch (e: Throwable) {}

        // 退出官方地图时的位置还原与缓存清理逻辑
        try {
            val terminalActivityClass = lpparam.classLoader.loadClass("com.blued.android.core.ui.TerminalActivity")
            terminalActivityClass.findMethod { name == "finish" }.hookBefore { param ->
                if (isRealTrackingMapOpened) {
                    isRealTrackingMapOpened = false 
                    val activity = param.thisObject as Activity
                    val classLoader = activity.classLoader
                    
                    try {
                        val managerClass = XposedHelpers.findClass("com.soft.blued.ui.find.manager.MapFindManager", classLoader)
                        val managerInstance = XposedHelpers.callStaticMethod(managerClass, "a") 
                        val beanClass = XposedHelpers.findClass("com.soft.blued.ui.find.manager.MapFindManager\$MapFindBean", classLoader)
                        for (f in managerClass.declaredFields) {
                            f.isAccessible = true
                            if (f.type == beanClass) { f.set(managerInstance, null) } 
                            else if (f.type == Boolean::class.java || f.type == Boolean::class.javaPrimitiveType) { f.set(managerInstance, false) }
                        }
                    } catch (e: Throwable) {}

                    try {
                        val liveEventBusClass = XposedHelpers.findClass("com.jeremyliao.liveeventbus.LiveEventBus", classLoader)
                        val observable = XposedHelpers.callStaticMethod(liveEventBusClass, "get", "map_find_click")
                        XposedHelpers.callMethod(observable, "post", false)
                    } catch(e: Throwable){}
                    
                    val token = Config.getAuthToken(activity)
                    val real = getBestRealLocation(activity)
                    if (token.isNotEmpty() && real != null) {
                        thread {
                            try {
                                val roamUrl = URL("https://argo.blued.cn/users/roam?llhook_ignore=1")
                                val roamConn = roamUrl.openConnection() as HttpURLConnection
                                roamConn.requestMethod = "DELETE"
                                roamConn.setRequestProperty("authorization", token)
                                roamConn.connectTimeout = 3000
                                roamConn.inputStream.bufferedReader().readText()
                            } catch(e: Throwable){}
                            updateLocation(token, real[0], real[1])
                        }
                    }
                }
            }
        } catch (e: Throwable) {}

        // 2. 拦截并接管按钮事件
        try {
            val fragmentClass = lpparam.classLoader.loadClass(Config.TargetClasses.USER_INFO_FRAGMENT_NEW)

            fragmentClass.findMethod { name == "onResume" }.hookAfter { param ->
                val fragmentInstance = param.thisObject
                val activity = XposedHelpers.callMethod(fragmentInstance, "getActivity") as? Activity ?: return@hookAfter
                
                // 🛑 核心防御：如果没有开启追踪总开关，或者开启了虚拟定位，本模块装死，全权交给 TrackHook 处理！
                if (!Config.isFeatureEnabled("switch_track", activity) || Config.isFeatureEnabled("switch_virtual_location", activity)) {
                    return@hookAfter
                }

                val rootLayout = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@hookAfter
                
                // 清理可能残留的 TrackHook 按钮（防止重叠）
                rootLayout.findViewWithTag<View>("TrackBtnVirtual")?.let { rootLayout.removeView(it) }
                rootLayout.findViewWithTag<View>("TrackBtnReal")?.let { rootLayout.removeView(it) }

                var currentTargetUid: String? = null

                // 🚀 自己当家做主：直接注入紫色的“实景雷达追踪”按钮
                val trackBtn = TextView(activity).apply {
                    tag = "TrackBtnReal"
                    text = "位置追踪"
                    textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(50, 20, 50, 20)
                    background = GradientDrawable().apply { setColor(Color.parseColor("#9C27B0")); cornerRadius = 50f; setStroke(2, Color.WHITE) }
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 320; marginEnd = 40 }

                    // 短按获取坐标
                    setOnClickListener {
                        val uid = currentTargetUid
                        if (uid != null) {
                            val res = trackCache[uid]
                            if (res != null) {
                                showResult(activity, res.success, res.msg, res.lat, res.lng)
                            } else {
                                Toast.makeText(activity, "位置追踪正解解算中....", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(activity, "正在初始化数据...", Toast.LENGTH_SHORT).show()
                        }
                    }

                    // 长按空降地图
                    setOnLongClickListener {
                        val uid = currentTargetUid
                        if (uid != null) {
                            val res = trackCache[uid]
                            if (res != null && res.success) {
                                val finalLat = res.lat
                                val finalLng = res.lng

                                thread {
                                    try {
                                        val token = Config.getAuthToken(activity)
                                        if (token.isNotEmpty()) updateLocation(token, finalLat, finalLng)
                                        
                                        mainHandler.post {
                                            Toast.makeText(activity, "定位成功", Toast.LENGTH_SHORT).show()
                                            if (MapHelper.openOfficialMapFind(activity, finalLat, finalLng, "目标位置")) {
                                                isRealTrackingMapOpened = true
                                            } else {
                                                Toast.makeText(activity, "调用官方地图失败", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } catch (e: Exception) {}
                                }
                            } else Toast.makeText(activity, "请稍等", Toast.LENGTH_SHORT).show()
                        }
                        true 
                    }
                }
                rootLayout.addView(trackBtn)

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
                    if (token.isEmpty() || trackCache.containsKey(uidFound)) return@thread

                    // 🌍 实景模式获取坐标：系统抓包 + 底层 Android 系统兜底
                    var realLat = sniffedLat
                    var realLng = sniffedLng
                    
                    if (realLat == 0.0 || realLng == 0.0) {
                        try {
                            val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                            var loc = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e:Throwable){null}
                            if (loc == null) loc = try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e:Throwable){null}
                            if (loc == null) loc = try { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (e:Throwable){null}
                            if (loc != null) { realLat = loc.latitude; realLng = loc.longitude }
                        } catch (e: Throwable) {}
                    }

                    if (realLat == 0.0 || realLng == 0.0) {
                        trackCache[uidFound] = TrackResult(false, "获取定位失败，请刷新后重试", 0.0, 0.0)
                        return@thread
                    }

                    // 记录真实坐标，退出官方地图时还原，防止坐标飘走
                    myRealLat = realLat
                    myRealLng = realLng

                    // 开始几何雷达解算
                    var initialDist = fetchDynamicDistance(uidFound, token, realLat, realLng)
                    if (initialDist < 0) return@thread

                    try {
                        trackCache[uidFound] = doTrilaterationTracking(uidFound, token, realLat, realLng, initialDist)
                    } catch (e: Throwable) {} finally {
                        updateLocation(token, realLat, realLng)
                    }
                }
            }

            fragmentClass.findMethod { name == "onPause" }.hookBefore { param ->
                val activity = XposedHelpers.callMethod(param.thisObject, "getActivity") as? Activity ?: return@hookBefore
                val rootLayout = activity.findViewById<ViewGroup>(android.R.id.content)
                rootLayout?.findViewWithTag<View>("TrackBtnReal")?.let { rootLayout.removeView(it) }
            }
        } catch (e: Exception) {}
    }

    private fun extractUid(fragmentInstance: Any): String? {
        try {
            for (field in fragmentInstance.javaClass.declaredFields) {
                try {
                    field.isAccessible = true
                    val obj = field.get(fragmentInstance) ?: continue
                    val tempUid = XposedHelpers.getObjectField(obj, "uid")?.toString()
                    if (!tempUid.isNullOrEmpty()) return tempUid
                } catch (e: Throwable) {}
            }
            val bundle = XposedHelpers.callMethod(fragmentInstance, "getArguments") as? Bundle
            if (bundle != null) return bundle.getString("uid") ?: bundle.getString("user_id")
        } catch (e: Throwable) {}
        return null
    }

    private fun updateLocation(token: String, lat: Double, lng: Double) {
        try {
            val conn = URL("https://argo.blued.cn/users?sort_by=nearby&latitude=$lat&longitude=$lng&limit=1&llhook_ignore=1").openConnection() as HttpURLConnection
            conn.setRequestProperty("authorization", token)
            conn.setRequestProperty("user-agent", "Mozilla/5.0 (Linux; U; Android 13; ...) app/1")
            conn.connectTimeout = 3000
            conn.inputStream.bufferedReader().use { it.readText() } 
        } catch (e: Exception) {}
    }

    /** 取最可靠的「真实坐标」用于退出地图时还原：优先追踪时记录的值，否则现取系统 GPS。 */
    private fun getBestRealLocation(activity: Activity): DoubleArray? {
        if (myRealLat != 0.0 && myRealLng != 0.0) return doubleArrayOf(myRealLat, myRealLng)
        return try {
            val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: Throwable) { null }
                ?: try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: Throwable) { null }
                ?: try { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (e: Throwable) { null }
            if (loc != null) doubleArrayOf(loc.latitude, loc.longitude) else null
        } catch (e: Throwable) { null }
    }

    private fun fetchDynamicDistance(uid: String, myToken: String, lat: Double, lng: Double): Double {
        try {
            updateLocation(myToken, lat, lng)
            Thread.sleep(200) 
            val conn = URL("https://argo.blued.cn/users/$uid/basic?llhook_ignore=1").openConnection() as HttpURLConnection
            conn.setRequestProperty("authorization", myToken)
            if (conn.responseCode == 200) {
                val dataArray = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("data")
                if (dataArray != null && dataArray.length() > 0) return dataArray.getJSONObject(0).optDouble("distance", -1.0)
            }
        } catch (t: Throwable) {}
        return -1.0 
    }

    // 📐 实景物理坐标专用算法：双圆几何相交 (Trilateration)
    private fun doTrilaterationTracking(uid: String, token: String, startLat: Double, startLng: Double, initialDist: Double): TrackResult {
        if (initialDist < 0.01) return TrackResult(true, "解算完成！目标就在你脚下！\n📍 $startLat, $startLng", startLat, startLng)

        val probeLat = startLat
        val probeLng = startLng + (initialDist / (111.32 * cos(Math.toRadians(startLat))))
        
        val r2 = fetchDynamicDistance(uid, token, probeLat, probeLng)
        if (r2 < 0) return TrackResult(false, "定位失败", 0.0, 0.0)

        val r1 = initialDist
        val d = initialDist
        val a = (r1 * r1 - r2 * r2 + d * d) / (2 * d)
        val hSq = r1 * r1 - a * a
        
        if (hSq < 0) return TrackResult(false, "定位失败", 0.0, 0.0)
        val h = sqrt(hSq)

        val midLat = startLat
        val midLng = startLng + (a / (111.32 * cos(Math.toRadians(startLat))))

        val i1Lat = midLat + (h / 111.32)
        val i1Lng = midLng

        val i2Lat = midLat - (h / 111.32)
        val i2Lng = midLng

        val distI1 = fetchDynamicDistance(uid, token, i1Lat, i1Lng)
        
        val finalLat: Double
        val finalLng: Double
        if (distI1 in 0.0..0.05) { 
            finalLat = i1Lat; finalLng = i1Lng
        } else {
            finalLat = i2Lat; finalLng = i2Lng
        }

        return TrackResult(true, "定位完成！\n\n📍 纬度: $finalLat\n📍 经度: $finalLng\n", finalLat, finalLng)
    }

    private fun showResult(activity: Activity, success: Boolean, msg: String, lat: Double = 0.0, lng: Double = 0.0) {
        mainHandler.post { com.example.ui.showTrackResult(activity, success, msg, lat, lng) }
    }
}
