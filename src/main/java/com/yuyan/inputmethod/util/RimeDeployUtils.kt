package com.yuyan.inputmethod.util

import android.util.Log
import com.yuyan.imemodule.application.Launcher
import com.yuyan.inputmethod.core.Rime
import java.io.File

/**
 * Rime 部署工具：导入 /sdcard/rime 方案 → 生成 default.yaml → 重新编译部署。
 *
 * 注意：不要删除 build/ 目录！其中的 *.table.bin 是预编译词典，
 * 在词典源码缺失时引擎会复用它们（内置方案词库即以此方式提供）。
 */
object RimeDeployUtils {

    private const val TAG = "RimeDeploy"

    /**
     * 重新部署。返回用户可读的结果文本。
     */
    fun deploy(): String {
        return try {
            Log.i(TAG, "Deploy starting...")
            val summary = StringBuilder()

            // 1. 导入 /sdcard/rime 下的方案文件（如已授权）
            val userProvidedDefault: Boolean
            if (RimeWorkspace.hasStoragePermission(Launcher.instance.context)) {
                summary.append(RimeWorkspace.importFromSdcard()).append("\n")
                userProvidedDefault = File(RimeWorkspace.SD_RIME_DIR, "default.yaml").exists()
            } else {
                summary.append("未授予文件访问权限，跳过 /sdcard/rime 导入\n")
                userProvidedDefault = false
            }

            // 2. 生成/刷新 default.yaml（schema_list 包含全部方案）
            val schemas = RimeWorkspace.allSchemas()
            RimeWorkspace.writeDefaultYamlIfNeeded(userProvidedDefault)

            // 3. 停止引擎并以完整检查模式重启，触发方案编译
            Rime.destroy()
            Rime.startup(Launcher.instance.context, true)

            summary.append("✅ 部署完成，共 ").append(schemas.size).append(" 个方案")
            Log.i(TAG, "Deploy done")
            summary.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Deploy failed", e)
            "❌ 部署失败: ${e.message}"
        }
    }
}
