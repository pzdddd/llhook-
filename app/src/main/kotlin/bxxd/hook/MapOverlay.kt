package bxxd.hook

import android.app.Activity
import com.example.ui.showHostComposeScreen
import com.example.ui.theme.MyApplicationTheme

/**
 * 地图精准选点入口。
 *
 * 阶段 2 重构: 原 820+ 行的传统 Android View 实现 (FrameLayout 全屏覆盖层 + 反射 MapView +
 * 各类 AlertDialog) 已全部移至 .legacy-ui/MapOverlay.kt.disabled。
 *
 * 现由 [MapEngineController] 承载反射逻辑, UI 由 com.example.ui.MapPickerScreen 用 llhook
 * 玻璃拟态 Compose 全屏页承载 (AndroidView 包裹反射 MapView + 周边全 Compose 控件)。
 *
 * 本 object 仅保留 [showMap] 入口签名, 供 RealLocationHook / TrackHook / 设置页工具栏调用,
 * 统一弹出 Compose 版地图选点页。
 */
object MapOverlay {

    fun showMap(activity: Activity, radarLat: Double = 0.0, radarLng: Double = 0.0) {
        showHostComposeScreen(activity) { onClose ->
            MyApplicationTheme {
                com.example.ui.MapPickerScreen(
                    activity = activity,
                    radarLat = radarLat,
                    radarLng = radarLng,
                    onClose = onClose
                )
            }
        }
    }
}
