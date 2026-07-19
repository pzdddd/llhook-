package bxxd.hook

import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object FeedHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Blued 的列表极大概率使用了开源的 BRVAH (BaseRecyclerViewAdapterHelper) 框架
            // 我们直接拦截这个万能适配器的核心渲染方法 onBindViewHolder
            val baseAdapterClass = lpparam.classLoader.loadClass("com.chad.library.adapter.base.BaseQuickAdapter")
            
            baseAdapterClass.findMethod { name == "onBindViewHolder" && parameterTypes.size == 2 }.hookBefore { param ->
                try {
                    val adapter = param.thisObject
                    val position = param.args[1] as Int
                    
                    // 获取当前即将渲染到屏幕上的那条“动态实体数据 (Model)”
                    val item = XposedHelpers.callMethod(adapter, "getItem", position) ?: return@hookBefore
                    val itemClass = item.javaClass
                    
                    // 【魔法 1：强制显示隐藏的距离】
                    try {
                        val hideDistField = itemClass.getDeclaredField("is_hide_distance")
                        hideDistField.isAccessible = true
                        if (hideDistField.getInt(item) == 1) {
                            hideDistField.setInt(item, 0) // 强行改为 0 (不隐藏)
                        }
                    } catch (e: Throwable) { /* 找不到字段说明这条数据不是动态(可能是头部布局等)，直接忽略 */ }

                    // 【魔法 2：强制显示隐藏的最后在线时间】
                    try {
                        val hideTimeField = itemClass.getDeclaredField("is_hide_last_operate")
                        hideTimeField.isAccessible = true
                        if (hideTimeField.getInt(item) == 1) {
                            hideTimeField.setInt(item, 0)
                        }
                    } catch (e: Throwable) {}

                    // 【魔法 3：动态流免广告】
                    try {
                        val isAdsField = itemClass.getDeclaredField("is_ads")
                        isAdsField.isAccessible = true
                        if (isAdsField.getInt(item) == 1) {
                            isAdsField.setInt(item, 0) // 剥夺它的广告特权标识
                        }
                    } catch (e: Throwable) {}

                } catch (e: Throwable) {
                    // 极简容错：任何异常直接吞掉，绝不影响宿主 App 运行
                }
            }
        } catch (e: Throwable) {
            // 如果 Blued 更新换掉了 BRVAH 框架，这里会捕获异常，不会闪退
            e.printStackTrace()
        }
    }
}
