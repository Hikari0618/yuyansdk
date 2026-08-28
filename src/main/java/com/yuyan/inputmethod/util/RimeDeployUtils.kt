package com.yuyan.inputmethod.util

import android.util.Log
import com.yuyan.imemodule.application.CustomConstant
import java.io.File

/**
 * Rime 部署工具
 * 方案文件更新后，删除 build 目录并调用 RimeDeployWorkspace() 重新编译。
 */
object RimeDeployUtils {

    private const val TAG = "RimeDeploy"

    init {
        System.loadLibrary("rime_sync")
    }

    /**
     * 部署：删除 build 目录 → 调用 RimeDeployWorkspace() 重新编译所有方案
     * @return 部署结果描述
     */
    fun deploy(): String {
        return try {
            // 删除 build 目录，强制重新编译
            val buildDir = File(CustomConstant.RIME_DICT_PATH, "build")
            if (buildDir.exists()) {
                val deleted = buildDir.deleteRecursively()
                Log.i(TAG, "build/ deleted: $deleted")
            }

            // 调用 librime 的 RimeDeployWorkspace()
            val result = nativeDeployWorkspace()
            if (result) {
                "✅ 部署完成，方案已重新编译"
            } else {
                "⚠️ RimeDeployWorkspace 返回 false，可能需要重启输入法"
            }
        } catch (e: Exception) {
            Log.e(TAG, "deploy failed", e)
            "❌ 部署失败: ${e.message}"
        }
    }

    @JvmStatic
    external fun nativeDeployWorkspace(): Boolean
}
