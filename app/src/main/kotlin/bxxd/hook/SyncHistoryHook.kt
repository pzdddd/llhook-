package bxxd.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 手动触发 Blued IM 全量同步聊天记录。
 *
 * 原理: 调用 Blued 原生 IM 同步入口 IM.a(SyncRequest, OnSyncFinishListener),
 * 构造 SyncType.SyncAll 全量同步请求。Blued 内部会从服务器拉取所有历史消息
 * 并存入本地 ChatDBImpl, 等于「重新拉一遍全部聊天记录」。
 *
 * 反编译依据 (7.32.0):
 *   - com.blued.android.module.im.IM.a(SyncOuterClass$SyncRequest, biz.sync.Sync$OnSyncFinishListener) static
 *   - com.blued.im.sync.SyncOuterClass$SyncRequest$Builder: setSyncType(SyncType) / setLocalId(int) / build()
 *   - com.blued.im.sync.SyncOuterClass$SyncType.SyncAll = 全量
 *   - com.blued.android.module.im.biz.sync.Sync$OnSyncFinishListener.onFinish(SyncResponse)
 *   - SyncResponse: getErrorValue()/getMessagesList()/getContinue()
 */
object SyncHistoryHook {

    private const val TAG = "【蓝蓝hook】"

    /** 手动触发全量同步 (必须手动点击调用)。返回是否成功提交请求。 */
    fun triggerSync(ctx: Context): Boolean {
        val cl = ctx.classLoader
        return try {
            val syncTypeCls = cl.loadClass("com.blued.im.sync.SyncOuterClass\$SyncType")
            val syncReqCls = cl.loadClass("com.blued.im.sync.SyncOuterClass\$SyncRequest")
            val builderCls = cl.loadClass("com.blued.im.sync.SyncOuterClass\$SyncRequest\$Builder")
            val listenerCls = cl.loadClass("com.blued.android.module.im.biz.sync.Sync\$OnSyncFinishListener")
            val imCls = cl.loadClass("com.blued.android.module.im.IM")

            // 1. 构造 SyncRequest: setSyncType(SyncAll) + setLocalId(时间戳低位)
            val syncAll = XposedHelpers.getStaticObjectField(syncTypeCls, "SyncAll")
            val builder = syncReqCls.getMethod("newBuilder").invoke(null)!!
            builderCls.getMethod("setSyncType", syncTypeCls).invoke(builder, syncAll)
            builderCls.getMethod("setLocalId", Int::class.javaPrimitiveType)
                .invoke(builder, (System.currentTimeMillis() and 0x7FFFFFFF).toInt())
            val request = builderCls.getMethod("build").invoke(builder)!!

            // 2. 创建 OnSyncFinishListener 动态代理
            val listener = Proxy.newProxyInstance(cl, arrayOf(listenerCls)) { _, method, args ->
                if (method.name == "onFinish" && args != null && args.isNotEmpty()) {
                    handleResponse(ctx, args[0])
                }
                null
            }

            // 3. 调用 IM.a(SyncRequest, OnSyncFinishListener) — 静态方法
            imCls.getMethod("a", syncReqCls, listenerCls).invoke(null, request, listener)

            toast(ctx, "⏳ 开始全量同步聊天记录…")
            XposedBridge.log("$TAG【历史同步】已提交 SyncAll 请求")
            true
        } catch (e: Throwable) {
            toast(ctx, "同步失败: ${e.message}")
            XposedBridge.log("$TAG【历史同步】失败: $e")
            false
        }
    }

    /** 处理同步回调: 读 SyncResponse 的错误码/消息数/是否继续, Toast 反馈。 */
    private fun handleResponse(ctx: Context, response: Any?) {
        try {
            if (response == null) { toast(ctx, "同步响应为空"); return }
            val errorValue = try { XposedHelpers.callMethod(response, "getErrorValue") as? Int ?: 0 } catch (_: Throwable) { 0 }
            if (errorValue != 0) {
                toast(ctx, "同步出错 (错误码 $errorValue)")
                return
            }
            val msgCount = try {
                (XposedHelpers.callMethod(response, "getMessagesList") as? List<*>)?.size ?: 0
            } catch (_: Throwable) { 0 }
            val cont = try { XposedHelpers.callMethod(response, "getContinue") as? Boolean ?: false }
            catch (_: Throwable) { false }
            toast(ctx, if (cont) "🔄 同步进行中: 已收到 $msgCount 条" else "✅ 同步完成: 收到 $msgCount 条消息")
        } catch (_: Throwable) {
            toast(ctx, "同步完成")
        }
    }

    private fun toast(ctx: Context, msg: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        } catch (_: Throwable) {}
    }
}
