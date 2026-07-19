package bxxd.hook

import android.content.Context
import com.github.kyuubiran.ezxhelper.utils.Log
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

object MmkvCacheClearHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 🚀 响应手动控制：App 启动时只打印模块就绪日志，绝对不自动清理文件
        Log.d("【蓝蓝hook】一键清理标签缓存模块已满血载入，等待菜单指令！")
    }

    /**
     * 🧹 供外部（如 FloatingUI）按钮点击时手动调用的核心清理方法
     * @param context 当前界面的上下文 Activity 
     * @return 是否成功删除了旧缓存
     */
    fun clearMmkvCache(context: Context): Boolean {
        try {
            // 动态获取当前 App 的数据沙盒根目录 (支持任何安卓系统及多开分身路径)
            val dataDir = context.applicationInfo.dataDir
            val mmkvDir = File(dataDir, "files/mmkv")

            if (mmkvDir.exists() && mmkvDir.isDirectory) {
                // 锁定标签主缓存文件和对应的 CRC 校验文件
                val targetFile = File(mmkvDir, "blued_sf_general_set")
                val targetCrcFile = File(mmkvDir, "blued_sf_general_set.crc")

                var isCleared = false

                // 物理销毁主文件
                if (targetFile.exists()) {
                    isCleared = targetFile.delete() || isCleared
                }
                
                // 物理销毁 CRC 校验文件
                if (targetCrcFile.exists()) {
                    isCleared = targetCrcFile.delete() || isCleared
                }

                if (isCleared) {
                    Log.d("【蓝蓝hook】手动清理成功：本地 MMKV 旧标签数据已被彻底粉碎！")
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.e("【蓝蓝hook】手动清理 MMKV 缓存文件失败", t)
        }
        return false
    }
}
