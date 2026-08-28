package com.yuyan.inputmethod.util

import android.content.Context
import com.yuyan.imemodule.application.CustomConstant
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rime 用户数据同步工具（应用层 workaround）
 * 模拟 Rime 原生 sync 行为：将用户数据同步到固定目录，
 * 可配合 Syncthing/网盘等工具实现多设备同步。
 *
 * 同步目录: /sdcard/rime/sync/yuyan/
 * 同步内容: user.txt、*.userdb、custom_phrase.txt
 */
object RimeSyncUtils {

    private const val SYNC_DIR = "/sdcard/rime/sync/yuyan"

    // 需要同步的文件
    private val SYNC_FILES = listOf("user.txt", "user.kct", "custom_phrase.txt")
    // 需要同步的目录后缀
    private val SYNC_DIR_SUFFIXES = listOf(".userdb")

    /**
     * 一键同步：先导出本地数据，再导入远端数据
     * @return 同步结果描述
     */
    fun sync(context: Context): String {
        val rimeDir = File(CustomConstant.RIME_DICT_PATH)
        val syncDir = File(SYNC_DIR)
        if (!rimeDir.exists()) return "❌ Rime 数据目录不存在"
        syncDir.mkdirs()

        val log = StringBuilder()
        var exportCount = 0
        var importCount = 0

        // ===== 第一步：导出（本地 → 同步目录）=====
        // 同步文件
        for (name in SYNC_FILES) {
            val src = File(rimeDir, name)
            if (src.exists()) {
                copyFile(src, File(syncDir, name))
                exportCount++
            }
        }
        // 同步目录（*.userdb）
        rimeDir.listFiles()?.forEach { f ->
            if (f.isDirectory && SYNC_DIR_SUFFIXES.any { f.name.endsWith(it) }) {
                copyDir(f, File(syncDir, f.name))
                exportCount++
            }
        }
        log.append("⬆️ 导出 $exportCount 项")

        // ===== 第二步：导入（同步目录 → 本地）=====
        // 只导入本地不存在的文件（避免覆盖本地更新的数据）
        for (name in SYNC_FILES) {
            val src = File(syncDir, name)
            val dst = File(rimeDir, name)
            if (src.exists() && !dst.exists()) {
                copyFile(src, dst)
                importCount++
            }
        }
        syncDir.listFiles()?.forEach { f ->
            if (f.isDirectory && SYNC_DIR_SUFFIXES.any { f.name.endsWith(it) }) {
                val dst = File(rimeDir, f.name)
                if (!dst.exists()) {
                    copyDir(f, dst)
                    importCount++
                }
            }
        }
        if (importCount > 0) log.append(" ⬇️ 导入 $importCount 项")

        // 记录时间戳
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        File(syncDir, ".last_sync").writeText(ts)
        log.append("\n🕐 $ts")
        log.append("\n📁 $SYNC_DIR")

        return log.toString()
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
    }

    private fun copyDir(src: File, dst: File) {
        dst.mkdirs()
        src.listFiles()?.forEach { f ->
            if (f.isDirectory) copyDir(f, File(dst, f.name))
            else copyFile(f, File(dst, f.name))
        }
    }
}
