package bxxd.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.util.TypedValue
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier

object MapHelper {

    // 长按追踪 → 打开官方「地图找人」后, 官方页面默认靠 GPS 居中、不读 MapFindBean。
    // 这里缓存待空降的目标坐标, 由 registerOfficialMapCenterHook 在 FindSearchMapFragment
    // 初始化后强制 moveCamera 过去, 实现「跳转到目标」。
    @Volatile private var pendingOfficialTarget: DoubleArray? = null
    private var officialMapHookRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 获取当前进程的本地配置 (正式版/极速版 均为宿主进程)
    private fun getPrefs(context: Context) = context.getSharedPreferences(
        if (Config.isBluedPackage(context.packageName)) "llhook_blued_local_v2" else "llhook_settings", 
        Context.MODE_PRIVATE
    )
    
    private fun getSyncAction(context: Context) = 
        if (Config.isBluedPackage(context.packageName)) "bxxd.hook.MAIN_SYNC_PUSH" else "bxxd.hook.SYNC_PUSH"

    private fun updateSyncFile(block: (JSONObject) -> Unit) {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "llhook_blued")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "llhook_sync.json")
            val json = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            block(json)
            json.put("ts", System.currentTimeMillis())
            file.writeText(json.toString())
        } catch (e: Exception) {}
    }

    fun pushOfflineLocation(lat: Double, lng: Double) {
        updateSyncFile { 
            it.put("lat", lat)
            it.put("lng", lng)
        }
    }

    fun pullOfflineData(): JSONObject? {
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "llhook_blued/llhook_sync.json")
            if (file.exists()) return JSONObject(file.readText())
        } catch (e: Exception) {}
        return null
    }

    fun getFavArray(context: Context): JSONArray {
        val prefs = getPrefs(context)
        val str = prefs.getString("fav_locations", "[]") ?: "[]"
        return try { JSONArray(str) } catch (e: Exception) { JSONArray() }
    }

    fun saveFavArray(context: Context, array: JSONArray) {
        getPrefs(context).edit().putString("fav_locations", array.toString()).apply()
        val intent = Intent(getSyncAction(context)).apply {
            putExtra("key", "fav_locations")
            putExtra("value", array.toString())
        }
        context.sendBroadcast(intent)
        updateSyncFile { it.put("favs", array) }
    }

    /**
     * 打开 Blued 官方「地图找人」并空降到目标坐标。
     *
     * ── 官方真实调用链 (从 dex 反编译确认) ──
     * 「身边」页右上角地图找人按钮 (NearbyPeopleFragment$49.Z_() 权限回调) 调用的是:
     *     FindSearchMapFragment.a(BaseFragmentActivity, int fromPage)   // fromPage = 2
     * 该静态方法内部已封装完整流程:
     *   new Bundle → putInt("from_page", fromPage)
     *   → TerminalActivity.a(Bundle) 预处理    // 极速版壳化为 H(Bundle)→Bundle
     *   → TerminalActivity.e(Context,Class,Bundle) 打开  // 极速版壳化为 I(Context,Class,Bundle)
     *   → ActivityChangeAnimationUtils 转场动画
     *
     * 关键: 业务类 FindSearchMapFragment 的 public 方法名【未被爱加密混淆】, 反射稳定;
     *       而底层 framework 类 TerminalActivity 的 a/e 已被壳化为 H/I (壳的方法名映射只对
     *       invoke 指令生效, 反射 getDeclaredMethod 看到的是壳后名字)。因此最优解是直接调用
     *       FindSearchMapFragment.a, 完全避开 TerminalActivity 的壳化方法名。
     *
     * 三层策略: ① 预存坐标 + 注册居中 hook (官方靠 GPS 居中, 不读 bean)
     *           ② FindSearchMapFragment.a (主, 最忠实官方) ③ TerminalActivity 签名枚举兜底。
     * 全程 catch(Throwable) —— XposedHelpers 抛 ClassNotFoundError/NoSuchMethodError 都是 Error。
     */
    fun openOfficialMapFind(activity: Activity, lat: Double, lng: Double, label: String = "目标位置"): Boolean {
        try {
            val cl = activity.classLoader

            // ① 预存待空降坐标 + 注册一次性居中 hook
            //    官方页面靠 GPS 居中、不读 MapFindBean; 必须在页面初始化后强制 moveCamera。
            pendingOfficialTarget = doubleArrayOf(lat, lng)
            registerOfficialMapCenterHook(cl)

            // ② 主策略: FindSearchMapFragment.a(BaseFragmentActivity, int fromPage)
            //    复刻官方 NearbyPeopleFragment$49.Z_(): from_page = 2 (身边来源)。
            //    TerminalActivity 继承 BaseFragmentActivity, 主进程 activity 可安全作为此参数。
            try {
                val fsmClass = XposedHelpers.findClass("com.soft.blued.ui.find.fragment.FindSearchMapFragment", cl)
                val baseActClass = XposedHelpers.findClass("com.blued.android.core.ui.BaseFragmentActivity", cl)
                val m = fsmClass.declaredMethods.firstOrNull {
                    Modifier.isStatic(it.modifiers) && it.name == "a" &&
                        it.parameterTypes.size == 2 &&
                        baseActClass.isAssignableFrom(it.parameterTypes[0]) &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                }
                if (m != null && baseActClass.isInstance(activity)) {
                    m.isAccessible = true
                    m.invoke(null, activity, 2)   // from_page = 2 (身边)
                    return true
                }
            } catch (e: Throwable) {}

            // ③ 兜底: 手动 TerminalActivity (仅当 FindSearchMapFragment.a 找不到时)
            //    极速版壳化: 预处理 a(Bundle)→H, 打开 e(Context,Class,Bundle)→I / M(...,int)
            try {
                val terminalClass = XposedHelpers.findClass("com.blued.android.core.ui.TerminalActivity", cl)
                val mapFragmentClass = XposedHelpers.findClass("com.soft.blued.ui.find.fragment.FindSearchMapFragment", cl)
                val bundle = Bundle().apply { putInt("from_page", 2) }
                // 预处理 (Bundle)→Bundle : 签名查, 命中极速版 H
                try {
                    val pre = terminalClass.declaredMethods.firstOrNull {
                        Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == Bundle::class.java && it.returnType == Bundle::class.java
                    }
                    if (pre != null) { pre.isAccessible = true; pre.invoke(null, bundle) }
                } catch (e: Throwable) {}
                // 打开 3 参 (Context, Class, Bundle) : 签名查, 命中极速版 I
                val open3 = terminalClass.declaredMethods.firstOrNull {
                    val p = it.parameterTypes
                    Modifier.isStatic(it.modifiers) && p.size == 3 &&
                        Context::class.java.isAssignableFrom(p[0]) &&
                        p[1] == Class::class.java && p[2] == Bundle::class.java
                }
                if (open3 != null) { open3.isAccessible = true; open3.invoke(null, activity, mapFragmentClass, bundle); return true }
                // 打开 4 参 (Context, Class, Bundle, int) : 签名查, 命中极速版 M, requestCode 传 0
                val open4 = terminalClass.declaredMethods.firstOrNull {
                    val p = it.parameterTypes
                    Modifier.isStatic(it.modifiers) && p.size == 4 &&
                        Context::class.java.isAssignableFrom(p[0]) &&
                        p[1] == Class::class.java && p[2] == Bundle::class.java &&
                        (p[3] == Int::class.javaPrimitiveType || p[3] == Integer::class.java)
                }
                if (open4 != null) { open4.isAccessible = true; open4.invoke(null, activity, mapFragmentClass, bundle, 0); return true }
            } catch (e: Throwable) {}

            return false
        } catch (e: Throwable) {
            return false
        }
    }

    /**
     * 一次性 hook FindSearchMapFragment.onResume: 页面可见时轮询等待 AMap 就绪后强制 moveCamera。
     *
     * 原本 hook onInitView 失效的原因: onInitView 后 getMap() 仍可能返回 null (地图引擎未就绪)。
     * 改为 hook onResume (页面可见必调) + 轮询重试(每 400ms, 最多 12 次=4.8s), 同时尝试:
     *   ① 按类型枚举找 com.amap.api.maps.AMap 字段 (官方代码读字段 a, 不经 getMap)
     *   ② 找 MapView 字段 getMap() 兑底
     * 找到非空 AMap 后 moveCamera + 加标记 + 顶部坐标叠层显示。
     */
    private fun registerOfficialMapCenterHook(cl: ClassLoader) {
        if (officialMapHookRegistered) return
        officialMapHookRegistered = true
        try {
            val fsmClass = XposedHelpers.findClass("com.soft.blued.ui.find.fragment.FindSearchMapFragment", cl)
            XposedBridge.hookAllMethods(fsmClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (pendingOfficialTarget == null) return
                    val fragment = param.thisObject ?: return
                    val target = pendingOfficialTarget ?: return
                    // 递归轮询取 AMap (地图引擎异步就绪)
                    val tryCount = intArrayOf(0)
                    val poll = object : Runnable {
                        override fun run() {
                            if (pendingOfficialTarget == null) return
                            tryCount[0]++
                            var amap: Any? = null
                            try {
                                // ① 按类型枚举找 AMap 字段 (遍历含父类)
                                var c: Class<*>? = fragment.javaClass
                                while (c != null && amap == null) {
                                    for (f in c.declaredFields) {
                                        if (f.type.name == "com.amap.api.maps.AMap") {
                                            f.isAccessible = true; amap = f.get(fragment); if (amap != null) break
                                        }
                                    }
                                    c = c.superclass
                                }
                                // ② MapView 字段 getMap() 兑底
                                if (amap == null) {
                                    var mv: Any? = null
                                    var c2: Class<*>? = fragment.javaClass
                                    while (c2 != null && mv == null) {
                                        for (f in c2.declaredFields) {
                                            if (f.type.name == "com.amap.api.maps.MapView") {
                                                f.isAccessible = true; mv = f.get(fragment); if (mv != null) break
                                            }
                                        }
                                        c2 = c2.superclass
                                    }
                                    if (mv != null) amap = XposedHelpers.callMethod(mv, "getMap")
                                }
                            } catch (e: Throwable) {}
                            if (amap == null) {
                                if (tryCount[0] < 12) mainHandler.postDelayed(this, 400)
                                return
                            }
                            // 就绪: 居中 + 标记 + 坐标叠层
                            pendingOfficialTarget = null
                            try {
                                val latLngClass = cl.loadClass("com.amap.api.maps.model.LatLng")
                                val latLng = XposedHelpers.newInstance(latLngClass, target[0], target[1])
                                val cuf = cl.loadClass("com.amap.api.maps.CameraUpdateFactory")
                                val update = XposedHelpers.callStaticMethod(cuf, "newLatLngZoom", latLng, 16f)
                                XposedHelpers.callMethod(amap, "moveCamera", update)
                                try {
                                    val moClass = cl.loadClass("com.amap.api.maps.model.MarkerOptions")
                                    val mo = XposedHelpers.newInstance(moClass)
                                    XposedHelpers.callMethod(mo, "position", latLng)
                                    XposedHelpers.callMethod(mo, "title", "目标精准坐标")
                                    XposedHelpers.callMethod(amap, "addMarker", mo)
                                } catch (e: Throwable) {}
                                // 顶部坐标叠层
                                showCoordOverlayOnFragment(fragment, target[0], target[1])
                            } catch (e: Throwable) {}
                        }
                    }
                    mainHandler.post(poll)
                }
            })
        } catch (e: Throwable) {}
    }

    /** 在官方地图 fragment 的 rootView 顶部添加坐标 + 逆地理名称叠层 (带 tag 防重复) */
    private fun showCoordOverlayOnFragment(fragment: Any, lat: Double, lng: Double) {
        try {
            val rootView = XposedHelpers.getObjectField(fragment, "rootView") as? android.view.View ?: return
            if (rootView !is android.view.ViewGroup) return
            val tag = "llhook_official_coord"
            rootView.findViewWithTag<android.view.View>(tag)?.let { rootView.removeView(it) }
            val activity = XposedHelpers.callMethod(fragment, "getActivity") as? Activity ?: return
            val tv = android.widget.TextView(activity).apply {
                text = "📍 ${formatLat(lat)}, ${formatLng(lng)}\n名称获取中..."
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                setPadding(dp2px(activity, 12f), dp2px(activity, 8f), dp2px(activity, 12f), dp2px(activity, 8f))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#CC000000")); cornerRadius = dp2px(activity, 12f).toFloat()
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; topMargin = dp2px(activity, 50f); marginStart = dp2px(activity, 12f) }
            }
            rootView.addView(tv)
            // 逆地理名称
            Thread {
                val name = reverseGeocode(lat, lng, Config.getApiKey(activity))
                mainHandler.post {
                    try {
                        if (tv.parent != null) tv.text = "📍 ${formatLat(lat)}, ${formatLng(lng)}\n$name"
                    } catch (e: Throwable) {}
                }
            }.start()
        } catch (e: Throwable) {}
    }

    private fun formatLat(v: Double) = String.format(java.util.Locale.US, "%.6f", v) + "N"
    private fun formatLng(v: Double) = String.format(java.util.Locale.US, "%.6f", v) + "E"

    /** 高德逆地理编码 Web API → 返回格式化地址 (需 apiKey) */
    fun reverseGeocode(lat: Double, lng: Double, apiKey: String): String {
        if (apiKey.isEmpty()) return "(未配置高德Key)"
        return try {
            val url = java.net.URL("https://restapi.amap.com/v3/geocode/regeo?location=${String.format(java.util.Locale.US, "%.6f", lng)},${String.format(java.util.Locale.US, "%.6f", lat)}&key=$apiKey&extensions=base")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                if (json.optString("status") == "1") {
                    val regeo = json.optJSONObject("regeocode") ?: return "(无地址)"
                    regeo.optString("formatted_address", "(无地址)")
                } else "(查询失败)"
            } else "(网络错误)"
        } catch (e: Throwable) { "(获取失败)" }
    }

    // 🚀 强制使用高德 Web API，返回包含经纬度和详细地址的智能列表
    fun searchAMapSuggestionOnline(activity: Activity, query: String, apiKey: String, onSuccess: (JSONArray) -> Unit) {
        Thread {
            activity.runOnUiThread { onSuccess(searchAMapSuggestion(query, apiKey)) }
        }.start()
    }

    /**
     * 高德地点联想 (同步, 供 Compose 协程调用)。返回已过滤的有效建议 (含坐标)。
     * 阶段 2: 替代 searchAMapSuggestionOnline 的 Activity+回调 老接口。
     */
    fun searchAMapSuggestion(query: String, apiKey: String): JSONArray {
        if (apiKey.isEmpty()) return JSONArray()
        return try {
            val url = URL("https://restapi.amap.com/v3/assistant/inputtips?keywords=$query&key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                if (json.optString("status") == "1") {
                    json.optJSONArray("tips") ?: JSONArray()
                } else JSONArray()
            } else JSONArray()
        } catch (e: Exception) { JSONArray() }
    }

    /**
     * 收藏当前位置 (阶段 2: 命名 UI 由 com.example.ui.MapPickerScreen 的 Compose 弹窗承载)。
     * 此方法仅做数据写入, 返回是否成功, 供 Compose 层调用。
     */
    fun addFavorite(context: Context, name: String, lat: Double, lng: Double): Boolean {
        if (name.isEmpty()) return false
        val favArray = getFavArray(context)
        favArray.put(JSONObject().apply { put("name", name); put("lat", lat); put("lng", lng) })
        saveFavArray(context, favArray)
        return true
    }

    /** 删除第 idx 个收藏。 */
    fun removeFavorite(context: Context, idx: Int) {
        val favArray = getFavArray(context)
        if (idx in 0 until favArray.length()) {
            favArray.remove(idx)
            saveFavArray(context, favArray)
        }
    }

    private fun dp2px(context: Context, dp: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt()
}
