package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import bxxd.hook.Config
import java.io.ByteArrayOutputStream

/**
 * 独立的透明图片选择 Activity (运行在模块进程, 合法的 ComponentActivity)。
 *
 *  存在原因: 宿主注入的 Compose 面板无法使用 rememberLauncherForActivityResult
 *  (宿主 Activity 不一定是 ActivityResultRegistryOwner → 闪退)。故把选图操作
 *  放到一个独立的、模块自有的透明 Activity, 它天然支持 ActivityResult,
 *  选完图压缩为 base64 写入 Config (跨进程同步), 再 finish 回到宿主。
 *
 *  通用化: 通过 Intent extra 指定写入哪两个 config key,
 *  这样「附近列表卡片」「身边页背景」等多个面板都能复用同一个选图 Activity。
 */
class ImagePickerActivity : ComponentActivity() {

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        try {
            if (uri != null) {
                val keyImage = intent.getStringExtra(EXTRA_KEY_IMAGE) ?: "nearby_card_image"
                val keyUse = intent.getStringExtra(EXTRA_KEY_USE_IMAGE) ?: "nearby_card_use_image"
                val bmp = decodeSampledBitmap(contentResolver, uri, 1100)
                if (bmp != null) {
                    val b64 = bmpToBase64(bmp)
                    Config.setRaw(keyImage, b64, this)
                    Config.setFeatureEnabled(keyUse, true, this)
                    Toast.makeText(this, "图片已设置，回到调色盘点击保存并应用", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "图片解码失败", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "选图失败: ${t.message}", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 仅首次 (非重建) 发起选图; 重建时 launcher 会自动恢复 pending request 回调
        if (savedInstanceState == null) {
            pickLauncher.launch("image/*")
        }
    }

    /** 采样解码, 限制最大边长 [maxEdge], 控制内存与 base64 体积。 */
    private fun decodeSampledBitmap(cr: android.content.ContentResolver, uri: Uri, maxEdge: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            val w = opts.outWidth; val h = opts.outHeight
            if (w <= 0 || h <= 0) return null
            while (w / sample > maxEdge * 2 || h / sample > maxEdge * 2) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) } ?: return null
            val scale = maxEdge.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            else bmp
        } catch (_: Throwable) { null }
    }

    /** Bitmap → 压缩 JPEG → base64。 */
    private fun bmpToBase64(bmp: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 72, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        const val EXTRA_KEY_IMAGE = "key_image"
        const val EXTRA_KEY_USE_IMAGE = "key_use_image"

        /** 便捷启动: 在宿主 Compose 面板里调用。 */
        @JvmStatic
        fun launch(ctx: Context, keyImage: String, keyUseImage: String) {
            val intent = Intent().apply {
                setClassName(Config.PACKAGE_NAME, "com.example.ImagePickerActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_KEY_IMAGE, keyImage)
                putExtra(EXTRA_KEY_USE_IMAGE, keyUseImage)
            }
            ctx.startActivity(intent)
        }
    }
}
