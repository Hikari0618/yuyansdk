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
                "❌ 同步失败"
            }
        } catch (e: Exception) {
            Log.e(TAG, "sync failed", e)
            "❌ 同步失败: ${e.message}"
        }
    }

    @JvmStatic
    external fun nativeSyncRimeUserData(): Boolean
}
