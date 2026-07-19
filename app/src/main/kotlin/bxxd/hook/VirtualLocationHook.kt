package bxxd.hook

import android.app.AndroidAppHelper
import android.location.Location
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

object VirtualLocationHook : BaseHook {

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // ==========================================
        // ⚔️ 核心：深度反射覆盖所有族谱私有变量
        // ==========================================
        val polluteLocation = { obj: Any ->
            try {
                val context = AndroidAppHelper.currentApplication()
                if (context != null && Config.isFeatureEnabled("switch_virtual_location", context)) {
                    val lat = Config.getCustomLat(context)
                    val lng = Config.getCustomLng(context)

                    // 尝试常规 Setter
                    try { obj.javaClass.getMethod("setLatitude", Double::class.java).invoke(obj, lat) } catch (e: Exception) {}
                    try { obj.javaClass.getMethod("setLongitude", Double::class.java).invoke(obj, lng) } catch (e: Exception) {}

                    // 深度向上遍历，找出所有父类层级中可能藏匿坐标的变量并强行篡改
                    var clazz: Class<*>? = obj.javaClass
                    while (clazz != null && clazz != Any::class.java) {
                        for (field in clazz.declaredFields) {
                            val name = field.name.lowercase()
                            // 只要变量名包含 lat 或 lng，全部无差别覆盖！
                            if (name.contains("lat") || name.contains("lng") || name.contains("lon")) {
                                if (field.type == Double::class.javaPrimitiveType || field.type == Double::class.java) {
                                    try {
                                        field.isAccessible = true
                                        if (name.contains("lat")) field.set(obj, lat)
                                        else field.set(obj, lng)
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                        clazz = clazz.superclass
                    }
                }
            } catch (e: Exception) {}
        }

        // ==========================================
        // 🛡️ [终极防线] 彻底切断 Wi-Fi 与基站定位 (反定位泄漏)
        // 迫使高德/腾讯/百度地图只能使用我们伪造的纯 GPS 数据
        // ==========================================
        try {
            // 1. 致盲 Wi-Fi 扫描 (让它以为周围没有路由器)
            WifiManager::class.java.findMethod { name == "getScanResults" }.hookAfter { param ->
                val ctx = AndroidAppHelper.currentApplication()
                if (ctx != null && Config.isFeatureEnabled("switch_virtual_location", ctx)) {
                    param.result = ArrayList<ScanResult>() // 返回空列表
                }
            }
            
            // 2. 伪造当前连接的 Wi-Fi MAC 地址为全 0
            WifiInfo::class.java.findMethod { name == "getBSSID" }.hookAfter { param ->
                val ctx = AndroidAppHelper.currentApplication()
                if (ctx != null && Config.isFeatureEnabled("switch_virtual_location", ctx)) {
                    param.result = "00:00:00:00:00:00"
                }
            }
            WifiInfo::class.java.findMethod { name == "getMacAddress" }.hookAfter { param ->
                val ctx = AndroidAppHelper.currentApplication()
                if (ctx != null && Config.isFeatureEnabled("switch_virtual_location", ctx)) {
                    param.result = "00:00:00:00:00:00"
                }
            }

            // 3. 致盲基站扫描 (切断移动网络定位)
            TelephonyManager::class.java.findMethod { name == "getAllCellInfo" }.hookAfter { param ->
                val ctx = AndroidAppHelper.currentApplication()
                if (ctx != null && Config.isFeatureEnabled("switch_virtual_location", ctx)) {
                    param.result = ArrayList<CellInfo>()
                }
            }
            TelephonyManager::class.java.findMethod { name == "getCellLocation" }.hookAfter { param ->
                val ctx = AndroidAppHelper.currentApplication()
                if (ctx != null && Config.isFeatureEnabled("switch_virtual_location", ctx)) {
                    param.result = null
                }
            }
            TelephonyManager::class.java.findMethod { name == "getNeighboringCellInfo" }.hookAfter { param ->
                val ctx = AndroidAppHelper.currentApplication()
                if (ctx != null && Config.isFeatureEnabled("switch_virtual_location", ctx)) {
                    param.result = ArrayList<Any>()
                }
            }
        } catch (e: Exception) {}

        // ==========================================
        // 🌍 原生 Location 对象无死角拦截
        // ==========================================
        try {
            // 拦截所有对象诞生
            for (constructor in Location::class.java.constructors) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { polluteLocation(param.thisObject) }
                })
            }
            Location::class.java.findMethod { name == "getLatitude" }.hookAfter { param ->
                val ctx = AndroidAppHelper.currentApplication()
                if (ctx != null && Config.isFeatureEnabled("switch_virtual_location", ctx)) {
                    param.result = Config.getCustomLat(ctx)
                    polluteLocation(param.thisObject)
                }
            }
            Location::class.java.findMethod { name == "getLongitude" }.hookAfter { param ->
                val ctx = AndroidAppHelper.currentApplication()
                if (ctx != null && Config.isFeatureEnabled("switch_virtual_location", ctx)) {
                    param.result = Config.getCustomLng(ctx)
                    polluteLocation(param.thisObject)
                }
            }
            Location::class.java.findMethod { name == "set" }.hookBefore { param ->
                val ctx = AndroidAppHelper.currentApplication()
                if (ctx != null && Config.isFeatureEnabled("switch_virtual_location", ctx)) {
                    val sourceLoc = param.args[0] as Location
                    sourceLoc.latitude = Config.getCustomLat(ctx)
                    sourceLoc.longitude = Config.getCustomLng(ctx)
                }
            }
        } catch (e: Exception) {}

        // ==========================================
        // 🗺️ 各大地图 SDK (高德、腾讯、百度) 通杀拦截
        // ==========================================
        val mapClassesToHook = listOf(
            "com.amap.api.location.AMapLocation",       // 高德
            "com.tencent.map.geolocation.TencentLocation", // 腾讯
            "com.baidu.location.BDLocation"             // 百度
        )

        for (className in mapClassesToHook) {
            try {
                val mapLocClass = lpparam.classLoader.loadClass(className)
                for (constructor in mapLocClass.constructors) {
                    XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) { polluteLocation(param.thisObject) }
                    })
                }
                mapLocClass.findMethod { name == "getLatitude" }.hookAfter { param ->
                    val ctx = AndroidAppHelper.currentApplication()
                    if (ctx != null && Config.isFeatureEnabled("switch_virtual_location", ctx)) {
                        param.result = Config.getCustomLat(ctx)
                        polluteLocation(param.thisObject)
                    }
                }
                mapLocClass.findMethod { name == "getLongitude" }.hookAfter { param ->
                    val ctx = AndroidAppHelper.currentApplication()
                    if (ctx != null && Config.isFeatureEnabled("switch_virtual_location", ctx)) {
                        param.result = Config.getCustomLng(ctx)
                        polluteLocation(param.thisObject)
                    }
                }
            } catch (e: Exception) {}
        }
    }
}
