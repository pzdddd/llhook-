package bxxd.hook

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * ============================================================================
 *  属性透视 (NearbyRoleHook) — v3 统一方案
 * ============================================================================
 *  开关: NetworkSpoofHook.KEY_ENABLED (switch_spoof_lite)
 *
 *  ★ 核心原理 ★:
 *    列表/资料接口 (users?sort_by=*, users/$uid, users/$uid/visitors 等) 在 app/1 UA 下
 *    role 恒为 -1/其他; 真实 role 只在「详情接口」users/$uid/basic (需 app/7 UA) 里。
 *    故 App 自己的请求保持 app/1 (IP/访客/闪照正常), role 由本 hook 两路补全:
 *
 *    ① 列表 UI 补全: 附近/来访/在线列表的 item 追加 role 标签, 异步查 /basic 回填。
 *       ★ 查看页(谁看过我)优化: 首次绑定时用 visited 接口批量拉取全部用户真实 role (app/7),
 *         一次请求即灌满 roleCache, 比逐个 /basic 快得多 (见 [fetchVisitedRolesBatch])。
 *    ② 资料页 JSON 补全: 拦截 Gson.fromJson, 把资料响应里 role=-1 改成 roleCache 真实值
 *       (命中缓存则即时生效; 未命中异步查并缓存, 下次刷新生效)。
 *
 *  roleCache 全局共享 (uid → 原始 roleRaw), 列表预热后资料页首次即可命中。
 * ============================================================================
 */
object NearbyRoleHook : BaseHook {

    private const val TAG = "llhook-NearbyRole"
    private const val ROLE_TAG_KEY = "llhook_nearby_role_tag"

    /** uid → 原始 roleRaw (详情接口解析后缓存; 存原始值便于回填 JSON, 显示时再 mapRole) */
    val roleCache = ConcurrentHashMap<String, String>()
    /** 昵称 → 原始 roleRaw (查看页兜底: item 无 uid tag 时, 用兄弟 name_view 的昵称查真实 role) */
    private val nameToRole = ConcurrentHashMap<String, String>()
    /** 正在请求中的 uid (防并发) */
    private val fetchingUids = ConcurrentHashMap.newKeySet<String>()
    /** roleView → 当前绑定的 uid (ViewHolder 复用校验) */
    private val viewUidMap = Collections.synchronizedMap(WeakHashMap<TextView, String>())
    /** itemView (来访列表) → 当前绑定的 uid (ViewHolder 复用校验, 延迟补丁防错位) */
    private val itemViewUidMap = Collections.synchronizedMap(WeakHashMap<View, String>())
    /** AgeHeightWeightView 实例 → uid (查看页等用此控件, 源头替换 role 时查缓存) */
    private val ahwvUidMap = Collections.synchronizedMap(WeakHashMap<View, String>())
    private val ioPool = Executors.newFixedThreadPool(3)

    /** 诊断: 已见过的 adapter 名 (每种只打一次) */
    private val seenAdapters = Collections.synchronizedSet(LinkedHashSet<String>())
    /** 查看页批量拉取节流: 上次拉取的 elapsedRealtime (同会话 5 分钟内不重复, 避免频繁请求)。 */
    @Volatile private var visitedBatchAt: Long = 0L
    /** 宿主 ClassLoader (init 时缓存, 供 getMyUid/currentCtx 等无 lpparam 的方法反射用, 避免 findClass(...,null) 抛 IllegalArgumentException 导致静默失败)。 */
    @Volatile private var hostClassLoader: ClassLoader? = null

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hostClassLoader = lpparam.classLoader
        // ==================== ① 身边列表 role 标签注入 (仅 People 适配器) ====================
        //    身边/在线列表 (argo, app/7) 响应已含真实 role, 但 App 不原生显示 role → 需注入标签。
        //    来访/查看页 (social, app/1) App 原生显示 role, 见 ③ hookBefore 改 item.role。
        XposedBridge.log("$TAG ★v4.2 启动 开关=${Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED)}")
        try {
            val baseAdapterClass = lpparam.classLoader.loadClass("com.chad.library.adapter.base.BaseQuickAdapter")
            val onBind = baseAdapterClass.findMethod { name == "onBindViewHolder" && parameterTypes.size == 2 }

            // ③ hookBefore: 来访/查看页改 item.role (命中缓存即时, 原生渲染真实值; 未命中异步查+刷新)
            onBind.hookBefore { param ->
                try {
                    val holder = param.args[0]
                    val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View ?: return@hookBefore
                    val ctx = itemView.context
                    if (!Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED, ctx)) return@hookBefore
                    val adapter = param.thisObject
                    val adapterName = adapter.javaClass.name
                    // 身边页 App 不原生显示 role, hookBefore 改 item.role 无意义, 交给 hookAfter 标签
                    if (adapterName.contains("People")) return@hookBefore

                    // 查看页(谁看过我)首次绑定 → 批量拉取真实 role (visited 接口, app/7)
                    maybeTriggerVisitedBatch(adapterName, ctx)

                    val position = param.args[1] as Int
                    val item = XposedHelpers.callMethod(adapter, "getItem", position) ?: return@hookBefore
                    val uid = readUid(item)
                    if (uid.isEmpty()) return@hookBefore
                    mapAhwvByUid(itemView, uid)   // App 渲染前填充映射, 供 AgeHeightWeightView 源头替换
                    val roleRaw = readRoleField(item)
                    val isPlaceholder = roleRaw == "-1" || roleRaw == "-1.0" || roleRaw.isEmpty()
                    if (!isPlaceholder) return@hookBefore

                    // 命中缓存: 预热 item.role (即使 App 不读字段, 也无害; 万一某版本读字段就生效)
                    val cached = roleCache[uid]
                    if (cached != null && cached.isNotEmpty() && cached != "-1" && cached != "-1.0") {
                        setAllRoleFields(item, cached, mapRole(cached))
                        return@hookBefore
                    }
                    // 未命中: 异步查 /basic, 查到后刷新该条目
                    if (fetchingUids.add(uid)) {
                        ioPool.execute {
                            try {
                                val raw = fetchRoleRaw(uid, ctx)
                                if (raw.isNotEmpty() && raw != "-1" && raw != "-1.0") {
                                    itemView.post {
                                        try { XposedHelpers.callMethod(adapter, "notifyItemChanged", position) }
                                        catch (_: Throwable) {}
                                    }
                                }
                            } catch (_: Throwable) {} finally { fetchingUids.remove(uid) }
                        }
                    }
                } catch (_: Throwable) {}
            }
            XposedBridge.log("$TAG 列表 hookBefore (item.role改写) 已挂载")

            // ① hookAfter: 渲染后直接修正显示
            onBind.hookAfter { param ->
                try {
                    val itemView = XposedHelpers.getObjectField(param.args[0], "itemView") as? View ?: return@hookAfter
                    val ctx = itemView.context
                    if (!Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED, ctx)) return@hookAfter
                    val adapter = param.thisObject
                    val adapterName = adapter.javaClass.name
                    val position = param.args[1] as Int
                    val item = XposedHelpers.callMethod(adapter, "getItem", position) ?: return@hookAfter
                    val uid = readUid(item)
                    if (uid.isEmpty()) return@hookAfter
                    val roleRaw = readRoleField(item)

                    if (adapterName.contains("People")) {
                        // 身边页: App 不原生显示 role → 注入标签
                        if (roleRaw.isEmpty() && uid.isEmpty()) return@hookAfter
                        val roleDisplay = mapRole(if (roleRaw.isEmpty()) (roleCache[uid] ?: "-1") else roleRaw)
                        if (adapterName.contains("Grid")) {
                            injectRoleToGridItem(param.args[0], roleDisplay, uid, ctx)
                        } else {
                            injectRoleToListItem(param.args[0], roleDisplay, uid, ctx)
                        }
                    } else {
                        // 来访/查看页: App 原生显示「其他」→ 扫描 itemView 修正该 TextView
                        patchOtherTextView(itemView, uid, roleRaw, adapter, position, ctx)
                    }
                } catch (_: Throwable) {}
            }

            // ==================== ④ 兜底: 非 BaseQuickAdapter 的 RecyclerView 列表 (查看页/足迹等) ====================
            //    这些页面不走 BaseQuickAdapter.onBindViewHolder (③ 完全没日志) → 运行时在 setAdapter 时
            //    动态 hook 该 adapter 自己 override 的 onBindViewHolder, 并从 itemView.tag 反推 uid。
            try {
                val rvClass = lpparam.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
                val vhClass = lpparam.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
                XposedBridge.hookAllMethods(rvClass, "setAdapter", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            val adapter = param.args[0] ?: return
                            val adapterCls = adapter.javaClass
                            if (baseAdapterClass.isAssignableFrom(adapterCls)) return  // 已被 ③ 静态 hook
                            // 查看页 setAdapter 时批量拉取真实 role (visited 接口, app/7; 含 5 分钟节流)
                            runCatching {
                                val rvCtx = XposedHelpers.callMethod(param.thisObject, "getContext") as? android.content.Context
                                if (rvCtx != null) maybeTriggerVisitedBatch(adapterCls.name, rvCtx)
                            }
                            val declCls = findOnBindDeclaringClass(adapterCls, vhClass) ?: return
                            if (!seenAdapters.add("rv-fallback:${declCls.name}")) return  // 已动态 hook
                            try {
                                val m = declCls.getDeclaredMethod("onBindViewHolder", vhClass, Int::class.javaPrimitiveType)
                                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                    // before: 在 App 渲染前填充 AgeHeightWeightView→uid 映射,
                                    //        让 AgeHeightWeightView 的 role 设置方法 (见 ⑤) 能查到缓存做源头替换
                                    override fun beforeHookedMethod(p: XC_MethodHook.MethodHookParam) {
                                        try {
                                            val itemView = XposedHelpers.getObjectField(p.args[0], "itemView") as? View ?: return
                                            val ctx = itemView.context
                                            if (!Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED, ctx)) return
                                            val position = p.args[1] as Int
                                            var item: Any? = null
                                            try { item = XposedHelpers.callMethod(adapter, "getItem", position) } catch (_: Throwable) {}
                                            val uid = when {
                                                item != null -> readUid(item).ifEmpty { readUidFromView(itemView) }
                                                else -> readUidFromView(itemView)
                                            }
                                            if (uid.isNotEmpty()) mapAhwvByUid(itemView, uid)
                                        } catch (_: Throwable) {}
                                    }
                                    override fun afterHookedMethod(p: XC_MethodHook.MethodHookParam) {
                                        try {
                                            val itemView = XposedHelpers.getObjectField(p.args[0], "itemView") as? View ?: return
                                            val ctx = itemView.context
                                            if (!Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED, ctx)) return
                                            val adapterName = adapterCls.name
                                            val position = p.args[1] as Int
                                            // item 可能拿不到 (非 BaseQuick 无 getItem) → 从 itemView 反推 uid
                                            var item: Any? = null
                                            try { item = XposedHelpers.callMethod(adapter, "getItem", position) } catch (_: Throwable) {}
                                            val uid = when {
                                                item != null -> readUid(item).ifEmpty { readUidFromView(itemView) }
                                                else -> readUidFromView(itemView)
                                            }
                                            val roleRaw = if (item != null) readRoleField(item) else ""
                                            if (uid.isEmpty()) return
                                            patchOtherTextView(itemView, uid, roleRaw, adapter, position, ctx)
                                        } catch (_: Throwable) {}
                                    }
                                })
                            } catch (_: Throwable) {}
                        } catch (_: Throwable) {}
                    }
                })
                XposedBridge.log("$TAG RecyclerView.setAdapter 兜底已挂载 (覆盖非BaseQuick列表, 如查看页/足迹)")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG RecyclerView 兜底挂载失败: $t")
            }

            // ==================== ⑤ AgeHeightWeightView role 源头替换 ====================
            //    查看页/来访页用此控件显示 role; App 在 onBindViewHolder 后会异步重渲染,
            //    post-render 改文本会被冲掉 → 直接 hook 控件的 role 设置方法, 在源头把 -1 换成缓存真实值。
            //    uid 通过 ahwvUidMap (onBindViewHolder before 填充) 关联到控件实例。
            try {
                val ahwvClass = lpparam.classLoader.loadClass("com.blued.android.module.common.view.AgeHeightWeightView")
                val ahwvMethods = ahwvClass.declaredMethods
                // hook role 设置方法, 源头替换 -1 → 缓存真实值。
                // AgeHeightWeightView 真实方法表: a(String,String,String,String) (age/height/weight/role 四合一) + applySkin()。
                //   → 匹配该 a 方法, 替换最后1个 String 参数 (role)。
                //   → 同时保留兼容: 单参 role/type 方法。
                var hooked = 0
                for (mm in ahwvMethods) {
                    val ptArr = mm.parameterTypes
                    val is4Str = ptArr.size == 4 && ptArr.all { it == String::class.java }
                    val is1Str = ptArr.size == 1 && ptArr[0] == String::class.java
                    val n = mm.name.lowercase()
                    val singleRole = ptArr.size == 1 && (n.contains("role") || n.contains("type"))
                    if (!is4Str && !singleRole) continue
                    val roleArgIdx = ptArr.size - 1   // 4参: 第3; 单参: 第0
                    try {
                        XposedBridge.hookMethod(mm, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                try {
                                    if (!Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED)) return
                                    val view = param.thisObject as? View ?: return
                                    val uid = ahwvUidMap[view] ?: return
                                    val incoming = param.args[roleArgIdx]?.toString() ?: return
                                    val isPlaceholder = incoming == "-1" || incoming == "-1.0" || incoming.isEmpty()
                                    if (!isPlaceholder) return
                                    val cached = roleCache[uid]
                                    if (cached.isNullOrEmpty() || cached == "-1" || cached == "-1.0") return
                                    param.args[roleArgIdx] = cached
                                } catch (_: Throwable) {}
                            }
                        })
                        hooked++
                    } catch (_: Throwable) {}
                }
                XposedBridge.log("$TAG AgeHeightWeightView 源头替换已挂载 (匹配${hooked}个方法)")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG AgeHeightWeightView hook失败: $t")
            }

            // ==================== ⑥ TextView.setText 源头替换 (终极方案) ====================
            //    查看/来访页 AgeHeightWeightView 的 role 文本由 App 直接调用 TextView.setText("其他") 设置,
            //    在 onBindViewHolder 后还会被重漄 → post-render 改文本会被冲掉。
            //    在源头拦截 setText: 当文本是「其他/未知/-1」且 TextView 在已映射 uid 的 AgeHeightWeightView 内时,
            //    用 roleCache 真实值替换 → App 原生渲染真实 role, 不会被重漄。
            //
            //    ★多类挂载: hookAllMethods 只 hook 目标类"自己声明"的方法。AHWV role 槽用的是皮肤/形状子类
            //      (ShapeTextView / SkinCompatTextView), 若它们 override 了 setText, 只 hook 基类 TextView 会漏。
            //      → 对 TextView + AppCompatTextView + SkinCompatTextView + ShapeTextView 全部挂载。
            //
            //    ★诊断: 只要 AHWV 内出现 "其他/未知" setText, 无论是否替换都一次性打印 tv 实际类名+uid+缓存,
            //      便于定位 "hook 抓没抓到 / 卡在哪一步"。
            val tvHook = fun(clazz: Class<*>, label: String) {
                try {
                    XposedBridge.hookAllMethods(clazz, "setText", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try {
                                val arg = if (param.args.isNotEmpty()) param.args[0] else return
                                if (arg !is CharSequence) return
                                // 热点路径: 先长度过滤再 toString ( setText 调用极频繁 )
                                val len = arg.length
                                if (len != 2 && len != 3 && len != 4) return
                                val s = arg.toString()
                                val isPlaceholder = s == "其他" || s == "未知" || s == "-1" ||
                                        s == "-1.0" || s.trim() == "其他" || s.trim() == "未知"
                                if (!isPlaceholder) return
                                val tv = param.thisObject as? TextView ?: return
                                // 祖先查找 AgeHeightWeightView (限深 12)
                                val ahwv = findAgeHeightWeightAncestor(tv) ?: return
                                val uid = ahwvUidMap[ahwv]
                                var cached = if (uid != null) roleCache[uid] else null
                                // ★ 查看页兜底: uid 未映射 / 缓存空时, 用兄弟 name_view 昵称查 nameToRole
                                if (cached.isNullOrEmpty() || cached == "-1" || cached == "-1.0") {
                                    val nm = findSiblingName(ahwv)
                                    if (nm.isNotEmpty()) {
                                        val nr = nameToRole[nm]
                                        if (!nr.isNullOrEmpty() && nr != "-1" && nr != "-1.0") cached = nr
                                    }
                                }
                                if (cached.isNullOrEmpty() || cached == "-1" || cached == "-1.0") return
                                param.args[0] = mapRole(cached)
                                ensureRoleSlotVisible(ahwv)
                            } catch (_: Throwable) {}
                        }
                    })
                    XposedBridge.log("$TAG [setText挂载] $label = ${clazz.name}")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG [setText挂载失败] $label: $t")
                }
            }
            val cl = lpparam.classLoader
            tvHook(cl.loadClass("android.widget.TextView"), "base-TextView")
            XposedHelpers.findClassIfExists("androidx.appcompat.widget.AppCompatTextView", cl)?.let { tvHook(it, "AppCompatTextView") }
            listOf(
                "skin.support.widget.SkinCompatTextView",
                "skin.support.app.SkinCompatTextView",
                "com.blued.android.framework.view.shape.ShapeTextView"
            ).forEach { name -> XposedHelpers.findClassIfExists(name, cl)?.let { tvHook(it, name.substringAfterLast('.')) } }
            XposedBridge.log("$TAG TextView.setText 源头替换已挂载 (多类: base/AppCompat/SkinCompat/Shape)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG 列表注入失败: $t")
        }

        // ==================== ② 资料页/列表 JSON role 补全 (Gson 拦截) ====================
        try {
            val gsonClass = XposedHelpers.findClassIfExists("com.google.gson.Gson", lpparam.classLoader) ?: return
            XposedBridge.hookAllMethods(gsonClass, "fromJson", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    try {
                        if (!Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED)) return
                        val json = param.args[0] as? String ?: return
                        // 快速过滤: 只处理含 role+uid 的 user JSON (排除广告/配置等)
                        if (!json.contains("\"role\"") || !json.contains("\"uid\"")) return
                        val enriched = enrichRoleJson(json)
                        if (enriched != null) param.args[0] = enriched
                    } catch (_: Throwable) {}
                }
            })
        } catch (_: Throwable) {}

        // ==================== ③ 资料页 role 直补 (UserInfoFragmentNew 渲染入口) ====================
        //    资料页 (social.irisgw.cn, app/1 UA) role 恒为 -1 → tv_basic_info_role 显示「其他」。
        //    Gson 拦截在主线程会异步化且资料页不会自动刷新 → 经常不生效。
        //    本 hook 在渲染入口 (单参 UserInfoEntity 方法, 如 c/j) 直接:
        //      before: 命中缓存 → 改 entity.role → App 原生渲染真实 role (即时);
        //      after : 找 tv_basic_info_role, 命中缓存即时补; 未命中异步查 /basic 后 post 回填。
        try {
            val userInfoFragClass = lpparam.classLoader.loadClass("com.soft.blued.ui.user.fragment.UserInfoFragmentNew")
            val userInfoEntityClass = lpparam.classLoader.loadClass("com.soft.blued.ui.user.model.UserInfoEntity")
            // 兼容多版本/混淆: hook 所有「单参 UserInfoEntity」方法 (c/j 等), 补全是幂等的。
            val renderMethods = userInfoFragClass.declaredMethods.filter {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == userInfoEntityClass
            }
            if (renderMethods.isNotEmpty()) {
                renderMethods.forEach { m ->
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try {
                                if (!Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED)) return
                                val entity = param.args[0] ?: return
                                val uid = readUid(entity)
                                if (uid.isEmpty()) return
                                val roleRaw = readRoleField(entity)
                                val isPlaceholder = roleRaw == "-1" || roleRaw == "-1.0" || roleRaw.isEmpty()
                                if (!isPlaceholder) return
                                val cached = roleCache[uid]
                                if (cached != null && cached.isNotEmpty() && cached != "-1" && cached != "-1.0") {
                                    setAllRoleFields(entity, cached, mapRole(cached))
                                }
                            } catch (_: Throwable) {}
                        }
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try {
                                if (!Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED)) return
                                val entity = param.args[0] ?: return
                                val uid = readUid(entity)
                                if (uid.isEmpty()) return
                                val rootView = XposedHelpers.callMethod(param.thisObject, "getView") as? View ?: return
                                patchProfileRoleView(rootView, uid, entity)
                            } catch (_: Throwable) {}
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // ==================== ⑦ 查看页(谁看过我) role 直显 (Activity 驱动 + 解密注入 双保险) ====================
        //   ★根因★: 查看页用新版 social.irisgw.cn 接口, 服务端对极速版返回 en_data / role=-1 → 全显「其他」。
        //   旧方案靠 adapter 名(Visitor/Visit/...)触发批量拉取, 但查看页 adapter 多被混淆 → 永不触发, 缓存恒空。
        //
        //   ★本方案 (不依赖 adapter 名, 直接驱动)★:
        //   A) VisitHistoryActivity.onResume → 触发旧版 social.blued.cn 独立请求 (app/7 全量 UA, 明文真实 role)
        //      灌满 roleCache + nameToRole; 并延时直扫 tv_role 把已渲染的「其他」改回真实 role。
        //   B) 解密入口 c.I111I1lI1I1 命中 /visited → 同步灌缓存 + 改写明文 role (en_data 路径保险)。
        //   C) TextView.setText(⑥) + AgeHeightWeightView(⑤) 全局源头替换, 命中缓存即原生渲染;
        //      uid 未映射时用「昵称→role」(nameToRole) 兜底, 解决查看页 item 无 uid tag 的情况。

        // A) Activity.onResume: 可靠触发 (用户进查看页必经), 不依赖 adapter 混淆名
        try {
            val visitActCls = XposedHelpers.findClassIfExists(
                "com.soft.blued.ui.find.VisitHistoryActivity", lpparam.classLoader)
            if (visitActCls != null) {
                XposedBridge.hookAllMethods(visitActCls, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED)) return
                            val act = param.thisObject as? android.app.Activity ?: return
                            val ctx = act.applicationContext
                            triggerVisitedFetch(ctx)
                            val root = runCatching { act.window?.decorView }.getOrNull() ?: return
                            // 延时直扫: 覆盖列表异步渲染 + 拉取完成后的回填 (多档兜底)
                            root.postDelayed({ applyRolesToVisitedPage(root) }, 600)
                            root.postDelayed({ applyRolesToVisitedPage(root) }, 1400)
                            root.postDelayed({ applyRolesToVisitedPage(root) }, 2500)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG [查看页 onResume] 异常: $t")
                        }
                    }
                })
                XposedBridge.log("$TAG ⑦-A VisitHistoryActivity.onResume hook 已挂载")
            } else {
                XposedBridge.log("$TAG ⑦-A VisitHistoryActivity 未找到 (混淆? 将仅靠解密+列表兜底)")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG ⑦-A onResume hook 失败: $t")
        }

        // B) 解密入口: 命中 /visited 时同步灌缓存 + 改写明文 (en_data 路径的保险)
        try {
            val cCls = XposedHelpers.findClassIfExists(
                "com.blued.android.http.encode.utils.c", lpparam.classLoader)
            if (cCls != null) {
                XposedHelpers.findAndHookMethod(cCls, "I111I1lI1I1",
                    String::class.java, ByteArray::class.java, String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                if (!Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED)) return
                                val url = param.args.getOrNull(2) as? String ?: return
                                if (!url.contains("/visited")) return
                                val plain = param.result as? String ?: return
                                if (plain.isEmpty() || !plain.contains("\"role\"")) return
                                val ctx = currentCtx() ?: return
                                ensureVisitedRolesSync(ctx)            // 同步灌满 roleCache (旧版明文接口)
                                val rewritten = injectRolesIntoUserJson(plain)  // 改写明文 role=-1 → 真实值
                                if (rewritten != null) param.result = rewritten
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG [visited解密注入] 异常: $t")
                            }
                        }
                    })
                XposedBridge.log("$TAG ⑦-B 解密注入 hook 已挂载 (c.I111I1lI1I1)")
            } else {
                XposedBridge.log("$TAG ⑦-B c.java 未找到, 跳过解密注入")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG ⑦-B 解密注入 hook 失败: $t")
        }
    }

    /**
     * 拦截 Gson.fromJson: 把响应 JSON 里 role=-1 的用户, 用 roleCache 真实值替换。
     * ★ 资料页/单用户 (data=对象 或 数组长度≤2): 同步查 /basic → 首次即显示真实 role。
     * ★ 大列表 (data=数组, 长度>2): 异步查 → 首次放过, 下次刷新命中缓存生效。
     */
    private fun enrichRoleJson(json: String): String? {
        val obj = JSONObject(json)
        val data = obj.opt("data") ?: return null
        val ctx = currentCtx() ?: return null
        return when (data) {
            is JSONObject -> enrichSingleUser(data, ctx, obj)                          // 资料页 (对象)
            is org.json.JSONArray -> {
                if (data.length() <= 2) enrichSmallArray(data, ctx, obj)              // 资料页 (数组包装) 同步
                else enrichUserList(data, ctx, obj)                                   // 来访/查看列表 异步
            }
            else -> null
        }
    }

    /** 资料页单用户 (data=对象): 同步查 /basic, 首次即真实 role (仅非主线程阻塞)。 */
    private fun enrichSingleUser(u: JSONObject, ctx: android.content.Context, root: JSONObject): String? {
        val uid = u.optString("uid", "")
        if (uid.isEmpty()) return null
        val role = u.optString("role", "")
        if (role != "-1" && role != "-1.0" && role.isNotEmpty()) return null  // 已是真实值
        return syncFillRole(u, uid, role, ctx, root, "资料页对象")
    }

    /** 资料页 (data=小数组, 长度≤2): 同步查, 首次即真实 role。 */
    private fun enrichSmallArray(dataArr: org.json.JSONArray, ctx: android.content.Context, root: JSONObject): String? {
        var changed = false
        for (i in 0 until dataArr.length()) {
            val u = dataArr.optJSONObject(i) ?: continue
            val uid = u.optString("uid", "")
            if (uid.isEmpty()) continue
            val role = u.optString("role", "")
            if (role != "-1" && role != "-1.0" && role.isNotEmpty()) continue
            if (syncFillRole(u, uid, role, ctx, root, "资料页小数组") != null) changed = true
        }
        return if (changed) root.toString() else null
    }

    /** 同步查询并填充 role (非主线程阻塞, 资料页专用)。返回非 null 表示有改动。 */
    private fun syncFillRole(u: JSONObject, uid: String, oldRole: String, ctx: android.content.Context, root: JSONObject, tag: String): String? {
        // 缓存命中: 直接改
        val cached = roleCache[uid]
        if (cached != null && cached.isNotEmpty() && cached != "-1" && cached != "-1.0") {
            u.put("role", cached)
            return root.toString()
        }
        // 主线程不阻塞 → 异步, 下次生效
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (fetchingUids.add(uid)) ioPool.execute { try { fetchRoleRaw(uid, ctx) } catch (_: Throwable) {} finally { fetchingUids.remove(uid) } }
            return null
        }
        // 非主线程: 同步查
        if (!fetchingUids.add(uid)) {
            return null
        }
        val raw = try { fetchRoleRaw(uid, ctx) } finally { fetchingUids.remove(uid) }
        if (raw.isNotEmpty() && raw != "-1" && raw != "-1.0") {
            u.put("role", raw)
            return root.toString()
        }
        return null
    }

    /** 列表多用户: 异步批量查 /basic, 首次放过, 下次刷新生效 (命中缓存的即时改)。 */
    private fun enrichUserList(dataArr: org.json.JSONArray, ctx: android.content.Context, root: JSONObject): String? {
        if (dataArr.length() == 0) return null
        var changed = false
        val needFetchUids = mutableListOf<String>()
        for (i in 0 until dataArr.length()) {
            val u = dataArr.optJSONObject(i) ?: continue
            val uid = u.optString("uid", "")
            if (uid.isEmpty()) continue
            val role = u.optString("role", "")
            if (role != "-1" && role != "-1.0" && role.isNotEmpty()) continue
            val real = roleCache[uid]
            if (real != null && real.isNotEmpty() && real != "-1" && real != "-1.0") {
                u.put("role", real); changed = true
            } else if (!roleCache.containsKey(uid)) {
                needFetchUids.add(uid)
            }
        }
        if (needFetchUids.isNotEmpty()) {
            ioPool.execute {
                needFetchUids.forEach { uid ->
                    if (fetchingUids.add(uid)) {
                        try { fetchRoleRaw(uid, ctx) } catch (_: Throwable) {} finally { fetchingUids.remove(uid) }
                    }
                }
            }
        }
        return if (changed) root.toString() else null
    }

    private fun currentCtx(): android.content.Context? = try {
        val cl = hostClassLoader ?: ClassLoader.getSystemClassLoader()
        XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", cl),
            "currentApplication"
        ) as? android.content.Context
    } catch (_: Throwable) { null }

    /** 读列表项 uid (UserBasicModel 多种字段名兼容)。 */
    private fun readUid(item: Any): String {
        val fields = arrayOf(
            "uid", "user_id", "userId", "blued_uid",
            "visitor_uid", "visit_uid", "target_uid", "to_uid", "u_id", "bluedUid", "uidStr", "suid"
        )
        // 1. 直接字段 (String / long / Long / int 均通过 getObjectField+toString 兼容)
        for (f in fields) {
            try {
                val v = XposedHelpers.getObjectField(item, f)?.toString()
                if (!v.isNullOrEmpty() && v != "0") return v
            } catch (_: Throwable) {}
        }
        // 2. 一层嵌套: 来访页模型常把 uid 放在子对象里 (visitor/user/userBasic/anchor …)
        var cls: Class<*>? = item.javaClass
        val visited = java.util.HashSet<Class<*>>()
        while (cls != null && cls != Any::class.java && visited.add(cls)) {
            for (fd in cls.declaredFields) {
                val ft = fd.type
                if (ft == String::class.java || ft.isPrimitive || ft.isArray ||
                    ft.name.startsWith("android.") || ft.name.startsWith("java.") ||
                    ft.name.startsWith("com.blued.android.framework.") ||
                    java.util.Collection::class.java.isAssignableFrom(ft) ||
                    java.util.Map::class.java.isAssignableFrom(ft)) continue
                try {
                    fd.isAccessible = true
                    val child = fd.get(item) ?: continue
                    for (f in fields) {
                        try {
                            val v = XposedHelpers.getObjectField(child, f)?.toString()
                            if (!v.isNullOrEmpty() && v != "0") return v
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
            cls = cls.superclass
        }
        return ""
    }

    /** 读列表项 role (兼容 String/Double/Int 字段类型)。 */
    private fun readRoleField(item: Any): String {
        return try {
            when (val v = XposedHelpers.getObjectField(item, "role")) {
                is String -> v
                is Number -> v.toString()
                null -> ""
                else -> v.toString()
            }
        } catch (_: Throwable) { "" }
    }


    /** 递归找声明了「非 abstract onBindViewHolder(VH,int)」的类 (子类自己 override 的实现, 有 code 体)。 */
    private fun findOnBindDeclaringClass(cls: Class<*>, vhClass: Class<*>): Class<*>? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            try {
                val m = c.getDeclaredMethod("onBindViewHolder", vhClass, Int::class.javaPrimitiveType)
                if (!java.lang.reflect.Modifier.isAbstract(m.modifiers)) return c
            } catch (_: NoSuchMethodException) {}
            c = c.superclass
        }
        return null
    }

    /** 从 itemView 及其子 view 的 tag 反推 uid (非 BaseQuick 列表常把 uid/model 存在 view.tag)。 */
    private fun readUidFromView(root: View): String {
        val fromRoot = uidFromTagValue(root.tag)
        if (fromRoot.isNotEmpty()) return fromRoot
        val out = ArrayList<View>()
        collectViewsForTag(root, out, 0)
        for (v in out) {
            val u = uidFromTagValue(v.tag)
            if (u.isNotEmpty()) return u
        }
        return ""
    }

    private fun uidFromTagValue(tag: Any?): String {
        if (tag == null) return ""
        when (tag) {
            is String -> if (tag.matches(Regex("\\d{4,}"))) return tag
            is Number -> { val s = tag.toString(); if (s.matches(Regex("\\d{4,}"))) return s }
            else -> return readUid(tag)   // 复杂对象: 复用字段名查找 (uid/visitor_uid/嵌套)
        }
        return ""
    }

    private fun collectViewsForTag(root: View, out: MutableList<View>, depth: Int) {
        if (depth > 6) return
        out.add(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i) ?: continue
                collectViewsForTag(c, out, depth + 1)
            }
        }
    }

    /** 遍历 item 所有字段 (含父类), 名字含 role 的全部改成真实值。 */
    private fun setAllRoleFields(item: Any, realRole: String, displayText: String): Boolean {
        var any = false
        var cls: Class<*>? = item.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                val n = f.name
                if (!n.lowercase().contains("role")) continue
                try {
                    f.isAccessible = true
                    when (f.type) {
                        java.lang.String::class.java -> {
                            // 字段名含 name → 是显示文字字段, 写 mapRole 后的显示文本; 否则写原始 role 值
                            f.set(item, if (n.lowercase().contains("name")) displayText else realRole)
                            any = true
                        }
                        java.lang.Double::class.java, Double::class.java, java.lang.Double.TYPE -> {
                            realRole.toDoubleOrNull()?.let { f.set(item, it); any = true }
                        }
                        java.lang.Integer::class.java, Int::class.java, Integer.TYPE -> {
                            realRole.toIntOrNull()?.let { f.set(item, it); any = true }
                        }
                    }
                } catch (_: Throwable) {}
            }
            cls = cls.superclass
        }
        return any
    }

    /** 写列表项 role (递归类层级查找字段, 兼容父类声明 + String/Double/Int 类型)。 */
    private fun setRoleField(item: Any, value: String): Boolean {
        // role 字段可能声明在父类 (如 UserBasicModel), getDeclaredField 不递归 → 必须手动向上找
        var cls: Class<*>? = item.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                val field = cls.getDeclaredField("role")
                field.isAccessible = true
                val typed: Any = when (field.type) {
                    java.lang.String::class.java -> value
                    java.lang.Double::class.java, Double::class.java, java.lang.Double.TYPE ->
                        value.toDoubleOrNull() ?: return false
                    java.lang.Integer::class.java, Int::class.java, Integer.TYPE ->
                        value.toIntOrNull() ?: return false
                    else -> value
                }
                field.set(item, typed)
                return true
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass   // 当前类没声明 role, 往父类找
            } catch (_: Throwable) { break }
        }
        // fallback: XposedHelpers 递归写入 (String)
        return try { XposedHelpers.setObjectField(item, "role", value); true } catch (_: Throwable) { false }
    }

    /** 列表 role 是占位值时, 异步查 /basic 补全, 回填到 roleView (校验 view 归属)。 */
    private fun maybeEnrichRole(uid: String, roleView: TextView, ctx: android.content.Context, listDisplay: String) {
        if (uid.isEmpty()) return
        val isPlaceholder = listDisplay == "其他" || listDisplay == "-1" || listDisplay == "未知" || listDisplay.isEmpty()
        if (!isPlaceholder) return
        viewUidMap[roleView] = uid
        roleCache[uid]?.let { cached -> roleView.text = mapRole(cached); return }
        if (!fetchingUids.add(uid)) return
        ioPool.execute {
            try {
                val raw = fetchRoleRaw(uid, ctx)
                if (raw.isNotEmpty()) {
                    roleCache[uid] = raw
                    val v = roleView
                    v.post { if (viewUidMap[v] == uid) v.text = mapRole(raw) }
                }
            } catch (_: Throwable) {} finally { fetchingUids.remove(uid) }
        }
    }

    /** 查 users/$uid/basic (app/7 UA), 返回原始 roleRaw (空串=失败)。结果存 roleCache。 */
    private fun fetchRoleRaw(uid: String, ctx: android.content.Context): String {
        val token = Config.getAuthToken(ctx)
        if (token.isEmpty()) return ""
        val ua = if (NetworkSpoofHook.capturedLatestUA.isNotEmpty()) {
            NetworkSpoofHook.capturedLatestUA
        } else {
            val r = android.os.Build.VERSION.RELEASE ?: "13"
            val m = android.os.Build.MODEL ?: "X"
            val id = android.os.Build.ID ?: "X"
            "Mozilla/5.0 (Linux; U; Android $r; $m Build/$id) Android/070647_7.64.7_2842_0221 (Asia/Shanghai) Dalvik/2.1.0 app/7"
        }
        val code: Int
        val body: String
        try {
            val conn = (URL("https://argo.blued.cn/users/$uid/basic").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("authorization", token)
                setRequestProperty("user-agent", ua)
                connectTimeout = 5000; readTimeout = 5000
                useCaches = false
            }
            code = conn.responseCode
            if (code != 200) return ""
            body = conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            return ""
        }
        val json = try { JSONObject(body) } catch (_: Throwable) {
            return ""
        }
        val data = json.opt("data")
        val raw: String = when (data) {
            is org.json.JSONArray -> data.optJSONObject(0)?.optString("role") ?: ""
            is JSONObject -> data.optString("role")
            else -> ""
        }
        if (raw.isNotEmpty()) roleCache[uid] = raw
        return raw
    }

    // ========================================================================
    //  查看页(谁看过我)批量拉取真实 role
    //  ---------------------------------------------------------------------
    //  ★ 使用旧版接口 social.blued.cn ★
    //    新版 social.irisgw.cn 服务端启用了 bcencode 加密, 响应体为 en_data 密文,
    //    parseVisitedRoles 无法解析 → 改用旧版 social.blued.cn (无加密, 明文 JSON),
    //    配合旧版 conn_type=6 / channel=a9999a, UA 仍 app/7 → 列表响应自带真实 role。
    //  接口: GET https://social.blued.cn/users/{myUid}/visited?page=N&size=50
    //  变量 (运行时替换):
    //    - 路径 {myUid}      : UserInfo.getInstance().getLoginUserInfo().getUid()
    //    - header authorization : Config.getAuthToken()
    //    - query latitude/longitude : Config.getCustomLat/Lng (当前定位)
    //    - query page/size  : 分页 (拉前 3 页共 150 人)
    //  其余 query (channel/model/os 等) 用合理常量, 服务端不强校验。
    // ========================================================================

    /** 取当前登录用户 uid (反射 UserInfo.getInstance().getLoginUserInfo().getUid())。 */
    private fun getMyUid(): String? = try {
        val cl = hostClassLoader ?: ClassLoader.getSystemClassLoader()
        val userInfoClass = XposedHelpers.findClass(
            "com.blued.android.module.common.user.model.UserInfo", cl)
        val inst = XposedHelpers.callStaticMethod(userInfoClass, "getInstance") ?: return null
        val loginUser = XposedHelpers.callMethod(inst, "getLoginUserInfo") ?: return null
        // getUid() 可能返回 String / Long / Int, 统一转字符串
        val uid = XposedHelpers.callMethod(loginUser, "getUid") ?: return null
        when (uid) {
            is String -> uid.takeIf { it.isNotEmpty() }
            is Number -> uid.toString().takeIf { it.isNotEmpty() }
            else -> uid.toString().takeIf { it.isNotEmpty() }
        }
    } catch (_: Throwable) { null }

    /**
     * 查看页首次绑定时触发批量拉取 (含 5 分钟节流, 含 adapter 名判定)。
     * 命中 Visitor/Visit/Track/Footprint/Browse 的 adapter 才触发。
     */
    private fun maybeTriggerVisitedBatch(adapterName: String, ctx: android.content.Context) {
        val isVisitPage = adapterName.contains("Visitor") || adapterName.contains("Visit") ||
            adapterName.contains("Track") || adapterName.contains("Footprint") || adapterName.contains("Browse")
        if (!isVisitPage) return
        // 节流: 同会话 5 分钟内不重复 (用户退出再进查看页也会刷新)
        val now = SystemClock.elapsedRealtime()
        if (now - visitedBatchAt < 5 * 60 * 1000L) return
        val myUid = getMyUid() ?: return  // 取不到 uid 则不更新时间戳, 下次重试
        visitedBatchAt = now
        ioPool.execute {
            try {
                fetchVisitedRolesBatch(myUid, ctx)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG 查看页批量拉取异常: $t")
            }
        }
    }

    /**
     * 批量拉取"查看页(谁看过我)"所有用户的真实 role, 灌入 [roleCache]。
     * @return 成功入缓存的 role 条数
     */
    private fun fetchVisitedRolesBatch(myUid: String, ctx: android.content.Context): Int {
        val token = Config.getAuthToken(ctx)
        if (token.isEmpty()) return 0
        val lat = Config.getCustomLat(ctx)
        val lng = Config.getCustomLng(ctx)
        val ua = if (NetworkSpoofHook.capturedLatestUA.isNotEmpty()) {
            NetworkSpoofHook.capturedLatestUA
        } else {
            val r = Build.VERSION.RELEASE ?: "13"
            val m = Build.MODEL ?: "X"
            val id = Build.ID ?: "X"
            "Mozilla/5.0 (Linux; U; Android $r; $m Build/$id) Android/070647_7.64.7_2842_0221 (Asia/Shanghai) Dalvik/2.1.0 app/7"
        }
        var total = 0
        // 拉前 3 页 (每页 50, 最多 150 人); 空页/出错则提前终止
        for (page in 1..3) {
            val urlStr = buildString {
                // ★旧版接口: social.blued.cn 无 bcencode 加密, 返回明文 JSON
                //   (新版 irisgw.cn 返回 en_data 密文, parseVisitedRoles 无法解析)
                append("https://social.blued.cn/users/").append(myUid)
                append("/visited?conn_type=6&country=CN")
                append("&latitude=").append(lat)
                append("&longitude=").append(lng)
                append("&channel=a9999a&page=").append(page).append("&size=50")
                append("&h=2482&w=1220&os=Android")
            }
            val filled = try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("authorization", token)
                    setRequestProperty("user-agent", ua)
                    setRequestProperty("accept", "*/*")
                    setRequestProperty("accept-language", "zh-cn")
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("accept-encoding", "identity")  // 不要 gzip, 直接拿明文
                    connectTimeout = 5000
                    readTimeout = 5000
                    useCaches = false
                }
                val code = conn.responseCode
                if (code != 200) { conn.disconnect(); break }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                parseVisitedRoles(body)
            } catch (_: Throwable) { break }
            if (filled == 0) break  // 没有更多数据
            total += filled
        }
        return total
    }

    /** 解析 visited 响应, 提取每个用户的 uid+role 灌入 [roleCache]。返回入缓存的条数。 */
    private fun parseVisitedRoles(body: String): Int {
        val json = try { JSONObject(body) } catch (_: Throwable) { return 0 }
        val data = json.opt("data") ?: return 0
        // data 可能是数组, 也可能是对象(含 list/visitors/data 数组)
        val arr = when (data) {
            is org.json.JSONArray -> data
            is JSONObject -> data.optJSONArray("list")
                ?: data.optJSONArray("visitors")
                ?: data.optJSONArray("data")
                ?: return 0
            else -> return 0
        }
        var count = 0
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i) ?: continue
            // visited 接口的用户可能直接是用户对象, 也可能嵌套在 visitor/user 字段里
            val owner = u.optJSONObject("visitor") ?: u.optJSONObject("user") ?: u
            val uid = owner.optString("uid", "").ifEmpty { u.optString("uid", "") }
            if (uid.isEmpty()) continue
            val role = owner.optString("role", "").ifEmpty { u.optString("role", "") }
            if (role.isNotEmpty() && role != "-1" && role != "-1.0") {
                roleCache[uid] = role
                val nm = owner.optString("name", "").ifEmpty { u.optString("name", "") }
                if (nm.isNotEmpty()) nameToRole[nm] = role
                count++
            }
        }
        return count
    }

    /**
     * 同步确保 visited role 缓存就绪 (查看页解密注入专用)。
     * 在解密线程同步拉取旧版 social.blued.cn/users/{myUid}/visited (明文 + 真实 role),
     * 灌入 [roleCache]。含 5 分钟节流 + 缓存非空校验: 命中(新鲜且非空)则秒回不阻塞。
     * 解密运行在 OkHttp 网络线程, 阻塞数百毫秒可接受; 独立 HttpURLConnection 不走 App
     * OkHttp, 无 UA 重算 / 无递归解密, 安全。
     */
    private fun ensureVisitedRolesSync(ctx: android.content.Context) {
        val now = SystemClock.elapsedRealtime()
        if (roleCache.isNotEmpty() && now - visitedBatchAt < 5 * 60 * 1000L) return
        try {
            val myUid = getMyUid() ?: return
            visitedBatchAt = now
            fetchVisitedRolesBatch(myUid, ctx)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [visited同步拉取] 异常: $t")
        }
    }

    /**
     * 把 JSON 明文里 role 为占位值(-1 / -1.0 / 空) 的用户, 用 [roleCache] 真实值替换。
     * 兼容 data 为「数组」与「对象(含 list/visitors/data 数组)」两种结构,
     * 兼容用户对象直接在数组里或嵌套在 visitor/user 字段里。
     * @return 改写后的完整 JSON 字符串; 无可改写项返回 null。
     */
    private fun injectRolesIntoUserJson(json: String): String? {
        val obj = try { JSONObject(json) } catch (_: Throwable) { return null }
        val data = obj.opt("data") ?: return null
        val arr = when (data) {
            is org.json.JSONArray -> data
            is JSONObject -> data.optJSONArray("list")
                ?: data.optJSONArray("visitors")
                ?: data.optJSONArray("data")
                ?: return null
            else -> return null
        }
        var changed = false
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i) ?: continue
            val owner = u.optJSONObject("visitor") ?: u.optJSONObject("user") ?: u
            val uid = owner.optString("uid", "").ifEmpty { u.optString("uid", "") }
            if (uid.isEmpty()) continue
            val role = owner.optString("role", "").ifEmpty { u.optString("role", "") }
            if (role != "-1" && role != "-1.0" && role.isNotEmpty()) continue   // 已是真实值, 不动
            val cached = roleCache[uid]
            if (cached.isNullOrEmpty() || cached == "-1" || cached == "-1.0") continue
            owner.put("role", cached)
            if (owner !== u) u.put("role", cached)
            changed = true
        }
        return if (changed) obj.toString() else null
    }

    /**
     * 触发查看页(谁看过我)真实 role 批量拉取 (异步; Activity.onResume / 解密 双触发共用)。
     * 用旧版 social.blued.cn + app/7 全量 UA 的独立请求拿明文真实 role, 灌入 [roleCache] + [nameToRole]。
     * 含 5 分钟节流: 缓存非空且新鲜则跳过; 缓存空(失败/首次)则每次 onResume 都重试, 保证最终拿到。
     */
    private fun triggerVisitedFetch(ctx: android.content.Context) {
        val now = SystemClock.elapsedRealtime()
        if (roleCache.isNotEmpty() && nameToRole.isNotEmpty() && now - visitedBatchAt < 5 * 60 * 1000L) return
        val myUid = getMyUid() ?: run {
            XposedBridge.log("$TAG [查看页拉取] getMyUid()=null, 跳过")
            return
        }
        visitedBatchAt = now
        ioPool.execute {
            try {
                fetchVisitedRolesBatch(myUid, ctx)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [查看页拉取] 异常: $t")
            }
        }
    }

    /**
     * 从 AgeHeightWeightView 向上找最近的同时含 name_view 的祖先, 读出用户昵称。
     * 查看页 item 布局: name_view 与 ahwv_personal_info 是同一竖向 LinearLayout 的子节点,
     * 故爬到该 LinearLayout 时 findViewById(name_view) 即命中, 不会越界到兄弟 item。
     */
    private fun findSiblingName(ahwv: View): String {
        val ctx = ahwv.context
        val nameId = ctx.resources.getIdentifier("name_view", "id", ctx.packageName)
        if (nameId == 0) return ""
        var p: View? = ahwv
        var depth = 0
        while (p != null && depth < 10) {
            val found = p.findViewById<View>(nameId) as? TextView
            if (found != null) return found.text?.toString()?.trim() ?: ""
            val parent = p.parent
            p = if (parent is View) parent else null
            depth++
        }
        return ""
    }

    /** 让 AgeHeightWeightView 内的 ll_role_info 可见 (查看页 role 槽默认 gone, 显示真实 role 时需打开)。 */
    private fun ensureRoleSlotVisible(ahwv: View) {
        try {
            val ctx = ahwv.context
            val llRoleId = ctx.resources.getIdentifier("ll_role_info", "id", ctx.packageName)
            if (llRoleId == 0) return
            val llRole = ahwv.findViewById<View>(llRoleId) ?: return
            if (llRole.visibility != View.VISIBLE) llRole.visibility = View.VISIBLE
        } catch (_: Throwable) {}
    }

    /**
     * 查看页 UI 直扫兜底: 遍历根视图所有 tv_role, 按 uid(ahwvUidMap) 或 昵称(nameToRole)
     * 回填真实 role 文本, 并打开 ll_role_info。在 onResume + 拉取完成后延时调用,
     * 覆盖「列表先于缓存渲染了 其他, 之后不自动重绘」的情况。
     */
    private fun applyRolesToVisitedPage(rootView: View) {
        try {
            val ctx = rootView.context
            if (!Config.isFeatureEnabled(NetworkSpoofHook.KEY_ENABLED, ctx)) return
            val tvRoleId = ctx.resources.getIdentifier("tv_role", "id", ctx.packageName)
            if (tvRoleId == 0) return
            val roleTvs = ArrayList<TextView>()
            findAllViewById(rootView, tvRoleId, roleTvs)
            if (roleTvs.isEmpty()) return
            for (tv in roleTvs) {
                val ahwv = findAgeHeightWeightAncestor(tv) ?: continue
                val uid = ahwvUidMap[ahwv]
                var role = if (uid != null) roleCache[uid] else null
                if (role.isNullOrEmpty() || role == "-1" || role == "-1.0") {
                    val nm = findSiblingName(ahwv)
                    if (nm.isNotEmpty()) role = nameToRole[nm]
                }
                if (role.isNullOrEmpty() || role == "-1" || role == "-1.0") continue
                val display = mapRole(role)
                ensureRoleSlotVisible(ahwv)
                if (tv.text?.toString()?.trim() != display) tv.text = display
            }
        } catch (_: Throwable) {}
    }

    /** 递归找出 root 子树内所有 id == targetId 的 View (限深 12, 防深层级爆炸)。泛型 out 便于收集特定子类 (如 TextView)。 */
    private fun <T : View> findAllViewById(root: View, targetId: Int, out: MutableList<T>, depth: Int = 0) {
        if (depth > 12) return
        @Suppress("UNCHECKED_CAST")
        if (root.id == targetId) out.add(root as T)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i) ?: continue
                findAllViewById(c, targetId, out, depth + 1)
            }
        }
    }

    /** 原始 roleRaw → 显示 (与 ChatWatermarkHook 一致)。 */
    private fun mapRole(role: String): String = when (role) {
        "-1", "-1.0" -> "其他"
        "1", "1.0" -> "1"
        "-2", "-2.0" -> "side"
        "0", "0.0" -> "0"
        "0.5" -> "0.5"
        "0.75" -> "0.75"
        "0.25" -> "0.25"
        "~" -> "保密"
        "" -> "其他"
        else -> role
    }

    /** 取当前生效 role: 优先 item 自带真实值, 其次缓存; 全是占位则返回 null。 */
    private fun currentEffectiveRole(uid: String, itemRoleRaw: String): String? {
        if (itemRoleRaw.isNotEmpty() && itemRoleRaw != "-1" && itemRoleRaw != "-1.0") return itemRoleRaw
        val c = roleCache[uid]
        return if (c != null && c.isNotEmpty() && c != "-1" && c != "-1.0") c else null
    }

    /**
     * 来访/查看页: App 原生显示「其他」(role=-1 的格式化文本, 多在 AgeHeightWeightView 内)。
     * 扫描 itemView 找文本为「其他」的 TextView 替换为真实 role。
     * ★ AgeHeightWeightView 等控件会在 onBindViewHolder 之后异步设置文本 →
     *   立即补一次 + 延迟再补 (200ms / 600ms), ViewHolder 复用以 itemViewUidMap 校验防错位。
     * 缓存命中即时; 未命中异步查 /basic, 查到后再延迟补 + 触发原生重绑 (hookBefore 用缓存改 item.role)。
     */
    private fun patchOtherTextView(
        itemView: View, uid: String, itemRoleRaw: String,
        adapter: Any, position: Int, ctx: android.content.Context
    ) {
        itemViewUidMap[itemView] = uid
        val patchRunnable = Runnable {
            try {
                if (itemViewUidMap[itemView] != uid) return@Runnable   // ViewHolder 已被复用给别的 uid
                val effective = currentEffectiveRole(uid, itemRoleRaw) ?: return@Runnable
                val display = mapRole(effective)
                val targets = collectRoleTextViews(itemView, ctx)
                if (targets.isNotEmpty()) {
                    targets.forEach { tv -> if (tv.text?.toString()?.trim() != display) tv.text = display }
                }
            } catch (_: Throwable) {}
        }
        // 立即补一次 (命中缓存即时生效) + 延迟再补 (兜 AgeHeightWeightView 异步设置文本)
        patchRunnable.run()
        itemView.postDelayed(patchRunnable, 200)
        itemView.postDelayed(patchRunnable, 600)
        // 未命中缓存: 异步查 /basic, 查到后再延迟补, 并触发原生重绑 (AgeHeightWeightView 原生渲染真实 role)
        if (currentEffectiveRole(uid, itemRoleRaw) == null && fetchingUids.add(uid)) {
            ioPool.execute {
                try {
                    val raw = fetchRoleRaw(uid, ctx)
                    if (raw.isNotEmpty() && raw != "-1" && raw != "-1.0") {
                        itemView.postDelayed(patchRunnable, 50)
                        itemView.postDelayed(patchRunnable, 400)
                        itemView.post {
                            try { XposedHelpers.callMethod(adapter, "notifyItemChanged", position) }
                            catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {} finally { fetchingUids.remove(uid) }
            }
        }
    }

    /**
     * 资料页: 补 tv_basic_info_role。
     * 命中缓存即时改文本; 未命中且当前显示占位「其他」→ 异步查 /basic, 查到后 post 回填 (校验仍附着)。
     * 已是真实 role (App 原生渲染 / beforeHook 已改 entity.role) 则不动。
     */
    private fun patchProfileRoleView(rootView: View, uid: String, entity: Any) {
        val ctx = rootView.context
        val roleId = ctx.resources.getIdentifier("tv_basic_info_role", "id", ctx.packageName)
        val roleView: TextView? = if (roleId != 0) rootView.findViewById(roleId) else null
        val effective = currentEffectiveRole(uid, readRoleField(entity))
        if (effective != null) {
            val display = mapRole(effective)
            if (roleView != null && roleView.text?.toString()?.trim() != display) {
                roleView.text = display
            }
            return
        }
        // 未命中: 仅当 tv_basic_info_role 确实显示占位时才异步查 (避免覆盖已正确的原生渲染)
        if (roleView == null) return
        val currentText = roleView.text?.toString()?.trim() ?: ""
        if (currentText.isNotEmpty() && currentText != "其他" && currentText != "-1" && currentText != "未知") return
        if (fetchingUids.add(uid)) {
            ioPool.execute {
                try {
                    val raw = fetchRoleRaw(uid, ctx)
                    if (raw.isNotEmpty() && raw != "-1" && raw != "-1.0") {
                        val display = mapRole(raw)
                        roleView.post {
                            try { if (roleView.isAttachedToWindow) roleView.text = display }
                            catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {} finally { fetchingUids.remove(uid) }
            }
        }
    }

    /** 找出 itemView 内所有 AgeHeightWeightView 后代, 绑定到 uid (供 ⑤ 源头替换查缓存)。 */
    private fun mapAhwvByUid(root: View, uid: String) {
        if (uid.isEmpty()) return
        val stack = ArrayDeque<View>(); stack.addLast(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v.javaClass.name.contains("AgeHeightWeightView")) ahwvUidMap[v] = uid
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i) ?: continue
                    stack.addLast(c)
                }
            }
        }
    }

    /**
     * 收集 itemView 内所有「role 文本承载位」(去重):
     *  ① 按资源 id tv_basic_info_role 精准定位 (dating_list_view_layout / layout_userinfo_top 等共用此 id)
     *     → 不依赖当前文本内容, 无论 App 异步何时设置都能稳定改对。
     *  ② 文本占位词扫描 (AgeHeightWeightView 内显示「其他」的 role TextView; 来访页走此分支)。
     *  资源 id 未命中返回空时 → 调用方可 dump view 树诊断。
     */
    private fun collectRoleTextViews(itemView: View, ctx: android.content.Context): List<TextView> {
        val out = LinkedHashMap<TextView, Boolean>()
        val roleId = ctx.resources.getIdentifier("tv_basic_info_role", "id", ctx.packageName)
        if (roleId != 0) {
            (itemView.findViewById<View>(roleId) as? TextView)?.let { out[it] = java.lang.Boolean.TRUE }
        }
        findTextViewsByText(itemView, setOf("其他", "未知", "-1")).forEach { out[it] = java.lang.Boolean.TRUE }
        return out.keys.toList()
    }

    /** 从 view 向上查 AgeHeightWeightView 祖先 (限深 12), 用于 TextView.setText 源头替换。 */
    private fun findAgeHeightWeightAncestor(start: View): View? {
        var v: View? = start
        var depth = 0
        while (v != null && depth < 12) {
            val p = v.parent
            if (p is View && p.javaClass.name.contains("AgeHeightWeightView")) return p
            v = if (p is View) p else null
            depth++
        }
        return null
    }

    /** 递归查找文本在指定集合内的 TextView (AgeHeightWeightView 嵌得深, 限深 8 层)。 */
    private fun findTextViewsByText(root: View, texts: Set<String>, depth: Int = 0, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (depth > 8) return out
        if (root is TextView) {
            val t = root.text?.toString()?.trim() ?: ""
            if (t in texts) out.add(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i) ?: continue
                findTextViewsByText(c, texts, depth + 1, out)
            }
        }
        return out
    }

    // ==================== 列表 UI 注入 ====================

    /** 列表模式 role 标签 (多锚点: 附近 ll_basic_info_weight → 来访 AgeHeightWeightView)。 */
    private fun injectRoleToListItem(holder: Any, role: String, uid: String, ctx: android.content.Context) {
        val itemView = XposedHelpers.getObjectField(holder, "itemView") as View

        // 锚点 ① 附近列表: ll_basic_info_weight / tv_basic_info_weight
        val weightContainerId = ctx.resources.getIdentifier("ll_basic_info_weight", "id", ctx.packageName)
        var anchor: View? = if (weightContainerId != 0) itemView.findViewById(weightContainerId) else null
        if (anchor == null) {
            val weightId = ctx.resources.getIdentifier("tv_basic_info_weight", "id", ctx.packageName)
            if (weightId != 0) anchor = itemView.findViewById(weightId)
        }
        // 锚点 ② 来访列表: AgeHeightWeightView 自定义控件
        if (anchor == null) {
            anchor = findViewByClassName(itemView, "AgeHeightWeightView")
        }
        val parent = anchor?.parent as? ViewGroup ?: return

        var roleView = parent.findViewWithTag<TextView>(ROLE_TAG_KEY)
        if (roleView == null) {
            val splitLineId = ctx.resources.getIdentifier("shape_split_line_path", "drawable", ctx.packageName)
            val splitLine = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(ctx, 6), dpToPx(ctx, 15))
                if (splitLineId != 0) setBackgroundResource(splitLineId)
                else setBackgroundColor(Color.parseColor("#0d000000"))
            }
            roleView = TextView(ctx).apply {
                tag = ROLE_TAG_KEY
                textSize = 10.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#888888"))
                background = GradientDrawable().apply { setColor(Color.parseColor("#0d000000")); cornerRadius = 5f }
                setPadding(4, 1, 4, 1); gravity = Gravity.CENTER
            }
            val anchorIndex = parent.indexOfChild(anchor)
            parent.addView(splitLine, anchorIndex + 1)
            parent.addView(roleView, anchorIndex + 2)
        }
        roleView.text = role
        roleView.visibility = View.VISIBLE
        maybeEnrichRole(uid, roleView, ctx, role)
    }

    /** 宫格模式 role 标签。 */
    private fun injectRoleToGridItem(holder: Any, role: String, uid: String, ctx: android.content.Context) {
        val itemView = XposedHelpers.getObjectField(holder, "itemView") as View
        val nameId = ctx.resources.getIdentifier("name_view", "id", ctx.packageName)
        if (nameId == 0) return
        val nameView = itemView.findViewById<View>(nameId) as? TextView ?: return
        val nameParent = nameView.parent as? ViewGroup ?: return

        var roleView = nameParent.findViewWithTag<TextView>(ROLE_TAG_KEY)
        if (roleView == null) {
            roleView = TextView(ctx).apply {
                tag = ROLE_TAG_KEY; textSize = 9f; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(0xFF6D00FF.toInt()); cornerRadius = 6f }
                setPadding(4, 0, 4, 0); gravity = Gravity.CENTER; maxLines = 1
            }
            if (nameParent is LinearLayout) {
                roleView.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 4; topMargin = 1; bottomMargin = 1 }
            }
            val nameIndex = nameParent.indexOfChild(nameView)
            if (nameIndex >= 0) nameParent.addView(roleView, nameIndex + 1) else nameParent.addView(roleView)
        }
        roleView.text = role
        roleView.visibility = View.VISIBLE
        maybeEnrichRole(uid, roleView, ctx, role)
    }

    /** 递归查找类名含 [keyword] 的 view (深度限制 3 层)。 */
    private fun findViewByClassName(root: View, keyword: String, depth: Int = 0): View? {
        if (depth > 3) return null
        if (root.javaClass.name.contains(keyword)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i) ?: continue
                val r = findViewByClassName(c, keyword, depth + 1)
                if (r != null) return r
            }
        }
        return null
    }

    private fun dpToPx(context: android.content.Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
