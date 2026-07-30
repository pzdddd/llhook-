package bxxd.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatAdvanceHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookReadReceipt(lpparam)
        hookScreenshotProtection(lpparam)
        hookMessageTimestamp(lpparam)
        hookSecretViewReceipt(lpparam)    // ★★ 核心: 拦截 ReadFlagSender.sendReceipt → 已读回执不发
        hookSecretViewOnClick(lpparam)    // 单击消息列表 → 自动调 toSecret 设悄悄查看状态 + 进入聊天
        hookSecretViewAllTrigger(lpparam) // 长按消息页顶部 Tab → 弹「悄悄查看所有消息」开关
    }

    // =========================================================================
    //  ★★ 核心: 拦截已读回执发送 (switch_secret_view_all) ★★
    //
    //  「悄悄查看」的本质 = 不发已读回执 (对方看不到「已读」)。
    //  所有已读回执汇聚点 = 私有方法 ReadFlagSender.sendReceipt(ReceiptModel)。
    //  (逆向验证: classes11.dex 中 ReceiptModel.receiptType:ReadFlagSender$ReceiptType,
    //   ReceiptType 枚举含 READ/RECEIVED; sendReadReceiptImmediate/sendReceiptImmediate
    //   最终都调 private sendReceipt(ReceiptModel)。)
    //
    //  hook 它, 命中 receiptType==READ 即 setResult(null) 丢弃 → 对方永远看不到「已读」。
    //  这是「悄悄查看」功能的根本保障 —— 即使 toSecret 因时序/异步未及时设状态,
    //  已读回执也绝对不会发出。
    //  注: 仅拦截 READ(已读), 不影响 RECEIVED(送达回执)。
    // =========================================================================
    private fun hookSecretViewReceipt(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cl = lpparam.classLoader
            val rfsClass = cl.loadClass("com.blued.android.chat.core.worker.chat.ReadFlagSender") ?: return
            val modelClass = cl.loadClass("com.blued.android.chat.core.worker.chat.ReadFlagSender\$ReceiptModel") ?: return
            val typeClass = cl.loadClass("com.blued.android.chat.core.worker.chat.ReadFlagSender\$ReceiptType") ?: return
            val readConst = typeClass.enumConstants?.firstOrNull {
                (it as? Enum<*>)?.name == "READ"
            } ?: return

            // ★ ReadFlagSender 有两个 sendReceipt 重载 (逆向确认):
            //   1) sendReceipt(ReceiptModel)                  — receiptType 在 model 内
            //   2) sendReceipt(S,J,J,ReceiptType)             — receiptType 是直接参数
            // hookAllMethods 一网打尽, 回调里检查「ReceiptModel.receiptType 或直接 ReceiptType == READ」。
            XposedBridge.hookAllMethods(rfsClass, "sendReceipt", object : de.robv.android.xposed.XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!Config.isFeatureEnabled("switch_secret_view_all")) return
                    var isRead = false
                    for (arg in param.args) {
                        if (arg == null) continue
                        when {
                            modelClass.isInstance(arg) -> {
                                if (XposedHelpers.getObjectField(arg, "receiptType") == readConst) isRead = true
                            }
                            typeClass.isInstance(arg) -> {
                                if (arg == readConst) isRead = true
                            }
                        }
                    }
                    if (isRead) {
                        param.setResult(null)   // 丢弃 READ 已读回执
                        blockedReadCount++
                    }
                }
            })
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    // 1. 开启悄悄查看(保留旧 hook: generateFullMethodName, 仅 switch_read_receipt)
    private fun hookReadReceipt(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val descriptorClass = lpparam.classLoader.loadClass(Config.TargetClasses.GRPC_METHOD_DESCRIPTOR)

            descriptorClass.findMethod {
                name == "generateFullMethodName" &&
                parameterTypes.size == 2 &&
                parameterTypes[0] == String::class.java &&
                parameterTypes[1] == String::class.java
            }.hookBefore { param ->
                if (!Config.isFeatureEnabled("switch_read_receipt")) return@hookBefore

                val serviceName = param.args[0] as? String ?: return@hookBefore
                val methodName = param.args[1] as? String ?: return@hookBefore

                if (serviceName == "com.blued.im.private_chat.Receipt" && methodName == "Read") {
                    param.args[0] = ""
                    param.args[1] = ""
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =========================================================================
    //  ★★「悄悄查看所有消息」(switch_secret_view_all) ★★
    //
    //  需求: 开启后, 单击消息列表任一会话 = 自动执行原生「悄悄查看」+ 进入聊天,
    //        省去「长按 → 弹菜单 → 点悄悄查看」三步。完全复刻长按菜单「悄悄查看」,
    //        走相同原生代码路径 (per-session, 非 VIP 全局开关, 无服务器认证)。
    //
    //  逆向定位 (脱壳 dex jadx 反编译 classes10):
    //    com.soft.blued.ui.msg.presenter.MsgPresenter (会话列表点击/长按 presenter)
    //      · onItemClick(parent, view, position, id)  ← 单击列表项 (进入聊天)
    //      · onItemLongClick(...)                     ← 长按 → showBottomActionSheet 弹菜单
    //      · toSecret(SessionModel)                   ← ★「悄悄查看」动作 (SilentView)
    //      · getSessionModel(int): SessionModel       ← 按 position 取会话
    //
    //  实现: hook onItemClick, 命中且为私聊时先调 toSecret(session) 设悄悄查看状态,
    //        再放行原 onItemClick 进入聊天。toSecret 与手点长按菜单「悄悄查看」完全一致,
    //        生效后消息列表会出现 com.soft.blued:id/iv_secret 标记 (原生刷新)。
    //
    //  注: toSecret/onItemClick 方法体被加固抽取 (jadx 标 native), 运行时由 .so 还原,
    //      Xposed hook 与反射调用均正常生效。本方案不碰任何 VIP/服务器认证逻辑。
    // =========================================================================
    private const val MSG_PRESENTER = "com.soft.blued.ui.msg.presenter.MsgPresenter"
    private const val SESSION_MODEL = "com.blued.android.chat.model.SessionModel"
    private const val SESSION_TYPE_PRIVATE: Int = 2   // SessionHeader.SESSION_TYPE_PRIVATE
    @Volatile private var blockedReadCount: Int = 0   // 已拦截的 READ 回执计数 (诊断用)

    private fun hookSecretViewOnClick(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = try { lpparam.classLoader.loadClass(MSG_PRESENTER) } catch (_: Throwable) { return }
        val sessionModelCls = try { lpparam.classLoader.loadClass(SESSION_MODEL) } catch (_: Throwable) { return }
        try {
            // onItemClick(AdapterView<?> parent, View view, int position, long id)
            cls.findMethod {
                name == "onItemClick" && parameterTypes.size == 4
            }.hookBefore { param ->
                if (!Config.isFeatureEnabled("switch_secret_view_all")) return@hookBefore
                val position = param.args[2] as? Int ?: return@hookBefore
                val presenter = param.thisObject
                val ctx = (param.args[1] as? View)?.context ?: appContext()
                val diag = StringBuilder("pos=$position | 拦读=$blockedReadCount")
                try {
                    // ★ 运行时动态自省: 不预设方法名 (混淆名不稳定, 抽取会隐藏)
                    // 1) 找「接收 int、返回 SessionModel 或其子类」的方法 → 取 session
                    val allMethods = presenter.javaClass.declaredMethods
                    val int2Session = allMethods.firstOrNull {
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        sessionModelCls.isAssignableFrom(it.returnType)
                    }?.also { it.isAccessible = true }
                    diag.append(" | int2sess=${int2Session?.name ?: "未找到"}")
                    val session = int2Session?.invoke(presenter, position)
                    diag.append(" | sess=" + (session?.javaClass?.simpleName ?: "null"))
                    if (session == null) { toast(ctx, "$diag"); return@hookBefore }
                    val st = (XposedHelpers.getObjectField(session, "sessionType") as? Number)?.toInt()
                    diag.append(" | type=$st")
                    if (st == SESSION_TYPE_PRIVATE) {
                        // 2) 找「接收单个 SessionModel、返回 void」的方法 = toSecret (可能被混淆成别的名)
                        val toSecretMethod = allMethods.firstOrNull {
                            it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == sessionModelCls &&
                            it.returnType == Void.TYPE
                        }?.also { it.isAccessible = true }
                        diag.append(" | secret=" + (toSecretMethod?.name ?: "未找到"))
                        if (toSecretMethod != null) {
                            toSecretMethod.invoke(presenter, session)
                            diag.append(" | OK ✅")
                        }
                        toast(ctx, "$diag")
                    } else {
                        toast(ctx, "$diag (非私聊)")
                    }
                } catch (e: Throwable) {
                    toast(ctx, "$diag | ERR ${e.javaClass.simpleName}: ${e.message?.take(60)}")
                }
                // 始终放行原 onItemClick → 进入聊天界面
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /** 取宿主 ApplicationContext, 用于 Toast。 */
    private fun appContext(): Context? = try {
        val cls = Class.forName("de.robv.android.xposed.AndroidAppHelper")
        XposedHelpers.callStaticMethod(cls, "currentApplication") as? Context
    } catch (_: Throwable) { null }

    /** 在宿主主线程弹 Toast (LONG), 用于调试确认 hook 是否触发。 */
    private fun toast(ctx: Context?, msg: String) {
        try {
            val c = ctx ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (_: Throwable) {}
    }

    // =========================================================================
    //  「悄悄查看所有消息」快捷入口
    //  长按消息页顶部 Tab (com.soft.blued:id/child_item_content_layout, 即「聊天/通知」
    //  等顶部导航 Tab) → 弹出毛玻璃开关面板, 一键开关全局「悄悄查看所有消息」
    //  (= 拦截已读回执 com.blued.im.private_chat.Receipt/Read)。
    //
    //  child_item_content_layout 是 title_top_navigation_layout 的通用 Tab 项
    //  (item_title_top_navigation_left_layout), 在消息页(MsgFragment 可见时)顶部出现。
    //  故挂在 MsgFragment 生命周期上扫描挂载, Tab 销毁后随 WeakHashMap 自动清理。
    // =========================================================================
    private val secretTabsAttached =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private fun hookSecretViewAllTrigger(lpparam: XC_LoadPackage.LoadPackageParam) {
        val fragCls = try {
            lpparam.classLoader.loadClass("com.soft.blued.ui.msg.MsgFragment")
        } catch (_: Throwable) { return }

        // 复用 MsgPageBgHook 已验证的 MsgFragment 生命周期签名, 任一触发即扫描 Tab
        listOf(
            "onInitView" to 1,
            "onViewCreated" to 2,
            "onActivityCreated" to 1,
            "onResume" to 0,
            "onHiddenChanged" to 1,
            "setUserVisibleHint" to 1
        ).forEach { (name, argc) ->
            try {
                fragCls.findMethod { this.name == name && parameterTypes.size == argc }
                    .hookAfter { p ->
                        try { attachSecretViewTrigger(p.thisObject) } catch (_: Throwable) {}
                    }
            } catch (_: Throwable) {}
        }
    }

    /** 在当前 Activity 视图树里找所有 child_item_content_layout, 挂长按 → 弹开关面板。 */
    private fun attachSecretViewTrigger(fragment: Any) {
        val ctx = try { XposedHelpers.callMethod(fragment, "getContext") as? Context }
            catch (_: Throwable) { null } ?: return
        val activity = unwrapActivity(ctx) ?: return
        val resId = ctx.resources.getIdentifier("child_item_content_layout", "id", ctx.packageName)
        if (resId == 0) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        scanAndAttachTabs(root, resId, activity)
        // 下一帧再扫一次, 兜住顶部 Tab 异步 inflate 的场景
        root.post { scanAndAttachTabs(root, resId, activity) }
    }

    private fun scanAndAttachTabs(root: ViewGroup, resId: Int, activity: Activity) {
        val tabs = ArrayList<View>()
        findAllById(root, resId, tabs)
        if (tabs.isEmpty()) return
        tabs.forEach { tab ->
            if (tab in secretTabsAttached) return@forEach
            secretTabsAttached.add(tab)
            tab.setOnLongClickListener {
                try {
                    com.example.ui.showHostComposePanel(activity) { onClose ->
                        com.example.ui.theme.MyApplicationTheme {
                            com.example.ui.SecretViewAllPanel(onClose)
                        }
                    }
                } catch (_: Throwable) {
                    android.widget.Toast.makeText(activity, "面板唤起失败", android.widget.Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }

    private fun unwrapActivity(ctx: Context?): Activity? {
        var c: Context? = ctx
        while (c != null) {
            if (c is Activity) return c
            c = if (c is ContextWrapper) c.baseContext else null
        }
        return null
    }

    private fun findAllById(root: ViewGroup, id: Int, out: ArrayList<View>) {
        if (root.id == id) out.add(root)
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) ?: continue
            if (child is ViewGroup) findAllById(child, id, out)
            else if (child.id == id) out.add(child)
        }
    }

    // 2. 破解私聊/闪照界面的防截屏限制
    private fun hookScreenshotProtection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fragmentClass = lpparam.classLoader.loadClass(Config.TargetClasses.MSG_CHATTING_FRAGMENT)

            fragmentClass.findMethod {
                name == "c" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == Boolean::class.javaPrimitiveType
            }.hookBefore { param ->
                if (!Config.isFeatureEnabled("switch_screenshot")) return@hookBefore

                val isProtected = param.args[0] as? Boolean ?: return@hookBefore
                if (isProtected) {
                    param.args[0] = false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 3. 聊天页消息气泡上方显示具体发送时间 (yyyy-MM-dd HH:mm:ss)
    //   做法 (参考手术刀 MessageTimestampHook): hook MsgChattingAdapter.a(int,View,ViewGroup),
    //   读 ChattingModel.msgTimestamp, 在聊天气泡所在的「纵向容器」内、内容气泡之前插入
    //   一个独立的灰色小字 TextView。通过资源名(chat_content_root/chat_content_in 等)定位,
    //   不依赖硬编码 ID, 跨版本稳定。
    private fun hookMessageTimestamp(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val adapterCls = lpparam.classLoader.loadClass("com.soft.blued.ui.msg.adapter.MsgChattingAdapter")
            adapterCls.findMethod {
                name == "a" && parameterTypes.size == 3 &&
                    parameterTypes[0] == Int::class.javaPrimitiveType &&
                    parameterTypes[1] == View::class.java &&
                    parameterTypes[2] == ViewGroup::class.java
            }.hookAfter { p ->
                val itemView = p.result as? View ?: return@hookAfter
                if (!Config.isFeatureEnabled("switch_msg_timestamp")) {
                    clearTimestampLabel(itemView); return@hookAfter
                }
                try {
                    val position = p.args[0] as Int
                    // 字段 a 是消息数据列表 (List<ChattingModel>)
                    val list = XposedHelpers.getObjectField(p.thisObject, "a") as? List<*> ?: return@hookAfter
                    val model = list.getOrNull(position) ?: return@hookAfter
                    val ts = readTimestamp(model)
                    if (ts == null || ts <= 0L) { clearTimestampLabel(itemView); return@hookAfter }
                    val millis = normalizeTimestamp(ts)
                    val container = findContainer(itemView)
                    if (container == null) { clearTimestampLabel(itemView); return@hookAfter }
                    val timeStr = tsFmt.get().format(Date(millis))
                    // 复用已挂载的标签 (RecyclerView/列表会回收 itemView)
                    val label = (itemView.findViewWithTag<TextView>("llhook_message_timestamp")
                        ?: createLabel(itemView.context, itemView))
                    attachBeforeMessage(container, label)
                    label.text = timeStr
                    label.visibility = View.VISIBLE
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    // ---- 时间戳相关辅助 (移植自手术刀 MessageTimestampHook) ----

    private val tsFmt: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    private val TIMESTAMP_FIELDS = arrayOf(
        "msgTimestamp", "timestamp", "sendTimestamp", "sendTime", "timeStamp", "createTime"
    )

    /** 从消息模型读取时间戳, 兼容多个常见字段名。 */
    private fun readTimestamp(model: Any): Long? {
        for (f in TIMESTAMP_FIELDS) {
            val v = readField(model, f) ?: continue
            val l = (v as? Number)?.toLong() ?: continue
            if (l > 0) return l
        }
        return null
    }

    private fun readField(obj: Any, name: String): Any? =
        try { XposedHelpers.getObjectField(obj, name) } catch (_: Throwable) { null }

    /** 秒级 (<10_000_000_000) 转 毫秒级。 */
    private fun normalizeTimestamp(ts: Long): Long =
        if (ts in 1..9_999_999_999L) ts * 1000 else ts

    /** 找到聊天气泡所在的纵向容器 (LinearLayout chat_content_root / msg_item_root)。 */
    private fun findContainer(root: View): ViewGroup? {
        val v = root as? ViewGroup ?: return null
        findViewByName(v, "chat_content_root")?.let { if (it is LinearLayout && it.orientation == LinearLayout.VERTICAL) return it }
        findViewByName(v, "msg_item_root")?.let { if (it is LinearLayout && it.orientation == LinearLayout.VERTICAL) return it }
        if (v is LinearLayout && v.orientation == LinearLayout.VERTICAL) return v
        return findFirstVertical(v)
    }

    /** 在容器内找到「消息内容气泡」的位置 (索引), 标签将插在它前面。 */
    private fun findContentPosition(container: ViewGroup): Int {
        val names = setOf("chat_content_in", "chat_content_out", "chat_content_out_layout", "msg_image_root")
        // 第一轮: 直接子 view 资源名命中
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            if (getResourceName(child) in names) return i
        }
        // 第二轮: 子树内含气泡布局名
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) as? ViewGroup ?: continue
            if (names.any { findViewByName(child, it) != null }) return i
        }
        // 第三轮: 内容根 msg_item_content_root
        for (i in 0 until container.childCount) {
            if (getResourceName(container.getChildAt(i)) == "msg_item_content_root") return i
        }
        findViewByName(container, "msg_item_content_root")?.let { content ->
            // 向上追溯到 container 的直接子, 返回其索引
            var directChild: View = content
            var p: ViewParent = content.parent
            while (p is ViewGroup && p !== container) {
                directChild = p
                p = directChild.parent
            }
            val idx = container.indexOfChild(directChild)
            return if (idx >= 0) idx else 0
        }
        return 0
    }

    /** 创建时间标签 TextView: 10sp, 灰色 #8A8A8A, tag llhook_message_timestamp。 */
    private fun createLabel(ctx: Context, anchor: View): TextView {
        return TextView(ctx).apply {
            tag = "llhook_message_timestamp"
            textSize = 10f
            setTextColor(0xFF8A8A8A.toInt())
            includeFontPadding = false
            isClickable = false
            isFocusable = false
            updateLabelAlignment(this, anchor)
        }
    }

    /** 根据是否「自己发的(右侧头像)」决定标签左右对齐 + 头像宽度留白。 */
    private fun updateLabelAlignment(label: TextView, anchor: View) {
        val isOutgoing = (anchor as? ViewGroup)?.let { findViewByName(it, "msg_include_avatar_right") != null } ?: false
        val avatarPad = dp(label.context, 54f)
        val sidePad = dp(label.context, 8f)
        val tiny = dp(label.context, 1f)
        val shiftRight = dp(label.context, 28f)  // 整体右移偏移
        if (isOutgoing) {
            label.gravity = Gravity.END
            label.setPadding(sidePad, tiny, avatarPad, tiny)
        } else {
            // 收到的消息: 时间标签右移 (留出头像 + 额外右移)
            label.gravity = Gravity.START
            label.setPadding(avatarPad + shiftRight, tiny, sidePad, tiny)
        }
    }

    /** 把标签插入到内容气泡之前 (上方); 已在正确位置则不重复插入。 */
    private fun attachBeforeMessage(container: ViewGroup, label: TextView) {
        val parent = label.parent
        if (parent === container) {
            val cur = container.indexOfChild(label)
            val target = findContentPosition(container)
            if (cur >= 0 && target == cur + 1) return // 已紧贴内容上方
            container.removeView(label)
        } else {
            (parent as? ViewGroup)?.removeView(label)
        }
        val pos = findContentPosition(container)
        container.addView(label, pos, createLayoutParams(container))
    }

    private fun createLayoutParams(container: ViewGroup): ViewGroup.LayoutParams {
        return when (container) {
            is LinearLayout -> {
                val w = if (container.orientation == LinearLayout.VERTICAL) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
                LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = 0
                    bottomMargin = -dp(container.context, 1f)
                }
            }
            is FrameLayout -> FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(container.context, 2f)
            }
            else -> ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** 移除 itemView 内的时间标签 (关闭开关时调用)。 */
    private fun clearTimestampLabel(itemView: View) {
        val label = itemView.findViewWithTag<View>("llhook_message_timestamp") ?: return
        (label.parent as? ViewGroup)?.removeView(label)
    }

    // ---- 通用 View 工具 ----

    private fun getResourceName(v: View): String? {
        val id = v.id
        if (id == View.NO_ID) return null
        return try { v.resources.getResourceEntryName(id) } catch (_: Resources.NotFoundException) { null }
    }

    /** 按资源名递归查找子 View (返回第一个匹配的 ViewGroup)。 */
    private fun findViewByName(root: ViewGroup, name: String): ViewGroup? {
        if (getResourceName(root) == name) return root
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i) ?: continue
            if (getResourceName(c) == name && c is ViewGroup) return c
            if (c is ViewGroup) findViewByName(c, name)?.let { return it }
        }
        return null
    }

    private fun findFirstVertical(root: ViewGroup): ViewGroup? {
        if (root is LinearLayout && root.orientation == LinearLayout.VERTICAL) return root
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i) ?: continue
            if (c is ViewGroup) findFirstVertical(c)?.let { return it }
        }
        return null
    }

    private fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
