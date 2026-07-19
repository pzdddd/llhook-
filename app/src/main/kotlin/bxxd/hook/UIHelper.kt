package bxxd.hook

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*

// dp 转 px 的全局扩展函数
fun Context.dp2px(dp: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

fun Context.createLargeTitle(title: String): TextView {
    val t = Theme.current(this)
    return TextView(this).apply {
        this.text = title
        textSize = 34f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(t.textPrimary)
        setPadding(dp2px(24f), 0, dp2px(24f), dp2px(20f))
    }
}

fun Context.createCardBackground(colorString: String, radius: Float): GradientDrawable {
    return GradientDrawable().apply {
        setColor(Color.parseColor(colorString))
        cornerRadius = dp2px(radius).toFloat()
    }
}

fun Context.createSettingsCard(title: String): LinearLayout {
    val t = Theme.current(this)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = createCardBackground("#${Integer.toHexString(t.card).substring(2).padStart(6, '0')}", 24f)
        if (Build.VERSION.SDK_INT >= 21) elevation = 8f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp2px(20f), 0, dp2px(20f), dp2px(20f)) }
        setPadding(0, dp2px(10f), 0, dp2px(10f))
    }
    val titleView = TextView(this).apply {
        this.text = title
        textSize = 13f
        setTextColor(t.accent) // 系统蓝小分组标题
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(dp2px(24f), dp2px(16f), dp2px(20f), dp2px(8f))
    }
    card.addView(titleView)
    return card
}

// 🚀 【终极重写】：生成一个 HyperOS 风格的大圆角、蓝白配色的“胶囊/方块”开关
fun Context.createHyperOSSwitch(title: String, prefKey: String, switchMap: MutableMap<String, Switch>): View {
    val activity = this as Activity
    val isEnabled = Config.isFeatureEnabled(prefKey, this)
    val t = Theme.current(this)

    // 1. 配色随主题 (HyperOS 蓝/灰 → iOS 蓝主题)
    val colorActiveBlue = t.accent
    val colorInactiveGray = if (t.isDark) t.cardElevated else Color.parseColor("#F2F2F7")
    val textOnColor = Color.WHITE
    val textOffColor = t.textPrimary

    // 2. 创建最外层可点击卡片容器
    val cardView = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp2px(75f) // 大尺寸 touch target
        ).apply { setMargins(dp2px(20f), dp2px(10f), dp2px(20f), dp2px(10f)) }
        
        background = createCardBackground(if (isEnabled) "#2196F3" else "#F2F2F7", 20f)
        isClickable = true
        isFocusable = true
    }

    // 3. 内部布局容器 (Horizontal LinearLayout)
    val contentLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp2px(20f), 0, dp2px(20f), 0)
    }
    cardView.addView(contentLayout, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    val iconView = TextView(this).apply {
        text = "⚡" // 可以根据 prefKey 动态替换这里
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(if (isEnabled) textOnColor else textOffColor)
        layoutParams = LinearLayout.LayoutParams(dp2px(40f), dp2px(40f)).apply { marginEnd = dp2px(15f) }
    }
    contentLayout.addView(iconView)

    // 5. 中间文字描述 (Vertical LinearLayout)
    val textLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    
    // 标题文本
    val titleText = TextView(this).apply {
        this.text = title
        textSize = 16f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(if (isEnabled) textOnColor else textOffColor)
    }
    
    // 状态描述文本 (如: "已开启" / "已关闭")
    val descText = TextView(this).apply {
        this.text = if (isEnabled) "已开启" else "已关闭"
        textSize = 12f
        setTextColor(if (isEnabled) Color.parseColor("#B3FFFFFF") else t.textSecondary)
    }
    
    textLayout.addView(titleText)
    textLayout.addView(descText)
    contentLayout.addView(textLayout)

    // 6. 核心逻辑：定义点击时的颜色和文字切换动画
    cardView.setOnClickListener {
        val currentStatus = Config.isFeatureEnabled(prefKey, this)
        val newStatus = !currentStatus
        Config.setFeatureEnabled(prefKey, newStatus, this) // 保存血肉

        // 颜色插值器动画 (平滑变色)
        val argbEvaluator = ArgbEvaluator()
        val bgAnim = ValueAnimator.ofObject(
            argbEvaluator,
            if (currentStatus) colorActiveBlue else colorInactiveGray,
            if (newStatus) colorActiveBlue else colorInactiveGray
        )
        bgAnim.duration = 180
        bgAnim.interpolator = DecelerateInterpolator()
        bgAnim.addUpdateListener { animator ->
            cardView.background = createCardBackground(animator.animatedValue.toString(), 20f)
        }
        bgAnim.start()

        // 更新内部文字颜色和描述
        if (newStatus) {
            titleText.setTextColor(textOnColor)
            descText.setTextColor(Color.parseColor("#B3FFFFFF"))
            descText.text = "已开启"
            iconView.setTextColor(textOnColor)
        } else {
            titleText.setTextColor(textOffColor)
            descText.setTextColor(t.textSecondary)
            descText.text = "已关闭"
            iconView.setTextColor(textOffColor)
        }

        // 🚀 【同步核心】如果 Blued 此时活着，告诉它我也改了
        try {
            val syncIntent = android.content.Intent("bxxd.hook.MAIN_SYNC_PUSH")
            syncIntent.setPackage(Config.currentBluedPackage)
            syncIntent.putExtra("key", prefKey)
            syncIntent.putExtra("value", newStatus.toString())
            this.sendBroadcast(syncIntent)
        } catch (e: Throwable) {}
    }
    
    return cardView
}

// 废弃旧的方法调用，为了兼容之前的逻辑，我把它重定向到新方法
fun Context.createSwitchRow(title: String, prefKey: String, switchMap: MutableMap<String, Switch>): View {
    return createHyperOSSwitch(title, prefKey, switchMap)
}

fun Context.createAboutRow(title: String, desc: String): LinearLayout {
    val t = Theme.current(this)
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp2px(20f), dp2px(12f), dp2px(20f), dp2px(12f))
    }
    row.addView(TextView(this).apply { 
        this.text = title; textSize = 16f; setTextColor(t.textPrimary); setTypeface(Typeface.DEFAULT_BOLD) 
    })
    row.addView(TextView(this).apply { 
        this.text = desc; textSize = 14f; setTextColor(t.textSecondary); setPadding(0, dp2px(4f), 0, 0) 
    })
    return row
}

fun Context.createDivider(): View {
    val t = Theme.current(this)
    return View(this).apply {
        setBackgroundColor(t.separator)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
            setMargins(dp2px(20f), 0, dp2px(20f), 0)
        }
    }
}

fun Context.createBottomTab(textStr: String, iconStr: String, index: Int): LinearLayout {
    val tab = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        gravity = Gravity.CENTER
        // 这里需要持有它的 index，在 MainActivity 里点击时传过去
        tag = index
    }
    val iconView = TextView(this).apply { this.text = iconStr; textSize = 20f; gravity = Gravity.CENTER }
    val textView = TextView(this).apply { this.text = textStr; textSize = 11f; gravity = Gravity.CENTER }
    tab.addView(iconView)
    tab.addView(textView)
    return tab
}
