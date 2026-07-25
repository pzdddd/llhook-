package bxxd.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.Gravity
import android.view.View
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val TAG = "LlhookNearbyPageBg"

/**
 * 身边(附近)页背景: 给整页设置自定义颜色/渐变/图片。
 *
 *  诊断走 Xposed 日志 (XposedBridge.log), 不再弹 Toast 打扰用户。
 *
 *  定位: fragment 字段 [coordinator] (id=0x7f0a0640) 优先, getView().findViewById 兜底。
 *  下发目标: root + SmartRefreshLayout + coordinator + appbar + recycler_view 多层冗余,
 *  其中 recycler/root 在 XML 里无 background 属性 → 不被 Blued 皮肤引擎重置, 最稳定。
 *
 *  开关: switch_nearby_page_bg
 *  参数: nearby_page_bg_color/color2/alpha/gradient/use_image/image/image_alpha
 */
object NearbyPageBgHook : BaseHook {

    @Volatile private var cachedBmp: Bitmap? = null
    @Volatile private var cachedKey: String = ""

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fragCls =
                lpparam.classLoader.loadClass("com.soft.blued.ui.find.fragment.NearbyPeopleFragment")
            var hooked = 0
            listOf("onInitView" to 1, "J" to 0).forEach { (name, argc) ->
                try {
                    fragCls.findMethod { this.name == name && parameterTypes.size == argc }
                        .hookAfter { p ->
                            try {
                                if (!Config.isFeatureEnabledFresh("switch_nearby_page_bg")) return@hookAfter
                                patch(p.thisObject, "frag:$name")
                            } catch (t: Throwable) { XposedBridge.log("$TAG $name err: $t") }
                        }
                    hooked++
                } catch (_: Throwable) {}
            }
            listOf("onResume" to 0, "onHiddenChanged" to 1, "setUserVisibleHint" to 1, "onActivityCreated" to 1).forEach { (name, argc) ->
                try {
                    fragCls.findMethod { this.name == name && parameterTypes.size == argc }
                        .hookAfter { p ->
                            try {
                                if (!Config.isFeatureEnabledFresh("switch_nearby_page_bg")) return@hookAfter
                                patch(p.thisObject, "lc:$name")
                            } catch (_: Throwable) {}
                        }
                    hooked++
                } catch (_: Throwable) {}
            }
            XposedBridge.log("$TAG hooked NearbyPeopleFragment methods=$hooked")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG init fail: $t")
        }
    }

    private fun patch(fragment: Any, from: String) {
        val ctx = try { XposedHelpers.callMethod(fragment, "getContext") as? Context }
            catch (_: Throwable) { null } ?: run { toastSafe("$TAG [$from] ctx null"); return }
        val res = ctx.resources
        val pkg = ctx.packageName
        val coordinatorId = res.getIdentifier("coordinator", "id", pkg)
        val appbarId = res.getIdentifier("appbar", "id", pkg)
        val recyclerId = res.getIdentifier("recycler_view", "id", pkg)
        val refreshId = res.getIdentifier("refresh_layout", "id", pkg)
        val tabBarId = res.getIdentifier("tab_bar", "id", pkg)

        // ===== 1) 定位 coordinator =====
        var coordinator: View? = try { XposedHelpers.getObjectField(fragment, "coordinator") as? View }
            catch (_: Throwable) { null }
        if (coordinator == null) {
            val sortTabBar = try { XposedHelpers.getObjectField(fragment, "sortTabBar") as? View }
                catch (_: Throwable) { null }
            coordinator = climbAncestor(sortTabBar, coordinatorId)
        }
        val root: View? = try { XposedHelpers.callMethod(fragment, "getView") as? View }
            catch (_: Throwable) { null }
        if (coordinator == null) coordinator = root?.findViewById(coordinatorId)

        if (coordinator == null && root == null) {
            XposedBridge.log("$TAG [$from] 视图未就绪(等生命周期补)")
            return
        }

        // ===== 2) 收集所有可见背景层 (多层冗余, 任一不被皮肤重置即可见) =====
        val recycler: View? = when {
            coordinator != null && recyclerId != 0 -> coordinator.findViewById(recyclerId)
            root != null && recyclerId != 0 -> root.findViewById(recyclerId)
            else -> null
        }
        val refresh: View? = when {
            coordinator != null && coordinator.id != 0 && refreshId != 0 && coordinator.parent is View ->
                climbAncestor(coordinator, refreshId) ?: (root?.findViewById(refreshId))
            root != null && refreshId != 0 -> root.findViewById(refreshId)
            else -> null
        }

        val useImage = Config.readRawLocal("nearby_page_bg_use_image", ctx).let { it != "null" && it != "" && it.toBoolean() }
        val targets = ArrayList<View>(5)
        root?.let { if (it !== coordinator && it !== refresh) targets.add(it) }
        refresh?.let { targets.add(it) }
        coordinator?.let { targets.add(it) }
        if (coordinator != null && appbarId != 0)
            coordinator.findViewById<View>(appbarId)?.let { targets.add(it) }
        // ★ tab_bar (排序/通话胶囊那一行, id=0x7f0a2a18): XML 里 background=@color/ColorMid01(白),
        //   原本一直白色。用户要求它跟身边页背景同色 → 加入下发目标。
        if (tabBarId != 0) {
            (coordinator?.findViewById<View>(tabBarId) ?: root?.findViewById(tabBarId))?.let { targets.add(it) }
        }
        recycler?.let { if (it !== root) targets.add(it) }

        if (targets.isEmpty()) { XposedBridge.log("$TAG [$from] 无可下发目标"); return }

        // ===== 3) 应用 (每次 patch 都设, 不做静默幂等 → 防皮肤覆盖后不补回) =====
        applyTo(targets, ctx, useImage, from)

        // ===== 4) 近白兑底只记日志 (不再弹 Toast) =====
        val alphaPct = (Config.readRawLocal("nearby_page_bg_alpha", ctx).let { if (it=="null"||it.isEmpty())"100" else it }.toFloatOrNull() ?: 100f).coerceIn(0f, 100f)
        val rawColorStr = Config.readRawLocal("nearby_page_bg_color", ctx).let { if (it=="null"||it.isEmpty())"0xFFE0E7FF" else it }
        if (!useImage && isNearWhite(applyAlpha(parseColor(rawColorStr), alphaPct)))
            XposedBridge.log("$TAG [$from] 近白兑底: 原值=$rawColorStr → indigo-500, 套到${targets.size}层")

        // ===== 5) 下一帧再补发 (兜底被皮肤/当帧重置) =====
        try {
            val postTargets = ArrayList(targets)
            (coordinator ?: root ?: refresh)?.post {
                if (!Config.isFeatureEnabledFresh("switch_nearby_page_bg", ctx)) return@post
                applyTo(postTargets, ctx, useImage, from + ":post")
            }
        } catch (_: Throwable) {}
    }

    private fun applyTo(targets: List<View>, ctx: Context, useImage: Boolean, tag: String) {
        if (targets.isEmpty()) return
        val alphaPct = (Config.readRawLocal("nearby_page_bg_alpha", ctx).let { if (it=="null"||it.isEmpty())"100" else it }.toFloatOrNull() ?: 100f).coerceIn(0f, 100f)
        if (useImage) {
            val imgAlpha = (Config.readRawLocal("nearby_page_bg_image_alpha", ctx).let { if (it=="null"||it.isEmpty())"100" else it }.toFloatOrNull() ?: 100f).coerceIn(0f, 100f)
            val bmp = decodeImage(ctx)
            if (bmp != null) {
                targets.forEach { v -> v.background = BitmapDrawable(ctx.resources, bmp).apply {
                    gravity = Gravity.FILL
                    setAlpha((imgAlpha / 100f * 255).toInt().coerceIn(0, 255))
                } }
            }
        } else {
            val gradient = Config.readRawLocal("nearby_page_bg_gradient", ctx).let { it != "null" && it != "" && it.toBoolean() }
            // ★ 白色兑底: 身边页底色纯白, 读到白色/近白(常为旧缓存) 会不可见 → 强制紫色
            var color1 = applyAlpha(parseColor(Config.readRawLocal("nearby_page_bg_color", ctx).let { if (it=="null"||it.isEmpty())"0xFFE0E7FF" else it }), alphaPct)
            if (isNearWhite(color1)) color1 = applyAlpha(0xFF6366F1.toInt(), alphaPct)   // indigo-500
            val color2 = applyAlpha(parseColor(Config.readRawLocal("nearby_page_bg_color2", ctx).let { if (it=="null"||it.isEmpty())"0xFFDDD6FE" else it }), alphaPct)
            targets.forEach { v -> v.background = makeSolid(gradient, color1, color2); v.invalidate() }
        }
        XposedBridge.log("$TAG [$tag] applied targets=${targets.size}")
    }

    private fun makeSolid(gradient: Boolean, color1: Int, color2: Int): Drawable =
        if (gradient) GradientDrawable().apply {
            colors = intArrayOf(color1, color2)
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        } else GradientDrawable().apply { setColor(color1) }

    private fun climbAncestor(start: View?, targetId: Int): View? {
        var v: View? = start ?: return null
        var guard = 0
        while (v != null && guard++ < 30) {
            if (v.id == targetId) return v
            val parent = v.parent
            v = if (parent is View) parent else null
        }
        return null
    }

    private fun decodeImage(ctx: Context): Bitmap? {
        val key = try { Config.readRawLocal("nearby_page_bg_image", ctx) } catch (_: Throwable) { "" }
        if (key.isEmpty() || key == "null") return null
        if (cachedBmp != null && cachedKey == key) return cachedBmp
        return try {
            val bytes = Base64.decode(key, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            cachedBmp = bmp; cachedKey = key; bmp
        } catch (t: Throwable) { XposedBridge.log("$TAG decode image fail: $t"); null }
    }

    private fun parseColor(s: String): Int = try {
        // ★ 修复: "0x..." 必须按 16 进制解析。
        //   旧实现 s.toLong() 默认 radix=10, 而 "0xFF1E293B" 不是合法十进制 →
        //   抛 NumberFormatException → 兜底 Color.WHITE(0xFFFFFFFF) → 命中 isNearWhite
        //   → 永远走硬编码紫兑底, 用户在面板保存的任何颜色都失效。
        val t = s.trim()
        when {
            t.startsWith("0x", true) -> t.substring(2).toLong(16).toInt()
            t.startsWith("#") -> t.substring(1).toLong(16).toInt()
            t.startsWith("-") -> t.toLong(10).toInt()
            else -> Color.parseColor(t)
        }
    } catch (_: Throwable) { Color.WHITE }

    private fun applyAlpha(color: Int, alphaPct: Float): Int {
        val a = (255 * (alphaPct / 100f)).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    /** 判定近白 (R/G/B 均 >= 0xF0): 在纯白页面上不可见。 */
    private fun isNearWhite(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r >= 0xF0 && g >= 0xF0 && b >= 0xF0
    }

    private fun toastSafe(msg: String) { XposedBridge.log(msg) }

    @JvmStatic
    fun invalidate() { cachedBmp = null; cachedKey = "" }
}

