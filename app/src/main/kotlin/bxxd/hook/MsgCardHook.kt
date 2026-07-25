package bxxd.hook

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val TAG = "LlhookMsgCard"

/**
 * 消息列表卡片化: 把每条会话项 (item_msg_friend_list) 改成圆角卡片背景 (纯色/渐变)。
 *
 *  定位: hook [MsgAdapter.getView] (ListView 适配器, 不是 RecyclerView),
 *  返回的 item view 内 [ll_msg_f_root] (id=0x7f0a1d8a, item 内容根 LinearLayout)
 *  即卡片背景下发目标。
 *
 *  与 NearbyCardHook 思路一致, 但锚点不同: 消息 item 内容根直接是 ll_msg_f_root,
 *  无需从子锚点回溯。配置键独立 (msg_card_*), 不影响附近列表。
 *
 *  诊断走 Xposed 日志 (XposedBridge.log)。
 *
 *  开关: switch_msg_card
 */
object MsgCardHook : BaseHook {

    private const val MSG_ADAPTER = "com.soft.blued.ui.msg.adapter.MsgAdapter"

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = lpparam.classLoader.loadClass(MSG_ADAPTER)
            // MsgAdapter extends BaseAdapter, getView(int, View, ViewGroup): View
            // (jadx 未反编译该方法体, 但运行时存在, 按签名 hook 即可)
            cls.findMethod {
                name == "getView" && parameterTypes.size == 3 &&
                    parameterTypes[0] == Int::class.javaPrimitiveType &&
                    parameterTypes[1] == View::class.java &&
                    parameterTypes[2] == ViewGroup::class.java
            }.hookAfter { param ->
                try {
                    val view = param.result as? View ?: return@hookAfter
                    applyCard(view)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG getView err: $t")
                }
            }
            XposedBridge.log("$TAG hooked MsgAdapter.getView OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG init fail: $t")
        }
    }

    private fun applyCard(itemView: View) {
        val ctx = itemView.context
        if (!Config.isFeatureEnabledFresh("switch_msg_card", ctx)) return
        val res = ctx.resources
        val pkg = ctx.packageName

        // ll_msg_f_root = item 内容根 LinearLayout (头像 + 消息预览那一块)
        val rootId = res.getIdentifier("ll_msg_f_root", "id", pkg)
        if (rootId == 0) { XposedBridge.log("$TAG ll_msg_f_root id=0(版本不符)"); return }
        val target = itemView.findViewById<View>(rootId)
            ?: run { XposedBridge.log("$TAG ll_msg_f_root 未命中(非消息列表项)"); return }

        val radius = dpF(ctx, Config.readRawLocal("msg_card_radius", ctx).let { if (it=="null"||it.isEmpty())"16" else it }.toFloatOrNull() ?: 16f)
        val alphaPct = (Config.readRawLocal("msg_card_alpha", ctx).let { if (it=="null"||it.isEmpty())"100" else it }.toFloatOrNull() ?: 100f).coerceIn(0f, 100f)
        val gap = dp(ctx, Config.readRawLocal("msg_card_gap", ctx).let { if (it=="null"||it.isEmpty())"6" else it }.toFloatOrNull() ?: 6f)
        val marginH = dp(ctx, Config.readRawLocal("msg_card_margin_h", ctx).let { if (it=="null"||it.isEmpty())"8" else it }.toFloatOrNull() ?: 8f)
        val gradient = Config.isFeatureEnabledFresh("msg_card_gradient", ctx)
        val rawColorStr = Config.readRawLocal("msg_card_color", ctx).let {
            if (it == "null" || it.isEmpty()) "0xFFE2E8F0" else it
        }
        var color1 = applyAlpha(parseColor(rawColorStr), alphaPct)
        val fellBack = isNearWhite(color1)
        if (fellBack) color1 = applyAlpha(0xFF94A3B8.toInt(), alphaPct)   // slate-400
        val color2 = applyAlpha(parseColor(Config.readRawLocal("msg_card_color2", ctx).let { if (it == "null" || it.isEmpty()) "0xFFE0E7FF" else it }), alphaPct)
        val strokeW = dp(ctx, 1f)
        val strokeColor = 0x33000000

        fun buildBg() = GradientDrawable().apply {
            cornerRadius = radius
            setStroke(strokeW, strokeColor)
            if (gradient) { colors = intArrayOf(color1, color2); orientation = GradientDrawable.Orientation.LEFT_RIGHT }
            else setColor(color1)
        }

        target.background = buildBg()
        applyRoundedOutline(target, radius)

        val lp = target.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.leftMargin = marginH
            lp.rightMargin = marginH
            lp.bottomMargin = gap
            target.layoutParams = lp
        }
        target.invalidate()

        if (fellBack) XposedBridge.log("$TAG 近白兑底: 原值=$rawColorStr → slate-400")

        try {
            val t = target
            itemView.post {
                if (!Config.isFeatureEnabledFresh("switch_msg_card", ctx)) return@post
                t.background = buildBg(); t.invalidate()
            }
        } catch (_: Throwable) {}
    }

    private fun applyRoundedOutline(view: View, radius: Float) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) return
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            view.clipToOutline = true
            (view as? ViewGroup)?.clipChildren = true
        } catch (_: Throwable) {}
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

    private fun dp(ctx: android.content.Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun dpF(ctx: android.content.Context, v: Float): Float =
        v * ctx.resources.displayMetrics.density
}
