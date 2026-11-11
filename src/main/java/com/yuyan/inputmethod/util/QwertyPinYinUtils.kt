package com.yuyan.inputmethod.util

object QwertyPinYinUtils {
    fun getQwertyComposition(composition: String, comment: String): String {
        if(comment.isEmpty())return composition
        val compositionList = composition.filter { it.code <= 0xFF }.split("'".toRegex())
        val pinyinList = comment.split("'")
        return buildSpannedString {
            append(composition.filter { it.code > 0xFF })
            pinyinList.zip(compositionList).forEach { (pinyin, compo) ->
                if (compo == pinyin) append(pinyin).append("'")
                else if (pinyin.startsWith(compo)) append(pinyin.substring(0, compo.length)).append("'")
                else {
                    val common = compo.zip(pinyin).takeWhile { (c, p) -> c == p }.joinToString("") { (c, _) -> c.toString() }
                    append(common).append("'")
                    append(compo.removePrefix(common))
                }
            }
            // 注释只覆盖光标之前的部分（librime 按 input.substr(0, caret_pos) 重组候选，
            // 光标后的输入码没有对应拼音），zip 会在较短的一方结束 —— 剩下的输入码
            // 必须原样补回来。否则「光标停在中间按分词，后面整串字母从待编辑区消失」，
            // 继续打字时又整段冒出来（用户实测）。
            // 空段是结尾多出来的分隔符，本身已经由前一节补过 "'"，这里跳过免得重复。
            compositionList.drop(pinyinList.size).filter { it.isNotEmpty() }
                .forEach { append(it).append("'") }
        }
    }
}