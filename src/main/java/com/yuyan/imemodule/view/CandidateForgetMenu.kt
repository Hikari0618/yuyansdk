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
                // 删完必须【重新取候选 + 重画】，否则界面还是旧数据。
                // 关键：必须走 app 自己的刷新入口 RimeEngine.updateCandidatesOrCommitText() ——
                // 它会 Rime.getRimeContext() 重新取候选并写回 showCandidates（还会打 [ctx] 日志）。
                // 之前只调 DecodingInfo.updateDecodingCandidate() 是不够的：那条路读的是
                // RimeEngine 里【上一次按键时缓存】的 showCandidates ✗（实测：日志里连 [ctx] 都没有）。
                com.yuyan.inputmethod.RimeEngine.updateCandidatesOrCommitText()
                onDeleted()
                // librime 的 delete_candidate 【只删用户词库】，对【系统词库】里的词是无效的
                // （它自己注释 "this doesn't mean anything is deleted for sure"，返回值 true
                //  不代表真删掉了）。宿主机实测：系统词删完 + 完整重放输入，词依旧在。
                // 所以复查一遍：还在就明确说清原因，别让用户以为「点了没反应」。
                if (DecodingInfo.candidates.any { it.text == text }) {
                    android.widget.Toast.makeText(
                        context,
                        "「$text」来自系统词库，无法忘记（只能忘记你自己输入过的词）",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                android.widget.Toast.makeText(
                    context, "删除失败（该词可能来自系统词库）", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            true
        }
    popup.show()
}
