package com.yuyan.inputmethod.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rime 用户数据同步
 * 通过 dlsym 调用 libyuyanime.so 中的 RimeSyncUserData()
 */
object RimeSyncUtils {

    private const val TAG = "RimeSync"
    private var loaded = false

    init {
        try {
            System.loadLibrary("rime_sync")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "librime_sync.so not available: ${e.message}")
        }
    }

    /**
     * 是否可用（librime_sync.so 是否成功加载）。
     */
    @JvmStatic
    fun isAvailable(): Boolean = loaded

    /**
     * 执行一次用户数据同步。
     * 将用户词典导出到 installation.yaml 中 sync_dir 配置的目录。
     * 应在后台线程调用。
     *
     * @return 同步结果描述字符串
     */
    fun sync(): String {
        if (!loaded) return "❌ librime_sync 不可用"
        return try {
            val result = nativeSyncRimeUserData()
            if (result) {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                "✅ 同步完成 $ts"
            } else {
                "❌ 同步失败"
            }
        } catch (e: Exception) {
            Log.e(TAG, "sync failed", e)
            "❌ 同步失败: ${e.message}"
        }
    }

    /**
     * 重新部署 Rime 工作空间。
     * 同步导入新数据后调用，使新词典生效。
     *
     * @return true 如果部署成功执行
     */
    @JvmStatic
    fun deployWorkspace(): Boolean {
        if (!loaded) {
            Log.w(TAG, "deployWorkspace: librime_sync not loaded")
            return false
        }
        return try {
            nativeDeployWorkspace()
        } catch (e: Exception) {
            Log.e(TAG, "deployWorkspace failed", e)
            false
        }
    }

    @JvmStatic
    external fun nativeSyncRimeUserData(): Boolean

    @JvmStatic
    external fun nativeDeployWorkspace(): Boolean
}
