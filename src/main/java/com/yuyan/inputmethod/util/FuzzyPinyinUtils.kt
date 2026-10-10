package com.yuyan.inputmethod.util

import com.yuyan.imemodule.application.CustomConstant
import java.io.File

/**
 * 模糊音设置：自由开关常见模糊音对，写入 <方案>.custom.yaml 后自动重新部署。
 *
 * 万象拼音系列方案使用 wanxiang_algebra.yaml 中的命名补丁段（__patch）；
 * 其他方案用 __prepend 把标准模糊音规则插到 speller/algebra 最前面。
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

    /** 模糊音规则库文件名（写入用户目录，供 <方案>.custom.yaml 用 __patch 引用） */
    private const val FUZZY_ALGEBRA_FILE = "yuyan_fuzzy.yaml"

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
            // ★ 模糊音必须插到 speller/algebra 的【最前面】——那时拼串还是完整拼音音节；
            //   若用 __append 加在末尾，此时已被双拼映射成输入码（小鹤 fen→fF→ff），
            //   规则作用域错位，prism 构建会异常（实测 21.8KB 塌成 4.4KB，该方案候选全消失）。
            //
            //   librime 只提供 __patch / __append / __merge（源码 config_compiler.h，无 __prepend），
            //   所以用 __patch 重建整个列表：[模糊音规则...] + [方案原有 algebra]。
            //   规则库放在 yuyan_fuzzy.yaml，通过引用引用，避免在 custom.yaml 里重复展开。
            writeFuzzyAlgebraLibrary()
            sb.append("    __patch:\n")
            OPTIONS.filter { it.key in enabled }.forEach {
                sb.append("      - yuyan_fuzzy:/").append(it.key).append("\n")
            }
            sb.append("      - ").append(schemaId).append(".schema:/speller/algebra\n")
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
     * 写入模糊音规则库 yuyan_fuzzy.yaml（所有选项集中一份，供各方案 custom.yaml 用 __patch 引用）。
     * 放在用户目录，与 rime 其它配置同级，可被 CreateReference 解析为 yuyan_fuzzy:/<key>。
     */
    private fun writeFuzzyAlgebraLibrary() {
        val sb = StringBuilder()
        sb.append("# 语燕输入法模糊音规则库（自动生成，请勿手改）\n")
        sb.append("# 规则作用在「完整拼音音节」上，由 <方案>.custom.yaml 用 __patch 插到\n")
        sb.append("# speller/algebra 最前面（先于双拼映射），因此对全拼/小鹤/微软等所有方案通用。\n")
        FUZZY_RULES.forEach { (key, rules) ->
            sb.append(key).append(":\n")
            rules.forEach { sb.append("  - ").append(it).append("\n") }
        }
        File(CustomConstant.RIME_DICT_PATH, FUZZY_ALGEBRA_FILE).writeText(sb.toString())
    }

    /**
     * 模糊音规则表 —— 与方案无关，统一作用在「完整拼音音节」上。
     *
     * 【为什么必须作用在音节、且必须插到 algebra 最前面】
     *
     * 双拼方案的 speller/algebra 本质是「拼音音节 → 输入码」的映射，而映射排在列表末尾。
     * 以小鹤为例：
     *     xform/(.)en$/$1F/    →  fen  →  fF  →  ff
     *     xform/(.)eng$/$1G/   →  feng →  fG  →  fg
     *     xform/in$/B/         →  xin  →  xB  →  xb
     *     xform/ing$|uai$/K/   →  xing →  xK  →  xk
     *     xlit/QWRT.../qwrty.../       ← 大小写归一，映射收尾
     *
     * 若用 __append 追加到末尾，此时拼串已经是「输入码」（ff / fg），
     * 而 Rime 的 derive 正则匹配的是「音节」—— 作用域错位：
     *   ① 规则基本匹配不上（如 derive/^z/v/ 想匹配声母 z，但音节里根本没有 v）；
     *   ② 更糟的是会让 prism 构建异常 —— 实测小鹤/全拼的 prism 从 21.8KB 塌成 4.4KB，
     *      导致该方案所有候选消失（用户现象：设了模糊音后这些音一个候选都没有）。
     *
     * 用 __patch 重建列表、把规则放到最前面后，拼串还是完整拼音，于是：
     *   ① 规则与方案无关 —— 全拼/小鹤/微软/智能ABC/自然码… 全部通用，无需按方案翻译码空间；
     *   ② 写法与官方模糊音一致（derive/(e)n$/$1ng/ 之类）；
     *   ③ 派生出的音节会继续走完后面整条 algebra，最终码空间自动正确。
     *
     * 注意 ^z/^c/^s 的逆向规则带 ([^h]) 负向条件，避免把 zhi/chi/shi 变成 zhhi/chhi/shhi。
     */
    private val FUZZY_RULES: Map<String, List<String>> = mapOf(
        "z_zh" to listOf("derive/^zh/z/", "derive/^z([^h])/zh$1/"),
        "c_ch" to listOf("derive/^ch/c/", "derive/^c([^h])/ch$1/"),
        "s_sh" to listOf("derive/^sh/s/", "derive/^s([^h])/sh$1/"),
        "nl" to listOf("derive/^n/l/", "derive/^l/n/"),
        "rl" to listOf("derive/^r/l/", "derive/^l/r/"),
        "hf" to listOf("derive/^f/h/", "derive/^h/f/"),
        "kg" to listOf("derive/^k/g/", "derive/^g/k/"),
        "en_eng" to listOf("derive/(e)ng$/$1n/", "derive/(e)n$/$1ng/"),
        "in_ing" to listOf("derive/(i)ng$/$1n/", "derive/(i)n$/$1ng/"),
    )
}
