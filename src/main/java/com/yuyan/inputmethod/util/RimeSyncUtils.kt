package com.yuyan.inputmethod.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rime 用户数据同步
 * 通过 dlsym 调用 libyuyanime.so 中的 RimeSyncUserData()
 * 编译产物: librime_sync.so (由 GitHub Actions 自动编译)
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

    @JvmStatic
    external fun nativeSyncRimeUserData(): Boolean
}
