package com.yuyan.inputmethod.util

import android.os.Environment
import com.yuyan.imemodule.application.CustomConstant
import java.io.File

/**
 * Rime 工作区管理：
 *  - 从 /sdcard/rime 导入用户放置的方案文件
 *  - 扫描用户目录中的自定义方案
 *  - 生成 default.yaml（schema_list 含全部可用方案）
 */
object RimeWorkspace {

    const val SD_RIME_DIR = "/sdcard/rime"

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

    private val userDir: File get() = File(CustomConstant.RIME_DICT_PATH)

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

    /** 仅自定义方案（排除内置），供键盘方案菜单展示 */
    fun customSchemas(): List<Pair<String, String>> =
        allSchemas().filter { it.first !in BUILTIN_SCHEMAS }

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

    /** 是否已授予所有文件访问权限（Android 11+ 读 /sdcard 需要） */
    fun hasStoragePermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
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
            return name.contains("userdb") || name == "user.yaml" || name == "build"
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
        return if (fileCount == 0) "$SD_RIME_DIR 中没有可导入的方案文件"
        else "已导入 $fileCount 个文件（$schemaCount 个方案）"
    }

    /**
     * 生成 default.yaml，schema_list 包含全部可用方案。
     * 以用户目录中的 default.yaml（assets 模板）为底，仅替换 schema_list 段，
     * 保留 switcher/punctuator/key_binder 等标准段（方案配置 import_preset 需要）。
     * 若用户在 /sdcard/rime 放置了自己的 default.yaml（导入时已复制），则尊重用户版本。
     */
    fun writeDefaultYamlIfNeeded(userProvided: Boolean): Boolean {
        val target = File(userDir, "default.yaml")
        if (userProvided && target.exists()) return false
        val schemas = allSchemas()
        if (schemas.isEmpty()) return false
        val newItems = schemas.map { "  - schema: $it" }
        val sb = StringBuilder()
        var inSchemaList = false
        var spliced = false
        if (target.exists()) {
            for (line in target.readText().lines()) {
                when {
                    line.trim() == "schema_list:" -> {
                        sb.append(line).append("\n")
                        newItems.forEach { sb.append(it).append("\n") }
                        inSchemaList = true
                        spliced = true
                    }
                    inSchemaList && line.trimStart().startsWith("- ") -> continue // 跳过旧列表项
                    else -> {
                        inSchemaList = false
                        sb.append(line).append("\n")
                    }
                }
            }
        }
        if (!spliced) {
            sb.append("config_version: \"1\"\n")
            sb.append("schema_list:\n")
            newItems.forEach { sb.append(it).append("\n") }
        }
        target.writeText(sb.toString())
        return true
    }
}
