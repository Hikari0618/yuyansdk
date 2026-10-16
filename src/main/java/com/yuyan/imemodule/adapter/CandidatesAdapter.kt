package com.yuyan.imemodule.adapter

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.callback.OnRecyclerItemClickListener
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance

/**
 * 候选词界面适配器
 */
class CandidatesAdapter(context: Context?) :
    RecyclerView.Adapter<CandidatesAdapter.SymbolHolder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val textColor: Int = activeTheme.keyTextColor
    private var mOnItemClickListener: OnRecyclerItemClickListener? = null
    private var mOnItemLongClickListener: OnRecyclerItemClickListener? = null
    fun setOnItemClickLitener(mOnItemClickLitener: OnRecyclerItemClickListener?) {
        mOnItemClickListener = mOnItemClickLitener
    }
    /** 长按候选（「忘记该词」入口，与候选栏一致） */
    fun setOnItemLongClickLitener(l: OnRecyclerItemClickListener?) {
        mOnItemLongClickListener = l
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val view = inflater.inflate(R.layout.sdk_item_recyclerview_candidates, parent, false)
        return SymbolHolder(view)
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val cand = DecodingInfo.candidates[position]
        // 与候选栏同款：comment（万象的辅助码/读音、造词标记 *）跟在词后面、字号小一点。
        // 之前这里只渲染 text —— 开了「辅助码」后候选栏有、下拉候选界面没有（用户反馈）。
        if (cand.comment.isEmpty()) {
            holder.textView.text = cand.text
        } else {
            val s = android.text.SpannableString("${cand.text} ${cand.comment}")
            s.setSpan(
                android.text.style.RelativeSizeSpan(0.7f),
                cand.text.length, s.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            holder.textView.text = s
        }
        holder.textView.setOnClickListener { view: View? ->
            mOnItemClickListener?.onItemClick(this@CandidatesAdapter, view, position)
        }
        if (mOnItemLongClickListener != null) {
            holder.textView.setOnLongClickListener { view: View? ->
                mOnItemLongClickListener!!.onItemClick(this@CandidatesAdapter, view, position)
                true
            }
        }
    }

    override fun getItemCount(): Int {
        return DecodingInfo.candidateSize
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        var textView: EmojiTextView = view.findViewById(R.id.gv_candidates_item)

        init {
            textView.setTextColor(textColor)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.candidateTextSize)
        }
    }
}
