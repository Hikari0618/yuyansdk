package com.yuyan.inputmethod.util

import android.os.Environment
import com.yuyan.imemodule.application.CustomConstant
import java.io.File

/**
 * Rime 工作区管理：
 *  - 从 /sdcard/yuyan 导入用户放置的方案文件
 *  - 扫描用户目录中的自定义方案
 *  - 生成 default.yaml（schema_list 含全部可用方案）
 */
object RimeWorkspace {

    /** 语燕专属的 sdcard 目录（勿用 /sdcard/rime：那是同文输入法等工作区，文件混入会破坏部署） */
    const val SD_RIME_DIR = "/sdcard/yuyan"

    /** 内置方案（随 APK 附带，不作为“自定义方案”展示） */
    private val BUILTIN_SCHEMAS = setOf(
        CustomConstant.SCHEMA_ZH_T9,
        CustomConstant.SCHEMA_ZH_QWERTY,
        CustomConstant.SCHEMA_EN,
        CustomConstant.SCHEMA_ZH_STROKE,
        CustomConstant.SCHEMA_ZH_DOUBLE_LX17,
        "double_pinyin_flypy",
        "double_pinyin_abc",
        "double_pinyin_mspy",
        "double_pinyin_sogou",
        "double_pinyin_natural",
        "double_pinyin_ziguang",
    )

    val userDir: File get() = File(CustomConstant.RIME_DICT_PATH)

    /** 所有可部署方案（内置 + 自定义），返回 (schemaId, 名称) */
    fun allSchemas(): List<Pair<String, String>> {
        val result = LinkedHashMap<String, String>()
        // 内置方案固定顺序
        listOf(
            CustomConstant.SCHEMA_ZH_T9,
            CustomConstant.SCHEMA_ZH_QWERTY,
            "double_pinyin_natural",
            "double_pinyin_mspy",
            "double_pinyin_sogou",
            "double_pinyin_flypy",
            "double_pinyin_abc",
            "double_pinyin_ziguang",
            CustomConstant.SCHEMA_ZH_DOUBLE_LX17,
            CustomConstant.SCHEMA_ZH_STROKE,
            CustomConstant.SCHEMA_EN,
        ).forEach { result[it] = "" }
        // 用户目录下的方案（含万象拼音等）
        userDir.listFiles()?.filter { it.isFile && it.name.endsWith(".schema.yaml") }?.forEach { f ->
            val info = parseSchemaFile(f) ?: return@forEach
            result.putIfAbsent(info.first, info.second)
        }
        return result.map { (id, name) -> id to (if (name.isEmpty()) id else name) }
    }

    /** 仅自定义方案（排除内置），供键盘方案菜单展示——按 default.yaml 的
     *  schema_list 过滤：词库转换、反查等附属方案不进方案列表（rime 标准机制） */
    fun customSchemas(): List<Pair<String, String>> {
        val active = activeSchemaIds()
        return allSchemas().filter {
            it.first !in BUILTIN_SCHEMAS && (active.isEmpty() || it.first in active)
        }
    }

    /** 当前 default.yaml 的 schema_list（用户/方案包维护的可用方案列表） */
    fun activeSchemaIds(): Set<String> {
        val f = File(userDir, "default.yaml")
        if (!f.exists()) return emptySet()
        val ids = mutableSetOf<String>()
        var inList = false
        f.forEachLine { line ->
            when {
                line.trim() == "schema_list:" -> inList = true
                inList && line.trimStart().startsWith("- ") -> {
                    Regex("schema:\\s*(\\S+)").find(line)?.groupValues?.get(1)?.let { ids.add(it) }
                }
                inList && line.isNotBlank() -> inList = false
            }
        }
        return ids
    }

    private fun parseSchemaFile(file: File): Pair<String, String>? {
        return try {
            var id: String? = null
            var name: String? = null
            var inSchemaBlock = false
            file.forEachLine { line ->
                if (line.trim() == "schema:") {
                    inSchemaBlock = true
                } else if (inSchemaBlock && line.isNotBlank() && !line.startsWith("  ")) {
                    inSchemaBlock = false
                }
                if (inSchemaBlock || id == null) {
                    val idMatch = Regex("^\\s*schema_id:\\s*['\"]?([\\w\\-\\.]+)").find(line)
                    if (idMatch != null && id == null) id = idMatch.groupValues[1]
                    if (inSchemaBlock) {
                        val nameMatch = Regex("^\\s*name:\\s*['\"]?([^'\"\n]+)").find(line)
                        if (nameMatch != null && name == null) name = nameMatch.groupValues[1].trim()
                    }
                }
            }
            val schemaId = id ?: return null
            schemaId to (name ?: schemaId)
        } catch (e: Exception) {
            null
        }
    }

    /** 是否已授予文件访问权限（Android 11+ 需“所有文件访问”，6-10 需存储运行时权限） */
    fun hasStoragePermission(context: android.content.Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 确保已授予文件访问权限。未授权时 Toast 提示并跳转系统授权页，返回 false。
     * Android 11+：跳“所有文件访问”授权页（部分 ROM 不支持时回退应用详情页）；
     * Android 6-10：跳应用详情页手动开启存储权限。
     */
    fun ensureStoragePermission(context: android.content.Context): Boolean {
        if (hasStoragePermission(context)) return true
        val pkg = context.packageName
        val detailIntent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$pkg")
        )
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$pkg")
                )
            } catch (e: Exception) {
                detailIntent
            }
        } else {
            detailIntent
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(detailIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        android.widget.Toast.makeText(
            context,
            "需要文件访问权限才能读取 /sdcard/yuyan，请授权后重新操作",
            android.widget.Toast.LENGTH_LONG
        ).show()
        return false
    }

    /**
     * 从 /sdcard/rime 导入方案文件到 Rime 用户目录。
     * 跳过用户数据（userdb、user.yaml、build/）。
     * 返回导入摘要文本。
     */
    fun importFromSdcard(): String {
        val sdDir = File(SD_RIME_DIR)
        if (!sdDir.isDirectory) return "未找到 $SD_RIME_DIR 目录"
        var fileCount = 0
        var schemaCount = 0
        fun skip(name: String): Boolean {
            // 用户数据与运行时文件不导入；default.yaml 不跳过——它就是 rime 标准的
            // 方案列表文件（万象包自带、用户在里面 patch 方案 id），必须尊重。
            // 注意：不能笼统用 contains("userdb")——方案包的 lua 模块
            // lua/wanxiang/userdb.lua 会被误伤，导致万象 super_replacer /
            // super_sequence 等 lua 组件加载失败（module 'wanxiang/userdb' not found，
            // 间接辅助码等功能全部失效）。只过滤真正的用户词典产物。
            val isUserDbArtifact = name == "userdb" || name.endsWith(".userdb") ||
                name.endsWith(".userdb.txt")
            return isUserDbArtifact || name == "user.yaml" || name == "build" ||
                name == "installation.yaml"
        }
        fun copyRecursively(src: File, dst: File) {
            if (src.isDirectory) {
                src.listFiles()?.forEach { child ->
                    if (skip(child.name)) return@forEach
                    copyRecursively(child, File(dst, child.name))
                }
            } else {
                if (skip(src.name)) return
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                fileCount++
                if (src.name.endsWith(".schema.yaml")) schemaCount++
            }
        }
        copyRecursively(sdDir, userDir)
        // 万象等方案包的安装约定：custom/ 目录里的方案/补丁/资源文件需提升到根目录才生效
        // （rime 的 auto_patch 与方案加载只认根目录；方案包放 custom/ 是怕升级覆盖用户修改）。
        // 根目录已有同名文件时保留（尊重用户手动放置的版本）。
        val customDir = File(userDir, "custom")
        if (customDir.isDirectory) {
            customDir.listFiles()?.forEach { f ->
                val n = f.name
                val isText = n.endsWith(".yaml") || n.endsWith(".txt") || n.endsWith(".csv")
                val dst = File(userDir, n)
                if (f.isFile && isText && !dst.exists()) {
                    f.copyTo(dst)
                    fileCount++
                    if (n.endsWith(".schema.yaml")) schemaCount++
                }
            }
        }
        // 万象词库命名兼容：import_tables 引用 xxx.pro / xxx.pure（release 包命名），
        // 源码仓库中文件名为 xxx.dict.yaml——为缺失的变体名生成别名副本
        val dictsDir = File(userDir, "dicts")
        if (dictsDir.isDirectory) {
            dictsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".dict.yaml") }?.forEach { f ->
                val stem = f.name.removeSuffix(".dict.yaml")
                for (variant in listOf("pro", "pure")) {
                    val alias = File(dictsDir, "$stem.$variant.dict.yaml")
                    if (!alias.exists()) {
                        f.copyTo(alias)
                        fileCount++
                    }
                }
            }
        }
        return if (fileCount == 0) "$SD_RIME_DIR 中没有可导入的方案文件"
        else "已导入 $fileCount 个文件（$schemaCount 个方案）"
    }

    /**
     * App 内置方案 id —— 必须出现在 default.yaml 的 schema_list 里，
     * 否则 Rime 部署时不会编译它们，select_schema 失败，表现为「方案失效/打不出字」。
     * 与 assets/rime 下各 *.schema.yaml 的 schema_id 以及 CustomConstant 的 SCHEMA_* 常量保持一致。
     */
    private val BUILTIN_SCHEMA_IDS = listOf(
        "pinyin",                    // 拼音·全键
        "t9_pinyin",                 // 拼音·九键
        "double_pinyin_flypy",       // 小鹤
        "double_pinyin_ls17",        // 乱序17
        "double_pinyin_abc",
        "double_pinyin_mspy",
        "double_pinyin_natural",
        "double_pinyin_sogou",
        "double_pinyin_ziguang",
        "english",
        "stroke",
        "wanxiang"                   // 万象拼音PRO（/sdcard/yuyan 导入）
    )

    /**
     * 保证工作区的 default.yaml 里包含全部内置方案。
     *
     * 原实现是 `if (target.exists()) return false` —— 一旦用户目录已有 default.yaml
     * （用户自定义，或万象方案包自带），App 就再也不碰它。后果：内置方案只要不在清单里
     * 就永远修不回来（实测 pinyin / t9_pinyin / english / 七种双拼全部无法加载）。
     *
     * 现在改为「补齐」而非「覆盖」：保留用户原有条目与顺序（用户方案仍排在前），
     * 只把缺失的内置方案追加到 schema_list 末尾；文件不存在时才新建。
     * 只动 schema_list，其它字段（switcher/punctuator/...）原样保留。
     */
    fun writeDefaultYamlIfNeeded(): Boolean {
        val target = File(userDir, "default.yaml")

        if (!target.exists()) {
            val sb = StringBuilder()
            sb.append("config_version: \"1\"\n")
            sb.append("schema_list:\n")
            BUILTIN_SCHEMA_IDS.forEach { sb.append("  - schema: ").append(it).append('\n') }
            target.parentFile?.mkdirs()
            target.writeText(sb.toString())
            return true
        }

        val lines = target.readText().lines()
        val existing = HashSet<String>()
        var inList = false
        var listStart = -1
        var listEnd = -1
        lines.forEachIndexed { i, line ->
            val t = line.trim()
            if (t == "schema_list:") {
                inList = true
                listStart = i
                return@forEachIndexed
            }
            if (inList) {
                when {
                    t.startsWith("- ") -> {
                        existing.add(t.removePrefix("- ").removePrefix("schema:").trim())
                        listEnd = i
                    }
                    // 列表结束（遇到下一个顶层键或非缩进内容）
                    t.isNotEmpty() && !t.startsWith("#") -> inList = false
                }
            }
        }

        val missing = BUILTIN_SCHEMA_IDS.filterNot { existing.contains(it) }
        if (missing.isEmpty()) return false

        val out = ArrayList(lines)
        if (listStart < 0) {
            // 完全没有 schema_list：追加一个块
            out.add("schema_list:")
            missing.forEach { out.add("  - schema: $it") }
        } else {
            val insertAt = if (listEnd >= 0) listEnd + 1 else listStart + 1
            missing.forEachIndexed { k, id -> out.add(insertAt + k, "  - schema: $id") }
        }
        target.writeText(out.joinToString("\n") + "\n")
        return true
    }
}
