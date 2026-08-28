package com.yuyan.inputmethod.util

import android.util.Log
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.inputmethod.core.Kernel
import com.yuyan.inputmethod.core.Rime

/**
 * Rime 部署工具
 * 方案文件更新后，触发 Rime 重新部署（编译方案文件）。
 * 
 * 原理：exitRime() + startupRime(fullCheck=true)
 * 与同文输入法 deploy() 逻辑一致。
 */
object RimeDeployUtils {

    private const val TAG = "RimeDeploy"

    /**
     * 重新部署：停止引擎 → 以 fullCheck=true 重新启动
     * Rime 会检测方案文件变更并重新编译
     */
    fun deploy(): String {
        return try {
            Log.i(TAG, "Deploy starting...")
            // 停止 Rime 引擎
            Rime.destroy()
            // 以 fullCheck=true 重新启动，触发部署（重新编译方案）
            Rime.startup(Launcher.instance.context, true)
            Log.i(TAG, "Deploy done")
            "✅ 部署完成"
        } catch (e: Exception) {
            Log.e(TAG, "Deploy failed", e)
            "❌ 部署失败: ${e.message}"
        }
    }
}
