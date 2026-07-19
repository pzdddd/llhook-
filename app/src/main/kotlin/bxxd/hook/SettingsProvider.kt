package bxxd.hook

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File

class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        // 使用 MODE_PRIVATE，跨进程读取通过文件权限提权保证
        val prefs = context?.getSharedPreferences("llhook_settings", Context.MODE_PRIVATE) ?: return null
        val key = uri.lastPathSegment ?: return null
        val cursor = MatrixCursor(arrayOf("value"))
        
        val value = prefs.all[key]
        cursor.addRow(arrayOf(value?.toString() ?: ""))
        return cursor
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val ctx = context ?: return 0
        val prefs = ctx.getSharedPreferences("llhook_settings", Context.MODE_PRIVATE)
        val key = uri.lastPathSegment ?: return 0
        val valueStr = values?.getAsString("value") ?: return 0
        
        val editor = prefs.edit()
        when (valueStr) {
            "true" -> editor.putBoolean(key, true)
            "false" -> editor.putBoolean(key, false)
            else -> editor.putString(key, valueStr)
        }
        editor.commit() // 必须同步写入，不能用 apply
        
        // 附加传统权限提权作为双重保险
        try {
            val dataDir = File(ctx.applicationInfo.dataDir)
            dataDir.setExecutable(true, false)
            dataDir.setReadable(true, false)
            val prefsDir = File(dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                prefsDir.setExecutable(true, false)
                prefsDir.setReadable(true, false)
            }
            val prefsFile = File(prefsDir, "llhook_settings.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
                prefsFile.setExecutable(true, false)
            }
        } catch (e: Throwable) {}
        
        return 1
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}