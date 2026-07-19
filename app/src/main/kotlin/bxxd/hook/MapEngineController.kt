package bxxd.hook

import android.app.Activity
import android.os.Bundle
import android.view.View
import de.robv.android.xposed.XposedHelpers

/**
 * 宿主原生地图引擎反射控制器 (纯逻辑, 不含任何 UI)。
 *
 * 阶段 2 重构: 从 .legacy-ui/MapOverlay.kt 提取的核心引擎逻辑, 兼容腾讯 / 高德 / 百度三引擎。
 *  - 通过宿主 classLoader 反射加载宿主打包的 TextureMapView / MapView (用宿主的 SDK, 免费 + 原生手势);
 *  - 反射调用 CameraUpdateFactory (腾讯/高德) 或 MapStatusUpdateFactory (百度) 控制地图;
 *  - 反射取地图中心坐标 / 添加标记 / 缩放。
 *
 * UI 由 com.example.ui.MapPickerScreen 用 Compose + AndroidView 承载 (llhook 玻璃拟态风格)。
 *
 * @param classLoader 宿主真实 ClassLoader (FloatingUI.hostClassLoader)
 */
class MapEngineController(private val classLoader: ClassLoader) {

    /** 反射创建的地图 View (TextureMapView / MapView), 供 Compose AndroidView 包装。 */
    var mapView: View? = null
        private set

    /** 地图实例 (TencentMap / AMap / BaiduMap), MapView.getMap() 返回值。 */
    var mapInstance: Any? = null
        private set

    /** 当前引擎: 0=无, 1=腾讯, 2=高德, 3=百度。 */
    var engineType = 0
        private set

    private var pkgPrefix = ""
    private var latLngClassName = ""
    private var markerOptionsClassName = ""

    /**
     * 检测宿主可用的地图引擎并初始化 MapView。
     * @param enginePref 0=自动, 1=强制腾讯, 2=强制高德, 3=强制百度
     * @return true=成功初始化; false=未检测到兼容引擎 (盲狙模式, UI 需回退到经纬度输入)
     */
    fun detectAndInit(activity: Activity, enginePref: Int): Boolean {
        // ⚠️ MapView 创建必须在主线程 (腾讯/高德/百度 SDK 内部 Handler 需要 Looper.myLooper() != null,
        //    后台线程会拖 NullPointerException: Looper.mQueue on null. 调用方请用 Dispatchers.Main)
        if (android.os.Looper.myLooper() == null) {
            android.util.Log.e("MapEngine", "detectAndInit called on thread without Looper, preparing one")
            android.os.Looper.prepare()
        }
        var hasTencent = false
        var hasAmap = false
        var hasBaidu = false
        try { classLoader.loadClass("com.tencent.tencentmap.mapsdk.maps.TextureMapView"); hasTencent = true } catch (e: Throwable) {
            try { classLoader.loadClass("com.tencent.tencentmap.mapsdk.maps.MapView"); hasTencent = true } catch (e2: Throwable) {}
        }
        try { classLoader.loadClass("com.amap.api.maps.TextureMapView"); hasAmap = true } catch (e: Throwable) {
            try { classLoader.loadClass("com.amap.api.maps.MapView"); hasAmap = true } catch (e2: Throwable) {}
        }
        try { classLoader.loadClass("com.baidu.mapapi.map.TextureMapView"); hasBaidu = true } catch (e: Throwable) {
            try { classLoader.loadClass("com.baidu.mapapi.map.MapView"); hasBaidu = true } catch (e2: Throwable) {}
        }

        when {
            enginePref == 1 && hasTencent -> { engineType = 1; pkgPrefix = "com.tencent.tencentmap.mapsdk.maps" }
            enginePref == 2 && hasAmap -> { engineType = 2; pkgPrefix = "com.amap.api.maps" }
            enginePref == 3 && hasBaidu -> { engineType = 3; pkgPrefix = "com.baidu.mapapi.map" }
            hasTencent -> { engineType = 1; pkgPrefix = "com.tencent.tencentmap.mapsdk.maps" }
            hasAmap -> { engineType = 2; pkgPrefix = "com.amap.api.maps" }
            hasBaidu -> { engineType = 3; pkgPrefix = "com.baidu.mapapi.map" }
        }

        if (engineType == 0) return false

        // 各引擎隐私合规初始化
        when (engineType) {
            1 -> {
                try {
                    val initClass = classLoader.loadClass("$pkgPrefix.TencentMapInitializer")
                    XposedHelpers.callStaticMethod(initClass, "setAgreePrivacy", true)
                } catch (e: Throwable) {}
                latLngClassName = "$pkgPrefix.model.LatLng"
                markerOptionsClassName = "$pkgPrefix.model.MarkerOptions"
            }
            2 -> {
                try {
                    val initClass = classLoader.loadClass("$pkgPrefix.MapsInitializer")
                    XposedHelpers.callStaticMethod(initClass, "updatePrivacyShow", activity, true, true)
                    XposedHelpers.callStaticMethod(initClass, "updatePrivacyAgree", activity, true)
                } catch (e: Throwable) {}
                latLngClassName = "$pkgPrefix.model.LatLng"
                markerOptionsClassName = "$pkgPrefix.model.MarkerOptions"
            }
            3 -> {
                try {
                    val sdkInitClass = classLoader.loadClass("com.baidu.mapapi.SDKInitializer")
                    XposedHelpers.callStaticMethod(sdkInitClass, "setAgreePrivacy", activity, true)
                } catch (e: Throwable) {}
                // 百度: LatLng 在 model 包, MarkerOptions 在 map 包 (pkgPrefix)
                latLngClassName = "com.baidu.mapapi.model.LatLng"
                markerOptionsClassName = "$pkgPrefix.MarkerOptions"
            }
        }

        // 创建 MapView (TextureMapView 优先, 回退 MapView)
        val viewClassName = try { classLoader.loadClass("$pkgPrefix.TextureMapView"); "TextureMapView" } catch (e: Throwable) { "MapView" }
        val mapViewClass = classLoader.loadClass("$pkgPrefix.$viewClassName")
        mapView = XposedHelpers.newInstance(mapViewClass, activity) as View
        try { XposedHelpers.callMethod(mapView, "onCreate", Bundle()) } catch (e: Throwable) {}
        try { XposedHelpers.callMethod(mapView, "onResume") } catch (e: Throwable) {}
        mapInstance = XposedHelpers.callMethod(mapView, "getMap")

        // 启用全部手势 + 关闭原生缩放按钮 (统一用 Compose 自定义 +/-, 三引擎表现一致)
        try {
            val uiSettings = XposedHelpers.callMethod(mapInstance, "getUiSettings")
            try { XposedHelpers.callMethod(uiSettings, "setZoomControlsEnabled", false) } catch (e: Throwable) {}
            try { XposedHelpers.callMethod(uiSettings, "setAllGesturesEnabled", true) } catch (e: Throwable) {}
            try { XposedHelpers.callMethod(uiSettings, "setScrollGesturesEnabled", true) } catch (e: Throwable) {}
            try { XposedHelpers.callMethod(uiSettings, "setZoomGesturesEnabled", true) } catch (e: Throwable) {}
        } catch (e: Throwable) {}

        return true
    }

    /** 移动地图到指定坐标+缩放。 */
    fun moveCameraTo(lat: Double, lng: Double, zoom: Float) {
        val mi = mapInstance ?: return
        if (pkgPrefix.isEmpty()) return
        try {
            if (engineType == 3) {
                // 百度: setMapStatus + MapStatusUpdateFactory
                val latLng = XposedHelpers.newInstance(classLoader.loadClass(latLngClassName), lat, lng)
                val builder = XposedHelpers.newInstance(classLoader.loadClass("$pkgPrefix.MapStatus\$Builder"))
                XposedHelpers.callMethod(builder, "target", latLng)
                XposedHelpers.callMethod(builder, "zoom", zoom)
                val mapStatus = XposedHelpers.callMethod(builder, "build")
                val factoryClass = classLoader.loadClass("$pkgPrefix.MapStatusUpdateFactory")
                val update = XposedHelpers.callStaticMethod(factoryClass, "newMapStatus", mapStatus)
                XposedHelpers.callMethod(mi, "setMapStatus", update)
            } else {
                // 腾讯/高德: moveCamera + CameraUpdateFactory
                val latLng = XposedHelpers.newInstance(classLoader.loadClass(latLngClassName), lat, lng)
                val factoryClass = classLoader.loadClass("$pkgPrefix.CameraUpdateFactory")
                val update = XposedHelpers.callStaticMethod(factoryClass, "newLatLngZoom", latLng, zoom)
                XposedHelpers.callMethod(mi, "moveCamera", update)
            }
        } catch (e: Throwable) {}
    }

    /** 缩放一步 (引擎无关, 绕过百度无原生缩放按钮的限制)。 */
    fun zoomStep(zoomIn: Boolean) {
        val mi = mapInstance ?: return
        if (pkgPrefix.isEmpty()) return
        try {
            if (engineType == 3) {
                val factoryClass = classLoader.loadClass("$pkgPrefix.MapStatusUpdateFactory")
                val update = XposedHelpers.callStaticMethod(factoryClass, if (zoomIn) "zoomIn" else "zoomOut")
                XposedHelpers.callMethod(mi, "setMapStatus", update)
            } else {
                val factoryClass = classLoader.loadClass("$pkgPrefix.CameraUpdateFactory")
                val update = XposedHelpers.callStaticMethod(factoryClass, if (zoomIn) "zoomIn" else "zoomOut")
                XposedHelpers.callMethod(mi, "moveCamera", update)
            }
        } catch (e: Throwable) {}
    }

    /**
     * 取地图中心坐标。无引擎时回退到盲狙坐标 [blindLat]/[blindLng]。
     */
    fun getCenterCoordinate(blindLat: Double = 0.0, blindLng: Double = 0.0): Pair<Double, Double>? {
        val mi = mapInstance
        if (mi != null && pkgPrefix.isNotEmpty()) {
            return try {
                // 百度: getMapStatus().target; 腾讯/高德: getCameraPosition().target
                val target = if (engineType == 3) {
                    val mapStatus = XposedHelpers.callMethod(mi, "getMapStatus")
                    XposedHelpers.getObjectField(mapStatus, "target")
                } else {
                    val cameraPosition = XposedHelpers.callMethod(mi, "getCameraPosition")
                    XposedHelpers.getObjectField(cameraPosition, "target")
                }
                val lat = try { XposedHelpers.callMethod(target, "getLatitude") as Double } catch (e: Throwable) { XposedHelpers.getDoubleField(target, "latitude") }
                val lng = try { XposedHelpers.callMethod(target, "getLongitude") as Double } catch (e: Throwable) { XposedHelpers.getDoubleField(target, "longitude") }
                Pair(lat, lng)
            } catch (e: Throwable) { null }
        }
        return if (blindLat != 0.0 && blindLng != 0.0) Pair(blindLat, blindLng) else null
    }

    /** 在地图上添加标记。 */
    fun addMarker(lat: Double, lng: Double, title: String) {
        val mi = mapInstance ?: return
        if (pkgPrefix.isEmpty()) return
        try {
            val latLng = XposedHelpers.newInstance(classLoader.loadClass(latLngClassName), lat, lng)
            val markerOptions = XposedHelpers.newInstance(classLoader.loadClass(markerOptionsClassName))
            XposedHelpers.callMethod(markerOptions, "position", latLng)
            XposedHelpers.callMethod(markerOptions, "title", title)
            // 百度用 addOverlay(MarkerOptions); 腾讯/高德用 addMarker(MarkerOptions)
            val addMethod = if (engineType == 3) "addOverlay" else "addMarker"
            XposedHelpers.callMethod(mi, addMethod, markerOptions)
        } catch (e: Throwable) {}
    }

    /** 引擎名称 (供 UI 显示)。 */
    val engineName: String
        get() = when (engineType) {
            1 -> "腾讯地图"
            2 -> "高德地图"
            3 -> "百度地图"
            else -> "未检测到引擎"
        }

    /** MapView 生命周期 (Compose DisposableEffect 调用)。 */
    fun onPause() { try { mapView?.let { XposedHelpers.callMethod(it, "onPause") } } catch (e: Throwable) {} }
    fun onResume() { try { mapView?.let { XposedHelpers.callMethod(it, "onResume") } } catch (e: Throwable) {} }
    fun onDestroy() { try { mapView?.let { XposedHelpers.callMethod(it, "onDestroy") } } catch (e: Throwable) {} }
}
