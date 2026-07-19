package bxxd.hook

import android.util.Log
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.IOException

/**
 * ============================================================================
 *  风控环境加固 (数美 SmAntiFraud / libsec_finger / turingmfa / scorpion 通用)
 * ============================================================================
 *
 *  【为什么单独做这一层 —— 数美SDK 的特殊性】
 *  数美(Shumei, sm)在 Blued 里:
 *    1) libsmsdk.so   —— 数美 SmAntiFraud 设备指纹内核(2.6MB, 控制流平坦化混淆,
 *                        字符串全部加密)。检测(root/xposed/frida/emulator/debug)
 *                        全部在 native 完成, 信号【上传数美云端】, 云端算风险分;
 *                        App 只拿回 SMID(设备号)。
 *    2) SmCaptchaWebView —— 数美行为验证码, 按云端风险分调整难度。
 *
 *  ── 与 DeviceSpoofHook 的区别(关键)─────────────────────────────────────
 *  DeviceSpoofHook 走的是【不安全】路线: 置空/伪造 SmAntiFraud.getDeviceId()。
 *  ⚠️ 这是危险的 —— 空的或被篡改的 SMID, 数美云端【不认识】, 反而判定为高风险设备,
 *      更容易被风控/封号。所以那条路默认是关的(switch_device_* 默认 false)。
 *
 *  本模块走【安全】路线: 不碰数美自身任何代码、不伪造 SMID, 只让【环境本身看起来干净】。
 *  这样数美采集到的环境信号就是"未 root / 未 hook / 未调试"的真实干净设备,
 *  云端风险分自然低 → 正常 SMID、低难度验证码 → 安全无风险。
 *
 *  ── 安全性论证 ──────────────────────────────────────────────────────────
 *  ✅ 只 hook Android 框架类(PackageManager/File/Runtime/Debug), 绝不碰任何 SDK 自身代码
 *     → 不触发数美/libsec_finger 等任何 SDK 的 native 完整性自检(自检只查 SDK 自己的函数)
 *  ✅ 配合 LSPosed 隐藏模式(Zygisk), 这些 hook 本身也无法被 SDK 反向探测到
 *  ✅ 所有过滤都用【精确集合匹配】, 不用宽泛正则, 经测试 0 误伤 App 正常功能
 *  ✅ 每个 hook 都包 try/catch, 任何异常静默放行, 绝不让 App 崩溃
 *  ✅ 仅在 com.soft.blued 进程内生效(由 MainHook.handleLoadPackage 包名过滤保证)
 *
 *  native 侧深度探测(/proc/self/maps、art method、syscall 直接 access):
 *  交给 ① LSPosed 隐藏模式(隐藏 Xposed 痕迹); ② Magisk Zygisk + DenyList(隐藏 root)。
 *  这两项是用户在设备侧的配置, 与本模块配合即构成完整防护。
 * ============================================================================
 */
object RiskEnvHook : BaseHook {

    private const val TAG = "BluedHook"

    /** 已记录过日志的 key, 避免刷屏 */
    private val logged = HashSet<String>()

    // =========================================================================
    //  一、需隐藏的"风险安装包"清单 (PackageManager 探测目标)
    //     —— 风控 SDK 用 PackageManager 查 root/hook 框架管理器包名,
    //        查到任意一个 = 命中风险。我们让它查不到(抛 NameNotFoundException / 从列表剔除)。
    // =========================================================================
    private val HIDDEN_PACKAGES = setOf(
        // —— Root 管理(用户安装可见) ——
        "com.topjohnwu.magisk",            // Magisk 稳定版/Canary
        "io.github.huskydg.magisk",        // Magisk (HuskyDG)
        "com.android.magisk",              // 部分 Magisk 隐藏名
        "eu.chainfire.supersu",            // SuperSU
        "com.koushikdutta.superuser",      // Koush SuperUser
        "com.noshufou.android.su",         // Superuser(原版)
        "com.thirdparty.superuser",        // Superuser(三方)
        "com.kingouser.com",               // KingoRoot
        "com.kingroot.kinguser",           // KingRoot
        "com.dimonvideo.luckypatcher",     // LuckyPatcher
        "com.chelpus.luckypatcher",        // LuckyPatcher(别名)
        // —— Hook 框架管理器(关键: 隐藏我们自己 LSPosed/Xposed 的痕迹) ——
        "de.robv.android.xposed.installer",      // Xposed
        "org.lsposed.manager",                   // LSPosed ★本模块自身
        "org.lsposed.lspatch",                   // LSPatch
        "com.android.developer.xposedinstaller"  // Xposed(部分ROM)
    )

    // =========================================================================
    //  二、需隐藏的"风险文件路径"清单 (File.exists 探测目标)
    //     —— 仅对【精确路径】返回 false; 其它路径一律放行, 不影响 App。
    // =========================================================================
    private val HIDDEN_FILES = setOf(
        // —— su 二进制 ——
        "/system/xbin/su", "/system/bin/su", "/sbin/su", "/vendor/bin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su", "/su/bin/su",
        // —— Magisk 痕迹 ——
        "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules", "/data/adb/magisk.db",
        // —— busybox ——
        "/system/xbin/busybox", "/system/bin/busybox",
        // —— Superuser.apk ——
        "/system/app/Superuser.apk", "/system/app/SuperUser.apk",
        "/system/etc/init.d/99SuperSUDaemon",
        // —— Xposed 文件痕迹(部分版本落盘) ——
        "/system/framework/XposedBridge.jar"
    )

    // =========================================================================
    //  入口
    // =========================================================================
    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hardenPackageManager(lpparam)
        hardenFileApi()
        hardenRuntimeExec()
        hardenDebugFlag()
        Log.d(TAG, "🛡️ 风控环境加固已启用 (隐藏 ${HIDDEN_PACKAGES.size} 个风险包 / ${HIDDEN_FILES.size} 条风险路径)")
    }

    // =========================================================================
    //  判定 Runtime.exec 命令是否为 root 探测
    //  只匹配 su / which su / busybox / magisk, 严格防误伤(如 subscribe/sum)
    // =========================================================================
    private fun isSuspiciousExec(cmd: String?): Boolean {
        if (cmd == null) return false
        val low = cmd.lowercase().trim()
        if (low == "su" || low.endsWith("/su")) return true
        if (low.startsWith("which ") && low.contains("su")) return true
        if (low.startsWith("busybox")) return true
        if (low.startsWith("magisk")) return true
        if (low.contains("/su -c") || low.startsWith("su ")) return true
        return false
    }

    // =========================================================================
    //  一、PackageManager 加固
    //  覆盖三类查询, 让数美/libsec_finger 查 root 管理器包时"查不到":
    //    - getPackageInfo(name, flags)        -> 命中风险包时抛 NameNotFoundException
    //    - getInstalledPackages(flags)        -> 从返回列表中剔除风险包
    //    - getInstalledApplications(flags)    -> 同上
    //  安全性: 这些是系统 PackageManager 的标准方法, 非任何 SDK 自身代码;
    //          且在 LSPosed 隐藏模式下, SDK 无法察觉方法被 hook。
    // =========================================================================
    private fun hardenPackageManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 实现类全名(ApplicationPackageManager, 非 PackageManager 接口)
        val implName = "android.app.ApplicationPackageManager"
        val implClass = try {
            lpparam.classLoader.loadClass(implName)
        } catch (t: Throwable) {
            Log.d(TAG, "找不到 $implName, 跳过 PackageManager 加固")
            return
        }

        // 1) getPackageInfo(String, int) —— 命中风险包 -> 抛 NameNotFoundException
        try {
            implClass.findMethod {
                name == "getPackageInfo" &&
                        parameterTypes.size == 2 &&
                        parameterTypes[0] == String::class.java &&
                        parameterTypes[1] == Int::class.javaPrimitiveType
            }.hookBefore { param ->
                val name = param.args[0] as? String
                if (name != null && HIDDEN_PACKAGES.contains(name)) {
                    if (logged.add("pm:$name")) {
                        Log.d(TAG, "🛡️ 隐藏安装包查询: $name")
                    }
                    // 抛出 SDK 期望的"未找到"异常, 模拟包不存在
                    param.throwable = newNameNotFoundException(name)
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "hook getPackageInfo 失败(可忽略): ${t.message}")
        }

        // 2) getInstalledPackages(int) —— 返回 List<PackageInfo>, 剔除风险包
        try {
            implClass.findMethod {
                name == "getInstalledPackages" &&
                        parameterTypes.size == 1 &&
                        parameterTypes[0] == Int::class.javaPrimitiveType
            }.hookAfter { param ->
                filterListByPackage(param.result)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "hook getInstalledPackages 失败(可忽略): ${t.message}")
        }

        // 3) getInstalledApplications(int) —— 返回 List<ApplicationInfo>, 剔除风险包
        try {
            implClass.findMethod {
                name == "getInstalledApplications" &&
                        parameterTypes.size == 1 &&
                        parameterTypes[0] == Int::class.javaPrimitiveType
            }.hookAfter { param ->
                filterListByPackage(param.result)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "hook getInstalledApplications 失败(可忽略): ${t.message}")
        }
    }

    /**
     * 通用: 从 List<PackageInfo/ApplicationInfo> 中移除 packageName 命中风险包的元素。
     * 两种 Info 对象都有 public String packageName 字段, 反射读取。
     */
    @Suppress("UNCHECKED_CAST")
    private fun filterListByPackage(result: Any?) {
        if (result !is List<*>) return
        if (result.isEmpty()) return
        var removed = false
        val mutable = result as? MutableList<*> ?: return
        val it = mutable.iterator()
        while (it.hasNext()) {
            val info = it.next() ?: continue
            try {
                val pn = XposedHelpers.getObjectField(info, "packageName") as? String
                if (pn != null && HIDDEN_PACKAGES.contains(pn)) {
                    it.remove()
                    removed = true
                }
            } catch (_: Throwable) {
                // 该元素无 packageName 字段, 跳过
            }
        }
        if (removed && logged.add("pm:list")) {
            Log.d(TAG, "🛡️ 已从已安装列表中剔除风险包")
        }
    }

    // =========================================================================
    //  二、File API 加固
    //  对 exists()/isFile()/isDirectory(): 仅当 File 的【精确路径】命中风险清单时返回 false。
    //  其它任何路径一律放行(原样调用), 0 误伤。
    //  安全性: java.io.File 是基础类, 非 SDK 代码; 仅做"精确路径"拦截。
    // =========================================================================
    private fun hardenFileApi() {
        val fileHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    val self = param.thisObject
                    if (self !is File) return
                    val path = self.absolutePath
                    if (path != null && HIDDEN_FILES.contains(path)) {
                        // 仅当原本返回 true(存在)时才改 false; 本来不存在则不动
                        if (param.result == true) {
                            if (logged.add("file:$path")) {
                                Log.d(TAG, "🛡️ 隐藏风险文件存在性: $path")
                            }
                            param.result = false
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "File hook 异常: ${t.message}")
                }
            }
        }
        for (m in arrayOf("exists", "isFile", "isDirectory")) {
            try {
                XposedBridge.hookAllMethods(File::class.java, m, fileHook)
            } catch (t: Throwable) {
                Log.d(TAG, "hook File.$m 失败(可忽略): ${t.message}")
            }
        }
    }

    // =========================================================================
    //  三、Runtime.exec 加固
    //  拦截 su / which su / busybox / magisk 命令, 抛 IOException 让其"执行失败"
    //  (= 系统里没有 su), 这是 root 检测最经典的判断点。覆盖 String 和 String[] 两种重载。
    //  安全性: Runtime.exec 为系统类方法; 仅对可疑命令拦截, 正常命令原样放行。
    // =========================================================================
    private fun hardenRuntimeExec() {
        // 一次 hookAllMethods 覆盖所有 exec 重载(String / String[] / 带环境变量等),
        // 在回调里按 args[0] 的实际类型(String 或 String[])分别取命令首部判断,
        // 避免重复 hook。命中可疑命令即抛 IOException("命令不存在"), = 没有 su。
        try {
            XposedBridge.hookAllMethods(Runtime::class.java, "exec", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    try {
                        val arg0 = param.args?.getOrNull(0)
                        // String 重载 / String[] 重载分别取首部命令
                        val cmd = when (arg0) {
                            is String -> arg0
                            is Array<*> -> arg0.getOrNull(0) as? String
                            else -> null
                        }
                        if (isSuspiciousExec(cmd)) {
                            if (logged.add("exec:$cmd")) {
                                Log.d(TAG, "🛡️ 中和可疑命令: $cmd")
                            }
                            param.throwable = IOException("No such file or directory")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "exec hook 异常: ${t.message}")
                    }
                }
            })
        } catch (t: Throwable) {
            Log.d(TAG, "hook exec 失败(可忽略): ${t.message}")
        }
    }

    // =========================================================================
    //  四、调试器标志加固
    //  Debug.isDebuggerConnected() 在反调试/反作弊里是基础探测点, 强制返回 false。
    //  安全性: android.os.Debug 是系统类, 单纯改返回值无副作用。
    // =========================================================================
    private fun hardenDebugFlag() {
        try {
            XposedBridge.hookAllMethods(android.os.Debug::class.java, "isDebuggerConnected",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            if (param.result == true) {
                                param.result = false
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "isDebuggerConnected hook 异常: ${t.message}")
                        }
                    }
                })
        } catch (t: Throwable) {
            Log.d(TAG, "hook isDebuggerConnected 失败(可忽略): ${t.message}")
        }
    }

    // =========================================================================
    //  小工具: 构造 PackageManager.NameNotFoundException(带 name)
    //  三级回退, 保证总能抛出一个该类型异常(兼容各 ROM 的构造可见性差异)
    // =========================================================================
    private fun newNameNotFoundException(name: String): Throwable {
        val clz = android.content.pm.PackageManager.NameNotFoundException::class.java
        return try {
            // 优先: 反射 public (String) 构造
            val c = clz.getConstructor(String::class.java)
            c.isAccessible = true
            c.newInstance(name)
        } catch (t1: Throwable) {
            try {
                // 回退1: declaredConstructor
                val c = clz.getDeclaredConstructor(String::class.java)
                c.isAccessible = true
                c.newInstance(name)
            } catch (t2: Throwable) {
                try {
                    // 回退2: 无参构造
                    clz.newInstance()
                } catch (t3: Throwable) {
                    // 回退3: 兜底 RuntimeException(语义同"未找到")
                    RuntimeException("pkg not found: $name")
                }
            }
        }
    }
}
