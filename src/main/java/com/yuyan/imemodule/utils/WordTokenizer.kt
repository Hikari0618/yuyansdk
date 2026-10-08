package com.yuyan.imemodule.utils

import android.os.Build

/**
 * 中文分词。
 *
 * 与同文（Trime）的 NativeTokenizer 同源：直接用系统自带的 ICU BreakIterator
 * 做词边界切分，不需要额外依赖，也不需要碰 native 库。
 * API 24+ 用 android.icu.text，更低版本回退到 java.text。
 */
object WordTokenizer {

    /**
     * @param text 原始文本
     * @param filterBlank 是否过滤掉空白词条（默认过滤）
     */
    fun tokenize(text: String, filterBlank: Boolean = true): List<String> {
        if (text.isEmpty()) return emptyList()
        val words = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val iterator = android.icu.text.BreakIterator.getWordInstance().apply { setText(text) }
            var start = iterator.first()
            var end = iterator.next()
            while (end != android.icu.text.BreakIterator.DONE) {
                val word = text.substring(start, end)
                if (!filterBlank || word.isNotBlank()) words.add(word)
                start = end
                end = iterator.next()
            }
        } else {
            val iterator = java.text.BreakIterator.getWordInstance().apply { setText(text) }
            var start = iterator.first()
            var end = iterator.next()
            while (end != java.text.BreakIterator.DONE) {
                val word = text.substring(start, end)
                if (!filterBlank || word.isNotBlank()) words.add(word)
                start = end
                end = iterator.next()
            }
        }
        return words
    }
}
