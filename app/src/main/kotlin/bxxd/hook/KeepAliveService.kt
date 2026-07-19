package bxxd.hook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 蓝蓝hook 保活前台服务（运行在模块 APK 自己的进程，独立于 Blued）
 *
 * 设计要点：
 *  - Blued 进程被系统杀掉时，本服务进程不受影响（它是独立 APK 进程）。
 *  - 配合 [KeepAliveReceiver] 的 AlarmManager 定时唤醒，形成：
 *      Boot / AlarmTick → Receiver → startForegroundService(本服务) → START_STICKY
 *    即使本服务和模块进程被杀，系统 Alarm（系统级，独立于任何 App）也会按时唤醒 Receiver
 *    重新拉起本服务。这是 Android 上不依赖厂商推送通道、能跨进程死亡存活的保活链。
 *  - 本服务每次被唤起时执行 [checkBlued]：若 Blued 长时间没发心跳(BLUED_ALIVE)，
 *    判定 Blued 已断开 → 发通知提醒 / (可选)自动拉起 Blued。
 *
 * 注意：本服务解决的是「让 Blued 进程尽量别死、死了能被重新拉起」，从而使
 *       ForcePushHook 的实时长连接推送持续生效，绕开厂商离线 push 的延迟。
 *       它本身不能改善厂商离线 push 的延迟（那是云→端路径，不经 App/hook）。
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "llhook_keepalive"
        const val NOTIF_ID = 0x77
        const val NOTIF_DISCONNECT_ID = 0x78
        const val KEY_LAST_ALIVE = "blued_last_alive"

        // Blued 心跳超过此阈值(毫秒)视为已断开。心跳间隔 45s，给 3 倍容错。
        const val ALIVE_TIMEOUT_MS = 3 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat(buildForegroundNotification("蓝蓝hook 保活运行中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户关闭了开关 → 主动停止
        if (!Config.isFeatureEnabled("switch_keep_alive", this)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 每次被唤醒/tick 时检查 Blued 存活状态
        checkBlued()
        return START_STICKY
    }

    private fun checkBlued() {
        try {
            val prefs = getSharedPreferences("llhook_settings", Context.MODE_PRIVATE)
            val lastAlive = prefs.getLong(KEY_LAST_ALIVE, 0L)
            val now = System.currentTimeMillis()
            val bluedAlive = lastAlive > 0 && (now - lastAlive) < ALIVE_TIMEOUT_MS

            if (!bluedAlive) {
                val autoRelaunch = Config.isFeatureEnabled("switch_keep_alive_relaunch", this)
                if (autoRelaunch) {
                    relaunchBlued()
                } else {
                    notifyDisconnected()
                }
            }
        } catch (e: Throwable) {
        }
    }

    private fun relaunchBlued() {
        try {
            val bluedPkg = detectBluedPackage() ?: return
            val launch = packageManager.getLaunchIntentForPackage(bluedPkg) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } catch (e: Throwable) {
        }
    }

    private fun notifyDisconnected() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val bluedPkg = detectBluedPackage()
            val launch = bluedPkg?.let { packageManager.getLaunchIntentForPackage(it) }
            val pi = launch?.let {
                android.app.PendingIntent.getActivity(
                    this, 1, it,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Blued 可能已断开")
                .setContentText("实时推送已中断，点击重新打开 Blued")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .also { if (pi != null) it.setContentIntent(pi) }
                .build()
            nm.notify(NOTIF_DISCONNECT_ID, n)
        } catch (e: Throwable) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "进程保活", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "蓝蓝hook 进程保活服务（维持实时推送）"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        } catch (e: Throwable) {
        }
    }

    private fun buildForegroundNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("蓝蓝hook")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            // Android 14 (API 34) 起必须显式声明 foregroundServiceType
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Throwable) {
            // 部分厂商 ROM 可能拒绝前台服务启动，兜底
            try { startForeground(NOTIF_ID, notification) } catch (_: Throwable) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // 检测当前已安装的 Blued 宿主(优先正式版, 其次极速版)
    private fun detectBluedPackage(): String? {
        for (pkg in Config.SUPPORTED_PACKAGES) {
            try {
                packageManager.getLaunchIntentForPackage(pkg) ?: continue
                return pkg
            } catch (e: Throwable) {}
        }
        return null
    }
}
