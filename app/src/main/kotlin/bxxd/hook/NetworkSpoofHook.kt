package bxxd.hook

import android.os.Build
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val SPOOF_TAG = "llhook-NetworkSpoof"

/**
 * ============================================================================
 *  网络伪装 (NetworkSpoofHook) — v4
 * ============================================================================
 *  ★ 历史教训 ★:
 *    v1/v2 全局改 UA → 资料页 IP 丢失、访客/闪照挂。
 *    v3 完全不改 UA → 列表 role 全显示"其他" (列表 role 依赖 app/7)。
 *
 *  ★ v4 精准方案 (既要 role 又要 IP) ★:
 *    列表/详情请求 (host=argo.blued.cn, path 含 /users) → 改 app/7 → role 真实。
 *    资料页请求 (host=social.irisgw.cn/users/$uid) → 保持 app/1 → IP 正常。
 *    两类请求 host 不同, 用 URL 子串 "argo.blued.cn/users" 精准区分, 互不干扰。
 *
 *  开关 KEY_ENABLED: 关闭则不改写任何请求 (全 app/1, role 不显示但 IP 全正常)。
 *  capturedLatestUA: 始终捕获 (供 NearbyRoleHook/ChatWatermarkHook 自发查询复用)。
 * ============================================================================
 */
object NetworkSpoofHook : BaseHook {

    const val KEY_ENABLED = "switch_spoof_lite"

    // 命中此 URL 子串的请求改 app/7 (列表附近/在线 + 详情 /users/$uid/basic)。
    // 资料页 host 是 social.irisgw.cn, 不会被命中 → 保持 app/1, IP 正常。
    private const val SPOOF_PATTERN = "argo.blued.cn/users"
    // ★ visited 请求降级: 新版 social.irisgw.cn (bcencode 加密 + app/1→role=-1)
    //   → 旧版 social.blued.cn (明文 JSON + app/7→真实 role)
    private const val VISITED_HOST = "social.irisgw.cn"
    private const val VISITED_PATH = "/visited"
    // visited 请求诊断去重 (每个 URL 首次打印, 避免刷屏; 全部进 LSPosed 日志)
    private val seenVisitedUrls = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    var capturedLatestUA: String = ""

    /** app/1 → app/7 UA 变换。非 app/1 返回 null。 */
    private fun transformToLite(ua: String): String? {
        if (!ua.contains("app/1")) return null
        var out = ua.replace("app/1", "app/7")
        out = Regex("Android/[\\d_\\.]+").replace(out, "Android/070647_7.64.7_2842_0221")
        return out
    }

    /** 兜底构造 app/7 UA (transformToLite 失败/UA 缺失时使用)。 */
    private fun makeApp7UA(): String {
        val r = Build.VERSION.RELEASE ?: "13"
        val m = Build.MODEL ?: "X"
        val id = Build.ID ?: "X"
        return "Mozilla/5.0 (Linux; U; Android $r; $m Build/$id) Android/070647_7.64.7_2842_0221 (Asia/Shanghai) Dalvik/2.1.0 app/7"
    }

    /** 读 builder 当前 User-Agent header (多重兑底, 读不到返回 "")。 */
    private fun readBuilderUA(builder: Any): String {
        try {
            val headers = XposedHelpers.getObjectField(builder, "headers") ?: return ""
            val v = XposedHelpers.callMethod(headers, "get", "User-Agent") as? String
            if (!v.isNullOrEmpty()) return v
        } catch (_: Throwable) {}
        return ""
    }

    /** 写 builder 的 User-Agent header, 多重兑底确保写入。返回是否成功。 */
    private fun setBuilderUA(builder: Any, ua: String): Boolean {
        try { XposedHelpers.callMethod(builder, "header", "User-Agent", ua); return true } catch (_: Throwable) {}
        try {
            val headers = XposedHelpers.getObjectField(builder, "headers") ?: return false
            XposedHelpers.callMethod(headers, "set", "User-Agent", ua); return true
        } catch (_: Throwable) {}
        try {
            val headers = XposedHelpers.getObjectField(builder, "headers") ?: return false
            XposedHelpers.callMethod(headers, "add", "User-Agent", ua); return true
        } catch (_: Throwable) {}
        return false
    }

    /**
     * 规范化 visited URL 到旧版可用格式:
     *   - host: social.irisgw.cn → social.blued.cn
     *   - conn_type: → 6   (新版=2 服务端走加密/role=-1; 旧版=6 返回真实 role)
     *   - channel : → a9999a (旧版可用渠道)
     * 返回 null 表示无需修改 (已是旧版格式)。
     */
    private fun normalizeVisitedUrl(urlStr: String): String? {
        var u = urlStr
        var changed = false
        // host
        if (u.contains("social.irisgw.cn", ignoreCase = true)) {
            u = u.replace("social.irisgw.cn", "social.blued.cn", ignoreCase = true); changed = true
        }
        // conn_type (原值任意 → 6; 缺失则不动)
        val ct = Regex("conn_type=[^&]*", RegexOption.IGNORE_CASE)
        if (ct.containsMatchIn(u)) {
            val m = ct.find(u)!!
            if (!m.value.equals("conn_type=6", ignoreCase = true)) { u = ct.replace(u, "conn_type=6"); changed = true }
        }
        // channel (原值任意 → a9999a; 缺失则不动)
        val ch = Regex("channel=[^&]*", RegexOption.IGNORE_CASE)
        if (ch.containsMatchIn(u)) {
            val m = ch.find(u)!!
            if (!m.value.equals("channel=a9999a", ignoreCase = true)) { u = ch.replace(u, "channel=a9999a"); changed = true }
        }
        return if (changed) u else null
    }

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val builderClass = try {
            lpparam.classLoader.loadClass("okhttp3.Request\$Builder")
        } catch (t: Throwable) {
            XposedBridge.log("$SPOOF_TAG ❌ okhttp3.Request.Builder 未找到: $t")
            return
        }

        // ① header()/addHeader(): 捕获 UA (始终) + 命中列表/详情则改写 app/7 + visited 修正版本号
        val captureLogic = hookLogic@ { param: XC_MethodHook.MethodHookParam ->
            val key = param.args[0] as? String ?: return@hookLogic
            val value = param.args[1] as? String ?: return@hookLogic
            if (!key.equals("User-Agent", ignoreCase = true) && !key.equals("ua", ignoreCase = true)) return@hookLogic
            // 始终捕获极速版 UA (供自发查询复用)
            transformToLite(value)?.let { capturedLatestUA = it }
            // 开关关闭则不改写出站请求
            if (!Config.isFeatureEnabled(KEY_ENABLED)) return@hookLogic
            // 读 builder.url 判断接口类型
            val urlLower = try {
                XposedHelpers.getObjectField(param.thisObject, "url")?.toString()?.lowercase()
            } catch (e: Throwable) { null } ?: return@hookLogic

            // ★ visited 请求: 强制 UA 为已知可用版本号 (070647_7.64.7)
            //   APP 的 OkHttp 拦截器会在 build() 后重建请求, 把 UA 版本号重算为真实 APP 版本
            //   (300237_0.23.7)。但 social.blued.cn 服务端只认旧极速版号 070647_7.64.7 才返回真实 role。
            //   → 在 header()/addHeader() 写入点拦截: 只要 URL 是 visited 且 host 是 blued.cn/irisgw.cn,
            //     不管 APP 写什么 UA, 都强制覆写成 makeApp7UA() (070647_7.64.7 + app/7)。
            if (urlLower.contains(VISITED_PATH) &&
                (urlLower.contains("social.blued.cn") || urlLower.contains("social.irisgw.cn"))) {
                param.args[1] = makeApp7UA()
                return@hookLogic
            }

            // 列表/详情 (argo.blued.cn/users): UA 改 app/7
            if (!urlLower.contains(SPOOF_PATTERN)) return@hookLogic
            // 命中: 改 UA 为极速版
            transformToLite(value)?.let { lite ->
                param.args[1] = lite
            }
        }
        try {
            builderClass.findMethod { name == "header" && parameterTypes.size == 2 }.hookBefore { captureLogic(it) }
            builderClass.findMethod { name == "addHeader" && parameterTypes.size == 2 }.hookBefore { captureLogic(it) }
        } catch (t: Throwable) {
            XposedBridge.log("$SPOOF_TAG ❌ header/addHeader 挂载失败: $t")
            return
        }

        // ② build() 兜底: header 调用时 url 可能还没设置, build 时必已就绪
        try {
            XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val builder = param.thisObject
                    val urlStr = try {
                        XposedHelpers.getObjectField(builder, "url")?.toString()
                    } catch (e: Throwable) { null } ?: return
                    val urlLower = urlStr.lowercase()

                    // ★ 诊断 (不依赖开关, 进 LSPosed 日志): visited 相关请求首次完整打印
                    //   便于排查「APP 实际发的 URL 长啥样 / host 是否命中 / 开关状态」
                    if (urlLower.contains("visited")) {
                        val diagKey = "v:" + urlStr.substringBefore("?").lowercase()
                        if (seenVisitedUrls.add(diagKey)) {
                            val enabled = Config.isFeatureEnabled(KEY_ENABLED)
                            val hostHit = urlLower.contains(VISITED_HOST)
                            val pathHit = urlLower.contains(VISITED_PATH)
                            XposedBridge.log("$SPOOF_TAG [visited诊断] 开关=$enabled host命中=$hostHit path命中=$pathHit UA='${readBuilderUA(builder)}' url=$urlStr")
                        }
                    }

                    if (!Config.isFeatureEnabled(KEY_ENABLED)) return
                    // ★ visited 请求降级到旧版 (匹配旧版可用请求: social.blued.cn + app/7 + conn_type=6 + channel=a9999a)
                    //   新版 social.irisgw.cn 响应是 en_data 密文 (bcencode), role 恒为 -1。
                    //   关键: 用 path(/visited) 判定而非 host 判定 —— APP 的 OkHttp 拦截器会 rebuild 请求并重算
                    //   UA 版本号 (070647_7.64.7 → 300237_0.23.7), 首次改写后第二次 build 的 host 已是 blued.cn,
                    //   若用 host命中 判定会跳过 → UA版本/conn_type/channel 都不会再修正。故每次 build 都规范化,
                    //   最后一次 build (拦截器重建后) 的规范化生效 → 最终出站请求正确。
                    //   只动 visited 这一个请求, 资料页 social.irisgw.cn/users/$uid 等不受影响。
                    if (urlLower.contains(VISITED_PATH) &&
                        (urlLower.contains("social.irisgw.cn") || urlLower.contains("social.blued.cn"))) {
                        try {
                            val uaBefore = readBuilderUA(builder)
                            // 1. 规范化 URL: irisgw→blued + conn_type→6 + channel→a9999a
                            val newUrl = normalizeVisitedUrl(urlStr)
                            if (newUrl != null && newUrl != urlStr) {
                                val httpUrlClass = XposedHelpers.findClass("okhttp3.HttpUrl", builderClass.classLoader)
                                val parsed = XposedHelpers.callStaticMethod(httpUrlClass, "parse", newUrl)
                                if (parsed != null) XposedHelpers.setObjectField(builder, "url", parsed)
                            }
                            // 2. 强制 UA 为已知可用 app/7 (每次覆盖, 防拦截器重算版本号)
                            val knownUA = makeApp7UA()
                            val setOk = setBuilderUA(builder, knownUA)
                            capturedLatestUA = knownUA
                            val uaAfter = readBuilderUA(builder)
                            XposedBridge.log("$SPOOF_TAG ★visited降级 | UA改前='$uaBefore' | header调用=$setOk | UA改后='$uaAfter' | url=${newUrl ?: urlStr}")
                        } catch (t: Throwable) {
                            XposedBridge.log("$SPOOF_TAG visited降级失败: $t")
                        }
                        return
                    }

                    // 列表/详情 (argo.blued.cn/users): UA 改 app/7
                    if (!urlLower.contains(SPOOF_PATTERN)) return
                    try {
                        val headers = XposedHelpers.getObjectField(builder, "headers") ?: return
                        val ua = XposedHelpers.callMethod(headers, "get", "User-Agent") as? String ?: return
                        val lite = transformToLite(ua) ?: return  // 已是 app/7 或其他, 无需改
                        XposedHelpers.callMethod(builder, "header", "User-Agent", lite)
                        capturedLatestUA = lite
                    } catch (_: Throwable) {}
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$SPOOF_TAG ❌ build hook 失败: $t")
        }

        // ③ 终极拦截: okhttp3.Request.header(String) getter
        //    APP 的 OkHttp 拦截器在 build() 后会重建请求并重算 UA 版本号 (300237_0.23.7),
        //    即使在 Builder.header() 写入点覆盖也会被后续拦截器覆盖 → 最终出站 UA 仍是 300237_0.23.7。
        //    → hook 不可变 Request 对象的 UA getter: 只要请求是 visited (blued.cn/irisgw.cn),
        //      读 UA 时一律返回 makeApp7UA() (070647_7.64.7 + app/7)。这是发请求前最后的读取点, 必然生效。
        try {
            val requestClass = lpparam.classLoader.loadClass("okhttp3.Request")
            XposedBridge.hookAllMethods(requestClass, "header", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (!Config.isFeatureEnabled(KEY_ENABLED)) return
                        if (param.args.size != 1) return
                        val name = param.args[0] as? String ?: return
                        if (!name.equals("User-Agent", ignoreCase = true)) return
                        val req = param.thisObject
                        val urlLower = try {
                            XposedHelpers.callMethod(req, "url")?.toString()?.lowercase()
                        } catch (e: Throwable) { null } ?: return
                        if (urlLower.contains(VISITED_PATH) &&
                            (urlLower.contains("social.blued.cn") || urlLower.contains("social.irisgw.cn"))) {
                            val correct = makeApp7UA()
                            val orig = param.result as? String ?: ""
                            if (orig != correct) {
                                param.result = correct
                                XposedBridge.log("$SPOOF_TAG [Request.header getter] visited UA 读出覆写: '$orig' → '$correct'")
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.log("$SPOOF_TAG okhttp3.Request.header(String) getter hook 已挂载")
        } catch (t: Throwable) {
            XposedBridge.log("$SPOOF_TAG ❌ Request getter hook 失败: $t")
        }

        XposedBridge.log("$SPOOF_TAG v4 部署: 命中 '$SPOOF_PATTERN' 改 app/7 (列表/详情), 资料页 social.irisgw.cn 保持 app/1 保 IP")
    }
}
