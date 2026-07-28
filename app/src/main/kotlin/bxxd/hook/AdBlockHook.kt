package bxxd.hook

import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.net.InetAddress
import java.net.URL

/**
 * ============================================================================
 *  屏蔽广告类 —— 网络层广告 / 追踪请求拦截 (单一职责, 全局唯一入口)
 * ============================================================================
 *
 *  本类负责【屏蔽广告类】: 在网络底层拦截广告 / 追踪 / 语音交友 / 呼叫 API 请求,
 *  让它们连不上或打到回环地址。所有网络级拦截集中在此, 杜绝分散重复 hook。
 *
 *  入口开关 (各层独立控制, 默认全关, 零副作用):
 *   - switch_block_ads       : 广告/追踪域名 + 静态广告路径 (UI「去广告」)
 *   - purify_block_voice     : voice.blued.cn 语音交友断网 (KEY_BLOCK_VOICE)
 *   - purify_block_call      : /users/call 呼叫 API 拦截 (KEY_BLOCK_CALL)
 *   - switch_block_ad_api    : 用户自定义黑名单 (ad_blocklist_user)
 *  ★ 旧版 voice/call 硬编码「无论开关一律阻断」, 在 Blued 7.39 上误伤核心接口导致
 *    「网络错误」; 现已改为独立开关 (默认关, 不随净化总开关), 可在「净化中心」逐项控制。
 *    拦截命中时会打 [llhook-AdBlock] 去重日志, 便于定位哪个 host 被误拦。
 *
 *  联动「闪照免看广告」(switch_flash_ad_skip): 开启时自动放行激励视频广告 SDK
 *  域名 (穿山甲/优量汇/快手), 让「看视频获得一次机会」面板可正常拉取广告配置,
 *  配合 AntiRecallHook 跳过广告直接发奖; 其它广告域名照常拦截。
 *
 *  ── 三层拦截 (互为兜底, 覆盖任意网络栈) ──
 *   1. OkHttp 层: Request.Builder.build 改写 url → 127.0.0.1 (Blued 主请求栈)
 *   2. DNS 层  : InetAddress.getAllByName 返回回环 IP (解析阶段即废)
 *   3. URL 层  : URL.openConnection 抛 ConnectException (老旧/原生栈兜底)
 *
 *  ── 与 AdsHook 的分工 ──
 *   AdBlockHook = 屏蔽广告类 (网络层拦截请求)        ← 本文件
 *   AdsHook     = 屏蔽控件 id 类 (按 View ID 隐藏 UI) ← 见 AdsHook.kt
 * ============================================================================
 */
object AdBlockHook : BaseHook {

    // ===== 用户自定义广告接口黑名单 (可由 UI 添加/删除) =====
    // 总开关: switch_block_ad_api (开启后三层拦截会额外匹配下方用户黑名单)
    // 规则存储: ad_blocklist_user (JSON 数组: [{"p":"ads.x.com","e":true}])
    //   p=pattern(域名或URL片段, 小写模糊匹配), e=enabled(该条是否生效, 支持逐条独立开关)
    //   兼容旧版: 读到不以 '[' 开头的值视为旧换行格式, 自动迁移为 JSON(全部 enabled)
    const val KEY_USER_BLOCK_SWITCH = "switch_block_ad_api"
    const val KEY_USER_BLOCKLIST = "ad_blocklist_user"
    private const val TAG = "llhook-AdBlock"
    /** 语音交友 / 呼叫API 网络拦截的独立开关 (默认关; 不随净化总开关, 因断网可能误伤新版核心接口)。
     *  UI 在「净化中心」逐项控制 (purify_block_voice / purify_block_call)。 */
    const val KEY_BLOCK_VOICE = "purify_block_voice"
    const val KEY_BLOCK_CALL = "purify_block_call"
    /** 诊断: 已打印过拦截日志的 host/url (去重, 避免高频请求刷屏)。 */
    private val loggedHits = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** 一条用户自定义广告接口规则 (pattern + 是否启用)。 */
    data class AdRule(val pattern: String, val enabled: Boolean)

    /** 规则内存缓存 (2s TTL): 网络请求高频, 避免每次都读 SharedPreferences + 解析 JSON。
     *  UI 调 setUserRules 后会同步刷新缓存。 */
    @Volatile private var cachedRules: List<AdRule> = emptyList()
    @Volatile private var cachedRulesTs = 0L

    /** 读全部规则 (含未启用的), 供 UI CRUD 展示。带 2s 内存缓存 + 旧格式自动迁移。 */
    fun getUserRules(ctx: android.content.Context? = null): List<AdRule> {
        val now = System.currentTimeMillis()
        if (now - cachedRulesTs < 2000 && cachedRules.isNotEmpty()) return cachedRules
        val raw = Config.getRaw(KEY_USER_BLOCKLIST, "", ctx).trim()
        val rules = if (raw.isEmpty()) {
            emptyList()
        } else if (raw.startsWith("[")) {
            // 新 JSON 格式
            try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    AdRule(o.optString("p"), o.optBoolean("e", true))
                }.filter { it.pattern.isNotEmpty() }
            } catch (e: Throwable) { emptyList() }
        } else {
            // 旧换行格式 → 迁移为规则 (全部 enabled), 留待 setUserRules 写回新格式
            raw.split('\n', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { AdRule(it, true) }
        }
        synchronized(this) { cachedRules = rules; cachedRulesTs = now }
        return rules
    }

    /** 持久化全部规则 (UI CRUD 调用): 序列化为 JSON 写回 + 刷新内存缓存。
     *  内部去空 (pattern 为空的丢弃), 但保留大小写原样(匹配时再小写化)。 */
    fun setUserRules(rules: List<AdRule>, ctx: android.content.Context? = null) {
        val clean = rules.filter { it.pattern.trim().isNotEmpty() }
        val arr = org.json.JSONArray()
        clean.forEach { r ->
            arr.put(org.json.JSONObject().apply { put("p", r.pattern.trim()); put("e", r.enabled) })
        }
        Config.setRaw(KEY_USER_BLOCKLIST, arr.toString(), ctx)
        synchronized(this) {
            cachedRules = clean
            cachedRulesTs = System.currentTimeMillis()
        }
    }

    /** 仅返回【已启用】规则的 pattern (小写化), 供三层拦截匹配。
     *  - hook 侧调用; UI 不用这个 (UI 要看全部含未启用, 用 getUserRules)
     *  - 与内置 adKeywords 同为 contains 模糊匹配 */
    private fun enabledUserPatterns(): List<String> =
        getUserRules().filter { it.enabled }.map { it.pattern.lowercase() }

    /** 当前请求 url/host 是否命中用户自定义广告接口黑名单。
     *  - 内部已判总开关 switch_block_ad_api, 关闭时直接返回 false
     *  - 只匹配【已启用】的规则; s 可传完整 url 或 host, 内部统一小写后 contains 匹配 */
    private fun matchesUserBlocklist(s: String): Boolean {
        if (!Config.isFeatureEnabled(KEY_USER_BLOCK_SWITCH)) return false
        val list = enabledUserPatterns()
        if (list.isEmpty()) return false
        val lower = s.lowercase()
        return list.any { lower.contains(it) }
    }

    /**
     * 广告联盟 / 追踪器 / 骚扰域名黑名单 (host 模糊匹配, 小写)。
     * 维护: 新增广告域名在此追加一行即可, 三层拦截自动全部生效。
     *  (用户自定义的临时广告接口请走 switch_block_ad_api + getUserRules, 不要硬编码在此)
     */
    private val adKeywords = listOf(
        // === 老牌主流广告联盟 ===
        "pangolin",       // 穿山甲系列
        "pglstatp",       // 穿山甲数据统计
        "gdt.qq",         // 腾讯广点通
        "e.qq.com",       // 腾讯优量汇
        "mobads",         // 百度联盟
        "hm-nrj.baidu.com", // 百度统计/联盟 (Blued 实测命中)
        "kuaishou",       // 快手联盟
        "oceanengine",    // 巨量引擎
        "pstatp",         // 字节系通用广告
        "ad.toutiao"      // 头条广告
    )

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookOkHttpEngine(lpparam) // 1. OkHttp 主请求栈 (改写 url)
        hookDns()                 // 2. DNS 解析劫持 (返回回环 IP)
        hookUrlConnection()       // 3. URL.openConnection 兜底 (抛异常)
    }

    // ==========================================
    // 🌐 1. OkHttp 强力域名劫持 (Blued 主请求栈)
    // ==========================================
    private fun hookOkHttpEngine(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val builderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", lpparam.classLoader)
            if (builderClass != null) {
                XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // ★ 各入口独立开关 (默认全关, 零副作用); 全关时直返, 不读 url 不匹配
                        val blockAds = Config.isFeatureEnabled("switch_block_ads")
                        val blockVoice = Config.isFeatureEnabled(KEY_BLOCK_VOICE)
                        val blockCall = Config.isFeatureEnabled(KEY_BLOCK_CALL)
                        if (!blockAds && !blockVoice && !blockCall) return

                        val builder = param.thisObject
                        val urlObj = try { XposedHelpers.getObjectField(builder, "url") } catch (e: Throwable) { null }
                        val urlStr = urlObj?.toString()?.lowercase() ?: return

                        val isVoice = blockVoice && urlStr.contains("voice.blued.cn")
                        val isCallApi = blockCall && urlStr.contains("users/call")
                        val isExactAdPath = blockAds && urlStr.contains("/obj/static/ad/")
                        // 命中广告域名, 但「闪照免看广告」开启时放行激励视频 SDK 域名
                        val isAd = blockAds && shouldBlockAd(urlStr)
                        // 用户自定义广告接口黑名单 (switch_block_ad_api 控制总开关)
                        val isUserBlocked = matchesUserBlocklist(urlStr)

                        if (isVoice || isCallApi || isExactAdPath || isAd || isUserBlocked) {
                            if (loggedHits.add("ok:$urlStr"))
                                XposedBridge.log("$TAG [OkHttp拦截] $urlStr (voice=$isVoice call=$isCallApi ad=$isAd exactAd=$isExactAdPath user=$isUserBlocked)")
                            try {
                                val httpUrlClass = XposedHelpers.findClass("okhttp3.HttpUrl", lpparam.classLoader)
                                val dummyUrl = XposedHelpers.callStaticMethod(httpUrlClass, "parse", "http://127.0.0.1/blocked_by_llhook")
                                XposedHelpers.setObjectField(builder, "url", dummyUrl)
                            } catch (e: Throwable) {}
                        }
                    }
                })
            }
        } catch (e: Throwable) {}
    }

    // ==========================================
    // 🌐 2. DNS 解析劫持 (InetAddress.getAllByName → 127.0.0.1)
    // ==========================================
    private fun hookDns() {
        try {
            InetAddress::class.java.findMethod {
                name == "getAllByName" && parameterTypes.size == 1 && parameterTypes[0] == String::class.java
            }.hookBefore { param ->
                val host = param.args[0] as? String ?: return@hookBefore
                // 广告域名受 switch_block_ads 控制; 语音交友域名受独立开关 KEY_BLOCK_VOICE 控制 (默认关)
                val blockAds = Config.isFeatureEnabled("switch_block_ads")
                val blockVoice = Config.isFeatureEnabled(KEY_BLOCK_VOICE)
                // 用户自定义黑名单: 这里只有 host, 路径型规则会在 OkHttp/URL 层命中, host 型在此命中
                val userHit = matchesUserBlocklist(host)
                val hitAds = blockAds && shouldBlockAd(host)
                val hitVoice = blockVoice && host.contains("voice.blued.cn")
                if (hitAds || hitVoice || userHit) {
                    if (loggedHits.add("dns:$host"))
                        XposedBridge.log("$TAG [DNS拦截] $host (voice=$hitVoice ads=$hitAds user=$userHit)")
                    // 强制返回本地回环 IP (127.0.0.1)，让它去访问空气
                    val fakeAddress = InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1))
                    param.result = arrayOf(fakeAddress)
                }
            }
        } catch (e: Throwable) {}
    }

    // ==========================================
    // 🌐 3. URL.openConnection 兜底 (古老/原生网络栈)
    // ==========================================
    private fun hookUrlConnection() {
        // 命中拦截条件则抛 ConnectException, 直接阻断连接
        fun blockSocket(param: XC_MethodHook.MethodHookParam) {
            val urlObj = param.thisObject as? URL ?: return
            val host = urlObj.host ?: return
            val urlStr = urlObj.toString().lowercase()

            val blockAds = Config.isFeatureEnabled("switch_block_ads")
            val blockVoice = Config.isFeatureEnabled(KEY_BLOCK_VOICE)
            val blockCall = Config.isFeatureEnabled(KEY_BLOCK_CALL)
            val isExactAdPath = blockAds && urlStr.contains("/obj/static/ad/")
            val isCallApi = blockCall && urlStr.contains("users/call")
            val isVoice = blockVoice && host.contains("voice.blued.cn")
            val hitAds = blockAds && shouldBlockAd(host)
            val isUserBlocked = matchesUserBlocklist(urlStr)
            // 精准狙击静态广告图片 + 呼叫 API + 广告域名 + 用户自定义黑名单, 防误伤 CDN
            if (hitAds || isExactAdPath || isCallApi || isVoice || isUserBlocked) {
                if (loggedHits.add("url:$urlStr"))
                    XposedBridge.log("$TAG [URL拦截] $urlStr (voice=$isVoice call=$isCallApi ad=$hitAds exactAd=$isExactAdPath user=$isUserBlocked)")
                throw java.net.ConnectException("Connection blocked by llhook")
            }
        }
        try {
            URL::class.java.findMethod { name == "openConnection" && parameterTypes.isEmpty() }.hookBefore(::blockSocket)
        } catch (e: Throwable) {}
        try {
            URL::class.java.findMethod { name == "openConnection" && parameterTypes.size == 1 && parameterTypes[0] == java.net.Proxy::class.java }.hookBefore(::blockSocket)
        } catch (e: Throwable) {}
    }

    /** 激励视频广告 SDK 域名 (闪照「看视频获得一次机会」所依赖)。
     *  开启「闪照免看广告」时放行这些域名, 让激励视频面板能正常拉取广告配置。
     *  TopOn(anythink) 走 LiteHook 的 init 拦截, 见 LiteHook.REWARD_SDK_CLASSES。 */
    private val rewardAdKeywords = listOf(
        "pangolin", "pglstatp", "pstatp", "oceanengine", "ad.toutiao", // 穿山甲 / 字节系
        "gdt.qq", "e.qq.com",   // 优量汇 (腾讯)
        "kuaishou"               // 快手
    )

    /** 命中广告域名且未被「闪照免看广告」放行。
     *  @param s 小写化的 host 或完整 url 均可 (contains 模糊匹配, 内部统一小写化) */
    private fun shouldBlockAd(s: String): Boolean {
        val lower = s.lowercase()
        if (adKeywords.none { lower.contains(it) }) return false          // 非广告域名
        // 开启闪照免看广告时, 放行激励视频 SDK 域名 (其它广告域名照拦)
        if (Config.isFeatureEnabled("switch_flash_ad_skip") &&
            rewardAdKeywords.any { lower.contains(it) }) return false
        return true
    }
}
