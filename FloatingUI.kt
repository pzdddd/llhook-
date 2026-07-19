package bxxd.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

object FloatingUI : BaseHook {

    private var isReceiverRegistered = false
    private val switchMap = mutableMapOf<String, Switch>()
    private val mainHandler = Handler(Looper.getMainLooper())
    var hostClassLoader: ClassLoader? = null

    private fun dp2pxV(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, android.content.res.Resources.getSystem().displayMetrics).toInt()
    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hostClassLoader = lpparam.classLoader 
        try {
            val homeActivityClass = lpparam.classLoader.loadClass("com.soft.blued.ui.home.HomeActivity")
            homeActivityClass.findMethod { name == "onResume" }.hookAfter { param ->
                val activity = param.thisObject as Activity
                
                try {
                    val data = MapHelper.pullOfflineData()
                    if (data != null) {
                        val fileTs = data.optLong("ts", 0L)
                        val localPrefs = activity.getSharedPreferences("llhook_blued_local_v2", Context.MODE_PRIVATE)
                        val localTs = localPrefs.getLong("master_timestamp", 0L)
                        
                        if (fileTs > localTs) {
                            Config.setCustomLocation(data.optDouble("lat"), data.optDouble("lng"), activity)
                            localPrefs.edit().putLong("master_timestamp", fileTs).apply()
                        }
                    }
                } catch (e: Throwable) {}

                if (!isReceiverRegistered) {
                    isReceiverRegistered = true
                    try {
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                if (intent.action == "bxxd.hook.PULL_REQUEST") {
                                    val localPrefs = context.getSharedPreferences("llhook_blued_local_v2", Context.MODE_PRIVATE)
                                    val json = JSONObject()
                                    localPrefs.all.forEach { (k, v) -> json.put(k, v) }
                                    val reply = Intent("bxxd.hook.PULL_RESPONSE")
                                    reply.setPackage("bxxd.hook")
                                    reply.putExtra("data", json.toString())
                                    context.sendBroadcast(reply)
                                } 
                                else if (intent.action == "bxxd.hook.MAIN_SYNC_PUSH") {
                                    val key = intent.getStringExtra("key") ?: return
                                    val valueStr = intent.getStringExtra("value") ?: return
                                    val editor = context.getSharedPreferences("llhook_blued_local_v2", Context.MODE_PRIVATE).edit()
                                    when (valueStr) {
                                        "true" -> editor.putBoolean(key, true)
                                        "false" -> editor.putBoolean(key, false)
                                        else -> editor.putString(key, valueStr)
                                    }
                                    editor.apply()

                                    mainHandler.post {
                                        switchMap[key]?.let { sw ->
                                            sw.setOnCheckedChangeListener(null)
                                            sw.isChecked = (valueStr == "true")
                                            sw.setOnCheckedChangeListener { _, isChecked -> Config.setFeatureEnabled(key, isChecked, context) }
                                        }
                                    }
                                }
                            }
                        }
                        val filter = IntentFilter().apply {
                            addAction("bxxd.hook.PULL_REQUEST")
                            addAction("bxxd.hook.MAIN_SYNC_PUSH")
                        }
                        if (Build.VERSION.SDK_INT >= 33) activity.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                        else activity.applicationContext.registerReceiver(receiver, filter)
                    } catch (e: Throwable) {}
                }

                activity.runOnUiThread {
                    val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
                    if (rootView?.findViewWithTag<View>("llhook_floating_btn") == null) {
                        addFloatingButton(activity)
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addFloatingButton(activity: Activity) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val floatingBtn = TextView(activity).apply {
            tag = "llhook_floating_btn"
            text = "蓝\nhook"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#80000000")); setStroke(2, Color.WHITE) }
            val size = dp2px(activity, 50f)
            layoutParams = FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; marginEnd = 30 }
        }

        var dX = 0f; var dY = 0f; var isDragging = false; var downX = 0f; var downY = 0f
        floatingBtn.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { dX = view.x - event.rawX; dY = view.y - event.rawY; downX = event.rawX; downY = event.rawY; isDragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > 10 || abs(event.rawY - downY) > 10) {
                        isDragging = true
                        view.animate().x(event.rawX + dX).y(event.rawY + dY).setDuration(0).start()
                    }
                }
                MotionEvent.ACTION_UP -> { if (!isDragging) showMenuDialog(activity) }
            }
            true
        }
        rootView.addView(floatingBtn)
    }

    /** 模块主菜单弹窗(悬浮球 & 设置页入口 共用)。改为 public 供其它模块复用。 */
    fun showMenuDialog(activity: Activity) {
        switchMap.clear()
        val t = Theme.current(activity)

        // —— 外层滚动容器 (iOS 分组列表背景) ——
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.background)
            setPadding(0, dp2pxV(4f), 0, dp2pxV(12f))
        }

        // —— 分组1: 功能开关 (圆角卡片, 每行自带分隔线) ——
        container.addView(activity.iosSectionHeader("功能开关", t))
        val switchSection = activity.iosSection(t)
        val switchItems = listOf(
            "⚡ 一键 lite (减负提速)" to "switch_lite",
            "风险用户拦截" to "switch_risk_user_block",
            "图片相册去水印" to "switch_watermark",
            "位置追踪 (个人主页右上角)" to "switch_track",
            "虚拟定位" to "switch_virtual_location",
            "拦截直播请求" to "switch_block_live",
            "属性透视 (伪装极速版网络)" to "switch_spoof_lite"
        )
        switchItems.forEachIndexed { idx, (label, key) ->
            val row = activity.iosSwitchRow(label, key, t)
            switchSection.addView(row)
            // 同步到 switchMap (兼容广播同步逻辑): row 的第二个子View是Switch
            ((row as? ViewGroup)?.getChildAt(1) as? Switch)?.let { switchMap[key] = it }
            if (idx < switchItems.size - 1) switchSection.addView(activity.iosDivider(t))
        }
        // 以下功能开关已迁移到 Blued 设置页入口 (SettingsEntryHook 注入), 悬浮窗不再显示:
        //   闪照转照片 / 消息防撤回 / 解锁本地VIP / 查看私密相册 / 悄悄查看 / 去除截屏限制 / 去广告
        container.addView(switchSection)

        // —— 分组2: 工具入口 (全宽按钮, iOS 圆角胶囊) ——
        container.addView(activity.iosSectionHeader("工具", t))
        val toolSection = activity.iosSection(t).apply {
            setPadding(dp2pxV(4f), dp2pxV(8f), dp2pxV(4f), dp2pxV(8f))
        }
        var dialogRef: AlertDialog? = null
        // 设备检测 (Blued 视角)
        toolSection.addView(activity.iosButton("🔍 设备检测 (Blued 视角)", t, IOSShape.GHOST) {
            try { DetectHook.showDetectDialog(activity) }
            catch (e: Throwable) { Toast.makeText(activity, "检测界面异常: ${e.message}", Toast.LENGTH_SHORT).show() }
        })
        // 净化中心
        toolSection.addView(activity.iosButton("🧹 净化中心 (逐项开关)", t, IOSShape.GHOST) {
            showPurifyDialog(activity)
        })
        // 聊天备份
        toolSection.addView(activity.iosButton("💾 聊天备份与恢复", t, IOSShape.GHOST) {
            showBackupDialog(activity)
        })
        // 地图选点
        toolSection.addView(activity.iosButton("📍 打开地图精准选点", t, IOSShape.GHOST) {
            try { dialogRef?.dismiss(); MapOverlay.showMap(activity) }
            catch (e: Throwable) { Toast.makeText(activity, "地图唤起失败：${e.message}", Toast.LENGTH_SHORT).show() }
        })
        // 站街 (横向双按钮)
        val autoVisitRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        autoVisitRow.addView(android.widget.Button(activity).apply {
            text = "⚡ 一键站街"; textSize = 15f; isAllCaps = false; stateListAnimator = null
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(t.accent); cornerRadius = dp2pxV(Theme.RADIUS_BUTTON).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp2pxV(46f), 1f).apply { marginEnd = dp2pxV(6f) }
            setOnClickListener { showAutoVisitDialog(activity) }
        })
        autoVisitRow.addView(android.widget.Button(activity).apply {
            text = "⛔ 停止站街"; textSize = 15f; isAllCaps = false; stateListAnimator = null
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(t.danger); cornerRadius = dp2pxV(Theme.RADIUS_BUTTON).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp2pxV(46f), 1f).apply { marginStart = dp2pxV(6f) }
            setOnClickListener {
                if (AutoVisitHook.isVisiting) { AutoVisitHook.stopAutoVisit(); Toast.makeText(activity, "已下达停止站街指令", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(activity, "当前未在站街", Toast.LENGTH_SHORT).show()
            }
        })
        toolSection.addView(autoVisitRow)
        // 清理缓存
        toolSection.addView(activity.iosButton("🗑 清理角色缓存并重启", t, IOSShape.DESTRUCTIVE) {
            val success = MmkvCacheClearHook.clearMmkvCache(activity)
            Toast.makeText(activity, if (success) "缓存已粉碎，正在重启..." else "当前无旧缓存，直接重启...", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ restartHostApp(activity) }, 500)
        })
        container.addView(toolSection)

        val scrollView = ScrollView(activity).apply { addView(container); setBackgroundColor(t.background) }
        val titleView = activity.iosLargeTitle("蓝蓝hook", t)

        val dialog = AlertDialog.Builder(activity, iosDialogTheme(t))
            .setCustomTitle(titleView)
            .setView(scrollView)
            .setPositiveButton("重启软件生效") { _, _ -> restartHostApp(activity) }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { switchMap.clear() }
            .create()
        dialogRef = dialog
        dialog.show()
    }

    /**
     * 净化中心弹窗 (重构后的菜单式净化)。
     * 顶部「净化总开关」= 旧的 switch_remove_ads (打开则所有净化项全部生效, 向后兼容);
     * 点击总开关行展开/收起逐项列表, 数据源来自 AdsHook.PURIFY_ITEMS。
     */
    fun showPurifyDialog(activity: Activity) {
        switchMap.clear()
        val t = Theme.current(activity)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.background)
            setPadding(0, dp2pxV(4f), 0, dp2pxV(12f))
        }
        container.addView(activity.iosSectionFooter("总开关打开 = 全部生效。点击总开关行展开逐项列表。", t))

        // —— 卡片: 总开关 (可展开/收起) + 逐项列表 ——
        container.addView(activity.iosSectionHeader("净化选项", t))
        val section = activity.iosSection(t)

        // 逐项列表容器 (初始隐藏)
        val itemsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // 总开关行 (向后兼容旧 switch_remove_ads), 点击整行展开/收起逐项
        val masterRow = activity.iosSwitchRow("净化总开关 (全部生效)", "switch_remove_ads", t)
        ((masterRow as? ViewGroup)?.getChildAt(1) as? Switch)?.let { switchMap["switch_remove_ads"] = it }
        masterRow.setOnClickListener {
            itemsContainer.visibility = if (itemsContainer.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        section.addView(masterRow)
        section.addView(activity.iosDivider(t))

        // 逐项独立开关 (与设置页共用 AdsHook.PURIFY_ITEMS 清单)
        AdsHook.PURIFY_ITEMS.forEachIndexed { idx, (label, key) ->
            val row = activity.iosSwitchRow(label, key, t)
            itemsContainer.addView(row)
            // 记入 switchMap 以支持广播同步
            ((row as? ViewGroup)?.getChildAt(1) as? Switch)?.let { switchMap[key] = it }
            if (idx < AdsHook.PURIFY_ITEMS.size - 1) itemsContainer.addView(activity.iosDivider(t))
        }
        section.addView(itemsContainer)
        container.addView(section)

        val scrollView = ScrollView(activity).apply { addView(container); setBackgroundColor(t.background) }

        AlertDialog.Builder(activity, iosDialogTheme(t))
            .setCustomTitle(activity.iosLargeTitle("🧹 净化中心", t))
            .setView(scrollView)
            .setPositiveButton("重启软件生效") { _, _ -> restartHostApp(activity) }
            .setNegativeButton("返回", null)
            .setOnDismissListener { switchMap.clear() }
            .show()
    }

    /**
     * 聊天备份与恢复弹窗。
     * 顶部: 当前备份目录 + 自定义目录按钮 + 立即备份按钮;
     * 下方: 已有备份列表, 每个可一键恢复(恢复前自动安全备份)或删除。
     */
    fun showBackupDialog(activity: Activity) {
        var dialogRef: AlertDialog? = null
        val t = Theme.current(activity)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.background)
            setPadding(0, dp2pxV(4f), 0, dp2pxV(12f))
        }

        // —— 当前备份目录 (脚注样式) ——
        val rootDir = ChatBackupManager.getBackupRoot(activity)
        container.addView(activity.iosSectionFooter("备份目录:\n${rootDir.absolutePath}", t))

        // —— 操作按钮卡片 ——
        container.addView(activity.iosSectionHeader("操作", t))
        val opSection = activity.iosSection(t).apply { setPadding(dp2pxV(4f), dp2pxV(8f), dp2pxV(4f), dp2pxV(8f)) }
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val btnDir = android.widget.Button(activity).apply {
            text = "📁 自定义目录"; textSize = 15f; isAllCaps = false; stateListAnimator = null
            setTextColor(t.accent)
            background = GradientDrawable().apply { setColor(if (t.isDark) t.cardElevated else Color.parseColor("#F0F5FF")); cornerRadius = dp2pxV(Theme.RADIUS_BUTTON).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp2pxV(44f), 1f).apply { marginEnd = dp2pxV(6f) }
            setOnClickListener { showCustomDirDialog(activity) { dialogRef?.dismiss(); showBackupDialog(activity) } }
        }
        val btnDoBackup = android.widget.Button(activity).apply {
            text = "💾 立即备份"; textSize = 15f; isAllCaps = false; stateListAnimator = null
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(t.success); cornerRadius = dp2pxV(Theme.RADIUS_BUTTON).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp2pxV(44f), 1f).apply { marginStart = dp2pxV(6f) }
            setOnClickListener {
                val r = ChatBackupManager.backup(activity)
                Toast.makeText(activity, r.msg, Toast.LENGTH_LONG).show()
                if (r.success) { dialogRef?.dismiss(); showBackupDialog(activity) }
            }
        }
        btnRow.addView(btnDir); btnRow.addView(btnDoBackup)
        opSection.addView(btnRow)
        container.addView(opSection)

        // —— 已有备份列表 (圆角卡片) ——
        container.addView(activity.iosSectionHeader("已有备份 (点击恢复)", t))
        val backups = ChatBackupManager.listBackups(activity)
        val listSection = activity.iosSection(t).apply { setPadding(dp2pxV(4f), dp2pxV(4f), dp2pxV(4f), dp2pxV(4f)) }
        if (backups.isEmpty()) {
            listSection.addView(TextView(activity).apply {
                text = "暂无备份, 点击「立即备份」创建"
                textSize = 13f; setTextColor(t.textSecondary); setPadding(0, dp2pxV(20f), 0, dp2pxV(20f))
                gravity = Gravity.CENTER
            })
        } else {
            backups.forEachIndexed { idx, zip ->
                listSection.addView(createBackupRow(activity, zip, t) { dialogRef?.dismiss(); showBackupDialog(activity) })
                if (idx < backups.size - 1) listSection.addView(activity.iosDivider(t))
            }
        }
        container.addView(listSection)

        val scrollView = ScrollView(activity).apply { addView(container); setBackgroundColor(t.background) }

        dialogRef = AlertDialog.Builder(activity, iosDialogTheme(t))
            .setCustomTitle(activity.iosLargeTitle("💾 聊天备份与恢复", t))
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .create()
        dialogRef?.show()
    }

    /** 备份列表的单行: 文件名/大小/时间 + [恢复][删除] */
    private fun createBackupRow(activity: Activity, zip: File, t: Theme.Colors, onchanged: () -> Unit): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2pxV(12f), dp2pxV(10f), dp2pxV(12f), dp2pxV(10f))
        }
        val info = TextView(activity).apply {
            val meta = ChatBackupManager.readMeta(zip)
            val srcLabel = when (meta?.sourcePackage) {
                "com.soft.blued" -> "正式版"
                "com.danlan.xiaolan" -> "极速版"
                null -> ""
                else -> "未知"
            }
            val srcLine = if (srcLabel.isEmpty()) "" else "  ·  来源:$srcLabel"
            text = "${zip.name}\n${ChatBackupManager.formatSize(zip.length())}  ·  ${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", zip.lastModified())}$srcLine"
            textSize = 14f; setTextColor(t.textPrimary)
        }
        row.addView(info)
        val opRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp2pxV(8f), 0, 0) }
        val btnRestore = android.widget.Button(activity).apply {
            text = "恢复"; textSize = 13f; isAllCaps = false; stateListAnimator = null
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(t.warning); cornerRadius = dp2pxV(Theme.RADIUS_BUTTON).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp2pxV(40f), 1f).apply { marginEnd = dp2pxV(6f) }
            setOnClickListener { confirmRestore(activity, zip) }
        }
        val btnDel = android.widget.Button(activity).apply {
            text = "删除"; textSize = 13f; isAllCaps = false; stateListAnimator = null
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(t.danger); cornerRadius = dp2pxV(Theme.RADIUS_BUTTON).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp2pxV(40f), 1f).apply { marginStart = dp2pxV(6f) }
            setOnClickListener {
                AlertDialog.Builder(activity, iosDialogTheme(t)).setTitle("删除备份")
                    .setMessage("确定删除 ${zip.name}?")
                    .setPositiveButton("删除") { _, _ ->
                        ChatBackupManager.deleteBackup(zip)
                        Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
                        onchanged()
                    }.setNegativeButton("取消", null).show()
            }
        }
        opRow.addView(btnRestore); opRow.addView(btnDel)
        row.addView(opRow)
        return row
    }

    /** 恢复确认: 恢复前自动安全备份当前数据, 防误恢复丢失; 跨版本恢复会校验兼容性 */
    private fun confirmRestore(activity: Activity, zip: File) {
        val meta = ChatBackupManager.readMeta(zip)
        val compat = ChatBackupManager.checkCompatibility(meta?.sourcePackage, Config.currentBluedPackage)
        val danger = compat.level == ChatBackupManager.CompatLevel.DANGER
        val metaLine = if (meta == null) "\n(旧版备份, 无来源标记)" else
            "\n备份来源: ${meta.sourcePackage ?: "未知"}" +
            (if (meta.sourceAppVersion.isNullOrBlank()) "" else " v${meta.sourceAppVersion}") +
            (if (meta.dbUserVersion <= 0) "" else "  [DB v${meta.dbUserVersion}]")
        AlertDialog.Builder(activity).setTitle(if (danger) "⚠️ 恢复警告" else "恢复聊天数据")
            .setMessage("将用 ${zip.name} 覆盖当前聊天数据。$metaLine\n\n${compat.message}\n\n恢复前会先自动备份当前数据, 完成后 App 自动重启。")
            .setPositiveButton(if (danger) "仍要恢复" else "确认恢复") { _, _ ->
                // 安全网: 先备份当前状态
                ChatBackupManager.backup(activity)
                val n = ChatBackupManager.restore(activity, zip)
                if (n >= 0) {
                    Toast.makeText(activity, "已恢复 $n 个文件, 即将重启...", Toast.LENGTH_LONG).show()
                    mainHandler.postDelayed({ restartHostApp(activity) }, 1500)
                } else {
                    Toast.makeText(activity, "恢复失败, 数据未改动", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("取消", null).show()
    }

    /** 自定义备份目录输入框 */
    private fun showCustomDirDialog(activity: Activity, onDone: () -> Unit) {
        val et = EditText(activity).apply {
            hint = "留空 = 默认 Download/bluedbackups"
            setText(Config.getBackupDir(activity))
            setSingleLine(true)
        }
        AlertDialog.Builder(activity).setTitle("自定义备份目录")
            .setMessage("输入绝对路径, 例: /storage/emulated/0/Download/bluedbackups\n留空则使用默认目录。")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                Config.setBackupDir(et.text.toString().trim(), activity)
                Toast.makeText(activity, "已保存, 重启后生效", Toast.LENGTH_SHORT).show()
                onDone()
            }.setNegativeButton("取消", null).show()
    }

    private fun showAutoVisitDialog(activity: Activity) {
        val t = Theme.current(activity)
        val mainTextColor = t.textPrimary
        val hintTextColor = t.textTertiary
        val bgColor = t.background

        val container = LinearLayout(activity).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(dp2pxV(20f), dp2pxV(8f), dp2pxV(20f), dp2pxV(20f)) 
            setBackgroundColor(bgColor) 
        }
        
        val infoText = TextView(activity).apply {
            text = "已拦截到 ${AutoVisitHook.cachedUsers.size} 名用户。\n\n(提示：没人或者人少的先去刷新列表。访问延迟建议一秒或者以上，1000＝1秒。)"
            textSize = 13f; setTextColor(t.warning); setPadding(0, 0, 0, dp2pxV(20f))
        }
        container.addView(infoText)

        val distLayout = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 20); gravity = Gravity.CENTER_VERTICAL }
        distLayout.addView(TextView(activity).apply { text = "距离范围(km): "; textSize = 15f; setTextColor(mainTextColor) })
        val minDistInput = EditText(activity).apply { 
            hint = "0"; setText("0"); setTextColor(mainTextColor); setHintTextColor(hintTextColor)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; layoutParams = LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.WRAP_CONTENT) 
        }
        distLayout.addView(minDistInput)
        distLayout.addView(TextView(activity).apply { text = " 至 "; textSize = 15f; setTextColor(mainTextColor) })
        val maxDistInput = EditText(activity).apply { 
            hint = "10"; setText("10"); setTextColor(mainTextColor); setHintTextColor(hintTextColor)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; layoutParams = LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.WRAP_CONTENT) 
        }
        distLayout.addView(maxDistInput)
        container.addView(distLayout)

        val countLayout = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 20); gravity = Gravity.CENTER_VERTICAL }
        countLayout.addView(TextView(activity).apply { text = "访问数量(0为不限制): "; textSize = 15f; setTextColor(mainTextColor) })
        val maxCountInput = EditText(activity).apply { 
            hint = "0"; setText("0"); setTextColor(mainTextColor); setHintTextColor(hintTextColor)
            inputType = InputType.TYPE_CLASS_NUMBER; layoutParams = LinearLayout.LayoutParams(200, LinearLayout.LayoutParams.WRAP_CONTENT) 
        }
        countLayout.addView(maxCountInput)
        container.addView(countLayout)

        val onlineLayout = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 20); gravity = Gravity.CENTER_VERTICAL }
        val onlineSwitch = Switch(activity).apply { 
            text = "只访问在线用户 (15分钟内活跃)"
            textSize = 15f
            setTextColor(mainTextColor) 
            isChecked = true 
        }
        onlineLayout.addView(onlineSwitch)
        container.addView(onlineLayout)

        val delayLayout = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 20); gravity = Gravity.CENTER_VERTICAL }
        delayLayout.addView(TextView(activity).apply { text = "随机延迟(毫秒): "; textSize = 14f; setTextColor(mainTextColor) })
        val minDelayInput = EditText(activity).apply { 
            hint = "1000"; setText("1000"); setTextColor(mainTextColor); setHintTextColor(hintTextColor)
            inputType = InputType.TYPE_CLASS_NUMBER; layoutParams = LinearLayout.LayoutParams(150, LinearLayout.LayoutParams.WRAP_CONTENT) 
        }
        delayLayout.addView(minDelayInput)
        delayLayout.addView(TextView(activity).apply { text = " 至 "; textSize = 14f; setTextColor(mainTextColor) })
        val maxDelayInput = EditText(activity).apply { 
            hint = "3000"; setText("3000"); setTextColor(mainTextColor); setHintTextColor(hintTextColor)
            inputType = InputType.TYPE_CLASS_NUMBER; layoutParams = LinearLayout.LayoutParams(150, LinearLayout.LayoutParams.WRAP_CONTENT) 
        }
        delayLayout.addView(maxDelayInput)
        container.addView(delayLayout)

        val scrollView = ScrollView(activity).apply { 
            addView(container) 
            setBackgroundColor(bgColor)
        }
        
        val titleView = activity.iosLargeTitle("⚡ 一键站街", t)

        AlertDialog.Builder(activity, iosDialogTheme(t))
            .setCustomTitle(titleView)
            .setView(scrollView)
            .setPositiveButton("确认饿了") { _, _ ->
                val minDistance = minDistInput.text.toString().toDoubleOrNull() ?: 0.0
                val maxDistance = maxDistInput.text.toString().toDoubleOrNull() ?: 10.0
                val maxCount = maxCountInput.text.toString().toIntOrNull() ?: 0
                val minDelay = minDelayInput.text.toString().toLongOrNull() ?: 1000L
                val maxDelay = maxDelayInput.text.toString().toLongOrNull() ?: 3000L
                val onlineOnly = onlineSwitch.isChecked
                
                AutoVisitHook.startAutoVisit(activity, minDistance, maxDistance, minDelay, maxDelay, maxCount, onlineOnly)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp2px(context: Context, dp: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt()

    @Deprecated("已迁移至 iosSwitchRow, 保留兼容旧调用", ReplaceWith("iosSwitchRow"))
    private fun createSwitch(context: Context, title: String, prefKey: String): Switch {
        val t = Theme.current(context)
        val sw = Switch(context).apply {
            text = title; textSize = 16f; setPadding(0, dp2pxV(15f), 0, dp2pxV(15f))
            setTextColor(t.textPrimary)
            isChecked = Config.isFeatureEnabled(prefKey, context)
            if (Build.VERSION.SDK_INT >= 21) {
                try { thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); trackTintList = android.content.res.ColorStateList.valueOf(if (isChecked) t.switchOn else t.switchOff) } catch (_: Throwable) {}
            }
            setOnCheckedChangeListener { _, isChecked -> Config.setFeatureEnabled(prefKey, isChecked, context) }
        }
        switchMap[prefKey] = sw 
        return sw
    }

    private fun restartHostApp(activity: Activity) {
        try {
            val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent); android.os.Process.killProcess(android.os.Process.myPid()); exitProcess(0)
            }
        } catch (e: Exception) {}
    }
}
