package bxxd.hook

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.Outline
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.app.AlertDialog
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ============================================================================
 *  QQ 风格首页: 左上角圆形头像 + 侧滑抽屉式「我的」页面
 * ============================================================================
 *
 *  【功能】
 *  1. 左上角 QQ 风格圆形头像 (异步加载 UserInfo.avatar)
 *  2. 隐藏底部导航「我的」按钮 (其余 4 tab 自动均分)
 *  3. 身边页面顶部标题栏 (交友/动态) 下移避让头像
 *  4. 点击头像 / 左边缘右滑 → 「我的」侧滑抽屉从左侧平滑划出
 *  5. 左滑 / 返回键 / 点击遮罩 → 平滑收起抽屉
 *
 *  【抽屉实现原理】
 *  - 在 contentView 顶层 addView 一个 drawerLayout(FrameLayout)
 *  - contentFrame 承载独立实例化的 MineNewFragment (FragmentTransaction.add)
 *  - 显示: translationX 从 -screenW 平滑动画到 0 (DecelerateInterpolator 280ms)
 *  - 收起: 反向动画, 结束后 GONE (fragment 保留, 下次直接显示)
 *  - dispatchTouchEvent + 手动 MotionEvent 追踪 (边缘右滑打开/左滑关闭, 兼容慢拖)
 *  - hook onBackPressed: 抽屉打开时拦截返回键改为收起
 *
 *  【为什么 MineNewFragment 能独立实例化?】
 *  反编译确认 MineNewFragment 不强转 HomeActivity (grep=0), 继承 MVVMBaseFragment,
 *  是自包含 Fragment, 可直接 FragmentTransaction.add 到抽屉容器。
 * ============================================================================
 */
object HomeQQHook : BaseHook {

    private const val TAG = "llhook_qq_avatar"
    private const val TAG_TITLE = "llhook_title_adjusted"
    private val mainHandler = Handler(Looper.getMainLooper())

    // 抽屉状态
    private var drawerLayout: FrameLayout? = null
    private var drawerContent: FrameLayout? = null
    private var dimView: View? = null
    private var mineFragment: Any? = null       // MineNewFragment 实例 (反射操作, 不强转)
    private var isDrawerOpen = false
    private var drawerAnimating = false
    private var hostActivity: Activity? = null
    private var screenWidth = 0

    // 拖拽追踪状态 (手动 MotionEvent, 兼容慢拖)
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragMode = 0        // 0=待定 1=边缘开 2=开→关 3=交给App
    private var touchSlop = 16     // 判定拖拽的最小距离 (运行时按 density 校准)
    private var edgeArmed = false  // 边缘已接管手势序列(拦截DOWN后全程拦截, 不漏给子View)

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val homeActivityClass = lpparam.classLoader.loadClass("com.soft.blued.ui.home.HomeActivity")

            // ① HomeActivity.onCreate — 注入头像 + 隐藏底部mine + 创建抽屉
            XposedBridge.hookAllMethods(homeActivityClass, "onCreate", object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val activity = param.thisObject as? Activity ?: return
                        if (!Config.isFeatureEnabled("switch_qq_home", activity)) return
                        hostActivity = activity
                        screenWidth = activity.resources.displayMetrics.widthPixels
                        injectQQAvatar(activity)
                        hideMineTabButton(activity)
                        setupMineDrawer(activity)
                    } catch (e: Throwable) { XposedBridge.log("llhook qq onCreate err: $e") }
                }
            })

            // ② dispatchTouchEvent — 边缘右滑打开 / 左滑关闭 (手动追踪, 兼容慢拖)
            //    ⚠️ 关键: HomeActivity 未 override dispatchTouchEvent, hookAllMethods(homeActivityClass,...) 抓不到!
            //    必须 hook 基类 android.app.Activity 的 dispatchTouchEvent, 回调里过滤 HomeActivity 实例
            val activityBase = lpparam.classLoader.loadClass("android.app.Activity")
            XposedBridge.hookAllMethods(activityBase, "dispatchTouchEvent", object : de.robv.android.xposed.XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        // 只处理 HomeActivity 实例 (基类方法会作用于所有 Activity)
                        if (param.thisObject?.javaClass != homeActivityClass) return
                        val ev = param.args[0] as? MotionEvent ?: return
                        // handleDragTouch 返回 true = 正在拖拽抽屉, 拦截事件不传给子View (ViewPager/列表)
                        // dispatchTouchEvent 返回 true = 已消费, 不向下传递
                        if (handleDragTouch(ev)) {
                            param.result = true
                        }
                    } catch (e: Throwable) {}
                }
            })

            // ③ onBackPressed — 抽屉打开时拦截, 改为收起
            XposedBridge.hookAllMethods(homeActivityClass, "onBackPressed", object : de.robv.android.xposed.XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (isDrawerOpen) {
                            hideMineDrawer()
                            param.result = null   // 阻止原 onBackPressed (退出App)
                        }
                    } catch (e: Throwable) {}
                }
            })

            // ③bis HomeActivity.onDestroy — 清理僵尸引用
            //   ⚠️ 关键: 深色模式切换等配置变化会触发 Activity 重建。HomeQQHook 是单例,
            //   实例字段 (mineFragment/drawerLayout/drawerContent/dimView/hostActivity)
            //   若不清空会指向已销毁的旧对象 → ensureMineFragment 误判"已加载"
            //   → 新 content 容器无 fragment → "我的"页面全屏白色 (重启才能恢复)
            XposedBridge.hookAllMethods(homeActivityClass, "onDestroy", object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.thisObject === hostActivity) {
                            mineFragment = null
                            drawerLayout = null
                            drawerContent = null
                            dimView = null
                            hostActivity = null
                            isDrawerOpen = false
                            drawerAnimating = false
                            dragMode = 0
                            edgeArmed = false
                        }
                    } catch (e: Throwable) { XposedBridge.log("llhook qq onDestroy err: $e") }
                }
            })

            // ④ 各 Tab Fragment.onResume — 顶部标题栏 (TitleTopNavigationView) 下移避让头像
            //    身边页 NearbyHomeFragment / 消息页 MessagePageFragment / 动态 DiscoveryPageFragment
            val titleFragments = listOf(
                "com.soft.blued.ui.find.fragment.NearbyHomeFragment",
                "com.soft.blued.ui.msg.MessagePageFragment",
                "com.soft.blued.ui.feed.DiscoveryPageFragment"
            )
            for (fragName in titleFragments) {
                try {
                    val fc = lpparam.classLoader.loadClass(fragName)
                    XposedBridge.hookAllMethods(fc, "onResume", object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val activity = hostActivity ?: return
                                if (!Config.isFeatureEnabled("switch_qq_home", activity)) return
                                mainHandler.postDelayed({ adjustTitleBar(param.thisObject) }, 100)
                            } catch (e: Throwable) {}
                        }
                    })
                } catch (e: Throwable) {}
            }

            // ⑤ MineNewFragment.onResume — 「我互动过/来访/群聊/动态」改成 2×2 田字布局, 按钮增大下移
            //    (只在 QQ 风格首页开启时生效)
            try {
                val mineFragClass = lpparam.classLoader.loadClass("com.soft.blued.ui.mine.fragment.MineNewFragment")
                XposedBridge.hookAllMethods(mineFragClass, "onResume", object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = hostActivity ?: return
                            if (!Config.isFeatureEnabled("switch_qq_home", activity)) return
                            val view = XposedHelpers.callMethod(param.thisObject, "getView") as? View ?: return
                            mainHandler.postDelayed({
                                purgeMineWidgets(view)
                                rearrangeMineEntries(view)
                            }, 150)
                        } catch (e: Throwable) {}
                    }
                })
            } catch (e: Throwable) {}

        } catch (e: Throwable) { XposedBridge.log("llhook qq init err: $e") }
    }

    // ========================================================================
    //  头像注入
    // ========================================================================
    private fun injectQQAvatar(activity: Activity) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        rootView.findViewWithTag<View>(TAG)?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val statusBarHeight = getStatusBarHeight(activity)
        val avatarSize = dp2px(activity, 44f)   // 加大
        val pad2 = dp2px(activity, 2f)
        val marginStart = dp2px(activity, 10f)
        val marginTop = statusBarHeight + dp2px(activity, 0f)
        touchSlop = ViewConfiguration.get(activity).scaledTouchSlop.coerceAtLeast(16)

        val avatarFrame = FrameLayout(activity).apply {
            tag = TAG
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(pad2, Color.WHITE)
            }
            setPadding(pad2, pad2, pad2, pad2)
            layoutParams = FrameLayout.LayoutParams(avatarSize, avatarSize).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(marginStart, marginTop, 0, 0)
            }
        }
        val avatarView = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // 圆形占位背景 (OVAL, 不再用方形灰色)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E0E0E0"))
            }
            // 关键: ViewOutlineProvider 强制裁圆, 保证头像始终是圆形
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        avatarFrame.addView(avatarView)
        avatarFrame.setOnClickListener { showMineDrawer() }
        rootView.addView(avatarFrame)
        loadAvatarAsync(activity, avatarView)
    }

    private fun loadAvatarAsync(activity: Activity, imageView: ImageView) {
        Thread {
            try {
                val cls = XposedHelpers.findClass("com.blued.android.module.common.user.model.UserInfo", activity.classLoader)
                val userInfo = XposedHelpers.callStaticMethod(cls, "getInstance")
                val loginInfo = XposedHelpers.callMethod(userInfo, "getLoginUserInfo")
                val url = XposedHelpers.getObjectField(loginInfo, "avatar") as? String ?: ""
                if (url.startsWith("http")) {
                    val raw = downloadBitmap(url)
                    if (raw != null) {
                        val circular = getCircularBitmap(raw)
                        mainHandler.post {
                            try { if (!activity.isFinishing) imageView.setImageBitmap(circular) } catch (e: Throwable) {}
                        }
                    }
                }
            } catch (e: Throwable) {}
        }.start()
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        return try {
            val conn = java.net.URL(urlStr).openConnection()
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.getInputStream().use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) { null }
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val src = Rect((bitmap.width - size) / 2, (bitmap.height - size) / 2, (bitmap.width + size) / 2, (bitmap.height + size) / 2)
        canvas.drawBitmap(bitmap, src, Rect(0, 0, size, size), paint)
        return output
    }

    // ========================================================================
    //  「我的」侧滑抽屉 (QQ 式)
    // ========================================================================
    private fun setupMineDrawer(activity: Activity) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // 防重建
        if (drawerLayout != null) {
            (drawerLayout?.parent as? ViewGroup)?.removeView(drawerLayout)
        }

        val W = screenWidth
        drawerLayout = FrameLayout(activity).apply {
            visibility = View.GONE
            isClickable = true
        }

        // 半透明遮罩 (点击关闭)
        dimView = View(activity).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            setOnClickListener { hideMineDrawer() }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        drawerLayout!!.addView(dimView)

        // 内容容器 (承载 MineNewFragment, translationX 动画)
        // 背景跟随深色模式 (之前硬编码 Color.WHITE → 深色模式/fragment 未加载时白屏)
        drawerContent = FrameLayout(activity).apply {
            id = generateViewIdCompat()
            setBackgroundColor(if (isDarkMode(activity)) Color.parseColor("#0F172A") else Color.WHITE)
            translationX = -W.toFloat()
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        drawerLayout!!.addView(drawerContent)

        rootView.addView(drawerLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** 显示抽屉: 首次加载 MineNewFragment, translationX -W→0 平滑动画 */
    private fun showMineDrawer() {
        val activity = hostActivity ?: return
        val layout = drawerLayout ?: return
        val content = drawerContent ?: return
        val dim = dimView ?: return
        if (isDrawerOpen || drawerAnimating) return
        val W = screenWidth

        layout.visibility = View.VISIBLE
        isDrawerOpen = true
        ensureMineFragment(activity, content)
        animateDrawer(content, dim, layout, content.translationX.let { if (it == 0f) -W.toFloat() else it }, 0f, true)
    }

    /** 预加载 MineNewFragment 并将抽屉置于屏幕外 (拖拽开始时调用) */
    private fun prepareDrawerForDrag() {
        val activity = hostActivity ?: return
        val layout = drawerLayout ?: return
        val content = drawerContent ?: return
        if (mineFragment == null) {
            layout.visibility = View.VISIBLE
            content.translationX = -screenWidth.toFloat()
            ensureMineFragment(activity, content)
        }
    }

    /** 加载 MineNewFragment 到 content 容器 (仅首次) */
    private fun ensureMineFragment(activity: Activity, content: FrameLayout) {
        if (mineFragment != null) return
        try {
            val fragClass = activity.classLoader.loadClass("com.soft.blued.ui.mine.fragment.MineNewFragment")
            val frag = try {
                fragClass.getDeclaredMethod("newInstance").invoke(null)
            } catch (e: Throwable) {
                fragClass.getDeclaredConstructor().newInstance()
            }
            mineFragment = frag
            val fm = XposedHelpers.callMethod(activity, "getSupportFragmentManager")
            val ft = XposedHelpers.callMethod(fm, "beginTransaction")
            XposedHelpers.callMethod(ft, "add", content.id, frag)
            XposedHelpers.callMethod(ft, "commitNowAllowingStateLoss")
        } catch (e: Throwable) { XposedBridge.log("llhook drawer frag err: $e") }
    }

    /** 通用动画: translationX 从 from 到 to */
    private fun animateDrawer(content: View, dim: View, layout: View, from: Float, to: Float, openAtEnd: Boolean) {
        val W = screenWidth.toFloat()
        drawerAnimating = true
        val anim = ValueAnimator.ofFloat(from, to).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener {
                val v = it.animatedValue as Float
                content.translationX = v
                dim.alpha = (1f - (-v / W)) * 0.4f
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                drawerAnimating = false
                if (!openAtEnd) {
                    layout.visibility = View.GONE
                }
            }
        })
        anim.start()
    }

    /** 收起抽屉: translationX 0→-W 平滑动画, 结束 GONE */
    private fun hideMineDrawer() {
        val content = drawerContent ?: return
        val dim = dimView ?: return
        val layout = drawerLayout ?: return
        if (!isDrawerOpen || drawerAnimating) return
        val W = screenWidth.toFloat()
        isDrawerOpen = false
        animateDrawer(content, dim, layout, content.translationX.let { if (it < 0f) it else 0f }, -W, false)
    }

    // ========================================================================
    //  手势: 手动 MotionEvent 追踪 (边缘右滑打开 / 左滑关闭, 兼容慢拖)
    // ========================================================================
    private fun handleDragTouch(ev: MotionEvent): Boolean {
        val content = drawerContent ?: return false
        val dim = dimView ?: return false
        val layout = drawerLayout ?: return false
        if (drawerAnimating) return false
        val W = screenWidth.toFloat()
        val edgeThreshold = dp2px(hostActivity ?: return false, 60f).toFloat()
        // 标题栏 tab(距离/在线、聊天/通知) 在顶部约 50dp(status_bar)+50dp(下移)+50dp(title)≈150dp 内。
        // edgeArmed 排除标题栏区域: 只在按下点低于标题栏(落在列表上)才接管, 标题栏 tab 正常响应。
        val titleExcludeY = dp2px(hostActivity ?: return false, 160f).toFloat()
        var consumed = false   // 本次事件是否拦截 (不传给子View)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = ev.rawX
                dragStartY = ev.rawY
                dragMode = 0
                edgeArmed = false
                // 抽屉关闭时, 左边缘(60dp)内 且 低于标题栏(避开tab) 按下 → 接管整个手势序列
                // 解决: 慢速右滑时 dx 迟迟达不到touchSlop, 但时间已过longPressTimeout(500ms)
                //       → 聊天项收到DOWN后触发长按菜单(悄悄查看/删除)
                // 拦截DOWN→聊天项收不到DOWN→不启动长按计时, 从源头根治
                // y>titleExcludeY: 排除标题栏区域, 避免误拦“距离/在线”“聊天/通知” tab
                if (!isDrawerOpen && dragStartX < edgeThreshold && dragStartY > titleExcludeY) {
                    edgeArmed = true
                    consumed = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - dragStartX
                val dy = abs(ev.rawY - dragStartY)
                if (dragMode == 0) {
                    // 判定拖拽方向: 水平位移 > 垂直 且超过 touchSlop
                    if (abs(dx) > touchSlop && abs(dx) > dy * 1.5f) {
                        if (!isDrawerOpen && dragStartX < edgeThreshold && dx > 0) {
                            // 左边缘右滑 → 开始打开
                            dragMode = 1
                            prepareDrawerForDrag()
                        } else if (isDrawerOpen && dx < 0) {
                            // 已打开 + 左滑 → 开始关闭
                            dragMode = 2
                        } else {
                            dragMode = 3  // 交给 App 处理
                        }
                    }
                }
                // 实时跟进 translationX (平滑跟随手指)
                if (dragMode == 1) {
                    val tx = (-W + dx).coerceIn(-W, 0f)
                    content.translationX = tx
                    dim.alpha = (1f - (-tx / W)) * 0.4f
                    layout.visibility = View.VISIBLE
                    consumed = true
                } else if (dragMode == 2) {
                    val tx = (0f + dx).coerceIn(-W, 0f)
                    content.translationX = tx
                    dim.alpha = (1f - (-tx / W)) * 0.4f
                    consumed = true
                }
                // 边缘接管区: 即使还未确认拖拽方向也拦截, 保证整个序列不漏给子View
                if (edgeArmed) consumed = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode == 1 || dragMode == 2) {
                    val tx = content.translationX
                    // 超过中点 → 打开, 否则 → 关闭
                    if (tx > -W / 2f) {
                        isDrawerOpen = true
                        animateDrawer(content, dim, layout, tx, 0f, true)
                    } else {
                        isDrawerOpen = false
                        animateDrawer(content, dim, layout, tx, -W, false)
                    }
                    // ⚠️ 关键: UP 也要拦截!
                    // 子View已收到DOWN(进入pressed), 若不拦截UP会触发onClick→打开聊天窗口。
                    // 拦截UP→子View收不到UP→不触发点击。
                    consumed = true
                }
                // 边缘接管序列的UP也拦截收尾
                if (edgeArmed) consumed = true
                dragMode = 0
                edgeArmed = false
            }
        }
        return consumed
    }

    // ========================================================================
    //  隐藏底部导航「我的」按钮
    // ========================================================================
    private fun hideMineTabButton(activity: Activity) {
        try {
            val resId = activity.resources.getIdentifier("other_badge_container", "id", activity.packageName)
            if (resId != 0) activity.findViewById<View>(resId)?.visibility = View.GONE
        } catch (e: Throwable) {}
    }

    // ========================================================================
    //  「我的」页面: 「我互动过/来访/群聊/动态」4 按钮重组为 2×2 田字布局, 按钮增大下移
    // ========================================================================
    private val MINE_REARRANGE_TAG = "qq_mine_rearranged"

    /** 净化「我的」页面: 移除指定的 ShapeLinearLayout 控件 */
    private fun purgeMineWidgets(view: View) {
        try {
            val res = view.resources
            val pkg = view.context.packageName
            // ll_live_table: 直播 tab 切换条 (与 ll_beans 充值入口并列于 ll_top_entry, 默认 gone 但代码会动态显示)
            val liveTableId = res.getIdentifier("ll_live_table", "id", pkg)
            if (liveTableId != 0) view.findViewById<View>(liveTableId)?.visibility = View.GONE
        } catch (e: Throwable) { XposedBridge.log("llhook purgeMine err: $e") }
    }

    /**
     * 第一版方案 (验证有效): 把 cl_per_entry 内部 4 个按钮从「单行 4 列」重组为「2×2 田字」。
     *  - 保留 cl_per_entry 容器位置/约束不变, 仅清空内部 4 子 view 后置入新的 vertical LinearLayout
     *  - ViewBinding 按 id 搜索整棵树, 移动 view 不影响绑定/点击
     */
    private fun rearrangeMineEntries(view: View) {
        try {
            val ctx = view.context
            val res = ctx.resources
            val pkg = ctx.packageName
            val clPerEntry = view.findViewById<ViewGroup>(res.getIdentifier("cl_per_entry", "id", pkg)) ?: return
            // 防重: 已重组过则跳过
            if (clPerEntry.getTag(MINE_REARRANGE_TAG.hashCode()) == true) return

            val interact = view.findViewById<View>(res.getIdentifier("cl_interact", "id", pkg)) ?: return
            val visit = view.findViewById<View>(res.getIdentifier("cl_visit", "id", pkg)) ?: return
            val group = view.findViewById<View>(res.getIdentifier("cl_group", "id", pkg)) ?: return
            val feed = view.findViewById<View>(res.getIdentifier("cl_feed", "id", pkg)) ?: return

            // 1) 从原容器移除 4 个按钮 (含其内部图标/文字/红点, 整体迁移)
            listOf(interact, visit, group, feed).forEach {
                (it.parent as? ViewGroup)?.removeView(it)
            }

            // 2) 增大按钮: 图标 30dp→48dp, 文字 12sp→15sp
            listOf(interact, visit, group, feed).forEach { enlargeMineEntry(it, ctx) }

            // 3) 组装 2×3: vertical LL → [row1(互动|来访), row2(群聊|动态), row3(蓝蓝hook|空)]
            val verticalLL = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            verticalLL.addView(makeMineRow(ctx, interact, visit))
            verticalLL.addView(makeMineRow(ctx, group, feed))
            // 第 3 行: 模块入口 (左格) | 黑名单 (右格)
            // 先测量业务按钮实际高度, 让模块/黑名单 cell 边框完全等高
            val refCell = interact
            val moduleCell = makeModuleEntryCell(ctx) {
                try {
                    // 阶段 3 修复: 「模块入口」格直接弹出 Compose 设置页 (不再依赖右下角悬浮球,
                    // 解决悬浮球未注入或被遮挡时的死循环提示问题)
                    val a = hostActivity ?: ctx as? Activity
                    if (a != null) {
                        com.example.ui.showHostComposeScreen(a) { onClose ->
                            com.example.ui.theme.MyApplicationTheme {
                                // mineEntry=true: “我的”入口显示完整配置分区 (基础/聊天增强/隐私特权/风控)
                                com.example.ui.MainScreen(hostActivity = a, inHost = true, mineEntry = true)
                            }
                        }
                    } else {
                        Toast.makeText(ctx, "页面未就绪, 请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("llhook module menu err: $e")
                    Toast.makeText(ctx, "设置页打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            val blacklistCell = makeBlacklistEntryCell(ctx) {
                try {
                    val routerClass = ctx.classLoader.loadClass(
                        "com.blued.android.similarity_operation_provider.BluedURIRouterAdapter")
                    val m = routerClass.getDeclaredMethod("openMyBlacklist", Context::class.java)
                    m.isAccessible = true
                    m.invoke(null, ctx)
                } catch (e: Throwable) {
                    XposedBridge.log("llhook blacklist open err: $e")
                    Toast.makeText(ctx, "打开黑名单失败", Toast.LENGTH_SHORT).show()
                }
            }
            // 强制模块/黑名单 cell 高度 = 业务按钮高度 (边框完全一致)
            refCell.post {
                val h = refCell.height
                if (h > 0) {
                    moduleCell.layoutParams = (moduleCell.layoutParams).apply { height = h }
                    blacklistCell.layoutParams = (blacklistCell.layoutParams).apply { height = h }
                }
            }
            verticalLL.addView(makeMineRow(ctx, moduleCell, blacklistCell))

            // 4) 置入 cl_per_entry (ConstraintLayout), 强制填满宽度
            clPerEntry.addView(verticalLL)
            val vlp = verticalLL.layoutParams
            vlp.width = ViewGroup.LayoutParams.MATCH_PARENT
            vlp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            verticalLL.layoutParams = vlp

            // 5) 整体下移: 加大 cl_per_entry 的 topMargin
            val plp = clPerEntry.layoutParams
            if (plp is ViewGroup.MarginLayoutParams) {
                plp.topMargin += dp2px(ctx, 12f)
                clPerEntry.layoutParams = plp
            }

            clPerEntry.setTag(MINE_REARRANGE_TAG.hashCode(), true)
        } catch (e: Throwable) { XposedBridge.log("llhook rearrangeMine err: $e") }
    }

    /** 增大单个业务按钮: 图标 30dp, 文字 13sp, 四周 padding + 圆角边框 */
    private fun enlargeMineEntry(entry: View, ctx: Context) {
        if (entry is ViewGroup) {
            val iconSize = dp2px(ctx, 30f)
            for (i in 0 until entry.childCount) {
                when (val c = entry.getChildAt(i)) {
                    is ImageView -> {
                        val lp = c.layoutParams
                        lp.width = iconSize; lp.height = iconSize
                        c.layoutParams = lp
                    }
                    is TextView -> c.textSize = 13f
                }
            }
            val pad = dp2px(ctx, 12f)
            entry.setPadding(pad, pad, pad, pad)
            entry.background = cellBorderBg(ctx)
        }
    }

    /** 统一格边框: 圆角白底 + 浅灰描边 (所有 cell 共用) */
    /** 判断当前是否为深色模式 (跟随系统) */
    private fun isDarkMode(ctx: Context): Boolean {
        val mode = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** 统一格边框: 圆角底 + 描边 (深色模式黑底, 浅色模式白底) */
    private fun cellBorderBg(ctx: Context): GradientDrawable {
        val isDark = isDarkMode(ctx)
        return GradientDrawable().apply {
            setColor(Color.parseColor(if (isDark) "#1F2937" else "#FFFFFF"))
            cornerRadius = dp2px(ctx, 12f).toFloat()
            setStroke(dp2px(ctx, 1f), Color.parseColor(if (isDark) "#374151" else "#D8D8D8"))
        }
    }



    /** 造一行 (两个按钮各占一半宽度, weight=1; b 为 null 时右格留空占位) */
    private fun makeMineRow(ctx: Context, a: View, b: View?): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            weightSum = 2f
            gravity = Gravity.CENTER_VERTICAL
            val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(a, half)
            addView(b ?: placeholderCell(ctx), half)
        }
    }

    /** 空占位格 (保持网格对齐) */
    private fun placeholderCell(ctx: Context): View =
        View(ctx).apply { setPadding(0, dp2px(ctx, 40f), 0, dp2px(ctx, 40f)) }

    /** 造「模块入口」格: 渐变圆角方块, 点击弹出独立功能面板 */
    private fun makeModuleEntryCell(ctx: Context, onClick: (View) -> Unit): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = dp2px(ctx, 12f)
            setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
            background = cellBorderBg(ctx)
            val iconSize = dp2px(ctx, 39f)
            val iv = TextView(ctx).apply {
                text = "🛠️"
                textSize = 25f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    colors = intArrayOf(Color.parseColor("#2196F3"), Color.parseColor("#21D4FD"))
                    cornerRadius = dp2px(ctx, 10f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }
            addView(iv)
            setOnClickListener { onClick(this) }
        }
    }

    /** 造「黑名单」格: 深灰圆角方块, 点击反射跳转 BluedURIRouterAdapter.openMyBlacklist */
    private fun makeBlacklistEntryCell(ctx: Context, onClick: (View) -> Unit): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = dp2px(ctx, 12f)
            setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
            background = cellBorderBg(ctx)
            val iconSize = dp2px(ctx, 39f)
            val iv = TextView(ctx).apply {
                text = "🈲"
                textSize = 25f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    colors = intArrayOf(Color.parseColor("#6C757D"), Color.parseColor("#495057"))
                    cornerRadius = dp2px(ctx, 10f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }
            addView(iv)
            setOnClickListener { onClick(this) }
        }
    }

    // ========================================================================
    //  独立模块功能面板 (不依赖 FloatingUI)
    // ========================================================================

    private fun restartHostApp(activity: Activity) {
        try {
            val pm = activity.packageManager
            val intent = pm.getLaunchIntentForPackage(activity.packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Throwable) {}
    }


    // ========================================================================
    //  各页面顶部标题栏 下移避让头像
    // ========================================================================
    /**
     * 通用: 找 fragment 根视图里的标题栏根容器并下移 offset。
     *
     *  ⚠️ 关键教训 (勿重复犯错):
     *   - 只能下移「标题栏根容器」一个对象, 不能同时下移它内部的 TitleTopNavigationView
     *     (两者是嵌套关系, 同时下移 = 双重位移, 标题栏会移出可见区/被列表覆盖)
     *   - 必须给标题栏 elevation 提高到列表之上, 否则下移后被 ViewPager 列表覆盖
     *   - 用 translationY 不改布局, 配合 elevation 保证可见
     */
    private fun adjustTitleBar(fragment: Any) {
        try {
            val activity = hostActivity ?: return
            val view = XposedHelpers.callMethod(fragment, "getView") as? View ?: return
            // 下移量 = 头像高度 + 间距, 刚好让标题栏顶部在头像底部下方
            val offset = (dp2px(activity, 44f) + dp2px(activity, 6f)).toFloat()

            // 找到标题栏 View (优先 id=title 根容器, 兜底 TitleTopNavigationView)
            val titleId = activity.resources.getIdentifier("title", "id", activity.packageName)
            var titleView: View? = if (titleId != 0) view.findViewById<View>(titleId) else null
            if (titleView == null) {
                val ttnId = activity.resources.getIdentifier("title_navigation_view", "id", activity.packageName)
                titleView = if (ttnId != 0) view.findViewById<View>(ttnId) else null
            }
            if (titleView == null) {
                // 按 fragment 字段类型枚举 TitleTopNavigationView
                val ttnClass = activity.classLoader.loadClass("com.blued.android.module.common.view.TitleTopNavigationView")
                for (f in fragment.javaClass.declaredFields) {
                    if (ttnClass.isAssignableFrom(f.type)) {
                        f.isAccessible = true
                        titleView = (f.get(fragment) as? View)
                        if (titleView != null) break
                    }
                }
            }

            // ① 下移标题栏 (translationY 视觉位移 + elevation 提到列表之上)
            titleView?.let {
                it.translationY = offset
                it.elevation = dp2px(activity, 8f).toFloat()
                it.bringToFront()
            }

            // ② 关键修复: 标题栏用 translationY 下移是纯视觉位移, 下方内容(搜索框/列表/ViewPager)
            //    布局位置不变会被盖住。跳过标题栏子树, 把所有可滚动内容容器也下移相同 offset。
            //    - 身边页: SmartRefreshLayout(含列表+搜索) 下移
            //    - 消息页: main_msg_viewpager 下移
            //    - 发现页: view_pager 下移
            if (titleView != null) pushContentDown(view, titleView!!, offset)
        } catch (e: Throwable) {}
    }

    /**
     * 递归收集「可滚动内容容器」(ViewPager/SmartRefreshLayout) 并下移 offset。
     * 跳过标题栏 View 及其子树, 避免把标题栏双重下移。
     */
    private fun pushContentDown(root: View, titleView: View, offset: Float) {
        val targets = ArrayList<View>()
        collectContentContainers(root, titleView, targets)
        targets.forEach {
            if (it !== titleView) it.translationY = offset
        }
    }

    private fun collectContentContainers(v: View, titleView: View, out: ArrayList<View>) {
        if (v === titleView) return  // 跳过标题栏本身(其子树也一并跳过)
        val cn = v.javaClass.name
        // 内容容器特征: ViewPager(消息/发现页) 或 SmartRefreshLayout(身边页含列表+搜索)
        if (cn.contains("ViewPager") || cn.contains("SmartRefreshLayout")) {
            out.add(v)
            return  // 命中后不再深入 (内部子容器随它一起下移)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectContentContainers(v.getChildAt(i), titleView, out)
        }
    }

    // ========================================================================
    //  工具方法
    // ========================================================================
    private fun generateViewIdCompat(): Int {
        return if (android.os.Build.VERSION.SDK_INT >= 17) View.generateViewId()
        else (System.currentTimeMillis() and 0xFFFFFF).toInt()
    }

    private fun getStatusBarHeight(context: Context): Int {
        return try {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) context.resources.getDimensionPixelSize(resId) else dp2px(context, 24f)
        } catch (e: Throwable) { dp2px(context, 24f) }
    }

    private fun dp2px(context: Context, dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt()

    private fun abs(v: Float) = if (v < 0) -v else v
}
