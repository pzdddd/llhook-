package bxxd.hook

import android.app.AndroidAppHelper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject

object AntiRecallHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. 底层数据库级防撤回 (改为 JSON 永久烙印防撤回，彻底干掉烦人的 Toast)
        hookDatabaseRecall(lpparam)
        
        // 2. 🚀 闪照完美破解 (纯净解密版)
        hookFlashPhotoBypass(lpparam)
        
        // 3. UI 提示文字注入 (支持闪照提示 + 防撤回永久提示)
        hookChattingUI(lpparam)
    }

    // =====================================
    // 核心：底层数据库级防撤回 (静默无弹窗版)
    // =====================================
    private fun hookDatabaseRecall(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pushMsgPackageClass = lpparam.classLoader.loadClass(Config.TargetClasses.PUSH_MSG_PACKAGE)
            val chatWorkerClass = lpparam.classLoader.loadClass(Config.TargetClasses.CHAT_WORKER)
            val chatManagerClass = lpparam.classLoader.loadClass(Config.TargetClasses.CHAT_MANAGER)

            chatWorkerClass.findMethod {
                name == "receiveOrderMessage" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == pushMsgPackageClass
            }.hookBefore { param ->
                if (!Config.isFeatureEnabled("switch_anti_recall")) return@hookBefore

                val pushMsgPackage = param.args[0] ?: return@hookBefore
                try {
                    val msgType = XposedHelpers.getShortField(pushMsgPackage, "msgType").toInt()
                    
                    if (msgType == 55) { // 撤回指令
                        val msgId = XposedHelpers.getLongField(pushMsgPackage, "msgId")
                        val sessionType = XposedHelpers.getShortField(pushMsgPackage, "sessionType")
                        val sessionId = XposedHelpers.getLongField(pushMsgPackage, "sessionId")

                        val dbOperImpl = XposedHelpers.getStaticObjectField(chatManagerClass, "dbOperImpl")
                        val originalMsg = XposedHelpers.callMethod(dbOperImpl, "findMsgData", sessionType, sessionId, msgId, 0L)
                        
                        if (originalMsg != null) {
                            val originalType = XposedHelpers.getShortField(originalMsg, "msgType")

                            if (originalType.toInt() == 55) return@hookBefore

                            // 1. 恢复消息为撤回前的原始类型
                            XposedHelpers.setShortField(pushMsgPackage, "msgType", originalType)
                            XposedHelpers.setShortField(originalMsg, "msgType", originalType)
                            
                            // 2. 💥 黑科技：在底层的 msgExtra 里打上永久的“防撤回烙印”存入数据库
                            try {
                                val extraStr = XposedHelpers.getObjectField(originalMsg, "msgExtra") as? String ?: "{}"
                                val json = if (extraStr.trim().startsWith("{")) JSONObject(extraStr) else JSONObject()
                                json.put("llhook_anti_recall", true)
                                XposedHelpers.setObjectField(originalMsg, "msgExtra", json.toString())
                            } catch (e: Throwable) {}

                            // 3. 将带有烙印的消息更新回数据库
                            XposedHelpers.callMethod(dbOperImpl, "updateChattingModel", originalMsg)

                            // 彻底拦截原来的方法，废除那烦死人的 Toast 弹窗
                            param.result = null
                        }
                    }
                } catch (e: Throwable) { e.printStackTrace() }
            }
        } catch (e: Throwable) { e.printStackTrace() }
    }

    // =====================================
    // 🚀 终极闪照拦截引擎
    // =====================================
    private fun hookFlashPhotoBypass(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val presentClass = lpparam.classLoader.loadClass(Config.TargetClasses.MSG_CHATTING_PRESENT)
            val chatHelperClass = lpparam.classLoader.loadClass("com.soft.blued.ui.msg.controller.tools.ChatHelperV4")

            presentClass.findMethod { name == "onMsgDataChanged" && parameterTypes.size == 1 && parameterTypes[0] == java.util.List::class.java }
                .hookBefore { param ->
                    if (!Config.isFeatureEnabled("switch_flash_photo")) return@hookBefore
                    
                    val list = param.args[0] as? List<*> ?: return@hookBefore
                    
                    list.forEach { msgObj ->
                        if (msgObj == null) return@forEach
                        
                        val isFromSelf = try { XposedHelpers.callMethod(msgObj, "isFromSelf") as Boolean } catch(e: Throwable){ false }
                        if (isFromSelf) return@forEach

                        val msgTypeField = XposedHelpers.findField(msgObj.javaClass, "msgType")
                        val msgType = (msgTypeField.get(msgObj) as? Number)?.toInt() ?: -1

                        if (msgType == 24 || msgType == 25) {
                            var decodedContent = ""
                            
                            var helperInstance: Any? = null
                            for (m in chatHelperClass.declaredMethods) {
                                if (java.lang.reflect.Modifier.isStatic(m.modifiers) && m.returnType == chatHelperClass && m.parameterTypes.isEmpty()) {
                                    helperInstance = m.invoke(null)
                                    break
                                }
                            }

                            if (helperInstance != null) {
                                val decodeMethodName = if (msgType == 25) "b" else "a"
                                val decodeMethod = chatHelperClass.declaredMethods.find { 
                                    it.name == decodeMethodName && it.parameterTypes.size == 1 && it.parameterTypes[0] == msgObj.javaClass && it.returnType == String::class.java 
                                }
                                if (decodeMethod != null) {
                                    decodeMethod.isAccessible = true
                                    decodedContent = decodeMethod.invoke(helperInstance, msgObj) as? String ?: ""
                                }
                            }

                            if (decodedContent.isEmpty()) {
                                val methodsToTry = chatHelperClass.declaredMethods.filter { 
                                    it.parameterTypes.size == 1 && it.parameterTypes[0] == msgObj.javaClass && it.returnType == String::class.java 
                                }
                                for (m in methodsToTry) {
                                    m.isAccessible = true
                                    val res = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                                        m.invoke(null, msgObj) as? String
                                    } else if (helperInstance != null) {
                                        m.invoke(helperInstance, msgObj) as? String
                                    } else null
                                    
                                    if (!res.isNullOrEmpty() && (res.startsWith("http") || res.startsWith("file") || res.startsWith("/"))) {
                                        decodedContent = res
                                        break
                                    }
                                }
                            }

                            if (decodedContent.isNotEmpty()) {
                                XposedHelpers.setObjectField(msgObj, "msgContent", decodedContent)

                                val newType = if (msgType == 25) 5 else 2
                                if (msgTypeField.type.name == "short") {
                                    XposedHelpers.setShortField(msgObj, "msgType", newType.toShort())
                                } else {
                                    XposedHelpers.setIntField(msgObj, "msgType", newType)
                                }
                                XposedHelpers.setAdditionalInstanceField(msgObj, "oldMsgType", msgType.toShort())

                                XposedHelpers.setAdditionalInstanceField(msgObj, "fuck_blued_notify", if(msgType==25) "已拦截闪拍" else "已拦截闪照")
                            }
                        }
                    }
                }

            val flashNumberModelClass = lpparam.classLoader.loadClass("com.soft.blued.ui.msg.model.FlashNumberModel")
            val flashPhotoManagerClass = lpparam.classLoader.loadClass("com.soft.blued.ui.msg.manager.FlashPhotoManager")

            flashPhotoManagerClass.declaredMethods.filter { 
                it.returnType == flashNumberModelClass 
            }.forEach { method ->
                method.hookAfter { param ->
                    if (!Config.isFeatureEnabled("switch_flash_photo")) return@hookAfter
                    
                    val result = param.result ?: return@hookAfter
                    try {
                        XposedHelpers.setObjectField(result, "flash_left_times", 99)
                        XposedHelpers.setObjectField(result, "stimulate_flash", 0)
                        XposedHelpers.setObjectField(result, "is_vip", 1)
                        XposedHelpers.setObjectField(result, "flash_prompt", "")
                    } catch (ex: Throwable) {}
                }
            }
        } catch (e: Throwable) { e.printStackTrace() }
    }

    // =====================================
    // UI 文字提示注入 (包含闪照拦截和防撤回)
    // =====================================
    private fun hookChattingUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val adapterClass = lpparam.classLoader.loadClass(Config.TargetClasses.MSG_CHATTING_ADAPTER)
            adapterClass.findMethod { 
                name == "a" && parameterTypes.size == 3 && parameterTypes[0] == Int::class.javaPrimitiveType && parameterTypes[1] == View::class.java && parameterTypes[2] == ViewGroup::class.java 
            }.hookAfter { param ->
                try {
                    val index = param.args[0] as Int
                    val listA = XposedHelpers.getObjectField(param.thisObject, "a") as? List<*> ?: return@hookAfter
                    if (index < 0 || index >= listA.size) return@hookAfter
                    val chattingModel = listA[index] ?: return@hookAfter

                    val viewGroup = param.result as? ViewGroup ?: return@hookAfter
                    val existingView = viewGroup.findViewWithTag<View>(1)

                    // 1. 获取可能存在的闪照提示标记
                    var finalTip = XposedHelpers.getAdditionalInstanceField(chattingModel, "fuck_blued_notify") as? String
                    
                    // 2. 检查底层的 msgExtra 是否有我们在数据库里打上的防撤回永久烙印
                    try {
                        val extraStr = XposedHelpers.getObjectField(chattingModel, "msgExtra") as? String
                        if (!extraStr.isNullOrEmpty() && extraStr.contains("llhook_anti_recall")) {
                            val json = JSONObject(extraStr)
                            if (json.optBoolean("llhook_anti_recall", false)) {
                                finalTip = if (finalTip == null) "已拦截撤回" else "$finalTip | 已拦截撤回"
                            }
                        }
                    } catch (e: Throwable) {}

                    // 3. 渲染到 UI
                    if (finalTip != null) {
                        if (existingView == null) {
                            val tv = TextView(viewGroup.context).apply {
                                tag = 1
                                textSize = 12f
                                text = finalTip
                                setTextColor(Color.parseColor("#ADAFB0"))
                                gravity = Gravity.CENTER
                                setPadding(20, 0, 20, 10)
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            }
                            viewGroup.addView(tv)
                        } else {
                            (existingView as TextView).text = finalTip
                        }
                    } else {
                        // 彻底防复用池串台：没有任何提示的正常消息，一定要把别人遗留的提示框拆掉！
                        existingView?.let { viewGroup.removeView(it) }
                    }
                } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {}
    }
}
