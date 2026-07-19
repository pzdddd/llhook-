package bxxd.hook

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object ProfileHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fragmentClass = lpparam.classLoader.loadClass(Config.TargetClasses.USER_INFO_FRAGMENT_NEW)

            // 拦截 onViewCreated，在页面创建完成时注入我们的按钮
            fragmentClass.findMethod {
                name == "onViewCreated" && parameterTypes.size == 2 && parameterTypes[0] == View::class.java
            }.hookAfter { param ->
                val fragmentInstance = param.thisObject
                val rootView = param.args[0] as? ViewGroup ?: return@hookAfter
                val activity = rootView.context as? Activity ?: return@hookAfter

                // 移除之前可能重复添加的按钮
                rootView.findViewWithTag<View>("TrackBtn")?.let { rootView.removeView(it) }

                // 创建一个精致的悬浮按钮
                val trackBtn = TextView(activity).apply {
                    tag = "TrackBtn"
                    text = "追踪此人"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(30, 15, 30, 15)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E91E63")) // 骚粉色
                        cornerRadius = 50f
                        setStroke(2, Color.WHITE)
                    }
                    
                    // 放在右上角
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = 150
                        marginEnd = 40
                    }

                    // 点击事件
                    setOnClickListener {
                        // 尝试获取当前主页的 UserInfo 数据
                        try {
                            val userObj = XposedHelpers.getObjectField(fragmentInstance, "user")
                            val uid = XposedHelpers.getObjectField(userObj, "uid") as? String
                            val distance = XposedHelpers.getObjectField(userObj, "distance") as? String
                            
                            if (uid != null) {
                                // 这里暂时弹窗提示，之后我们会在这里启动 LocationTracker！
                                Toast.makeText(activity, "准备追踪 UID: $uid\n当前距离: $distance", Toast.LENGTH_LONG).show()
                                
                                // TODO: 等待补齐 NetworkManager 后，在此处执行三点定位算法
                                
                            } else {
                                Toast.makeText(activity, "获取目标用户信息失败", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(activity, "暂无法获取用户信息，请稍后再试", Toast.LENGTH_SHORT).show()
                            e.printStackTrace()
                        }
                    }
                }

                // 将按钮添加到根布局
                rootView.addView(trackBtn)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
