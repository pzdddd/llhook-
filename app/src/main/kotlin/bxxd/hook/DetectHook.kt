package bxxd.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface

/**
 * ============================================================================
 *  设备检测可视化 (移植自 "检测_1.0.apk" 的 DeviceInfoDetector / xposed14)
 * ============================================================================
 *
 *  【做什么】
 *  把 Blued 内部设备采集 SDK 对你设备做的全部采集 / 风控检测结果可视化出来,
 *  让你直观看到 "Blued 到底收集了我哪些信息、风控判定我是什么环境"。
 *
 *  【两层数据】
 *  1) Blued 自家采集: Hook com.danlan.android.cognition.collector.base
 *     .BaseDeviceInfoCollector.getDeviceData(), 截获 Blued 主动采集的设备指纹 JSON。
 *  2) 独立本地检测: 在 Blued 进程内直接查 模拟器/Root/调试器/Hook框架/VPN/代理,
 *     结果 = 【Blued 此刻真实看到的环境】(若 RiskEnvHook 已开, 这里会显示干净,
 *     正好用来验证隐藏效果)。
 *
 *  【与原版区别】
 *  - 去掉了原版的时间炸弹 (2025-10-13 过期) / Telegram 联系人 / 明文落盘敏感数据;
 *  - 去掉了 StringFog 双重加密 + Zalgo 噪声, 全部明文逻辑, 可读可审计;
 *  - 接入本项目 iOS 风格弹窗, 从悬浮窗主菜单进入。
 * ============================================================================
 */
object DetectHook : BaseHook {

    private const val TAG = "BluedHook"

    /** Blued 设备认知采集 SDK 的核心类 (Blued 正式版/极速版类名一致) */
    private const val COLLECTOR_CLASS =
        "com.danlan.android.cognition.collector.base.BaseDeviceInfoCollector"

    /** 已捕获的 Blued 设备采集 JSON (内存, 不落盘) */
    @Volatile
    private var capturedJson: String? = null
    @Volatile
    private var captureTime: Long = 0L

    private var collectorClassRef: Class<*>? = null

    /** dp->px (无需 Context, 用系统资源) */
    private fun dp2pxV(dp: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp,
            android.content.res.Resources.getSystem().displayMetrics
        ).toInt()

    // =========================================================================
    //  入口
    // =========================================================================
    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookDeviceCollector(lpparam)
        Log.d(TAG, "🔍 设备检测可视化已启用 (目标: $COLLECTOR_CLASS)")
    }

    // =========================================================================
    //  一、Hook Blued 设备采集器
    //     getDeviceData() 无参, 返回 String/JSONObject —— 截获后存内存。
    // =========================================================================
    private fun hookDeviceCollector(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = lpparam.classLoader.loadClass(COLLECTOR_CLASS)
            collectorClassRef = cls
            cls.findMethod { name == "getDeviceData" && parameterTypes.isEmpty() }
                .hookAfter { param ->
                    try {
                        val result = param.result ?: return@hookAfter
                        val json = result.toString()
                        if (json.isNotBlank() && json.length > 2) {
                            capturedJson = json
                            captureTime = System.currentTimeMillis()
                            Log.d(TAG, "🔍 已捕获 Blued 设备采集数据 (${json.length} 字节)")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "捕获设备数据异常: ${t.message}")
                    }
                }
        } catch (e: Throwable) {
            Log.d(TAG, "BaseDeviceInfoCollector 未找到, 仅显示本地检测: ${e.message}")
        }
    }

    /** 主动触发一次采集 (尝试静态调用 getDeviceData), 失败则静默 */
    private fun triggerCollect(activity: Activity): Boolean {
        val cls = collectorClassRef ?: return false
        return try {
            val m = cls.getDeclaredMethod("getDeviceData")
            m.isAccessible = true
            val result = m.invoke(null) // 先按静态方法试
            val json = result?.toString()
            if (!json.isNullOrBlank()) {
                capturedJson = json
                captureTime = System.currentTimeMillis()
                Log.d(TAG, "🔍 主动采集成功 (${json.length} 字节)")
                true
            } else false
        } catch (t: Throwable) {
            // 非静态 / 需要实例 —— 暂时拿不到实例, 提示用户操作 App 触发
            Log.d(TAG, "主动采集失败(需实例): ${t.message}")
            false
        }
    }

    // =========================================================================
    //  二、独立本地风险检测
    //     全部在 Blued 进程内执行, 反映【Blued 此刻真实看到的环境】。
    //     注意: 若 RiskEnvHook 已开启, su 文件 / 风险包查询会被隐藏,
    //     这里会显示 "干净" —— 正是 RiskEnvHook 的隐藏效果。
    // =========================================================================

    data class Check(
        val category: String,
        val item: String,
        val hit: Boolean,       // true = 命中风险
        val detail: String
    )

    /** 风险等级 (阶段 2: 供 Compose 检测报告页概览使用) */
    enum class RiskLevel(val label: String) {
        SAFE("安全"), WARNING("有风险"), DANGER("高危")
    }

    /** 完整检测报告 (阶段 2: 聚合本地环境检测 + Blued 风控判定 + 原始采集数据) */
    data class DetectReport(
        val localChecks: List<Check>,      // 本地环境检测 (模拟器/Root/调试器/Hook App/Frida/VPN/代理)
        val bluedChecks: List<Check>,      // Blued 对本机的真实风控判定 (来自采集 JSON)
        val capturedJson: String?,         // 原始采集 JSON
        val capturedPretty: String?,       // 格式化后的 JSON (展示用)
        val captureTime: Long,             // 采集时间戳 (0 = 未采集)
        val hitCount: Int,                 // 命中风险项数
        val riskLevel: RiskLevel           // 综合风险等级
    )

    /**
     * 生成当前检测报告 (读内存中已捕获的 Blued 数据 + 跑一遍本地环境检测)。
     * 不触发主动采集, 安全可在任意线程调用。阶段 2: 供 [com.example.ui.DetectScreen] 调用。
     */
    fun generateReport(ctx: Context): DetectReport {
        val local = runLocalChecks(ctx)
        val blued = parseBluedRisk(capturedJson)
        val pretty = capturedJson?.let { prettyJson(it) }
        val all = local + blued
        val hit = all.count { it.hit }
        val level = computeRiskLevel(local, blued)
        return DetectReport(local, blued, capturedJson, pretty, captureTime, hit, level)
    }

    /**
     * 主动触发 Blued 采集器采集一次, 再生成报告。
     * 返回 null = 采集器类未就绪 (Hook 尚未生效) 或反射调用失败。
     */
    fun triggerCollectAndReport(activity: Activity, ctx: Context): DetectReport? {
        val ok = triggerCollect(activity)
        return if (ok) generateReport(ctx) else null
    }

    /** 拷贝完整报告为可读文本 (阶段 2: 供 Compose 页 “复制报告” 按钮调用) */
    fun buildReportText(report: DetectReport): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════ llhook 设备检测报告 ═══════════")
        sb.appendLine("风险等级: ${report.riskLevel.label}  (命中 ${report.hitCount} 项)")
        sb.appendLine("生成时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        if (report.captureTime > 0) {
            sb.appendLine("Blued 采集时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(report.captureTime))}")
        }
        sb.appendLine()
        sb.appendLine("── 本地环境检测 ──")
        report.localChecks.forEach {
            sb.appendLine("[${if (it.hit) "✗ 命中" else "✓ 安全"}] ${it.category} / ${it.item}  ${it.detail}")
        }
        sb.appendLine()
        if (report.bluedChecks.isNotEmpty()) {
            sb.appendLine("── Blued 风控判定 (Blued 对你设备的真实判定) ──")
            report.bluedChecks.forEach {
                sb.appendLine("[${if (it.hit) "✗ 命中" else "✓ 安全"}] ${it.category} / ${it.item}  ${it.detail}")
            }
        } else {
            sb.appendLine("── Blued 风控判定 ──")
            sb.appendLine("(尚未捕获 Blued 采集数据, 请先使用 App 一会再检测)")
        }
        return sb.toString()
    }

    /**
     * 综合风险等级: 模拟器/Root/Hook 任一命中 = 高危; 否则按命中数分告警/安全。
     * 模拟器与 Hook 是 Blued 必封顶, Root 是强信号; 其他 (VPN/代理/调试) 属可解释项。
     */
    private fun computeRiskLevel(local: List<Check>, blued: List<Check>): RiskLevel {
        val criticalHit = (local + blued).any {
            it.hit && (it.item.contains("模拟器") || it.item.contains("Root") ||
                it.item.contains("Hook") || it.item.contains("重打包") || it.item.contains("注入"))
        }
        if (criticalHit) return RiskLevel.DANGER
        val n = local.count { it.hit } + blued.count { it.hit }
        return if (n >= 1) RiskLevel.WARNING else RiskLevel.SAFE
    }

    private fun runLocalChecks(ctx: Context): List<Check> {
        val list = ArrayList<Check>()

        // —— 模拟器检测 ——
        val emuProps = listOf(
            "ro.kernel.qemu" to "1",
            "ro.product.model" to "",
            "ro.product.brand" to "",
            "ro.product.manufacturer" to "",
            "ro.hardware" to "",
            "ro.product.fingerprint" to ""
        )
        val emuKeywords = listOf("generic", "vbox", "x86_64", "goldfish", "ranchu", "sdk_gphone", "emulator")
        val propQemu = getSystemProp("ro.kernel.qemu", "0")
        val model = Build.MODEL?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""
        val hw = Build.HARDWARE?.lowercase() ?: ""
        val fp = Build.FINGERPRINT?.lowercase() ?: ""
        val product = Build.PRODUCT?.lowercase() ?: ""
        val manuf = Build.MANUFACTURER?.lowercase() ?: ""
        val isEmuByProp = propQemu == "1" ||
            listOf(model, brand, hw, fp, product, manuf).any { v -> emuKeywords.any { v.contains(it) } }
        // 注意: /proc/tty/drivers 在所有真机上都存在, 不能作为模拟器特征, 已移除
        val emuFiles = listOf(
            "/dev/socket/qemud", "/dev/qemu_pipe", "/system/bin/qemu-props",
            "/system/lib/libc_malloc_debug_qemu.so"
        )
        val isEmuByFile = emuFiles.any { runCatching { File(it).exists() }.getOrDefault(false) }
        list += Check(
            "环境", "模拟器",
            isEmuByProp || isEmuByFile,
            buildString {
                if (propQemu == "1") append("qemu=1; ")
                append("model=${Build.MODEL}")
                if (isEmuByProp || isEmuByFile) append(" [命中模拟器特征]")
            }
        )

        // —— Root 检测 (File.exists 会受 RiskEnvHook 影响) ——
        val suPaths = listOf(
            "/system/xbin/su", "/system/bin/su", "/sbin/su", "/vendor/bin/su",
            "/system/sd/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/data/local/su", "/su/bin/su", "/system/bin/failsafe/su",
            "/system/xbin/busybox", "/system/bin/busybox",
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules"
        )
        val rootHit = suPaths.any { runCatching { File(it).exists() }.getOrDefault(false) }
        list += Check("环境", "Root / Magisk", rootHit, if (rootHit) "发现 su/Magisk 痕迹" else "未发现 su/Magisk 痕迹")

        // —— 调试器 ——
        val dbg = Debug.isDebuggerConnected()
        list += Check("环境", "调试器", dbg, if (dbg) "调试器已连接" else "无调试器连接")

        // —— Hook 框架管理器 App (PackageManager 查询受 RiskEnvHook 影响) ——
        val hookApps = setOf(
            "org.lsposed.manager", "org.lsposed.lspatch",
            "de.robv.android.xposed.installer",
            "com.android.developer.xposedinstaller",
            "com.topjohnwu.magisk", "io.github.va.exposed", "rikg.cleantwitter"
        )
        val foundHookApps = hookApps.mapNotNull { pkg ->
            try { ctx.packageManager.getPackageInfo(pkg, 0); pkg }
            catch (t: Throwable) { null }
        }
        list += Check(
            "环境", "Hook 框架 App",
            foundHookApps.isNotEmpty(),
            if (foundHookApps.isEmpty()) "未发现 Xposed/Magisk 管理器" else "已安装: ${foundHookApps.joinToString()}"
        )

        // —— Frida (检查进程内是否已加载 frida 相关, 粗略) ——
        val fridaHit = runCatching {
            val maps = File("/proc/self/maps").readText()
            maps.contains("frida") || maps.contains("gum-js")
        }.getOrDefault(false)
        list += Check("环境", "Frida", fridaHit, if (fridaHit) "内存中发现 Frida 痕迹" else "未发现 Frida 痕迹")

        // —— VPN ——
        val vpnHit = isVpnActive(ctx)
        list += Check("网络", "VPN", vpnHit, if (vpnHit) "检测到 VPN 传输通道" else "未检测到 VPN")

        // —— 代理 ——
        val proxyHost = System.getProperty("http.proxyHost")
        val proxyPort = System.getProperty("http.proxyPort")
        val proxyHit = !proxyHost.isNullOrBlank()
        list += Check(
            "网络", "HTTP 代理", proxyHit,
            if (proxyHit) "代理: $proxyHost:${proxyPort ?: "8080"}" else "未设置代理"
        )

        // —— Blued 采集数据捕获状态 ——
        list += Check(
            "采集", "Blued 设备采集",
            false,
            if (capturedJson != null) "已捕获 ${capturedJson!!.length} 字节 (${formatTime(captureTime)})" else "尚未捕获(使用 App 触发或点主动采集)"
        )

        return list
    }

    private fun isVpnActive(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (t: Throwable) {
            // 回退: 检查网络接口名
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.any {
                    it.name?.lowercase()?.contains("tun") == true ||
                    it.name?.lowercase()?.contains("ppp") == true
                } ?: false
            } catch (e: Throwable) { false }
        }
    }

    /** 反射读取系统属性 (SystemProperties 为隐藏 API) */
    private fun getSystemProp(key: String, default: String): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java, String::class.java)
            m.invoke(null, key, default) as? String ?: default
        } catch (t: Throwable) { default }
    }

    private fun formatTime(ms: Long): String {
        if (ms == 0L) return "—"
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ms))
    }

    // =========================================================================
    //  二·补、解析 Blued 采集 JSON 里的风控判定字段 (原模块核心逻辑)
    //     原模块的检测全部来自这里 —— 读取 BaseDeviceInfoCollector 返回的 JSON 中
    //     Blued 自己算出的风险标志, 这才是 "Blued 对你设备的真实判定"。
    //     采用递归扫描, 兼容字段在顶层 / risk 节点 / system 节点等任意位置。
    // =========================================================================

    /** 已知的风控判定 key → (类目, 检测项名)。值 truthy = Blued 判定命中风险。*/
    private val BLUED_RISK_KEYS: Map<String, Pair<String, String>> = linkedMapOf(
        "rootFlag"           to ("Root"   to "Root 检测"),
        "root"               to ("Root"   to "Root 检测"),
        "emulatorFlag"       to ("模拟器" to "模拟器检测"),
        "emulatorFlag2"      to ("模拟器" to "模拟器检测2"),
        "emulator"           to ("模拟器" to "模拟器检测"),
        "emulator2"          to ("模拟器" to "模拟器检测2"),
        "hasEmulatorFiles"   to ("模拟器" to "模拟器文件"),
        "emulator_files"     to ("模拟器" to "模拟器文件"),
        "hookFlag"           to ("Hook"   to "Hook 检测"),
        "hook"               to ("Hook"   to "Hook 检测"),
        "flag"               to ("Hook"   to "Hook/注入检测"),
        "flag_ls_posed"      to ("Hook"   to "LSPosed 检测"),
        "rePackFlag"         to ("完整性" to "重打包检测"),
        "repack"             to ("完整性" to "重打包检测"),
        "riskApi"            to ("API"   to "风险 API"),
        "riskapi"            to ("API"   to "风险 API"),
        "amsProxy"           to ("多开"   to "AMS 代理/多开"),
        "amsproxy"           to ("多开"   to "AMS 代理/多开"),
        "isVPN"              to ("网络"   to "VPN"),
        "vpn"                to ("网络"   to "VPN"),
        "isProxy"            to ("网络"   to "HTTP 代理"),
        "proxy"              to ("网络"   to "HTTP 代理"),
        "isOpenDevSetting"   to ("系统"   to "开发者选项"),
        "isUsbDebugSetting"  to ("系统"   to "USB 调试"),
        "usb_debug"          to ("系统"   to "USB 调试"),
        "adbSecure"          to ("系统"   to "ADB 安全"),
        "adb_secure"         to ("系统"   to "ADB 安全"),
        "adb_state"          to ("系统"   to "ADB 状态"),
        "adbdState"          to ("系统"   to "ADB 状态"),
        "ro_adb_root"        to ("系统"   to "ADB Root"),
        "ro_debug"           to ("系统"   to "ro.debug"),
        "isDeviceUnlocked"   to ("系统"   to "BootLoader 解锁")
    )

    /** 递归收集 JSON 中所有已知风控 key 的【原始值】(保留类型, 供后续按语义判定) */
    private fun collectRiskKeys(node: Any, found: MutableMap<String, Any?>) {
        when (node) {
            is JSONObject -> {
                val it = node.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = node.opt(k)
                    if (BLUED_RISK_KEYS.containsKey(k) && v != null) {
                        if (!found.containsKey(k)) found[k] = v   // 保留原始 JSONObject/Array/Boolean/Number/String
                    } else if (v is JSONObject || v is JSONArray) {
                        collectRiskKeys(v, found)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val v = node.opt(i) ?: continue
                    if (v is JSONObject || v is JSONArray) collectRiskKeys(v, found)
                }
            }
        }
    }

    /** 按字段语义判定 Blued 风控值是否构成风险, 返回 (是否命中, 明细)。
     *  关键修正: Blued 的 rootFlag / emulatorFlag / hookFlag / rePackFlag 是
     *  {"flag":N,"reason":R} 包装对象, 只有 flag!=0 才是 "检测到"。
     *  旧版把整个对象当字符串 "非空=命中", 导致大量误报。*/
    private fun classifyRisk(key: String, raw: Any?): Pair<Boolean, String> {
        if (raw == null) return false to "Blued: 无值"

        // 1) {"flag":N,"reason":R} 包装对象 —— flag!=0 才算命中
        if (raw is JSONObject) {
            val flag = raw.opt("flag")
            val reason = raw.optString("reason", "").trim()
            val hit = when (flag) {
                is Boolean -> flag
                is Number -> flag.toInt() != 0
                is String -> flag.trim().let { fs ->
                    fs.toIntOrNull()?.let { it != 0 } ?: (fs.isNotBlank() && fs != "0")
                }
                else -> false
            }
            val detail = if (reason.isNotEmpty()) "Blued: flag=$flag, reason=$reason" else "Blued: flag=$flag"
            return hit to detail
        }
        // 2) 数组: 非空 = 命中 (如 riskApi)
        if (raw is JSONArray) {
            val n = raw.length()
            return (n > 0) to if (n > 0) "Blued: 命中 $n 项" else "Blued: 空"
        }
        // 3) 布尔
        if (raw is Boolean) return raw to "Blued: $raw"
        // 4) 字符串 / 数字 统一按字符串处理
        val s = raw.toString().trim()
        val low = s.lowercase()
        val SAFE_STR = setOf("", "0", "-1", "false", "no", "none", "null", "unknown",
            "stopped", "未检测到", "关闭", "未开启", "未使用", "[]", "0.0")
        var hit = low !in SAFE_STR
        low.toIntOrNull()?.let { hit = it > 0 }

        // 5) 特殊语义字段 (值=1 反而代表 "安全", 之前全判反了)
        when (key) {
            "adbSecure", "adb_secure", "ro_secure" ->
                hit = (low == "0" || low == "false")          // 1=安全, 0=风险
            "adbdState", "adb_state" ->
                hit = (low == "running" || low == "start" || low == "on" || low == "1")  // stopped=安全
            "adbPort" -> hit = s.isNotEmpty()                  // 有端口=风险
            "ro_adb_root", "ro_debug", "ro_kernel_qemu" ->
                hit = (low == "1")                              // -1/0=安全
        }
        return hit to "Blued: $s"
    }

    /** 解析 Blued 采集 JSON, 输出 Blued 的风控判定 (原模块核心逻辑的移植) */
    private fun parseBluedRisk(json: String?): List<Check> {
        if (json.isNullOrBlank()) return emptyList()
        val root = try {
            JSONObject(json)
        } catch (e: Throwable) {
            // 尝试截取第一个 {...}
            val s = json.indexOf('{'); val e2 = json.lastIndexOf('}')
            if (s in 0 until e2) try { JSONObject(json.substring(s, e2 + 1)) } catch (e3: Throwable) { return emptyList() }
            else return emptyList()
        }
        val found = LinkedHashMap<String, Any?>()
        collectRiskKeys(root, found)

        // 同一检测项可能出现多个 key (如 rootFlag + root), 去重保留最严重的(命中优先)
        val byItem = LinkedHashMap<String, Check>()
        for ((key, raw) in found) {
            val (category, item) = BLUED_RISK_KEYS[key] ?: continue
            val (hit, detail) = classifyRisk(key, raw)
            val prev = byItem[item]
            if (prev == null || (hit && !prev.hit)) {
                byItem[item] = Check(category, item, hit, detail)
            }
        }
        // 顶层 reason_ls_posed / reason (某些版本 Blued 用顶层字段, 本设备在包装对象内已带 reason)
        val reasonRaw = found["reason_ls_posed"] ?: found["reason"]
        if (reasonRaw is String && reasonRaw.isNotBlank()) {
            val rHit = reasonRaw.trim().lowercase() !in setOf("", "0", "false", "none", "null")
            byItem["Hook/注入检测"] = byItem["Hook/注入检测"]?.copy(detail = "Blued: $reasonRaw")
                ?: Check("Hook", "Hook/注入检测", rHit, "Blued: $reasonRaw")
        }
        return byItem.values.toList()
    }

    // =========================================================================
    //  三、检测结果弹窗 (iOS 风格)
    // =========================================================================
    private fun prettyJson(raw: String): String? {
        val trimmed = raw.trim()
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> trimmed
            }
        } catch (e: Throwable) {
            // 可能是带前缀的 JSON, 尝试截取第一个 { 到最后一个 }
            try {
                val s = trimmed.indexOf('{')
                val e2 = trimmed.lastIndexOf('}')
                if (s in 0 until e2) JSONObject(trimmed.substring(s, e2 + 1)).toString(2) else raw
            } catch (e3: Throwable) { raw }
        }
    }

    private fun copyToClipboard(ctx: Context, label: String, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        } catch (t: Throwable) {
            Log.e(TAG, "复制失败: ${t.message}")
        }
    }
}
