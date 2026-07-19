package bxxd.hook

import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.callbacks.XC_LoadPackage

object LiveBlockHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val urlClass = lpparam.classLoader.loadClass("com.blued.android.module.common.url.BluedHttpUrl")

            urlClass.findMethod { name == "s" && parameterTypes.isEmpty() }.hookBefore { param ->
                // 实时检查开关状态
                if (Config.isFeatureEnabled("switch_block_live")) {
                    param.result = "http://127.0.0.1" 
                }
            }

            urlClass.findMethod { name == "F" && parameterTypes.isEmpty() }.hookBefore { param ->
                if (Config.isFeatureEnabled("switch_block_live")) {
                    param.result = "127.0.0.1"
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
