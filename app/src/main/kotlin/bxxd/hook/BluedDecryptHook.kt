package bxxd.hook // 保持你的包名不变

import android.content.Context
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Blued 网络流量解密 Hook。
 *
 *  挂两个监控点:
 *   1) 底层 AES-GCM 解密函数 c.java#I111I1lI1I1 → 拿到解密后明文 + 请求 URL
 *   2) 上层原始响应分发器 b.java#I111I1lI1I1  → 拿到原始 URL + 原始响应体
 *
 *  除打到 Xposed 日志外, 同时把明文响应存入内存环形缓冲 (最多 [MAX_BUFFER] 条),
 *  供 com.example.ui.NetworkCaptureScreen 实时浏览 (网络抓包查看器)。
 *
 *  默认捕获关闭 (性能考虑), 用户在抓包页打开开关后才开始记录。
 */
object BluedDecryptHook {

    /** 单条抓包记录。 */
    data class Packet(
        val id: Long,                    // 自增序号, 用于列表 key + 排序
        val url: String,                 // 请求 URL
        val body: String,                // 明文响应体
        val source: String,              // "解密明文" / "原始响应"
        val timestamp: Long,             // elapsedRealtime 毫秒
        val wallTime: String             // yyyy-MM-dd HH:mm:ss.SSS (便于人读)
    )

    /** 内存环形缓冲上限 (条)。超过则丢弃最旧的。 */
    private const val MAX_BUFFER = 300

    /** 单条 body 截断上限 (字符), 防止超长响应吃满内存。 */
    private const val MAX_BODY_CHARS = 64_000

    private val buffer = ArrayDeque<Packet>()
    private val bufferLock = Any()
    private val seq = AtomicLong(0L)

    /** 捕获开关 (内存缓存, 由 Config 持久化)。默认 false。 */
    @Volatile
    private var captureEnabled: Boolean = false

    /** UI 查询: 捕获是否开启。 */
    fun isCaptureEnabled(): Boolean = captureEnabled

    /** UI 切换: 同时更新内存 + 持久化。 */
    fun setCaptureEnabled(enabled: Boolean, ctx: Context) {
        captureEnabled = enabled
        Config.setRaw("net_capture_enabled", enabled.toString(), ctx)
    }

    /** 取当前缓冲快照 (新→旧), 线程安全拷贝。 */
    fun getCapturedPackets(): List<Packet> = synchronized(bufferLock) {
        buffer.toList().reversed()
    }

    /** 当前缓冲条数。 */
    fun getCaptureCount(): Int = synchronized(bufferLock) { buffer.size }

    /** 清空缓冲。 */
    fun clearCaptured() = synchronized(bufferLock) { buffer.clear() }

    /** 内部: 追加一条记录, 满则淘汰最旧。 */
    private fun append(url: String?, body: String?, source: String) {
        if (!captureEnabled) return
        if (url.isNullOrBlank() && body.isNullOrBlank()) return
        val safeBody = body ?: ""
        val truncated = if (safeBody.length > MAX_BODY_CHARS) safeBody.take(MAX_BODY_CHARS) + "\n…[已截断]" else safeBody
        val pkt = Packet(
            id = seq.incrementAndGet(),
            url = url ?: "(unknown)",
            body = truncated,
            source = source,
            timestamp = SystemClock.elapsedRealtime(),
            wallTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        )
        synchronized(bufferLock) {
            if (buffer.size >= MAX_BUFFER) buffer.pollFirst()
            buffer.addLast(pkt)
        }
    }

    fun Init(lpparam: LoadPackageParam) {
        if (!lpparam.packageName.contains("blued")) {
            return
        }

        // 启动时从持久化加载标志 (宿主 UI 设置过则本次会话默认开启)
        runCatching {
            captureEnabled = Config.getRaw("net_capture_enabled", "false") == "true"
        }

        XposedBridge.log("🔵 [蓝蓝Hook] 成功注入进程: ${lpparam.packageName} (captureEnabled=$captureEnabled)")

        // ==========================================
        // 监控点 1：底层 AES-GCM 解密函数 (c.java)
        // ==========================================
        try {
            XposedHelpers.findAndHookMethod(
                "com.blued.android.http.encode.utils.c",
                lpparam.classLoader,
                "I111I1lI1I1",
                String::class.java,
                ByteArray::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 关闭时零开销: 立即返回, 不读参数不打日志
                        if (!captureEnabled) return
                        try {
                            val plainText = param.result as? String
                            val requestUrl = param.args[2] as? String

                            append(requestUrl, plainText, "解密明文")
                            XposedBridge.log("========== 🔵 蓝蓝响应解密成功 ==========")
                            XposedBridge.log("👉 URL: $requestUrl")
                            XposedBridge.log("✅ 明文: $plainText")
                            XposedBridge.log("=========================================")
                        } catch (e: Exception) {
                            XposedBridge.log("🔵 解析异常: ${e.message}")
                        }
                    }
                }
            )
            XposedBridge.log("🔵 [蓝蓝Hook] (监控点1) 解密函数挂载成功！")
        } catch (e: Throwable) {
            XposedBridge.log("🔵 [蓝蓝Hook] (监控点1) 挂载失败: ${e.message}")
        }

        // ==========================================
        // 监控点 2：上层原始网络响应分发器 (b.java)
        // ==========================================
        try {
            val bClass = XposedHelpers.findClass("com.blued.android.http.encode.utils.b", lpparam.classLoader)
            // 使用 hookAllMethods 避免 okhttp3.Headers 导包导致找不到方法的异常
            XposedBridge.hookAllMethods(bClass, "I111I1lI1I1", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 关闭时零开销
                    if (!captureEnabled) return
                    // 精准匹配 b.java 里的 public static DR I111I1lI1I1(String str, Headers headers, String str2)
                    if (param.args.size == 3 && param.args[0] is String && param.args[2] is String) {
                        val url = param.args[0] as String
                        val rawJson = param.args[2] as String

                        append(url, rawJson, "原始响应")
                        XposedBridge.log("====== 📥 收到原始网络响应 ======")
                        XposedBridge.log("URL: $url")
                        XposedBridge.log("Raw Data (服务器原封不动返回的数据): $rawJson")
                        XposedBridge.log("==================================")
                    }
                }
            })
            XposedBridge.log("🔵 [蓝蓝Hook] (监控点2) 原始响应分发器挂载成功！")
        } catch (e: Throwable) {
            XposedBridge.log("🔵 [蓝蓝Hook] (监控点2) 分发器挂载失败: ${e.message}")
        }
    }
}
