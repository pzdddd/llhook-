package bxxd.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.net.InetAddress
import java.net.URL

object AdsHook : BaseHook {

    // 底层网络拦截黑名单
    private val adKeywords = listOf(
       "hm-nrj.baidu.com"
    )

    // 🎯 终极 UI 暗杀名单
    private val targetViewIds = arrayOf(
        "card_style_3",
        "cl_style_1",
        "ll_img_list",
        "recommend_view",
        "layout_native_ad",
        "ll_hello_layout",
        "fl_blued_ad",
        "img_ad",
        "live_video_c3_cover_mc"
    )

    /**
     * ──────────────────────────────────────────────────────────────────────
     *  净化子项清单 (标题 -> 配置开关 key)
     *  供 悬浮球净化菜单 / 设置页净化列表 共用, 改一处两处同步, 杜绝不一致。
     *  新增净化项时, 只需在此追加一行 + 在下面对应 hook 里 purifyEnabled(key)。
     * ──────────────────────────────────────────────────────────────────────
     */
    // 顺序即菜单/设置页顺序; 标题带「·」分组前缀, 一眼看清所属板块
    val PURIFY_ITEMS: List<Pair<String, String>> = listOf(
        // —— 页面净化 ——
        "开屏广告秒跳"            to "purify_splash",
        "信息流伪装卡片"          to "purify_list_card",
        "顶部横幅广告"            to "purify_header",
        "我的页面精简"            to "purify_mine",
        "访客列表广告"            to "purify_visitor",
        "语音聊天室精简"          to "purify_voice_room",
        // —— 身边列表 / 顶部弹窗 ——
        "身边页·超级通话弹窗"     to "purify_super_call",
        "身边列表·荷尔健康广告"   to "purify_nearby_health_ad",
        "身边列表·显示个性签名"   to "nearby_show_sign",
        // —— 聊天界面 ——
        "聊天·安全提示卡片"         to "purify_chat_safe",
        // —— 底部 Tab 栏 —— (find_*=身边 / feed_*=发现 / live_*=直播, 按显示文字匹配, 标准版/极速版均正确)
        "底部Tab·隐藏发现"        to "purify_tab_feed",
        // —— 发现页内容 ——
        "发现页·顶部广告"         to "purify_find_ad",
        "发现页·地图找人入口"     to "purify_find_map",
        "发现页·首页二楼"         to "purify_find_floor",
        "发现页·金币签到引导"     to "purify_find_gold",
        // —— 设置页条目 ——
        "设置页·真人认证"         to "purify_set_verify",
        "设置页·直播设置"         to "purify_set_live",
        "设置页·扫一扫"           to "purify_set_scan",
        "设置页·安全中心"         to "purify_set_safe",
        "设置页·隐私政策"         to "purify_set_privacy",
        "设置页·信息收集清单"     to "purify_set_info_gather",
        "设置页·第三方信息共享"   to "purify_set_info_share",
        "设置页·关于Blued"        to "purify_set_about",
        "设置页·调试入口"         to "purify_set_debug"
    )

    /**
     * 单项净化开关判定 (向后兼容旧"净化页面"总开关)。
     *  - 旧用户若开了总开关 switch_remove_ads -> 所有净化项一律生效, 行为不变;
     *  - 新用户用独立开关逐项控制, 灵活精细。
     *  这样既支持新的菜单式独立开关, 又不会让老配置失效。
     */
    private fun purifyEnabled(granularKey: String): Boolean {
        if (Config.isFeatureEnabled("switch_remove_ads")) return true
        return Config.isFeatureEnabled(granularKey)
    }

    /** 供其它 Hook 调用: 净化总开关打开或单项开关打开时返回 true */
    fun isPurifyEnabled(granularKey: String, ctx: Context? = null): Boolean {
        if (Config.isFeatureEnabled("switch_remove_ads", ctx)) return true
        return Config.isFeatureEnabled(granularKey, ctx)
    }

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookSplashAds(lpparam)       // 🚀 1. 【听你的】直接 onCreate 秒跳，0秒开屏告别黑屏！
        hookListEngineAds(lpparam)   // 2. 列表视图压扁机 (完美拦截伪装卡片)
        hookOkHttpEngine(lpparam)    // 3. OkHttp 底层断网 (专杀呼唤API)
        hookNetworkLayer()           // 4. 古老 URL 网络层兜底
        hookHeaderAds(lpparam)       // 5. 顶部固定横幅隐藏
        hookHomePurify(lpparam)       // 6. 底部 Tab 栏 + 发现页内容净化
        hookMineAds(lpparam)         // 7. 我的页面精简
        hookVisitorAds(lpparam)      // 8. 访客列表去广告
        hookVoiceChatRoom(lpparam)   // 9. 语音聊天室精简
        hookSettingsItems(lpparam)   // 10. 设置页条目净化
        hookMineDataSetters(lpparam) // 11. 我的页动态条目过滤 (会员/收益/健康入口)
        hookNearbyList(lpparam)      // 12. 身边列表: 个性签名 + 荷尔健康广告
        hookSuperCallNotification(lpparam) // 13. 紫色"和他聊聊"超级通话弹窗拦截
    }

    // ==========================================
    // 🚀 1. 【大道至简】硬核秒进，拒绝一切黑屏
    // ==========================================
    private fun hookSplashAds(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val firstActivityClass = lpparam.classLoader.loadClass("com.soft.blued.ui.welcome.FirstActivity")

            // 战术1：进门前先把广告开关关掉，给服务器省点流量
            firstActivityClass.findMethod { name == "onCreate" }.hookBefore { param ->
                if (purifyEnabled("purify_splash")) {
                    val activity = param.thisObject as Activity
                    activity.intent.putExtra("arg_show_ad", false)
                    activity.intent.putExtra("extra_bool_open_welcome_page", false)
                }
            }

            // 战术2：等 super.onCreate 跑完（防止闪退），瞬间拉起主页并自杀！连黑屏的机会都不给！
            firstActivityClass.findMethod { name == "onCreate" }.hookAfter { param ->
                if (purifyEnabled("purify_splash")) {
                    val activity = param.thisObject as Activity
                    val homeIntent = Intent().apply {
                        setClassName(activity, "com.soft.blued.ui.home.HomeActivity")
                    }
                    activity.intent.extras?.let { homeIntent.putExtras(it) }
                    activity.startActivity(homeIntent)
                    activity.finish()
                }
            }
        } catch (e: Throwable) {}

        // 旧版 TerminalActivity 保留原汁原味的跳过
        try {
            val terminalActivityClass = lpparam.classLoader.loadClass("com.blued.android.core.ui.TerminalActivity")
            terminalActivityClass.findMethod { name == "f" && parameterTypes.isEmpty() }.hookBefore { param ->
                if (!purifyEnabled("purify_splash")) return@hookBefore
                val activity = param.thisObject as Activity
                val fragmentName = activity.intent.getStringExtra("arg_fragment_class_name")
                if (fragmentName == "com.soft.blued.ui.welcome.SerialSplashFragment" || fragmentName == "com.soft.blued.ui.welcome.WelcomeFragment") {
                    val homeIntent = Intent().apply { setClassName(activity, "com.soft.blued.ui.home.HomeActivity") }
                    activity.intent.extras?.let { homeIntent.putExtras(it) }
                    activity.startActivity(homeIntent)
                    activity.finish()
                    param.result = null
                }
            }
        } catch (e: Throwable) {}
    }

    // ==========================================
    // 🚀 2. BRVAH 列表视觉压扁机 (防反弹版)
    // ==========================================
    private fun hookListEngineAds(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val baseAdapterClass = XposedHelpers.findClassIfExists("com.chad.library.adapter.base.BaseQuickAdapter", lpparam.classLoader)
            if (baseAdapterClass != null) {
                XposedBridge.hookAllMethods(baseAdapterClass, "onBindViewHolder", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!purifyEnabled("purify_list_card")) return
                        try {
                            val adapter = param.thisObject
                            val holder = param.args[0] ?: return
                            val position = param.args[1] as Int

                            val item = try { XposedHelpers.callMethod(adapter, "getItem", position) } catch(e:Throwable){null}
                            val itemView = try { XposedHelpers.getObjectField(holder, "itemView") as? View } catch(e:Throwable){null} ?: return

                            sweepAndCrush(itemView)

                            val viewIdName = try { itemView.context.resources.getResourceEntryName(itemView.id) } catch(e:Throwable){""}
                            val isRootTarget = targetViewIds.contains(viewIdName)

                            if (isAdObject(item) || isRootTarget) {
                                crushViewSafely(itemView)
                                val parent = itemView.parent as? ViewGroup
                                if (parent != null && parent.javaClass.simpleName == "LinearLayout") {
                                    crushViewSafely(parent)
                                }
                            } else {
                                if (itemView.visibility == View.GONE && itemView.layoutParams?.height == 0) {
                                    itemView.visibility = View.VISIBLE
                                    itemView.layoutParams?.let { lp ->
                                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                                        itemView.layoutParams = lp
                                    }
                                }
                            }
                        } catch (e: Throwable) {}
                    }
                })
            }
        } catch (e: Throwable) {}
    }

    private fun isAdObject(obj: Any?): Boolean {
        if (obj == null) return false
        val clazz = obj.javaClass
        val className = clazz.name
        if (className.startsWith("java.") || className.startsWith("android.") || className.startsWith("kotlin.")) return false

        try {
            val fields = clazz.declaredFields
            for (field in fields) {
                field.isAccessible = true
                val name = field.name
                if (name == "is_ads" || name == "is_ad" || name == "is_ads_data") {
                    val value = field.get(obj)
                    if (value == 1 || value == true || value == "1") return true
                }
                if (name == "click_url" || name == "show_url" || name == "hidden_url" || name == "nearby_dating" || name == "live_recommend" || name == "ad_info") {
                    val value = field.get(obj)
                    if (value != null) {
                        if (value is String && value.isNotEmpty()) return true
                        if (value is Collection<*> && value.isNotEmpty()) return true
                        if (value is Array<*> && value.isNotEmpty()) return true
                    }
                }
            }
        } catch (e: Throwable) {}
        return false
    }

    private fun sweepAndCrush(root: View) {
        val context = root.context
        val idsToCrush = targetViewIds.map { context.resources.getIdentifier(it, "id", context.packageName) }.filter { it != 0 }
        if (idsToCrush.isEmpty()) return

        fun scan(view: View) {
            if (idsToCrush.contains(view.id)) {
                crushViewSafely(view)
                if (view.id == context.resources.getIdentifier("card_style_3", "id", context.packageName) ||
                    view.id == context.resources.getIdentifier("live_video_c3_cover_mc", "id", context.packageName)) {
                    val parent = view.parent as? ViewGroup
                    if (parent != null) {
                        val parentIdName = try { context.resources.getResourceEntryName(parent.id) } catch (e: Exception) { "" }
                        if (parentIdName == "fl_main") crushViewSafely(parent)
                    }
                }
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    scan(view.getChildAt(i))
                }
            }
        }
        scan(root)
    }

    private fun crushViewSafely(view: View) {
        val lp = view.layoutParams
        if (view.visibility == View.GONE && lp?.height == 0 && view.paddingTop == 0) return

        view.visibility = View.GONE
        view.setPadding(0, 0, 0, 0)

        if (lp != null) {
            lp.height = 0
            lp.width = 0
            if (lp is ViewGroup.MarginLayoutParams) lp.setMargins(0, 0, 0, 0)
            view.layoutParams = lp
        }
    }

    /**
     * 通用按 id 名净化: 在 root 中查找 id 名列表对应的 View, 逐个彻底隐藏。
     * 资源 id 动态解析 (不依赖硬编码 R 值), 未找到自动跳过, 安全无副作用。
     */
    private fun hideViewByIdNames(root: View, idNames: Array<String>, key: String) {
        if (!purifyEnabled(key)) return
        val ctx = root.context
        idNames.forEach { name ->
            val rid = ctx.resources.getIdentifier(name, "id", ctx.packageName)
            if (rid != 0) root.findViewById<View>(rid)?.let { crushViewSafely(it) }
        }
    }

    // ==========================================
    // 🛡️ 4. 顶部固定横幅静默隐藏
    // ==========================================
    private fun hookHeaderAds(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fragmentClass = lpparam.classLoader.loadClass("androidx.fragment.app.Fragment")
            fragmentClass.findMethod { name == "onViewCreated" && parameterTypes.size == 2 }.hookAfter { param ->
                if (!purifyEnabled("purify_header")) return@hookAfter
                val view = param.args[0] as? View ?: return@hookAfter

                val context = view.context
                val headerIds = arrayOf("recommend_view", "ll_hello_layout", "fl_blued_ad").map {
                    context.resources.getIdentifier(it, "id", context.packageName)
                }.filter { it != 0 }

                if (headerIds.isNotEmpty()) {
                    view.viewTreeObserver.addOnGlobalLayoutListener {
                        headerIds.forEach { resId ->
                            view.findViewById<View>(resId)?.let { target ->
                                if (target.visibility != View.GONE && target.layoutParams?.height != 0) {
                                    crushViewSafely(target)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { }

    }

    // ==========================================
    // 🌐 3. OkHttp 强力域名劫持
    // ==========================================
    private fun hookOkHttpEngine(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val builderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", lpparam.classLoader)
            if (builderClass != null) {
                XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!Config.isFeatureEnabled("switch_block_ads")) return

                        val builder = param.thisObject
                        val urlObj = try { XposedHelpers.getObjectField(builder, "url") } catch(e:Throwable){null}
                        val urlStr = urlObj?.toString()?.lowercase() ?: return

                        val isCallApi = urlStr.contains("/users/call")
                        val isExactAdPath = urlStr.contains("/obj/static/ad/")
                        val isVoice = urlStr.contains("voice.blued.cn")
                        val isAd = adKeywords.any { urlStr.contains(it) }

                        if (isCallApi || isExactAdPath || isVoice || isAd) {
                            try {
                                val httpUrlClass = XposedHelpers.findClass("okhttp3.HttpUrl", lpparam.classLoader)
                                val dummyUrl = XposedHelpers.callStaticMethod(httpUrlClass, "parse", "http://127.0.0.1/blocked_by_llhook")
                                XposedHelpers.setObjectField(builder, "url", dummyUrl)
                            } catch(e: Throwable) {}
                        }
                    }
                })
            }
        } catch (e: Throwable) {}
    }

    // ==========================================
    // 🌐 4. 古老网络层兜底 (DNS + Socket)
    // ==========================================
    private fun hookNetworkLayer() {
        try {
            InetAddress::class.java.findMethod { name == "getAllByName" && parameterTypes.size == 1 && parameterTypes[0] == String::class.java }.hookBefore { param ->
                val host = param.args[0] as? String ?: return@hookBefore
                if ((Config.isFeatureEnabled("switch_block_ads") && isAdHost(host)) || host.contains("voice.blued.cn")) {
                    param.result = arrayOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
                }
            }
            val blockSocket = { param: XC_MethodHook.MethodHookParam ->
                val urlStr = (param.thisObject as? URL)?.toString()?.lowercase() ?: ""
                val host = (param.thisObject as? URL)?.host ?: ""
                val isExactAdPath = urlStr.contains("/obj/static/ad/")
                val isCallApi = urlStr.contains("/users/call")
                if ((Config.isFeatureEnabled("switch_block_ads") && (isAdHost(host) || isExactAdPath || isCallApi)) || host.contains("voice.blued.cn")) {
                    throw java.net.ConnectException("Connection blocked by llhook")
                }
            }
            URL::class.java.findMethod { name == "openConnection" && parameterTypes.isEmpty() }.hookBefore(blockSocket)
            URL::class.java.findMethod { name == "openConnection" && parameterTypes.size == 1 && parameterTypes[0] == java.net.Proxy::class.java }.hookBefore(blockSocket)
        } catch (e: Throwable) {}
    }

    private fun isAdHost(host: String): Boolean = adKeywords.any { host.lowercase().contains(it) }

    // ==========================================
    // 🎙️ 9. 语音聊天室剔除
    // ==========================================
    private fun hookVoiceChatRoom(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fragmentClass = lpparam.classLoader.loadClass("androidx.fragment.app.Fragment")
            fragmentClass.findMethod { name == "onResume" }.hookAfter { param ->
                if (!purifyEnabled("purify_voice_room")) return@hookAfter
                val fragment = param.thisObject
                val view = try { XposedHelpers.callMethod(fragment, "getView") as? View } catch(e:Throwable){null} ?: return@hookAfter

                val context = view.context
                val resId = context.resources.getIdentifier("nearby_chat_room_host", "id", context.packageName)
                if (resId != 0) {
                    view.findViewById<View>(resId)?.let { target ->
                        crushViewSafely(target)
                        (target.parent as? ViewGroup)?.let { crushViewSafely(it) }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    // ==========================================
    // 🚀 7. 清理“我的”页面广告
    // ==========================================
    private fun hookMineAds(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val mineNewFragmentClass = lpparam.classLoader.loadClass(Config.TargetClasses.MINE_NEW_FRAGMENT)
            val minePageModelClass = lpparam.classLoader.loadClass(Config.TargetClasses.MINE_PAGE_MODEL)

            try {
                mineNewFragmentClass.findMethod { name == "onViewCreated" && parameterTypes.size == 2 && parameterTypes[0] == View::class.java }.hookAfter { param ->
                    if (!purifyEnabled("purify_mine")) return@hookAfter
                    val view = param.args[0] as? View ?: return@hookAfter
                    hideMineViewsSafely(view)
                }
            } catch (e: Exception) { }

            try {
                mineNewFragmentClass.findMethod { name == "onResume" }.hookAfter { param ->
                    if (!purifyEnabled("purify_mine")) return@hookAfter
                    val fragmentView = try { XposedHelpers.callMethod(param.thisObject, "getView") as? View } catch (e: Exception) { null }
                    if (fragmentView != null) hideMineViewsSafely(fragmentView)
                }
            } catch (e: Exception) { }

            // 标准版: MineNewFragment.a(MinePageModel) 是统一的数据回填入口, 置空广告字段即可阻止渲染。
            // 极速版: 该方法被爱加密加固拆分抽空(变成 db(MineBanner)/fb(HealthyAD)/ib(HealthItem)/pb(VipInfo,...)
            //         等单参 setter), findMethod 找不到会抛异常, 用 try 兜住, 改由 hideMineViewsSafely 按 View id 隐藏。
            try {
                val targetMethod = mineNewFragmentClass.findMethod { name == "a" && parameterTypes.size == 1 && parameterTypes[0] == minePageModelClass }
                targetMethod.hookBefore { param ->
                    if (!purifyEnabled("purify_mine")) return@hookBefore
                    val model = param.args[0]
                    if (model != null) {
                        arrayOf("anchor", "banner", "emotions", "healthy", "healthy_ad", "healthy_banner", "service", "vip_broadcast").forEach { fieldName ->
                            try { XposedHelpers.setObjectField(model, fieldName, null) } catch (e: Exception) { }
                        }
                    }
                }
                targetMethod.hookAfter { param ->
                    if (!purifyEnabled("purify_mine")) return@hookAfter
                    val fragmentView = try { XposedHelpers.callMethod(param.thisObject, "getView") as? View } catch (e: Exception) { null }
                    if (fragmentView != null) hideMineViewsSafely(fragmentView)
                }
            } catch (e: Exception) { }
        } catch (e: Exception) { }
    }

    private fun hideMineViewsSafely(fragmentView: View) {
        try {
            val context = fragmentView.context
            // 1) 区块行: 钻石/直播/语音室/其它 (语音室 ll_yy 仅标准版有, 极速版已移除, 找不到自动跳过)
            arrayOf("ll_beans", "ll_live", "ll_yy", "ll_other").forEach { idName ->
                val viewId = context.resources.getIdentifier(idName, "id", context.packageName)
                if (viewId != 0) fragmentView.findViewById<View>(viewId)?.let { crushViewSafely(it) }
            }
            // 2) 广告/横幅/VIP广播/增值入口: 极速版 a(MinePageModel) 被加固抽空, 模型置空失效,
            //    改为按 id 直接隐藏对应 View (与标准版 a() 置空字段同效果)。
            val adIdNames = arrayOf(
                "ad_view_layout", "cv_ad", "img_ad",   // 通用广告位
                "banner_vas",                           // 横幅 banner
                "gl_vas_entry", "ll_vas",              // 表情/增值入口 emotions
                "cl_health_ad", "cv_health", "vf_health",          // 荷尔健康广告 healthy_ad
                "cl_health_care_center", "ll_health", "ll_health_entry",  // 健康服务中心 healthy / health_care_center
                "vf_vip_ad", "card_vip", "layout_vip", "btn_to_vip_center",  // 开通会员入口 vip_broadcast / card_vip
                "ll_live_wealth",                       // 主播收益/财富报表 live_wealth
                "rv_resource_promotion"                 // 资源推广位 resource_promotion
            )
            val adRids = adIdNames.mapNotNull { name ->
                val r = context.resources.getIdentifier(name, "id", context.packageName)
                if (r != 0) r else null
            }
            adRids.forEach { fragmentView.findViewById<View>(it)?.let { v -> crushViewSafely(v) } }
            // 3) 异步兜底: 数据 setter 在 onResume 之后才回填广告 View, 监听布局变化反复隐藏, 避免漏网
            fragmentView.viewTreeObserver.addOnGlobalLayoutListener {
                if (!fragmentView.isAttachedToWindow) return@addOnGlobalLayoutListener
                adRids.forEach { rid ->
                    fragmentView.findViewById<View>(rid)?.let { v ->
                        if (v.visibility != View.GONE) crushViewSafely(v)
                    }
                }
            }
        } catch (e: Exception) { }
    }

    // ==========================================
    // ⚙️ 6. 底部 Tab 栏净化 + 发现页内容净化
    //   底部导航布局 tab_main.xml: 每个 Tab 包在 HomeQBadgeContainer 里 (按显示文字匹配, 不靠 id 名)
    //     身边 = find_badge_container/ll_main_find   发现 = feed_badge_container/ll_main_feed
    //     直播 = live_badge_container/ll_main_live   (标准版与极速版 id 含义一致)
    //   发现页 fragment_main_find.xml: 顶部广告/地图入口/二楼/金币签到引导
    //   hook 点: HomeActivity.onResume, 发现页是其子页, decorView 可跨 fragment 查找
    // ==========================================
    private fun hookHomePurify(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            lpparam.classLoader.loadClass("com.soft.blued.ui.home.HomeActivity").findMethod { name == "onResume" }.hookAfter { param ->
                val activity = param.thisObject as Activity
                val decor = activity.window.decorView

                // —— 底部 Tab 隐藏: 按 App 当前语言下的「显示文字」匹配, 不再依赖容易错位的 id 名。
                //    Blued 的 find_*/feed_*/live_* 在标准版与极速版均分别对应 身边/发现/直播,
                //    用字符串资源解析出文字后命中哪个 Tab 隐藏哪个, 杜绝「隐藏发现却隐藏了身边」。
                if (purifyEnabled("purify_tab_feed")) hideBottomTabByLabel(decor, "discovery")         // 发现
                if (purifyEnabled("purify_tab_live")) hideBottomTabByLabel(decor, "liveVideo_live")    // 直播

                // —— 发现页内容净化 ——
                hideViewByIdNames(decor, arrayOf("blued_ad_layout"), "purify_find_ad")
                hideViewByIdNames(decor, arrayOf("map_pag_view"), "purify_find_map")
                hideViewByIdNames(decor, arrayOf("second_floor", "iv_two_level", "second_floor_ad_icon"), "purify_find_floor")
                hideViewByIdNames(decor, arrayOf("layout_gold_guide", "nearby_activity_tip", "tv_sign_days_tip", "tv_tip"), "purify_find_gold")
            }
        } catch (e: Exception) { }
    }

    /**
     * 按底部 Tab 的「显示文字」定位并隐藏整个 Tab 入口。
     * 直接按 id 名隐藏有风险: id 与中文标签的对应在不同版本/运营配置下可能不一致
     * (实测 find 系列 id = 身边, feed 系列 id = 发现)。改为解析 App 自身字符串资源得到当前 Tab
     * 文字(身边/发现/直播), 再在 5 个 Tab 容器内匹配 TextView 文字, 命中即隐藏, 永远只隐藏用户想隐藏的那一个。
     */
    private fun hideBottomTabByLabel(root: View, stringResName: String): Boolean {
        val ctx = root.context
        val sid = ctx.resources.getIdentifier(stringResName, "string", ctx.packageName)
        if (sid == 0) return false
        val target = ctx.resources.getString(sid)
        // (badge 容器 id, 容器内文字 TextView id) 一一对应
        val tabs = arrayOf(
            "find_badge_container"  to "tv_main_find",
            "feed_badge_container"  to "tv_main_feed",
            "live_badge_container"  to "tv_main_live",
            "msg_badge_container"   to "tv_main_msg",
            "other_badge_container" to "tv_main_others"
        )
        var hit = false
        for ((contIdName, tvIdName) in tabs) {
            val cid = ctx.resources.getIdentifier(contIdName, "id", ctx.packageName)
            if (cid == 0) continue
            val container = root.findViewById<View>(cid) ?: continue
            val lid = ctx.resources.getIdentifier(tvIdName, "id", ctx.packageName)
            val tv = if (lid != 0) container.findViewById<android.widget.TextView>(lid) else null
            if (tv != null && tv.text?.toString() == target) {
                crushViewSafely(container)   // 隐藏整个 Tab 入口(容器+图标+文字), 宽高置 0
                hit = true
            }
        }
        return hit
    }

    // ==========================================
    // ⚙️ 10. 设置页条目净化 (隐藏指定设置项)
    //   设置页 fragment_settings.xml 条目 id: 真人认证/直播设置/扫一扫/安全中心/调试等
    //   hook 点: androidx Fragment.onViewCreated, 用 ll_face_verify 作指纹识别设置页
    //   (不依赖设置页 Fragment 类名, 对任意版本鲁棒)
    // ==========================================
    private fun hookSettingsItems(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fragmentClass = lpparam.classLoader.loadClass("androidx.fragment.app.Fragment")
            fragmentClass.findMethod { name == "onViewCreated" }.hookAfter { param ->
                val view = param.args.getOrNull(0) as? View ?: return@hookAfter
                val ctx = view.context
                // 指纹: 设置页必含 ll_face_verify; 非设置页立即 return, 零副作用
                val fpId = ctx.resources.getIdentifier("ll_face_verify", "id", ctx.packageName)
                if (fpId == 0 || view.findViewById<View>(fpId) == null) return@hookAfter

                // 逐项隐藏 (条目折叠后, 其相邻细分隔线仅 1dp, 视觉影响极小)
                hideViewByIdNames(view, arrayOf("ll_face_verify"), "purify_set_verify")
                hideViewByIdNames(view, arrayOf("ll_live_setting"), "purify_set_live")
                hideViewByIdNames(view, arrayOf("ll_scan_setting"), "purify_set_scan")
                hideViewByIdNames(view, arrayOf("ll_safe_center"), "purify_set_safe")
                hideViewByIdNames(view, arrayOf("ll_privacy_clause"), "purify_set_privacy")
                hideViewByIdNames(view, arrayOf("ll_information_gathering"), "purify_set_info_gather")
                hideViewByIdNames(view, arrayOf("ll_information_shared"), "purify_set_info_share")
                hideViewByIdNames(view, arrayOf("ll_about_blued"), "purify_set_about")
                hideViewByIdNames(view, arrayOf("ll_debug", "ll_url_request"), "purify_set_debug")
            }
        } catch (e: Exception) { }
    }

    private fun hookVisitorAds(lpparam: XC_LoadPackage.LoadPackageParam) {
        arrayOf(Config.TargetClasses.VISITOR_LIST_ADAPTER, Config.TargetClasses.VISITOR_LIST_RECYCLE_VIEW_ADAPTER).forEach { className ->
            try {
                val targetMethod = lpparam.classLoader.loadClass(className).findMethod { name == "a" && parameterTypes.size == 2 && parameterTypes[0] == java.util.List::class.java && parameterTypes[1] == Int::class.javaPrimitiveType }
                targetMethod.hookBefore { param ->
                    if (!purifyEnabled("purify_visitor")) return@hookBefore
                    param.setObjectExtra("filtered_list", (param.args[0] as? List<*>)?.filter { it == null || try { XposedHelpers.getIntField(it, "is_ads") != 1 } catch (e: Exception) { true } } ?: emptyList<Any>())
                }
                targetMethod.hookAfter { param ->
                    if (!purifyEnabled("purify_visitor")) return@hookAfter
                    val filteredList = param.getObjectExtra("filtered_list") as? List<*> ?: return@hookAfter
                    if (className == Config.TargetClasses.VISITOR_LIST_ADAPTER) try { XposedHelpers.setObjectField(param.thisObject, "j", filteredList) } catch (e: Exception) { }
                    else try { XposedHelpers.callMethod(param.thisObject, "setNewData", filteredList) } catch (e: Exception) { }
                    try { XposedHelpers.callMethod(param.thisObject, "notifyDataSetChanged") } catch (e: Exception) { }
                }
            } catch (e: Exception) { }
        }
    }

    // =========================================
    // ⚙️ 11. 我的页动态条目过滤 (极速版专用增强)
    //   极速版 MineNewFragment.a(MinePageModel) 被爱加密加固拆成 db(MineBanner)/fb(List<HealthyAD>)
    //   /gb(List<ColumnsItem>)/hb(ColumnsItem)/ib(HealthItem)/pb(VipInfo,...) 等单参 setter。
    //   方法名被混淆, 但【参数类型稳定】。枚举所有单 List 参数 setter, 按列表元素类型分流:
    //     ColumnsItem -> 按 title/content/key 关键词剔除「收益/报表/钱包/健康服务/会员中心」入口;
    //     HealthyAD   -> 整组清空 (荷尔健康广告 healthy_ad)。
    //   只把入参替换为过滤后的新列表, 空列表/过滤列表对 setter 均安全, 无 NPE。
    // =========================================
    private fun hookMineDataSetters(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fragClass = lpparam.classLoader.loadClass(Config.TargetClasses.MINE_NEW_FRAGMENT)
            val colsItemClass = runCatching {
                Class.forName("${Config.TargetClasses.MINE_PAGE_MODEL}\$ColumnsItem", false, lpparam.classLoader)
            }.getOrNull() ?: return
            val healthyAdClass = runCatching {
                Class.forName("${Config.TargetClasses.MINE_PAGE_MODEL}\$HealthyAD", false, lpparam.classLoader)
            }.getOrNull()

            fragClass.declaredMethods.forEach { m ->
                val ps = m.parameterTypes
                if (ps.size == 1 && ps[0] == java.util.List::class.java) {
                    runCatching {
                        m.hookBefore { param ->
                            if (!purifyEnabled("purify_mine")) return@hookBefore
                            val list = param.args[0] as? List<*> ?: return@hookBefore
                            if (list.isEmpty()) return@hookBefore
                            val sample = list[0] ?: return@hookBefore
                            when {
                                colsItemClass.isInstance(sample) -> {
                                    val filtered = list.filterNot { isUnwantedMineColumn(it) }
                                    if (filtered.size != list.size) param.args[0] = filtered
                                }
                                healthyAdClass != null && healthyAdClass.isInstance(sample) -> {
                                    param.args[0] = emptyList<Any>()   // 荷尔健康广告 healthy_ad 整组清空
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { }
    }

    /** 我的页 ColumnsItem 是否属于要净化的入口 (收益/钱包/健康服务/会员开通)。 */
    private fun isUnwantedMineColumn(item: Any?): Boolean {
        if (item == null) return false
        val keywords = arrayOf(
            "收益", "报表", "钱包", "提现", "健康", "荷尔",
            "会员中心", "开通会员", "开通VIP", "开通vip",
            "health", "wallet", "income", "earnings"
        )
        fun fieldStr(n: String): String = try {
            (XposedHelpers.getObjectField(item, n) as? String).orEmpty()
        } catch (e: Exception) { "" }
        val blob = arrayOf("title", "content", "description", "name", "key", "item_key")
            .joinToString("") { fieldStr(it) }
        return keywords.any { kw -> blob.contains(kw, ignoreCase = true) }
    }

    // =========================================
    // ⚙️ 12. 身边列表: 个性签名注入 + 荷尔健康广告拦截
    //   hook 点: PeopleGridQuickAdapter.convert(BaseViewHolder, Object) —— 该方法是 BaseQuickAdapter
    //   库的合成桥接方法, 方法名固定不受加固混淆影响; 身边列表(网格/列表双模式)的 grid 版均继承自它。
    //     个性签名: UserFindResult.description 注入到 tv_name 下方一行 (默认关, 需手动开, 不随总净化开关)。
    //     荷尔健康广告: item 内含 cl_health_ad 等健康广告 id 即整张压扁。
    // =========================================
    private fun hookNearbyList(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val adapterClass = runCatching {
                lpparam.classLoader.loadClass("com.soft.blued.ui.find.adapter.PeopleGridQuickAdapter")
            }.getOrNull() ?: return
            val holderClass = runCatching {
                lpparam.classLoader.loadClass("com.chad.library.adapter.base.BaseViewHolder")
            }.getOrNull() ?: return
            val findResultClass = runCatching {
                lpparam.classLoader.loadClass("com.soft.blued.ui.find.model.UserFindResult")
            }.getOrNull()
            val convertMethod = adapterClass.declaredMethods.firstOrNull {
                it.name == "convert" && it.parameterTypes.size == 2 && it.parameterTypes[0] == holderClass
            } ?: return
            convertMethod.hookAfter { param ->
                val holder = param.args[0] ?: return@hookAfter
                val itemView = getHolderItemView(holder) ?: return@hookAfter
                // 1) 荷尔健康广告 item: 命中健康广告 id 即压扁整张卡片
                if (purifyEnabled("purify_nearby_health_ad") && containsHealthAdView(itemView)) {
                    crushViewSafely(itemView)
                    return@hookAfter
                }
                // 2) 个性签名 (默认关, 不随总净化开关): 仅对真实用户 item 注入
                val item = param.args[1]
                if (findResultClass != null && findResultClass.isInstance(item) &&
                    Config.isFeatureEnabled("nearby_show_sign")) {
                    injectNearbySignature(itemView, item)
                }
            }
        } catch (e: Exception) { }
    }

    /** 取 BaseViewHolder 持有的 itemView (兼容 BRVAH 2.x getItemView() 与反射字段)。 */
    private fun getHolderItemView(holder: Any): View? {
        return try {
            XposedHelpers.callMethod(holder, "getItemView") as? View
        } catch (e: Exception) {
            try { XposedHelpers.getObjectField(holder, "itemView") as? View } catch (e2: Exception) { null }
        }
    }

    /** 身边列表 item 是否为荷尔健康广告 (按健康广告专属 id 判定)。 */
    private fun containsHealthAdView(view: View): Boolean {
        val ctx = view.context
        val idNames = arrayOf(
            "cl_health_ad", "cv_health_ad_image_left", "cv_health_ad_image_right",
            "iv_health_ad_image_left", "iv_health_ad_image_right", "cl_health_care_center"
        )
        return idNames.any { name ->
            val rid = ctx.resources.getIdentifier(name, "id", ctx.packageName)
            rid != 0 && view.findViewById<View>(rid) != null
        }
    }

    /** 把 UserFindResult.description (个性签名) 追加到身边卡片昵称下方一行 (小号)。 */
    private fun injectNearbySignature(itemView: View, item: Any) {
        try {
            val desc = XposedHelpers.getObjectField(item, "description") as? String
            if (desc.isNullOrBlank()) return
            val ctx = itemView.context
            val nameRid = ctx.resources.getIdentifier("tv_name", "id", ctx.packageName)
            if (nameRid == 0) return
            val tv = itemView.findViewById<TextView>(nameRid) ?: return
            val name = tv.text?.toString().orEmpty()
            if (name.contains("\n")) return   // 已注入过, 避免重复追加
            val ss = SpannableStringBuilder().append(name).append("\n").append(desc)
            val signStart = name.length + 1
            ss.setSpan(RelativeSizeSpan(0.83f), signStart, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.maxLines = 2
            tv.ellipsize = TextUtils.TruncateAt.END
            runCatching { tv.maxEms = Int.MAX_VALUE }
            tv.text = ss
        } catch (e: Exception) { }
    }

    // =========================================
    // ⚙️ 13. 紫色「和他聊聊」超级通话弹窗拦截
    //   来源: FollowedUsersNotificationManager —— 顶部偶尔弹出的紫色卡片 (super_call_notification,
    //   文案"和他聊聊", 配色 #612BFF, 3秒自动关闭)。它有多个 (FriendsNotificationExtra)->View 的
    //   A/B 卡片 builder, 其中一个构建 privilege_user_notification_test1_layout 即紫色卡。
    //   方法名混淆, 但按【参数+返回类型】枚举所有 builder, hook after 后按布局自带 id
    //   (iv_super_call_tag) 识别紫色卡 (不靠异步填充的文案), 命中则替换为 0 尺寸不可见空 View。
    // =========================================
    private fun hookSuperCallNotification(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val mgrClass = runCatching {
                lpparam.classLoader.loadClass("com.soft.blued.manager.FollowedUsersNotificationManager")
            }.getOrNull() ?: return
            val extraClass = runCatching {
                lpparam.classLoader.loadClass("com.soft.blued.ui.msg.model.FriendsNotificationExtra")
            }.getOrNull() ?: return
            mgrClass.declaredMethods.forEach { m ->
                val ps = m.parameterTypes
                if (ps.size == 1 && ps[0] == extraClass && m.returnType == View::class.java) {
                    runCatching {
                        m.hookAfter { param ->
                            if (!purifyEnabled("purify_super_call")) return@hookAfter
                            val v = param.result as? View ?: return@hookAfter
                            if (isPurpleSuperCallCard(v)) {
                                // 替换为 0 尺寸不可见空 View, 展示调度对 view 非空校验后直接返回, 紫色卡永不出现
                                param.result = View(v.context).apply {
                                    visibility = View.GONE
                                    layoutParams = ViewGroup.LayoutParams(0, 0)
                                }
                            }
                        }
                    }
                }
            }
            // 双保险 2: 拦截展示方法 a(FrameLayout, View, FriendsNotificationExtra) —— 卡片被 addView
            // 到顶层容器前再判一次 (即便 builder 替换被加固绕过, 此处仍能把紫色卡换成空 View)
            val frameLayoutClass = android.widget.FrameLayout::class.java
            mgrClass.declaredMethods.forEach { m ->
                val ps = m.parameterTypes
                if (ps.size == 3 && ps[0] == frameLayoutClass && ps[1] == View::class.java && ps[2] == extraClass) {
                    runCatching {
                        m.hookBefore { param ->
                            if (!purifyEnabled("purify_super_call")) return@hookBefore
                            val v = param.args[1] as? View ?: return@hookBefore
                            if (isPurpleSuperCallCard(v)) {
                                param.args[1] = View(v.context).apply {
                                    visibility = View.GONE
                                    layoutParams = ViewGroup.LayoutParams(0, 0)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { }
    }

    /** 判定弹窗卡片是否为紫色「超级呼唤」卡 (按布局自带 id 命中, 不依赖文案填充时机)。 */
    private fun isPurpleSuperCallCard(view: View): Boolean {
        val ctx = view.context
        // privilege_user_notification_test1_layout 布局自带 super_call 标签 id,
        // inflate 完成立即存在; 而文案"和他聊聊"是异步填充, builder 刚返回时尚未 setText,
        // 故绝不能用遍历 TextView 找文案的方式 (那是上一版拦截失败的根因)。
        val idNames = arrayOf("iv_super_call_tag", "tv_super_call_tag")
        return idNames.any { name ->
            val rid = ctx.resources.getIdentifier(name, "id", ctx.packageName)
            rid != 0 && view.findViewById<View>(rid) != null
        }
    }
}
