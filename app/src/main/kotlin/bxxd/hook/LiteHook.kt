package bxxd.hook

import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 一键 lite —— 让 Blued 变轻变快, 减少启动期广告 SDK 初始化和不用的重型原生库。
 *
 * ┌──────────────────────── 稳定性设计(必读)─────────────────────────┐
 * │ 1. init 阶段只读取一次 switch_lite 开关。开关【关闭】时完全不注册任何 hook, │
 * │    LiteHook 对启动过程零影响 —— 这是避免"开关关闭仍闪退"的关键。          │
 * │    (旧版本对 loadLibrary0 无条件 hook, 关闭开关时仍会在启动早期触发        │
 * │     Config 读取 → contentResolver 死锁 → 闪退)                          │
 * │ 2. 开关【开启】时才注册; hookBefore 内部只做纯内存判断, 不再读取配置,      │
 * │    避免在 loadLibrary0 等高频/超早期方法里触发重逻辑。                   │
 * │ 3. 不再 hook Tinker: 它深度侵入 Application 启动流程, 空转其 load 方法    │
 * │    会导致卡在第一屏。                                                    │
 * │ 4. 修改开关后需【重启 Blued】生效。全程可逆。                            │
 * │ 5. 推送 SDK 与 地图 SDK 保留, 不精简。                                  │
 * └────────────────────────────────────────────────────────────────────┘
 */
object LiteHook : BaseHook {

    private const val TAG = "LiteHook"

    // ==========================================
    // 启动期广告 SDK 初始化入口 (init* / onCreate / start 直接空转)
    // ==========================================
    private val AD_SDK_INIT_CLASSES = listOf(
        "com.kwad.sdk.api.KsAdSDK",                  // 快手广告
        "com.opos.mobad.api.MobAdManager",           // OPPO 广告
        "com.anythink.core.api.ATSDK",               // AnyThink / TopOn 聚合
        "com.bytedance.sdk.openadsdk.TTAdSdk",       // 穿山甲
        "com.qq.e.comm.managers.GDTADManager",       // 广点通
        "com.baidu.mobads.sdk.api.Mobads",           // 百度联盟
        "com.huawei.openalliance.ad.inter.AdManager", // 华为广告
        "com.heytap.msp.mobad.api.MobAdManager",     // HeyTap 广告
        "com.tramini.plugin.api.TraminiSDK"          // Tramini 小游戏广告
    )

    // ==========================================
    // 不用的重型原生库 (命中关键字则不加载, 省 20MB+ 内存)
    // ⚠ 只保留【确定不影响首页/feed】的库 —— 直播推拉流 + 实名活体。
    //   曾误拦 ffavc/st_mobile/liteavsdk 等解码/美颜库, 导致首页列表卡顿,
    //   因为首页视频/动图 feed 的解码会反复尝试加载被拦的库。
    // ==========================================
    private val HEAVY_SO_KEYWORDS = listOf(
        "ZegoLiveRoom",          // 即构直播推拉流 (~17MB, 仅进直播间时用)
        "pldroid_streaming_core", // 七牛直播推流 (仅开播时用)
        "YTLiveness",             // 活体检测 (仅实名认证时用)
        "YTCommonLiveness"        // 活体检测公共库
    )

    // ==========================================
    // 激励视频广告 SDK (闪照「看视频获得一次机会」所用)
    //  开启「闪照免看广告」时不拦截这些 SDK 的初始化, 让面板可用。
    // ==========================================
    private val REWARD_SDK_CLASSES = setOf(
        "com.bytedance.sdk.openadsdk.TTAdSdk",       // 穿山甲 (adms_type=4)
        "com.qq.e.comm.managers.GDTADManager",       // 优量汇 (adms_type=3)
        "com.anythink.core.api.ATSDK",               // TopOn/AnyThink (adms_type=6)
        "com.kwad.sdk.api.KsAdSDK"                   // 快手 (adms_type=13)
    )

    /** 已记录过拦截日志的库名 (去重, 避免高频拦截时磁盘 I/O 拖慢) */
    private val loggedSo = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        // ★ 只读一次开关; 关闭则【完全不注册 hook】, 对启动零影响
        val enabled = try {
            Config.isFeatureEnabled("switch_lite")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] read switch failed, default OFF: $t")
            false
        }
        if (!enabled) {
            XposedBridge.log("[$TAG] switch_lite OFF → no hooks registered")
            return
        }
        XposedBridge.log("[$TAG] switch_lite ON → applying lite hooks")
        // 闪照免看广告开启时, 保留激励视频 SDK 初始化 (放行, 不杀),
        // 让「看视频获得一次机会」面板可正常拉取广告配置 (配合 AntiRecallHook 跳广告发奖)。
        // ★ 同 switch_lite 一样在 init 读一次, 改开关后需【重启 Blued】生效。
        val keepRewardSdk = try { Config.isFeatureEnabled("switch_flash_ad_skip") } catch (_: Throwable) { false }
        XposedBridge.log("[$TAG] switch_lite ON → applying lite hooks (keepRewardSdk=$keepRewardSdk)")
        hookAdsInit(lpparam, keepRewardSdk)
        hookHeavySo()
    }

    // ----------------------------------------------------------------
    // 广告 SDK 初始化拦截: init* / onCreate / start 空转
    //   (开关已确认 ON, hookBefore 内不再读配置, 直接执行)
    // ----------------------------------------------------------------
    private fun hookAdsInit(lpparam: XC_LoadPackage.LoadPackageParam, keepRewardSdk: Boolean) {
        for (className in AD_SDK_INIT_CLASSES) {
            // 闪照免看广告: 保留激励视频 SDK (穿山甲/优量汇/TopOn/快手), 让其正常初始化
            if (keepRewardSdk && className in REWARD_SDK_CLASSES) continue
            try {
                val clz = XposedHelpers.findClass(className, lpparam.classLoader)
                for (m in clz.declaredMethods) {
                    val n = m.name
                    if (n == "init" || n.startsWith("init") || n == "onCreate" || n == "start") {
                        try {
                            m.hookBefore { p ->
                                try { p.result = null } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) { /* 该 SDK 不存在, 跳过 */ }
        }
    }

    // ----------------------------------------------------------------
    // 拦截重型原生库加载: hook Runtime.loadLibrary0, 命中关键字则不加载。
    //   hookBefore 内纯内存判断, 全程 try-catch, 绝不抛出影响 SO 加载。
    // ----------------------------------------------------------------
    private fun hookHeavySo() {
        try {
            val runtime = Class.forName("java.lang.Runtime")
            for (m in runtime.declaredMethods.filter { it.name == "loadLibrary0" }) {
                try {
                    m.hookBefore { p ->
                        try {
                            val libArg = p.args.lastOrNull { it is String } as? String
                                ?: return@hookBefore
                            for (kw in HEAVY_SO_KEYWORDS) {
                                if (libArg.contains(kw)) {
                                    // 同一库名只记一次日志, 避免高频拦截时 I/O 拖慢
                                    if (loggedSo.add(libArg)) {
                                        XposedBridge.log("[$TAG] block SO load: $libArg")
                                    }
                                    p.result = null
                                    return@hookBefore
                                }
                            }
                        } catch (_: Throwable) { /* 拦截逻辑异常时放行, 不阻断加载 */ }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}
