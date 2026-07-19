package bxxd.hook

import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.callbacks.XC_LoadPackage

object ChatAdvanceHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookReadReceipt(lpparam)
        hookScreenshotProtection(lpparam)
    }

    // 1. 悄悄查看 (拦截底层的 gRPC 已读回执请求)
    private fun hookReadReceipt(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val descriptorClass = lpparam.classLoader.loadClass(Config.TargetClasses.GRPC_METHOD_DESCRIPTOR)
            
            // 拦截 io.grpc.MethodDescriptor.generateFullMethodName 方法
            descriptorClass.findMethod {
                name == "generateFullMethodName" &&
                parameterTypes.size == 2 &&
                parameterTypes[0] == String::class.java &&
                parameterTypes[1] == String::class.java
            }.hookBefore { param ->
                // 检查开关
                if (!Config.isFeatureEnabled("switch_read_receipt")) return@hookBefore

                val serviceName = param.args[0] as? String ?: return@hookBefore
                val methodName = param.args[1] as? String ?: return@hookBefore

                // 如果发现是发送“已读”回执的请求，直接把参数清空，让它请求失败
                if (serviceName == "com.blued.im.private_chat.Receipt" && methodName == "Read") {
                    param.args[0] = ""
                    param.args[1] = ""
                }
            }
        } catch (e: Exception) { 
            e.printStackTrace() 
        }
    }

    // 2. 破解私聊/闪照界面的防截屏限制
    private fun hookScreenshotProtection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fragmentClass = lpparam.classLoader.loadClass(Config.TargetClasses.MSG_CHATTING_FRAGMENT)
            
            // 拦截 MsgChattingFragment 中负责控制截屏保护的方法 c(boolean)
            fragmentClass.findMethod {
                name == "c" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == Boolean::class.javaPrimitiveType
            }.hookBefore { param ->
                // 检查开关
                if (!Config.isFeatureEnabled("switch_screenshot")) return@hookBefore

                val isProtected = param.args[0] as? Boolean ?: return@hookBefore
                // 如果系统想开启保护 (true)，我们就强行改成 false
                if (isProtected) {
                    param.args[0] = false
                }
            }
        } catch (e: Exception) { 
            e.printStackTrace() 
        }
    }
}
