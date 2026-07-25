package bxxd.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

/**
 * ============================================================================
 *  附近页「排序栏筛选按钮 + 客户端筛选」(NearbySortHook)
 * ============================================================================
 *  开关: switch_nearby_sort (UI「聊天增强 → 首页增加筛选」)
 *
 *  ★ 定位方式 (绝对可靠): 直接读 fragment 字段 `sortTabBar` (LinearLayout),
 *    即 fragment_nearby_home.xml: sort_container(0x7f0a289e) → ll_sort(0x7f0a1e8b)
 *    → tab_bar(0x7f0a2a18) → sort_tab_bar(0x7f0a289f) 这一行的在线/距离胶囊容器。
 *
 *  双保险 hook 点:
 *    ① onInitView(Bundle) after —— 首次进入附近页 (J() 已构建好 tabs)
 *    ② J() after —— 切换排序/重建 tabs 后补回筛选按钮
 *
 *  做两件事:
 *   ① 整个 sortTabBar 往右平移 5dp (在线/距离/筛选 一起右移)。
 *   ② 每个 tab 的 tv_sort 文字加粗; 末尾追加「筛选」tab (官方 layout_nearby_people_tab 同款胶囊),
 *      文字「筛选」+ 加粗, 图标 icon_arrow_down_filter, 点击弹出 Compose 筛选面板。
 *
 *  客户端筛选 (抓包不可见): hook PeopleGridQuickAdapter.setNewData/addData before,
 *  缓存原始完整数据, 任一筛选生效则替换入参为过滤子集; 确定时重新 setNewData 触发刷新。
 *  筛选项来源: 首页在线响应数据真实字段 (role/online_state/vip_grade/is_vip_annual/
 *  is_show_real_text/personal_card_album/is_new/is_shadow/distance/height/age)。
 * ============================================================================
 */
object NearbySortHook : BaseHook {

    private const val TAG = "llhook-NearbySort"
    private const val FILTER_TAG = "llhook_filter_tab"

    /** 筛选条件持久化 key (Config.setRaw/getFresh, 跨进程同步)。 */
    private const val KEY_FILTER = "nearby_filter_state"

    /** 当前生效的筛选条件 (Compose 面板写回, init 时从持久化读回)。 */
    @Volatile
    private var currentFilter = NearbyFilterState()

    @Volatile private var filterLoaded = false

    /** 从持久化读回筛选 (init / 打开面板前调用, 保证重启不重置)。 */
    private fun ensureFilterLoaded() {
        if (filterLoaded) return
        try {
            val json = Config.getFresh(KEY_FILTER, "{}", null)
            currentFilter = NearbyFilterState.fromJson(json)
        } catch (_: Throwable) {}
        filterLoaded = true
    }

    /** 写回筛选 (内存 + 持久化, onApply/onReset 调用)。 */
    private fun persist(state: NearbyFilterState) {
        currentFilter = state
        filterLoaded = true
        try { Config.setRaw(KEY_FILTER, state.toJson(), null) } catch (_: Throwable) {}
    }

    private val fullData = Collections.synchronizedMap(WeakHashMap<Any, MutableList<Any>>())
    private val adapters = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))

    /** uid → 原始 JSON (Gson.fromJson 抓取, 保留被 Gson 丢弃的字段如 is_new / anchor_sign_status)。 */
    private val rawByUid = Collections.synchronizedMap(object : LinkedHashMap<String, JSONObject>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean = size > 2000
    })

    /** 防重入标记: reapplyAll 自己 setNewData 时置 true, hook 放行不再二次过滤 (借鉴手术刀 INTERNAL_UPDATE)。 */
    private val internalUpdate = ThreadLocal<Boolean>()

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        ensureFilterLoaded()   // 启动即读回上次筛选, 不再每次重置
        try {
            // ============ UI: 直接读 fragment 字段 sortTabBar ============
            val fragCls = lpparam.classLoader.loadClass("com.soft.blued.ui.find.fragment.NearbyPeopleFragment")

            fragCls.findMethod { name == "onInitView" && parameterTypes.size == 1 }.hookAfter { p ->
                if (!Config.isFeatureEnabled("switch_nearby_sort")) return@hookAfter
                patchFromFragment(p.thisObject, "onInitView")
            }

            // J() private — 重建排序 tab 时补回筛选
            fragCls.findMethod { name == "J" && parameterTypes.isEmpty() }.hookAfter { p ->
                if (!Config.isFeatureEnabled("switch_nearby_sort")) return@hookAfter
                patchFromFragment(p.thisObject, "J")
            }

            // ============ 抓取原始 JSON (保留被 Gson 丢弃的字段) ============
            try {
                val gsonClass = lpparam.classLoader.loadClass("com.google.gson.Gson")
                XposedBridge.hookAllMethods(gsonClass, "fromJson", object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val jsonStr = param.args.getOrNull(0) as? String ?: return
                        // 指纹: 附近/在线交友列表 (与 AutoVisitHook 同套)
                        if (!jsonStr.contains("\"data\":[{") || !jsonStr.contains("\"uid\"") ||
                            !jsonStr.contains("\"distance\"")) return
                        try {
                            val arr = JSONObject(jsonStr).optJSONArray("data") ?: return
                            for (i in 0 until arr.length()) {
                                val u = arr.optJSONObject(i) ?: continue
                                val uid = u.optString("uid", "")
                                if (uid.isNotEmpty()) rawByUid[uid] = u
                            }
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}

            // ============ 客户端筛选 ============
            // 同时 hook Grid/List 两个 adapter (手术刀同策略, 两种列表形态都覆盖)
            val adapterClassNames = listOf(
                "com.soft.blued.ui.find.adapter.PeopleGridQuickAdapter",
                "com.soft.blued.ui.find.adapter.PeopleListQuickAdapter"
            )
            for (cn in adapterClassNames) {
                val cls = try { lpparam.classLoader.loadClass(cn) } catch (_: Throwable) { continue }
                cls.findMethod { name == "setNewData" && parameterTypes.size == 1 }.hookBefore { p ->
                    if (!Config.isFeatureEnabled("switch_nearby_sort")) return@hookBefore
                    // 防重入: reapplyAll 自己 setNewData 时放行 (数据已提前过滤好)
                    if (internalUpdate.get() == true) { internalUpdate.set(false); return@hookBefore }
                    val ad = p.thisObject ?: return@hookBefore
                    adapters.add(ad)
                    val src = (p.args[0] as? List<*>)?.filterNotNull() ?: return@hookBefore
                    fullData[ad] = ArrayList(src)
                    if (currentFilter.active) p.args[0] = src.filter { matches(it) }
                }
                cls.findMethod { name == "addData" && parameterTypes.size == 1 }.hookBefore { p ->
                    if (!Config.isFeatureEnabled("switch_nearby_sort")) return@hookBefore
                    if (internalUpdate.get() == true) { internalUpdate.set(false); return@hookBefore }
                    val ad = p.thisObject ?: return@hookBefore
                    adapters.add(ad)
                    val src = (p.args[0] as? Collection<*>)?.filterNotNull() ?: return@hookBefore
                    fullData.getOrPut(ad) { mutableListOf() }.addAll(src)
                    if (currentFilter.active) p.args[0] = src.filter { matches(it) }
                }
            }

            XposedBridge.log("$TAG init OK (fragment字段sortTabBar + J/onInitView + Compose筛选)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG init 失败: $t")
        }
    }

    // =========================================================================
    //  从 fragment 实例读 sortTabBar 字段, 右移 + 加粗 + 注入筛选 tab
    // =========================================================================
    private fun patchFromFragment(fragment: Any, from: String) {
        val bar: LinearLayout? = try {
            XposedHelpers.getObjectField(fragment, "sortTabBar") as? LinearLayout
        } catch (_: Throwable) { null }

        if (bar == null) {
            XposedBridge.log("$TAG [$from] sortTabBar 字段为 null (时机过早?)")
            return
        }
        val ctx = bar.context
        val res = ctx.resources
        val tvSortId = res.getIdentifier("tv_sort", "id", ctx.packageName)

        // ① 整个 sortTabBar 往右平移 5dp (在线/距离/筛选 一起右移)
        val shift = dp(ctx, 5).toFloat()
        if (bar.translationX < shift) bar.translationX = shift

        // ② 加粗所有 tab 文字 (幂等)
        if (tvSortId != 0) {
            for (i in 0 until bar.childCount) {
                (bar.getChildAt(i).findViewById<View>(tvSortId) as? TextView)
                    ?.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }
        }

        // ③ 追加「筛选」tab (防重复)
        if (bar.findViewWithTag<View>(FILTER_TAG) == null) {
            val ok = addFilterTab(bar, ctx, tvSortId)
            XposedBridge.log("$TAG [$from] 筛选tab added=$ok childCount=${bar.childCount}")
        }
    }

    private fun addFilterTab(bar: LinearLayout, ctx: Context, tvSortId: Int): Boolean {
        val res = ctx.resources
        val tabLayoutId = res.getIdentifier("layout_nearby_people_tab", "layout", ctx.packageName)
        val tab: View = if (tabLayoutId != 0) {
            try { LayoutInflater.from(ctx).inflate(tabLayoutId, bar, false) }
            catch (t: Throwable) { XposedBridge.log("$TAG inflate tab 失败: ${t.message}"); null }
                ?: buildFallbackTab(ctx)
        } else buildFallbackTab(ctx)

        tab.tag = FILTER_TAG
        tab.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { marginStart = dp(ctx, 6) }

        if (tvSortId != 0) {
            (tab.findViewById<View>(tvSortId) as? TextView)?.apply {
                text = "筛选"; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(0xFF333333.toInt())
            }
        }
        val ivSortId = res.getIdentifier("iv_sort", "id", ctx.packageName)
        val filterIcon = res.getIdentifier("icon_arrow_down_filter", "drawable", ctx.packageName)
        if (ivSortId != 0 && filterIcon != 0) {
            (tab.findViewById<View>(ivSortId) as? ImageView)?.setImageResource(filterIcon)
        }
        val layoutSortId = res.getIdentifier("layout_sort", "id", ctx.packageName)
        val clickTarget = (if (layoutSortId != 0) tab.findViewById<View>(layoutSortId) else null) ?: tab
        clickTarget.setOnClickListener { showFilterPanel(ctx) }
        // 长按筛选 tab → 恢复被隐藏的悬浮球 (隐藏悬浮球后的恢复入口之一)
        clickTarget.setOnLongClickListener {
            try {
                val act = ctx as? Activity
                if (act != null) {
                    FloatButtonInjector.unhide(act)
                    Toast.makeText(ctx, "悬浮球已恢复", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Throwable) {}
            true
        }

        tab.visibility = View.VISIBLE
        bar.addView(tab)
        return true
    }

    /** 官方布局 inflate 失败时的兜底胶囊 */
    private fun buildFallbackTab(ctx: Context): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4))
            val gd = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat(); setColor(0x11000000)
            }
            background = gd
        }
        val tv = TextView(ctx).apply {
            text = "筛选"; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 15f; setTextColor(0xFF333333.toInt())
        }
        val iv = ImageView(ctx).apply {
            val filterIcon = resources.getIdentifier("icon_arrow_down_filter", "drawable", ctx.packageName)
            if (filterIcon != 0) setImageResource(filterIcon)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 14), dp(ctx, 14)).apply { marginStart = dp(ctx, 3) }
        }
        row.addView(tv); row.addView(iv)
        return row
    }

    // =========================================================================
    //  Compose 筛选面板
    // =========================================================================
    private fun showFilterPanel(ctx: Context) {
        val activity = unwrapActivity(ctx) ?: run {
            XposedBridge.log("$TAG 无法获取 Activity, 跳过筛选面板")
            return
        }
        ensureFilterLoaded()
        val snapshot = currentFilter
        try {
            com.example.ui.showHostComposePanel(activity) { onClose ->
                com.example.ui.theme.MyApplicationTheme {
                    com.example.ui.NearbyFilterPanel(
                        initial = snapshot,
                        onApply = { state -> persist(state); reapplyAll() },
                        onReset = { persist(NearbyFilterState()) },
                        onClose = onClose
                    )
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Compose 面板失败, 回退无操作: $t")
        }
    }

    /** 从 Context 逐层解包出 Activity (view.context 可能是 ContextThemeWrapper)。 */
    private fun unwrapActivity(ctx: Context?): Activity? {
        var c = ctx
        while (c != null && c !is Activity && c is ContextWrapper) c = c.baseContext
        return c as? Activity
    }

    private fun reapplyAll() {
        val snap = synchronized(adapters) { adapters.toList() }
        for (ad in snap) {
            // 若 fullData 还没缓住, 主动 getData() 取当前列表 (手术刀同策略)
            if (fullData[ad] == null) {
                try {
                    val cur = XposedHelpers.callMethod(ad, "getData") as? List<*>
                    if (!cur.isNullOrEmpty()) fullData[ad] = ArrayList(cur.filterNotNull())
                } catch (_: Throwable) {}
            }
            val full = fullData[ad] ?: continue
            // 提前过滤好, 这样 setNewData 传的已是结果, ThreadLocal 让 hook 放行不再二次过滤
            val data = if (currentFilter.active) full.filter { matches(it) } else ArrayList(full)
            internalUpdate.set(true)
            try { XposedHelpers.callMethod(ad, "setNewData", ArrayList(data)) }
            catch (t: Throwable) { internalUpdate.set(false); XposedBridge.log("$TAG reapply 失败: $t") }
        }
    }

    // =========================================================================
    //  匹配逻辑 (字段均为 UserBasicModel/UserFindResult 公有字段, 反射读取)
    // =========================================================================
    private fun matches(item: Any): Boolean {
        val st = currentFilter
        val uid = tryStr(item, "uid") ?: ""
        val raw = if (uid.isNotEmpty()) rawByUid[uid] else null

        // 角色 (精确值匹配: 1/0.75/0.5/0.25/0/-1/-2)
        if (st.roleExact.isNotEmpty()) {
            val r = rawNum(raw, item, "role")
            if (r == null || st.roleExact.none { kotlin.math.abs(it - r) < 1e-9 }) return false
        }
        // 在线状态 (仅 1=在线 / 0=离线; 2=最近在线 不提供筛选)
        if (st.onlineStates.isNotEmpty()) {
            val o = raw?.optInt("online_state", -1) ?: (tryInt(item, "online_state") ?: -1)
            if (o !in st.onlineStates) return false
        }
        // 资质三态 (优先原始 JSON; is_new/anchor_sign_status 仅存于 JSON, 模型被 Gson 丢弃)
        if (!tri(rawInt(raw, item, "vip_grade") > 0, st.vipMode)) return false
        if (!tri(rawInt(raw, item, "is_vip_annual") == 1, st.annualVipMode)) return false
        if (!tri(rawInt(raw, item, "is_show_real_text") == 1, st.realMode)) return false
        if (!tri(hasAlbum(raw, item), st.albumMode)) return false
        if (!tri(rawInt(raw, item, "is_new") == 1, st.newMode)) return false
        if (!tri(rawInt(raw, item, "is_shadow") == 1, st.shadowMode)) return false
        // 主播: anchor_sign_status != 0 或 live != 0 (两者仅存于 JSON)
        val isAnchor = (raw?.optInt("anchor_sign_status", 0) ?: rawInt(raw, item, "anchor_sign_status")) != 0 ||
            (raw?.optInt("live", 0) ?: rawInt(raw, item, "live")) != 0
        if (!tri(isAnchor, st.anchorMode)) return false
        return true
    }

    /** 取整型标记: raw 优先, 回退模型反射 (默认 0)。 */
    private fun rawInt(raw: JSONObject?, item: Any, key: String): Int {
        if (raw != null && raw.has(key)) return raw.optInt(key, 0)
        return tryInt(item, key) ?: 0
    }

    /** 取数值 (角色): raw 优先, 回退模型反射。 */
    private fun rawNum(raw: JSONObject?, item: Any, key: String): Double? {
        if (raw != null && raw.has(key)) {
            raw.optString(key, "").toDoubleOrNull()?.let { return it }
            val d = raw.optDouble(key, Double.NaN)
            return if (d.isNaN()) null else d
        }
        return tryNum(item, key)
    }

    /** 相册: 原始 JSON personal_card_album 非空, 回退模型 list.size>0。 */
    private fun hasAlbum(raw: JSONObject?, item: Any): Boolean {
        if (raw != null) {
            val a = raw.optJSONArray("personal_card_album")
            if (a != null && a.length() > 0) return true
            if (raw.optString("blued_pic", "").isNotEmpty()) return true
        }
        return tryListSize(item, "personal_card_album") > 0
    }

    /** 三态判定: NONE 不过滤 / ONLY 需成立 / EXCLUDE 需不成立。 */
    private fun tri(condition: Boolean, mode: TriState): Boolean = when (mode) {
        TriState.NONE -> true
        TriState.ONLY -> condition
        TriState.EXCLUDE -> !condition
    }

    private fun tryStr(o: Any, f: String): String? = try { XposedHelpers.getObjectField(o, f) as? String } catch (_: Throwable) { null }
    private fun tryInt(o: Any, f: String): Int? = try {
        when (val v = XposedHelpers.getObjectField(o, f)) {
            is Number -> v.toInt(); is String -> v.toIntOrNull(); else -> null
        }
    } catch (_: Throwable) { null }
    private fun tryNum(o: Any, f: String): Double? = try {
        when (val v = XposedHelpers.getObjectField(o, f)) {
            is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null
        }
    } catch (_: Throwable) { null }
    private fun tryListSize(o: Any, f: String): Int = try { (XposedHelpers.getObjectField(o, f) as? List<*>)?.size ?: 0 } catch (_: Throwable) { 0 }
    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
}

/**
 * 附近筛选三态 (资质「仅看 / 不看」)。NONE 不过滤, ONLY 必须满足, EXCLUDE 必须不满足。
 */
enum class TriState { NONE, ONLY, EXCLUDE }

/**
 * 附近筛选条件快照 (与 [com.example.ui.NearbyFilterPanel] 共用)。
 *
 * - roleExact: role 精确值集合 (1/0.75/0.5/0.25/0/-1/-2)
 * - onlineStates: online_state 集合 (仅 1=在线 / 0=离线)
 * - *Mode: 资质三态 (VIP / 年费VIP / 真人认证 / 相册 / 新人 / 影子用户 / 主播)
 */
data class NearbyFilterState(
    val roleExact: Set<Double> = emptySet(),
    val onlineStates: Set<Int> = emptySet(),
    val vipMode: TriState = TriState.NONE,
    val annualVipMode: TriState = TriState.NONE,
    val realMode: TriState = TriState.NONE,
    val albumMode: TriState = TriState.NONE,
    val newMode: TriState = TriState.NONE,
    val shadowMode: TriState = TriState.NONE,
    val anchorMode: TriState = TriState.NONE,
) {
    val active: Boolean get() = roleExact.isNotEmpty() || onlineStates.isNotEmpty() ||
        listOf(vipMode, annualVipMode, realMode, albumMode, newMode, shadowMode, anchorMode).any { it != TriState.NONE }

    // ===== 持久化 (JSON) =====
    //  保存到 Config key=NearbySortHook.KEY_FILTER, 下次启动 init() 读回, 避免每次开软件重置筛选。
    fun toJson(): String = try {
        val o = JSONObject()
        o.put("roleExact", JSONArray(roleExact))
        o.put("onlineStates", JSONArray(onlineStates))
        o.put("vipMode", vipMode.name)
        o.put("annualVipMode", annualVipMode.name)
        o.put("realMode", realMode.name)
        o.put("albumMode", albumMode.name)
        o.put("newMode", newMode.name)
        o.put("shadowMode", shadowMode.name)
        o.put("anchorMode", anchorMode.name)
        o.put("v", 1)
        o.toString()
    } catch (_: Throwable) { "{}" }

    companion object {
        fun fromJson(s: String?): NearbyFilterState {
            if (s.isNullOrBlank() || s == "null") return NearbyFilterState()
            return try {
                val o = JSONObject(s)
                val roles = linkedSetOf<Double>()
                o.optJSONArray("roleExact")?.let { ra ->
                    for (i in 0 until ra.length()) {
                        val d = ra.optDouble(i, Double.NaN)
                        if (!d.isNaN()) roles.add(d)
                    }
                }
                val online = linkedSetOf<Int>()
                o.optJSONArray("onlineStates")?.let { oa ->
                    for (i in 0 until oa.length()) {
                        val v = oa.optInt(i, Int.MIN_VALUE)
                        if (v != Int.MIN_VALUE) online.add(v)
                    }
                }
                NearbyFilterState(
                    roleExact = roles,
                    onlineStates = online,
                    vipMode = parseTri(o, "vipMode"),
                    annualVipMode = parseTri(o, "annualVipMode"),
                    realMode = parseTri(o, "realMode"),
                    albumMode = parseTri(o, "albumMode"),
                    newMode = parseTri(o, "newMode"),
                    shadowMode = parseTri(o, "shadowMode"),
                    anchorMode = parseTri(o, "anchorMode"),
                )
            } catch (_: Throwable) { NearbyFilterState() }
        }

        private fun parseTri(o: JSONObject, key: String): TriState = try {
            TriState.valueOf(o.optString(key, "NONE"))
        } catch (_: Throwable) { TriState.NONE }
    }
}
