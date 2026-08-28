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
 * Rime 用户数据同步工具
 * 由于 native 库未暴露 RimeSyncUserData() JNI 方法，
 * 通过文件复制方式实现用户数据的导出/导入。
 *
 * 同步目录: /sdcard/rime_sync/yuyan/
 * 同步内容: 用户词库(user.txt)、用户数据库(*.userdb)、自定义短语
 */
object RimeSyncUtils {

    // 同步目标目录
    private const val SYNC_BASE_DIR = "/sdcard/rime_sync"
    private const val SYNC_INSTALLATION_ID = "yuyan"

    // 需要同步的文件/目录模式
    private val SYNC_PATTERNS = listOf(
        "user.txt",           // 用户自造词
        "user.kct",           // 用户词典 (旧版)
    )
    // 需要同步的目录后缀
    private val SYNC_DIR_SUFFIXES = listOf(
        ".userdb",            // 用户数据库目录
    )
    // 需要同步的自定义文件
    private val SYNC_CUSTOM_FILES = listOf(
        "custom_phrase.txt",  // 自定义短语
    )
    // 不同步的文件/目录
    private val EXCLUDE_PATTERNS = listOf(
        "build/",             // 编译缓存
        "opencc/",            // OpenCC 数据(只读)
        "installation.yaml",  // 设备安装信息
        ".prism.bin",
        ".table.bin",
        ".gram",
    )

    private fun getSyncDir(): File {
        return File(SYNC_BASE_DIR, SYNC_INSTALLATION_ID)
    }

    private fun getRimeDir(context: Context): File {
        return File(CustomConstant.RIME_DICT_PATH)
    }

    /**
     * 导出用户数据到同步目录
     * @return 导出结果描述
     */
    fun exportUserData(context: Context): String {
        val rimeDir = getRimeDir(context)
        val syncDir = getSyncDir()

        if (!rimeDir.exists()) {
            return "❌ Rime 数据目录不存在: ${rimeDir.absolutePath}"
        }

        syncDir.mkdirs()

        var copiedCount = 0
        val errors = mutableListOf<String>()

        // 1. 同步用户词库文件
        for (pattern in SYNC_PATTERNS) {
            val src = File(rimeDir, pattern)
            if (src.exists()) {
                try {
                    copyFile(src, File(syncDir, pattern))
                    copiedCount++
                } catch (e: Exception) {
                    errors.add("$pattern: ${e.message}")
                }
            }
        }

        // 2. 同步用户数据库目录
        rimeDir.listFiles()?.forEach { file ->
            if (file.isDirectory && SYNC_DIR_SUFFIXES.any { file.name.endsWith(it) }) {
                try {
                    copyDirectory(file, File(syncDir, file.name))
                    copiedCount++
                } catch (e: Exception) {
                    errors.add("${file.name}: ${e.message}")
                }
            }
        }

        // 3. 同步自定义文件
        for (name in SYNC_CUSTOM_FILES) {
            val src = File(rimeDir, name)
            if (src.exists()) {
                try {
                    copyFile(src, File(syncDir, name))
                    copiedCount++
                } catch (e: Exception) {
                    errors.add("$name: ${e.message}")
                }
            }
        }

        // 4. 写入同步时间戳
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        File(syncDir, ".sync_timestamp").writeText(timestamp)

        val result = StringBuilder()
        result.append("✅ 导出完成: $copiedCount 项")
        if (errors.isNotEmpty()) {
            result.append("\n⚠️ 部分失败:\n${errors.joinToString("\n")}")
        }
        result.append("\n📁 同步目录: ${syncDir.absolutePath}")
        return result.toString()
    }

    /**
     * 从同步目录导入用户数据
     * @return 导入结果描述
     */
    fun importUserData(context: Context): String {
        val rimeDir = getRimeDir(context)
        val syncDir = getSyncDir()

        if (!syncDir.exists()) {
            return "❌ 同步目录不存在: ${syncDir.absolutePath}\n请先从其他设备导出数据到此目录。"
        }

        var restoredCount = 0
        val errors = mutableListOf<String>()

        // 1. 恢复用户词库文件
        for (pattern in SYNC_PATTERNS) {
            val src = File(syncDir, pattern)
            if (src.exists()) {
                try {
                    copyFile(src, File(rimeDir, pattern))
                    restoredCount++
                } catch (e: Exception) {
                    errors.add("$pattern: ${e.message}")
                }
            }
        }

        // 2. 恢复用户数据库目录
        syncDir.listFiles()?.forEach { file ->
            if (file.isDirectory && SYNC_DIR_SUFFIXES.any { file.name.endsWith(it) }) {
                try {
                    copyDirectory(file, File(rimeDir, file.name))
                    restoredCount++
                } catch (e: Exception) {
                    errors.add("${file.name}: ${e.message}")
                }
            }
        }

        // 3. 恢复自定义文件（不覆盖已有内容，合并）
        for (name in SYNC_CUSTOM_FILES) {
            val src = File(syncDir, name)
            if (src.exists()) {
                val dst = File(rimeDir, name)
                if (!dst.exists()) {
                    try {
                        copyFile(src, dst)
                        restoredCount++
                    } catch (e: Exception) {
                        errors.add("$name: ${e.message}")
                    }
                }
                // 如果已存在，不覆盖（避免丢失本地自定义内容）
            }
        }

        val result = StringBuilder()
        result.append("✅ 导入完成: $restoredCount 项")
        if (errors.isNotEmpty()) {
            result.append("\n⚠️ 部分失败:\n${errors.joinToString("\n")}")
        }

        // 读取同步时间戳
        val tsFile = File(syncDir, ".sync_timestamp")
        if (tsFile.exists()) {
            result.append("\n📅 数据时间: ${tsFile.readText()}")
        }

        return result.toString()
    }

    /**
     * 获取同步状态信息
     */
    fun getSyncStatus(context: Context): String {
        val rimeDir = getRimeDir(context)
        val syncDir = getSyncDir()

        val result = StringBuilder()
        result.appendLine("📁 Rime 数据目录: ${rimeDir.absolutePath}")
        result.appendLine("📁 同步目录: ${syncDir.absolutePath}")
        result.appendLine("🔑 安装ID: $SYNC_INSTALLATION_ID")

        // 检查同步目录状态
        if (syncDir.exists()) {
            val tsFile = File(syncDir, ".sync_timestamp")
            if (tsFile.exists()) {
                result.appendLine("📅 上次同步: ${tsFile.readText()}")
            }
            val fileCount = syncDir.listFiles()?.size ?: 0
            result.appendLine("📦 同步文件数: $fileCount")
        } else {
            result.appendLine("⚠️ 同步目录不存在")
        }

        // 检查用户数据
        val userTxt = File(rimeDir, "user.txt")
        result.appendLine("📝 用户词库: ${if (userTxt.exists()) "✓" else "✗"}")

        rimeDir.listFiles()?.filter { it.isDirectory && it.name.endsWith(".userdb") }?.forEach {
            result.appendLine("📚 ${it.name}: ✓")
        }

        return result.toString()
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun copyDirectory(src: File, dst: File) {
        dst.mkdirs()
        src.listFiles()?.forEach { file ->
            val target = File(dst, file.name)
            if (file.isDirectory) {
                copyDirectory(file, target)
            } else {
                copyFile(file, target)
            }
        }
    }
}
