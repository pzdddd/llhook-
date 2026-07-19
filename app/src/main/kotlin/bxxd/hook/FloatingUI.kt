package bxxd.hook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import kotlin.system.exitProcess

/**
 * 宿主侧 (Blued 进程) 的「设置同步基础设施」。
 *
 * 历史: 原本这里还包含一整套传统 Android View 的悬浮球 + 各类 AlertDialog (设备检测 / 净化 /
 * 备份 / 地图 / 站街)。合并到 llhook 后, 所有 UI 已统一收口到 llhook 的 Compose 玻璃拟态风格
 * (com.example.ui.*), 本 object 仅保留**纯逻辑**职责:
 *
 *   1. HomeActivity.onResume 时, 从离线文件拉取最新虚拟定位写入 Blued 本地;
 *   2. 注册跨进程广播接收器, 接收模块进程 (com.app.hook) 推来的开关变更, 写入 Blued 本地
 *      SharedPreferences (llhook_blued_local_v2), 供各 hook 模块读取。
 *
 * UI 入口 (悬浮球 / 工具菜单) 由 com.example.hook.MainHook 注入的 Compose 悬浮按钮接管,
 * 不再在此注入任何 View。
 */
object FloatingUI : BaseHook {

    private var isReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    var hostClassLoader: ClassLoader? = null

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hostClassLoader = lpparam.classLoader
        try {
            val homeActivityClass = lpparam.classLoader.loadClass("com.soft.blued.ui.home.HomeActivity")
            homeActivityClass.findMethod { name == "onResume" }.hookAfter { param ->
                val activity = param.thisObject as Activity

                // 1) 离线坐标同步: 拉取模块侧最新虚拟定位, 写入 Blued 本地
                try {
                    val data = MapHelper.pullOfflineData()
                    if (data != null) {
                        val fileTs = data.optLong("ts", 0L)
                        val localPrefs = activity.getSharedPreferences("llhook_blued_local_v2", Context.MODE_PRIVATE)
                        val localTs = localPrefs.getLong("master_timestamp", 0L)
                        if (fileTs > localTs) {
                            Config.setCustomLocation(data.optDouble("lat"), data.optDouble("lng"), activity)
                            localPrefs.edit().putLong("master_timestamp", fileTs).apply()
                        }
                    }
                } catch (e: Throwable) {}

                // 2) 跨进程设置同步接收器 (模块进程 → Blued 进程)
                if (!isReceiverRegistered) {
                    isReceiverRegistered = true
                    try {
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                when (intent.action) {
                                    "bxxd.hook.PULL_REQUEST" -> {
                                        // 模块侧主动拉取 Blued 本地全部设置 (首次连接兜底)
                                        val localPrefs = context.getSharedPreferences("llhook_blued_local_v2", Context.MODE_PRIVATE)
                                        val json = JSONObject()
                                        localPrefs.all.forEach { (k, v) -> json.put(k, v) }
                                        val reply = Intent("bxxd.hook.PULL_RESPONSE")
                                        reply.setPackage(Config.PACKAGE_NAME)
                                        reply.putExtra("data", json.toString())
                                        context.sendBroadcast(reply)
                                    }
                                    "bxxd.hook.MAIN_SYNC_PUSH" -> {
                                        // 模块侧推来单个开关变更, 写入 Blued 本地
                                        val key = intent.getStringExtra("key") ?: return
                                        val valueStr = intent.getStringExtra("value") ?: return
                                        val editor = context.getSharedPreferences("llhook_blued_local_v2", Context.MODE_PRIVATE).edit()
                                        when (valueStr) {
                                            "true" -> editor.putBoolean(key, true)
                                            "false" -> editor.putBoolean(key, false)
                                            else -> editor.putString(key, valueStr)
                                        }
                                        editor.apply()
                                    }
                                }
                            }
                        }
                        val filter = IntentFilter().apply {
                            addAction("bxxd.hook.PULL_REQUEST")
                            addAction("bxxd.hook.MAIN_SYNC_PUSH")
                        }
                        if (Build.VERSION.SDK_INT >= 33) {
                            activity.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            activity.applicationContext.registerReceiver(receiver, filter)
                        }
                    } catch (e: Throwable) {}
                }
                // 悬浮球入口已由 com.example.hook.MainHook 的 Compose 悬浮按钮接管, 此处不注入任何 View。
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 重启宿主 App (供 Compose UI 的「重启生效」按钮调用)。 */
    fun restartHostApp(activity: Activity) {
        try {
            val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(0)
            }
        } catch (e: Exception) {}
    }
}
