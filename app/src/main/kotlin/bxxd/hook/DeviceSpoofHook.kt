package bxxd.hook

import android.util.Log
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.UUID

object DeviceSpoofHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // ==========================================
            // 武器库 1 & 2：读取拦截 (数美底层 & OAID)
            // ==========================================
            
            // 拦截数美底层
            try {
                val smAntiFraudClass = lpparam.classLoader.loadClass("com.ishumei.smantifraud.SmAntiFraud")
                smAntiFraudClass.findMethod { 
                    name == "getDeviceId" 
                }.hookAfter { param ->
                    if (Config.isFeatureEnabled("switch_device_empty")) {
                        // 策略 A：暴力置空
                        param.result = ""
                        Log.d("BluedHook", "🛡️ 数美 DeviceId -> 已强制置空")
                    } else if (Config.isFeatureEnabled("switch_device_fake")) {
                        // 策略 B：高级伪装
                        val originalId = param.result as? String
                        if (!originalId.isNullOrEmpty() && originalId.length > 10) {
                            val fakeTail = UUID.randomUUID().toString().replace("-", "").take(8)
                            val fakeId = originalId.substring(0, originalId.length - 8) + fakeTail
                            param.result = fakeId
                            Log.d("BluedHook", "🎭 数美 DeviceId -> 成功伪装: $fakeId")
                        }
                    }
                }
            } catch (e: Throwable) {}

            // 拦截 Blued 内部 OAID 读取
            try {
                val deviceIdentityClass = lpparam.classLoader.loadClass("com.blued.android.module.device_identity.library.BluedDeviceIdentity")
                deviceIdentityClass.findMethod {
                    name == "k" && parameterTypes.isEmpty()
                }.hookAfter { param ->
                    if (Config.isFeatureEnabled("switch_device_empty")) {
                        // 策略 A：暴力置空
                        param.result = ""
                        Log.d("BluedHook", "🛡️ SM_OAID -> 已强制置空")
                    } else if (Config.isFeatureEnabled("switch_device_fake")) {
                        // 策略 B：高级伪装
                        val originalOaid = param.result as? String
                        if (!originalOaid.isNullOrEmpty() && originalOaid.length > 8) {
                            val fakeTail = UUID.randomUUID().toString().replace("-", "").take(6)
                            val fakeOaid = originalOaid.substring(0, originalOaid.length - 6) + fakeTail
                            param.result = fakeOaid
                            Log.d("BluedHook", "🎭 SM_OAID -> 成功伪装: $fakeOaid")
                        } else {
                            param.result = UUID.randomUUID().toString().replace("-", "").take(16) + "0000000000000000"
                        }
                    }
                }

                // ==========================================
                // 武器库 3：拦截上传请求
                // ==========================================
                deviceIdentityClass.findMethod {
                    name == "a" && parameterTypes.size == 2 && 
                    parameterTypes[0] == String::class.java && 
                    parameterTypes[1] == String::class.java
                }.hookBefore { param ->
                    if (Config.isFeatureEnabled("switch_device_intercept")) {
                        // 直接掐断上传网线
                        param.result = null 
                        Log.d("BluedHook", "⛔ 已强行阻断设备码上传！拦截类型: ${param.args[0]}, ID: ${param.args[1]}")
                    }
                }
            } catch (e: Throwable) {}

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
