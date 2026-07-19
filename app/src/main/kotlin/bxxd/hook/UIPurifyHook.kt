package bxxd.hook

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ============================================================================
 *  UI 净化/改造
 * ============================================================================
 *
 *  ① 聊天安全提示移除 —— 净化开关 purify_chat_safe
 *     - common_safe_root (msg_safe_common_system): 可滑动风险卡片 (防艾/平台提示)
 *       ⚠️ 关键: 运行时 APK 的 resource id 被重映射, getIdentifier("common_safe_root")
 *          可能返回 0 → findAncestorById 返回 null → 静默失败!
 *       ⚠️ 该布局走非标准加载路径 (ViewBinding bind), inflate hook 抓不到!
 *       方案: 锚点 SafeTabLayout 构造 + 方法 a(), 找祖先时【不依赖 id】,
 *             改为从 SafeTabLayout 往上找最顶层的 android.widget.LinearLayout (= common_safe_root)
 *     - sensitive_root (msg_safe_sensitive): inflate hook, 多重 GONE + PreDraw 防 re-show/闪烁
 *
 *  ② 用户资料页 sll_chat_button —— 独立开关 switch_chat_button_style
 *     - 56dp 圆形 + reparent 到 fl_all 右下角 + 图标+文字垂直布局
 * ============================================================================
 */
object UIPurifyHook : BaseHook {

    private var idsResolved = false
    private var safeRootId = 0          // common_safe_root (可能解析失败=0, 有兜底)
    private var sensitiveRootId = 0     // sensitive_root
    private var chatBtnId = 0           // sll_chat_button
    private var chatIconId = 0          // iv_chat_icon
    private var chatTextId = 0          // tv_chat_button
    private var flAllId = 0             // fl_all

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        // ① common_safe_root 滑动卡片 —— SafeTabLayout 构造 + 方法 a() 双锚点
        try {
            val safeTabClass = lpparam.classLoader.loadClass(
                "com.soft.blued.ui.msg.view.SafeTabLayout")
            val hookCb = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? View ?: return
                        if (!AdsHook.isPurifyEnabled("purify_chat_safe", view.context)) return
                        if (!idsResolved) resolveIds(view.context)
                        hideSafeCard(view)
                        view.post { hideSafeCard(view) }
                        view.postDelayed({ hideSafeCard(view) }, 100)
                        view.postDelayed({ hideSafeCard(view) }, 300)
                    } catch (_: Throwable) {}
                }
            }
            XposedBridge.hookAllConstructors(safeTabClass, hookCb)
            XposedBridge.hookAllMethods(safeTabClass, "a", hookCb)
            XposedBridge.log("llhook uipure: SafeTabLayout ctor+a hooked")
        } catch (e: Throwable) {
            XposedBridge.log("llhook uipure: SafeTabLayout hook fail: $e")
        }

        // ① sensitive_root 安全提示卡片 —— inflate hook + PreDraw 防 re-show
        try {
            val inflaterClass = lpparam.classLoader.loadClass("android.view.LayoutInflater")
            XposedBridge.hookAllMethods(inflaterClass, "inflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val root = param.result as? View ?: return
                        val ctx = root.context ?: return
                        if (!idsResolved) resolveIds(ctx)
                        if (!AdsHook.isPurifyEnabled("purify_chat_safe", ctx)) return
                        if (sensitiveRootId == 0) return
                        // root 本身或子树含 sensitive_root
                        val target = if (root.id == sensitiveRootId) root
                                     else root.findViewById<View>(sensitiveRootId)
                        if (target != null) {
                            target.visibility = View.GONE
                            target.post { target.visibility = View.GONE }
                            // PreDraw 拦截任何后续 setVisibility(VISIBLE) 重置, 消除闪烁
                            try {
                                target.viewTreeObserver.addOnPreDrawListener(object :
                                    android.view.ViewTreeObserver.OnPreDrawListener {
                                    override fun onPreDraw(): Boolean {
                                        if (target.visibility != View.GONE) target.visibility = View.GONE
                                        return true
                                    }
                                })
                            } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }
            })
        } catch (_: Throwable) {}

        // ② 资料页聊天按钮改造
        try {
            val inflaterClass = lpparam.classLoader.loadClass("android.view.LayoutInflater")
            XposedBridge.hookAllMethods(inflaterClass, "inflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (!Config.isFeatureEnabled("switch_chat_button_style")) return
                        val root = param.result as? View ?: return
                        val ctx = root.context ?: return
                        if (!idsResolved) resolveIds(ctx)
                        if (chatBtnId == 0) return
                        val chatBtn = root.findViewById<View>(chatBtnId) ?: return
                        restyleAndRelocateChatButton(chatBtn)
                    } catch (e: Throwable) {
                        XposedBridge.log("llhook uipure chatbtn err: $e")
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    /**
     * 隐藏 common_safe_root 卡片:
     *  - 优先按 id 查找 (id 可能解析失败)
     *  - 兜底: 从 SafeTabLayout 往上找最顶层的 android.widget.LinearLayout (= common_safe_root)
     *    结构: SafeTabLayout → ConstraintLayout → ShapeLinearLayout → common_safe_root(LinearLayout)
     */
    private fun hideSafeCard(safeTab: View) {
        try {
            val target = findCardRoot(safeTab)
            if (target != null && target.visibility != View.GONE) {
                target.visibility = View.GONE
            }
        } catch (_: Throwable) {}
    }

    private fun findCardRoot(view: View): View? {
        // 1) 优先按 id
        if (safeRootId != 0) {
            var n: View? = view
            while (n != null) {
                if (n.id == safeRootId) return n
                n = (n.parent as? View)
            }
        }
        // 2) 兜底: 找最顶层的普通 android.widget.LinearLayout (common_safe_root 是这个类型)
        //    ShapeLinearLayout 类名含 ShapeLinearLayout, 跳过; 找纯净 LinearLayout
        var best: View? = null
        var n2: View? = view
        var depth = 0
        while (n2 != null && depth < 8) {
            val cn = n2.javaClass.name
            if (cn == "android.widget.LinearLayout") best = n2
            n2 = (n2.parent as? View)
            depth++
        }
        return best
    }

    private fun resolveIds(ctx: Context) {
        try {
            val res = ctx.resources
            val pkg = ctx.packageName
            safeRootId = res.getIdentifier("common_safe_root", "id", pkg)
            sensitiveRootId = res.getIdentifier("sensitive_root", "id", pkg)
            chatBtnId = res.getIdentifier("sll_chat_button", "id", pkg)
            chatIconId = res.getIdentifier("iv_chat_icon", "id", pkg)
            chatTextId = res.getIdentifier("tv_chat_button", "id", pkg)
            flAllId = res.getIdentifier("fl_all", "id", pkg)
            XposedBridge.log("llhook uipure ids: safe=$safeRootId sens=$sensitiveRootId chatBtn=$chatBtnId flAll=$flAllId")
        } catch (_: Throwable) {} finally { idsResolved = true }
    }

    /**
     * sll_chat_button 改造: 56dp 圆形 + reparent 到 fl_all 右下角 + 纯文字
     */
    private fun restyleAndRelocateChatButton(chatBtn: View) {
        chatBtn.post {
            try {
                val ctx = chatBtn.context
                val btnSize = dp(ctx, 56f)

                // 1) 隐藏图标, 只留文字 (居中显示)
                (chatBtn as? LinearLayout)?.gravity = Gravity.CENTER
                chatBtn.findViewById<View>(chatIconId)?.visibility = View.GONE
                // 文字显示, 字号加大 (14sp)
                chatBtn.findViewById<TextView>(chatTextId)?.let { tv ->
                    tv.visibility = View.VISIBLE
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    tv.setTextColor(Color.WHITE)
                    tv.includeFontPadding = false
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.gravity = Gravity.CENTER
                    tv.layoutParams = lp
                }

                // 2) 圆形蓝色背景
                chatBtn.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#2196F3"))
                }
                chatBtn.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                chatBtn.clipToOutline = true
                chatBtn.setPadding(0, dp(ctx, 8f), 0, dp(ctx, 8f))
                chatBtn.elevation = dp(ctx, 8f).toFloat()

                // 3) reparent 到 fl_all 右下角
                val host = findAncestorById(chatBtn, flAllId)
                if (host is ViewGroup && host !== chatBtn.parent) {
                    (chatBtn.parent as? ViewGroup)?.removeView(chatBtn)
                    val lp = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                        rightMargin = dp(ctx, 18f)
                        bottomMargin = dp(ctx, 90f)
                    }
                    host.addView(chatBtn, lp)
                } else {
                    val lp = chatBtn.layoutParams
                    if (lp != null) { lp.width = btnSize; lp.height = btnSize; chatBtn.layoutParams = lp }
                }
            } catch (e: Throwable) { XposedBridge.log("llhook uipure relocate err: $e") }
        }
    }

    private fun findAncestorById(view: View, targetId: Int): View? {
        if (targetId == 0) return null
        var node: View? = view
        while (node != null) {
            if (node.id == targetId) return node
            node = (node.parent as? View)
        }
        return null
    }

    private fun dp(ctx: Context, dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.resources.displayMetrics).toInt()
}
