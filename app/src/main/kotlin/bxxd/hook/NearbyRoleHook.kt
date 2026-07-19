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
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object NearbyRoleHook : BaseHook {

    private const val TAG = "llhook-NearbyRole"
    private const val ROLE_TAG_KEY = "llhook_nearby_role_tag"

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 监听底层通用的列表适配器
            val baseAdapterClass = lpparam.classLoader.loadClass("com.chad.library.adapter.base.BaseQuickAdapter")

            baseAdapterClass.findMethod { name == "onBindViewHolder" && parameterTypes.size == 2 }.hookAfter { param ->
                try {
                    val adapter = param.thisObject
                    val holder = param.args[0]
                    val position = param.args[1] as Int

                    // 获取当前列表项的数据模型
                    val item = XposedHelpers.callMethod(adapter, "getItem", position) ?: return@hookAfter
                    val roleRaw = XposedHelpers.getObjectField(item, "role") as? String
                    if (roleRaw.isNullOrEmpty()) return@hookAfter

                    val itemView = XposedHelpers.getObjectField(holder, "itemView") as View
                    
                    // 调用官方代码，把数字 (1, 0.5) 翻译成汉字
                    val roleDisplay = resolveRoleName(itemView.context, roleRaw)

                    val adapterClassName = adapter.javaClass.name
                    // 判断是大图列表模式还是小图宫格模式，分别注入
                    if (adapterClassName.contains("PeopleList")) {
                        injectRoleToListItem(holder, roleDisplay)
                    } else if (adapterClassName.contains("PeopleGrid")) {
                        injectRoleToGridItem(holder, roleDisplay)
                    }
                } catch (_: Throwable) {}
            }

            XposedBridge.log("$TAG Hook BaseQuickAdapter.onBindViewHolder 成功 (属性标签注入完毕)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG 属性标签 UI 注入失败: $t")
        }
    }

    // 列表模式 (大图) 的 UI 注入逻辑
    private fun injectRoleToListItem(holder: Any, role: String) {
        val itemView = XposedHelpers.getObjectField(holder, "itemView") as View
        val context = itemView.context

        val weightContainerId = context.resources.getIdentifier("ll_basic_info_weight", "id", context.packageName)
        val targetView = if (weightContainerId != 0) {
            itemView.findViewById<View>(weightContainerId)
        } else null

        val anchor = targetView ?: run {
            val weightId = context.resources.getIdentifier("tv_basic_info_weight", "id", context.packageName)
            if (weightId == 0) return
            itemView.findViewById<View>(weightId)
        } ?: return

        val parent = anchor.parent as? ViewGroup ?: return

        var roleView = parent.findViewWithTag<TextView>(ROLE_TAG_KEY)
        if (roleView == null) {
            val splitLineId = context.resources.getIdentifier("shape_split_line_path", "drawable", context.packageName)
            val splitLine = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(context, 6), dpToPx(context, 15))
                if (splitLineId != 0) setBackgroundResource(splitLineId)
                else setBackgroundColor(Color.parseColor("#0d000000"))
            }
            roleView = TextView(context).apply {
                tag = ROLE_TAG_KEY
                textSize = 10.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#888888"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0d000000"))
                    cornerRadius = 5f
                }
                setPadding(4, 1, 4, 1)
                gravity = Gravity.CENTER
            }
            val anchorIndex = parent.indexOfChild(anchor)
            parent.addView(splitLine, anchorIndex + 1)
            parent.addView(roleView, anchorIndex + 2)
        }

        roleView.text = role
        roleView.visibility = View.VISIBLE
    }

    // 宫格模式 (小图) 的 UI 注入逻辑
    private fun injectRoleToGridItem(holder: Any, role: String) {
        val itemView = XposedHelpers.getObjectField(holder, "itemView") as View
        val context = itemView.context

        val nameId = context.resources.getIdentifier("name_view", "id", context.packageName)
        if (nameId == 0) return
        val nameView = itemView.findViewById<View>(nameId) as? TextView ?: return
        val nameParent = nameView.parent as? ViewGroup ?: return

        var roleView = nameParent.findViewWithTag<TextView>(ROLE_TAG_KEY)
        if (roleView == null) {
            roleView = TextView(context).apply {
                tag = ROLE_TAG_KEY
                textSize = 9f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(0xFF6D00FF.toInt()) // 显眼的紫色标签
                    cornerRadius = 6f
                }
                setPadding(4, 0, 4, 0)
                gravity = Gravity.CENTER
                maxLines = 1
            }

            if (nameParent is LinearLayout) {
                roleView.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 4
                    topMargin = 1
                    bottomMargin = 1
                }
            }
            val nameIndex = nameParent.indexOfChild(nameView)
            if (nameIndex >= 0) {
                nameParent.addView(roleView, nameIndex + 1)
            } else {
                nameParent.addView(roleView)
            }
        }

        roleView.text = role
        roleView.visibility = View.VISIBLE
    }

    private fun dpToPx(context: android.content.Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    // 调用官方翻译器：把底层数字 1 翻译成 "1" 或者其他显示文字
    private fun resolveRoleName(context: android.content.Context, roleRaw: String): String {
        try {
            for (className in arrayOf(
                "com.blued.android.module.common.user.UserInfoHelper",
                "com.blued.android.module.common.p223user.UserInfoHelper"
            )) {
                val clazz = XposedHelpers.findClassIfExists(className, context.classLoader) ?: continue
                for (method in clazz.declaredMethods) {
                    if (method.returnType == String::class.java
                        && method.parameterTypes.size == 2
                        && method.parameterTypes[0] == android.content.Context::class.java
                        && method.parameterTypes[1] == String::class.java
                    ) {
                        method.isAccessible = true
                        val result = method.invoke(null, context, roleRaw) as? String
                        if (!result.isNullOrEmpty()) return result
                    }
                }
            }
        } catch (_: Throwable) {}
        return roleRaw
    }
}
