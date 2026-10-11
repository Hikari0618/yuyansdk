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

    /** 部署前要求的最小可用存储（MB）。低于此值直接拒绝部署，绝不开始。 */
    private const val MIN_FREE_SPACE_MB = 2048L

    /** 看门狗触发阈值（MB）：部署期间可用存储跌破此值立刻结束进程。 */
    private const val WATCHDOG_ABORT_MB = 512L

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

            // ★ 部署前存储检查（硬保险）。
            // 曾出现一次「点部署后设备存储被写满、用户被迫清除应用数据」的事故：
            // 部署会跑 full_check 维护（重建用户词典等），一旦那条路径失控，会以极快速度
            // 写满设备。这里在开跑前就拦一道：可用空间不足直接拒绝，绝不开始。
            val freeMb = try {
                val stat = android.os.StatFs(RimeWorkspace.userDir.absolutePath)
                stat.availableBytes / 1024 / 1024
            } catch (e: Exception) {
                -1L
            }
            log("可用存储: ${if (freeMb < 0) "未知" else "${freeMb}MB"}")
            if (freeMb in 0 until MIN_FREE_SPACE_MB) {
                val msg = "可用存储不足（${freeMb}MB < ${MIN_FREE_SPACE_MB}MB），已中止部署。请先清理存储再试。"
                log("⚠ $msg")
                writeLogFile(context)
                return msg
            }

            // ★ 部署期间的空间看门狗。
            // 上面的检查只能拦住「一开始就不够」的情况；如果某条路径在中途失控增长，
            // 仍然会在几秒内写满设备。看门狗每秒采样一次可用空间，一旦跌破下限就
            // 立刻结束进程 —— 宁可中断部署，也不能把设备写满。
            startSpaceWatchdog(context, freeMb)

            // 0. 需要时先把 assets 的 rime 资源复制到用户目录。
            // 放在部署里而不是只挂在应用启动上：部署是用户主动触发的，时机可控、
            // 结果写进本日志，不会再出现「改动没下发却查不出来」的情况。
            // 带版本号闸门，版本没变则跳过（不会每次部署都搬一遍几十 MB 的词库表）。
            if (RimeWorkspace.copyAssetsIfNeeded(context)) {
                log("assets/rime 已复制到用户目录（词库版本变化）")
            } else {
                log("assets/rime 无需复制（词库版本未变）")
            }

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
            // 关键一步：default.custom.yaml 里的 patch: schema_list: 是【替换】语义，
            // 会把上面写进 default.yaml 的清单整段覆盖掉（实测引擎日志只有 patching schema_list、
            // 且没有任何 pinyin 编译记录）。必须再往 default.custom.yaml 追加 schema_list/+。
            val patched = RimeWorkspace.ensureBuiltinSchemasInCustomPatch()
            log("default.custom.yaml 内置方案追加 ${if (patched) "已写入" else "已存在"}")
            // 诊断：把 default.custom.yaml 里与 schema_list 相关的行打出来，
            // 便于确认最终生效的清单来源（替换 vs 追加）。
            runCatching {
                val cf = File(RimeWorkspace.userDir, "default.custom.yaml")
                if (cf.exists()) {
                    log("default.custom.yaml 相关行:")
                    cf.readText().lines().forEachIndexed { i, l ->
                        if (l.contains("schema_list") || l.contains("语燕输入法自动追加")) log("  ${i + 1}: $l")
                    }
                }
            }
            // 诊断：把各方案的 <方案>.custom.yaml 里 speller 相关行打出来。
            // 模糊音设置是「按方案」写进这里的，用户遇到「设了模糊音没效果」时，
            // 这一条能直接看出：设置到底落在哪个方案上、规则是什么。
            runCatching {
                val all = RimeWorkspace.userDir.listFiles()
                val customs = ArrayList<File>()
                if (all != null) {
                    for (f in all) {
                        if (f.isFile && f.name.endsWith(".custom.yaml")) customs.add(f)
                    }
                }
                customs.sortBy { it.name }
                log("方案级 custom.yaml 共 ${customs.size} 个:")
                for (f in customs) {
                    val lines = f.readText().lines().filter { l ->
                        l.contains("speller") || l.contains("derive/") || l.contains("__append") ||
                            l.contains("模糊音") || l.contains("enabled:")
                    }
                    log("  ${f.name}（${f.length()} bytes，speller/模糊音相关 ${lines.size} 行）:")
                    for (l in lines.take(24)) log("    $l")
                }
            }
            try {
                val df = File(RimeWorkspace.userDir, "default.yaml")
                if (df.exists()) {
                    val inList = df.readText().lines()
                        .dropWhile { it.trim() != "schema_list:" }
                        .drop(1)
                        .takeWhile { it.trim().startsWith("- ") || it.trim().startsWith("#") || it.isBlank() }
                        .filter { it.trim().startsWith("- ") }
                    log("最终 schema_list 共 ${inList.size} 项:")
                    inList.forEach { log("  $it") }
                    val need = listOf("pinyin", "t9_pinyin", "english", "stroke", "double_pinyin_flypy")
                    val text = inList.joinToString(" ")
                    val missing = need.filterNot { text.contains(it) }
                    log(if (missing.isEmpty()) "内置方案齐备 ✅" else "⚠ 缺失内置方案: $missing")
                } else {
                    log("⚠ 用户目录没有 default.yaml")
                }
            } catch (e: Exception) {
                log("读取 default.yaml 失败: $e")
            }

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
            val problems = selfCheckSchemas(schemas)
            if (problems.isEmpty()) {
                log("产物自检: 全部通过 ✅")
            } else {
                log("产物自检: 发现 ${problems.size} 处异常 ⚠")
                problems.forEach { log("  ⚠ $it") }
            }
            summary.append("✅ 部署完成，共 ").append(schemas.size).append(" 个方案")
            if (problems.isNotEmpty()) {
                summary.append("\n⚠ 自检发现 ").append(problems.size).append(" 处异常：")
                problems.take(5).forEach { summary.append("\n  · ").append(it) }
            }
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

    /**
     * 部署产物自检：把「静默坏」变成「立刻知道坏」。
     *
     * 为什么需要：模糊音毁方案（cand=1）、emoji 词典缺失这两次事故，引擎侧都是**零报错**，
     * 只能事后翻日志才看出来。但坏的时候指纹其实很明显，部署完成时扫一遍就能抓出来：
     *   · custom.yaml 里挂着【空的 __append:】—— Rime 会把整个 {__append: ...} 映射直接
     *     赋给 speller/algebra，列表变成 map，键位映射全丢 → 方案打不出字（cand=1）；
     *   · speller/algebra: 下面一个条目都没有；
     *   · build/<方案>.prism.bin 缺失或为 0 —— 该方案根本没编译出来。
     * 异常写进部署日志和返回消息，用户不用等「打字打不出来」才发现。
     */
    private fun selfCheckSchemas(schemas: List<Pair<String, String>>): List<String> {
        val problems = mutableListOf<String>()
        val userDir = RimeWorkspace.userDir
        for ((id, _) in schemas) {
            // ① custom.yaml：空的 __append 是「毁掉方案」的指纹
            val custom = File(userDir, "$id.custom.yaml")
            if (custom.exists()) {
                val lines = runCatching { custom.readText().lines() }.getOrNull()
                if (lines == null) {
                    problems.add("$id: custom.yaml 读取失败")
                } else {
                    val idx = lines.indexOfFirst { it.trim() == "__append:" }
                    if (idx >= 0) {
                        val hasChild = lines.drop(idx + 1)
                            .takeWhile { it.isBlank() || it.startsWith(" ") || it.startsWith("\t") }
                            .any { it.trim().startsWith("- ") }
                        if (!hasChild) {
                            problems.add("$id: custom.yaml 里有空的 __append:（会把 speller/algebra 变成 map → 方案打不出字）")
                        }
                    }
                    val algIdx = lines.indexOfFirst { it.trim() == "speller/algebra:" }
                    if (algIdx >= 0) {
                        val hasItem = lines.drop(algIdx + 1)
                            .takeWhile { it.isBlank() || it.startsWith(" ") || it.startsWith("\t") }
                            .any { it.trim().startsWith("- ") || it.trim() == "__append:" || it.trim() == "__patch:" }
                        if (!hasItem) {
                            problems.add("$id: custom.yaml 的 speller/algebra: 下面一个条目都没有")
                        }
                    }
                }
            }
            // ② prism：缺失或为 0 说明该方案根本没编译出来
            val prism = File(userDir, "build/$id.prism.bin")
            if (!prism.exists() || prism.length() == 0L) {
                problems.add("$id: build/$id.prism.bin 缺失或为空（方案未编译成功）")
            }
        }
        return problems
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
            lines.takeLast(30).forEach { log("  $it") }
            // 全量收集错误/警告行（部署失败的真实原因可能在日志前部，只看尾部会漏）
            val problems = lines.filter {
                it.contains(" E") || it.contains(" W") || it.contains("[ERROR]") || it.contains("failed")
            }
            log("---- 引擎日志问题行 (${problems.size} 条) ----")
            problems.take(40).forEach { log("  $it") }
            log("---- 引擎日志统计: ${lines.size} 行 ----")
        } catch (e: Exception) {
            log("读取引擎日志失败: $e")
        }
    }

    /**
     * 部署期间的空间看门狗。
     *
     * 只作兜底：正常部署不会触发。之所以需要它，是因为部署会跑 full_check 维护
     * （重建用户词典等），那条路径一旦失控会在几秒内写满设备（实测发生过一次，
     * 用户被迫清除应用数据）。看门狗每秒采样可用存储，跌破下限就结束进程 ——
     * 宁可中断部署，也不能把设备写满。
     */
    private fun startSpaceWatchdog(context: android.content.Context, initialFreeMb: Long) {
        Thread {
            while (true) {
                try {
                    Thread.sleep(1000)
                    val freeMb = try {
                        android.os.StatFs(RimeWorkspace.userDir.absolutePath).availableBytes / 1024 / 1024
                    } catch (e: Exception) {
                        -1L
                    }
                    if (freeMb in 0 until WATCHDOG_ABORT_MB) {
                        // 尽量留一条记录再结束（此时通常还写得进几十 KB）
                        try {
                            File(RimeWorkspace.SD_RIME_DIR, "deploy_aborted.log").appendText(
                                "部署期间可用存储跌破 ${WATCHDOG_ABORT_MB}MB" +
                                    "（当前 ${freeMb}MB，起始 ${initialFreeMb}MB），" +
                                    "已强制结束进程以防写满设备。" +
                                    "时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n"
                            )
                        } catch (_: Exception) {
                        }
                        android.os.Process.killProcess(android.os.Process.myPid())
                        return@Thread
                    }
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (_: Exception) {
                }
            }
        }.apply {
            isDaemon = true
            name = "deploy-space-watchdog"
        }.start()
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
