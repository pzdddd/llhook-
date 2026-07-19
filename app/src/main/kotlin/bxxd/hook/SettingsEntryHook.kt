package bxxd.hook

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ============================================================================
 *  在 Blued 自身「设置」页面顶部注入模块入口
 * ============================================================================
 *
 *  【功能】
 *  点击后启动模块主界面 (com.example.MainActivity)。
 *
 *  ── 为什么用 LayoutInflater.inflate 方案 (比 Fragment.onViewCreated 更稳) ──
 *  上一版 hook androidx.Fragment.onViewCreated 存在隐患: 若设置页 Fragment
 *  重写了 onViewCreated 且不调用 super, 基类方法 hook 就【不会触发】, 入口不出现。
 *
 *  本版改 hook android.view.LayoutInflater.inflate —— 任何页面要显示, 其布局
 *  【必然会被 inflate】, 这是最底层、最不可绕过的时机, 与 Fragment 是否重写无关。
 *  匹配方式: args[0] (布局资源 id) == fragment_settings 的资源 id 时才注入。
 *
 *  【资源 id 怎么拿 —— 不依赖硬编码, 全动态】
 *  Blued 的 dex 是加密的 (assets/39285EFA.dex), R 类字段值无法静态确定。
 *  因此用 Resources.getIdentifier("fragment_settings","layout",pkg) 动态获取,
 *  首次取到后缓存。这套机制对任意版本都自适应。
 *
 *  【如何插入"顶部单独一行"】
 *  inflate fragment_settings 后, 返回的根 View 里第一个设置项是 ll_face_verify
 *  (真人认证, 见反编译布局, 该 id 极稳定)。它的 parent 就是 ScrollView 内的垂直
 *  LinearLayout (所有设置项的容器)。往该容器 addView(入口行, 0) 即置于最顶部。
 *  用 parent 关系定位容器, 不依赖容器 id, 版本兼容性更好。
 *
 *  【防重复】
 *  给入口行打唯一 Tag, 插入前扫描容器, 已存在同 Tag 子 View 则跳过, 避免重复堆叠。
 *
 *  【性能 & 安全】
 *  ✅ inflate 被高频调用, 但回调里先做 `args[0] as? Int`( XmlPullParser 重载会被
 *     排除) 和 int 比较, 99.99% 情况下一行 return, 几乎零开销
 *  ✅ 只 hook Android 框架类 LayoutInflater, 不碰任何 SDK 自身代码
 *  ✅ 非设置页一律直接 return, 对其它页面/其它 App 零影响
 *  ✅ 全程 try/catch, 任何异常静默放行, 绝不让 App 崩溃 (inflate 在 UI 关键路径)
 *  ✅ 入口行用纯原生 View 构建, 依赖极少, 兼容性好; 自动适配日/夜间配色
 * ============================================================================
 */
object SettingsEntryHook : BaseHook {

    /** 入口区块唯一 Tag, 防止重复插入 */
    private const val TAG_ENTRY = "llhook_settings_entry_v1"
    /** 功能开关区块唯一 Tag, 防止重复插入 */
    private const val TAG_SWITCHES = "llhook_settings_switches_v1"

    /** 设置页布局名 (fragment_settings.xml) */
    private const val SETTINGS_LAYOUT_NAME = "fragment_settings"

    /** 设置页内首个设置项 id —— 作为定位容器的锚点 (真人认证项, 极稳定) */
    private const val ANCHOR_ID = "ll_face_verify"

    /**
     * 迁移到设置页的功能开关清单 (悬浮窗不再显示)。
     * [标题, SP key] —— key 与各 Hook 模块读取的 Config 键严格一致。
     */
    private val SETTINGS_SWITCHES = arrayOf(
        "⚡ 一键 lite (减负提速)" to "switch_lite",
        "闪照转照片"   to "switch_flash_photo",
        "消息防撤回"   to "switch_anti_recall",
        "解锁本地VIP"  to "switch_local_vip",
        "查看私密相册" to "switch_private_photo",
        "悄悄查看"     to "switch_read_receipt",
        "去除截屏限制" to "switch_screenshot",
        "去广告"       to "switch_block_ads",
        "QQ风格首页"   to "switch_qq_home"
    )

    // 资源 id 缓存: 首次使用时通过 getIdentifier 计算, 之后直接 int 比较 (0 表示未取到)
    private var settingsLayoutId: Int = 0
    private var anchorViewId: Int = 0
    private var idsResolved: Boolean = false

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        // hook 【所有】 inflate 重载 (int 版 + XmlPullParser 版),
        // 回调里用 `as? Int` 区分: XmlPullParser 重载的 args[0] 不是 Int, 直接跳过。
        try {
            val inflaterClass = lpparam.classLoader.loadClass("android.view.LayoutInflater")
            XposedBridge.hookAllMethods(inflaterClass, "inflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        // 仅处理 int 资源 id 重载; XmlPullParser 重载 args[0] 转 Int 失败直接返回
                        val resId = param.args.getOrNull(0) as? Int ?: return
                        if (resId == 0) return
                        val root = param.result as? View ?: return
                        val ctx = root.context ?: return

                        // 首次: 动态解析资源 id 并缓存 (后续纯 int 比较)
                        if (!idsResolved) resolveIds(ctx)

                        // 非设置页布局 → 立即返回 (绝大多数 inflate 都走这里)
                        if (resId != settingsLayoutId) return

                        injectEntry(root)
                    } catch (_: Throwable) {
                        // inflate 在 UI 关键路径, 绝不让异常逃逸
                    }
                }
            })
        } catch (_: Throwable) {
            // 无 LayoutInflater 类 (几乎不可能), 跳过
        }
    }

    // =========================================================================
    //  动态解析资源 id 并缓存 (一次)
    // =========================================================================
    private fun resolveIds(ctx: Context) {
        try {
            val res = ctx.resources
            val pkg = ctx.packageName
            settingsLayoutId = res.getIdentifier(SETTINGS_LAYOUT_NAME, "layout", pkg)
            anchorViewId = res.getIdentifier(ANCHOR_ID, "id", pkg)
        } catch (_: Throwable) {
            // 取不到保持 0, 后续匹配自然失败, 安全无操作
        } finally {
            idsResolved = true
        }
    }

    // =========================================================================
    //  注入入口行 + 功能开关区块到设置项容器顶部
    // =========================================================================
    private fun injectEntry(root: View) {
        // ① 锚点 id 未取到 (非 Blued 或资源缺失) → 放弃
        if (anchorViewId == 0) return

        // ② 通过锚点 ll_face_verify 定位"设置项容器"(它的 parent)
        val anchor = root.findViewById<View>(anchorViewId) ?: return
        val container = anchor.parent as? ViewGroup ?: return

        // ③ 功能开关区块: 已存在则跳过
        if (findTaggedView(container, TAG_SWITCHES) == null) {
            val switchSection = buildSwitchSection(root.context)
            // 开关区块插在入口区块之后 (若入口已存在则插入其后面, 否则顶部)
            val entryIdx = indexOfTaggedView(container, TAG_ENTRY)
            if (entryIdx >= 0) container.addView(switchSection, entryIdx + 1)
            else container.addView(switchSection, 0)
        }

        // ④ 入口区块: 已存在则跳过
        if (findTaggedView(container, TAG_ENTRY) != null) return

        // ⑤ 构造入口区块 (行 + 底部分隔线) 并置于最顶部
        val section = buildEntrySection(root.context)
        container.addView(section, 0)
    }

    /** 扫描容器, 返回指定 Tag 的子 View, 无则 null */
    private fun findTaggedView(container: ViewGroup, tag: String): View? {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (tag == child.tag) return child
        }
        return null
    }

    /** 返回指定 Tag 子 View 的索引, 无则 -1 */
    private fun indexOfTaggedView(container: ViewGroup, tag: String): Int {
        for (i in 0 until container.childCount) {
            if (tag == container.getChildAt(i).tag) return i
        }
        return -1
    }

    // =========================================================================
    //  构造入口区块: 一个竖直 LinearLayout 包 [入口行 + 底部细分隔线]
    //  入口行: 圆角渐变图标 + 主/副标题 + 右箭头, 自动适配日/夜间
    // =========================================================================
    private fun buildEntrySection(ctx: Context): View {
        val t = Theme.current(ctx)
        val rowBg = t.card
        val titleColor = t.textPrimary
        val subColor = t.textSecondary
        val dividerColor = t.separator
        val arrowColor = t.textTertiary

        // —— 外层区块: 竖直, 打防重复 Tag ——
        val section = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_ENTRY
        }

        // —— 入口行: 水平 ——
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16f), dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f))
            setBackgroundColor(rowBg)
            isClickable = true
            isFocusable = true
        }

        // 左: 圆角渐变图标块 + "B"
        val icon = TextView(ctx).apply {
            text = "B"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                colors = intArrayOf(Color.parseColor("#2196F3"), Color.parseColor("#21D4FD"))
                cornerRadius = dp(ctx, 10f).toFloat()
            }
            val s = dp(ctx, 34f)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(ctx, 14f) }
        }
        row.addView(icon)

        // 中: 主标题 + 副标题
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(ctx).apply {
            text = "蓝蓝hook 功能菜单"
            textSize = 16f
            setTextColor(titleColor)
            setTypeface(Typeface.DEFAULT_BOLD)
        })
        textCol.addView(TextView(ctx).apply {
            text = "点击展开模块功能开关"
            textSize = 12f
            setTextColor(subColor)
            setPadding(0, dp(ctx, 2f), 0, 0)
        })
        row.addView(textCol)

        // 右: 箭头
        row.addView(TextView(ctx).apply {
            text = "›"
            textSize = 26f
            setTextColor(arrowColor)
            gravity = Gravity.CENTER
            val s = dp(ctx, 28f)
            layoutParams = LinearLayout.LayoutParams(s, s)
        })

        // 点击 → 启动模块主界面 (UI 已统一到 Compose)
        row.setOnClickListener {
            try {
                val activity = (ctx as? Activity) ?: findActivity(ctx)
                if (activity != null) {
                    val intent = android.content.Intent().apply {
                        setClassName("com.app.hook", "com.example.MainActivity")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                } else {
                    Toast.makeText(ctx, "无法获取当前界面, 请稍后重试", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                try { Toast.makeText(ctx, "菜单唤起失败: ${t.message}", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
            }
        }

        // 底部细分隔线 (与设置页原有分隔风格统一, 让入口"单独成列")
        val divider = View(ctx).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)
            ).apply { setMargins(dp(ctx, 16f), 0, 0, 0) }
        }

        section.addView(row)
        section.addView(divider)
        return section
    }

    // =========================================================================
    //  构造功能开关区块: iOS 风格圆角卡片 + 逐行绿色开关
    // =========================================================================
    private fun buildSwitchSection(ctx: Context): View {
        val t = Theme.current(ctx)
        val section = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_SWITCHES
            background = ctx.iosCardBg(t)
        }
        SETTINGS_SWITCHES.forEachIndexed { idx, (label, key) ->
            section.addView(ctx.iosSwitchRow(label, key, t))
            if (idx < SETTINGS_SWITCHES.size - 1) section.addView(ctx.iosDivider(t))
        }
        return section
    }

    /** 从任意 Context 向上解析出 Activity (Fragment/View 的 context 一般直接是 Activity, 此为兜底) */
    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c != null) {
            if (c is Activity) return c
            c = if (c is android.content.ContextWrapper) c.baseContext else break
        }
        return null
    }

    private fun dp(ctx: Context, dp: Float): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.resources.displayMetrics).toInt()
}
