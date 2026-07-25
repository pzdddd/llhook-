package bxxd.hook

import android.content.Context
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
    }

    // 1. 悄悄查看 (拦截底层的 gRPC 已读回执请求)
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
