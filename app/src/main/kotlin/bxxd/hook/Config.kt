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

    /**
     * 确定当前进程包名: 优先用传入 ctx (Compose 面板的主线程回调里 AndroidAppHelper
     * 不稳定), 兜底用 AndroidAppHelper。
     * 这是修复「面板保存后 hook 读不到 / 还原默认」的根本措施。
     */
    private fun currentPackage(ctx: Context?): String {
        if (ctx != null) {
            val pn = try { ctx.packageName } catch (_: Throwable) { null }
            if (pn != null && (isBluedPackage(pn) || pn == PACKAGE_NAME)) return pn
            // ctx.applicationContext 更可靠
            val apn = try { ctx.applicationContext.packageName } catch (_: Throwable) { null }
            if (apn != null && (isBluedPackage(apn) || apn == PACKAGE_NAME)) return apn
        }
        val helperPkg = try { AndroidAppHelper.currentPackageName() } catch (_: Throwable) { "" }
        if (helperPkg.isNotEmpty() && (isBluedPackage(helperPkg) || helperPkg == PACKAGE_NAME)) return helperPkg
        // 【最后兑底】Compose 主线程回调里 AndroidAppHelper 可能返回空串,
        // 用反射调 ActivityThread.currentPackageName() 判定 (本进程 = 宿主进程 = Blued)。
        return try {
            val atCls = Class.forName("android.app.ActivityThread")
            val pn = atCls.getMethod("currentPackageName").invoke(null) as? String
            if (pn != null && (isBluedPackage(pn) || pn == PACKAGE_NAME)) pn else ""
        } catch (_: Throwable) { "" }
    }

    /** 获取可靠的 Application Context (优先 ctx, 兜底 AndroidAppHelper)。 */
    private fun currentApp(ctx: Context?): android.app.Application? {
        // ★ 优先用 AndroidAppHelper.currentApplication() —— 返回「当前进程」的 Application,
        //   是判进程最可靠的方式 (ctx 可能是 ContextWrapper/主题包装, ctx.packageName 不稳定)。
        //   本代码要么跑在 Blued 进程(返回 Blued App), 要么模块进程(返回模块 App)。
        try { AndroidAppHelper.currentApplication()?.let { return it } } catch (_: Throwable) {}
        if (ctx != null) {
            return ctx.applicationContext as? android.app.Application
        }
        return null
    }

    private fun getValue(key: String, defaultValue: String, ctx: Context? = null): String {
        val currentPkg = currentPackage(ctx)
        android.util.Log.d("LlhookConfig", "GET pkg=$currentPkg key=$key ctxPkg=${ctx?.packageName}")

        // ==========================================
        // 1. Blued 内部读取机制 (正式版 / 极速版 均适用)
        // ==========================================
        if (isBluedPackage(currentPkg)) {
            val app = currentApp(ctx)
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
        freshCache.remove(key)   // ★ 保存即失效缓存, 让后续读取立刻拿到新值
        val currentPkg = currentPackage(ctx)
        val context = getContext(ctx) ?: currentApp(ctx)
        android.util.Log.d("LlhookConfig", "SET pkg=$currentPkg key=$key val=$value ctxPkg=${ctx?.packageName}")

        if (isBluedPackage(currentPkg)) {
            try {
                val app = currentApp(ctx) ?: return
                val editor = app.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE).edit()
                when (value) {
                    "true" -> editor.putBoolean(key, true)
                    "false" -> editor.putBoolean(key, false)
                    else -> editor.putString(key, value)
                }
                editor.commit()   // 同步写入确保持久化完成 (修复面板保存后读不到)

                val pushIntent = android.content.Intent("bxxd.hook.SYNC_PUSH")
                pushIntent.setPackage(PACKAGE_NAME)
                pushIntent.putExtra("key", key)
                pushIntent.putExtra("value", value)
                app.sendBroadcast(pushIntent)
            } catch (e: Throwable) {}
            return
        }

        try {
            val safeCtx = context ?: return
            val cv = ContentValues().apply { put("value", value) }
            safeCtx.contentResolver.update(Uri.parse("$AUTHORITY/$key"), cv, null, null)
            
            // 🚀 【核心修复】主应用修改时，也立刻发广播通知 Blued：“我改数据了！”
            // 同时向所有已安装的宿主(正式版/极速版)广播, 保证双版本都能同步
            for (pkg in SUPPORTED_PACKAGES) {
                val pushIntent = android.content.Intent("bxxd.hook.MAIN_SYNC_PUSH")
                pushIntent.setPackage(pkg)
                pushIntent.putExtra("key", key)
                pushIntent.putExtra("value", value)
                try { safeCtx.sendBroadcast(pushIntent) } catch (_: Throwable) {}
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

    // =====================================================
    // ★ 强制新鲜读 (修复「模块改了色/开关, Blued 读到旧缓存」)
    //   面板(模块App)保存 → 写模块 prefs; Blued 的 getValue 命中本地缓存就 return,
    //   旧值(如白色)永不更新。getFresh 绕过缓存直接问模块要最新值。
    //   带进程内 2s TTL 缓存, 避免频繁 provider 调用。
    // =====================================================
    private val freshCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Pair<String, Long>>() {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<String, Long>>?): Boolean = size > 64
        })

    fun getFresh(key: String, default: String, ctx: Context? = null): String {
        val now = System.currentTimeMillis()
        freshCache[key]?.let { (v, ts) -> if (now - ts < 1500) return v }
        // ★ 始终先试 Blued 本地 prefs (本代码要么跑在 Blued 进程, 要么模块进程)。
        //   避免 currentPackage() 判定不稳定(itemView.context 可能是包装过的 ContextWrapper)
        //   导致走到错误分支读出默认值。本地 prefs 里有就读它。
        val app = currentApp(ctx)
            ?: try { AndroidAppHelper.currentApplication() } catch (_: Throwable) { null }
        val localPrefs = app?.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
        if (localPrefs != null && localPrefs.contains(key)) {
            val v = localPrefs.all[key]?.toString() ?: default
            freshCache[key] = v to now
            return v
        }
        val currentPkg = currentPackage(ctx)
        if (isBluedPackage(currentPkg)) {
            // 本地没有 → 向模块 provider 引导 (模块App刚保存、Blued还没拉取的首次)
            val context = getContext(ctx)
            if (context != null) {
                try {
                    val cursor = context.contentResolver.query(Uri.parse("$AUTHORITY/$key"), null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val v = it.getString(0)
                            if (v != null && v.isNotEmpty()) {
                                val editor = localPrefs?.edit()
                                when (v) {
                                    "true" -> editor?.putBoolean(key, true)
                                    "false" -> editor?.putBoolean(key, false)
                                    else -> editor?.putString(key, v)
                                }
                                editor?.apply()
                                freshCache[key] = v to now
                                return v
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
            freshCache[key] = default to now
            return default
        }
        // 模块进程: 普通读
        val v = getValue(key, default, ctx)
        freshCache[key] = v to now
        return v
    }

    fun isFeatureEnabledFresh(prefKey: String, ctx: Context? = null): Boolean =
        getFresh(prefKey, "false", ctx).toBoolean()

    // =====================================================
    // ★ 诊断工具 (精确定位「保存了但 hook 读不到」)
    // =====================================================
    /** 直接读 Blued 本地 prefs 原始值 (绕过 getFresh 所有逻辑/缓存)。 */
    fun readRawLocal(key: String, ctx: Context? = null): String {
        return try {
            val app = currentApp(ctx) ?: AndroidAppHelper.currentApplication()
            app?.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)?.all?.get(key)?.toString() ?: "null"
        } catch (e: Throwable) { "err" }
    }

    /** 当前进程名 (Blued 是多进程的, 需确认面板与 hook 是否同进程)。 */
    fun currentProcessName(): String {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val name = at.getMethod("currentProcessName").invoke(null) as? String
            name ?: ("pid" + android.os.Process.myPid())
        } catch (_: Throwable) {
            try { "pid" + android.os.Process.myPid() } catch (_: Throwable) { "?" }
        }
    }
}



// build 1784914763


