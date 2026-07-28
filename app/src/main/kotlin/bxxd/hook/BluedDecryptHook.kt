package bxxd.hook // 保持你的包名不变

import android.content.Context
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Blued 网络流量解密 Hook。
 *
 *  挂三个监控点:
 *   1) 底层 AES-GCM 解密函数 c.java#I111I1lI1I1 → 拿到解密后明文 + 请求 URL
 *   2) 上层原始响应分发器 b.java#I111I1lI1I1  → 拿到原始 URL + 原始响应体
 *   3) OkHttp Request.Builder.build()           → 关联原请求 method/url/auth/ua/body,
 *                                                   供"改UA重发" [replayRequest]
 *
 *  除打到 Xposed 日志外, 同时把响应存入内存环形缓冲 (最多 [MAX_BUFFER] 条),
 *  供 com.example.ui.NetworkCaptureScreen 实时浏览 (网络抓包查看器)。
 *
 *  能力:
 *   - 实时抓包 (明文 / 原始 / 强制解密 en_data)
 *   - 手动解密器 [manualDecrypt]
 *   - 改 UA 重发 [replayRequest] —— 换 UA 重放请求, 自动解密 en_data 响应
 *
 *  默认捕获关闭 (性能考虑), 用户在抓包页打开开关后才开始记录。
 */
object BluedDecryptHook {

    /** 单条抓包记录。 */
    data class Packet(
        val id: Long,                    // 自增序号, 用于列表 key + 排序
        val url: String,                 // 请求 URL
        val body: String,                // 明文响应体
        val source: String,              // "解密明文" / "原始响应" / "强制解密"
        val timestamp: Long,             // elapsedRealtime 毫秒
        val wallTime: String,            // yyyy-MM-dd HH:mm:ss.SSS (便于人读)
        // —— 重发(改UA)所需的原请求信息 (由 OkHttp build() hook 关联填充, 缺省容错) ——
        val method: String = "GET",      // HTTP 方法
        val authToken: String = "",      // Authorization 头 (原请求所用)
        val userAgent: String = "",      // User-Agent 头 (原请求所用, UI 可编辑后重发)
        val requestBody: String = "",    // 请求体 (POST 用, 文本)
        val contentType: String = ""     // 请求体 Content-Type (POST 用)
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

    /** 宿主内 b.java / c.java 类引用 (Init 时缓存, 供强制解密复用, 避免反复 findClass)。 */
    @Volatile
    private var bClassRef: Class<*>? = null

    @Volatile
    private var cClassRef: Class<*>? = null

    // ========================================================================
    //  原请求信息缓存 (供"改UA重发"使用)
    //  ---------------------------------------------------------------------
    //  监控点2 拿到的是响应头, 拿不到 Authorization/User-Agent 等请求头。
    //  因此在 OkHttp Request.Builder.build() 时把每条请求的 method/url/headers/body
    //  记一份 (以 url 为 key, LRU), 响应到来时按 url 关联到 Packet。
    // ========================================================================

    /** 单条原请求信息 (build 时捕获)。 */
    data class RequestInfo(
        val url: String,
        val method: String,
        val auth: String,
        val ua: String,
        val body: String,
        val contentType: String
    )

    /** url → 最近一次请求 (LRU, 上限 200 条, 防内存膨胀)。 */
    private val recentRequests = object : LinkedHashMap<String, RequestInfo>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RequestInfo>): Boolean = size > 200
    }
    private val requestLock = Any()

    /** 全局兜底的 auth/ua (build hook 顺带刷新; 重发时若某包未关联到原请求则用此)。 */
    @Volatile private var cachedAuth: String = ""
    @Volatile private var cachedUa: String = ""

    private fun rememberRequest(info: RequestInfo) = synchronized(requestLock) {
        recentRequests[info.url] = info
    }

    private fun lookupRequest(url: String): RequestInfo? = synchronized(requestLock) { recentRequests[url] }

    /** UI 查询: 当前兜底 UA (供重发面板默认值)。 */
    fun getCachedUserAgent(): String = cachedUa

    /** UI 查询: 当前兜底 Authorization (供重发面板默认值)。 */
    fun getCachedAuth(): String =
        if (cachedAuth.isNotEmpty()) cachedAuth else Config.getAuthToken(null)

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

    // ========================================================================
    //  强制解密 (en_data)
    //  ---------------------------------------------------------------------
    //  服务器对部分接口返回 {"en_data":"..."} 加密响应。正常情况下 App 会调用
    //  c.java#I111I1lI1I1 解密 (监控点1 已能抓到明文)。但有些接口 App 本身
    //  不解密 (例如后台预取 / 列表懒加载), 此时监控点1 抓不到。这里提供:
    //    1) tryDecryptRawResponse: 抓包页对每条原始响应自动尝试解密
    //    2) manualDecrypt        : UI 解密器, 用户手动粘贴 en_data 解密
    //  密钥取自 b.java 静态字段 l1l1l1l1 (协商后的 AES key)。
    // ========================================================================

    /** 手动解密结果。 */
    data class DecryptResult(
        val success: Boolean,        // 是否解密成功
        val plaintext: String?,      // 解密明文 (success=true 时非空)
        val error: String?,          // 失败原因 (success=false 时非空)
        val keyReady: Boolean,       // 内存密钥是否就绪
        val enDataLength: Int,       // 识别出的 en_data 长度
        val usedUrl: String          // 解密时使用的 URL (AAD)
    )

    /** 查询内存协商密钥是否就绪 (UI 状态提示用)。 */
    fun isKeyReady(): Boolean = runCatching {
        val bClass = bClassRef ?: return false
        ((XposedHelpers.getStaticObjectField(bClass, "l1l1l1l1") as? ByteArray)?.isNotEmpty() == true)
    }.getOrDefault(false)

    /**
     * 从输入中智能提取 en_data:
     *  - 若是含 "en_data" 字段的 JSON → 取其值
     *  - 否则把整段非空文本当作 en_data 本体
     */
    fun extractEnData(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        // 情况1: JSON 含 en_data 字段
        runCatching {
            val obj = JSONObject(trimmed)
            if (obj.has("en_data")) {
                return obj.optString("en_data")
            }
        }
        // 情况2: 整段当作 en_data
        return trimmed
    }

    /**
     * 主动解密单段 en_data (内部核心, 不做 JSON 解析)。
     * @return 解密明文, 失败 null
     */
    private fun decryptEnDataCore(enData: String, url: String): String? {
        val bClass = bClassRef ?: return null
        val cClass = cClassRef ?: return null
        val secretKey = runCatching {
            XposedHelpers.getStaticObjectField(bClass, "l1l1l1l1") as? ByteArray
        }.getOrNull()
        if (secretKey == null || secretKey.isEmpty()) return null
        return runCatching {
            XposedHelpers.callStaticMethod(cClass, "I111I1lI1I1", enData, secretKey, url) as? String
        }.getOrNull()
    }

    /**
     * 对一条原始响应 JSON 尝试强制解密。
     * @return Pair(enData, 明文); 不含 en_data 或解密失败返回 null
     */
    fun tryDecryptRawResponse(url: String, rawJson: String): Pair<String, String>? {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        if (!obj.has("en_data")) return null
        val enData = obj.optString("en_data")
        if (enData.isEmpty()) return null
        val plain = decryptEnDataCore(enData, url) ?: return null
        return enData to plain
    }

    /**
     * 手动解密 (供 UI 解密器调用)。
     * @param input en_data 值, 或含 en_data 字段的完整 JSON 响应体
     * @param url   原始请求 URL (AES-GCM 的 AAD, 必须与抓包时一致; 留空则尝试空 AAD)
     */
    fun manualDecrypt(input: String, url: String): DecryptResult {
        if (bClassRef == null || cClassRef == null) {
            return DecryptResult(false, null, "解密类未加载 (当前非蓝蓝宿主进程, 无法解密)", false, 0, url)
        }
        val enData = extractEnData(input)
            ?: return DecryptResult(false, null, "无法识别 en_data (请粘贴 en_data 值或含 en_data 的完整响应体)", false, 0, url)

        val keyReady = isKeyReady()
        if (!keyReady) {
            return DecryptResult(false, null,
                "内存中未找到 AES 密钥 (b.l1l1l1l1 为空)。\n请到手机设置 → 应用管理 → 强行停止蓝蓝, 重新打开让它完成握手。",
                false, enData.length, url)
        }
        return runCatching {
            val plain = decryptEnDataCore(enData, url)
            if (plain != null) {
                DecryptResult(true, plain, null, true, enData.length, url)
            } else {
                DecryptResult(false, null,
                    "解密返回空。常见原因:\n• URL(AAD) 不匹配 → 请填入抓包时该接口的完整 URL\n• en_data 已被服务端轮换/过期",
                    true, enData.length, url)
            }
        }.getOrElse {
            DecryptResult(false, null, "解密异常: ${it.message}", true, enData.length, url)
        }
    }

    // ========================================================================
    //  改 UA 重发
    //  ---------------------------------------------------------------------
    //  在蓝蓝进程内用 HttpURLConnection 原样重放某条请求 (只改 UA), 拿到服务器
    //  原始响应 → 若含 en_data 则就地用内存密钥解密。验证"换 UA 后服务端是否
    //  返回不同内容 / 是否触发风控 / 加密响应能否解密"。
    // ========================================================================

    /** 重发结果。 */
    data class ReplayResult(
        val success: Boolean,          // HTTP 请求是否成功发出并拿到响应
        val httpCode: Int,             // HTTP 状态码 (-1 表示未拿到)
        val rawBody: String?,          // 服务器原始响应体 (未解密)
        val decrypted: DecryptResult?, // 若含 en_data 则为解密结果, 否则 null
        val latencyMs: Long,           // 耗时
        val error: String?             // 失败原因
    )

    /**
     * 改 UA 重发某条已抓包的请求。
     * @param pkt 原抓包记录 (提供 url/method/auth/body/content-type)
     * @param newUa 新 User-Agent
     * @param newAuth 可选, 覆盖 Authorization (空则用原请求的)
     */
    fun replayRequest(
        pkt: Packet, newUa: String, newAuth: String = ""
    ): ReplayResult {
        val url = pkt.url
        if (url.isBlank() || url == "(unknown)") {
            return ReplayResult(false, -1, null, null, 0, "无有效 URL")
        }
        val method = pkt.method.ifBlank { "GET" }.uppercase()
        val auth = if (newAuth.isNotBlank()) newAuth else pkt.authToken
        val started = SystemClock.elapsedRealtime()
        return runCatching {
            val u = URL(url)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = false
                setRequestProperty("Host", u.host)
                if (auth.isNotBlank()) setRequestProperty("authorization", auth)
                setRequestProperty("user-agent", newUa.ifBlank { pkt.userAgent })
                // 显式不要 gzip, 拿到的就是明文字节, 免去解压
                setRequestProperty("Accept-Encoding", "identity")
            }
            // POST / PUT 等带 body 的方法
            val bodyBytes = pkt.requestBody.toByteArray(Charsets.UTF_8)
            if (method != "GET" && method != "HEAD" && pkt.requestBody.isNotEmpty()) {
                conn.doOutput = true
                if (pkt.contentType.isNotBlank())
                    conn.setRequestProperty("Content-Type", pkt.contentType)
                conn.outputStream.use { it.write(bodyBytes) }
            }
            val code = runCatching { conn.responseCode }.getOrDefault(-1)
            // ≥400 时 inputStream 会抛, 回退到 errorStream; 同时以 runCatching 免整个重发崩
            val body = runCatching {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.recoverCatching {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }.getOrDefault("")
            conn.disconnect()
            val latency = SystemClock.elapsedRealtime() - started
            // 含 en_data 则就地解密 (复用原 url 作为 AAD)
            val dec = if (body.contains("en_data")) manualDecrypt(body, url) else null
            ReplayResult(true, code, body, dec, latency, null)
        }.getOrElse {
            ReplayResult(false, -1, null, null, SystemClock.elapsedRealtime() - started,
                it.message ?: it.toString())
        }
    }

    /**
     * 把重发结果作为新条目存入抓包缓冲 (供列表展示, 点击查看详情)。
     *
     * 重发是用户主动操作, 不受 [captureEnabled] 影响 (确保用户总能看到自己重发的结果):
     *  - 成功的原始响应 → 存为 "重发原始" (青色徽章)
     *  - 若含 en_data 且解密成功 → 额外存一条 "重发明文" (紫色徽章)
     *
     * @param origPkt 原抓包记录 (提供 url/method/body/content-type)
     * @param result  [replayRequest] 的返回值
     * @param usedUa  本次重发使用的 User-Agent (存入记录, 区分于原请求 UA)
     */
    fun appendReplayResult(origPkt: Packet, result: ReplayResult, usedUa: String) {
        if (!result.success) return
        val rawBody = result.rawBody
        if (rawBody.isNullOrBlank()) return

        val now = SystemClock.elapsedRealtime()
        val wall = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        fun truncate(s: String) =
            if (s.length > MAX_BODY_CHARS) s.take(MAX_BODY_CHARS) + "\n…[已截断]" else s

        // 1) 重发原始响应
        val rawPkt = Packet(
            id = seq.incrementAndGet(),
            url = origPkt.url,
            body = truncate(rawBody),
            source = "重发原始",
            timestamp = now,
            wallTime = "$wall 🔁",
            method = origPkt.method,
            authToken = origPkt.authToken,
            userAgent = usedUa,
            requestBody = origPkt.requestBody,
            contentType = origPkt.contentType
        )
        synchronized(bufferLock) {
            if (buffer.size >= MAX_BUFFER) buffer.pollFirst()
            buffer.addLast(rawPkt)
        }

        // 2) 重发解密明文 (若 en_data 解密成功)
        result.decrypted?.let { dec ->
            if (dec.success && !dec.plaintext.isNullOrBlank()) {
                val plainPkt = rawPkt.copy(
                    id = seq.incrementAndGet(),
                    body = truncate(dec.plaintext),
                    source = "重发明文"
                )
                synchronized(bufferLock) {
                    if (buffer.size >= MAX_BUFFER) buffer.pollFirst()
                    buffer.addLast(plainPkt)
                }
            }
        }
    }

    /** 内部: 追加一条记录, 满则淘汰最旧。 */
    private fun append(url: String?, body: String?, source: String) {
        if (!captureEnabled) return
        if (url.isNullOrBlank() && body.isNullOrBlank()) return
        val safeBody = body ?: ""
        val truncated = if (safeBody.length > MAX_BODY_CHARS) safeBody.take(MAX_BODY_CHARS) + "\n…[已截断]" else safeBody
        // 按 url 关联原请求信息 (method/auth/ua/body), 缺省则用全局兼底
        val req = if (url != null) lookupRequest(url) else null
        val pkt = Packet(
            id = seq.incrementAndGet(),
            url = url ?: "(unknown)",
            body = truncated,
            source = source,
            timestamp = SystemClock.elapsedRealtime(),
            wallTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date()),
            method = req?.method ?: "GET",
            authToken = req?.auth ?: cachedAuth,
            userAgent = req?.ua ?: cachedUa,
            requestBody = req?.body ?: "",
            contentType = req?.contentType ?: ""
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

        // 缓存 b.java / c.java 类引用 (强制解密复用, 避免反复 findClass)
        bClassRef = runCatching {
            XposedHelpers.findClass("com.blued.android.http.encode.utils.b", lpparam.classLoader)
        }.getOrNull()
        cClassRef = runCatching {
            XposedHelpers.findClass("com.blued.android.http.encode.utils.c", lpparam.classLoader)
        }.getOrNull()
        XposedBridge.log("🔵 [蓝蓝Hook] 类引用缓存: b=${bClassRef != null} c=${cClassRef != null}")

        // ==========================================
        // 监控点 3：OkHttp Request.Builder.build() —— 关联原请求信息
        //   捕获每条请求的 method/url/Authorization/User-Agent/body,
        //   以 url 为 key 存入 LRU; 响应到来时按 url 关联到 Packet, 供"改UA重发"。
        //   关闭捕获时零开销 (不读 body)。
        // ==========================================
        try {
            val okhttpBuilderClass =
                XposedHelpers.findClass("okhttp3.Request\$Builder", lpparam.classLoader)
            XposedBridge.hookAllMethods(okhttpBuilderClass, "build", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!captureEnabled) return
                    runCatching {
                        val request = param.result ?: return
                        val urlStr = (XposedHelpers.callMethod(request, "url")?.toString()) ?: return
                        val method = (XposedHelpers.callMethod(request, "method") as? String) ?: "GET"
                        // 读取请求头
                        var auth = ""
                        var ua = ""
                        val headers = XposedHelpers.callMethod(request, "headers")
                        if (headers != null) {
                            val names = XposedHelpers.callMethod(headers, "names") as? Set<*>
                            names?.forEach { n ->
                                val name = n as? String ?: return@forEach
                                val v = XposedHelpers.callMethod(headers, "get", name) as? String ?: return@forEach
                                when (name.lowercase(Locale.getDefault())) {
                                    "authorization" -> auth = v
                                    "user-agent" -> ua = v
                                }
                            }
                        }
                        // 全局兼底刷新
                        if (auth.isNotEmpty()) cachedAuth = auth
                        if (ua.isNotEmpty()) cachedUa = ua
                        // 读取请求体 (POST 等; GET 为 null)
                        var bodyStr = ""
                        var ct = ""
                        val reqBody = XposedHelpers.callMethod(request, "body")
                        if (reqBody != null) {
                            ct = XposedHelpers.callMethod(reqBody, "contentType")?.toString() ?: ""
                            val bufferClass = XposedHelpers.findClass("okhttp3.Buffer", lpparam.classLoader)
                            val buffer = bufferClass.getDeclaredConstructor().newInstance()
                            XposedHelpers.callMethod(reqBody, "writeTo", buffer)
                            val bytes = XposedHelpers.callMethod(buffer, "readByteArray") as? ByteArray
                            if (bytes != null) bodyStr = String(bytes, Charsets.UTF_8)
                        }
                        rememberRequest(RequestInfo(urlStr, method, auth, ua, bodyStr, ct))
                    }
                }
            })
            XposedBridge.log("🔵 [蓝蓝Hook] (监控点3) OkHttp build() 关联器挂载成功！")
        } catch (e: Throwable) {
            XposedBridge.log("🔵 [蓝蓝Hook] (监控点3) build() 关联器挂载失败: ${e.message}")
        }

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

                        // ★ 强制解密: 含 en_data 的响应主动解密一份明文
                        //   (App 自身不解密的接口也能看到明文)
                        if (rawJson.contains("en_data")) {
                            val result = tryDecryptRawResponse(url, rawJson)
                            if (result != null) {
                                append(url, result.second, "强制解密")
                                XposedBridge.log("🔓 [强制解密] en_data 已解密 (${result.first.length} 字符)")
                                XposedBridge.log("✅ 明文: ${result.second}")
                            } else {
                                XposedBridge.log("⚠️ [强制解密] en_data 解密失败 (密钥未就绪 或 URL/AAD 不匹配)")
                            }
                        }
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
