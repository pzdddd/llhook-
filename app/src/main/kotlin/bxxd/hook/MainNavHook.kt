package bxxd.hook

import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val TAG = "LlhookMainNav"

/**
 * ============================================================================
 *  底部导航栏 (main_navigation) 美化: 圆角 + 悬浮。
 * ============================================================================
 *  定位: HomeActivity 私有方法 [a(Intent)] 里 this.i = findViewById(R.id.main_navigation)
 *  (activity_main.xml 把 <include layout="@layout/tab_main"> 标记为 main_navigation,
 *   tab_main 根是 LinearLayout, 含 5 个 HomeQBadgeContainer tab)。
 *
 *  双 hook 点:
 *   ① a(Intent) after —— 首次构建好导航栏即改
 *   ② onResume after —— 皮肤引擎/重布局后补回 (幂等)
 *
 *  开关:
 *   - switch_main_nav_round  导航栏圆角+悬浮 (圆角背景 + 上抬阴影 + 左右下边距 + 移除分割线)
 *  参数: main_nav_radius(默认28dp) / main_nav_width(默认92%, 屏宽百分比) / main_nav_height(默认50dp, 导航栏高度) / main_nav_margin_b(默认10dp) / main_nav_elevation(默认8dp)
 * ============================================================================
 */
object MainNavHook : BaseHook {

    private const val HOME_CLS = "com.soft.blued.ui.home.HomeActivity"

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = lpparam.classLoader.loadClass(HOME_CLS)
            // a(Intent) — 导航栏刚构建好
            try {
                cls.findMethod {
                    name == "a" && parameterTypes.size == 1 &&
                        parameterTypes[0] == android.content.Intent::class.java
                }.hookAfter { p ->
                    try { (p.thisObject as? Activity)?.let { patch(it, "a(Intent)") } }
                    catch (t: Throwable) { XposedBridge.log("$TAG a(Intent) err: $t") }
                }
                XposedBridge.log("$TAG hooked HomeActivity.a(Intent)")
            } catch (t: Throwable) { XposedBridge.log("$TAG hook a(Intent) fail: $t") }
            // onResume — 皮肤引擎重布局后补回
            try {
                cls.findMethod { name == "onResume" && parameterTypes.isEmpty() }
                    .hookAfter { p ->
                        try { (p.thisObject as? Activity)?.let { patch(it, "onResume") } }
                        catch (t: Throwable) { XposedBridge.log("$TAG onResume err: $t") }
                    }
            } catch (t: Throwable) { XposedBridge.log("$TAG hook onResume fail: $t") }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG init fail: $t")
        }
    }

    private fun patch(activity: Activity, from: String) {
        val res = activity.resources
        val pkg = activity.packageName
        val navId = res.getIdentifier("main_navigation", "id", pkg)
        if (navId == 0) { XposedBridge.log("$TAG [$from] main_navigation id=0 (版本不符)"); return }
        val nav = activity.findViewById<LinearLayout>(navId)
            ?: run { XposedBridge.log("$TAG [$from] main_navigation 未找到"); return }

        // ★ 单一开关 switch_main_nav_round (不再读旧键 switch_main_nav_float: 合并后 UI 无法关闭它, 残留 true 会导致关不掉)
        val enabled = Config.isFeatureEnabledFresh("switch_main_nav_round", activity)
        if (!enabled) {
            restoreDefault(activity, nav, pkg)
            return
        }

        val radiusDp = readInt(activity, "main_nav_radius", 28).coerceIn(0, 60)
        val widthPct = readInt(activity, "main_nav_width", 92).coerceIn(50, 100)
        val heightDp = readInt(activity, "main_nav_height", 50).coerceIn(40, 80)
        val marginBDp = readInt(activity, "main_nav_margin_b", 10).coerceIn(0, 64)
        val elevDp = readInt(activity, "main_nav_elevation", 8).coerceIn(0, 24)

        val density = activity.resources.displayMetrics.density
        val screenW = activity.resources.displayMetrics.widthPixels
        val radiusPx = dp(activity, radiusDp.toFloat())

        // ===== 1) 圆角背景 (皮肤底色 syc_b) =====
        val skinColor = try {
            val cid = res.getIdentifier("syc_b", "color", pkg)
            if (cid != 0) {
                if (Build.VERSION.SDK_INT >= 23) activity.getColor(cid) else @Suppress("DEPRECATION") res.getColor(cid)
            } else Color.WHITE
        } catch (_: Throwable) { Color.WHITE }

        nav.background = GradientDrawable().apply {
            cornerRadius = radiusPx.toFloat()
            setColor(skinColor)
            setStroke(dp(activity, 0.5f), 0x14000000)
        }
        // 圆角真正裁剪内容 (5 个 tab 不再溢出圆角)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            nav.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx.toFloat())
                }
            }
            nav.clipToOutline = true
            nav.clipChildren = true
        }

        // ===== 2) 悬浮: 抬升阴影 =====
        nav.elevation = elevDp * density

        // ===== 3) 移除上方分割线 bottom_line (悬浮后不需要) =====
        val blId = res.getIdentifier("bottom_line", "id", pkg)
        if (blId != 0) activity.findViewById<View>(blId)?.visibility = View.GONE

        // ===== 4) 消除通栏白色长条: cl_root 透明 + tabhost 内容延伸到屏底, nav 真正浮在内容之上 =====
        (nav.parent as? ViewGroup)?.let { it.background = null; it.setBackgroundColor(Color.TRANSPARENT) }
        val tabhost = activity.findViewById<View>(android.R.id.tabhost)
        if (tabhost != null) {
            setConstraintField(tabhost, "bottomToBottom", 0)   // 0 = PARENT_ID
            setConstraintField(tabhost, "bottomToTop", -1)     // -1 = UNSET
        }

        // ===== 5) 宽度 + 高度: 由宽度百分比推导左右边距 (居中), 高度单独可调 =====
        val desiredWidth = screenW * widthPct / 100
        val marginH = ((screenW - desiredWidth) / 2).coerceAtLeast(0)
        val lp = nav.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.leftMargin = marginH
            lp.rightMargin = marginH
            lp.bottomMargin = dp(activity, marginBDp.toFloat())
            // 高度: 直接设 layout 高度 (原 50dp, 可调 40-80dp)
            lp.height = dp(activity, heightDp.toFloat())
            nav.layoutParams = lp
        }

        // 父容器允许阴影/圆角溢出绘制
        (nav.parent as? ViewGroup)?.let { parent ->
            parent.clipChildren = false
            parent.clipToPadding = false
        }

        // ===== 6) tab 按钮垂直居中: nav 变高后, 子容器(ll_main_find 等, 原始顶对齐)要居中显示 =====
        nav.gravity = Gravity.CENTER_VERTICAL
        for (i in 0 until nav.childCount) {
            val child = nav.getChildAt(i) ?: continue
            // 子容器改 WRAP_CONTENT, 才能在更高的 nav 里被 gravity 居中 (原本 match_parent 撑满顶部)
            val clp = child.layoutParams
            clp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            child.layoutParams = clp
        }

        nav.invalidate()
        XposedBridge.log("$TAG [$from] patched radius=${radiusDp}dp width=${widthPct}% height=${heightDp}dp mb=${marginBDp}dp elev=${elevDp}dp")
    }

    /** 开关关闭时, 还原 Blued 原生外观。 */
    private fun restoreDefault(activity: Activity, nav: LinearLayout, pkg: String) {
        try {
            val res = activity.resources
            val cid = res.getIdentifier("syc_b", "color", pkg)
            val bg = if (cid != 0) {
                if (Build.VERSION.SDK_INT >= 23) activity.getColor(cid) else @Suppress("DEPRECATION") res.getColor(cid)
            } else Color.WHITE
            nav.background = null   // 清掉自定义圆角 drawable, 让 tab_main 原生背景接管
            nav.setBackgroundColor(bg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                nav.outlineProvider = null
                nav.clipToOutline = false
            }
            nav.elevation = 0f
            (nav.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.leftMargin = 0; lp.rightMargin = 0; lp.bottomMargin = 0
                // 还原高度到原生 50dp
                lp.height = dp(activity, 50f)
                nav.layoutParams = lp
            }
            // 还原 tab 按钮排布: gravity 回顶, 子容器高度回 match_parent (充满导航栏)
            nav.gravity = Gravity.TOP
            for (i in 0 until nav.childCount) {
                val child = nav.getChildAt(i) ?: continue
                val clp = child.layoutParams
                clp.height = ViewGroup.LayoutParams.MATCH_PARENT
                child.layoutParams = clp
            }
            val blId = res.getIdentifier("bottom_line", "id", pkg)
            if (blId != 0) activity.findViewById<View>(blId)?.visibility = View.VISIBLE
            // 还原悬浮时的改动: cl_root 背景设回原生 syc_b + tabhost 约束还原
            (nav.parent as? ViewGroup)?.let { clRoot ->
                clRoot.background = null
                clRoot.setBackgroundColor(bg)
            }
            val tabhost = activity.findViewById<View>(android.R.id.tabhost)
            if (tabhost != null) {
                // tabhost 原本 bottom_toTopOf=bottom_line
                setConstraintField(tabhost, "bottomToBottom", -1)
                val bottomLineId = res.getIdentifier("bottom_line", "id", pkg)
                if (bottomLineId != 0) setConstraintField(tabhost, "bottomToTop", bottomLineId)
            }
            nav.invalidate()
        } catch (t: Throwable) { XposedBridge.log("$TAG restore fail: $t") }
    }

    private fun readInt(activity: Activity, key: String, default: Int): Int =
        Config.readRawLocal(key, activity).let { it.takeIf { it != "null" && it.isNotEmpty() }?.toIntOrNull() ?: default }

    /** 反射设置 ConstraintLayout.LayoutParams 的 int 约束字段 (bottomToBottom/bottomToTop 等)。
     *  模块编译期不依赖 constraintlayout, 只能在运行期宿主进程里拿到该类。 */
    private fun setConstraintField(view: View, fieldName: String, value: Int) {
        try {
            val lp = view.layoutParams ?: return
            var c: Class<*>? = lp.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField(fieldName)
                    f.isAccessible = true
                    f.set(lp, value)
                    view.layoutParams = lp
                    return
                } catch (_: NoSuchFieldException) {}
                c = c.superclass
            }
            XposedBridge.log("$TAG 约束字段未找到: $fieldName")
        } catch (t: Throwable) { XposedBridge.log("$TAG setConstraintField($fieldName) 失败: $t") }
    }

    private fun dp(ctx: android.content.Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
