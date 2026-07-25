package bxxd.hook

import android.content.Context
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 *  坐标破译 (CoordSpyHook)
 * ============================================================================
 *  开关: switch_coord_spy (UI「聊天增强 → 坐标破译」)
 *
 *  抓包 (来访的响应 / 查看的响应):
 *    来访 /users/{uid}/visitors → 服务端【直接泄露】真实 latitude/longitude
 *    查看 /users/{uid}/visited  → 只有 distance, 服务端【不返回】坐标
 *
 *  本 Hook 唯一会【改写】的响应: 「查看 (visited)」响应 (含 visited_time)。
 *  在其 data[] 每个用户对象里 put 进 latitude / longitude 两个 key。
 *  → 目的: 官方本不返回坐标, 我加进去, 看客户端模型/界面会不会因此显示位置。
 *
 *  注入值优先级 (保证「打开就能试」, 不依赖先刷过附近):
 *    1) 该 uid 在附近/来访响应抓到的【真实坐标】缓存 (只读抓取, 不改那些响应)
 *    2) 否则用【你自己的当前坐标】(从请求 URL query 的 latitude/longitude 解出)
 *       —— 这样即便没缓存, visited 响应也一定会带上坐标, 可立即验证。
 *
 *  另外 (只读, 不改响应):
 *    - Request.Builder: 解析每个请求 URL 里的 latitude/longitude, 存为「自身坐标」。
 *    - Gson.fromJson: 附近/来访响应里的真实坐标 → 入缓存。
 *
 *  显示 (可选, 辅助验证): 来访/附近列表距离文本后追加 📍坐标。
 * ============================================================================
 */
object CoordSpyHook : BaseHook {

    private const val TAG = "llhook_CoordSpy"

    /** uid -> doubleArrayOf(lat, lon)  GCJ-02 (来自附近/来访响应的真实坐标) */
    val cachedCoords = mutableMapOf<String, DoubleArray>()

    /** 自身当前坐标 [lat, lon] (从请求 URL query 解出, 作为注入兜底值) */
    @Volatile
    private var ownCoords: DoubleArray = doubleArrayOf(0.0, 0.0)

    private fun isEnabled(): Boolean = try {
        val ctx = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null),
            "currentApplication"
        ) as? Context
        if (ctx != null) Config.isFeatureEnabled("switch_coord_spy", ctx) else false
    } catch (_: Throwable) { false }

    /** 从 URL 字符串解出 latitude / longitude query 参数 */
    private fun parseOwnCoords(url: String) {
        try {
            val lat = Regex("[?&]latitude=([0-9.\\-]+)").find(url)?.groupValues?.get(1)?.toDoubleOrNull()
            val lon = Regex("[?&]longitude=([0-9.\\-]+)").find(url)?.groupValues?.get(1)?.toDoubleOrNull()
            if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                ownCoords = doubleArrayOf(lat, lon)
            }
        } catch (_: Throwable) {}
    }

    /** 从 Gson.fromJson 结果里抽出模型列表 (可能是 Collection, 也可能是含 data 字段的包装) */
    private fun extractModels(result: Any?): List<Any>? {
        if (result == null) return null
        if (result is Collection<*>) return result.filterNotNull()
        try {
            val data = XposedHelpers.findFieldIfExists(result.javaClass, "data")?.get(result)
            if (data is Collection<*>) return data.filterNotNull()
            if (data != null) return listOf(data)
        } catch (_: Throwable) {}
        return null
    }

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {

        // ===== ① 只读: Request.Builder.build 解析自身坐标 =====
        try {
            val builderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", lpparam.classLoader)
            if (builderClass != null) {
                XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        try {
                            val urlObj = XposedHelpers.getObjectField(param.result, "url")
                            parseOwnCoords(urlObj?.toString() ?: "")
                        } catch (_: Throwable) {}
                    }
                })
            }
        } catch (_: Throwable) {}

        // ===== ② 只读抓取真实坐标 + ③ 仅对 visited 注入 =====
        val gsonHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled()) return
                val json = param.args[0] as? String ?: return
                if (!json.contains("\"uid\"") || !json.contains("\"data\"")) return

                // 唯一会被【改写】的响应: 查看 (visited)
                val isVisited = json.contains("\"visited_time\"")
                if (!isVisited && ownCoords[0] == 0.0) {
                    // 非注入目标, 仅做轻量只读抓取 (有真实坐标才入缓存)
                }

                try {
                    val root = JSONObject(json)
                    val dataEl = root.opt("data") ?: return
                    val arr = when (dataEl) {
                        is JSONArray -> dataEl
                        is JSONObject -> JSONArray().apply { put(dataEl) }
                        else -> return
                    }
                    var captured = 0
                    var injected = 0
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val uid = obj.optString("uid", "")
                        if (uid.isEmpty() || obj.has("is_ads")) continue

                        val lat = obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optDouble("longitude", Double.NaN)
                        if (!lat.isNaN() && !lon.isNaN() && lat != 0.0 && lon != 0.0) {
                            // 只读抓取: 附近/来访泄露的真实坐标 (不改本响应)
                            cachedCoords[uid] = doubleArrayOf(lat, lon)
                            captured++
                        }

                        // 注入: 仅「查看」响应, 每个用户都 put 两个 key
                        if (isVisited && obj.has("visited_time")) {
                            val useOwn = (lat.isNaN() || lon.isNaN() || lat == 0.0 || lon == 0.0)
                            val c = if (!useOwn) doubleArrayOf(lat, lon)        // 已有(罕见)
                                    else cachedCoords[uid]                       // 真实坐标缓存
                                    ?: ownCoords.takeIf { it[0] != 0.0 }         // 兜底: 自身坐标
                            if (c != null) {
                                obj.put("latitude", c[0])
                                obj.put("longitude", c[1])
                                injected++
                            }
                        }
                    }
                    if (captured > 0)
                        XposedBridge.log("$TAG 抓取真实坐标 $captured 条 (只读), 缓存共 ${cachedCoords.size}")
                    if (injected > 0) {
                        param.args[0] = root.toString()
                        XposedBridge.log("$TAG 【查看/visited 响应】注入 latitude/longitude $injected 条")
                    }
                } catch (_: Throwable) {}
            }

            // ===== 验证: Gson 解析后, 模型是否保留了注入的 latitude/longitude =====
            //   打印模型【全部字段名】, 亲眼确认有没有 latitude。
            //   结论预期: 模型无该字段 → Gson 丢弃 → 注入对模型无效。
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isEnabled()) return
                try {
                    val items = extractModels(param.result) ?: return
                    if (items.isEmpty()) return
                    val m = items[0]
                    // 汇总整条继承链的所有 public 字段名
                    val allNames = mutableListOf<String>()
                    var c: Class<*>? = m.javaClass
                    while (c != null && c != Any::class.java) {
                        c.declaredFields.forEach { allNames.add(it.name) }
                        c = c.superclass
                    }
                    val hasLat = allNames.any { it.equals("latitude", true) }
                    val hasLon = allNames.any { it.equals("longitude", true) }
                    val sample = allNames.joinToString(",")
                    XposedBridge.log("$TAG 模型类型=${m.javaClass.name}")
                    XposedBridge.log("$TAG 模型全部字段(${'$'}{allNames.size}): $sample")
                    XposedBridge.log("$TAG 含latitude字段=$hasLat 含longitude字段=$hasLon")
                    if (hasLat) {
                        val lv = XposedHelpers.findFieldIfExists(m.javaClass, "latitude")?.get(m)
                        XposedBridge.log("$TAG latitude值=$lv  ←【模型保留了坐标, 注入有效!】")
                    } else {
                        XposedBridge.log("$TAG ←【模型无latitude字段, Gson已丢弃注入值, 注入对模型无效】")
                    }
                } catch (_: Throwable) {}
            }
        }
        try {
            XposedHelpers.findClassIfExists("com.google.gson.Gson", lpparam.classLoader)
                ?.let { XposedBridge.hookAllMethods(it, "fromJson", gsonHook) }
        } catch (_: Throwable) {}

        // ===== ④ 显示 (辅助): 来访/附近列表距离后追加坐标 =====
        try {
            val duClass = XposedHelpers.findClassIfExists(
                "com.blued.android.module.common.utils.DistanceUtils", lpparam.classLoader) ?: return
            val ubmClass = XposedHelpers.findClassIfExists(
                "com.blued.android.module.common.login.model.UserBasicModel", lpparam.classLoader) ?: return
            XposedHelpers.findAndHookMethod(
                duClass, "a",
                Context::class.java, TextView::class.java, ubmClass, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        try {
                            val tv = param.args[1] as? TextView ?: return
                            val model = param.args[2] ?: return
                            val uid = XposedHelpers.getObjectField(model, "uid") as? String ?: return
                            if (uid.isEmpty()) return
                            val c = cachedCoords[uid] ?: return
                            val cur = tv.text?.toString() ?: ""
                            if (cur.contains("📍")) return
                            tv.text = "$cur  📍" + "%.4f,%.4f".format(c[0], c[1])
                        } catch (_: Throwable) {}
                    }
                })
        } catch (_: Throwable) {}
    }
}
