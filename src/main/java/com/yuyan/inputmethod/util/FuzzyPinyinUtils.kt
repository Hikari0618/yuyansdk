package com.yuyan.inputmethod.util

import com.yuyan.imemodule.application.CustomConstant
import java.io.File

/**
 * 模糊音设置：自由开关常见模糊音对，写入 <方案>.custom.yaml 后自动重新部署。
 *
 * 万象拼音系列方案使用 wanxiang_algebra.yaml 中的命名补丁段；
 * 其他方案使用标准 speller/algebra derive 规则追加（__append）。
 */
object FuzzyPinyinUtils {

    data class FuzzyOption(val key: String, val label: String)

    val OPTIONS = listOf(
        FuzzyOption("z_zh", "z ↔ zh  平翘舌"),
        FuzzyOption("c_ch", "c ↔ ch  平翘舌"),
        FuzzyOption("s_sh", "s ↔ sh  平翘舌"),
        FuzzyOption("nl", "n ↔ l   鼻音边音"),
        FuzzyOption("rl", "r ↔ l"),
        FuzzyOption("hf", "h ↔ f   唇齿音"),
        FuzzyOption("kg", "k ↔ g"),
        FuzzyOption("en_eng", "en ↔ eng  前后鼻音"),
        FuzzyOption("in_ing", "in ↔ ing  前后鼻音"),
    )

    private const val ENABLED_MARKER = "# enabled:"

    /** 万象拼音系列（algebra 由 wanxiang_algebra.yaml 提供命名补丁段） */
    fun isWanxiang(schemaId: String): Boolean {
        val schemaFile = File(CustomConstant.RIME_DICT_PATH, "$schemaId.schema.yaml")
        return try {
            schemaFile.exists() && schemaFile.readText().contains("wanxiang_algebra")
        } catch (e: Exception) {
            false
        }
    }

    private fun customFile(schemaId: String) =
        File(CustomConstant.RIME_DICT_PATH, "$schemaId.custom.yaml")

    /** 当前已启用的模糊音集合 */
    fun getEnabled(schemaId: String): Set<String> {
        val file = customFile(schemaId)
        if (!file.exists()) return emptySet()
        val text = file.readText()
        // 优先读取生成时写入的标记行
        text.lineSequence().forEach { line ->
            if (line.startsWith(ENABLED_MARKER)) {
                return line.removePrefix(ENABLED_MARKER).trim().split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }.toSet()
            }
        }
        // 兼容手写配置：扫描补丁项
        return OPTIONS.mapNotNull { opt ->
            val pattern = "模糊音_${opt.key}"
            val enabled = text.lineSequence()
                .map { it.trim() }
                .any { it.startsWith("- ") && it.contains(pattern) && !it.startsWith("#") }
            if (enabled) opt.key else null
        }.toSet()
    }

    /**
     * 保存模糊音设置。保留 custom.yaml 中与模糊音无关的原有补丁项（如 menu/page_size）。
     */
    fun save(schemaId: String, enabled: Set<String>): Boolean {
        val wanxiang = isWanxiang(schemaId)
        val file = customFile(schemaId)

        // 读取原有 patch 内容，保留 speller/algebra 之外的项
        val keptLines = mutableListOf<String>()
        if (file.exists()) {
            var inPatch = false
            var skippingAlgebra = false
            for (line in file.readText().lines()) {
                val trimmed = line.trim()
                when {
                    trimmed == "patch:" -> {
                        inPatch = true
                        skippingAlgebra = false
                        continue
                    }
                    inPatch && (line.startsWith("  ") || line.startsWith("\t")) -> {
                        if (trimmed.startsWith("speller/algebra")) {
                            skippingAlgebra = true
                            continue
                        }
                        if (skippingAlgebra && (line.startsWith("    ") || line.startsWith("\t"))) {
                            continue // algebra 块的续行
                        }
                        skippingAlgebra = false
                        keptLines.add(line)
                    }
                    else -> {
                        if (trimmed.startsWith(ENABLED_MARKER)) continue
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            if (keptLines.isNotEmpty()) keptLines.add(line)
                        } else {
                            inPatch = false
                            skippingAlgebra = false
                        }
                    }
                }
            }
        }

        val sb = StringBuilder()
        sb.append("# 模糊音设置（由语燕输入法生成，可在“模糊音”设置中修改）\n")
        sb.append(ENABLED_MARKER).append(" ").append(enabled.sorted().joinToString(" ")).append("\n")
        sb.append("patch:\n")
        keptLines.filter { it.isNotBlank() && it.trim() != "patch:" }
            .forEach { sb.append(it).append("\n") }
        sb.append("  speller/algebra:\n")
        if (wanxiang) {
            // 保留用户已选的方案组合（base/pro/键盘位），默认：pro/小鹤双拼 + pro/间接辅助
            val combo = keptBaseCombo(if (file.exists()) file.readText() else "")
                .ifEmpty { listOf("wanxiang_algebra:/pro/小鹤双拼", "wanxiang_algebra:/pro/间接辅助") }
            sb.append("    __patch:\n")
            OPTIONS.filter { it.key in enabled }.forEach {
                sb.append("      - wanxiang_algebra:/模糊音_").append(it.key).append("\n")
            }
            combo.forEach { sb.append("      - ").append(it).append("\n") }
        } else {
            sb.append("    __append:\n")
            OPTIONS.filter { it.key in enabled }.forEach { opt ->
                rulesFor(schemaId, opt.key).forEach { sb.append("      - ").append(it).append("\n") }
            }
        }
        file.writeText(sb.toString())
        return true
    }

    /** 保留万象 custom 中已有的方案组合条目（base/pro/键盘位，排除模糊音段） */
    private fun keptBaseCombo(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") && it.contains("wanxiang_algebra:/") && !it.contains("模糊音_") }
            .map { it.removePrefix("- ").substringBefore("  #").trim() }
            .toList()
    }

    /**
     * 双拼方案的模糊音规则表（按方案 × 选项）。
     *
     * 【为什么需要这张表】通用模糊音规则写的是全拼（如 derive/^zh/z/），
     * 但 Rime 的 speller/algebra 按序执行，而本类写入的是 __append（加在末尾）——
     * 此时码已经过双拼映射（zh→v、en→f …），全拼规则永远匹配不上。
     * 所以必须把「全拼空间的模糊音对」翻译成「双拼码空间的键位对」。
     *
     * 【生成方式】对每个方案用它自身的 algebra 推演：
     *   声母码 = 对该声母+参照韵母(a)取码，再扣掉 a 的韵码；
     *   韵母码 = 对参照声母(b)+该韵母取码，再扣掉 b 的声母码。
     * 结果与各双拼方案的公开键位表逐一核对一致。
     */
    private val DOUBLE_PINYIN_FUZZY: Map<String, Map<String, List<String>>> = mapOf(
        "double_pinyin_flypy" to mapOf(   // 小鹤
            "z_zh" to listOf("derive/^z/v/", "derive/^v/z/"),
            "c_ch" to listOf("derive/^c/i/", "derive/^i/c/"),
            "s_sh" to listOf("derive/^s/u/", "derive/^u/s/"),
            "nl" to listOf("derive/^n/l/", "derive/^l/n/"),
            "rl" to listOf("derive/^r/l/", "derive/^l/r/"),
            "hf" to listOf("derive/^f/h/", "derive/^h/f/"),
            "kg" to listOf("derive/^k/g/", "derive/^g/k/"),
            "en_eng" to listOf("derive/f$/g/", "derive/g$/f/"),
            "in_ing" to listOf("derive/b$/k/", "derive/k$/b/"),
        ),
        "double_pinyin_natural" to mapOf(   // 自然码
            "z_zh" to listOf("derive/^z/v/", "derive/^v/z/"),
            "c_ch" to listOf("derive/^c/i/", "derive/^i/c/"),
            "s_sh" to listOf("derive/^s/u/", "derive/^u/s/"),
            "nl" to listOf("derive/^n/l/", "derive/^l/n/"),
            "rl" to listOf("derive/^r/l/", "derive/^l/r/"),
            "hf" to listOf("derive/^f/h/", "derive/^h/f/"),
            "kg" to listOf("derive/^k/g/", "derive/^g/k/"),
            "en_eng" to listOf("derive/f$/g/", "derive/g$/f/"),
            "in_ing" to listOf("derive/n$/y/", "derive/y$/n/"),
        ),
        "double_pinyin_abc" to mapOf(   // 智能ABC
            "z_zh" to listOf("derive/^z/a/", "derive/^a/z/"),
            "c_ch" to listOf("derive/^c/e/", "derive/^e/c/"),
            "s_sh" to listOf("derive/^s/v/", "derive/^v/s/"),
            "nl" to listOf("derive/^n/l/", "derive/^l/n/"),
            "rl" to listOf("derive/^r/l/", "derive/^l/r/"),
            "hf" to listOf("derive/^f/h/", "derive/^h/f/"),
            "kg" to listOf("derive/^k/g/", "derive/^g/k/"),
            "en_eng" to listOf("derive/f$/g/", "derive/g$/f/"),
            "in_ing" to listOf("derive/c$/y/", "derive/y$/c/"),
        ),
        "double_pinyin_mspy" to mapOf(   // 微软
            "z_zh" to listOf("derive/^z/v/", "derive/^v/z/"),
            "c_ch" to listOf("derive/^c/i/", "derive/^i/c/"),
            "s_sh" to listOf("derive/^s/u/", "derive/^u/s/"),
            "nl" to listOf("derive/^n/l/", "derive/^l/n/"),
            "rl" to listOf("derive/^r/l/", "derive/^l/r/"),
            "hf" to listOf("derive/^f/h/", "derive/^h/f/"),
            "kg" to listOf("derive/^k/g/", "derive/^g/k/"),
            "en_eng" to listOf("derive/f$/g/", "derive/g$/f/"),
            "in_ing" to listOf("derive/n$/;/", "derive/;$/n/"),
        ),
        "double_pinyin_sogou" to mapOf(   // 搜狗
            "z_zh" to listOf("derive/^z/v/", "derive/^v/z/"),
            "c_ch" to listOf("derive/^c/i/", "derive/^i/c/"),
            "s_sh" to listOf("derive/^s/u/", "derive/^u/s/"),
            "nl" to listOf("derive/^n/l/", "derive/^l/n/"),
            "rl" to listOf("derive/^r/l/", "derive/^l/r/"),
            "hf" to listOf("derive/^f/h/", "derive/^h/f/"),
            "kg" to listOf("derive/^k/g/", "derive/^g/k/"),
            "en_eng" to listOf("derive/f$/g/", "derive/g$/f/"),
            "in_ing" to listOf("derive/n$/;/", "derive/;$/n/"),
        ),
        "double_pinyin_ziguang" to mapOf(   // 紫光
            "z_zh" to listOf("derive/^z/u/", "derive/^u/z/"),
            "c_ch" to listOf("derive/^c/a/", "derive/^a/c/"),
            "s_sh" to listOf("derive/^s/i/", "derive/^i/s/"),
            "nl" to listOf("derive/^n/l/", "derive/^l/n/"),
            "rl" to listOf("derive/^r/l/", "derive/^l/r/"),
            "hf" to listOf("derive/^f/h/", "derive/^h/f/"),
            "kg" to listOf("derive/^k/g/", "derive/^g/k/"),
            "en_eng" to listOf("derive/w$/t/", "derive/t$/w/"),
            "in_ing" to listOf("derive/y$/;/", "derive/;$/y/"),
        ),
        "double_pinyin_ls17" to mapOf(   // 乱序17
            "z_zh" to listOf("derive/^W/Z/", "derive/^Z/W/"),
            "c_ch" to listOf("derive/^F/C/", "derive/^C/F/"),
            "s_sh" to listOf("derive/^M/S/", "derive/^S/M/"),
            "nl" to listOf("derive/^N/L/", "derive/^L/N/"),
            "rl" to listOf("derive/^N/L/", "derive/^L/N/"),
            "hf" to listOf("derive/^F/H/", "derive/^H/F/"),
            "kg" to listOf("derive/^J/G/", "derive/^G/J/"),
            "en_eng" to listOf("derive/S$/Y/", "derive/Y$/S/"),
            "in_ing" to listOf("derive/S$/Y/", "derive/Y$/S/"),
        ),
    )

    /**
     * 取某方案某模糊音项对应的 derive 规则。
     * 双拼方案走码空间翻译表（见 DOUBLE_PINYIN_FUZZY），
     * 其余（全拼等）仍用全拼规则 —— 它们没有双拼映射，规则本就作用于原始输入。
     */
    private fun rulesFor(schemaId: String, key: String): List<String> =
        DOUBLE_PINYIN_FUZZY[schemaId]?.get(key) ?: genericRules(key)

    /** 通用（全拼）方案的模糊音 derive 规则 */
    private fun genericRules(key: String): List<String> = when (key) {
        "z_zh" -> listOf("derive/^zh/z/", "derive/^z([^h])/zh\$1/")
        "c_ch" -> listOf("derive/^ch/c/", "derive/^c([^h])/ch\$1/")
        "s_sh" -> listOf("derive/^sh/s/", "derive/^s([^h])/sh\$1/")
        "nl" -> listOf("derive/^n/l/", "derive/^l/n/")
        "rl" -> listOf("derive/^r/l/", "derive/^l/r/")
        "hf" -> listOf("derive/^f/h/", "derive/^h/f/")
        "kg" -> listOf("derive/^k/g/", "derive/^g/k/")
        "en_eng" -> listOf("derive/^(.*)en\$/\$1eng/", "derive/^(.*)eng\$/\$1en/")
        "in_ing" -> listOf("derive/^(.*)in\$/\$1ing/", "derive/^(.*)ing\$/\$1in/")
        else -> emptyList()
    }
}
