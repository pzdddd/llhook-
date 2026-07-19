package bxxd.hook

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.example.ui.showHostComposeScreen
import com.example.ui.theme.MyApplicationTheme
import de.robv.android.xposed.XposedBridge

/**
 * 蓝钩悬浮球注入器 (公共, 新老 Xposed API 共用)。
 *
 *  解决问题: 之前悬浮球只在 com.example.hook.MainHook (新 API) 注入,
 *  老版 LSPosed/EdXoped 框架不加载新 API → 用户看不到悬浮球。
 *  现统一由各 API 入口 hook 到 HomeActivity.onResume 后调用本注入器。
 *
 *  另: 「我的」页面田字格的「模块入口」也已直接弹设置页 (HomeQQHook), 二者互为双保险。
 */
object FloatButtonInjector {

    private const val TAG = "LlhookFloatButton"

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

            val floatButton = FrameLayout(activity).apply {
                tag = TAG
                layoutParams = FrameLayout.LayoutParams(160, 160).apply {
                    gravity = Gravity.END or Gravity.BOTTOM
                    bottomMargin = 400
                    rightMargin = 60
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

                // 拖拽逻辑
                var initX = 0f; var initY = 0f
                var initMarginX = 0; var initMarginY = 0
                var isDragging = false

                setOnTouchListener { view, event ->
                    val params = view.layoutParams as FrameLayout.LayoutParams
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initX = event.rawX; initY = event.rawY
                            initMarginX = params.rightMargin; initMarginY = params.bottomMargin
                            isDragging = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initX
                            val dy = event.rawY - initY
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                            params.rightMargin = initMarginX - dx.toInt()
                            params.bottomMargin = initMarginY - dy.toInt()
                            view.layoutParams = params
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) view.performClick()
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
            // 悬浮球点击 → 居中浮窗面板 (不全屏, 保留悬浮窗观感)
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
}
