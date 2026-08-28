package com.yuyan.inputmethod.util

import android.content.Context
import android.util.Log
import com.yuyan.imemodule.application.CustomConstant
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rime 用户数据同步工具
 *
 * 优先通过 dlsym 调用 libyuyanime.so 中未暴露的 RimeSyncUserData()。
 * 如果 librime_sync.so 未编译/加载失败，则 fallback 到文件复制。
 *
 * 同步目录: /sdcard/rime/sync/yuyan/
 */
object RimeSyncUtils {

    private const val TAG = "RimeSync"
    private const val SYNC_DIR = "/sdcard/rime/sync/yuyan"
    private val SYNC_FILES = listOf("user.txt", "custom_phrase.txt")

    private var nativeAvailable: Boolean? = null

    init {
        try {
            System.loadLibrary("rime_sync")
            nativeAvailable = true
            Log.i(TAG, "librime_sync.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            nativeAvailable = false
            Log.w(TAG, "librime_sync.so not found, will use file copy fallback")
        }
    }

    /**
     * 一键同步：优先 native，fallback 文件复制
     */
    fun sync(context: Context): String {
        // 尝试 native sync
        if (nativeAvailable == true) {
            return try {
                val result = nativeSyncRimeUserData()
                if (result) {
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    "✅ Rime 原生同步完成\n🕐 $ts\n📁 $SYNC_DIR"
                } else {
                    Log.w(TAG, "native sync returned false, falling back")
                    syncByFileCopy(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "native sync failed", e)
                syncByFileCopy(context)
            }
        }
        return syncByFileCopy(context)
    }

    /**
     * 文件复制 fallback
     */
    private fun syncByFileCopy(context: Context): String {
        val rimeDir = File(CustomConstant.RIME_DICT_PATH)
        val syncDir = File(SYNC_DIR)
        if (!rimeDir.exists()) return "❌ Rime 数据目录不存在"
        syncDir.mkdirs()

        val log = StringBuilder("[文件复制模式]\n")
        var exportCount = 0
        var importCount = 0

        // 导出
        for (name in SYNC_FILES) {
            val src = File(rimeDir, name)
            if (src.exists() && src.length() > 0) {
                copyFile(src, File(syncDir, name))
                exportCount++
            }
        }
        log.append("⬆️ 导出 $exportCount 项")

        // 导入（仅本地没有时）
        for (name in SYNC_FILES) {
            val src = File(syncDir, name)
            val dst = File(rimeDir, name)
            if (src.exists() && (!dst.exists() || dst.length() == 0L)) {
                copyFile(src, dst)
                importCount++
            }
        }
        if (importCount > 0) log.append(" ⬇️ 导入 $importCount 项")

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

    /**
     * 调用 librime 的 RimeSyncUserData()，通过 dlsym 从 libyuyanime.so 中查找
     * 需要编译 librime_sync.so（见 src/main/jni/rime_sync_jni.c）
     */
    @JvmStatic
    external fun nativeSyncRimeUserData(): Boolean
}
