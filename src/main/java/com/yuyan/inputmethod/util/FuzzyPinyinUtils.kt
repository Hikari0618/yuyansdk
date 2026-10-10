package com.yuyan.inputmethod.util

import com.yuyan.imemodule.application.CustomConstant
import java.io.File

/**
 * 模糊音设置：自由开关常见模糊音对，写入 <方案>.custom.yaml 后自动重新部署。
 *
 * 万象拼音系列方案使用 wanxiang_algebra.yaml 中的命名补丁段（__patch）；
 * 其他方案在 custom.yaml 里重写整条 speller/algebra：模糊音规则在前、方案原有映射在后。
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
            val rules = OPTIONS.filter { it.key in enabled }
                .flatMap { FUZZY_RULES[it.key].orEmpty() }
            val base = extractSchemaAlgebra(schemaId)
            if (rules.isNotEmpty() && base != null) {
                // ★ 规则在前（作用于完整拼音音节）+ 方案原有 algebra 在后（继续做键位映射）。
                //   为什么必须「重写整条列表」而不是打补丁：librime 的三个列表指令都插不到前面 ——
                //     __append → AppendToList，只能加末尾（此时拼串已是输入码，规则匹配不上）；
                //     __patch  → 引用内容必须是 ConfigMap，列表直接报 "invalid patch at ..."；
                //     __merge  → MergeTree 只处理 ConfigMap，列表静默失败；
                //     __prepend → 不存在（config_compiler.h 里没有）。
                //   注意 base 可能为空（全拼方案本来就没有 algebra）—— 那种情况下这个列表
                //   就只有模糊音规则，正是我们要的；绝不能对它写 __append（会把节点变成 map）。
                rules.forEach { sb.append("    - ").append(it).append("\n") }
                base.forEach { sb.append("    - ").append(it).append("\n") }
            } else {
                // 抽取失败（含 __ 指令等）→ 退回 __append。此时 schema 里确实有 speller/algebra
                // 节点，追加语义可用；放末尾虽匹配不上音节、但不破坏方案，属安全退化。
                sb.append("    __append:\n")
                rules.forEach { sb.append("      - ").append(it).append("\n") }
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
     * 从 <方案>.schema.yaml 抽出 speller.algebra 的规则行。
     *
     * 返回值有两种「空」的含义，调用方必须区分：
     *   - 返回空列表  → 方案本来就没有 algebra（如全拼 pinyin.schema.yaml），
     *                   此时 custom.yaml 直接写「只有模糊音规则」的平铺列表即可；
     *   - 返回 null    → 抽取失败（出现 __ 指令、格式不认识、文件读不到），
     *                   此时退回 __append 做安全退化。
     * 不能把两者都当空列表：对「没有 algebra 的方案」写 __append，
     * 因为 schema 里没有 speller/algebra 节点，Rime 会把整个 __append 映射
     * 直接赋给 speller/algebra（EditNode 的 overwriting 分支），
     * 结果该节点成了 map 而不是 list，引擎无法使用 → 模糊音不生效（全拼实测）。
     */
    private fun extractSchemaAlgebra(schemaId: String): List<String>? {
        val f = File(CustomConstant.RIME_DICT_PATH, "$schemaId.schema.yaml")
        if (!f.exists()) return null
        val out = ArrayList<String>()
        var inSpeller = false
        var inAlgebra = false
        var spellerIndent = 0
        var algebraIndent = 0
        try {
            for (raw in f.readText().lines()) {
                val line = raw.trimEnd()
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val lead = line.length - line.trimStart().length
                if (!inSpeller) {
                    if (t == "speller:") {
                        inSpeller = true
                        spellerIndent = lead
                    }
                    continue
                }
                if (lead <= spellerIndent) {   // 已离开 speller 段
                    inSpeller = false
                    inAlgebra = false
                    continue
                }
                if (!inAlgebra) {
                    if (t == "algebra:") {
                        inAlgebra = true
                        algebraIndent = lead
                    }
                    continue
                }
                if (lead <= algebraIndent) {   // algebra 段结束
                    inAlgebra = false
                    continue
                }
                if (t.startsWith("- ")) {
                    val rule = t.removePrefix("- ").substringBefore("  #").trim()
                    if (rule.isEmpty() || rule.contains("__")) return null
                    out.add(rule)
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out   // 可能为空：方案本来就没有 algebra
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
     * 用「重写整条 algebra」把规则放到最前面后，拼串还是完整拼音，于是：
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
