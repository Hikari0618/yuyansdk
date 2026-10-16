package com.yuyan.inputmethod.core

import android.view.KeyEvent
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.inputmethod.RimeEngine

object Kernel {

    // 初始化输入法
    @Synchronized
    fun initImeSchema(schema: String) {
        RimeEngine.selectSchema(schema)
        nativeUpdateImeOption()
    }

    /**
     * 计时版 initImeSchema（给「中英切换卡顿」定位用）。
     * 返回 (selectSchema 耗时, updateImeOption 耗时)：前者是 librime 换方案（要加载该方案的
     * 词典，耗时与方案大小成正比），后者只是两次 setOption。分开量才能证明卡顿不在 app 侧。
     * 必须放在 Kernel 里 —— RimeEngine / nativeUpdateImeOption 只在本类内可见。
     */
    fun initImeSchemaTimed(schema: String): Pair<Long, Long> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        RimeEngine.selectSchema(schema)
        val t1 = android.os.SystemClock.elapsedRealtime()
        nativeUpdateImeOption()
        val t2 = android.os.SystemClock.elapsedRealtime()
        return Pair(t1 - t0, t2 - t1)
    }

    fun getCurrentRimeSchema(): String {
        return RimeEngine.getCurrentRimeSchema()
    }

    // 传入一个键码
    fun inputKeyCode(event: KeyEvent) {
        RimeEngine.onNormalKey(event)
    }

    // 是否输入完毕，等待上屏。
    val isFinish: Boolean
        get() = RimeEngine.isFinish()

    val candidates: List<CandidateListItem>
        get() = RimeEngine.showCandidates

    val nextPageCandidates: Array<CandidateListItem>
        get() = RimeEngine.getNextPageCandidates()

    // 拿到候选词拼音
    val prefixs: Array<String>
        get() = RimeEngine.getPrefixs()

    // 选择某个候选拼音
    fun selectPrefix(index: Int) {
        RimeEngine.selectPinyin(index)
    }

    // 执行选择动作，选择了index指向的词语
    fun getWordSelectedWord(index: Int) {
        if (DecodingInfo.isAssociate) RimeEngine.selectAssociation(index)
        else if(!isFinish)RimeEngine.selectCandidate(index)
    }

    // 最上端拼音行
    val wordsShowPinyin: String
        get() = RimeEngine.showComposition

    // 得到即将上屏的候选词
    val commitText: String
        get() = RimeEngine.preCommitText

    // 删除操作
    fun deleteAction() {
        RimeEngine.onDeleteKey()
    }

    // 重置输入状态
    fun reset() {
        RimeEngine.reset()
    }

    // 释放内存
    fun resetIme() {
        RimeEngine.destroy()
        initImeSchema(AppPrefs.getInstance().internal.pinyinModeRime.getValue())
    }

    // 根据输入的字符查询候选词
    fun getAssociateWord(words: String) {
        RimeEngine.predictAssociationWords(words)
    }

    // 刷新引擎配置
    fun nativeUpdateImeOption() {
        val chineseFanTi = AppPrefs.getInstance().input.chineseFanTi.getValue()
        RimeEngine.setImeOption("traditionalization", chineseFanTi)
        val emojiInput = AppPrefs.getInstance().input.emojiInput.getValue()
        RimeEngine.setImeOption("emoji", emojiInput)
    }

    fun setCharCase(charCase: Int) {
        RimeEngine.setCharCase(charCase)
    }
}