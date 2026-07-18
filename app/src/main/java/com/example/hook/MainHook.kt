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
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class MainHook : XposedModule() {

    private var isToastShown = false
    private var prefs: android.content.SharedPreferences? = null
    
    private fun logMsg(msg: String) {
        // Priority 4 = Log.INFO
        log(4, "MainHook", msg)
    }

    private fun logError(msg: String, tr: Throwable? = null) {
        // Priority 6 = Log.ERROR
        log(6, "MainHook", msg, tr)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        val packageName = param.packageName
        if (packageName == "com.blued.international.lite" || packageName == "com.soft.blued.lite" || packageName == "com.danlan.xiaolan") {
            logMsg("Hooking Initialized for: $packageName")
            
            try {
                val instrumentationClass = Class.forName("android.app.Instrumentation", false, param.defaultClassLoader)
                val method = instrumentationClass.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
                
                hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        
                        val app = chain.args[0] as Application
                        val realClassLoader = app.classLoader
                        
                        // Initialize prefs when Application is created
                        prefs = app.getSharedPreferences("blued_hook_prefs", android.content.Context.MODE_PRIVATE)
                        
                        logMsg("Application created. Real ClassLoader obtained for: $packageName")
                        
                        hookRealActivity(realClassLoader, param)
                        
                        return result
                    }
                })
            } catch (e: Throwable) {
                logError(e.stackTraceToString(), e)
            }
        }
    }
    
    private fun hookRealActivity(classLoader: ClassLoader, param: XposedModuleInterface.PackageLoadedParam) {
        hookLocationManager(classLoader, param)
        try {
            val activityClass = Class.forName("android.app.Activity", false, classLoader)
            val method = activityClass.getDeclaredMethod("onResume")
            
            hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    
                    val activity = chain.thisObject as Activity
                    try {
                        if (!isToastShown) {
                            isToastShown = true
                            Toast.makeText(activity, "加载成功", Toast.LENGTH_SHORT).show()
                            logMsg("Toast shown")
                        }
                        
                        val className = activity.javaClass.name
                        if (className == "com.soft.blued.ui.home.HomeActivity" || className.endsWith("HomeActivity")) {
                            injectFloatButton(activity)
                        }
                    } catch (e: Throwable) {
                        logError(e.stackTraceToString(), e)
                    }
                    
                    return result
                }
            })
        } catch (e: Throwable) {
            logError(e.stackTraceToString(), e)
        }
    }

    private fun hookLocationManager(classLoader: ClassLoader, param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val locationManagerClass = Class.forName("android.location.LocationManager", false, classLoader)
            val getLastKnownLocationMethod = locationManagerClass.getDeclaredMethod("getLastKnownLocation", String::class.java)
            hook(getLastKnownLocationMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    logMsg("Intercepted getLastKnownLocation")
                    val result = chain.proceed()
                    
                    try {
                        val p = this@MainHook.prefs
                        if (p != null) {
                            val virtualLocation = p.getBoolean("virtualLocation", false)
                            if (virtualLocation) {
                                val lat = p.getFloat("loc_lat", 39.9042f).toDouble()
                                val lng = p.getFloat("loc_lng", 116.4074f).toDouble()
                                
                                val provider = chain.args[0] as? String ?: android.location.LocationManager.GPS_PROVIDER
                                val location = android.location.Location(provider).apply {
                                    latitude = lat
                                    longitude = lng
                                    accuracy = 10f
                                    time = System.currentTimeMillis()
                                    elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                }
                                logMsg("Virtual location applied: $lat, $lng")
                                return location
                            }
                        }
                    } catch (e: Throwable) {
                         logError("Failed to apply virtual location: ${e.message}", e)
                    }

                    return result
                }
            })
        } catch (e: Throwable) {
            logError("Failed to hook LocationManager: ${e.message}", e)
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
        logMsg("Injected Float Button into: ${activity.localClassName}")
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
                    // Get SharedPreferences from the target context
                    val context = activity.applicationContext
                    val prefs = context.getSharedPreferences("blued_hook_prefs", android.content.Context.MODE_PRIVATE)
                    com.example.ui.MainScreen(prefs)
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
            logError("Failed to show dialog: ${e.message}", e)
            Toast.makeText(activity, "无法加载设置界面", Toast.LENGTH_SHORT).show()
        }
    }
}
