package bxxd.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * ============================================================================
 *  附近列表「一键聊天」入口注入
 * ============================================================================
 *  开关: switch_nearby_chat (UI「聊天增强 → 附近列表一键聊天」)
 *
 *  原理:
 *   Blued 附近/访客列表项 item_people_list.xml 顶部行 ll_user_info_top
 *   (ConstraintLayout) 最右侧是 ll_distance_and_time (距离 + 在线时间),
 *   没有直接进聊天的入口, 必须先进资料页再点聊天。
 *
 *  做法:
 *   1. 在 BaseQuickAdapter.onBindViewHolder 之后, 找到 ll_distance_and_time
 *   2. 把它从父控件 ll_user_info_top (ConstraintLayout) 里「摘」出来,
 *      套进一个水平 LinearLayout 容器: 距离块占 weight=1 (自然挤到左边),
 *      右侧放一个圆形 💬 聊天按钮 → 距离左移, 按钮靠右, 完美对齐。
 *      容器复用 ll_distance_and_time 原本的 ConstraintLayout 约束 (不动约束),
 *      无需 ConstraintSet / 额外依赖。
 *   3. 点击按钮直接调用官方聊天入口:
 *      ChatHelperV4.a().a(ctx, uid, name, avatar, vbadge, vip_grade,
 *        is_vip_annual, vip_exp_lvl, distance, dw, 0, 0, LogData, MsgSourceEntity)
 *      ← 与「资料页聊天按钮」(UserInfoFragmentNew.sll_chat_button) 走的是同一条
 *        官方 ChatHelperV4 通道, 一模一样的进聊天流程。
 *
 *  数据源: 列表项模型 UserFindResult extends UserBasicModel
 *          (uid / name / avatar / vbadge / vip_grade / is_vip_annual / vip_exp_lvl
 *           均为父类公开字段, 反射可读)。
 * ============================================================================
 */
object NearbyChatHook : BaseHook {

    private const val TAG = "llhook-NearbyChat"
    private const val CHAT_TAG = "llhook_nearby_chat_btn"
    private const val ALBUM_TAG = "llhook_nearby_album_btn"
    private const val CONTAINER_TAG = "llhook_nearby_chat_wrap"

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val baseAdapterClass =
                lpparam.classLoader.loadClass("com.chad.library.adapter.base.BaseQuickAdapter")
            baseAdapterClass.findMethod {
                name == "onBindViewHolder" && parameterTypes.size == 2
            }.hookAfter { param ->
                try {
                    if (!Config.isFeatureEnabled("switch_nearby_chat")) return@hookAfter
                    val holder = param.args[0] ?: return@hookAfter
                    val position = param.args[1] as Int
                    val adapter = param.thisObject

                    val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View
                        ?: return@hookAfter
                    val item = XposedHelpers.callMethod(adapter, "getItem", position)
                        ?: return@hookAfter

                    injectChatEntry(itemView, item)
                } catch (_: Throwable) {}
            }
            XposedBridge.log("$TAG hooked BaseQuickAdapter.onBindViewHolder")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG init fail: $t")
        }
    }

    // ==========================================
    // 注入聊天 + 相册入口 (距离左移 + 右侧纵列两按钮)
    // ==========================================
    private fun injectChatEntry(itemView: View, item: Any) {
        val ctx = itemView.context
        val res = ctx.resources
        val pkg = ctx.packageName
        val distId = res.getIdentifier("ll_distance_and_time", "id", pkg)
        if (distId == 0) return          // 非目标列表项 (如宫格卡片) → 跳过

        val distView = itemView.findViewById<View>(distId) ?: return

        // ⚠️ 排除资料页「动态」feed 项: item_feed_user_info.xml 同样含 ll_distance_and_time,
        //    但它是资料页动态列表的用户信息行(含 feed_user_info), 不是附近列表项 → 跳过,
        //    否则聊天/相册按钮会误注入到资料页动态里。附近列表项(item_people_list)无 feed_user_info。
        val feedInfoId = res.getIdentifier("feed_user_info", "id", pkg)
        if (feedInfoId != 0 && itemView.findViewById<View>(feedInfoId) != null) return

        val wantChat = Config.isFeatureEnabled("switch_nearby_chat", ctx)
        val wantAlbum = Config.isFeatureEnabled("switch_nearby_album", ctx)
        if (!wantChat && !wantAlbum) return

        // —— 已注入过: 复用按钮, 只刷新点击目标 uid ——
        val needRebuild = itemView.findViewWithTag<View>(CONTAINER_TAG) == null
        if (needRebuild) {
            val chatBtn = if (wantChat) buildChatButton(ctx) else null
            val albumBtn = if (wantAlbum) buildAlbumButton(ctx) else null
            reparentWithButtons(distView, chatBtn, albumBtn)
        }
        // (重新) 绑定点击目标
        itemView.findViewWithTag<View>(CHAT_TAG)?.let { bindChatClick(it, item) }
        itemView.findViewWithTag<View>(ALBUM_TAG)?.let { bindAlbumClick(it, item) }
    }

    /**
     * 把 distView 从 ConstraintLayout(ll_user_info_top) 摘出, 套进水平 LinearLayout:
     *   [ ll_distance_and_time (weight=1, 靠左) ] [ 纵列按钮 (靠右) ]
     * 容器沿用 distView 原本的 ConstraintLayout.LayoutParams (约束不变),
     * 因此无需 ConstraintSet, 距离块自然左移、按钮占据右侧空白。
     */
    private fun reparentWithButtons(distView: View, chatBtn: View?, albumBtn: View?) {
        val cl = distView.parent as? ViewGroup ?: return
        val origLp = distView.layoutParams   // ConstraintLayout.LayoutParams

        cl.removeView(distView)

        val wrap = LinearLayout(distView.context).apply {
            tag = CONTAINER_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 距离块: weight=1, 吃掉左侧全部剩余空间 (内部文字本就左对齐)
        wrap.addView(
            distView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        // 右侧按钮: 若只有一个就直挂, 两个则水平排列 (相册在聊天右边)
        val buttons = listOfNotNull(chatBtn, albumBtn)
        if (buttons.isNotEmpty()) {
            val size = dp(distView.context, 28f)
            val btnLp = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(distView.context, 8f)
                marginEnd = dp(distView.context, 2f)
                bottomMargin = dp(distView.context, 2f)
                topMargin = dp(distView.context, 2f)
            }
            if (buttons.size == 1) {
                wrap.addView(buttons[0], btnLp)
            } else {
                // 水平排列: [💬聊天] [🖼相册]
                val row = LinearLayout(distView.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                buttons.forEachIndexed { i, b ->
                    val lp = LinearLayout.LayoutParams(size, size)
                    if (i > 0) lp.marginStart = dp(distView.context, 6f)
                    row.addView(b, lp)
                }
                wrap.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(distView.context, 8f)
                    marginEnd = dp(distView.context, 2f)
                })
            }
        }

        cl.addView(wrap, origLp)
    }

    // ==========================================
    // 圆形按钮 (Compose Material 图标渲染)
    // 注入到 Blued 原生 View 无法直接挂 ImageVector (compose 运行时与注入 view 隔离),
    // 故取 compose Icons 的 pathData, 用宿主自带 androidx.core PathParser
    // 解析成 Path 再 Canvas 绘制 —— 几何与 compose 图标完全一致。
    // ==========================================
    private fun buildChatButton(ctx: Context): View {
        return buildIconButton(ctx, CHAT_TAG, Color.parseColor("#2196F3"),
            // == compose Icons.Filled.Send (纸飞机)
            "M2.01,21L23,12L2.01,3L2,10l15,2l-15,2Z")
    }

    private fun buildAlbumButton(ctx: Context): View {
        return buildIconButton(ctx, ALBUM_TAG, Color.parseColor("#8B5CF6"),
            // == compose Icons.Filled.PhotoLibrary (相册)
            "M22,16V4c0-1.1-0.9-2-2-2H8c-1.1,0-2,0.9-2,2v12c0,1.1,0.9,2,2,2h12C21.1,18,22,17.1,22,16zM11,12l2.03,2.71L16,11l4,5H8L11,12zM2,6v14c0,1.1,0.9,2,2,2h14v-2H4V6H2z")
    }

    private fun buildIconButton(ctx: Context, tag: String, bgColor: Int, pathData: String): View {
        return ImageView(ctx).apply {
            this.tag = tag
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(BitmapDrawable(ctx.resources, composeIconBitmap(ctx, pathData, 16f, Color.WHITE)))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
            // 强制圆形裁剪 + 阴影
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            elevation = dp(ctx, 4f).toFloat()
            isClickable = true
            // 点击态透明度反馈 (原生 View, 不依赖 ripple)
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.65f
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; v.performClick() }
                }
                false
            }
        }
    }

    /**
     * Compose Material Icons pathData (viewport 24x24),
     * 用宿主 androidx.core.graphics.PathParser 解析为 [Path] 后缩放绘制成 Bitmap。
     */
    private fun composeIconBitmap(ctx: Context, pathData: String, sizeDp: Float, color: Int): Bitmap {
        val sizePx = dp(ctx, sizeDp)
        val path = try {
            val cls = ctx.classLoader.loadClass("androidx.core.graphics.PathParser")
            val m = cls.getMethod("createPathFromPathData", String::class.java)
            m.invoke(null, pathData) as Path
        } catch (_: Throwable) {
            // 极端兜底: 画一个实心圆点 (宿主必有 androidx.core, 正常不会走到)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 4f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            return bmp
        }
        val matrix = Matrix()
        matrix.postScale(sizePx / 24f, sizePx / 24f)
        path.transform(matrix)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawPath(path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL })
        return bmp
    }

    // ==========================================
    // 点击 → 官方聊天入口 ChatHelperV4
    // ==========================================
    private fun bindChatClick(chatBtn: View, item: Any) {
        chatBtn.setOnClickListener { v ->
            try {
                openChat(v.context, item)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG openChat fail: $t")
            }
        }
    }

    // ==========================================
    // 点击 → Blued 原生相册浏览页 (ShowPhotoFragment)
    // 不自建 UI, 直接调出 Blude 自带的照片浏览控件:
    //   BasePhotoFragment.a(Activity, String[] urls, idx, showPhoto, uid, waterMask, avatarWidget)
    //   → 启动 ShowAlbumActivity + ShowPhotoFragment, 体验与资料页点照片完全一致。
    // 相册 url 列表由资料页接口获取 (Blude 自身也是这么拿数据的, 见 UserInfoNewPresenter)。
    // ==========================================
    private fun bindAlbumClick(albumBtn: View, item: Any) {
        albumBtn.setOnClickListener { v ->
            try {
                val uid = (XposedHelpers.getObjectField(item, "uid") as? String) ?: ""
                val name = (XposedHelpers.getObjectField(item, "name") as? String) ?: ""
                if (uid.isEmpty()) return@setOnClickListener
                val activity = unwrapActivity(v.context) ?: return@setOnClickListener

                // ★ 防访问记录: 拉黑 → action(后台) → 强制解黑(带重试+告警)。
                //   peekSafely 用 try-finally 保证解黑一定被调用, 解黑失败会重试+醒目提示。
                peekSafely(activity, uid) {
                    // 拉黑成功后, 后台线程执行
                    val urls = fetchAlbumUrls(activity, uid)
                    activity.runOnUiThread {
                        if (urls.isNullOrEmpty()) {
                            android.widget.Toast.makeText(
                                activity, "未读取到私密照片", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            showCustomAlbum(activity, uid, name, urls)
                        }
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG openAlbum fail: $t")
            }
        }
    }

    // ===== 防追踪访问的高可靠性封装 =====
    // 同一 uid 正在走「拉黑→action→解黑」流程时, 忽略重复点击, 避免并发请求乱序。
    private val processingUids = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 防追踪访问封装: 拉黑 → [onBlocked](后台) → 强制解黑(带重试)。
     *  - 拉黑失败: 提示后返回, 不执行 onBlocked
     *  - onBlocked 包在 try-finally, 无论是否抛异常, finally 都会解黑
     *  - 解黑用 [unblockWithRetry], 失败重试 N 次, 仍失败则醒目告警 (后果不可逆, 必须提示)
     *  - per-uid 去重锁覆盖整个生命周期 (解黑彻底结束才放开)
     */
    private fun peekSafely(activity: android.app.Activity, uid: String, onBlocked: () -> Unit) {
        if (!processingUids.add(uid)) {
            android.widget.Toast.makeText(activity, "正在处理中…", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        Ban2Hook.operateBlacklist(activity, uid, true) { blocked ->
            if (!blocked) {
                processingUids.remove(uid)
                android.widget.Toast.makeText(activity, "拉黑失败, 无法防追踪访问", android.widget.Toast.LENGTH_SHORT).show()
                return@operateBlacklist
            }
            thread {
                try {
                    onBlocked()
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG peekSafely action err uid=$uid: $t")
                } finally {
                    // ★ 无论 action 成功与否, 强制解黑; 锁在解黑彻底结束后才放开
                    unblockWithRetry(activity, uid) {
                        activity.runOnUiThread { processingUids.remove(uid) }
                    }
                }
            }
        }
    }

    /**
     * 带重试的解除拉黑。解黑失败后果不可逆(对方被永久拉黑, 可能被发现), 必须:
     *  - 最多重试 [maxRetries] 次, 间隔递增 (1s/2s/3s)
     *  - 全部失败 → 醒目 Toast + Xposed 日志 (提示用户手动解除)
     *  - [onDone] 在彻底结束时回调 (成功或告警后), 用来释放去重锁
     */
    private fun unblockWithRetry(
        activity: android.app.Activity,
        uid: String,
        maxRetries: Int = 3,
        onDone: (Boolean) -> Unit = {}
    ) {
        var attempt = 0
        fun tryOnce() {
            attempt++
            Ban2Hook.operateBlacklist(activity, uid, false) { ok ->
                if (ok) {
                    XposedBridge.log("$TAG unblock OK uid=$uid (attempt=$attempt)")
                    onDone(true)
                    return@operateBlacklist
                }
                if (attempt < maxRetries) {
                    val wait = attempt * 1000L
                    XposedBridge.log("$TAG unblock retry uid=$uid attempt=$attempt failed, wait ${wait}ms")
                    thread {
                        try { Thread.sleep(wait) } catch (_: InterruptedException) {}
                        tryOnce()
                    }
                } else {
                    XposedBridge.log("$TAG ⚠️ unblock FINAL FAIL uid=$uid after $attempt attempts — 需手动解除!")
                    activity.runOnUiThread {
                        android.widget.Toast.makeText(
                            activity,
                            "⚠️ 解除拉黑失败! uid=$uid 可能仍被拉黑, 请到黑名单手动解除",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    onDone(false)
                }
            }
        }
        tryOnce()
    }

    /** 弹出自构私密相册面板 (替代官方 ShowPhotoFragment, 含「保留到相册库」)。 */
    private fun showCustomAlbum(activity: android.app.Activity, uid: String, name: String, urls: List<String>) {
        try {
            // ★ 居中浮窗形态 (非全屏): showHostComposePanel = 92%×88% + 圆角 + 0.5 遮罩
            com.example.ui.showHostComposePanel(activity) { onClose ->
                com.example.ui.NearbyAlbumViewerPanel(activity, uid, name, urls, onClose)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG 自构相册面板失败, 回退官方: $t")
            showNativeAlbum(activity, urls.toTypedArray(), uid)
        }
    }

    /**
     * 资料页接口拉取私密相册图片直链。
     *   GET https://social.blued.cn/users/{uid}?from=nearby&is_living=false&is_live_flow=1&is_vip_page=0
     *   响应 data.album[].url 即图片直链 (服务器即便锁定也返回真实地址)。
     * 凭证复用 AutoVisitHook 拦截到的 authorization (与一键站街同源)。
     */
    private fun fetchAlbumUrls(ctx: Context, uid: String): List<String>? {
        val token = AutoVisitHook.cachedToken.ifEmpty { Config.getAuthToken(ctx) }
        if (token.isEmpty()) return null
        val ua = NetworkSpoofHook.capturedLatestUA.ifEmpty { AutoVisitHook.cachedUserAgent }
        val urlStr =
            "https://social.blued.cn/users/$uid?from=nearby&is_living=false&is_live_flow=1&is_vip_page=0"
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("authorization", token)
                setRequestProperty("user-agent", ua)
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val root = JSONObject(body)
            val dataObj = root.opt("data") ?: return emptyList()
            val obj = when (dataObj) {
                is JSONObject -> dataObj
                is org.json.JSONArray -> dataObj.optJSONObject(0)
                else -> null
            } ?: return emptyList()
            val albumArr = obj.optJSONArray("album") ?: return emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until albumArr.length()) {
                val u = albumArr.optJSONObject(i)?.optString("url", "")?.ifEmpty {
                    albumArr.optJSONObject(i)?.optString("image", "")
                } ?: ""
                if (u.isNotEmpty()) list.add(u)
            }
            // ★ 接口返回的是缩略图直链 (如 ...!Head.jpg), 加 !original.png 后缀拉原图
            //   否则面板预览 + 保留入库都是模糊缩略图
            list.map { bxxd.hook.ChatSpyHook.toOriginalUrl(it) }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 反射调用 Blude 原生照片浏览页:
     *   BasePhotoFragment.a(Context, String[] urls, int idx, int showPhoto, String uid, String waterMask, int avatarWidget)
     *   → ShowAlbumActivity + ShowPhotoFragment (Blude 自带控件, 含下拉/翻页/缩放/保存)
     */
    private fun showNativeAlbum(activity: android.app.Activity, urls: Array<String>, uid: String) {
        val cls = XposedHelpers.findClass(
            "com.soft.blued.ui.photo.fragment.BasePhotoFragment", activity.classLoader)
        val m = cls.getMethod(
            "a",
            Context::class.java,
            Array<String>::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, String::class.java,
            Int::class.javaPrimitiveType
        )
        m.invoke(null, activity, urls, 0, 0, uid, "", 0)
    }

    /** 从 Context 逐层解包出 Activity (itemView.context 可能是 ContextThemeWrapper)。 */
    private fun unwrapActivity(ctx: Context?): android.app.Activity? {
        var c = ctx
        while (c != null && c !is android.app.Activity && c is android.content.ContextWrapper) {
            c = c.baseContext
        }
        return c as? android.app.Activity
    }

    /**
     * 调用官方聊天通道 (与资料页 sll_chat_button 同源):
     *   ChatHelperV4.a().a(Context, long uid, String name, String avatar,
     *     int vbadge, int vip_grade, int is_vip_annual, int vip_exp_lvl,
     *     String distance, boolean dw, int, int, LogData, MsgSourceEntity)
     */
    private fun openChat(ctx: Context, item: Any) {
        val cl = ctx.classLoader

        val uidStr = XposedHelpers.getObjectField(item, "uid") as? String
        val uid = uidStr?.toLongOrNull() ?: run {
            XposedBridge.log("$TAG no uid"); return
        }
        val name = (XposedHelpers.getObjectField(item, "name") as? String) ?: ""
        val avatar = (XposedHelpers.getObjectField(item, "avatar") as? String) ?: ""
        val vbadge = intField(item, "vbadge")
        val vipGrade = intField(item, "vip_grade")
        val isVipAnnual = intField(item, "is_vip_annual")
        val vipExpLvl = intField(item, "vip_exp_lvl")

        val helperCls = XposedHelpers.findClass(
            "com.soft.blued.ui.msg.controller.tools.ChatHelperV4", cl)
        val helper = XposedHelpers.callStaticMethod(helperCls, "a") // 单例

        val logDataCls = XposedHelpers.findClass(
            "com.blued.android.module.common.log.oldtrack.LogData", cl)
        val logData = safeNewInstance(logDataCls) ?: return

        // MsgSourceEntity(StrangerSource.UNKNOWN_STRANGER_SOURCE)
        val sourceCls = XposedHelpers.findClass(
            "com.blued.das.message.MessageProtos\$StrangerSource", cl)
        val consts = sourceCls.enumConstants ?: run {
            XposedBridge.log("$TAG StrangerSource enumConstants null"); return
        }
        val unknownSource = consts.firstOrNull {
            (it as Enum<*>).name == "UNKNOWN_STRANGER_SOURCE"
        } ?: consts.firstOrNull() ?: return
        val msgSrcCls = XposedHelpers.findClass(
            "com.soft.blued.ui.msg.model.MsgSourceEntity", cl)
        val msgSrc = msgSrcCls.getConstructor(sourceCls).newInstance(unknownSource)

        // 定位 14 参重载
        val method = helperCls.getMethod(
            "a",
            Context::class.java,
            java.lang.Long.TYPE,
            String::class.java, String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java,
            java.lang.Boolean.TYPE,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            logDataCls, msgSrcCls
        )

        method.invoke(
            helper, ctx, uid, name, avatar,
            vbadge, vipGrade, isVipAnnual, vipExpLvl,
            "", false, 0, 0, logData, msgSrc
        )
    }

    /** 读 int 字段 (含父类), 缺失返回 0。 */
    private fun intField(obj: Any, name: String): Int = try {
        XposedHelpers.getObjectField(obj, name) as? Int ?: 0
    } catch (_: Throwable) { 0 }

    /** 反射构造实例: 优先无参构造, 失败则取最少参数构造并用默认值填充。 */
    private fun safeNewInstance(cls: Class<*>): Any? {
        return try {
            cls.getDeclaredConstructor().newInstance()
        } catch (_: Throwable) {
            try {
                val c = cls.declaredConstructors.minByOrNull { it.parameterCount } ?: return null
                c.isAccessible = true
                c.newInstance(*c.parameterTypes.map { defaultArg(it) }.toTypedArray())
            } catch (_: Throwable) { null }
        }
    }

    private fun defaultArg(type: Class<*>): Any? = when (type) {
        Int::class.javaPrimitiveType, Integer::class.java -> 0
        Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0f
        Double::class.javaPrimitiveType, java.lang.Double::class.java -> 0.0
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> ' '
        else -> null
    }

    private fun dp(ctx: Context, dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.resources.displayMetrics).toInt()
}
