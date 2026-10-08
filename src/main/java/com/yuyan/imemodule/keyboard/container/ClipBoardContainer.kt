package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.ClipBoardAdapter
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.ui.activity.ClipEditActivity
import com.yuyan.imemodule.libs.recyclerview.SwipeMenu
import com.yuyan.imemodule.libs.recyclerview.SwipeMenuBridge
import com.yuyan.imemodule.libs.recyclerview.SwipeMenuItem
import com.yuyan.imemodule.libs.recyclerview.SwipeRecyclerView
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.ClipboardLayoutMode
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.manager.layout.CustomGridLayoutManager
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import splitties.dimensions.dp
import splitties.views.textResource
import kotlin.math.ceil

/**
 * 粘贴板列表键盘容器
 *
 * 使用RecyclerView实现垂直ListView列表布局。
 */
@SuppressLint("ViewConstructor")
class ClipBoardContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    private val mPaint : Paint = Paint() // 测量字符串长度
    private val mRVSymbolsView: SwipeRecyclerView = SwipeRecyclerView(context)
    private var mTVLable: TextView? = null
    private var itemMode:SkbMenuMode? = null

    init {
        mPaint.textSize = dp(22f)
        initView(context)
    }

    private fun initView(context: Context) {
        mTVLable = TextView(context).apply {
            textResource = R.string.clipboard_empty_ltip
            gravity = Gravity.CENTER
            setTextColor(activeTheme.keyTextColor)
            textSize = instance.candidateTextSize
        }
        mRVSymbolsView.setItemAnimator(null)
        val layoutParams2 = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        mRVSymbolsView.layoutParams = layoutParams2
        this.addView(mRVSymbolsView)
    }

    /**
     * 显示候选词界面 , 点击候选词时执行
     */
    fun showClipBoardView(item: SkbMenuMode) {
        CustomConstant.lockClipBoardEnable = false
        itemMode = item
        mRVSymbolsView.setHasFixedSize(true)
        val copyContents : MutableList<Clipboard> =
            if(itemMode == SkbMenuMode.ClipBoard) {
                DataBaseKT.instance.clipboardDao().getAll().toMutableList()
            } else {
                DataBaseKT.instance.phraseDao().getAll().map { line -> Clipboard(line.content) }.toMutableList()
            }
        val manager =  when (AppPrefs.getInstance().clipboard.clipboardLayoutCompact.getValue()){
            ClipboardLayoutMode.ListView ->  LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            ClipboardLayoutMode.GridView -> CustomGridLayoutManager(context, 2)
            ClipboardLayoutMode.FlexboxView -> {
                calculateColumn(copyContents)
                CustomGridLayoutManager(context, 6).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(i: Int) = mHashMapSymbols[i] ?: 1
                    }
                }
            }
        }
        mRVSymbolsView.setLayoutManager(manager)
        val viewParent = mTVLable?.parent
        if (viewParent != null) {
            (viewParent as ViewGroup).removeView(mTVLable)
        }
        if(copyContents.isEmpty()){
            this.addView(mTVLable, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val adapter = ClipBoardAdapter(context, copyContents)
        mRVSymbolsView.setAdapter(null)
        // 长按条目：编辑 / 分享 / 分词 / 收藏（置顶、删除仍走左滑）
        // 必须用 SwipeRecyclerView 自己的长按监听：它包了一层 adapter，条目视图上的长按会被它吃掉
        mRVSymbolsView.setOnItemLongClickListener { itemView, position ->
            showItemMenu(itemView, copyContents[position])
        }
        mRVSymbolsView.setOnItemClickListener{ _: View?, position: Int ->
            inputView.responseLongKeyEvent(Pair(PopupMenuMode.Text, copyContents[position].content))
            if(!CustomConstant.lockClipBoardEnable)KeyboardManager.instance.switchKeyboard()
        }
        mRVSymbolsView.setSwipeMenuCreator{ _: SwipeMenu, rightMenu: SwipeMenu, position: Int ->
            val topItem = SwipeMenuItem(mContext).apply {
                setImage(if(itemMode == SkbMenuMode.ClipBoard) {
                    if(copyContents[position].isKeep == 1)R.drawable.ic_baseline_untop_circle_32 else R.drawable.ic_baseline_top_circle_32 }
                else R.drawable.ic_menu_edit)
                image.setTint(activeTheme.keyTextColor)
            }
            rightMenu.addMenuItem(topItem)
            val deleteItem = SwipeMenuItem(mContext).apply {
                setImage(R.drawable.ic_menu_delete)
                image.setTint(activeTheme.keyTextColor)
            }
            rightMenu.addMenuItem(deleteItem)
        }
        mRVSymbolsView.setOnItemMenuClickListener { menuBridge: SwipeMenuBridge, position: Int ->
            menuBridge.closeMenu()
            if(itemMode == SkbMenuMode.ClipBoard){
                if(menuBridge.position == 0) {
                    val data: Clipboard = copyContents[position]
                    data.isKeep = 1 - data.isKeep
                    DataBaseKT.instance.clipboardDao().update(data)
                    showClipBoardView(SkbMenuMode.ClipBoard)
                } else if(menuBridge.position == 1){
                    val data: Clipboard = copyContents.removeAt(position)
                    DataBaseKT.instance.clipboardDao().deleteByContent(data.content)
                    mRVSymbolsView.adapter?.notifyItemRemoved(position)
                }
            } else {
                val content = copyContents[position].content
                if(menuBridge.position == 0) {
                    inputView.onSettingsMenuClick(SkbMenuMode.AddPhrases, DataBaseKT.instance.phraseDao().queryByContent(content))
                } else if(menuBridge.position == 1){
                    DataBaseKT.instance.phraseDao().deleteByContent(content)
                    showClipBoardView(SkbMenuMode.Phrases)
                }
            }
        }
        mRVSymbolsView.setAdapter(adapter)
    }

    /** 长按剪贴板条目的菜单：编辑 / 分享 / 分词 / 收藏（置顶、删除仍走左滑） */
    private fun showItemMenu(anchor: View, item: Clipboard) {
        val menu = LinearLayout(mContext).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(activeTheme.keyBackgroundColor)
                setCornerRadius(ThemeManager.prefs.keyRadius.getValue().toFloat())
            }
        }
        val actions: List<Pair<String, () -> Unit>> = listOf(
            "编辑" to { editItem(item) },
            "分享" to { shareItem(item) },
            "分词" to { segmentItem(item) },
            "收藏" to { collectItem(item) },
        )
        val popup = PopupWindow(
            menu, dp(88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        actions.forEach { (label, action) ->
            menu.addView(TextView(mContext).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(activeTheme.keyTextColor)
                textSize = instance.candidateTextSize.toFloat()
                setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        popup.showAsDropDown(anchor)
    }

    /** 编辑：交给独立 Activity —— 输入法没法给自己窗口里的输入框打字 */
    private fun editItem(item: Clipboard) {
        runCatching {
            mContext.startActivity(Intent(mContext, ClipEditActivity::class.java).apply {
                putExtra(ClipEditActivity.EXTRA_CONTENT, item.content)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    /** 分享：交给系统选择器 */
    private fun shareItem(item: Clipboard) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, item.content)
            }
            mContext.startActivity(
                Intent.createChooser(send, null).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    /** 分词：切词后当候选显示，回到键盘就能点选上屏 */
    private fun segmentItem(item: Clipboard) {
        if (DecodingInfo.segmentClipboardSuggestion(item.content)) {
            KeyboardManager.instance.switchKeyboard()
        }
    }

    /** 收藏：存进短语（语燕的收藏就是短语库） */
    private fun collectItem(item: Clipboard) {
        val content = item.content
        if (content.isBlank()) return
        runCatching {
            DataBaseKT.instance.phraseDao().insert(
                Phrase(content = content, t9 = "", qwerty = "", lx17 = "")
            )
        }
        Toast.makeText(mContext, "已收藏到短语", Toast.LENGTH_SHORT).show()
    }

    private val mHashMapSymbols = HashMap<Int, Int>() //候选词索引列数对应表
    private fun calculateColumn(data : MutableList<Clipboard>) {
        mHashMapSymbols.clear()
        val itemWidth = instance.skbWidth/6 - dp(10)
        var mCurrentColumn = 0
        val contents = data.map { it.content }
        contents.forEachIndexed { position, candidate ->
            var count = getSymbolsCount(candidate, itemWidth)
            var nextCount = 0
            if (contents.size > position + 1) {
                val nextCandidate = contents[position + 1]
                nextCount = getSymbolsCount(nextCandidate, itemWidth)
            }
            mCurrentColumn = if (mCurrentColumn + count + nextCount > 6) {
                count = 6 - mCurrentColumn
                0
            } else  (mCurrentColumn + count) % 6
            mHashMapSymbols[position] = count
        }
    }

    private fun getSymbolsCount(data: String, itemWidth:Int): Int {
        return if (!TextUtils.isEmpty(data)) ceil(mPaint.measureText(data).div(itemWidth)).toInt() else 0
    }
    fun getMenuMode():SkbMenuMode? {
       return itemMode
    }
}
