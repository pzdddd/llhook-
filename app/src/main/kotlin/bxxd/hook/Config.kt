package bxxd.hook

import android.app.AndroidAppHelper
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.XSharedPreferences

object Config {
    private const val AUTHORITY = "content://com.app.hook.settings"
    private const val PREF_NAME = "llhook_settings"
    const val PACKAGE_NAME = "com.app.hook"   // 模块 APK 自身包名
    private const val LOCAL_PREFS = "llhook_blued_local_v2"

    // =====================================================
    // 兼容多宿主: 正式版与极速版
    //   - 正式版: com.soft.blued
    //   - 极速版: com.danlan.xiaolan
    // 两版内部 Java 类名完全一致(com.soft.blued.* / com.blued.android.*),
    // 仅 applicationId 不同, 故所有 hook 目标类名无需区分。
    // =====================================================
    val SUPPORTED_PACKAGES = setOf(
        "com.soft.blued",              // 正式版
        "com.danlan.xiaolan",          // 极速版
        "com.blued.international.lite",// 国际极速版
        "com.soft.blued.lite"          // 极速版(软蓝)
    )

    /** 运行时由 MainHook 注入时设置: 当前宿主(Blued)的实际包名 */
    @Volatile
    var currentBluedPackage: String = "com.soft.blued"
        private set

    /** 由 MainHook 在确认注入时调用, 记录当前宿主包名 */
    fun setCurrentBluedPackage(pkg: String) {
        if (pkg in SUPPORTED_PACKAGES) currentBluedPackage = pkg
    }

    /** 判断给定包名是否为受支持的 Blued 宿主 */
    fun isBluedPackage(pkg: String?): Boolean = pkg != null && pkg in SUPPORTED_PACKAGES

    object TargetClasses {
        const val MINE_NEW_FRAGMENT = "com.soft.blued.ui.mine.fragment.MineNewFragment"
        const val MINE_PAGE_MODEL = "com.soft.blued.ui.mine.model.MinePageModel"
        const val VISITOR_LIST_ADAPTER = "com.soft.blued.ui.find.adapter.VisitorListAdapter"
        const val VISITOR_LIST_RECYCLE_VIEW_ADAPTER = "com.soft.blued.ui.find.adapter.VisitorListRecycleViewAdapter"
        const val MSG_CHATTING_ADAPTER = "com.soft.blued.ui.msg.adapter.MsgChattingAdapter"
        const val MSG_CHATTING_PRESENT = "com.soft.blued.ui.msg.presenter.MsgChattingPresent"
        const val CHAT_HELPER_V4 = "com.soft.blued.ui.msg.controller.tools.ChatHelperV4"
        const val USER_INFO = "com.blued.android.module.common.user.model.UserInfo"
        const val LOGIN_RESULT = "com.blued.android.module.common.user.model.BluedLoginResult"
        const val PRIVATE_PHOTO = "com.soft.blued.ui.user.adapter.UserInfoPrivateAlbumAdapter"
        const val GRPC_METHOD_DESCRIPTOR = "io.grpc.MethodDescriptor"
        const val MSG_CHATTING_FRAGMENT = "com.soft.blued.ui.msg.MsgChattingFragment"
        const val PUSH_MSG_PACKAGE = "com.blued.android.chat.core.pack.PushMsgPackage"
        const val CHAT_WORKER = "com.blued.android.chat.core.worker.chat.Chat"
        const val CHAT_MANAGER = "com.blued.android.chat.ChatManager"
        const val USER_INFO_FRAGMENT_NEW = "com.soft.blued.ui.user.fragment.UserInfoFragmentNew"
    }

    private fun getContext(ctx: Context? = null): Context? {
        if (ctx != null) return ctx
        return try { AndroidAppHelper.currentApplication() } catch (e: Throwable) { null }
    }

    private fun getValue(key: String, defaultValue: String, ctx: Context? = null): String {
        val currentPkg = try { AndroidAppHelper.currentPackageName() } catch (e: Throwable) { "" }

        // ==========================================
        // 1. Blued 内部读取机制 (正式版 / 极速版 均适用)
        // ==========================================
        if (isBluedPackage(currentPkg)) {
            val app = AndroidAppHelper.currentApplication()
            val localPrefs = app?.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)

            // 优先读自己的本地存储，速度最快
            if (localPrefs != null && localPrefs.contains(key)) {
                return localPrefs.all[key]?.toString() ?: defaultValue
            }

            // 【兜底方案】：如果 Blued 是冷启动没收到广播，主动去敲外面的门拉取！
            val context = getContext(ctx)
            if (context != null) {
                try {
                    val cursor = context.contentResolver.query(Uri.parse("$AUTHORITY/$key"), null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val v = it.getString(0)
                            val editor = localPrefs?.edit()
                            when(v) {
                                "true" -> editor?.putBoolean(key, true)
                                "false" -> editor?.putBoolean(key, false)
                                else -> editor?.putString(key, v)
                            }
                            editor?.apply()
                            return v
                        }
                    }
                } catch (e: Throwable) {}
            }
            
            // XSharedPreferences 最后的挣扎
            try {
                val xPrefs = XSharedPreferences(PACKAGE_NAME, PREF_NAME)
                xPrefs.makeWorldReadable()
                xPrefs.reload()
                if (xPrefs.all.containsKey(key)) return xPrefs.all[key]?.toString() ?: defaultValue
            } catch (e: Throwable) {}

            return defaultValue
        }

        // ==========================================
        // 2. 模块主界面读取机制
        // ==========================================
        val context = getContext(ctx) ?: return defaultValue
        if (context.packageName == PACKAGE_NAME) {
            // 模块主界面使用 MODE_PRIVATE，避免 MODE_WORLD_READABLE 在 Android 7+ 崩溃
            // 跨进程读取由 SettingsProvider 的文件权限提权保证
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.all[key]?.toString() ?: defaultValue
        }

        return defaultValue
    }

    private fun setValue(key: String, value: String, ctx: Context? = null) {
        val context = getContext(ctx) ?: return
        val currentPkg = try { AndroidAppHelper.currentPackageName() } catch (e: Throwable) { "" }

        if (isBluedPackage(currentPkg)) {
            try {
                val app = AndroidAppHelper.currentApplication()
                val editor = app.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE).edit()
                when (value) {
                    "true" -> editor.putBoolean(key, true)
                    "false" -> editor.putBoolean(key, false)
                    else -> editor.putString(key, value)
                }
                editor.apply() 

                val pushIntent = android.content.Intent("bxxd.hook.SYNC_PUSH")
                pushIntent.setPackage(PACKAGE_NAME)
                pushIntent.putExtra("key", key)
                pushIntent.putExtra("value", value)
                app.sendBroadcast(pushIntent)
            } catch (e: Throwable) {}
            return
        }

        try {
            val cv = ContentValues().apply { put("value", value) }
            context.contentResolver.update(Uri.parse("$AUTHORITY/$key"), cv, null, null)
            
            // 🚀 【核心修复】主应用修改时，也立刻发广播通知 Blued：“我改数据了！”
            // 同时向所有已安装的宿主(正式版/极速版)广播, 保证双版本都能同步
            for (pkg in SUPPORTED_PACKAGES) {
                val pushIntent = android.content.Intent("bxxd.hook.MAIN_SYNC_PUSH")
                pushIntent.setPackage(pkg)
                pushIntent.putExtra("key", key)
                pushIntent.putExtra("value", value)
                try { context.sendBroadcast(pushIntent) } catch (_: Throwable) {}
            }
            
        } catch (e: Throwable) { e.printStackTrace() }
    }

    fun isFeatureEnabled(prefKey: String, ctx: Context? = null): Boolean = getValue(prefKey, "false", ctx).toBoolean()
    fun setFeatureEnabled(prefKey: String, enabled: Boolean, ctx: Context? = null) = setValue(prefKey, enabled.toString(), ctx)
    fun getCustomLat(ctx: Context? = null): Double = getValue("custom_lat", "39.9042", ctx).toDoubleOrNull() ?: 39.9042
    fun getCustomLng(ctx: Context? = null): Double = getValue("custom_lng", "116.4074", ctx).toDoubleOrNull() ?: 116.4074
    fun setCustomLocation(lat: Double, lng: Double, ctx: Context? = null) {
        setValue("custom_lat", lat.toString(), ctx)
        setValue("custom_lng", lng.toString(), ctx)
    }
    fun getApiKey(ctx: Context? = null): String = getValue("amap_api_key", "", ctx)
    fun setApiKey(key: String, ctx: Context? = null) = setValue("amap_api_key", key, ctx)
    fun getAuthToken(ctx: Context? = null): String = getValue("blued_auth_token", "", ctx)
    fun setAuthToken(token: String, ctx: Context? = null) = setValue("blued_auth_token", token, ctx)

    // 聊天备份自定义目录 (空=默认 Download/bluedbackups); 两进程通过 SharedPreferences+广播同步
    fun getBackupDir(ctx: Context? = null): String = getValue("backup_dir", "", ctx)
    fun setBackupDir(path: String, ctx: Context? = null) = setValue("backup_dir", path, ctx)

    // 通用字符串开关读写 (供 UI 持久化任意配置, 如 地图服务商 选择)
    fun getRaw(key: String, default: String, ctx: Context? = null): String = getValue(key, default, ctx)
    fun setRaw(key: String, value: String, ctx: Context? = null) = setValue(key, value, ctx)
}
