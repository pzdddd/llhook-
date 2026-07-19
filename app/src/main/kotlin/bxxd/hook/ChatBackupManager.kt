package bxxd.hook

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*

/**
 * ============================================================================
 *  Blued 聊天数据 备份 / 恢复 管理
 * ============================================================================
 *
 *  【设计理念 —— 文件级冷备份 (SQLite 官方推荐做法)】
 *  Blued 的聊天记录 / 会话 / 联系人等核心数据, 全部存在它的私有数据库目录:
 *      /data/data/com.soft.blued/databases/
 *  本模块运行在 com.soft.blued 进程内, 拥有该目录的完全读写权限。
 *
 *  不去逆向具体表结构 (dex 加密、且不同版本表名会变), 而是采用【文件级冷备份】:
 *    - 拷贝 databases 目录下所有 .db 主库 + .db-wal/.db-shm/.db-journal 附属文件;
 *    - WAL 模式下, SQLite 用「主库 + -wal + -shm」三个文件【共同】表示当前完整状态,
 *      三个文件一起拷贝 = 数据完整, 这是 SQLite 官方文档推荐的非在线备份方式。
 *    - 全部打包成一个 ZIP, 方便管理和迁移。
 *  优点: 无需逆向、跨版本兼容、恢复时无表结构不匹配问题、零锁冲突。
 *
 *  【备份目录】
 *  优先级: 用户自定义目录 > 默认 Download/bluedbackups > app 专属外部存储(兜底)。
 *  任何一级目录不存在都会自动创建 (mkdirs)。
 *
 *  【恢复安全网】
 *  恢复前若检测到当前有数据库, 会先自动做一次当前状态备份 (pre_restore_xxx.zip),
 *  避免误恢复导致数据丢失。恢复后需重启 Blued 让其重新加载新数据库。
 * ============================================================================
 */
object ChatBackupManager {

    private const val DEFAULT_DIR_NAME = "bluedbackups"

    /** 备份操作结果 */
    data class BackupResult(val success: Boolean, val msg: String, val file: File?)

    /** 数据库文件后缀 (主库 + WAL 三件套 + 旧式 journal) */
    private val DB_SUFFIXES = arrayOf(".db", ".db-wal", ".db-shm", ".db-journal")

    /** 备份包内元信息文件名 (不会被判为 db 文件, restore 时自动跳过) */
    private const val META_FILE_NAME = "llhook_meta.json"

    /** 主库名 (两版相同, 用于读取 SQLite header 里的 user_version) */
    private const val MAIN_DB = "blued2015.db"

    /**
     * 备份元信息: 记录来源, 用于跨版本兼容性校验。
     * (普通版 com.soft.blued 的 DB_VERSION=40065, 极速版 com.danlan.xiaolan=40067)
     */
    data class BackupMeta(
        val sourcePackage: String?,   // 正式版 com.soft.blued / 极速版 com.danlan.xiaolan
        val sourceAppVersion: String?,
        val sourceUid: String?,
        val dbUserVersion: Long,
        val backupTime: String
    )

    /** 兼容性等级 */
    enum class CompatLevel { SAFE, WARN, DANGER }

    /** 兼容性校验结果 */
    data class CompatibilityResult(val level: CompatLevel, val message: String)

    /**
     * 校验「把 sourcePkg 来源的备份 恢复到 currentPkg 宿主」是否安全。
     *
     * 原理: Blued 数据库走 ORMLite, onUpgrade 是幂等的 ALTER TABLE ADD COLUMN (向上兼容),
     * 但【onDowngrade 未重写】。SQLite 在 db.user_version > app 期望版本 时触发 onDowngrade,
     * 默认抛 SQLiteException 导致启动崩溃。
     *
     *  - 同版互恢复: 安全
     *  - 普通版 → 极速版: 安全 (极速版 onUpgrade 补齐缺失列)
     *  - 极速版 → 普通版: 危险 (降级崩溃)
     */
    fun checkCompatibility(sourcePkg: String?, currentPkg: String?): CompatibilityResult {
        val src = sourcePkg
        val cur = currentPkg
        if (src.isNullOrBlank())
            return CompatibilityResult(CompatLevel.WARN, "旧版备份无来源标记, 兼容性未知, 恢复需谨慎。")
        if (cur.isNullOrBlank())
            return CompatibilityResult(CompatLevel.WARN, "无法确定当前宿主版本。")
        if (src == cur)
            return CompatibilityResult(CompatLevel.SAFE, "同版本备份, 完全兼容。")
        if (src == "com.soft.blued" && cur == "com.danlan.xiaolan")
            return CompatibilityResult(CompatLevel.SAFE,
                "普通版 → 极速版: 极速版将自动升级数据库 (onUpgrade), 兼容。")
        if (src == "com.danlan.xiaolan" && cur == "com.soft.blued")
            return CompatibilityResult(CompatLevel.DANGER,
                "⚠️ 极速版 → 普通版: 极速版数据库版本更高 (40067 > 40065),\n" +
                "普通版未实现降级逻辑 (onDowngrade), 恢复后会导致 Blued 启动崩溃!\n" +
                "建议改在极速版内恢复此备份。")
        return CompatibilityResult(CompatLevel.WARN,
            "来源($src) 与 当前($cur) 不同, 跨版本恢复需谨慎。")
    }

    /**
     * 备份根目录: 自定义优先, 否则默认 Download/bluedbackups, 再不行回退 app 专属目录。
     * 不存在自动创建。返回值一定可写 (最坏走 app 专属目录, 无需任何权限)。
     */
    fun getBackupRoot(context: Context): File {
        // 1) 用户自定义目录
        val custom = Config.getBackupDir(context).trim()
        if (custom.isNotEmpty()) {
            val f = File(custom)
            if (ensureDir(f)) return f
        }
        // 2) 默认: 公共 Download/bluedbackups (用户最容易找到)
        runCatching {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val def = File(dl, DEFAULT_DIR_NAME)
            if (ensureDir(def)) return def
        }
        // 3) 兜底: app 专属外部存储 (一定可写, 无需权限)
        val fb = File(context.getExternalFilesDir(null), DEFAULT_DIR_NAME)
        ensureDir(fb)
        return fb
    }

    /** 确保目录存在且可写; 不可写返回 false */
    private fun ensureDir(f: File): Boolean = try {
        if (!f.exists()) f.mkdirs()
        f.exists() && f.canWrite()
    } catch (t: Throwable) { false }

    /** Blued 数据库目录: /data/data/<宿主包名>/databases (正式版 com.soft.blued / 极速版 com.danlan.xiaolan) */
    private fun getDbDir(context: Context): File =
            File(context.applicationInfo.dataDir, "databases")

    /** 判断一个文件名是否为数据库相关文件 */
    private fun isDbFile(name: String): Boolean = DB_SUFFIXES.any { name.endsWith(it) }

    /**
     * 立即备份: 把 databases 下所有数据库文件打包成 ZIP。
     * 单个文件失败会跳过、其余继续, 保证整体不崩。
     */
    fun backup(context: Context): BackupResult {
        return try {
        val dbDir = getDbDir(context)
        if (!dbDir.exists() || !dbDir.isDirectory) {
            return BackupResult(false, "数据库目录不存在", null)
        }
        val files = dbDir.listFiles { f -> f.isFile && isDbFile(f.name) }
                ?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) return BackupResult(false, "未找到任何数据库文件", null)

        val root = getBackupRoot(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val zipFile = File(root, "blued_backup_$ts.zip")

        var packed = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            files.forEach { f ->
                try {
                    zos.putNextEntry(ZipEntry(f.name))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                    packed++
                } catch (t: Throwable) { /* 单文件失败跳过, 其余继续 */ }
            }
            // 写入元信息 (记录来源宿主/版本, 用于跨版本兼容性校验)
            try {
                val meta = JSONObject().apply {
                    put("source_package", context.packageName)
                    put("source_app_version", runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "")
                    put("source_uid", readLoginUid(context) ?: "")
                    put("db_user_version", readUserVersion(File(dbDir, MAIN_DB)))
                    put("backup_time", ts)
                    put("schema", 1)
                }
                zos.putNextEntry(ZipEntry(META_FILE_NAME))
                zos.write(meta.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            } catch (t: Throwable) { /* meta 写入失败不影响主备份 */ }
        }
        if (packed == 0) {
            zipFile.delete()
            BackupResult(false, "打包失败, 未写入任何文件", null)
        } else {
            BackupResult(true, "成功备份 $packed 个文件 → ${root.absolutePath}", zipFile)
        }
    } catch (t: Throwable) {
        BackupResult(false, "备份失败: ${t.message}", null)
    }
    }

    /**
     * 恢复: 把 ZIP 解压回 databases 目录。
     * 覆盖前先删除同名文件 (避免残留 -wal 导致数据状态混乱)。
     * @return 成功恢复的文件数, -1 表示失败
     */
    fun restore(context: Context, zipFile: File): Int = try {
        val dbDir = getDbDir(context)
        if (!dbDir.exists()) dbDir.mkdirs()
        var count = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    if (isDbFile(name)) {
                        // 取 basename, 防止路径穿越攻击 (../etc)
                        val out = File(dbDir, File(name).name)
                        if (out.exists()) out.delete()
                        BufferedOutputStream(FileOutputStream(out)).use { zis.copyTo(it) }
                        count++
                    }
                }
                entry = zis.nextEntry
            }
        }
        count
    } catch (t: Throwable) { -1 }

    /** 列出备份 ZIP (按修改时间倒序, 最新在最前) */
    fun listBackups(context: Context): List<File> {
        val root = getBackupRoot(context)
        return root.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 删除一个备份文件 */
    fun deleteBackup(zipFile: File): Boolean = try { zipFile.delete() } catch (t: Throwable) { false }

    /** 读取备份包里的元信息 (无则返回 null, 旧版备份兼容) */
    fun readMeta(zip: File): BackupMeta? {
        return try {
            ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == META_FILE_NAME) {
                        val j = JSONObject(zis.bufferedReader().readText())
                        return@use BackupMeta(
                            sourcePackage = j.optString("source_package").takeIf { it.isNotBlank() },
                            sourceAppVersion = j.optString("source_app_version").takeIf { it.isNotBlank() },
                            sourceUid = j.optString("source_uid").takeIf { it.isNotBlank() },
                            dbUserVersion = j.optLong("db_user_version"),
                            backupTime = j.optString("backup_time")
                        )
                    }
                    entry = zis.nextEntry
                }
                null
            }
        } catch (t: Throwable) { null }
    }

    /**
     * 读取 SQLite 数据库 user_version (不依赖锁, 直接读文件头)。
     * SQLite 格式: offset 96 处 4 字节大端整数 = user_version。
     */
    private fun readUserVersion(dbFile: File): Long = try {
        java.io.RandomAccessFile(dbFile, "r").use { raf ->
            raf.seek(96)
            ((raf.readByte().toLong() and 0xFF) shl 24) or
            ((raf.readByte().toLong() and 0xFF) shl 16) or
            ((raf.readByte().toLong() and 0xFF) shl 8) or
            (raf.readByte().toLong() and 0xFF)
        }
    } catch (t: Throwable) { 0 }

    /** best-effort 反射读取当前登录 uid (用于标记备份归属账号, 失败留空) */
    private fun readLoginUid(context: Context): String? = try {
        val userInfo = Class.forName("com.blued.android.module.common.user.model.UserInfo")
            .getMethod("getInstance").invoke(null)
        val user = userInfo?.javaClass?.getMethod("getLoginUserInfo")?.invoke(userInfo)
        user?.javaClass?.getMethod("getUid")?.invoke(user) as? String
    } catch (t: Throwable) { null }

    /** 友好的文件大小显示 */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1fKB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1fMB", bytes / 1024.0 / 1024)
        else -> String.format(Locale.getDefault(), "%.2fGB", bytes / 1024.0 / 1024 / 1024)
    }
}
