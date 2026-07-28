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

private const val TAG = "LlhookNearbyCard"

/**
 * 附近(身边)列表卡片化: 把每条列表项改成圆角卡片背景 (纯色/渐变)。
 *
 *  定位: 锚点 [ll_distance_and_time] 确认是 people list 项 (过滤宫格/广告), 从锚点向上
 *  回溯 fl_main (item 根) 设背景。
 *
 *  诊断走 Xposed 日志 (XposedBridge.log), 不再弹 Toast 打扰用户。
 *
 *  开关: switch_nearby_card
 */
object NearbyCardHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val baseAdapterClass =
                lpparam.classLoader.loadClass("com.chad.library.adapter.base.BaseQuickAdapter")
            baseAdapterClass.findMethod {
                name == "onBindViewHolder" && parameterTypes.size == 2
            }.hookAfter { param ->
                try {
                    val holder = param.args[0] ?: return@hookAfter
                    val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View
                        ?: return@hookAfter
                    applyCard(itemView)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG bind err: $t")
                }
            }
            XposedBridge.log("$TAG hooked BaseQuickAdapter.onBindViewHolder OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG init fail: $t")
        }
    }

    private fun applyCard(itemView: View) {
        val ctx = itemView.context
        if (!Config.isFeatureEnabledFresh("switch_nearby_card", ctx)) return
        val res = ctx.resources
        val pkg = ctx.packageName

        // 锚点: ll_distance_and_time 确认是 people list 项 (过滤非目标 item / 宫格卡片)
        val anchorId = res.getIdentifier("ll_distance_and_time", "id", pkg)
        if (anchorId == 0) return
        val anchor = itemView.findViewById<View>(anchorId) ?: return

        // 从锚点向上回溯 fl_main (item 内容根); 找不到就用 itemView
        val flMainId = res.getIdentifier("fl_main", "id", pkg)
        var target: View = itemView
        var found = false
        if (flMainId != 0) {
            var p: View? = anchor
            var guard = 0
            while (p != null && guard++ < 30) {
                if (p.id == flMainId) { target = p; found = true; break }
                p = p.parent as? View
            }
            if (!found && itemView.id == flMainId) { target = itemView; found = true }
        }
        if (!found) return

        val radius = dpF(ctx, Config.readRawLocal("nearby_card_radius", ctx).let { if (it=="null"||it.isEmpty())"16" else it }.toFloatOrNull() ?: 16f)
        val alphaPct = (Config.readRawLocal("nearby_card_alpha", ctx).let { if (it=="null"||it.isEmpty())"100" else it }.toFloatOrNull() ?: 100f).coerceIn(0f, 100f)
        val gap = dp(ctx, Config.readRawLocal("nearby_card_gap", ctx).let { if (it=="null"||it.isEmpty())"6" else it }.toFloatOrNull() ?: 6f)
        val marginH = dp(ctx, Config.readRawLocal("nearby_card_margin_h", ctx).let { if (it=="null"||it.isEmpty())"8" else it }.toFloatOrNull() ?: 8f)
        val gradient = Config.isFeatureEnabledFresh("nearby_card_gradient", ctx)
        // 默认明显浅灰: 身边页底色纯白, 太浅会"白底白卡"
        // ★ 直接读 Blued 本地 prefs 原始值 (绕过 getFresh 逻辑, 与诊断一致, 最可靠)
        val rawColorStr = Config.readRawLocal("nearby_card_color", ctx).let {
            if (it == "null" || it.isEmpty()) "0xFFE2E8F0" else it
        }
        var color1 = applyAlpha(parseColor(rawColorStr), alphaPct)
        // ★ 白色兑底: 身边页底色 #FFFFFFFF(纯白), 若读到白色/近白(常为旧缓存) 会不可见 →
        //   强制改用明显灰, 让卡片始终可见。
        val fellBack = isNearWhite(color1)
        if (fellBack) color1 = applyAlpha(0xFF94A3B8.toInt(), alphaPct)   // slate-400 明显灰
        val color2 = applyAlpha(parseColor(Config.readRawLocal("nearby_card_color2", ctx).let { if (it == "null" || it.isEmpty()) "0xFFE0E7FF" else it }), alphaPct)
        val strokeW = dp(ctx, 1f)
        val strokeColor = 0x33000000   // 加深描边: 任何底色下轮廓清晰可辨

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

        // 下一帧补发: 兜底被当帧重置
        try {
            val t = target
            itemView.post {
                if (!Config.isFeatureEnabledFresh("switch_nearby_card", ctx)) return@post
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
        // ★ 修复: "0x..." 必须按 16 进制解析。
        //   旧实现 s.toLong() 默认 radix=10, 而 "0xFF22C55E" 不是合法十进制 →
        //   抛 NumberFormatException → 兜底 Color.WHITE(0xFFFFFFFF) → 命中 isNearWhite
        //   → 永远走硬编码兑底色(灰/紫), 用户在面板保存的任何颜色都失效。
        val t = s.trim()
        when {
            t.startsWith("0x", true) -> t.substring(2).toLong(16).toInt()
            t.startsWith("#") -> t.substring(1).toLong(16).toInt()
            t.startsWith("-") -> t.toLong(10).toInt()   // Color.toArgb().toString() 的负值
            else -> Color.parseColor(t)                  // 命名色 / #RRGGBB
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

    private fun dp(ctx: android.content.Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun dpF(ctx: android.content.Context, v: Float): Float =
        v * ctx.resources.displayMetrics.density
}

