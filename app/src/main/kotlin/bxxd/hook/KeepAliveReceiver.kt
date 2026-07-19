package bxxd.hook

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * 保活链的唤醒枢纽（运行在模块 APK 进程）
 *
 * 三类触发：
 *  1. ACTION_BOOT_COMPLETED          — 开机自启（若开关打开）
 *  2. bxxd.hook.KEEPALIVE_TICK       — AlarmManager 定时唤醒（每 ~15 分钟）
 *  3. bxxd.hook.KEEPALIVE_START/STOP — 来自 MainActivity 开关切换
 *  4. bxxd.hook.BLUED_ALIVE          — 来自 KeepAliveHook(注入在 Blued 内) 的心跳，记录存活时间
 *
 * 关键：AlarmManager 由系统进程持有，即使模块进程和 Blued 进程都被杀，
 *      系统仍会按时唤醒本 Receiver → 再拉起 [KeepAliveService]。这是保活链的核心。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TICK = "bxxd.hook.KEEPALIVE_TICK"
        const val ACTION_START = "bxxd.hook.KEEPALIVE_START"
        const val ACTION_STOP = "bxxd.hook.KEEPALIVE_STOP"
        const val ACTION_BLUED_ALIVE = "bxxd.hook.BLUED_ALIVE"

        private const val ALARM_REQUEST_CODE = 7
        private val TICK_INTERVAL = AlarmManager.INTERVAL_FIFTEEN_MINUTES // 系统 Doze 下实际最长约 1 小时
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_TICK,
            ACTION_START -> {
                if (Config.isFeatureEnabled("switch_keep_alive", context)) {
                    scheduleNextTick(context)
                    startKeepService(context)
                } else {
                    cancelTick(context)
                    stopKeepService(context)
                }
            }
            ACTION_STOP -> {
                cancelTick(context)
                stopKeepService(context)
            }
            ACTION_BLUED_ALIVE -> {
                // Blued 进程心跳：记录最后存活时间
                try {
                    context.getSharedPreferences("llhook_settings", Context.MODE_PRIVATE)
                        .edit()
                        .putLong(KeepAliveService.KEY_LAST_ALIVE, System.currentTimeMillis())
                        .apply()
                } catch (e: Throwable) {}
            }
        }
    }

    private fun startKeepService(context: Context) {
        try {
            val i = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        } catch (e: Throwable) {
            // Android 12+ 后台启动前台服务在某些场景受限，失败则靠下一次 tick 重试
        }
    }

    private fun stopKeepService(context: Context) {
        try { context.stopService(Intent(context, KeepAliveService::class.java)) } catch (e: Throwable) {}
    }

    // 用 setAndAllowWhileIdle：Doze 下仍会触发，且不需要 SCHEDULE_EXACT_ALARM 权限
    // 每次 tick 到达后由本 Receiver 再排下一次，形成自循环
    private fun scheduleNextTick(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val i = Intent(ACTION_TICK).setPackage(Config.PACKAGE_NAME)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, i, flags)
            val triggerAt = SystemClock.elapsedRealtime() + TICK_INTERVAL
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } catch (e: Throwable) {}
    }

    private fun cancelTick(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val i = Intent(ACTION_TICK).setPackage(Config.PACKAGE_NAME)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, i, flags)
            am.cancel(pi)
        } catch (e: Throwable) {}
    }
}
