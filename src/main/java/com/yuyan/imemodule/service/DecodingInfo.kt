package com.yuyan.imemodule.service

import android.view.KeyEvent
import androidx.lifecycle.MutableLiveData
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Kernel
import com.yuyan.inputmethod.core.Rime

/**
 * 词库解码操作对象
 */
object DecodingInfo {

    var activeCandidate = 0  //当前显示候选词位置
    var activeCandidateBar = 0  //当前显示候选词位置
    // 候选词列表
    val candidatesLiveData = MutableLiveData<List<CandidateListItem>>()
    // 是否是联想词
    var isAssociate = false

    /**
     * 重置
     */
    fun reset() {
        isAssociate = false
        activeCandidate = 0
        activeCandidateBar = 0
        candidatesLiveData.value = emptyList()
        Kernel.reset()
    }

    val isCandidatesEmpty: Boolean
        // 候选词列表是否为空
        get() = candidatesLiveData.value.isNullOrEmpty()

    val candidateSize: Int
        // 候选词列表是否为空
        get() = if(isCandidatesEmpty) 0 else candidatesLiveData.value!!.size


    val candidates: List<CandidateListItem>
        // 候选词列表是否为空
        get() = candidatesLiveData.value?:emptyList()

    // 增加拼写字符
    fun inputAction(event: KeyEvent) {
        activeCandidate = 0
        activeCandidateBar = 0
        Kernel.inputKeyCode(event)
        isAssociate = false
    }

    /**
     * 选择拼音
     * @param position 选择的position
     */
    fun selectPrefix(position: Int) {
        activeCandidate = 0
        activeCandidateBar = 0
        Kernel.selectPrefix(position)
    }

    val prefixs: Array<String>  //获取拼音组合
        get() = Kernel.prefixs

    /**
     * 删除
     */
    fun deleteAction() {
        activeCandidate = 0
        activeCandidateBar = 0
        if(!isEngineFinish)Kernel.deleteAction()
        else reset()
    }


    val isEngineFinish: Boolean
        get() = Kernel.isFinish

    val composingStrForDisplay: String   //获取显示的拼音字符串/
        get() = Kernel.wordsShowPinyin

    val composingStrForCommit: String   // 获取输入的拼音字符串（用于回车/上屏）
        get() {
            // 必须用「引擎原始输入」而不是显示串：显示串里的空格/撇号只是音节分隔符，
            // 而且显示串可能已被换成全拼或带声调注释。直接提交显示串会把分隔符一起上屏
            // （用户实测：输入 iiii 回车得到 "ii ii"）。
            val raw = Rime.compositionText
                .filter { it.code <= 0xFF }      // 去掉引擎插入的提示字符（如 U+2038 光标符）
                .replace("'", "")
                .replace(" ", "")
            if (raw.isNotEmpty()) return raw
            // 引擎没有 preedit（例如英文模式）时退回显示串，同样去掉分隔符
            return Kernel.wordsShowPinyin.replace("'", "").replace(" ", "")
                .ifEmpty { getCandidate(0)?.text ?: "" }
        }

    val nextPageCandidates: Int   // 获取下一页的候选词
        get() {
            val cands = Kernel.nextPageCandidates
            if (cands.isNotEmpty()) {
                candidatesLiveData.postValue(candidatesLiveData.value?.plus(cands))
                return cands.size
            }
            return 0
        }

    /**
     * 选择一个候选词，且重新获取候选词列表
     */
    fun chooseDecodingCandidate(candId: Int): String {
        activeCandidate = 0
        activeCandidateBar = 0
        var candidate: String
        if(!isEngineFinish || isAssociate) { // Rime和联想
            if (candId >= 0) Kernel.getWordSelectedWord(candId)
            val newCandidates = Kernel.candidates
            candidate = if (newCandidates.isNotEmpty()) Kernel.commitText
            else if (candId in 0..<candidateSize) Kernel.commitText.ifEmpty { candidatesLiveData.value!![candId].text }
            else ""
            candidatesLiveData.value = newCandidates
        } else {  // 手写
            candidate = if (candId in 0..<candidateSize) candidatesLiveData.value!![candId].text  else ""
            reset()
        }
        return candidate
    }

    /**
     * 对输入的拼音进行查询。
     */
    fun updateDecodingCandidate() {
        activeCandidate = 0
        activeCandidateBar = 0
        candidatesLiveData.value = Kernel.candidates
    }

    /**
     * 获得指定的候选词
     */
    fun getCandidate(candId: Int): CandidateListItem? {
        return candidatesLiveData.value?.getOrNull(candId)
    }

    // 更新候选词
    fun cacheCandidates(words: Array<CandidateListItem>, associate: Boolean = false) {
        com.yuyan.inputmethod.util.ImeLog.d("[ui] cacheCand n=${words.size} assoc=$associate first='${words.firstOrNull()?.text ?: ""}'")
        isAssociate = associate
        activeCandidate = 0
        activeCandidateBar = 0
        candidatesLiveData.value = words.asList()
    }

    /**
     * 打开分词页面（照同文 Trime 的 SegmentsWindow）。
     * 切词结果不塞进候选区，而是新开一个页面：可多选、可按住滑行选择，再一起上屏。
     */
    fun segmentClipboardSuggestion(content: String): Boolean {
        if (content.isBlank()) return false
        if (com.yuyan.imemodule.utils.WordTokenizer.tokenize(content).isEmpty()) return false
        com.yuyan.imemodule.keyboard.container.SegmentsContainer.sourceText = content
        com.yuyan.imemodule.keyboard.KeyboardManager.instance.switchKeyboard(
            com.yuyan.imemodule.keyboard.KeyboardManager.KeyboardType.SEGMENTS
        )
        return true
    }

    /**
     * 根据输入的字符查询候选词
     */
    fun getAssociateWord(words: String) {
        isAssociate = true
        Kernel.getAssociateWord(words)
    }
}