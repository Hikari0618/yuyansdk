package com.yuyan.imemodule.view

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.CandidatesBarAdapter
import com.yuyan.imemodule.adapter.CandidatesMenuAdapter
import com.yuyan.imemodule.callback.CandidateViewListener
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.imemodule.data.menuSkbFunsPreset
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.entity.SkbFunItem
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.InputBaseContainer
import com.yuyan.imemodule.manager.layout.CustomLinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.dimensions.dp

/**
 * 候选词集装箱
 */
class CandidatesBar(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs) {

    private lateinit var mCvListener: CandidateViewListener // 候选词视图监听器
    private lateinit var mRightArrowBtn: ImageView // 右边箭头按钮
    private lateinit var mMenuRightArrowBtn: ImageView
    private lateinit var mCandidatesDataContainer: LinearLayout //候选词视图
    private lateinit var mCandidatesMenuContainer: LinearLayout //控制菜单视图
    private lateinit var mComposingView: TextView // 组成字符串的View，用于显示输入的拼音。
    private var mCaretPos = -1        // 组合区光标位置（去掉分隔符后的下标），-1 = 未设置
    private var mCaretBaseText = ""   // 设置光标时的组合串，串一变标记作废
    private var mComposingExpanded = false  // 待编辑区是否已放大（点击定位后放大，上屏恢复）
    private lateinit var mRVCandidates: RecyclerView    //候选词列表
    private lateinit var mIvMenuSetting: ImageView
    private lateinit var mLlContainer: LinearLayout
    private lateinit var mFlowerType: TextView
    private lateinit var mCandidatesAdapter: CandidatesBarAdapter
    private lateinit var mRVContainerMenu:RecyclerView   // 候选词栏菜单
    private lateinit var mCandidatesMenuAdapter: CandidatesMenuAdapter
    private lateinit var candidatesData: LinearLayout //候选词视图
    private var activeCandNo:Int = 0

    fun initialize(cvListener: CandidateViewListener) {
        mCvListener = cvListener
        initMenuView()
        initCandidateView()
    }

    // 初始化候选词界面
    private fun initCandidateView() {
        if(!::mCandidatesDataContainer.isInitialized) {
            mCandidatesDataContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = GONE
            }
            mComposingView = TextView(context).apply {
                includeFontPadding = false
                setPadding(dp(10), 0, dp(10), 0)
                // 点击待编辑区里的字母 → 光标移到该字母后面（方便改前面的错字）
                setOnTouchListener { v, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        moveCaretByTouch(event.x, event.y)
                        v.performClick()
                    }
                    true
                }
            }
            candidatesData = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mRightArrowBtn = ImageView(context).apply {
                isClickable = true
                isEnabled = true
                setImageResource(R.drawable.sdk_level_list_candidates_display)
            }
            mRVCandidates = RecyclerView(context).apply {
                setItemAnimator(null)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                layoutManager =
                    CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            mCandidatesAdapter = CandidatesBarAdapter(context)
            mCandidatesAdapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
                mCvListener.onClickChoice(position)
            }
            mRVCandidates.setAdapter(mCandidatesAdapter)
            mRVCandidates.addOnScrollListener(object : OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                        DecodingInfo.activeCandidateBar =
                            layoutManager.findLastVisibleItemPosition()
                        val itemCount = recyclerView.adapter?.itemCount
                        if (KeyboardManager.instance.currentContainer !is CandidatesContainer && itemCount != null && DecodingInfo.activeCandidateBar >= itemCount - 1) {
                            DecodingInfo.nextPageCandidates
                        }
                    }
                }
            })
            mCandidatesDataContainer.addView(mComposingView)
            mCandidatesDataContainer.addView(candidatesData)
            this.addView(mCandidatesDataContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            (mRightArrowBtn.parent as ViewGroup).removeView(mRightArrowBtn)
            (mRVCandidates.parent as ViewGroup).removeView(mRVCandidates)
        }
        var candidatesHeight = instance.heightForCandidates
        mComposingView.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, instance.heightForcomposing)
        mRightArrowBtn.layoutParams = LinearLayout.LayoutParams(candidatesHeight, candidatesHeight, 0f).apply { marginEnd = dp(10) }
        candidatesData.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, candidatesHeight)
        mRightArrowBtn.setOnClickListener { view: View ->
            when (val level = (view as ImageView).drawable.level) {
                2 -> mCvListener.onClickClearCandidate()
                else -> {
                    mCvListener.onClickMore(level)
                    view.drawable.setLevel(1 - level)
                }
            }
        }
        val oneHandedModSwitch = AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
        val oneHandedMod = AppPrefs.getInstance().keyboardSetting.oneHandedMod.getValue()
        if (oneHandedModSwitch && oneHandedMod == KeyboardOneHandedMod.LEFT) {
            candidatesData.addView(mRightArrowBtn)
            candidatesData.addView(mRVCandidates, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, candidatesHeight, 1f))
        } else {
            candidatesData.addView(mRVCandidates, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, candidatesHeight, 1f))
            candidatesData.addView(mRightArrowBtn)
        }
        mComposingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.composingTextSize)
        mCandidatesAdapter.notifyChanged()
    }

    //初始化标题栏
    fun initMenuView() {
        if(!::mCandidatesMenuContainer.isInitialized) {
            this.removeAllViews()
            mCandidatesMenuContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mIvMenuSetting = ImageView(context).apply {
                setImageResource(R.drawable.sdk_level_candidates_menu_left)
                isClickable = true
                isEnabled = true
                setOnClickListener { mCvListener.onClickMenu(SkbMenuMode.SettingsMenu) }
            }
            mLlContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mFlowerType = TextView(context).apply {
                setTextColor(ThemeManager.activeTheme.keyTextColor)
                setPadding(dp(10), 0, 0, 0)
            }
            val flowerTypefaces = arrayOf(FlowerTypefaceMode.Mars, FlowerTypefaceMode.FlowerVine, FlowerTypefaceMode.Messy, FlowerTypefaceMode.Germinate,
                FlowerTypefaceMode.Fog,FlowerTypefaceMode.ProhibitAccess, FlowerTypefaceMode.Grass, FlowerTypefaceMode.Wind, FlowerTypefaceMode.Disabled)
            val flowerTypefacesName = resources.getStringArray(R.array.FlowerTypeface)
            if (CustomConstant.flowerTypeface == FlowerTypefaceMode.Disabled) {
                mLlContainer.visibility = GONE
            } else {
                mFlowerType.text =
                    flowerTypefacesName[flowerTypefaces.indexOf(CustomConstant.flowerTypeface)]
            }
            mFlowerType.setOnClickListener { _: View ->
                val popupMenu = PopupMenu(context, mLlContainer).apply {
                    menuInflater.inflate(R.menu.flower_typeface_menu, menu)
                    setOnMenuItemClickListener { menuItem ->
                        val ids = listOf(R.id.flower_type_mars, R.id.flower_type_flowervine, R.id.flower_type_messy, R.id.flower_type_grminate,
                            R.id.flower_type_fog, R.id.flower_type_prohibitaccess, R.id.flower_type_grass, R.id.flower_type_wind, R.id.flower_type_disabled
                        )
                        val position = ids.indexOf(menuItem.itemId)
                        val select = flowerTypefaces[position]
                        mFlowerType.text = flowerTypefacesName[position]
                        CustomConstant.flowerTypeface = select
                        if (select == FlowerTypefaceMode.Disabled) {
                            mLlContainer.visibility = GONE
                        }
                        mCandidatesMenuAdapter.notifyChanged()// 刷新菜单栏
                        false
                    }
                }
                popupMenu.show()
            }
            mLlContainer.addView(mFlowerType, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            mRVContainerMenu = RecyclerView(context).apply {
                setItemAnimator(null)
                layoutManager = CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, true)
            }
            mCandidatesMenuAdapter = CandidatesMenuAdapter(context)
            mCandidatesMenuAdapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, view: View?, position: Int ->
                val skbMenuMode = mCandidatesMenuAdapter.getMenuMode(position)
                if (skbMenuMode != null) onClickMenu(skbMenuMode, view)
            }
            mRVContainerMenu.setAdapter(mCandidatesMenuAdapter)
            mMenuRightArrowBtn = ImageView(context).apply {
                isClickable = true
                isEnabled = true
                setImageResource(R.drawable.ic_menu_arrow_down)
            }
            mMenuRightArrowBtn.setOnClickListener { _: View ->
                mCvListener.onClickMenu(SkbMenuMode.CloseSKB)
            }
            mCandidatesMenuContainer.addView(mIvMenuSetting)
            mCandidatesMenuContainer.addView(mLlContainer)
            mCandidatesMenuContainer.addView(mRVContainerMenu)
            mCandidatesMenuContainer.addView(mMenuRightArrowBtn)
            this.addView(
                mCandidatesMenuContainer,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
        var menuHeight = (instance.heightForCandidatesArea * 0.8).toInt()
        mFlowerType.textSize = instance.candidateTextSize
        mIvMenuSetting.layoutParams = LinearLayout.LayoutParams(menuHeight, menuHeight, 0f).apply { marginStart = dp(10) }
        mMenuRightArrowBtn.layoutParams = LinearLayout.LayoutParams(menuHeight, menuHeight, 0f).apply { marginEnd = dp(10) }
        mLlContainer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, menuHeight,0f)
        mRVContainerMenu.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, menuHeight, 1f)
        mCandidatesMenuAdapter.notifyChanged()  // 点击下拉菜单后，需要刷新菜单栏
    }

    private fun onClickMenu(skbMenuMode: SkbMenuMode, view: View?) {
        if(skbMenuMode == SkbMenuMode.ClearClipBoard){
            val contextWrapper = ContextThemeWrapper(context, R.style.Theme_AppTheme)
            val popupMenu = PopupMenu(contextWrapper, view).apply {
                menuInflater.inflate(R.menu.clear_clipboard, menu)
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.clear -> {
                            mCvListener.onClickClearClipBoard()
                        }
                    }
                    false
                }
            }
            popupMenu.show()
        } else {
            mCvListener.onClickMenu(skbMenuMode)
        }
    }

    // 增加窗口防抖机制
    private var pendingMenuJob: Job? = null
    private val serviceScope = MainScope()
    fun scheduleShowCandidates() {
        if (!DecodingInfo.isCandidatesEmpty) {
            pendingMenuJob?.cancel()
            pendingMenuJob = null
            showCandidates()
            return
        }
        if (pendingMenuJob?.isActive == true) return
        pendingMenuJob = serviceScope.launch {
            delay(100)
            showCandidates()
            pendingMenuJob = null
        }
    }

    /** 待编辑区文本（点击定位的光标用 | 标出来） */
    private fun refreshComposingText() {
        val raw = DecodingInfo.composingStrForDisplay
        // 组合串变了（又按了键）→ 之前点出来的光标标记作废
        if (mCaretPos >= 0 && raw != mCaretBaseText) mCaretPos = -1
        // 上屏了（组合清空）→ 待编辑区恢复原大小
        if (raw.isEmpty()) setComposingExpanded(false)
        if (mCaretPos < 0 || raw.isEmpty()) {
            mComposingView.text = raw
            return
        }
        // mCaretPos 是去掉分隔符后的下标，映射回显示串
        var idx = 0
        var cnt = 0
        while (idx < raw.length && cnt < mCaretPos) {
            val c = raw[idx]
            if (c != ' ' && c != '\'') cnt++
            idx++
        }
        mComposingView.text = raw.substring(0, idx) + "|" + raw.substring(idx)
    }

    /** 点击待编辑区某个字母：把组合区光标移到该字母后面 */
    private fun moveCaretByTouch(x: Float, y: Float) {
        val raw = DecodingInfo.composingStrForDisplay
        if (raw.isEmpty()) return
        val lay: android.text.Layout? = mComposingView.layout
        if (lay == null) return
        val shown = mComposingView.text?.toString() ?: return
        // Layout 不含 padding，坐标要先扣掉内边距
        val px = x - mComposingView.paddingLeft
        val py = y - mComposingView.paddingTop
        var off = lay.getOffsetForHorizontal(lay.getLineForVertical(py.toInt()), px)
        // 减掉自己插的光标标记
        val marker = shown.indexOf('|')
        if (marker in 0 until off) off -= 1
        off = off.coerceIn(0, raw.length)
        // 显示串里的空格/撇号只是分隔符，引擎里的真实下标要去掉它们
        val caret = raw.substring(0, off).count { it != ' ' && it != '\'' }
        if (com.yuyan.inputmethod.core.Rime.setCaretPos(caret)) {
            mCaretPos = caret
            mCaretBaseText = raw
            // 点击定位后把待编辑区放大，方便继续精确修改（上屏后自动恢复）
            setComposingExpanded(true)
            DecodingInfo.updateDecodingCandidate()
            showCandidates()
        }
    }

    /** 待编辑区放大/恢复：点击定位后放大，组合清空（上屏）后恢复 */
    private fun setComposingExpanded(expand: Boolean) {
        if (mComposingExpanded == expand) return
        mComposingExpanded = expand
        // 待编辑区变高、候选行同步变矮：候选栏总高度不变，
        // 键盘和候选词的位置都不动（否则候选词会被键盘顶掉/盖住）
        val total = instance.heightForcomposing + instance.heightForCandidates
        val composingH = if (expand) total * 2 / 3 else instance.heightForcomposing
        val candH = total - composingH
        mComposingView.layoutParams =
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, composingH)
        mComposingView.setTextSize(
            TypedValue.COMPLEX_UNIT_DIP,
            if (expand) instance.composingTextSize * 1.8f else instance.composingTextSize
        )
        candidatesData.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, candH)
        requestLayout()
    }

    /**
     * 显示候选词
     */
    fun showCandidates() {
        refreshComposingText()
        val container = KeyboardManager.instance.currentContainer
        mIvMenuSetting.drawable.setLevel( if(container is InputBaseContainer) 0 else 1)
        if (container is ClipBoardContainer) {
            showViewVisibility(mCandidatesMenuContainer)
            mCandidatesMenuAdapter.items = if(container.getMenuMode() == SkbMenuMode.ClipBoard) {
                listOf(menuSkbFunsPreset[SkbMenuMode.ClearClipBoard]!!, menuSkbFunsPreset[SkbMenuMode.ClipBoard]!!, menuSkbFunsPreset[SkbMenuMode.Phrases]!!, menuSkbFunsPreset[SkbMenuMode.LockClipBoard]!!)
            } else {
                listOf(menuSkbFunsPreset[SkbMenuMode.AddPhrases]!!, menuSkbFunsPreset[SkbMenuMode.ClipBoard]!!, menuSkbFunsPreset[SkbMenuMode.Phrases]!!, menuSkbFunsPreset[SkbMenuMode.LockClipBoard]!!)
            }
        } else if (DecodingInfo.isCandidatesEmpty) {
            mRightArrowBtn.drawable.setLevel(0)
            // 引擎仍在组合（万象的 / 命令、未成词的拼音等）却没有候选时，必须继续显示组合串。
            // 组合串 mComposingView 是 mCandidatesDataContainer 的子视图，此前这里直接切到
            // 菜单条，把组合串一起藏掉了 → 表现为「打几个键待编辑区突然全部消失」。
            if (DecodingInfo.composingStrForDisplay.isNotEmpty()) {
                showViewVisibility(mCandidatesDataContainer)
            } else {
                showViewVisibility(mCandidatesMenuContainer)
                val mFunItems: MutableList<SkbFunItem> = mutableListOf()
                val barMenus = DataBaseKT.instance.skbFunDao().getALlBarMenu()
                for (item in barMenus) {
                    val skbMenuMode = SkbMenuMode.decode(item.name)
                    val skbFunItem = menuSkbFunsPreset[skbMenuMode]
                    if (skbFunItem != null) {
                        mFunItems.add(skbFunItem)
                    }
                }
                mCandidatesMenuAdapter.items = mFunItems
            }
        } else {
            if (DecodingInfo.candidateSize > DecodingInfo.activeCandidateBar) mRVCandidates.layoutManager?.scrollToPosition(DecodingInfo.activeCandidateBar)
            showViewVisibility(mCandidatesDataContainer)
            mRightArrowBtn.drawable.setLevel(if (DecodingInfo.isAssociate) 2 else if (KeyboardManager.instance.currentContainer is CandidatesContainer) 1 else 0)
        }
        activeCandNo = 0
        mCandidatesAdapter.activeCandidates(activeCandNo)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
    }

    /**
     * 显示表情
     */
    fun showEmoji() {
        showViewVisibility(mCandidatesMenuContainer)
        mCandidatesMenuAdapter.items = listOf(menuSkbFunsPreset[SkbMenuMode.Emoticon]!!,menuSkbFunsPreset[SkbMenuMode.Emojicon]!!)
        activeCandNo = 0
        mCandidatesAdapter.activeCandidates(activeCandNo)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
    }

    /**
     * 更新激活的候选词
     */
    fun updateActiveCandidateNo(keyCode: Int) {
        if (!DecodingInfo.isCandidatesEmpty) {
            when(keyCode){
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if(--activeCandNo <= 0) activeCandNo = 0
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if(++activeCandNo > DecodingInfo.candidateSize) activeCandNo = DecodingInfo.candidateSize
                }
            }
            mCandidatesAdapter.activeCandidates(activeCandNo)
            mCandidatesAdapter.notifyChanged()
            mRVCandidates.layoutManager?.scrollToPosition(if(activeCandNo - 1 > 0) activeCandNo - 1 else 0 )
        }
    }

    /**
     * 获取激活的候选词
     */
    fun getActiveCandNo():Int {
        return if(activeCandNo > 0) activeCandNo -1 else 0
    }

    /**
     * 是否操作选词
     */
    fun isActiveCand():Boolean {
        return activeCandNo > 0
    }

    /**
     * 选择花漾字
     */
    fun showFlowerTypeface() {
        if(CustomConstant.flowerTypeface == FlowerTypefaceMode.Disabled) {
            mLlContainer.visibility = GONE
        } else {
            CustomConstant.flowerTypeface = FlowerTypefaceMode.Mars
            mFlowerType.text = "焱暒妏"
            mLlContainer.visibility = VISIBLE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMeasure = MeasureSpec.makeMeasureSpec(instance.heightForCandidatesArea, MeasureSpec.EXACTLY)
        val widthMeasure = MeasureSpec.makeMeasureSpec(instance.skbWidth, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

    private fun showViewVisibility(candidatesContainer: View) {
        if(candidatesContainer === mCandidatesMenuContainer){
            mCandidatesMenuContainer.visibility = VISIBLE
            mCandidatesDataContainer.visibility = GONE
        } else {
            mCandidatesMenuContainer.visibility = GONE
            mCandidatesDataContainer.visibility = VISIBLE
        }
    }

    // 刷新主题
    fun updateTheme(textColor: Int) {
        initMenuView()
        initCandidateView()
        mIvMenuSetting.setImageResource(R.drawable.sdk_level_candidates_menu_left)
        mComposingView.setTextColor(textColor)
        mRightArrowBtn.drawable.setTint(textColor)
        mMenuRightArrowBtn.drawable.setTint(textColor)
        mIvMenuSetting.drawable.setTint(textColor)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
        mFlowerType.setTextColor(textColor)
    }
}
