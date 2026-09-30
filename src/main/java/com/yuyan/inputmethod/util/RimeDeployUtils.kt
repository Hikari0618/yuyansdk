package com.yuyan.inputmethod.util

import android.util.Log
import com.yuyan.imemodule.application.Launcher
import com.yuyan.inputmethod.core.Rime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rime 部署工具：导入 /sdcard/yuyan 方案 → 生成 default.yaml → 重新编译部署。
 *
 * 部署全过程写入详细日志（/sdcard/yuyan/last_deploy.log，无权限时写应用内部目录），
 * 失败时把日志路径告知用户，便于排查。
 *
 * 注意：不要删除 build/ 目录！其中的 *.table.bin 是预编译词典，
 * 在词典源码缺失时引擎会复用它们（内置方案词库即以此方式提供）。
 */
object RimeDeployUtils {

    private const val TAG = "RimeDeploy"

    private val logLines = StringBuilder()
    private fun log(line: String) {
        logLines.append(line).append("\n")
        Log.i(TAG, line)
    }

    /**
     * 重新部署。返回用户可读的结果文本（含详细日志路径）。
     */
    fun deploy(): String {
        val started = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logLines.clear()
        log("====== 语燕 Rime 部署日志 $started ======")
        return try {
            val context = Launcher.instance.context
            log("应用版本: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")

            // 0. 权限与 sdcard 目录状态
            val hasPermission = RimeWorkspace.hasStoragePermission(context)
            log("文件访问权限: ${if (hasPermission) "已授予" else "未授予"}")
            val sdDir = File(RimeWorkspace.SD_RIME_DIR)
            log("${RimeWorkspace.SD_RIME_DIR} 目录存在: ${sdDir.isDirectory}")
            if (sdDir.isDirectory) {
                val names = sdDir.list()?.take(30) ?: emptyList()
                log("${RimeWorkspace.SD_RIME_DIR} 顶层内容: ${names.joinToString(", ")}")
            }

            // 1. 导入 /sdcard/yuyan 下的方案文件（如已授权）
            val summary = StringBuilder()
            if (hasPermission) {
                val importResult = RimeWorkspace.importFromSdcard()
                summary.append(importResult).append("\n")
                log("导入结果: $importResult")
            } else {
                summary.append("⚠ 未授予文件访问权限，跳过 ${RimeWorkspace.SD_RIME_DIR} 导入\n")
                log("跳过导入（无权限）")
            }

            // 2. 生成/刷新 default.yaml（schema_list 始终合并全部方案，防止失效列表导致打不出字）
            val schemas = RimeWorkspace.allSchemas()
            log("扫描到 ${schemas.size} 个方案:")
            schemas.forEach { (id, name) -> log("  - $id ($name)") }
            val wrote = RimeWorkspace.writeDefaultYamlIfNeeded()
            log("default.yaml schema_list ${if (wrote) "已刷新" else "无需变更"}")

            // 3. 工作区 build/ 产物盘点（预编译词典是打字的关键）
            val buildDir = File(RimeWorkspace.userDir, "build")
            val buildFiles = buildDir.listFiles()?.sortedBy { it.name } ?: emptyList()
            log("build/ 产物 ${buildFiles.size} 个:")
            buildFiles.forEach { log("  - ${it.name} (${it.length()} bytes)") }

            // 4. 停止引擎并以完整检查模式重启，触发方案编译
            log("重启引擎（full_check=true，开始编译方案）…")
            Rime.destroy()
            Rime.startup(context, true)
            log("引擎重启完成")

            // 5. 引擎状态诊断
            try {
                val schema = Rime.getCurrentRimeSchema()
                log("当前方案: ${schema.ifEmpty { "(空)" }}")
            } catch (e: Exception) {
                log("获取当前方案失败: $e")
            }
            val userDirFiles = RimeWorkspace.userDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            log("用户目录内容: ${userDirFiles.joinToString(", ")}")

            log("====== 部署完成 ======")
            summary.append("✅ 部署完成，共 ").append(schemas.size).append(" 个方案")
            appendEngineLogTail()
            val logFile = writeLogFile(context)
            if (logFile != null) {
                summary.append("\n📄 详细日志: ").append(logFile.absolutePath)
            }
            summary.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Deploy failed", e)
            log("❌ 部署异常: $e")
            e.stackTrace.take(15).forEach { log("    at $it") }
            log("====== 部署失败 ======")
            val logFile = runCatching { writeLogFile(Launcher.instance.context) }.getOrNull()
            "❌ 部署失败: $e\n📄 详细日志: ${logFile?.absolutePath ?: "(写入失败)"}"
        }
    }

    /** 把 librime 引擎日志（/sdcard/yuyan/logs 最新一个 glog 文件）的尾部并入部署日志——
     *  部署失败的真实原因（config 构建失败、schema list not defined 等）只在这里 */
    private fun appendEngineLogTail() {
        try {
            val logsDir = File(RimeWorkspace.SD_RIME_DIR, "logs")
            val latest = logsDir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
            if (latest == null) {
                log("（无引擎日志文件 /sdcard/yuyan/logs）")
                return
            }
            log("---- 引擎日志尾部 (${latest.name}) ----")
            val lines = latest.readLines()
            lines.takeLast(60).forEach { log("  $it") }
            val errors = lines.count { it.contains(" E") || it.contains("[ERROR]") }
            log("---- 引擎日志统计: ${lines.size} 行, $errors 条错误 ----")
        } catch (e: Exception) {
            log("读取引擎日志失败: $e")
        }
    }

    /** 写日志文件：优先 sdcard（方便用户取），无权限则写应用内部 */
    private fun writeLogFile(context: android.content.Context): File? {
        val candidates = mutableListOf<File>()
        if (RimeWorkspace.hasStoragePermission(context)) {
            candidates.add(File(RimeWorkspace.SD_RIME_DIR, "last_deploy.log"))
        }
        candidates.add(File(context.filesDir, "last_deploy.log"))
        for (f in candidates) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(logLines.toString())
                return f
            } catch (_: Exception) {
            }
        }
        return null
    }
}
