package bxxd.hook

import android.app.AndroidAppHelper
import android.widget.Toast
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 查看私密相册解锁 (把相册申请状态 applyStatus 强行改为 0)
 *
 * 兼容两个版本:
 *  - 标准版: UserInfoPrivateAlbumAdapter 有名为 "a" 的方法, args[1] 是相册对象 (BluedAlbum)
 *  - 极速版: 同一 adapter 被 (爱加密) 加固, 方法被改名为 convert(BaseViewHolder, Object),
 *           args[1] 仍是相册对象。BluedAlbum.applyStatus 是 public int, 两版字段名一致。
 */
object PrivatePhotoHook : BaseHook {

    // 记录一下上次弹窗的时间，防止滑动列表时一直弹窗卡死手机
    private var lastToastTime: Long = 0

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val adapterClass = lpparam.classLoader.loadClass(Config.TargetClasses.PRIVATE_PHOTO)

            // 兼容标准版("a")与极速版("convert")：枚举所有处理单个相册项的方法
            adapterClass.declaredMethods.forEach { method ->
                val ps = method.parameterTypes
                val isStandard = method.name == "a" && ps.size >= 2
                val isLite = method.name == "convert" &&
                    ps.size == 2 &&
                    ps[0].name.contains("BaseViewHolder")
                if (!isStandard && !isLite) return@forEach

                method.hookBefore { param ->
                    // 检查悬浮窗开关
                    if (!Config.isFeatureEnabled("switch_private_photo")) return@hookBefore

                    // 确保参数数量足够，且第二个参数不为空 (相册对象)
                    if (param.args == null || param.args.size < 2) return@hookBefore
                    val albumObj = param.args[1] ?: return@hookBefore

                    try {
                        // 核心：强行把申请状态改成 0
                        XposedHelpers.setIntField(albumObj, "applyStatus", 0)

                        // 弹窗提示 (做了防刷屏处理，2秒内只弹一次)
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastToastTime > 2000) {
                            lastToastTime = currentTime
                            val context = AndroidAppHelper.currentApplication()
                            if (context != null) {
                                Toast.makeText(context, "已解锁", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        try {
                            // 容错：如果 applyStatus 不是基本数据类型 int 而是 Integer 对象
                            XposedHelpers.setObjectField(albumObj, "applyStatus", 0)
                        } catch (e2: Exception) { }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
