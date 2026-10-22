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
import com.yuyan.imemodule.view.ImeMenuItem
import com.yuyan.imemodule.view.showImeItemMenu
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
    // 容器按 KeyboardType 缓存复用，切走/回来不会重建视图。重新可见时的自动刷新用它去重，
    // 避免与显式刷新（SettingsMenuClick 里的 showClipBoardView）在同一帧里连刷两次导致列表闪烁。
    private var pendingRefreshOnShow = false

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
        // 显式刷新一到，就撤掉「重新可见自动刷新」的标记，保证一帧只刷一次（见 scheduleRefreshOnShow）
        pendingRefreshOnShow = false
        // 空列表提示的取色只在 initView 里做过一次；容器是缓存的，换主题后不补刷会一直是旧配色。
        // 这里在每次取数时顺手刷一遍（showClipBoardView 覆盖显式刷新与重新可见两条路径）。
        mTVLable?.setTextColor(activeTheme.keyTextColor)
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

    /**
     * 容器是按 KeyboardType 缓存的，切走时只是 GONE（甚至被 removeView），回来时不会重建 ——
     * 而剪贴板/短语数据可能在别处被改过（典型：长按条目进编辑界面改完返回），
     * DB 变了但缓存的视图不会自己重取，于是「改了没反应」。所以重新可见时重取一次数据。
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // 用「自身可见性」判断：回调里的 visibility 是发生变化的那个 view 的 ——
        // 输入法重新显示时根视图变 VISIBLE，本容器即使已是 GONE 也会收到 VISIBLE 回调。
        if (this.visibility == View.VISIBLE && isAttachedToWindow) {
            scheduleRefreshOnShow()
        }
    }

    /**
     * 重新可见时的刷新。不能在这里同步直接调 showClipBoardView：
     * SettingsMenuClick 先 switchKeyboard 显示本容器（这一步会触发 onVisibilityChanged），
     * 紧接着还会显式再调一次 showClipBoardView，同步刷就会连刷两次、列表闪一下。
     * 这里只打标记并 post 到下一帧；期间若有显式刷新（showClipBoardView 会清标记），
     * 这次就跳过，从而保证一帧只刷一次。
     */
    private fun scheduleRefreshOnShow() {
        if (itemMode == null) return // 首次显示时 itemMode 还没设，交给显式的 showClipBoardView
        if (pendingRefreshOnShow) return
        pendingRefreshOnShow = true
        post {
            if (pendingRefreshOnShow) {
                pendingRefreshOnShow = false
                itemMode?.let { showClipBoardView(it) }
            }
        }
    }

    /** 长按剪贴板条目的菜单：编辑 / 分享 / 分词 / 收藏（置顶、删除仍走左滑） */
    private fun showItemMenu(anchor: View, item: Clipboard) {
        // 必须用非聚焦弹窗（ImeItemMenu.kt）：android.widget.PopupMenu 的弹窗是 focusable 的，
        // 在输入法里弹出会抢走宿主应用的窗口焦点；宿主（Google 搜索首页的搜索框就是典型）
        // 一收到失焦就收起键盘 —— 表现是「长按后键盘消失、菜单闪一下即不见」。
        val items = mutableListOf(
            ImeMenuItem("编辑") {
                // 短语模式必须走短语自己的编辑路径（数据源是 phrase 表，不是 clipboard 表）：
                // 拿剪贴板那条路径去编辑短语，会 findByContent 查不到行、保存整段跳过（改了没反应）。
                if (itemMode == SkbMenuMode.Phrases) editPhraseItem(item) else editItem(item)
            },
            ImeMenuItem("分享") { shareItem(item) },
            ImeMenuItem("分词") { segmentItem(item) },
        )
        // 「收藏」只对剪贴板条目有意义：短语模式下这条本来就是短语，再收藏只是重复/置顶，
        // 语义上说不通，故该模式下不显示这一项。
        if (itemMode != SkbMenuMode.Phrases) {
            items.add(ImeMenuItem("收藏") { collectItem(item) })
        }
        showImeItemMenu(anchor, items)
    }

    /**
     * 短语模式下长按「编辑」：复用左滑「编辑」走的同一条路径 ——
     * onSettingsMenuClick(AddPhrases, 现有短语)。它会先把该短语从 phrase 表删除，
     * 再把内容填进「新增短语」界面供修改保存，改完回短语表，不再是「改了没反应」。
     */
    private fun editPhraseItem(item: Clipboard) {
        runCatching {
            inputView.onSettingsMenuClick(
                SkbMenuMode.AddPhrases,
                DataBaseKT.instance.phraseDao().queryByContent(item.content)
            )
        }
    }

    /**
     * 编辑：与常用语走【同一个】窗口内编辑器（EditPhrasesView），数据源换成 clipboard 表。
     *
     * 原来这里起的是独立 Activity（ClipEditActivity），其注释理由是「输入法没法给自己窗口里的
     * 输入框打字」—— 但那个限制已被 ImeEditText 解决（常用语编辑一直就是这么做的），
     * 而且 Activity 方案还有副作用：编辑页返回后列表数据是旧的，得专门做一次重新读库。
     * 统一成窗口内编辑后，两类条目体验一致，也不必再绕这一圈。
     */
    private fun editItem(item: Clipboard) {
        runCatching { inputView.onClipEditClick(item.content) }
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
        com.yuyan.inputmethod.util.ImeLog.d("[seg] 剪贴板菜单点分词 pos len=${item.content.length}")
        if (DecodingInfo.segmentClipboardSuggestion(item.content)) {
            com.yuyan.inputmethod.util.ImeLog.d("[seg] 分词页面已打开")
        } else {
            com.yuyan.inputmethod.util.ImeLog.d("[seg] 分词请求被拒绝")
        }
    }

    /** 收藏：存进短语库（语燕的收藏就是短语），重复收藏就置顶，并给出明确提示 */
    private fun collectItem(item: Clipboard) {
        val content = item.content
        if (content.isBlank()) return
        val dao = DataBaseKT.instance.phraseDao()
        val message = runCatching {
            val exist = dao.queryByContent(content)
            if (exist != null) {
                exist.isKeep = 1
                dao.update(exist)
                "已在短语中，已置顶"
            } else {
                dao.insert(Phrase(content = content, t9 = "", qwerty = "", lx17 = ""))
                "已收藏到短语"
            }
        }.getOrElse { "收藏失败：${it.message}" }
        // 用 App 上下文弹 Toast（输入法服务上下文在部分 ROM 上会被吞）
        Toast.makeText(
            com.yuyan.imemodule.application.Launcher.instance.context, message, Toast.LENGTH_SHORT
        ).show()
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
