package com.yuyan.inputmethod.util

import android.content.Context
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.inputmethod.core.Kernel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rime 用户数据同步工具（应用层 workaround）
 *
 * 由于 native 库未暴露 RimeSyncUserData() JNI，
 * 通过文件复制实现同步。同步前停止 Rime 引擎，同步后重启。
 *
 * 同步目录: /sdcard/rime/sync/yuyan/
 * 同步内容: user.txt（用户词库）、custom_phrase.txt（自定义短语）
 *
 * 注意: userdb 不同步，Rime 重启时会根据 user.txt 自动重建。
 */
object RimeSyncUtils {

    private const val SYNC_DIR = "/sdcard/rime/sync/yuyan"

    // 同步的文件（userdb 不需要，Rime 重启会从 user.txt 重建）
    private val SYNC_FILES = listOf("user.txt", "custom_phrase.txt")

    /**
     * 一键同步：停止引擎 → 导出 → 导入 → 重启引擎
     */
    fun sync(context: Context): String {
        val rimeDir = File(CustomConstant.RIME_DICT_PATH)
        val syncDir = File(SYNC_DIR)
        if (!rimeDir.exists()) return "❌ Rime 数据目录不存在"
        syncDir.mkdirs()

        val log = StringBuilder()
        var exportCount = 0
        var importCount = 0

        // 停止 Rime 引擎，释放文件锁
        try { Kernel.reset() } catch (_: Exception) {}

        // ===== 导出（本地 → 同步目录）=====
        for (name in SYNC_FILES) {
            val src = File(rimeDir, name)
            if (src.exists() && src.length() > 0) {
                copyFile(src, File(syncDir, name))
                exportCount++
            }
        }
        log.append("⬆️ 导出 $exportCount 项")

        // ===== 导入（同步目录 → 本地，仅本地没有时）=====
        for (name in SYNC_FILES) {
            val src = File(syncDir, name)
            val dst = File(rimeDir, name)
            if (src.exists() && (!dst.exists() || dst.length() == 0L)) {
                copyFile(src, dst)
                importCount++
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
}
