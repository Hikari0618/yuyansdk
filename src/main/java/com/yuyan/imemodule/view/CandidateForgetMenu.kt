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
                // 删完【走 app 自己的「光标重定位」流程】—— 这是「点一下待编辑框」会走的那条路，
                // 也是 app 里唯一能把「引擎重算 + 界面重新拉候选」两步都做完的现成入口：
                //   ① Rime.setCaretPos(...) → librime 的 Context::set_caret_pos 【无条件】发
                //      update_notifier_ → ConcreteEngine::OnContextUpdate → Compose() ★重建候选★
                //      （不比较位置是否变化，所以设成当前位置也能触发 ✓）
                //   ② RimeEngine.refreshCandidatesAfterCaretMove() → app 重新拉候选写回 showCandidates
                //      （代码注释原话：showCandidates 是按键时缓存的，必须按新位置重新拉一次）
                val caret = com.yuyan.inputmethod.core.Rime.getRimeInput().length
                com.yuyan.inputmethod.core.Rime.setCaretPos(caret)
                com.yuyan.inputmethod.RimeEngine.refreshCandidatesAfterCaretMove()
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
