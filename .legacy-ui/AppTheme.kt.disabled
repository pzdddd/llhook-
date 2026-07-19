package bxxd.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * ============================================================================
 *  AppTheme —— 统一 iOS / HyperOS 风格主题系统 (深色模式自适应)
 * ============================================================================
 *
 *  设计语言参考 iOS 分组列表 (Grouped Table View) + HyperOS 圆角卡片:
 *    - 浅色: #F2F2F7 背景 + #FFFFFF 卡片
 *    - 深色: #000000 背景 + #1C1C1E 卡片
 *    - 圆角卡片 (12dp 连续圆角) 分组承载行
 *    - 系统蓝/绿/红/橙 强调色
 *    - iOS 风格绿色拨动开关 (Switch tint)
 *    - 所有组件自动适配日/夜间, 调用方只需用 Theme.current(ctx) 取配色
 *
 *  使用方式:
 *    val t = Theme.current(ctx)
 *    val section = ctx.iosSection(t)        // 圆角卡片容器
 *    val row = ctx.iosSwitchRow("标题", key, t) // 开关行
 *    val btn = ctx.iosButton("确定", t, IOSShape.PRIMARY)
 * ============================================================================
 */
object Theme {

    /**
     * 配色集合。所有颜色用 Int 存储, 避免到处 parseColor。
     * 命名对齐 iOS 系统色 + Material。
     */
    data class Colors(
        val isDark: Boolean,
        // —— 表面层 ——
        val background: Int,        // 整体背景 (弹窗/页面底色)
        val card: Int,              // 卡片/分组背景
        val cardElevated: Int,      // 凸起卡片 (搜索框/浮层)
        val navBar: Int,            // 顶栏
        // —— 文字 ——
        val textPrimary: Int,       // 主标题
        val textSecondary: Int,     // 副标题/说明
        val textTertiary: Int,      // 占位/禁用
        // —— 分隔/描边 ——
        val separator: Int,         // 分隔线
        val separatorMuted: Int,    // 更淡的分隔
        // —— 强调色 (iOS system colors) ——
        val accent: Int,            // 系统蓝
        val accentLight: Int,       // 浅蓝 (渐变用)
        val success: Int,           // 绿 (开关 ON)
        val warning: Int,           // 橙
        val danger: Int,            // 红 (删除/危险)
        // —— 开关配色 ——
        val switchOn: Int,          // 开关打开轨道色 (iOS 绿)
        val switchOff: Int,         // 开关关闭轨道色 (浅灰)
    )

    /** 浅色配色 (iOS Light) */
    private val LIGHT = Colors(
        isDark = false,
        background = Color.parseColor("#F2F2F7"),
        card = Color.parseColor("#FFFFFF"),
        cardElevated = Color.parseColor("#FFFFFF"),
        navBar = Color.parseColor("#F9F9F9"),
        textPrimary = Color.parseColor("#1C1C1E"),
        textSecondary = Color.parseColor("#8E8E93"),
        textTertiary = Color.parseColor("#C7C7CC"),
        separator = Color.parseColor("#E5E5EA"),
        separatorMuted = Color.parseColor("#EDEDF0"),
        accent = Color.parseColor("#007AFF"),
        accentLight = Color.parseColor("#5AC8FA"),
        success = Color.parseColor("#34C759"),
        warning = Color.parseColor("#FF9500"),
        danger = Color.parseColor("#FF3B30"),
        switchOn = Color.parseColor("#3D7DF4"),  // MIUI 蓝
        switchOff = Color.parseColor("#E9E9EA"),
    )

    /** 深色配色 (iOS Dark) */
    private val DARK = Colors(
        isDark = true,
        background = Color.parseColor("#000000"),
        card = Color.parseColor("#1C1C1E"),
        cardElevated = Color.parseColor("#2C2C2E"),
        navBar = Color.parseColor("#1C1C1E"),
        textPrimary = Color.parseColor("#FFFFFF"),
        textSecondary = Color.parseColor("#98989F"),
        textTertiary = Color.parseColor("#48484A"),
        separator = Color.parseColor("#38383A"),
        separatorMuted = Color.parseColor("#2C2C2E"),
        accent = Color.parseColor("#0A84FF"),
        accentLight = Color.parseColor("#64D2FF"),
        success = Color.parseColor("#30D158"),
        warning = Color.parseColor("#FF9F0A"),
        danger = Color.parseColor("#FF453A"),
        switchOn = Color.parseColor("#4A82F6"),  // MIUI 蓝 (深色模式稍亮)
        switchOff = Color.parseColor("#39393D"),
    )

    /** 根据当前 Context 的夜间模式返回配色 */
    fun current(ctx: Context): Colors {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        return if (night) DARK else LIGHT
    }

    // ========================================================================
    //  尺寸 token (dp)
    // ========================================================================
    const val RADIUS_CARD = 14f       // 卡片圆角
    const val RADIUS_BUTTON = 12f     // 按钮圆角
    const val RADIUS_PILL = 22f       // 胶囊按钮圆角
    const val INSET_HORIZONTAL = 16f  // 卡片左右外边距 (iOS inset)
    const val ROW_HEIGHT = 50f        // 标准行高
}

// ============================================================================
//  Context 扩展: dp 转换
// ============================================================================
fun Context.dp2pxV(dp: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

// ============================================================================
//  扩展构建器: iOS 风格组件
// ============================================================================

/** iOS 大标题 (iOS Large Title) —— 粗体大字, 左对齐 */
fun Context.iosLargeTitle(text: String, t: Theme.Colors): TextView = TextView(this).apply {
    this.text = text
    textSize = 28f
    setTextColor(t.textPrimary)
    setTypeface(Typeface.DEFAULT_BOLD)
    setPadding(dp2pxV(20f), dp2pxV(8f), dp2pxV(20f), dp2pxV(6f))
}

/** iOS 分组小标题 (Section Header) —— 小字, 大写感, 灰色 */
fun Context.iosSectionHeader(text: String, t: Theme.Colors): TextView = TextView(this).apply {
    this.text = text
    textSize = 13f
    setTextColor(t.textSecondary)
    setTypeface(Typeface.DEFAULT_BOLD)
    setPadding(dp2pxV(20f), dp2pxV(18f), dp2pxV(20f), dp2pxV(6f))
}

/** iOS 分组小脚注 (Section Footer) —— 极小灰字说明 */
fun Context.iosSectionFooter(text: String, t: Theme.Colors): TextView = TextView(this).apply {
    this.text = text
    textSize = 12f
    setTextColor(t.textSecondary)
    setLineSpacing(dp2pxV(2f).toFloat(), 1f)
    setPadding(dp2pxV(20f), dp2pxV(6f), dp2pxV(20f), dp2pxV(8f))
}

/** 圆角卡片背景 (连续圆角, 可选阴影/描边) */
fun Context.iosCardBg(t: Theme.Colors, radiusDp: Float = Theme.RADIUS_CARD): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(t.card)
        cornerRadius = dp2pxV(radiusDp).toFloat()
    }

/**
 * iOS 分组卡片容器 —— 圆角白底, 带柔和阴影。
 * 往里面逐行 addView 即可组成设置组。
 */
fun Context.iosSection(t: Theme.Colors): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = iosCardBg(t)
    if (Build.VERSION.SDK_INT >= 21) elevation = dp2pxV(1f).toFloat()
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        setMargins(dp2pxV(Theme.INSET_HORIZONTAL), 0, dp2pxV(Theme.INSET_HORIZONTAL), dp2pxV(0f))
    }
    clipToOutline = true
}

/** 分隔线 (inset 左缩进, 与 iOS 分组行分隔风格一致) */
fun Context.iosDivider(t: Theme.Colors, leftInsetDp: Float = 16f, heightDp: Float = 0.5f): View =
    View(this).apply {
        setBackgroundColor(t.separator)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxOf(1, dp2pxV(heightDp))
        ).apply { setMargins(dp2pxV(leftInsetDp), 0, 0, 0) }
    }

/**
 * iOS 风格开关行 —— 左标题 + 右绿色拨动开关。
 * 开关 tint 成 iOS 绿色 (on) / 浅灰 (off), 自动读取/写入 Config。
 */
fun Context.iosSwitchRow(
    title: String,
    prefKey: String,
    t: Theme.Colors,
    subtitle: String? = null
): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp2pxV(16f), dp2pxV(12f), dp2pxV(16f), dp2pxV(12f))
        minimumHeight = dp2pxV(Theme.ROW_HEIGHT)
        isClickable = true
        isFocusable = true
    }
    // 左侧文字列
    val textCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    textCol.addView(TextView(this).apply {
        text = title
        textSize = 16f
        setTextColor(t.textPrimary)
    })
    if (subtitle != null) {
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(t.textSecondary)
            setPadding(0, dp2pxV(2f), 0, 0)
        })
    }
    row.addView(textCol)
    // 右侧 iOS 风格开关
    val sw = Switch(this).apply {
        isChecked = Config.isFeatureEnabled(prefKey, this@iosSwitchRow)
        // iOS 绿色拨动: thumb 恒白, track 用 tint 切换绿/灰
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                trackTintList = android.content.res.ColorStateList.valueOf(if (isChecked) t.switchOn else t.switchOff)
                setOnCheckedChangeListener { v, checked ->
                    (v as Switch).trackTintList = android.content.res.ColorStateList.valueOf(if (checked) t.switchOn else t.switchOff)
                }
            }
        } catch (_: Throwable) {}
        setOnCheckedChangeListener { _, checked ->
            Config.setFeatureEnabled(prefKey, checked, this@iosSwitchRow)
        }
    }
    row.addView(sw)
    return row
}

/** 按钮形状类型 */
enum class IOSShape { PRIMARY, SECONDARY, DESTRUCTIVE, GHOST }

/**
 * iOS 风格按钮 —— 实心填充 + 大圆角, 自带按压涟漪。
 * - PRIMARY:   蓝底白字 (主操作)
 * - SECONDARY: 浅蓝底蓝字 (次要)
 * - DESTRUCTIVE: 红底白字 (删除/危险)
 * - GHOST:     透明底强调色字 (文字按钮)
 */
fun Context.iosButton(
    text: String,
    t: Theme.Colors,
    shape: IOSShape = IOSShape.PRIMARY,
    onClick: (View) -> Unit = {}
): android.widget.Button = android.widget.Button(this).apply {
    this.text = text
    textSize = 15f
    isAllCaps = false
    stateListAnimator = null
    val (bgColor, fgColor) = when (shape) {
        IOSShape.PRIMARY -> t.accent to Color.WHITE
        IOSShape.SECONDARY -> (if (t.isDark) Color.argb(40, 10, 132, 255) else Color.parseColor("#E3F0FF")) to t.accent
        IOSShape.DESTRUCTIVE -> t.danger to Color.WHITE
        IOSShape.GHOST -> Color.TRANSPARENT to t.accent
    }
    val baseBg = GradientDrawable().apply {
        cornerRadius = dp2pxV(Theme.RADIUS_BUTTON).toFloat()
        if (shape == IOSShape.GHOST) {
            setStroke(dp2pxV(1f), t.separator)
            setColor(if (t.isDark) t.card else Color.WHITE)
        } else {
            setColor(bgColor)
        }
    }
    // 涟漪反馈 (API21+), 低版本回退纯色
    background = if (Build.VERSION.SDK_INT >= 21) {
        RippleDrawable(
            android.content.res.ColorStateList.valueOf(
                if (shape == IOSShape.PRIMARY || shape == IOSShape.DESTRUCTIVE)
                    Color.argb(60, 255, 255, 255) else Color.argb(30, 0, 0, 0)
            ),
            baseBg, null
        )
    } else baseBg
    setTextColor(fgColor)
    val lp = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp2pxV(46f)
    )
    lp.setMargins(0, dp2pxV(6f), 0, dp2pxV(6f))
    layoutParams = lp
    setOnClickListener { onClick(this) }
}

/** iOS 弹窗主题资源 id (日/夜间) */
fun iosDialogTheme(t: Theme.Colors): Int =
    if (t.isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert
    else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
