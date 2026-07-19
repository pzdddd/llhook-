package bxxd.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

// 整个模块的唯一入口点
class MainHook : IXposedHookLoadPackage {

    // 加上 Volatile 防止多线程下的并发重复注入
    @Volatile
    private var isInitialized = false
    
    @Volatile
    private var hasShownToast = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // 🚀 激活自检逻辑 (模块自身进程: 让界面知道模块已激活)
        if (lpparam.packageName == Config.PACKAGE_NAME) {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.example.MainActivity",
                    lpparam.classLoader,
                    "isModuleActive",
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any {
                            return true
                        }
                    }
                )
            } catch (e: Throwable) {}
            return
        }

        // 过滤包名 (同时支持正式版 com.soft.blued 与极速版 com.danlan.xiaolan)
        // 两版内部 Java 类名完全一致, 仅 applicationId 不同
        if (!Config.isBluedPackage(lpparam.packageName)) return
        Config.setCurrentBluedPackage(lpparam.packageName)

        // =======================================================
        // 💥 系统级全局探壳：无视所有加固厂商！
        // =======================================================
        try {
            // 🟢 【无壳直通测试】：先拿探针刺一下，没报错说明是脱壳版
            lpparam.classLoader.loadClass("com.soft.blued.ui.home.HomeActivity")
            initAllModules(lpparam)
            return 
        } catch (e: ClassNotFoundException) {
            // 🔴 发现加固壳！不慌，立刻启用系统级 Application 拦截
        }

        // 核心武器：直接从 Android 系统底层基类拦截，不管壳怎么伪装，都必须调用 ContextWrapper
        val contextWrapperHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isInitialized) return
                
                // 获取系统传递过来的真实 Context
                val context = param.args[0] as? Context ?: return
                val realClassLoader = context.classLoader
                
                try {
                    // 用拿到的真实 ClassLoader 去试探 Blued 核心类
                    realClassLoader.loadClass("com.soft.blued.ui.home.HomeActivity")
                    
                    // 没报错，说明壳已经把真实代码吐出来了！
                    isInitialized = true
                    
                    // 💥 偷天换日：替换掉 lpparam 里的假 ClassLoader
                    lpparam.classLoader = realClassLoader 
                    
                    // 放狗！全量注入！
                    initAllModules(lpparam)
                    
                } catch (e: Throwable) {
                    // 还没解密完，继续等...
                }
            }
        }

        try {
            // 绝杀 1：拦截 ContextWrapper.attachBaseContext (最高优先级)
            XposedBridge.hookAllMethods(
                ContextWrapper::class.java,
                "attachBaseContext",
                contextWrapperHook
            )
            
            // 绝杀 2：作为备用，拦截 Application.onCreate (双重保险)
            XposedBridge.hookAllMethods(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInitialized) return
                        val app = param.thisObject as? Application ?: return
                        val realClassLoader = app.classLoader
                        
                        try {
                            realClassLoader.loadClass("com.soft.blued.ui.home.HomeActivity")
                            isInitialized = true
                            lpparam.classLoader = realClassLoader 
                            initAllModules(lpparam)
                        } catch (e: Throwable) {}
                    }
                }
            )
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    // 把注入逻辑统一封装
    private fun initAllModules(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 使用真身的 ClassLoader 初始化 EzXHelper 环境
            EzXHelperInit.initHandleLoadPackage(lpparam)
            EzXHelperInit.setLogTag("llhook")

            // ==========================================
            // 🚀 首屏/核心界面 启动成功提示
            // ==========================================
            try {
                val firstActivityClass = lpparam.classLoader.loadClass("com.soft.blued.ui.welcome.FirstActivity")
                firstActivityClass.findMethod { name == "onCreate" }.hookAfter { param ->
                    if (!hasShownToast) {
                        hasShownToast = true
                        val activity = param.thisObject as Activity
                        Toast.makeText(activity, "模块已加载", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Throwable) { }

            try {
                val terminalActivityClass = lpparam.classLoader.loadClass("com.blued.android.core.ui.TerminalActivity")
                terminalActivityClass.findMethod { name == "onCreate" }.hookAfter { param ->
                    if (!hasShownToast) {
                        hasShownToast = true
                        val activity = param.thisObject as Activity
                        Toast.makeText(activity, "模块已加载", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Throwable) { }

            // 依次唤醒所有子模块
            FloatingUI.init(lpparam)      // 注入悬浮窗UI
            AdsHook.init(lpparam)         // 注入去广告逻辑
            AntiRecallHook.init(lpparam)  // 注入防撤回逻辑
            VipHook.init(lpparam)         // 注入本地vip
            PrivatePhotoHook.init(lpparam)// 隐私相册
            ChatAdvanceHook.init(lpparam) // 悄悄查看
            VirtualLocationHook.init(lpparam)// 虚拟定位
            TrackHook.init(lpparam)       // 定位追踪
            FeedHook.init(lpparam)        // 隐藏距离显示
            TokenHook.init(lpparam)       // tk获取
            ChatSpyHook.init(lpparam)     // 用户信息
            LiveBlockHook.init(lpparam)   // 直播拦截
            AdBlockHook.init(lpparam)     // 广告拦截
            DeviceSpoofHook.init(lpparam) //数美拦截(置空/伪造 SMID, 默认关, 不安全)
            RiskEnvHook.init(lpparam)     //数美/风控环境加固(安全路线: 隐藏 root/hook 痕迹, 强烈推荐常开)
            //SettingsEntryHook.init(lpparam)//已移除: 模块入口改在「我的」页面田字里, 不再在 Blued 设置页注入
            LicenseBypassHook.init(lpparam)//license授权拦截
            WatermarkHook.init(lpparam) //去除保存照片水印
            AutoVisitHook.init(lpparam)//自动访问
            RealLocationHook.init(lpparam) //关闭虚拟定位位置追踪
            Ban2Hook.init(lpparam)        // 防删聊天与风险提示拦截
            NetworkSpoofHook.init(lpparam) // 极速版网络请求头伪装
            NearbyRoleHook.init(lpparam)   // 附近列表角色 UI 注入 (显示数据)
            ChatWatermarkHook.init(lpparam)
            ForcePushHook.init(lpparam)         // 强制消息推送(解决手机收不到 Blued 推送)
            KeepAliveHook.init(lpparam)         // Blued 进程保活心跳(让实时推送持续生效)
            // 网络抓包: 始终挂 hook (开销极小), 实际记录由 captureEnabled 标志门控 (UI 可实时开关)
            BluedDecryptHook.Init(lpparam)
            UserProfileHook.Init(lpparam)
            HomeQQHook.init(lpparam)            // QQ风格首页: 左上角圆形头像 + 点击/右滑打开我的
            UIPurifyHook.init(lpparam)          // UI净化: 移除聊天风险提示 + 资料页聊天按钮改圆形右下角
            DetectHook.init(lpparam)             // 设备检测可视化 (Blued 采集数据 + 本地风控检测, 从悬浮窗主菜单进入)
            LiteHook.init(lpparam)              // 一键lite: 禁广告SDK/关Tinker/砍重型SO, 标准版减负提速(保留推送与地图)

            // 蓝钩悬浮球注入 (hook HomeActivity.onResume, 新老 Xposed 框架都能用)
            // 与「我的」页面田字格「模块入口」互为双保险
            try {
                val homeActivityClass = lpparam.classLoader.loadClass("com.soft.blued.ui.home.HomeActivity")
                XposedBridge.hookAllMethods(homeActivityClass, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        FloatButtonInjector.inject(activity)
                    }
                })
            } catch (e: Throwable) {
                XposedBridge.log("llhook float hook err: $e")
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
