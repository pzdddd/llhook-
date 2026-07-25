package bxxd.hook

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.example.ui.theme.MyApplicationTheme
import de.robv.android.xposed.XposedBridge

/**
 * 蓝钩悬浮球注入器 (公共, 新老 Xposed API 共用)。
 *
 *  交互:
 *   - 单击 → 弹设置浮窗
 *   - 拖动 → 移动位置 (松手后自动记忆, 下次进入主页沿用)
 *   - 长按 (≥600ms 且未移动) → 隐藏悬浮球
 *
 *  隐藏后恢复方式:
 *   - 长按附近列表「筛选」按钮 → 重新显示
 *   - 「我的」页面田字格「模块入口」→ 打开设置时一并恢复
 *
 *  状态持久化: SharedPreferences (llhook_blued_local_v2):
 *   - float_hidden (bool): 是否隐藏
 *   - float_right_margin / float_bottom_margin (int): 上次位置
 */
object FloatButtonInjector {

    private const val TAG = "LlhookFloatButton"
    private const val PREFS = "llhook_blued_local_v2"
    private const val KEY_HIDDEN = "float_hidden"
    private const val KEY_MARGIN_RIGHT = "float_right_margin"
    private const val KEY_MARGIN_BOTTOM = "float_bottom_margin"

    // 默认位置 (像素), 首次使用时
    private const val DEFAULT_RIGHT_MARGIN = 60
    private const val DEFAULT_BOTTOM_MARGIN = 400
    private const val LONG_PRESS_MS = 600L
    private const val DRAG_THRESHOLD = 10

    /**
     * 注入悬浮球到 [activity] 的内容根视图; 若已注入则跳过 (幂等)。
     * 仅在 HomeActivity 等主界面调用。
     */
    @JvmStatic
    fun inject(activity: Activity) {
        try {
            val rootContainer = activity.window.decorView
                .findViewById<ViewGroup>(android.R.id.content)
                ?: activity.window.decorView as ViewGroup

            // 已注入则跳过 (避免 onResume 重复触发)
            if (rootContainer.findViewWithTag<View>(TAG) != null) return

            // 隐藏状态: 隐藏时不注入, 等恢复入口触发 unhide()
            if (isHidden(activity)) {
                XposedBridge.log("$TAG skip inject (hidden by user)")
                return
            }

            val dm = activity.resources.displayMetrics
            val savedRight = activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getInt(KEY_MARGIN_RIGHT, DEFAULT_RIGHT_MARGIN)
            val savedBottom = activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getInt(KEY_MARGIN_BOTTOM, DEFAULT_BOTTOM_MARGIN)

            val floatButton = FrameLayout(activity).apply {
                tag = TAG
                layoutParams = FrameLayout.LayoutParams(160, 160).apply {
                    gravity = Gravity.END or Gravity.BOTTOM
                    bottomMargin = savedBottom
                    rightMargin = savedRight
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x80000000.toInt())
                    setStroke(4, 0x80FFFFFF.toInt())
                }

                val textView = TextView(activity).apply {
                    text = "蓝钩"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                addView(textView)

                // ===== 拖拽 + 长按隐藏 (长按不影响拖动) =====
                val handler = Handler(Looper.getMainLooper())
                var initX = 0f; var initY = 0f
                var initMarginX = 0; var initMarginY = 0
                var isDragging = false
                var moved = false
                var longPressTriggered = false
                var longPressTask: Runnable? = null

                setOnTouchListener { view, event ->
                    val params = view.layoutParams as FrameLayout.LayoutParams
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initX = event.rawX; initY = event.rawY
                            initMarginX = params.rightMargin; initMarginY = params.bottomMargin
                            isDragging = false
                            moved = false
                            longPressTriggered = false
                            // 调度长按: 600ms 内未移动则触发隐藏
                            longPressTask?.let { handler.removeCallbacks(it) }
                            val task = Runnable {
                                if (!moved) {
                                    longPressTriggered = true
                                    vibrate(view.context)
                                    Toast.makeText(view.context, "已隐藏悬浮球\n长按「筛选」或打开「模块入口」恢复", Toast.LENGTH_LONG).show()
                                    hide(activity, view)
                                }
                            }
                            longPressTask = task
                            handler.postDelayed(task, LONG_PRESS_MS)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initX
                            val dy = event.rawY - initY
                            if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                                isDragging = true
                                moved = true
                                // 一旦移动, 取消长按 (不影响拖动)
                                longPressTask?.let { handler.removeCallbacks(it) }
                            }
                            if (isDragging) {
                                // 限制在屏幕内, 留出按钮一半可见
                                val maxRight = dm.widthPixels - view.width / 2
                                val maxBottom = dm.heightPixels - view.height / 2
                                params.rightMargin = (initMarginX - dx.toInt()).coerceIn(0, maxRight)
                                params.bottomMargin = (initMarginY - dy.toInt()).coerceIn(0, maxBottom)
                                view.layoutParams = params
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            longPressTask?.let { handler.removeCallbacks(it) }
                            // 长按已触发隐藏 → 不再弹面板
                            if (!isDragging && !longPressTriggered) view.performClick()
                            // 记忆最终位置
                            savePosition(view.context, params.rightMargin, params.bottomMargin)
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            longPressTask?.let { handler.removeCallbacks(it) }
                            true
                        }
                        else -> false
                    }
                }

                setOnClickListener { openSettings(activity) }
            }

            rootContainer.addView(floatButton)
        } catch (e: Throwable) {
            XposedBridge.log("llhook float inject err: $e")
        }
    }

    /** 弹出 llhook Compose 设置页 (与「我的」入口共用同一套 UI, 悬浮球用浮窗形态)。 */
    @JvmStatic
    fun openSettings(activity: Activity) {
        try {
            com.example.ui.showHostComposePanel(activity) { onClose ->
                MyApplicationTheme {
                    com.example.ui.MainScreen(hostActivity = activity, inHost = true, panelMode = true)
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("llhook open settings err: $e")
            Toast.makeText(activity, "设置页打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 隐藏 / 恢复 API ====================

    /** 隐藏悬浮球 (长按触发): 写入隐藏标记 + 设 GONE。 */
    @JvmStatic
    fun hide(activity: Activity) {
        try {
            val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                ?: activity.window.decorView as ViewGroup
            val btn = root.findViewWithTag<View>(TAG)
            hide(activity, btn)
        } catch (e: Throwable) { XposedBridge.log("$TAG hide err: $e") }
    }

    private fun hide(activity: Activity, btn: View?) {
        try {
            activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HIDDEN, true).apply()
            btn?.visibility = View.GONE
        } catch (_: Throwable) {}
    }

    /**
     * 恢复悬浮球 (长按筛选 / 模块入口触发): 清除隐藏标记并立即显示;
     * 若当前页未注入则调用 inject 补上。
     */
    @JvmStatic
    fun unhide(activity: Activity) {
        try {
            activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HIDDEN, false).apply()
            val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                ?: activity.window.decorView as ViewGroup
            val btn = root.findViewWithTag<View>(TAG)
            if (btn != null) {
                btn.visibility = View.VISIBLE
            } else {
                // 当前页面未注入 (隐藏时跳过了), 重新注入
                inject(activity)
            }
        } catch (e: Throwable) { XposedBridge.log("$TAG unhide err: $e") }
    }

    private fun isHidden(ctx: android.content.Context): Boolean =
        try { ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_HIDDEN, false) }
        catch (_: Throwable) { false }

    /** 公开查询: 悬浮球当前是否处于隐藏状态 (供 Compose 设置开关初始化)。 */
    @JvmStatic
    fun isCurrentlyHidden(ctx: android.content.Context): Boolean = isHidden(ctx)

    private fun savePosition(ctx: android.content.Context, right: Int, bottom: Int) {
        try {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putInt(KEY_MARGIN_RIGHT, right)
                .putInt(KEY_MARGIN_BOTTOM, bottom)
                .apply()
        } catch (_: Throwable) {}
    }

    private fun vibrate(ctx: android.content.Context) {
        try {
            val v = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(30)
            }
        } catch (_: Throwable) {}
    }
}
