package bxxd.hook

import android.app.AndroidAppHelper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 保活心跳 hook（注入到 com.soft.blued 主进程）
 *
 * 作用：Blued 主进程存活时，每 45 秒向模块 APK 进程发送一次心跳广播
 *      (bxxd.hook.BLUED_ALIVE)。模块侧的 [KeepAliveReceiver] 收到后记录存活时间，
 *      [KeepAliveService] 据此判断 Blued 是否还活着；心跳超时即认为 Blued 被杀/断连，
 *      进而发通知提醒或自动拉起。
 *
 * 只在主进程发心跳：Blued 是多进程(com.soft.blued + 多个 :lark_* 子进程)，
 *   IM 长连接在主进程，判断主进程存活即可。
 *
 * 心跳本身是极轻量的定向广播(setPackage)，仅在开关打开时发送，几乎不耗电。
 */
object KeepAliveHook : BaseHook {

    private const val ACTION_ALIVE = "bxxd.hook.BLUED_ALIVE"
    private const val MODULE_PKG = Config.PACKAGE_NAME
    private const val HEART_INTERVAL_MS = 45_000L
    private const val FIRST_DELAY_MS = 20_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var started = false

    private val heartBeat = object : Runnable {
        override fun run() {
            try {
                if (Config.isFeatureEnabled("switch_keep_alive")) {
                    val ctx = AndroidAppHelper.currentApplication()
                    if (ctx != null) {
                        val i = Intent(ACTION_ALIVE).setPackage(MODULE_PKG)
                        ctx.sendBroadcast(i)
                    }
                }
            } catch (e: Throwable) {
            }
            handler.postDelayed(this, HEART_INTERVAL_MS)
        }
    }

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只在 Blued 主进程启动心跳（:lark_* 子进程不发，避免冗余）
        try {
            val processName = AndroidAppHelper.currentProcessName()
            if (processName == null || processName.contains(":")) return
        } catch (e: Throwable) {
            return
        }

        // 借 Blued 首页 Activity onCreate 这个稳定时机启动心跳循环
        try {
            val homeClass = lpparam.classLoader.loadClass("com.soft.blued.ui.home.HomeActivity")
            homeClass.findMethod { name == "onCreate" }.hookAfter {
                if (started) return@hookAfter
                started = true
                handler.postDelayed(heartBeat, FIRST_DELAY_MS)
            }
        } catch (e: Throwable) {
            // 兜底：直接启动
            if (!started) {
                started = true
                handler.postDelayed(heartBeat, FIRST_DELAY_MS)
            }
        }
    }
}
