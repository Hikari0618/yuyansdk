package com.yuyan.imemodule.view

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import com.yuyan.imemodule.R
import com.yuyan.imemodule.service.DecodingInfo

/**
 * 长按候选 → 「忘记该词」的共用菜单。
 *
 * 候选栏（CandidatesBar）和下拉候选界面（CandidatesContainer）共用同一份实现 ——
 * 之前只有候选栏有，下拉界面长按没反应（用户反馈）。
 *
 * 做法借鉴同文 BaseInputView.showCandidateActionMenu：第一行显示要删的那个词（禁用 = 纯展示），
 * 第二行「忘记该词」执行删除；删的是用户词库里的该词（librime delete_candidate）。
 *
 * @param onDeleted 删除成功后刷新列表（两个界面的刷新方式不同，由调用方决定）
 */
internal fun showCandidateForgetMenu(
    context: Context,
    anchor: View?,
    position: Int,
    onDeleted: () -> Unit,
) {
    if (anchor == null) return
    if (position < 0 || position >= DecodingInfo.candidates.size) return
    val text = DecodingInfo.candidates[position].text
    if (text.isEmpty()) return
    val popup = PopupMenu(context, anchor)
    popup.menu.add(0, 0, 0, text).isEnabled = false
    popup.menu.add(0, 1, 1, context.getString(R.string.forget_this_word))
        .setOnMenuItemClickListener {
            val ok = com.yuyan.inputmethod.core.Rime.deleteCandidate(position)
            com.yuyan.inputmethod.util.ImeLog.d("[forget] deleteCandidate($position) '$text' -> $ok")
            if (ok) {
                // 删完必须【重新问引擎拿候选】，否则界面还是旧数据 ——
                // 用户反馈：删完候选栏没变化，得把拼音删了重打才看到确实忘了。
                // 正常流程（选词后）也是走 DecodingInfo.updateDecodingCandidate()；
                // 同文则在 deleteCandidate 里带 emitResponse() 把新候选推给 UI。
                DecodingInfo.updateDecodingCandidate()
                onDeleted()
            } else {
                android.widget.Toast.makeText(
                    context, "删除失败（该词可能来自系统词库）", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            true
        }
    popup.show()
}
