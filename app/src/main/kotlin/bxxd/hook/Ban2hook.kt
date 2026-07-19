package bxxd.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object Ban2Hook : BaseHook {

    // 内存缓存，防止频繁被调用时疯狂读写磁盘卡死手机
    private val memoryInterceptedSet = mutableSetOf<String>()

    // 已拦截(强制保留在聊天列表)的 uid 集合, 用于在 Blued 原生消息列表显示"已拦截"角标
    private val interceptedDisplaySet = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    // 标记是否已从磁盘懒加载过拦截集合
    private var displaySetLoaded = false

    // 风险来源类型 (用作拦截记录的「来源」标记, 区分不同风险渠道)
    // 这些类型对应 Blued 逆向出的真实数据字段:
    //   DELETE   - SessionRelationModel.delete==1   对方删除好友/消失
    //   OFFLINE  - SessionRelationModel.online==0   长期离线(疑似注销/封禁)
    //   RISKY    - SessionSettingExtraMode.isRiskyUser!=0  服务端标记风险用户
    //   CHEAT    - MsgUserCheatModel.risk>0         诈骗风险等级
    private const val SRC_DELETE = "消失"
    private const val SRC_OFFLINE = "离线"
    private const val SRC_RISKY = "风险"
    private const val SRC_CHEAT = "诈骗"

    /**
     * 通用: 把一个风险用户写入拦截记录(带来源标记), 内存+磁盘双重去重。
     * 不同风险渠道都调这里, 在 JSON 里记 source 字段区分「拦截单位/来源」。
     */
    private fun recordRiskUser(uid: String, source: String, extraName: String = "") {
        if (uid == "0" || uid.isEmpty() || memoryInterceptedSet.contains(uid)) return
        memoryInterceptedSet.add(uid)
        interceptedDisplaySet.add(uid) // 同步加入显示集合, 供消息列表角标判断
        thread {
            try {
                val context = android.app.AndroidAppHelper.currentApplication() ?: return@thread
                val sp = context.getSharedPreferences("llhook_risk_users", Context.MODE_PRIVATE)
                val json = JSONObject(sp.getString("intercepted_uids_json", "{}") ?: "{}")
                if (!json.has(uid)) {
                    json.put(uid, JSONObject().apply {
                        put("name", if (extraName.isNotEmpty()) extraName else uid)
                        put("union_uid", "")
                        put("source", source) // ← 拦截单位/来源标记
                        put("time", System.currentTimeMillis())
                    })
                    sp.edit().putString("intercepted_uids_json", json.toString()).apply()
                } else {
                    // 已存在则补记多来源 (一个用户可能同时命中多个风险渠道)
                    val old = json.optJSONObject(uid)
                    val oldSrc = old?.optString("source", "") ?: ""
                    if (oldSrc.isNotEmpty() && !oldSrc.contains(source)) {
                        old?.put("source", "$oldSrc+$source")
                        json.put(uid, old)
                        sp.edit().putString("intercepted_uids_json", json.toString()).apply()
                    }
                }
            } catch (e: Exception) {
                Log.e("【蓝蓝hook】存储拦截记录异常: ${e.message}")
            }
        }
    }

    // =========================================================================
    //  阶段 2: 风控用户列表公开 API (供 com.example.ui.RiskUsersScreen 调用)
    //  数据持久化: SharedPreferences("llhook_risk_users") 两个 JSON 键
    //    - intercepted_uids_json: 自动拦截的风控用户
    //    - collected_uids_json:   手动收藏/添加的用户
    // =========================================================================

    /** 风控用户记录 (阶段 2 公开数据模型) */
    data class RiskUser(
        val uid: String,
        val name: String,       // 备注名 (默认 = uid)
        val unionUid: String,   // union_uid (跨区 uid, 可能需 fetchRealUid 解析)
        val source: String,     // 拦截来源 (消失/离线/风险/诈骗...)
        val time: Long          // 记录时间戳
    )

    private fun parseRiskMap(jsonStr: String?): MutableList<RiskUser> {
        val out = mutableListOf<RiskUser>()
        if (jsonStr.isNullOrBlank()) return out
        try {
            val json = JSONObject(jsonStr)
            json.keys().forEach { uid ->
                val o = json.optJSONObject(uid)
                if (o != null) {
                    out.add(RiskUser(uid, o.optString("name", uid), o.optString("union_uid", ""),
                        o.optString("source", ""), o.optLong("time", 0L)))
                } else {
                    val name = json.opt(uid)?.toString() ?: uid
                    out.add(RiskUser(uid, name, "", "", 0L))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveRiskMap(ctx: Context, key: String, list: List<RiskUser>) {
        val json = JSONObject()
        list.forEach { u -> json.put(u.uid, JSONObject().apply {
            put("name", u.name); put("union_uid", u.unionUid); put("source", u.source); put("time", u.time)
        }) }
        ctx.getSharedPreferences("llhook_risk_users", Context.MODE_PRIVATE)
            .edit().putString(key, json.toString()).apply()
    }

    private const val KEY_INTERCEPTED = "intercepted_uids_json"
    private const val KEY_COLLECTED = "collected_uids_json"

    /** 获取所有自动拦截记录 (按时间倒序) */
    fun getInterceptedUsers(ctx: Context): List<RiskUser> {
        val sp = ctx.getSharedPreferences("llhook_risk_users", Context.MODE_PRIVATE)
        return parseRiskMap(sp.getString(KEY_INTERCEPTED, "{}")).sortedByDescending { it.time }
    }

    /** 获取所有手动收藏记录 */
    fun getCollectedUsers(ctx: Context): List<RiskUser> {
        val sp = ctx.getSharedPreferences("llhook_risk_users", Context.MODE_PRIVATE)
        return parseRiskMap(sp.getString(KEY_COLLECTED, "{}")).sortedByDescending { it.time }
    }

    /** 手动添加到收藏 (uid + 备注名) */
    fun addCollectedUser(ctx: Context, uid: String, name: String) {
        if (uid.isEmpty() || uid == "0") return
        val list = getCollectedUsers(ctx).toMutableList()
        if (list.none { it.uid == uid }) {
            list.add(RiskUser(uid, name.ifEmpty { uid }, "", "手动添加", System.currentTimeMillis()))
            saveRiskMap(ctx, KEY_COLLECTED, list)
        }
    }

    /** 修改备注名 */
    fun renameRiskUser(ctx: Context, isIntercepted: Boolean, uid: String, newName: String) {
        val key = if (isIntercepted) KEY_INTERCEPTED else KEY_COLLECTED
        val list = (if (isIntercepted) getInterceptedUsers(ctx) else getCollectedUsers(ctx)).toMutableList()
        val i = list.indexOfFirst { it.uid == uid }
        if (i >= 0) { list[i] = list[i].copy(name = newName); saveRiskMap(ctx, key, list) }
    }

    /** 删除一条记录 */
    fun removeRiskUser(ctx: Context, isIntercepted: Boolean, uid: String) {
        val key = if (isIntercepted) KEY_INTERCEPTED else KEY_COLLECTED
        val list = (if (isIntercepted) getInterceptedUsers(ctx) else getCollectedUsers(ctx)).toMutableList()
        saveRiskMap(ctx, key, list.filterNot { it.uid == uid })
        if (isIntercepted) { memoryInterceptedSet.remove(uid); interceptedDisplaySet.remove(uid) }
    }

    /** 转存: 拦截记录 → 收藏 */
    fun moveInterceptedToCollected(ctx: Context, uid: String) {
        val u = getInterceptedUsers(ctx).find { it.uid == uid } ?: return
        removeRiskUser(ctx, true, uid)
        val list = getCollectedUsers(ctx).toMutableList()
        if (list.none { it.uid == uid }) {
            list.add(u.copy(source = u.source.ifEmpty { "转存" }))
            saveRiskMap(ctx, KEY_COLLECTED, list)
        }
    }

    /** 清空 */
    fun clearAll(ctx: Context, isIntercepted: Boolean) {
        val key = if (isIntercepted) KEY_INTERCEPTED else KEY_COLLECTED
        ctx.getSharedPreferences("llhook_risk_users", Context.MODE_PRIVATE).edit().remove(key).apply()
        if (isIntercepted) { memoryInterceptedSet.clear(); interceptedDisplaySet.clear() }
    }

    /** 导出全部记录为可读文本 */
    fun exportAll(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("════════ llhook 风控用户列表 ════════")
        sb.appendLine("导出时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        val inter = getInterceptedUsers(ctx)
        sb.appendLine("\n── 自动拦截记录 (${inter.size}) ──")
        inter.forEach { sb.appendLine("[${it.source}] ${it.name} (${it.uid})") }
        val col = getCollectedUsers(ctx)
        sb.appendLine("\n── 我的收藏 (${col.size}) ──")
        col.forEach { sb.appendLine("${it.name} (${it.uid})${if (it.unionUid.isNotEmpty()) " union=${it.unionUid}" else ""}") }
        return sb.toString()
    }

    /** 从会话模型对象提取 sessionId(uid), 兼容 long/String 两种字段类型 */
    private fun extractSessionId(obj: Any): String {
        return try {
            XposedHelpers.getLongField(obj, "sessionId").toString()
        } catch (e: Exception) {
            try {
                XposedHelpers.getObjectField(obj, "sessionId")?.toString() ?: "0"
            } catch (e2: Exception) { "0" }
        }
    }

    /** 从磁盘加载已拦截 uid 到显示集合(用于消息列表角标) */
    private fun loadDisplaySet() {
        try {
            val ctx = android.app.AndroidAppHelper.currentApplication() ?: return
            val sp = ctx.getSharedPreferences("llhook_risk_users", Context.MODE_PRIVATE)
            val json = JSONObject(sp.getString("intercepted_uids_json", "{}") ?: "{}")
            json.keys().forEach { interceptedDisplaySet.add(it) }
        } catch (_: Throwable) {}
    }

    /** 递归收集 View 树里所有 TextView */
    private fun collectTextViews(v: View?, out: ArrayList<TextView>) {
        if (v == null) return
        if (v is TextView) out.add(v)
        else if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectTextViews(v.getChildAt(i), out)
        }
    }

    /** 给消息列表项追加"·已拦截"标记: 找字号最大的非数字 TextView(通常是昵称) */
    private fun markInterceptedBadge(itemView: View) {
        try {
            val tvs = ArrayList<TextView>()
            collectTextViews(itemView, tvs)
            var bestTv: TextView? = null
            var bestSize = 0f
            for (tv in tvs) {
                val t = tv.text?.toString() ?: ""
                if (t.isEmpty() || t.contains("已拦截")) continue
                if (t.matches(Regex("\\d.*"))) continue // 跳过时间/未读数等纯数字
                if (tv.textSize > bestSize) { bestSize = tv.textSize; bestTv = tv }
            }
            bestTv?.let { it.text = "${it.text} ·已拦截" }
        } catch (_: Throwable) {}
    }

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        // ================================================================
        // 风险来源 1: 对方删除好友 (SessionRelationModel.delete==1) → 会话从列表消失
        // ================================================================
        try {
            val sessionClass = lpparam.classLoader.loadClass("com.blued.android.module.chat.model.SessionRelationModel")
            sessionClass.findMethod { name == "getDelete" }.hookBefore { param ->
                val deleteVal = XposedHelpers.getIntField(param.thisObject, "delete")
                if (deleteVal == 1) {
                    val sessionId = extractSessionId(param.thisObject)
                    recordRiskUser(sessionId, SRC_DELETE)
                    param.result = 0 // 拦截删除标记, 让会话继续显示在聊天列表
                }
            }
        } catch (e: Exception) {
            Log.e("【蓝蓝hook】聊天防删拦截失败: ${e.message}")
        }

        // ================================================================
        // 风险来源 2: 服务端标记的风险用户 (SessionSettingExtraMode.isRiskyUser)
        //   hook 该类的构造/字段访问, 只要 isRiskyUser>0 就记录并提示。
        //   该字段是 Blued 后台下发的「该用户为风险用户」标志。
        // ================================================================
        try {
            val extraClass = lpparam.classLoader.loadClass("com.blued.android.module.chat.model.SessionSettingExtraMode")
            // hook 所有 getter: 只要读到 isRiskyUser 且非 0, 就提取 union_uid 记录
            extraClass.declaredMethods.forEach { m ->
                try {
                    if (m.name == "getIsRiskyUser" || (m.parameterTypes.isEmpty() && m.returnType == Integer::class.javaPrimitiveType)) {
                        m.hookAfter { param ->
                            try {
                                val v = param.result as? Int ?: 0
                                if (v != 0) {
                                    var uid = "0"
                                    try { uid = XposedHelpers.getObjectField(param.thisObject, "union_uid")?.toString() ?: "0" } catch (_: Throwable) {}
                                    if (uid == "0") {
                                        try { uid = XposedHelpers.getLongField(param.thisObject, "uid").toString() } catch (_: Throwable) {}
                                    }
                                    recordRiskUser(uid, SRC_RISKY)
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (e: Exception) {
            Log.e("【蓝蓝hook】风险用户(isRiskyUser)拦截失败: ${e.message}")
        }

        // ================================================================
        // 风险来源 3: 聊天诈骗风险 (MsgUserCheatModel.risk>0)
        //   risk 字段是 Blued 反诈骗系统给出的风险等级, >0 表示该用户有诈骗风险。
        // ================================================================
        try {
            val cheatClass = lpparam.classLoader.loadClass("com.soft.blued.ui.msg.model.MsgUserCheatModel")
            cheatClass.declaredMethods.forEach { m ->
                try {
                    if (m.parameterTypes.isEmpty() && m.returnType == Integer::class.javaPrimitiveType) {
                        m.hookAfter { param ->
                            try {
                                val v = param.result as? Int ?: 0
                                if (v > 0) {
                                    // MsgUserCheatModel 无 uid, 用 warn_message/alert 内容辅助
                                    var uid = "0"
                                    try { uid = XposedHelpers.getObjectField(param.thisObject, "uid")?.toString() ?: "0" } catch (_: Throwable) {}
                                    if (uid == "0") {
                                        try { uid = XposedHelpers.getLongField(param.thisObject, "sessionId").toString() } catch (_: Throwable) {}
                                    }
                                    if (uid != "0") recordRiskUser(uid, SRC_CHEAT)
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (e: Exception) {
            Log.e("【蓝蓝hook】诈骗风险(risk)拦截失败: ${e.message}")
        }

        // ================================================================
        // 功能: 在 Blued 原生消息列表给已拦截会话显示"·已拦截"角标
        //   hook BaseListAdapter.getView (MsgAdapter 继承它), 渲染完成后给
        //   已拦截 uid 的会话项追加标记文字。只追加文字不改背景, convertView
        //   复用时 adapter 会重置文本, 天然避免脏标记残留。
        // ================================================================
        try {
            val getViewHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        // 懒加载: 首次渲染时从磁盘加载已拦截 uid
                        if (!displaySetLoaded) { loadDisplaySet(); displaySetLoaded = true }
                        if (interceptedDisplaySet.isEmpty()) return
                        val position = param.args.getOrNull(0) as? Int ?: return
                        val itemView = param.result as? View ?: return
                        // 从 BaseListAdapter 字段 c(List) 取该位置的数据对象
                        val list = XposedHelpers.getObjectField(param.thisObject, "c") as? List<*> ?: return
                        val data = list.getOrNull(position) ?: return
                        // 取 sessionId (SessionBaseModel.sessionId, 多重回退)
                        var uid = runCatching { XposedHelpers.getLongField(data, "sessionId").toString() }.getOrDefault("0")
                        if (uid == "0") uid = runCatching { XposedHelpers.getObjectField(data, "uid")?.toString() ?: "0" }.getOrDefault("0")
                        if (uid == "0") return
                        if (uid in interceptedDisplaySet) markInterceptedBadge(itemView)
                    } catch (_: Throwable) {}
                }
            }
            var hooked = 0
            for (name in listOf(
                "com.soft.blued.ui.msg.adapter.BaseListAdapter",
                "com.soft.blued.ui.msg.adapter.MsgAdapter"
            )) {
                try {
                    val cls = lpparam.classLoader.loadClass(name)
                    hooked += XposedBridge.hookAllMethods(cls, "getView", getViewHook).size
                } catch (_: Throwable) {}
            }
            if (hooked == 0) Log.e("【蓝蓝hook】消息列表角标: 未hook到getView")
        } catch (e: Exception) {
            Log.e("【蓝蓝hook】消息列表角标 hook 失败: ${e.message}")
        }

        try {
            val activityClass = lpparam.classLoader.loadClass("android.app.Activity")
            activityClass.findMethod { name == "onResume" }.hookAfter { param ->
                val activity = param.thisObject as Activity
                val resId = activity.resources.getIdentifier("ll_main_msg", "id", activity.packageName)
                if (resId != 0) {
                    activity.findViewById<View>(resId)?.setOnLongClickListener {
                        showRiskUsersDialog(activity)
                        true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("【蓝蓝hook】寻找消息按钮失败: ${e.message}")
        }
    }

    fun fetchRealUid(activity: Activity, unionId: String): String? {
        try {
            val urlStr = "https://social.blued.cn/users/search?keywords=$unionId"
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 13; 22081212C Build/TKQ1.220829.002) Android/300237_0.23.7_2842_0221 (Asia/Shanghai) Dalvik/2.1.0 app/7")
            
            try {
                val userInfoClass = XposedHelpers.findClass("com.blued.android.module.common.user.model.UserInfo", activity.classLoader)
                val userInfoInst = XposedHelpers.callStaticMethod(userInfoClass, "getInstance")
                val accessToken = XposedHelpers.callMethod(userInfoInst, "getAccessToken") as? String
                val loginUserInfo = XposedHelpers.callMethod(userInfoInst, "getLoginUserInfo")
                val myUid = XposedHelpers.callMethod(loginUserInfo, "getUid") as? String
                
                if (!accessToken.isNullOrEmpty() && !myUid.isNullOrEmpty()) {
                    val authRaw = "$myUid:$accessToken"
                    val authBase64 = Base64.encodeToString(authRaw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    conn.setRequestProperty("Authorization", "Basic $authBase64")
                }
            } catch (e: Exception) {
                Log.e("【蓝蓝hook】提取 Token 异常: ${e.message}")
            }

            if (conn.responseCode == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(responseStr)
                var realUid: String? = null
                val dataArray = jsonObject.optJSONArray("data")
                if (dataArray != null && dataArray.length() > 0) {
                    realUid = dataArray.optJSONObject(0)?.optString("uid")
                } 
                if (realUid.isNullOrEmpty()) {
                    realUid = jsonObject.optJSONObject("data")?.optString("uid")
                }
                if (realUid.isNullOrEmpty()) {
                    realUid = jsonObject.optString("uid")
                }
                return realUid
            }
        } catch (e: Exception) {
            Log.e("【蓝蓝hook】fetchRealUid异常: ${e.message}")
        }
        return null
    }

    fun resolveUnionIdAndJump(activity: Activity, unionId: String, isChat: Boolean) {
        thread {
            val realUid = fetchRealUid(activity, unionId)
            activity.runOnUiThread {
                if (!realUid.isNullOrEmpty()) {
                    if (isChat) jumpToChatRoom(activity, realUid) 
                    else jumpToUserProfile(activity, realUid, false)
                } else {
                    Toast.makeText(activity, "解析失败：未能提取到真实 UID", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun operateBlacklist(activity: Activity, targetUid: String, isBlock: Boolean, onComplete: (Boolean) -> Unit) {
        thread {
            try {
                val userInfoClass = XposedHelpers.findClass("com.blued.android.module.common.user.model.UserInfo", activity.classLoader)
                val userInfoInst = XposedHelpers.callStaticMethod(userInfoClass, "getInstance")
                val accessToken = XposedHelpers.callMethod(userInfoInst, "getAccessToken") as? String
                val loginUserInfo = XposedHelpers.callMethod(userInfoInst, "getLoginUserInfo")
                val myUid = XposedHelpers.callMethod(loginUserInfo, "getUid") as? String
                
                if (accessToken.isNullOrEmpty() || myUid.isNullOrEmpty()) {
                    activity.runOnUiThread { Toast.makeText(activity, "获取凭证失败", Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                
                val authRaw = "$myUid:$accessToken"
                val authBase64 = Base64.encodeToString(authRaw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                
                val urlStr = if (isBlock) {
                    "https://social.blued.cn/users/$myUid/blacklist/$targetUid"
                } else {
                    "https://social.blued.cn/users/$myUid/blacklist/$targetUid?http_method_override=DELETE"
                }
                
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Basic $authBase64")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 13; 22081212C Build/TKQ1.220829.002) Android/300237_0.23.7_2842_0221 (Asia/Shanghai) Dalvik/2.1.0 app/7")
                
                val code = conn.responseCode
                activity.runOnUiThread { onComplete(code == 200) }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "网络请求异常: ${e.message}", Toast.LENGTH_SHORT).show()
                    onComplete(false)
                }
            }
        }
    }

    fun jumpToChatRoom(context: Context, targetId: String) {
        try {
            val bundle = Bundle().apply {
                putShort("passby_session_type", 2.toShort())
                putLong("passby_session_id", targetId.toLong())
                putString("passby_nick_name", "正在读取用户")
                putInt("passby_vip_grade", 0)
                putInt("passby_is_vip_annual", 0)
                putInt("passby_vip_exp_lvl", 1)
                putInt("passby_vbadge", 0)
                putInt("passby_is_hide_vip_look", 0)
                putBoolean("passby_session_secret", false)
                putBoolean("PASSBY_DATE_TODAY", false)
            }
            val intent = Intent().apply {
                setClassName(Config.currentBluedPackage, "com.blued.android.core.ui.TerminalActivity")
                putExtra("arg_fragment_class_name", "com.soft.blued.ui.msg.MsgChattingFragment")
                putExtra("arg_fragment_args", bundle)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "拉起聊天失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun jumpToUserProfile(context: Context, targetId: String, isUnionMode: Boolean) {
        try {
            val userInfoFragmentNewClass = XposedHelpers.findClass("com.soft.blued.ui.user.fragment.UserInfoFragmentNew", context.classLoader)
            val methods = userInfoFragmentNewClass.declaredMethods
            var invoked = false

            for (method in methods) {
                if (java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                    val params = method.parameterTypes
                    if (params.size == 3 && params[0] == Context::class.java && params[1] == String::class.java && params[2] == String::class.java) {
                        try {
                            method.isAccessible = true
                            method.invoke(null, context, targetId, "search_user")
                            invoked = true
                            break
                        } catch (e: Exception) {}
                    }
                }
            }

            if (!invoked) {
                for (method in methods) {
                    if (java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                        val params = method.parameterTypes
                        if (params.size == 2 && params[0] == Context::class.java && params[1] == String::class.java) {
                            try {
                                method.isAccessible = true
                                method.invoke(null, context, targetId)
                                invoked = true
                                break
                            } catch (e: Exception) {}
                        }
                    }
                }
            }

            if (!invoked) {
                val bundle = Bundle().apply {
                    putString("UID", targetId)
                    putString("userfrom", "search_user")
                    putBoolean("arg_without_fitui", true)
                    putBoolean("is_living", false)
                    putBoolean("is_shadow", false)
                }
                val intent = Intent().apply {
                    setClassName(Config.currentBluedPackage, "com.blued.android.core.ui.TerminalActivity")
                    putExtra("arg_fragment_class_name", "com.soft.blued.ui.user.fragment.UserInfoFragmentNew")
                    putExtra("arg_fragment_args", bundle)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (ex: Exception) {
            Toast.makeText(context, "跳转核心异常: ${ex.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createRoundRect(colorHex: String, radiusDp: Float, density: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = radiusDp * density
        }
    }

    fun showRiskUsersDialog(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        fun dp2px(dp: Int): Int = (dp * density).toInt()

        val colorBg = "#FFFFFF"         
        val colorInputBg = "#F2F2F7"    
        val colorPrimary = "#0A84FF"    
        val colorUnion = "#FF9500"      
        val colorJump = "#34C759"       
        val colorText = "#000000"       
        val colorSubText = "#8E8E93"    
        val colorDivider = "#E5E5EA"    

        val sp = activity.getSharedPreferences("llhook_risk_users", Context.MODE_PRIVATE)

        // ================= 解析收藏夹数据 =================
        val collectedMap = mutableMapOf<String, JSONObject>()
        try {
            val jsonStr = sp.getString("collected_uids_json", "{}") ?: "{}"
            val json = JSONObject(jsonStr)
            json.keys().forEach { key -> 
                val value = json.opt(key)
                if (value is JSONObject) {
                    collectedMap[key] = value
                } else if (value is String) {
                    collectedMap[key] = JSONObject().apply { put("name", value); put("union_uid", "") }
                }
            }
        } catch (e: Exception) {}

        // 兼容旧版 Set 收藏数据
        val oldCollected = sp.getStringSet("collected_uids", null)
        if (oldCollected != null) {
            oldCollected.forEach { 
                if (!collectedMap.containsKey(it)) collectedMap[it] = JSONObject().apply { put("name", it); put("union_uid", "") }
            }
            sp.edit().remove("collected_uids").apply()
            val newJson = JSONObject()
            collectedMap.forEach { (k, v) -> newJson.put(k, v) }
            sp.edit().putString("collected_uids_json", newJson.toString()).apply()
        }

        // ================= 解析拦截记录数据 =================
        val interceptedMap = mutableMapOf<String, JSONObject>()
        try {
            val jsonStr = sp.getString("intercepted_uids_json", "{}") ?: "{}"
            val json = JSONObject(jsonStr)
            json.keys().forEach { key ->
                val value = json.opt(key)
                if (value is JSONObject) {
                    interceptedMap[key] = value
                    memoryInterceptedSet.add(key)
                }
            }
        } catch (e: Exception) {}

        // 兼容旧版纯 Set 拦截数据迁移
        val oldIntercepted = sp.getStringSet("uids", null)
        if (oldIntercepted != null) {
            oldIntercepted.forEach {
                if (it != "0" && !interceptedMap.containsKey(it)) {
                    interceptedMap[it] = JSONObject().apply { put("name", it); put("union_uid", "") }
                    memoryInterceptedSet.add(it)
                }
            }
            sp.edit().remove("uids").apply()
            val newJson = JSONObject()
            interceptedMap.forEach { (k, v) -> newJson.put(k, v) }
            sp.edit().putString("intercepted_uids_json", newJson.toString()).apply()
        }

        var interceptedList = interceptedMap.toList()
        var collectedList = collectedMap.toList()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val mainContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect(colorBg, 24f, density)
            setPadding(dp2px(20), dp2px(20), dp2px(20), dp2px(20))
        }

        val searchBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp2px(15)) }
        }

        var isUnionMode = sp.getBoolean("is_union_mode", false)

        val editTextUid = EditText(activity).apply {
            hint = if (isUnionMode) "当前: Union_ID" else "当前: UID"
            setHintTextColor(Color.parseColor(colorSubText))
            setTextColor(Color.parseColor(colorText))
            textSize = 13f 
            inputType = android.text.InputType.TYPE_CLASS_NUMBER 
            background = createRoundRect(colorInputBg, 10f, density)
            setPadding(dp2px(12), dp2px(8), dp2px(12), dp2px(8))
            layoutParams = LinearLayout.LayoutParams(0, dp2px(36), 1f).apply {
                setMargins(0, 0, dp2px(8), 0) 
            }
        }

        val btnTeleport = Button(activity).apply {
            text = if (isUnionMode) "搜Union" else "搜UID"
            setTextColor(Color.WHITE)
            textSize = 11f 
            setTypeface(null, Typeface.BOLD)
            background = createRoundRect(if (isUnionMode) colorUnion else colorPrimary, 10f, density)
            setPadding(dp2px(2), dp2px(2), dp2px(2), dp2px(2))
            layoutParams = LinearLayout.LayoutParams(dp2px(65), dp2px(36)).apply {
                setMargins(0, 0, dp2px(6), 0)
            }
            
            setOnClickListener {
                val inputStr = editTextUid.text.toString().trim()
                if (inputStr.isNotEmpty()) {
                    if (isUnionMode) resolveUnionIdAndJump(activity, inputStr, isChat = true)
                    else { jumpToChatRoom(activity, inputStr); dialog.dismiss() }
                } else Toast.makeText(activity, "请先输入 ID", Toast.LENGTH_SHORT).show()
            }

            setOnLongClickListener {
                isUnionMode = !isUnionMode
                sp.edit().putBoolean("is_union_mode", isUnionMode).apply()
                if (isUnionMode) {
                    text = "搜官方ID"
                    editTextUid.hint = "当前: Union_ID"
                    background = createRoundRect(colorUnion, 10f, density) 
                } else {
                    text = "搜UID"
                    editTextUid.hint = "当前: UID"
                    background = createRoundRect(colorPrimary, 10f, density) 
                }
                true
            }
        }

        val btnCustomJump = Button(activity).apply {
            text = "跳主页"
            setTextColor(Color.WHITE)
            textSize = 11f 
            setTypeface(null, Typeface.BOLD)
            background = createRoundRect(colorJump, 10f, density)
            setPadding(dp2px(2), dp2px(2), dp2px(2), dp2px(2))
            layoutParams = LinearLayout.LayoutParams(dp2px(65), dp2px(36))
            
            setOnClickListener {
                val inputId = editTextUid.text.toString().trim()
                if (inputId.isNotEmpty()) {
                    if (isUnionMode) resolveUnionIdAndJump(activity, inputId, isChat = false)
                    else { jumpToUserProfile(activity, inputId, false); dialog.dismiss() }
                } else Toast.makeText(activity, "请先输入 ID", Toast.LENGTH_SHORT).show()
            }

            setOnLongClickListener {
                val inputId = editTextUid.text.toString().trim()
                if (inputId.isNotEmpty()) {
                    if (isUnionMode) {
                        Toast.makeText(activity, "正在获取真实UID...", Toast.LENGTH_SHORT).show()
                        thread {
                            val realUid = fetchRealUid(activity, inputId)
                            activity.runOnUiThread {
                                if (realUid != null) {
                                    if (!collectedMap.containsKey(realUid)) {
                                        collectedMap[realUid] = JSONObject().apply {
                                            put("name", inputId)
                                            put("union_uid", inputId)
                                        }
                                        val newJson = JSONObject()
                                        collectedMap.forEach { (k, v) -> newJson.put(k, v) }
                                        sp.edit().putString("collected_uids_json", newJson.toString()).apply()
                                        Toast.makeText(activity, "收藏成功", Toast.LENGTH_SHORT).show()
                                        dialog.dismiss()
                                        showRiskUsersDialog(activity)
                                    } else {
                                        Toast.makeText(activity, "该 ID 已在收藏夹中", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(activity, "获取UID失败，无法收藏", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        if (!collectedMap.containsKey(inputId)) {
                            collectedMap[inputId] = JSONObject().apply {
                                put("name", inputId)
                                put("union_uid", "")
                            }
                            val newJson = JSONObject()
                            collectedMap.forEach { (k, v) -> newJson.put(k, v) }
                            sp.edit().putString("collected_uids_json", newJson.toString()).apply()
                            Toast.makeText(activity, "已加入收藏夹", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            showRiskUsersDialog(activity)
                        } else {
                            Toast.makeText(activity, "该 ID 已在收藏夹中", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else Toast.makeText(activity, "请先填入要收藏的 ID", Toast.LENGTH_SHORT).show()
                true
            }
        }

        searchBar.addView(editTextUid)
        searchBar.addView(btnTeleport)
        searchBar.addView(btnCustomJump)
        mainContainer.addView(searchBar)

        val tabLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            weightSum = 2f
        }

        val tvTabLeft = TextView(activity).apply {
            text = "拦截记录"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp2px(8), 0, dp2px(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTabRight = TextView(activity).apply {
            text = "我的收藏"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp2px(8), 0, dp2px(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val indicatorContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp2px(2))
            weightSum = 2f
        }
        val indicatorLeft = View(activity).apply {
            background = ColorDrawable(Color.parseColor(colorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val indicatorRight = View(activity).apply {
            background = ColorDrawable(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        indicatorContainer.addView(indicatorLeft)
        indicatorContainer.addView(indicatorRight)

        tabLayout.addView(tvTabLeft)
        tabLayout.addView(tvTabRight)
        mainContainer.addView(tabLayout)
        mainContainer.addView(indicatorContainer)

        val contentFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp2px(250)).apply {
                setMargins(0, dp2px(10), 0, 0)
            }
        }

        // ================= 左侧：拦截列表 =================
        val listViewLeft = ListView(activity).apply {
            divider = ColorDrawable(Color.parseColor(colorDivider))
            dividerHeight = 1
            val displayList = interceptedList.map { 
                val uid = it.first
                val name = it.second.optString("name", uid)
                val src = it.second.optString("source", "")
                val srcTag = if (src.isNotEmpty()) "[$src]" else ""
                if (name == uid) "$uid $srcTag".trim() else "$name (UID: $uid) $srcTag".trim()
            }
            adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, displayList) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(Color.parseColor(colorText))
                    view.textSize = 14f
                    view.setPadding(dp2px(10), dp2px(16), dp2px(10), dp2px(16))
                    return view
                }
            }
            
            // 拦截列表 - 单击事件
            setOnItemClickListener { _, _, position, _ ->
                val uid = interceptedList[position].first
                val options = arrayOf("跳转至主页", "跳转至聊天界面", "UID键入搜索框")
                AlertDialog.Builder(activity)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> { jumpToUserProfile(activity, uid, false); dialog.dismiss() }
                            1 -> { jumpToChatRoom(activity, uid); dialog.dismiss() }
                            2 -> { editTextUid.setText(uid) } // 强制提取纯数字UID放入搜索框
                        }
                    }.show()
            }
            
            // 拦截列表 - 长按事件
            setOnItemLongClickListener { _, _, position, _ ->
                val uid = interceptedList[position].first
                val obj = interceptedList[position].second
                val currentName = obj.optString("name", uid)
                val options = arrayOf("跳转至主页", "跳转至聊天界面", "转存到我的收藏", "复制UID", "备注", "删除")
                AlertDialog.Builder(activity)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> { jumpToUserProfile(activity, uid, false); dialog.dismiss() }
                            1 -> { jumpToChatRoom(activity, uid); dialog.dismiss() }
                            2 -> {
                                if (!collectedMap.containsKey(uid)) {
                                    collectedMap[uid] = JSONObject().apply {
                                        put("name", currentName)
                                        put("union_uid", "")
                                    }
                                    val newJson = JSONObject()
                                    collectedMap.forEach { (k, v) -> newJson.put(k, v) }
                                    sp.edit().putString("collected_uids_json", newJson.toString()).apply()
                                    Toast.makeText(activity, "已转存到收藏夹", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(activity, "该 ID 已在收藏夹中", Toast.LENGTH_SHORT).show()
                                }
                            }
                            3 -> {
                                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("UID", uid)) // 强制复制纯UID
                                Toast.makeText(activity, "已复制纯数字 UID: $uid", Toast.LENGTH_SHORT).show()
                            }
                            4 -> {
                                val renameInput = EditText(activity).apply {
                                    setText(if (currentName == uid) "" else currentName)
                                    hint = "请输入备注名"
                                    setPadding(dp2px(20), dp2px(20), dp2px(20), dp2px(20))
                                }
                                AlertDialog.Builder(activity)
                                    .setTitle("修改拦截记录备注")
                                    .setView(renameInput)
                                    .setPositiveButton("保存") { _, _ ->
                                        val newName = renameInput.text.toString().trim()
                                        obj.put("name", if (newName.isNotEmpty()) newName else uid)
                                        interceptedMap[uid] = obj
                                        val newJson = JSONObject()
                                        interceptedMap.forEach { (k, v) -> newJson.put(k, v) }
                                        sp.edit().putString("intercepted_uids_json", newJson.toString()).apply()
                                        dialog.dismiss()
                                        showRiskUsersDialog(activity)
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                            5 -> {
                                interceptedMap.remove(uid)
                                memoryInterceptedSet.remove(uid)
                                val newJson = JSONObject()
                                interceptedMap.forEach { (k, v) -> newJson.put(k, v) }
                                sp.edit().putString("intercepted_uids_json", newJson.toString()).apply()
                                dialog.dismiss()
                                showRiskUsersDialog(activity)
                                Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.show()
                true
            }
        }

        // ================= 右侧：收藏列表 =================
        val listViewRight = ListView(activity).apply {
            divider = ColorDrawable(Color.parseColor(colorDivider))
            dividerHeight = 1
            val displayList = collectedList.map { 
                val uid = it.first
                val name = it.second.optString("name", uid)
                val unionUid = it.second.optString("union_uid", "")
                if (name == uid && unionUid.isEmpty()) uid else "$name (UID: $uid)"
            }
            adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, displayList) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(Color.parseColor(colorText))
                    view.textSize = 14f
                    view.setPadding(dp2px(10), dp2px(16), dp2px(10), dp2px(16))
                    return view
                }
            }
            
            setOnItemClickListener { _, _, position, _ ->
                val uid = collectedList[position].first
                val unionUid = collectedList[position].second.optString("union_uid", "")
                val options = arrayOf("跳转至主页", "跳转至聊天界面", "UID键入搜索框")
                AlertDialog.Builder(activity)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> { 
                                if (isUnionMode && unionUid.isNotEmpty()) resolveUnionIdAndJump(activity, unionUid, false)
                                else jumpToUserProfile(activity, uid, false)
                                dialog.dismiss() 
                            }
                            1 -> { 
                                if (isUnionMode && unionUid.isNotEmpty()) resolveUnionIdAndJump(activity, unionUid, true)
                                else jumpToChatRoom(activity, uid)
                                dialog.dismiss() 
                            }
                            2 -> { editTextUid.setText(uid) } // 强制填入纯数字UID，不论在什么模式下
                        }
                    }.show()
            }

            setOnItemLongClickListener { _, _, position, _ ->
                val uid = collectedList[position].first
                val obj = collectedList[position].second
                val currentName = obj.optString("name", uid)
                val options = arrayOf("拉黑并访问主页", "解除拉黑", "复制UID", "备注", "删除")
                AlertDialog.Builder(activity)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                Toast.makeText(activity, "正在拉黑并访问...", Toast.LENGTH_SHORT).show()
                                operateBlacklist(activity, uid, true) { success ->
                                    if (success) {
                                        Toast.makeText(activity, "已拉黑，跳转主页", Toast.LENGTH_SHORT).show()
                                        jumpToUserProfile(activity, uid, false)
                                        dialog.dismiss()
                                    } else {
                                        Toast.makeText(activity, "拉黑失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            1 -> {
                                Toast.makeText(activity, "正在解除拉黑...", Toast.LENGTH_SHORT).show()
                                operateBlacklist(activity, uid, false) { success ->
                                    if (success) Toast.makeText(activity, "解除拉黑成功", Toast.LENGTH_SHORT).show()
                                    else Toast.makeText(activity, "解除拉黑失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                            2 -> {
                                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("UID", uid)) // 强行只复制UID
                                Toast.makeText(activity, "已复制纯数字 UID: $uid", Toast.LENGTH_SHORT).show()
                            }
                            3 -> {
                                val renameInput = EditText(activity).apply {
                                    setText(if (currentName == uid) "" else currentName)
                                    hint = "请输入备注名"
                                    setPadding(dp2px(20), dp2px(20), dp2px(20), dp2px(20))
                                }
                                AlertDialog.Builder(activity)
                                    .setTitle("修改备注")
                                    .setView(renameInput)
                                    .setPositiveButton("保存") { _, _ ->
                                        val newName = renameInput.text.toString().trim()
                                        obj.put("name", if (newName.isNotEmpty()) newName else uid)
                                        collectedMap[uid] = obj
                                        val newJson = JSONObject()
                                        collectedMap.forEach { (k, v) -> newJson.put(k, v) }
                                        sp.edit().putString("collected_uids_json", newJson.toString()).apply()
                                        dialog.dismiss()
                                        showRiskUsersDialog(activity)
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                            4 -> {
                                collectedMap.remove(uid)
                                val newJson = JSONObject()
                                collectedMap.forEach { (k, v) -> newJson.put(k, v) }
                                sp.edit().putString("collected_uids_json", newJson.toString()).apply()
                                dialog.dismiss()
                                showRiskUsersDialog(activity)
                                Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.show()
                true
            }
        }
        
        listViewRight.visibility = View.GONE

        contentFrame.addView(listViewLeft)
        contentFrame.addView(listViewRight)
        mainContainer.addView(contentFrame)

        var currentTab = 0
        fun switchTab(tabIndex: Int) {
            currentTab = tabIndex
            if (tabIndex == 0) {
                tvTabLeft.setTypeface(null, Typeface.BOLD)
                tvTabRight.setTypeface(null, Typeface.NORMAL)
                tvTabLeft.setTextColor(Color.parseColor(colorPrimary))
                tvTabRight.setTextColor(Color.parseColor(colorSubText))
                indicatorLeft.setBackgroundColor(Color.parseColor(colorPrimary))
                indicatorRight.setBackgroundColor(Color.TRANSPARENT)
                listViewLeft.visibility = View.VISIBLE
                listViewRight.visibility = View.GONE
            } else {
                tvTabLeft.setTypeface(null, Typeface.NORMAL)
                tvTabRight.setTypeface(null, Typeface.BOLD)
                tvTabLeft.setTextColor(Color.parseColor(colorSubText))
                tvTabRight.setTextColor(Color.parseColor(colorPrimary))
                indicatorLeft.setBackgroundColor(Color.TRANSPARENT)
                indicatorRight.setBackgroundColor(Color.parseColor(colorPrimary))
                listViewLeft.visibility = View.GONE
                listViewRight.visibility = View.VISIBLE
            }
        }
        
        switchTab(0)
        tvTabLeft.setOnClickListener { switchTab(0) }
        tvTabRight.setOnClickListener { switchTab(1) }

        val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 100 && Math.abs(velocityX) > 100) {
                    if (dx > 0 && currentTab == 1) switchTab(0) 
                    else if (dx < 0 && currentTab == 0) switchTab(1) 
                    return true
                }
                return false
            }
        })

        val touchListener = View.OnTouchListener { _, event -> gestureDetector.onTouchEvent(event); false }
        listViewLeft.setOnTouchListener(touchListener)
        listViewRight.setOnTouchListener(touchListener)

        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { 
                setMargins(0, dp2px(15), 0, 0) 
            }
        }

        val btnClear = Button(activity).apply {
            text = "清空当前列表"
            setTextColor(Color.parseColor("#FF3B30")) 
            textSize = 13f
            background = createRoundRect(colorInputBg, 12f, density)
            layoutParams = LinearLayout.LayoutParams(0, dp2px(42), 1f).apply { setMargins(0, 0, dp2px(10), 0) }
            setOnClickListener {
                if (currentTab == 0) {
                    interceptedMap.clear()
                    memoryInterceptedSet.clear()
                    sp.edit().remove("intercepted_uids_json").apply()
                } else {
                    collectedMap.clear()
                    sp.edit().remove("collected_uids_json").apply()
                }
                Toast.makeText(activity, "当前列表已清空", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                showRiskUsersDialog(activity) // 重新渲染界面
            }
        }

        val btnClose = Button(activity).apply {
            text = "关闭"
            setTextColor(Color.parseColor(colorText))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            background = createRoundRect(colorInputBg, 12f, density)
            layoutParams = LinearLayout.LayoutParams(0, dp2px(42), 1f).apply { setMargins(dp2px(10), 0, 0, 0) }
            setOnClickListener { dialog.dismiss() }
        }

        bottomBar.addView(btnClear)
        bottomBar.addView(btnClose)
        mainContainer.addView(bottomBar)

        dialog.setContentView(mainContainer)
        val width = (activity.resources.displayMetrics.widthPixels * 0.85).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }
}
