package bxxd.hook

import android.app.AndroidAppHelper
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

/**
 * 强制推送通知
 *
 * 背景：Blued 在手机上经常收不到推送，根因有三：
 *  1. [加固抽空] com.blued.android.framework.push.NotificationSender / NotificationModel
 *     的真实逻辑被爱加密(Ijiami)抽取式保护抽空(dex 里全是 nop)，由 native VMP 引擎运行时执行，
 *     本地通知链路本身可能异常/被搞坏。
 *  2. [厂商通道] 应用被杀后长连接断开，只能依赖厂商推送通道(华为HMS/小米MiPush/OPPO Heytap 等)，
 *     国内手机省电策略常导致通道被杀 → 收不到推送。
 *  3. [权限/通道] 用户可能在系统设置里关了 Blued 通知权限，或通知通道被设为静音。
 *
 * 方案：模块在 IM 消息接收入口 [com.blued.android.chat.core.worker.chat.Chat#receiveOrderMessage]
 *  (该方法是稳定的 Java 可读方法，AntiRecallHook 已验证可 hook) 处，由模块自己用独立的
 *  NotificationManager 通道强制发送本地通知 —— 完全绕开被加固抽空的 NotificationSender，
 *  也不依赖厂商推送通道。只要 Blued 长连接还活着(前台或后台未被杀)就能收到通知。
 *
 * 两个开关：
 *  - switch_force_push        : 强制推送总开关(私聊消息)
 *  - switch_force_push_group  : 同时推送群聊消息
 *  - switch_push_takeover     : (实验性)接管 Blued 原本的 NotificationSender，读取其 NotificationModel
 *                               字段后用模块自己的高优先级通道重新发送
 */
object ForcePushHook : BaseHook {

    private const val TAG = "llhook_push"
    private const val CHANNEL_ID = "llhook_force_push"
    private const val CHANNEL_NAME = "强制推送通知"
    // 宿主包名运行时动态获取(正式版/极速版), 不再写死

    private const val KEY_FORCE_PUSH = "switch_force_push"
    private const val KEY_FORCE_PUSH_GROUP = "switch_force_push_group"
    private const val KEY_PUSH_TAKEOVER = "switch_push_takeover"

    private const val NOTIF_TAG = "llhook_fp"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bluedClassLoader: ClassLoader? = null

    // msgId 去重，避免同一条消息因多次回调而重复推送
    private val notifiedMsgIds = Collections.synchronizedSet(LinkedHashSet<Long>())
    private var permissionWarned = false

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        bluedClassLoader = lpparam.classLoader
        hookForcePush(lpparam)
        hookTakeoverOriginal(lpparam)
    }

    // ============================================================
    // 核心：强制推送 —— 在消息接收入口处，模块自己发本地通知
    // ============================================================
    private fun hookForcePush(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pushMsgPackageClass = lpparam.classLoader.loadClass(Config.TargetClasses.PUSH_MSG_PACKAGE)
            val chatWorkerClass = lpparam.classLoader.loadClass(Config.TargetClasses.CHAT_WORKER)

            chatWorkerClass.findMethod {
                name == "receiveOrderMessage" &&
                    parameterTypes.size == 1 &&
                    parameterTypes[0] == pushMsgPackageClass
            }.hookAfter { param ->
                try {
                    if (!Config.isFeatureEnabled(KEY_FORCE_PUSH)) return@hookAfter

                    val pkg = param.args[0] ?: return@hookAfter

                    val msgType = XposedHelpers.getShortField(pkg, "msgType").toInt()
                    val msgId = XposedHelpers.getLongField(pkg, "msgId")
                    val sessionType = XposedHelpers.getShortField(pkg, "sessionType").toInt()
                    val fromId = XposedHelpers.getLongField(pkg, "fromId")
                    val fromName = XposedHelpers.getObjectField(pkg, "fromName") as? String ?: ""
                    val fromAvatar = XposedHelpers.getObjectField(pkg, "fromAvatar") as? String ?: ""
                    val msgContent = XposedHelpers.getObjectField(pkg, "msgContent") as? String ?: ""

                    // 跳过撤回指令(55)及各类无意义回执
                    if (msgType == 55) return@hookAfter

                    // 去重
                    synchronized(notifiedMsgIds) {
                        if (!notifiedMsgIds.add(msgId)) return@hookAfter
                        val it = notifiedMsgIds.iterator()
                        while (notifiedMsgIds.size > 300 && it.hasNext()) {
                            it.next(); it.remove()
                        }
                    }

                    // 排除自己发的消息(多端同步)
                    if (isMyMessage(fromId)) return@hookAfter

                    // 会话类型过滤：群聊(sessionType==2)走群开关，其余默认推送
                    val isGroup = sessionType == 2
                    if (isGroup && !Config.isFeatureEnabled(KEY_FORCE_PUSH_GROUP)) return@hookAfter

                    val title = if (isGroup) "[群] $fromName" else fromName.ifEmpty { "新消息" }
                    val content = describeMsg(msgType, msgContent)

                    postNotification(title, content, fromAvatar, msgId.toInt())
                } catch (e: Throwable) {
                    XposedBridge.log("[$TAG] force push error: $e")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] hookForcePush failed: $e")
        }
    }

    // ============================================================
    // 实验：接管 Blued 原本的通知发送 —— 读取 NotificationModel 字段后用模块通道重发
    // 注意：NotificationSender.b 被 Ijiami 抽空成 nop，真实逻辑在 native VMP 里，
    //       该 Java 桩不一定被调用，故效果有限，仅作为补充手段。
    // ============================================================
    private fun hookTakeoverOriginal(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val senderClass = lpparam.classLoader.loadClass("com.blued.android.framework.push.NotificationSender")
            val modelClass = lpparam.classLoader.loadClass("com.blued.android.framework.push.NotificationModel")

            // NotificationSender.b(NotificationModel) 是 Blued 发本地通知的核心方法
            senderClass.findMethod {
                name == "b" && parameterTypes.size == 1 && parameterTypes[0] == modelClass
            }.hookBefore { param ->
                if (!Config.isFeatureEnabled(KEY_PUSH_TAKEOVER)) return@hookBefore
                try {
                    val model = param.args[0] ?: return@hookBefore

                    // getter 被抽空，直接读字段(NotificationModel 的字段定义未被抽取)
                    val title = XposedHelpers.getObjectField(model, "contentTitle") as? String ?: ""
                    val content = XposedHelpers.getObjectField(model, "contentText") as? CharSequence ?: ""
                    val id = try { XposedHelpers.getIntField(model, "id") } catch (e: Throwable) { 0 }

                    // 拦截原方法，由模块接管
                    param.result = null

                    if (title.isEmpty() && content.isEmpty()) return@hookBefore
                    postNotification(title.ifEmpty { "新消息" }, content.toString(), null, id)
                } catch (e: Throwable) {
                    XposedBridge.log("[$TAG] takeover error: $e")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] hookTakeoverOriginal failed (正常, 不同版本方法名可能不同): $e")
        }
    }

    // ============================================================
    // 通知发送
    // ============================================================
    private fun postNotification(title: String, content: String, avatarUrl: String?, notifId: Int) {
        val context = AndroidAppHelper.currentApplication() ?: return
        ensureChannel(context)

        val nmc = NotificationManagerCompat.from(context)
        if (!areNotificationsEnabled(context)) {
            warnNoPermission(context)
            return
        }

        val safeId = notifId and 0x7fffffff

        // 运行时获取当前宿主包名(正式版/极速版均适用)
        val bluedPkg = try { AndroidAppHelper.currentPackageName() } catch (e: Throwable) { Config.currentBluedPackage }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(bluedPkg)
        val pi = launchIntent?.let {
            PendingIntent.getActivity(
                context, safeId, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
        if (pi != null) builder.setContentIntent(pi)

        // 先发一次(无头像)
        try {
            nmc.notify(NOTIF_TAG, safeId, builder.build())
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] notify failed: $e")
        }

        // 异步加载头像后更新通知
        if (!avatarUrl.isNullOrEmpty() && avatarUrl.startsWith("http")) {
            thread {
                try {
                    val bmp = loadBitmap(avatarUrl)
                    if (bmp != null) {
                        builder.setLargeIcon(bmp)
                        nmc.notify(NOTIF_TAG, safeId, builder.build())
                    }
                } catch (e: Throwable) {
                }
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "蓝蓝hook 强制推送的聊天消息通知(独立通道,不受 Blued 通知设置影响)"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }
            nm.createNotificationChannel(channel)
        } catch (e: Throwable) {
        }
    }

    private fun areNotificationsEnabled(context: Context): Boolean {
        return try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (e: Throwable) {
            true
        }
    }

    private fun warnNoPermission(context: Context) {
        if (permissionWarned) return
        permissionWarned = true
        mainHandler.post {
            try {
                Toast.makeText(
                    context,
                    "蓝蓝hook: Blued 通知权限未开启, 强制推送无法显示, 请到系统设置开启 Blued 通知权限",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Throwable) {
            }
        }
    }

    // 判断是否是自己发的消息(多端同步), 自己发的不推送
    private fun isMyMessage(fromId: Long): Boolean {
        if (fromId <= 0L) return false
        return try {
            val cl = bluedClassLoader ?: return false
            val userInfoClass = cl.loadClass("com.blued.android.module.common.user.model.UserInfo")
            val inst = XposedHelpers.callStaticMethod(userInfoClass, "getInstance") ?: return false
            val loginUser = XposedHelpers.callMethod(inst, "getLoginUserInfo") ?: return false
            val myUid = XposedHelpers.callMethod(loginUser, "getUid") as? String ?: return false
            myUid.trim() == fromId.toString()
        } catch (e: Throwable) {
            false
        }
    }

    // 消息类型 → 通知内容描述
    private fun describeMsg(msgType: Int, content: String?): String {
        val c = content?.trim()?.takeIf { it.isNotEmpty() }
        return when (msgType) {
            1, 0x1001 -> c ?: "[文本消息]"
            2 -> "[图片]"
            3 -> "[语音]"
            5 -> "[音频]"
            6 -> "[视频]"
            7 -> "[位置]"
            8 -> "[表情]"
            24, 25 -> "[闪照]"
            19 -> c ?: "[系统消息]"
            55 -> "[撤回]"
            else -> c ?: "[新消息]"
        }
    }

    private fun loadBitmap(url: String): Bitmap? {
        return try {
            val conn = URL(url).openConnection().apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            conn.getInputStream().use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            null
        }
    }
}
