package com.example.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 在宿主 (Blued) 进程内, 于目标 [activity] 上弹起一个 Compose [Dialog], 承载任意 Compose 内容。
 *
 * 它负责把 ComposeView 所需的 ViewTree (Lifecycle / SavedStateRegistry / ViewModelStore) owners
 * 补齐 (宿主 Activity 的 decorView 上通常没有这些), 并以透明全屏 Dialog 形式显示。
 *
 * 两种展示形态:
 *  - [showHostComposeScreen]: 全屏 (用于「我的」页面田字格入口);
 *  - [showHostComposePanel]:  居中浮窗 (用于右下角悬浮球点击, 保留悬浮窗观感)。
 *
 * @param activity 宿主 Activity
 * @param onError 弹窗构建失败时的回调 (默认弹 Toast)
 * @param content Compose 内容; 接收 [onClose] 回调, 调用即 dismiss 整个弹窗。
 *               (调用方应自行包一层 MyApplicationTheme)
 */
/**
 * 居中浮窗形态 (悬浮球点击专用): 屏幕中央圆角面板, 带半透明遮罩, 不覆盖全屏。
 *
 * 与 [showHostComposeScreen] 唯一区别: Dialog 窗口尺寸为屏幕 92%×88% + 圆角裁剪 + 0.5 遮罩,
 * 营造悬浮窗观感 (用户能看到宿主界面在四周若隐若现)。
 */
fun showHostComposePanel(
    activity: Activity,
    onError: (Throwable) -> Unit = { e ->
        Toast.makeText(activity, "界面加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
    },
    content: @Composable (onClose: () -> Unit) -> Unit
) = showHostDialog(activity, fullScreen = false, rounded = true, onError = onError, content = content)

fun showHostComposeScreen(
    activity: Activity,
    onError: (Throwable) -> Unit = { e ->
        Toast.makeText(activity, "界面加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
    },
    content: @Composable (onClose: () -> Unit) -> Unit
) = showHostDialog(activity, fullScreen = true, rounded = false, onError = onError, content = content)

/**
 * 内部统一实现: [fullScreen] = true 全屏 / false 居中浮窗。
 *
 * 核心约束: SavedStateRegistryController.performRestore 要求执行时 lifecycle 必须处于
 * [Lifecycle.State.INITIALIZED]; 之后再走完 ON_CREATE → ON_START → ON_RESUME 流转。
 * 由 [HostLifecycleOwner] 严格遵守此顺序。
 */
private fun showHostDialog(
    activity: Activity,
    fullScreen: Boolean,
    rounded: Boolean,
    onError: (Throwable) -> Unit,
    content: @Composable (onClose: () -> Unit) -> Unit
) {
    try {
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar_TranslucentDecor)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        // 统一构造一个完整 owner (Lifecycle + SavedStateRegistry + ViewModelStore 三合一),
        // 不依赖 decorView 上可能存在也可能不存在的宿主 owner (行为可预测)。
        val owner = HostLifecycleOwner()
        val viewModelStore = ViewModelStore()

        val composeView = ComposeView(activity).apply {
            id = View.generateViewId()
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 只挂在 ComposeView 子树上 (setViewTree* 是 view 级别的), 不影响宿主其他 view
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore get() = viewModelStore
            })
            setContent {
                if (rounded) {
                    // 浮窗: 圆角裁剪容器, 让内部背景渐变也随圆角裁剪
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(22.dp))
                    ) { content { dialog.dismiss() } }
                } else {
                    content { dialog.dismiss() }
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.apply {
            if (fullScreen) {
                // 全屏: 铺满, 完全透明背景 (Compose 内容自绘整个屏幕)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            } else {
                // 浮窗: 居中, 92%×88%, 透明背景 + 0.5 遮罩 (四周能看到宿主界面)
                val dm = activity.resources.displayMetrics
                val w = (dm.widthPixels * 0.92f).toInt()
                val h = (dm.heightPixels * 0.88f).toInt()
                setLayout(w, h)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(0.5f)
            }
        }

        // Dialog 销毁时正确收尾 lifecycle (避免泄漏), 并清空 ViewModelStore
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                viewModelStore.clear()
            }
        })
        dialog.setOnDismissListener {
            owner.performDestroy()
        }

        dialog.show()
    } catch (e: Throwable) {
        onError(e)
    }
}

/**
 * 宿主内 Compose 渲染专用的 Lifecycle + SavedStateRegistry owner。
 *
 * 核心约束: SavedStateRegistryController.performRestore 要求执行时 lifecycle 必须处于
 * [Lifecycle.State.INITIALIZED]; 之后再走完 ON_CREATE → ON_START → ON_RESUME 流转。
 *
 * 因此本类在构造时保持 lifecycle 为 INITIALIZED, 在 init 块里调用 performRestore,
 * 然后才通过 handleLifecycleEvent 推进到 RESUMED。
 */
private class HostLifecycleOwner : SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = controller.savedStateRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    init {
        // ⚠️ 此时 lifecycleRegistry 还是 INITIALIZED (LifecycleRegistry 默认初始状态), 合法
        controller.performRestore(null)
        // 现在 restarter 已注册成功, 推进 lifecycle 到 RESUMED
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** Dialog dismiss 时调用, 走完 ON_STOP → ON_DESTROY 让所有 observer 收到销毁事件。 */
    fun performDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
