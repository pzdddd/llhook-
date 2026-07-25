package bxxd.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val TAG = "LlhookMsgPageBg"

/**
 * 消息页背景: 给消息列表区域设置自定义颜色/渐变/图片。
 *
 *  ── 关键定位 (从 fragment_msg.xml + selector_item_msg 确认) ──
 *  布局层级:
 *    keyboardRelativeLayout (整页根, 含搜索栏等, 不要碰)
 *      └─ RelativeLayout └ LinearLayout └ RelativeLayout(0x7f0a2320)
 *            ├─ msg_friend_pullrefresh (RenrenPullToRefreshListView, 下拉刷新容器)
 *            │     └─ ListView (字段 d)   ← 背景下发目标
 *            └─ View (半透明遮罩)
 *
 *  item 背景 (selector_item_msg):
 *    - 默认态 ColorMid01 = 纯白 (完全不透明) → 会完全覆盖 ListView 背景
 *    - 按压态 ColorMid11_05 = 半透明灰       → 会透出 ListView 背景色
 *
 *  所以单纯给 ListView 设背景会:
 *    (a) 列表区域看不到色 (被 item 纯白覆盖)
 *    (b) item 按压时变背景色 (半透明 selector 透色)
 *  并且给 pullRefresh 设背景会让下拉刷新头区域变色。
 *
 *  ── 正确方案 ──
 *  ① 背景只下发到内部 ListView (字段 d), 不动 pullRefresh (下拉刷新头不串色)
 *  ② hook MsgAdapter.getView, 把 item 根 selector 替换为:
 *       默认态 = 透明 (透出 ListView 背景)
 *       按压态 = 半透明白光 (按压反馈, 不透出背景色)
 *     这样列表区域整体显示背景色, item 按压不变色, 下拉刷新不变色。
 *
 *  开关: switch_msg_page_bg
 *  参数: msg_page_bg_color/color2/alpha/gradient/use_image/image/image_alpha
 */
object MsgPageBgHook : BaseHook {

    private const val MSG_FRAGMENT = "com.soft.blued.ui.msg.MsgFragment"
    private const val MSG_ADAPTER = "com.soft.blued.ui.msg.adapter.MsgAdapter"

    @Volatile private var cachedBmp: Bitmap? = null
    @Volatile private var cachedKey: String = ""

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fragCls = lpparam.classLoader.loadClass(MSG_FRAGMENT)
            var hooked = 0
            // 首次构建: onInitView(1) / onViewCreated(2) / onActivityCreated(1)
            listOf(
                "onInitView" to 1,
                "onViewCreated" to 2,
                "onActivityCreated" to 1
            ).forEach { (name, argc) ->
                try {
                    fragCls.findMethod { this.name == name && parameterTypes.size == argc }
                        .hookAfter { p ->
                            try {
                                if (!Config.isFeatureEnabledFresh("switch_msg_page_bg")) return@hookAfter
                                patch(p.thisObject, "frag:$name", p.args.getOrNull(0) as? View)
                            } catch (t: Throwable) { XposedBridge.log("$TAG $name err: $t") }
                        }
                    hooked++
                } catch (_: Throwable) {}
            }
            // 生命周期补回 (皮肤/重布局后重新覆盖)
            listOf("onResume" to 0, "onHiddenChanged" to 1, "setUserVisibleHint" to 1).forEach { (name, argc) ->
                try {
                    fragCls.findMethod { this.name == name && parameterTypes.size == argc }
                        .hookAfter { p ->
                            try {
                                if (!Config.isFeatureEnabledFresh("switch_msg_page_bg")) return@hookAfter
                                patch(p.thisObject, "lc:$name", null)
                            } catch (_: Throwable) {}
                        }
                    hooked++
                } catch (_: Throwable) {}
            }
            XposedBridge.log("$TAG hooked MsgFragment methods=$hooked")

            // ② hook MsgAdapter.getView: 替换 item 根 selector (默认透明 + 按压白光)
            try {
                val adapterCls = lpparam.classLoader.loadClass(MSG_ADAPTER)
                adapterCls.findMethod {
                    name == "getView" && parameterTypes.size == 3 &&
                        parameterTypes[0] == Int::class.javaPrimitiveType &&
                        parameterTypes[1] == View::class.java &&
                        parameterTypes[2] == ViewGroup::class.java
                }.hookAfter { param ->
                    try {
                        if (!Config.isFeatureEnabledFresh("switch_msg_page_bg", null)) return@hookAfter
                        val itemView = param.result as? View ?: return@hookAfter
                        // itemView = item_msg_friend_list 根 RelativeLayout
                        itemView.background = makeItemSelector(itemView.context)
                    } catch (_: Throwable) {}
                }
                XposedBridge.log("$TAG hooked MsgAdapter.getView OK")
            } catch (t: Throwable) { XposedBridge.log("$TAG adapter hook fail: $t") }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG init fail: $t")
        }
    }

    private fun patch(fragment: Any, from: String, rootView: View?) {
        val ctx = try { XposedHelpers.callMethod(fragment, "getContext") as? Context }
            catch (_: Throwable) { null } ?: run { XposedBridge.log("$TAG [$from] ctx null"); return }

        // ★ 只取内部 ListView (字段 d, getRefreshableView), 不取 pullRefresh:
        //   pullRefresh = RenrenPullToRefreshListView, 它的子 view 含下拉刷新头,
        //   给它设背景 → 下拉刷新头区域变色。只给内部 ListView 设背景, 下拉刷新头不受影响。
        val listView: View? = try { XposedHelpers.getObjectField(fragment, "d") as? View }
            catch (_: Throwable) { null }
        if (listView == null) { XposedBridge.log("$TAG [$from] ListView(d) 未就绪(等生命周期补)"); return }

        val useImage = Config.readRawLocal("msg_page_bg_use_image", ctx).let { it != "null" && it != "" && it.toBoolean() }
        val targets = listOf(listView)
        applyTo(targets, ctx, useImage, from)

        val alphaPct = (Config.readRawLocal("msg_page_bg_alpha", ctx).let { if (it=="null"||it.isEmpty())"100" else it }.toFloatOrNull() ?: 100f).coerceIn(0f, 100f)
        val rawColorStr = Config.readRawLocal("msg_page_bg_color", ctx).let { if (it=="null"||it.isEmpty())"0xFFE0E7FF" else it }
        if (!useImage && isNearWhite(applyAlpha(parseColor(rawColorStr), alphaPct)))
            XposedBridge.log("$TAG [$from] 近白兑底: 原值=$rawColorStr → indigo-500")

        // 下一帧补发 (兜底被当帧重置)
        try {
            val postTargets = ArrayList(targets)
            listView.post {
                if (!Config.isFeatureEnabledFresh("switch_msg_page_bg", ctx)) return@post
                applyTo(postTargets, ctx, useImage, from + ":post")
            }
        } catch (_: Throwable) {}
    }

    private fun applyTo(targets: List<View>, ctx: Context, useImage: Boolean, tag: String) {
        if (targets.isEmpty()) return
        val alphaPct = (Config.readRawLocal("msg_page_bg_alpha", ctx).let { if (it=="null"||it.isEmpty())"100" else it }.toFloatOrNull() ?: 100f).coerceIn(0f, 100f)
        if (useImage) {
            val imgAlpha = (Config.readRawLocal("msg_page_bg_image_alpha", ctx).let { if (it=="null"||it.isEmpty())"100" else it }.toFloatOrNull() ?: 100f).coerceIn(0f, 100f)
            val bmp = decodeImage(ctx)
            if (bmp != null) {
                targets.forEach { v -> v.background = BitmapDrawable(ctx.resources, bmp).apply {
                    gravity = Gravity.FILL
                    setAlpha((imgAlpha / 100f * 255).toInt().coerceIn(0, 255))
                } }
            }
        } else {
            val gradient = Config.readRawLocal("msg_page_bg_gradient", ctx).let { it != "null" && it != "" && it.toBoolean() }
            var color1 = applyAlpha(parseColor(Config.readRawLocal("msg_page_bg_color", ctx).let { if (it=="null"||it.isEmpty())"0xFFE0E7FF" else it }), alphaPct)
            if (isNearWhite(color1)) color1 = applyAlpha(0xFF6366F1.toInt(), alphaPct)
            val color2 = applyAlpha(parseColor(Config.readRawLocal("msg_page_bg_color2", ctx).let { if (it=="null"||it.isEmpty())"0xFFDDD6FE" else it }), alphaPct)
            targets.forEach { v -> v.background = makeSolid(gradient, color1, color2); v.invalidate() }
        }
        XposedBridge.log("$TAG [$tag] applied targets=${targets.size}")
    }

    /**
     * item 根 selector: 默认透明 (透出 ListView 背景), 按压半透明白光 (不透出背景色)。
     * 替代原生 selector_item_msg (默认纯白 + 按压半透明灰)。
     */
    private fun makeItemSelector(ctx: Context): StateListDrawable {
        val pressed = GradientDrawable().apply { setColor(0x33FFFFFF) }  // 按压: 白光反馈
        val normal = GradientDrawable().apply { setColor(Color.TRANSPARENT) } // 默认: 透明, 透出背景
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun makeSolid(gradient: Boolean, color1: Int, color2: Int): Drawable =
        if (gradient) GradientDrawable().apply {
            colors = intArrayOf(color1, color2)
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        } else GradientDrawable().apply { setColor(color1) }

    private fun decodeImage(ctx: Context): Bitmap? {
        val key = try { Config.readRawLocal("msg_page_bg_image", ctx) } catch (_: Throwable) { "" }
        if (key.isEmpty() || key == "null") return null
        if (cachedBmp != null && cachedKey == key) return cachedBmp
        return try {
            val bytes = Base64.decode(key, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            cachedBmp = bmp; cachedKey = key; bmp
        } catch (t: Throwable) { XposedBridge.log("$TAG decode image fail: $t"); null }
    }

    private fun parseColor(s: String): Int = try {
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

    private fun isNearWhite(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r >= 0xF0 && g >= 0xF0 && b >= 0xF0
    }

    @JvmStatic
    fun invalidate() { cachedBmp = null; cachedKey = "" }
}
