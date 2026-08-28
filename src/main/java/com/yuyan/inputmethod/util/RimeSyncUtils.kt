package com.yuyan.inputmethod.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rime 用户数据同步
 * 通过 dlsym 调用 libyuyanime.so 中的 RimeSyncUserData()
 * sync_dir 使用 app 私有目录（不需要存储权限）
 */
object RimeSyncUtils {

    private const val TAG = "RimeSync"

    init {
        System.loadLibrary("rime_sync")
    }

    fun sync(): String {
        return try {
            val result = nativeSyncRimeUserData()
            if (result) {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                "✅ 同步完成 $ts"
            } else {
                "❌ 同步失败: RimeSyncUserData 返回 false"
            }
        } catch (e: Exception) {
            Log.e(TAG, "sync failed", e)
            "❌ 同步失败: ${e.message}"
        }
    }

    /**
     * 更新 installation.yaml 中的 sync_dir 为 app 私有目录
     * 在 Launcher 初始化时调用，确保 Rime 引擎能正确找到同步目录
     */
    fun updateSyncDir(rimeDir: String) {
        try {
            val installYaml = java.io.File(rimeDir, "installation.yaml")
            if (!installYaml.exists()) return
            val content = installYaml.readText()
            val syncDir = "$rimeDir/sync"
            // 确保 sync 目录存在
            java.io.File(syncDir).mkdirs()
            // 替换 sync_dir 行
            val updated = content.replace(
                Regex("""sync_dir:\s*".*?""""),
                """sync_dir: "$syncDir""""
            )
            if (updated != content) {
                installYaml.writeText(updated)
                Log.i(TAG, "installation.yaml sync_dir updated to $syncDir")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update sync_dir", e)
        }
    }

    @JvmStatic
    external fun nativeSyncRimeUserData(): Boolean
}
