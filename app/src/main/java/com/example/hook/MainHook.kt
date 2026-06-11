package com.example.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    private var isToastShown = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        if (packageName == "com.blued.international.lite" || packageName == "com.soft.blued.lite" || packageName == "com.danlan.xiaolan") {
            XposedBridge.log("Hooking Initialized for: $packageName")
            
            // 为了应对加壳应用，我们劫持 Instrumentation 的 callApplicationOnCreate，
            // 此时真正的 Dex 已经解压并加载到 ClassLoader
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.Instrumentation",
                    lpparam.classLoader,
                    "callApplicationOnCreate",
                    Application::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val app = param.args[0] as Application
                            val realClassLoader = app.classLoader
                            
                            XposedBridge.log("Application created. Real ClassLoader obtained for: $packageName")
                            
                            // 在这里继续 Hook 真实的业务代码
                            hookRealActivity(realClassLoader)
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }
        }
    }
    
    private fun hookRealActivity(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                classLoader,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        try {
                            if (!isToastShown) {
                                isToastShown = true
                                Toast.makeText(activity, "加载成功", Toast.LENGTH_SHORT).show()
                                XposedBridge.log("Toast shown")
                            }
                            
                            val className = activity.javaClass.name
                            if (className == "com.soft.blued.ui.home.HomeActivity" || className.endsWith("HomeActivity")) {
                                injectFloatButton(activity)
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log(e)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }
    }

    private fun injectFloatButton(activity: Activity) {
        val rootContainer = activity.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
            ?: activity.window.decorView as android.view.ViewGroup

        // Avoid injecting multiple times
        if (rootContainer.findViewWithTag<android.view.View>("LspFloatButton") != null) return

        val floatButton = android.widget.FrameLayout(activity).apply {
            tag = "LspFloatButton"
            layoutParams = android.widget.FrameLayout.LayoutParams(160, 160).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                bottomMargin = 400
                rightMargin = 60
            }
            
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x80000000.toInt()) // Semi-transparent black
                setStroke(4, 0x80FFFFFF.toInt())
            }
            
            val textView = android.widget.TextView(activity).apply {
                text = "极速"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, 
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(textView)

            // Implement drag logic
            var initX = 0f
            var initY = 0f
            var initMarginX = 0
            var initMarginY = 0
            var isDragging = false

            setOnTouchListener { view, event ->
                val params = view.layoutParams as android.widget.FrameLayout.LayoutParams
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initX = event.rawX
                        initY = event.rawY
                        initMarginX = params.rightMargin
                        initMarginY = params.bottomMargin
                        isDragging = false
                        true // intercept
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initX
                        val deltaY = event.rawY - initY
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isDragging = true
                        }
                        params.rightMargin = initMarginX - deltaX.toInt()
                        params.bottomMargin = initMarginY - deltaY.toInt()
                        view.layoutParams = params
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            view.performClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            
            setOnClickListener {
                showSettingsDialog(activity)
            }
        }
        
        rootContainer.addView(floatButton)
        XposedBridge.log("Injected Float Button into: ${activity.localClassName}")
    }

    private fun showSettingsDialog(activity: Activity) {
        try {
            val dialog = android.app.Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar_TranslucentDecor)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            
            // To ensure ComposeView works in target app without full Lifecycle components setup,
            // we will use pure Android views for the dialog wrapper, then embed Compose View
            val composeView = androidx.compose.ui.platform.ComposeView(activity).apply {
                id = android.view.View.generateViewId()
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                setContent {
                    com.example.ui.MainScreen()
                }
            }

            // Provide ViewTree setup for Compose
            val decorView = activity.window.decorView
            val lifecycleOwner = decorView.findViewTreeLifecycleOwner() 
                ?: object : androidx.lifecycle.LifecycleOwner {
                    private val registry = androidx.lifecycle.LifecycleRegistry(this).apply {
                        currentState = androidx.lifecycle.Lifecycle.State.RESUMED
                    }
                    override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
                }
                
            composeView.setViewTreeLifecycleOwner(lifecycleOwner)
            
            val savedStateRegistryOwner = decorView.findViewTreeSavedStateRegistryOwner()
                ?: object : androidx.savedstate.SavedStateRegistryOwner {
                    private val registryController = androidx.savedstate.SavedStateRegistryController.create(this).apply {
                        performRestore(null)
                    }
                    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = registryController.savedStateRegistry
                    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleOwner.lifecycle
                }
            composeView.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            
            val viewModelStoreOwner = decorView.findViewTreeViewModelStoreOwner()
                ?: object : androidx.lifecycle.ViewModelStoreOwner {
                    private val store = androidx.lifecycle.ViewModelStore()
                    override val viewModelStore: androidx.lifecycle.ViewModelStore get() = store
                }
            composeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)

            dialog.setContentView(composeView)
            
            dialog.window?.apply {
                setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT
                )
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
            dialog.show()
        } catch (e: Throwable) {
            XposedBridge.log("Failed to show dialog: ${e.message}")
            Toast.makeText(activity, "无法加载设置界面", Toast.LENGTH_SHORT).show()
        }
    }
}
