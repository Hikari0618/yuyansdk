package com.yuyan.imemodule.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.postDelayed
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.callback.CandidateViewListener
import com.yuyan.imemodule.callback.IResponseKeyEvent
import com.yuyan.imemodule.data.emojicon.EmojiconData.SymbolPreset
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.entity.StringQueue
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.imemodule.keyboard.container.T9TextContainer
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.utils.InputMethodUtil
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.utils.LogUtil
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.view.CandidatesBar
import com.yuyan.imemodule.view.EditPhrasesView
import com.yuyan.imemodule.view.FullDisplayKeyboardBar
import com.yuyan.imemodule.view.popup.PopupComponent
import com.yuyan.imemodule.view.preference.ManagedPreference
import com.yuyan.imemodule.view.widget.LifecycleRelativeLayout
import com.yuyan.inputmethod.CustomEngine
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Kernel
import splitties.views.bottomPadding
import splitties.views.rightPadding
import kotlin.math.absoluteValue

/**
 * 输入法主界面。
 * 包含拼音显示、候选词栏、键盘界面等。
 */
@SuppressLint("ViewConstructor")
class InputView(context: Context, private val service: ImeService) : LifecycleRelativeLayout(context), IResponseKeyEvent {
    private val appPrefs = getInstance()
    private val clipboardItemTimeout = appPrefs.clipboard.clipboardItemTimeout.getValue()
    private var chinesePrediction = true
    var isAddPhrases = false
    private val mChoiceNotifier = ChoiceNotifier()
    var mSkbRoot: RelativeLayout
    var mSkbCandidatesBarView: CandidatesBar
    private var mHoderLayoutLeft: LinearLayout
    private var mHoderLayoutRight: LinearLayout
    private lateinit var mOnehandHoderLayout: LinearLayout
    var mAddPhrasesLayout: EditPhrasesView
    private var mLlKeyboardBottomHolder: LinearLayout
    private var mInputKeyboardContainer: RelativeLayout
    private lateinit var mRightPaddingKey: ManagedPreference.PInt
    private lateinit var mBottomPaddingKey: ManagedPreference.PInt
    private var mFullDisplayKeyboardBar: FullDisplayKeyboardBar? = null
    var hasSelection = false
    var hasSelectionAll = false
    // 记录删除内容
    private val textBeforeCursors = StringQueue(50)

    init {
        LogUtil.d("1111111111111", "InputView init")
        initNavbarBackground(service)
        InputModeSwitcher.reset()
        mSkbRoot = LayoutInflater.from(context).inflate(R.layout.sdk_skb_container, this, false) as RelativeLayout
        addView(mSkbRoot)
        mSkbCandidatesBarView = mSkbRoot.findViewById(R.id.candidates_bar)
        mHoderLayoutLeft = mSkbRoot.findViewById(R.id.ll_skb_holder_layout_left)
        mHoderLayoutRight = mSkbRoot.findViewById(R.id.ll_skb_holder_layout_right)
        mInputKeyboardContainer = mSkbRoot.findViewById(R.id.ll_input_keyboard_container)
        mAddPhrasesLayout = EditPhrasesView(context)
        mLlKeyboardBottomHolder = mSkbRoot.findViewById(R.id.iv_keyboard_holder)
        KeyboardManager.instance.setData(mSkbRoot.findViewById(R.id.skb_input_keyboard_view), this)
        PopupComponent.get().root.let { root ->
            root.parent?.let { (it as ViewGroup).removeView(root) }
            addView(root, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                addRule(ALIGN_BOTTOM, mSkbRoot.id)
                addRule(ALIGN_LEFT, mSkbRoot.id)
            })
        }
        DecodingInfo.candidatesLiveData.observe(this) {
            updateCandidateBar()
            (KeyboardManager.instance.currentContainer as? CandidatesContainer)?.showCandidatesView()
        }
        initView(context)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initView(context: Context) {
        LogUtil.d("1111111111111", "InputView initView")
        if (isAddPhrases) {
            if (mAddPhrasesLayout.parent == null) {
                addView(mAddPhrasesLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    addRule(ABOVE, mSkbRoot.id)
                    addRule(ALIGN_LEFT, mSkbRoot.id)
                })
                mAddPhrasesLayout.handleAddPhrasesView()
            }
        } else {
            removeView(mAddPhrasesLayout)
        }
        mSkbCandidatesBarView.initialize(mChoiceNotifier)
        // 点建议行里被复制的内容 = 直接上屏（原来它是候选词，改成自定义行后丢了点击）
        mSkbCandidatesBarView.onSuggestionClick = { content ->
            service.currentInputConnection?.commitText(content, 1)
        }
        // 候选栏内容变化时让 Service 重算 insets（触摸区跟随可见内容）
        mSkbCandidatesBarView.onContentChanged = { service.updateInputViewShown() }
        val env = EnvironmentSingleton.instance
        val keyboardSetting = appPrefs.keyboardSetting
        val oneHandedModSwitch = keyboardSetting.oneHandedModSwitch.getValue()
        val oneHandedMod = keyboardSetting.oneHandedMod.getValue()
        if (::mOnehandHoderLayout.isInitialized) mOnehandHoderLayout.visibility = GONE
        if (oneHandedModSwitch) {
            mOnehandHoderLayout = if (oneHandedMod == KeyboardOneHandedMod.LEFT) mHoderLayoutRight else mHoderLayoutLeft
            mOnehandHoderLayout.apply {
                visibility = VISIBLE
                get(0).setOnClickListener { onClick(it) }
                get(1).setOnClickListener { onClick(it) }
                (get(1) as ImageButton).setImageResource(
                    if (oneHandedMod == KeyboardOneHandedMod.LEFT) R.drawable.ic_menu_one_hand_right else R.drawable.ic_menu_one_hand
                )
                layoutParams = layoutParams.apply {
                    width = env.holderWidth
                    height = env.skbHeight
                }
            }
        }
        mLlKeyboardBottomHolder.removeAllViews()
        mLlKeyboardBottomHolder.layoutParams.width = env.skbWidth
        mInputKeyboardContainer.layoutParams.width = env.inputAreaWidth
        if (env.keyboardModeFloat) {
            val isLand = env.isLandscape
            val internal = appPrefs.internal
            mBottomPaddingKey = if (isLand) internal.keyboardBottomPaddingLandscapeFloat else internal.keyboardBottomPaddingFloat
            mRightPaddingKey = if (isLand) internal.keyboardRightPaddingLandscapeFloat else internal.keyboardRightPaddingFloat

            // 应用存储位移时只在「视图真正布局完成」后才 clamp：
            // 布局过程中的瞬时 height 偏小会把位移压小（症状：打字时键盘被往下压、下缘被裁）。
            // 拖动路径已经保证写入值合法，这里的 clamp 只是兜底。
            val kbH = maxOf(mSkbRoot.height, designKeyboardHeight())
            val kbW = mSkbRoot.width
            val canClamp = isLaidOut && kbH > 0 && kbH < height
            val maxBottom = if (canClamp) height - kbH else Int.MAX_VALUE
            val maxRight = if (isLaidOut && kbW > 0 && kbW < width) width - kbW else Int.MAX_VALUE
            val storedBottom = mBottomPaddingKey.getValue()
            val storedRight = mRightPaddingKey.getValue()
            bottomPadding = storedBottom.coerceIn(0, maxBottom)
            rightPadding = storedRight.coerceIn(0, maxRight)
            com.yuyan.inputmethod.util.ImeLog.d(
                "[pad] 应用存储位移 stored=$storedBottom→$bottomPadding storedR=$storedRight→$rightPadding" +
                    " laidOut=$isLaidOut thisH=$height rootH=$kbH maxB=$maxBottom"
            )
            mSkbRoot.bottomPadding = 0
            mSkbRoot.rightPadding = 0

            mLlKeyboardBottomHolder.minimumHeight = env.heightForKeyboardMove
            val mIvKeyboardMove = ImageView(context).apply {
                setImageResource(R.drawable.ic_horizontal_line)
                isClickable = true
                isEnabled = true
            }
            mLlKeyboardBottomHolder.addView(mIvKeyboardMove)
            mIvKeyboardMove.setOnTouchListener { _, event -> onMoveKeyboardEvent(event) }
        } else {
            val fullDisplayEnable = appPrefs.internal.fullDisplayKeyboardEnable.getValue()
            if (fullDisplayEnable && !env.isLandscape) {
                mFullDisplayKeyboardBar = FullDisplayKeyboardBar(context, this)
                mLlKeyboardBottomHolder.addView(mFullDisplayKeyboardBar)
                mLlKeyboardBottomHolder.minimumHeight = env.heightForFullDisplayBar + env.systemNavbarWindowsBottom
            } else {
                mLlKeyboardBottomHolder.minimumHeight = env.systemNavbarWindowsBottom
            }
            bottomPadding = 0
            rightPadding = 0
            mBottomPaddingKey = appPrefs.internal.keyboardBottomPadding
            mRightPaddingKey = appPrefs.internal.keyboardRightPadding
            mSkbRoot.bottomPadding = mBottomPaddingKey.getValue()
            mSkbRoot.rightPadding = mRightPaddingKey.getValue()
        }
        // 键盘根尺寸变化埋点：悬浮键盘打字时 region 顶边在 100/233 之间跳（差 133），
        // 而候选栏自己的高度是稳定的（[h2] 只触发一次且 bar=133），所以要逐层打出
        // 根 / 容器 / 候选栏 / 按键区 / 底部 holder 的高度，定位是哪一层在变。
        if (!rootLayoutLogged) {
            rootLayoutLogged = true
            mSkbRoot.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
                if (b - t != ob - ot || r - l != or2 - ol) {
                    com.yuyan.inputmethod.util.ImeLog.d(
                        "[root] h=${b - t} (old=${ob - ot})" +
                            " kbContainer=${mInputKeyboardContainer.height}" +
                            " bar=${mSkbCandidatesBarView.height}/${mSkbCandidatesBarView.visibility}" +
                            " keys=${mInputKeyboardContainer.findViewById<View>(R.id.skb_input_keyboard_view)?.height}" +
                            " bottomHolder=${mLlKeyboardBottomHolder.height}" +
                            " holderMin=${mLlKeyboardBottomHolder.minimumHeight}" +
                            " holderVis=${mLlKeyboardBottomHolder.visibility}" +
                            " holderKids=${mLlKeyboardBottomHolder.childCount}" +
                            " parentLp=${(mInputKeyboardContainer.findViewById<View>(R.id.skb_input_keyboard_view)?.layoutParams)?.height}" +
                            " parentMeas=${(mInputKeyboardContainer.findViewById<View>(R.id.skb_input_keyboard_view) as? View)?.measuredHeight}" +
                            " container=${KeyboardManager.instance.currentContainer?.javaClass?.simpleName}" +
                            " contH=${KeyboardManager.instance.currentContainer?.height}" +
                            " contLp=${KeyboardManager.instance.currentContainer?.layoutParams?.height}" +
                            " skbH=${EnvironmentSingleton.instance.skbHeight}" +
                            " areaH=${EnvironmentSingleton.instance.heightForCandidatesArea}"
                    )
                }
            }
        }
        updateTheme()
    }

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var rightPaddingValue = 0
    private var bottomPaddingValue = 0
    private var mLastAlignFloat: Boolean? = null

    // 窗口高度恢复「原行为」：保持 wrap_content、mSkbRoot 仍底对齐 XML（含全面屏
    // 优化底部条）。之前在 onMeasure 里按 mSkbRoot.bottom 强行收缩窗口高度，导致
    // 窗口高度随候选栏内容变化 → 键盘上下跳，且底部全面屏条被切掉（失效）。
    // 死区问题改由 ImeService 的 touchableRegion 精确圈「候选栏+键盘」解决：
    // 窗口里 region 之外的区域不认领触摸，同样没有死区。

    /**
     * 键盘「应有的」完整高度（设计高度，不受父容器挤压影响）。
     *
     * 悬浮模式下键盘根是 wrap + 底部对齐，可用高度 = 输入视图高度 − 位移：
     * 位移越大 → 可用空间越小 → 键盘被父容器压扁（日志实测按键区 720↔625）。
     * 而拖动上限如果拿「被压扁后的实测高度」来算，会得到更大的上限，于是
     * 「位移越大 → 越扁 → 上限越大」形成自洽循环，两个状态互相喂。
     * 上限必须用这个常量，循环才能断开。
     */
    private fun designKeyboardHeight(): Int {
        val env = EnvironmentSingleton.instance
        return env.skbHeight + env.heightForCandidatesArea +
            env.heightForKeyboardMove + env.systemNavbarWindowsBottom
    }

    private var rootLayoutLogged = false
    private var mSkbRootHeight = 0
    private var mSkbRootWidth = 0

    private fun onMoveKeyboardEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                bottomPaddingValue = mBottomPaddingKey.getValue()
                rightPaddingValue = mRightPaddingKey.getValue()
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                mSkbRootHeight = maxOf(mSkbRoot.height, designKeyboardHeight())
                mSkbRootWidth = mSkbRoot.width
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val env = EnvironmentSingleton.instance

                if (dx.absoluteValue > 10) {
                    rightPaddingValue = (rightPaddingValue - dx.toInt()).coerceIn(0, this.width - mSkbRootWidth)
                    initialTouchX = event.rawX
                    if (env.keyboardModeFloat) rightPadding = rightPaddingValue else mSkbRoot.rightPadding = rightPaddingValue
                }
                if (dy.absoluteValue > 10) {
                    // 上限用 this.height：窗口高度已在 applyInputViewHeight 里设成
                    // MATCH_PARENT（日志 winH=-1），this.height 是稳定的窗口高度
                    //（= 屏幕高 - 状态栏/导航栏），不是当初那个「键盘高 + 位移」的 wrap 值。
                    // 用 mScreenHeight 会偏大（超出窗口可见范围），把键盘顶出窗口顶边。
                    bottomPaddingValue = (bottomPaddingValue - dy.toInt()).coerceIn(0, this.height - mSkbRootHeight)
                    com.yuyan.inputmethod.util.ImeLog.d(
                        "[move] dy=$dy pad=$bottomPaddingValue thisH=$height rootH=$mSkbRootHeight"
                    )
                    initialTouchY = event.rawY
                    if (env.keyboardModeFloat) bottomPadding = bottomPaddingValue else mSkbRoot.bottomPadding = bottomPaddingValue
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mRightPaddingKey.setValue(rightPaddingValue)
                mBottomPaddingKey.setValue(bottomPaddingValue)
            }
        }
        return false
    }

    /** 触摸区应当从哪个屏幕 y 开始：跳过候选栏里当前空着的那部分（透明，透出 App 内容），
     *  只圈住真有内容的区域 + 键盘。空着时若仍圈进触摸区，系统边缘返回手势会被吃掉。 */
    fun touchableTop(): Int {
        val loc = IntArray(2)
        mSkbCandidatesBarView.getLocationOnScreen(loc)
        return loc[1] + mSkbCandidatesBarView.contentTopOffset()
    }

    // 皮肤背景「缩放」结果缓存：scale 一张整屏位图要新建 w*h 的 bitmap（十几 MB），
    // 同主题同尺寸直接复用，避免每次 updateTheme 都重新分配
    private var bgScaleKey: String? = null
    private var bgScaledBitmap: android.graphics.Bitmap? = null

    fun updateTheme() {
        val perfT0 = android.os.SystemClock.elapsedRealtime()
        setBackgroundResource(android.R.color.transparent)
        val activeTheme = ThemeManager.activeTheme
        val keyTextColor = activeTheme.keyTextColor
        val env = EnvironmentSingleton.instance

        val background = activeTheme.backgroundDrawable(ThemeManager.prefs.keyBorder.getValue())
        if (background is BitmapDrawable) {
            val w = env.skbWidth
            val h = env.inputAreaHeight
            val key = "${activeTheme.name}|$w|$h"
            val scaledBitmap = if (key == bgScaleKey && bgScaledBitmap != null) {
                bgScaledBitmap!!
            } else {
                background.bitmap.scale(w, h).also {
                    bgScaleKey = key
                    bgScaledBitmap = it
                }
            }
            mSkbRoot.background = scaledBitmap.toDrawable(context.resources).apply {
                colorFilter = background.colorFilter
            }
        } else {
            mSkbRoot.background = background
        }
        mSkbCandidatesBarView.updateTheme(keyTextColor)
        if (::mOnehandHoderLayout.isInitialized) {
            (mOnehandHoderLayout[0] as ImageButton).drawable?.setTint(keyTextColor)
            (mOnehandHoderLayout[1] as ImageButton).drawable?.setTint(keyTextColor)
        }
        mFullDisplayKeyboardBar?.updateTheme(keyTextColor)
        mAddPhrasesLayout.updateTheme(activeTheme)
        com.yuyan.inputmethod.util.ImeLog.d(
            "[perf] updateTheme ${android.os.SystemClock.elapsedRealtime() - perfT0}ms " +
                "bg=${if (background is BitmapDrawable) "bitmap" else "color"}"
        )
    }

    private fun onClick(view: View) {
        val keyboardSetting = appPrefs.keyboardSetting
        if (view.id == R.id.ib_holder_one_hand_none) {
            keyboardSetting.oneHandedModSwitch.setValue(!keyboardSetting.oneHandedModSwitch.getValue())
        } else {
            val currentMod = keyboardSetting.oneHandedMod.getValue()
            keyboardSetting.oneHandedMod.setValue(if (currentMod == KeyboardOneHandedMod.LEFT) KeyboardOneHandedMod.RIGHT else KeyboardOneHandedMod.LEFT)
        }
        EnvironmentSingleton.instance.initData()
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.apply {
            clearKeyboard()
            switchKeyboard()
        }
    }

    override fun responseLongKeyEvent(result: Pair<PopupMenuMode, String>) {
        val (mode, value) = result
        // "/" 和 "`" 在中文模式下要送进引擎（万象命令前缀 / 输入中反查引导符），
        // 此时绝不能先自动选词：否则「lk 上滑 /」会先把 lk 的候选上屏，
        // 用户就打不出 lk/mm 这类间接辅助（同文输入法下正常，差别就在这里）
        val isRimeFuncKey = InputModeSwitcher.isChinese && (value == "/" || value == "`")
        if (!isRimeFuncKey && mode != PopupMenuMode.None && !DecodingInfo.isAssociate && !DecodingInfo.isCandidatesEmpty) {
            if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
        }

        when (mode) {
            PopupMenuMode.Text -> {
                // 上滑/长按打出的符号：中文模式下 "/" 交给引擎（万象命令模式），
                // 否则直接上屏（此前所有来源的 "/" 都是直接上屏，命令永远触发不了）
                if (!inputRimeFuncKeyIfChinese(value)) {
                    if (SymbolPreset.containsKey(value)) commitPairSymbol(value) else commitText(value)
                }
            }
            PopupMenuMode.SwitchIME -> InputMethodUtil.showPicker()
            PopupMenuMode.EMOJI -> onSettingsMenuClick(SkbMenuMode.Emojicon)
            PopupMenuMode.EnglishCell -> {
                val pref = appPrefs.input.abcSearchEnglishCell
                pref.setValue(!pref.getValue())
                KeyboardManager.instance.switchKeyboard()
            }
            PopupMenuMode.Clear -> {
                if (isAddPhrases) mAddPhrasesLayout.clearPhrasesContent()
                else service.getTextBeforeCursor(1000).takeIf { it.isNotEmpty() }?.let {
                    textBeforeCursors.push(it)
                    service.deleteSurroundingText(1000)
                }
            }
            PopupMenuMode.Revertl -> textBeforeCursors.popInReverseOrder()?.takeIf { it.isNotEmpty() }?.let { commitText(it) }
            PopupMenuMode.Enter -> commitText("\n")
            else -> {}
        }
        if (mode == PopupMenuMode.Clear) resetToIdleState()
    }

    override fun responseHandwritingResultEvent(words: Array<CandidateListItem>) {
        DecodingInfo.cacheCandidates(words)
    }

    override fun responseKeyEvent(sKey: SoftKey) {
        val keyCode = sKey.code
        if(sKey.isUserDefKey)processUserDefKey(keyCode, sKey.keyLabel)
        else if(sKey.isUniStrKey){
            // 中文模式下符号页的 "/" 是 rime 的功能键（万象用它进入命令模式），
            // 必须送进引擎进组合区（待编辑区），否则会被当普通符号直接上屏。
            if (inputRimeFuncKeyIfChinese(sKey.label)) return
            if (!DecodingInfo.isAssociate && !DecodingInfo.isCandidatesEmpty) chooseAndUpdate()
            sKey.label.takeIf(String::isNotEmpty)?.let {
                if (SymbolPreset.containsKey(it)) commitPairSymbol(it) else commitText(it)
            }
        } else {
            val metaState = when(Kernel.getCurrentRimeSchema()) {
                CustomConstant.SCHEMA_ZH_T9, CustomConstant.SCHEMA_ZH_STROKE, CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> KeyEvent.META_CAPS_LOCK_ON
                else -> InputModeSwitcher.mToggleStates.modifiers
            }
            processKeyUp(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
        }
    }


    fun processKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL -> return true
            // 返回键只在键盘显示时消费（此时用来收起键盘）。
            // 之前无条件 return true，DOWN 就被吃掉，processSystemKeys 那个
            // “键盘没显示就不消费”的判断永远走不到 —— 结果输入法运行期间
            // 系统返回（含边缘侧滑手势）全被吞掉，杀进程才好、重开又坏。
            KeyEvent.KEYCODE_BACK -> return service.isInputViewShown
        }
        return false
    }

    fun processKeyUp(event: KeyEvent): Boolean {
        if(event.isSystem) return processSystemKeys(event)
        else if(isFunctionKey(event.keyCode)){
            processFunctionKey(event)
            return true
        }
        InputModeSwitcher.resetCharCase()
        val englishCellDisable = InputModeSwitcher.isEnglish && !appPrefs.input.abcSearchEnglishCell.getValue()
        return when {
            englishCellDisable -> processEnglishKey(event)
            InputModeSwitcher.isEnglish || InputModeSwitcher.isChinese -> processInput(event)
            else -> processEnglishKey(event)
        }
//        return if(appPrefs.input.abcSearchEnglishCell.getValue() || InputModeSwitcherManager.isChinese)processInput(event) else processEnglishKey(event)
    }

    private fun processEnglishKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        // 软键盘合成的 KeyEvent unicodeChar 恒为 0，必须经 KeyCharacterMap 解析，
        // 否则这里提交出去的是 NUL 字符（英文模式打不出字母）
        val keyChar = if (event.unicodeChar != 0) event.unicodeChar
            else android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                .get(keyCode, event.metaState)
        val label = keyChar.toChar().toString()
        var result = true
        when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                sendKeyEvent(keyCode)
            }
            keyCode in (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) -> {
                textBeforeCursors.clear()
                commitText(label)
            }
            keyCode != 0 -> sendKeyEvent(keyCode)
            label.isNotEmpty() -> if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
            else -> result = false
        }
        return result
    }

    // 系统按键只处理返回键，当点击返回键且软键盘显示时，隐藏键盘并消费事件
    private fun processSystemKeys(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (service.isInputViewShown) { requestHideSelf(); true } else false
            else -> false
        }
    }

    fun isFunctionKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_LANGUAGE_SWITCH, KeyEvent.KEYCODE_SYM,
            KeyEvent.KEYCODE_PICTSYMBOLS, KeyEvent.KEYCODE_NUM -> return true
        }
        return false
    }

    private fun processFunctionKey(event: KeyEvent) {
        when (val keyCode = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) {
                    if (keyCode == KeyEvent.KEYCODE_SPACE) {
                        // 空格用「文本提交」而不是键值事件：
                        // 远控类应用（UU远程等）只转发文本，InputConnection.sendKeyEvent
                        // 会被丢掉 —— 表现为「字母能传到电脑，空格传不过去」。
                        // 与同文一致（Trime 的 send: space 也是交给 RIME 当普通字符提交成文本），
                        // 也与本文件 commitDecInfoText() 里英文模式的处理保持一致。
                        service.commitText(" ")
                    } else {
                        sendKeyEvent(keyCode)
                    }
                    resetToIdleState()
                }
                else chooseAndUpdate()
            }
            KeyEvent.KEYCODE_CLEAR -> resetToIdleState()
            KeyEvent.KEYCODE_ENTER -> {
                if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) sendKeyEvent(keyCode)
                else commitDecInfoText(DecodingInfo.composingStrForCommit)
                resetToIdleState()
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if(InputModeSwitcher.isChinese && !DecodingInfo.isEngineFinish) processInput(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_APOSTROPHE, 0, 0, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
                else InputModeSwitcher.processShiftKey(keyCode)
            }
        }
    }


    private fun processUserDefKey(keyCode: Int, label: String) {
        when {
            keyCode == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> {
                resetToIdleState()
                return
            }
            // 中文标点键（「，」的 code 是 USER_KEYCODE_COMMA_EMOJI=-8，走的就是这里）
            // 在组合中必须送进引擎，不能先自动选词上屏 —— 方案里的 processor/punctuator
            // 是按 Rime 键名（X11 keysym）判断的，直接 commitText("，") 等于把组合串
            // 选词上屏再补一个逗号（万象 super_tips 提示按逗号不上屏就是这个原因）。
            // 非中文 / 无组合串 / 非标点时 helper 返回 false，行为保持原样。
            inputRimeFuncKeyIfChinese(label) -> return
            !DecodingInfo.isAssociate && !DecodingInfo.isCandidatesEmpty -> {
                if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
            }
        }

        when (keyCode) {
            InputModeSwitcher.USER_KEYCODE_SYMBOL -> {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SYMBOL)
                (KeyboardManager.instance.currentContainer as? SymbolContainer)?.setSymbolsView()
            }
            InputModeSwitcher.USER_KEYCODE_EMOJI -> onSettingsMenuClick(SkbMenuMode.Emojicon)
            in InputModeSwitcher.USER_KEYCODE_RETURN..InputModeSwitcher.USER_KEYCODE_LANG -> InputModeSwitcher.switchModeForUserKey(keyCode)
            in InputModeSwitcher.USER_KEYCODE_PASTE..InputModeSwitcher.USER_KEYCODE_CUT -> commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            InputModeSwitcher.USER_KEYCODE_MOVE_START -> service.setSelection(0, if (hasSelection) selEnd else 0)
            InputModeSwitcher.USER_KEYCODE_MOVE_END -> {
                if (hasSelection) {
                    val start = selStart
                    commitTextEditMenu(KeyPreset.textEditMenuPreset[InputModeSwitcher.USER_KEYCODE_SELECT_ALL])
                    postDelayed(100) { service.setSelection(start, selEnd) }
                } else {
                    commitTextEditMenu(KeyPreset.textEditMenuPreset[InputModeSwitcher.USER_KEYCODE_SELECT_ALL])
                    postDelayed(100) { service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)}
                }
            }
            InputModeSwitcher.USER_KEYCODE_SELECT_MODE -> {
                hasSelection = !hasSelection
                if (!hasSelection) service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            InputModeSwitcher.USER_KEYCODE_SELECT_ALL -> {
                hasSelectionAll = !hasSelectionAll
                if (!hasSelectionAll) service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                else commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            }
            else -> {
                if(label.isNotEmpty()){
                    if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                }
            }
        }
    }

    private fun processInput(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        // 软键盘合成的 KeyEvent 无 unicodeChar（恒 0），必须经 KeyCharacterMap 解析，
        // 否则按键进不了 Rime、直接当原始按键发给编辑框（表现为"打不出字"）
        val keyChar = if (event.unicodeChar != 0) event.unicodeChar
            else android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                .get(keyCode, event.metaState)
        val label = keyChar.toChar().toString()

        return when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                // 引擎仍在组合（未结束）→ 退引擎；否则才发给编辑框删已上屏的字。
                // 不能用 isCandidatesEmpty 判断：组合非空但没有候选词时（万象命令、
                // 未成词、上屏瞬间）会误判成"没在输入"，于是删了编辑框的字、待编辑区
                // 却不变，继续打字还在变多（用户实测）。
                if (DecodingInfo.isEngineFinish || DecodingInfo.isAssociate) {
                    service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                    sendKeyEvent(keyCode)
                } else {
                    DecodingInfo.deleteAction()
                    updateCandidate()
                }
                true
            }
            // KEYCODE_SLASH 同样交给引擎：万象用 "/" 触发命令模式，
            // 落到默认分支会被直接上屏（不进待编辑区）。
            // KEYCODE_SLASH / KEYCODE_GRAVE 同样交给引擎：万象用 "/" 和 "`" 触发命令模式
            (Character.isLetterOrDigit(keyChar) && keyCode != KeyEvent.KEYCODE_0) || keyCode == KeyEvent.KEYCODE_APOSTROPHE || keyCode == KeyEvent.KEYCODE_SEMICOLON || keyCode == KeyEvent.KEYCODE_SLASH || keyCode == KeyEvent.KEYCODE_GRAVE -> {
                textBeforeCursors.clear()
                DecodingInfo.inputAction(event)
                val raw = com.yuyan.inputmethod.RimeEngine.pendingRawCommit
                if (raw.isNotEmpty()) {
                    // 引擎没消费这个键（ascii 直输 / 方案不认）→ 原样上屏，
                    // 否则字符被静默丢弃（英文模式打不出字母）
                    com.yuyan.inputmethod.RimeEngine.pendingRawCommit = ""
                    commitText(raw)
                    resetToIdleState()
                } else {
                    updateCandidate()
                }
                true
            }
            keyCode != 0 -> {
                if (!DecodingInfo.isCandidatesEmpty && !DecodingInfo.isAssociate) chooseAndUpdate()
                sendKeyEvent(keyCode)
                resetToIdleState()
                true
            }
            label.isNotEmpty() -> {
                if (!DecodingInfo.isCandidatesEmpty && !DecodingInfo.isAssociate) chooseAndUpdate()
                if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                true
            }
            else -> false
        }
    }

    fun resetToIdleState() {
        resetCandidateWindow()
        if (hasSelectionAll) hasSelectionAll = false
    }

    /** 中文模式下 rime 的功能键必须送进引擎进组合区（万象用 "/" 和 "`" 进入命令模式），
     *  否则会被当普通符号直接上屏；返回 true 表示已按引擎处理。 */
    private fun inputRimeFuncKeyIfChinese(value: String): Boolean {
        if (!InputModeSwitcher.isChinese) return false
        var metaState = 0
        val keyCode = when (value) {
            "/" -> KeyEvent.KEYCODE_SLASH
            "`" -> KeyEvent.KEYCODE_GRAVE
            else -> {
                // 中文标点同样要送进引擎：方案里的 processor 是按 Rime 键名（X11 keysym）
                // 判断的。万象 super_tips 的 tips_key = "comma" 就是这样 —— 只有把「，」
                // 当成 keysym 0x2C 送进去（key:repr() == "comma"）才会触发提示上屏；
                // 原来走的是 chooseAndUpdate() + commitText("，")，等于先把组合串选词
                // 上屏、再补一个逗号，表现就是「提示显示着，按逗号却打出了 aaerfa，」。
                // 只在有组合串时改走引擎：没在输入时保持原来「直接上屏这个标点」的行为。
                if (com.yuyan.inputmethod.core.Rime.compositionText.isEmpty()) return false
                val punct = punctKeyCode(value) ?: return false
                metaState = punct.second
                punct.first
            }
        }
        com.yuyan.inputmethod.util.ImeLog.d(
            "[punct] 标点送引擎 label='$value' keyCode=$keyCode meta=$metaState " +
                "comp='${com.yuyan.inputmethod.core.Rime.compositionText}'"
        )
        DecodingInfo.inputAction(
            KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD)
        )
        val engineCommit = Kernel.commitText
        val raw = com.yuyan.inputmethod.RimeEngine.pendingRawCommit
        when {
            // 引擎主动提交：万象 super_tips 的 tips_key 就是 lua 里 env.engine:commit_text(text)，
            // 文本落在 preCommitText（Kernel.commitText）里。这条路径原来没人接 —— 只有点候选的
            // chooseDecodingCandidate 会读它，所以按键被引擎吞掉、组合串也不上屏，
            // 表现为「按逗号什么都不出」（日志里能看到 commit='α' 但屏幕上没有）。
            engineCommit.isNotEmpty() -> {
                commitText(engineCommit)
                // 清 preCommitText，否则下一次按键会把它再上屏一遍
                resetToIdleState()
            }
            raw.isNotEmpty() -> {
                com.yuyan.inputmethod.RimeEngine.pendingRawCommit = ""
                commitText(raw)
            }
            else -> updateCandidate()
        }
        return true
    }

    /**
     * 中文标点 → 送进引擎时用的按键（KEYCODE + 修饰键）。
     *
     * librime 的 Key::repr() 只认 X11 keysym：`,` 的 keysym 是 0x2C，名字才叫 "comma"；
     * 而中文键盘上的「，」字符码是 U+FF0C，直接送字符码的话 repr() 得到 "0xff0c"，
     * 方案里按 key:repr() 判断的 processor 全部匹配不上。
     * 这里改送 KEYCODE，由 KeyCharacterMap 还原成 ASCII 字符，正好是 librime 认的 keysym；
     * 方案的 punctuator 会再把 `,` 映射回 `，`，输出和原来一致。
     */
    private fun punctKeyCode(value: String): Pair<Int, Int>? = when (value) {
        "，" -> KeyEvent.KEYCODE_COMMA to 0
        "。" -> KeyEvent.KEYCODE_PERIOD to 0
        "、" -> KeyEvent.KEYCODE_BACKSLASH to 0
        "；" -> KeyEvent.KEYCODE_SEMICOLON to 0
        "：" -> KeyEvent.KEYCODE_SEMICOLON to KeyEvent.META_SHIFT_ON
        "？" -> KeyEvent.KEYCODE_SLASH to KeyEvent.META_SHIFT_ON
        "！" -> KeyEvent.KEYCODE_1 to KeyEvent.META_SHIFT_ON
        "“", "”" -> KeyEvent.KEYCODE_APOSTROPHE to KeyEvent.META_SHIFT_ON
        "‘", "’" -> KeyEvent.KEYCODE_APOSTROPHE to 0
        "（" -> KeyEvent.KEYCODE_9 to KeyEvent.META_SHIFT_ON
        "）" -> KeyEvent.KEYCODE_0 to KeyEvent.META_SHIFT_ON
        "《" -> KeyEvent.KEYCODE_COMMA to KeyEvent.META_SHIFT_ON
        "》" -> KeyEvent.KEYCODE_PERIOD to KeyEvent.META_SHIFT_ON
        "【" -> KeyEvent.KEYCODE_LEFT_BRACKET to 0
        "】" -> KeyEvent.KEYCODE_RIGHT_BRACKET to 0
        "—" -> KeyEvent.KEYCODE_MINUS to 0
        else -> null
    }

    fun chooseAndUpdate(candId: Int = mSkbCandidatesBarView.getActiveCandNo()) {
        val candidate = DecodingInfo.getCandidate(candId)
        if (candidate?.comment == "📋") {
            commitDecInfoText(candidate.text)
        } else {
            val choice = DecodingInfo.chooseDecodingCandidate(candId)
            if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) {
                KeyboardManager.instance.switchKeyboard()
                (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
                commitDecInfoText(choice)
            } else {
                if (!DecodingInfo.isCandidatesEmpty) {
                    (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
                    if (InputModeSwitcher.isEnglish) setComposingText(DecodingInfo.composingStrForCommit)
                } else {
                    resetToIdleState()
                }
            }
        }
    }

    private fun updateCandidate() {
        DecodingInfo.updateDecodingCandidate()
        if (!DecodingInfo.isCandidatesEmpty) {
            (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
        } else if (DecodingInfo.isEngineFinish) {
            // 只在引擎确实没有待上屏输入时才复位。候选暂空但仍在组合（拼音还没攒够）时
            // 复位会经 Kernel.reset → Rime.clearComposition 清掉引擎组合与 preedit，
            // 导致拼音每按一键都归零、永远攒不起来（表现为打不出字）。
            resetToIdleState()
        }
        if (InputModeSwitcher.isEnglish) setComposingText(DecodingInfo.composingStrForCommit)
    }

    fun updateCandidateBar() = mSkbCandidatesBarView.scheduleShowCandidates()

    private fun resetCandidateWindow() {
        DecodingInfo.reset()
        (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
    }

    inner class ChoiceNotifier internal constructor() : CandidateViewListener {
        override fun onClickChoice(choiceId: Int) {
            DevicesUtils.tryPlayKeyDown()
            DevicesUtils.tryVibrate(KeyboardManager.instance.currentContainer)
            chooseAndUpdate(choiceId)
        }

        override fun onClickMore(level: Int) {
            if (level == 0) {
                onSettingsMenuClick(SkbMenuMode.CandidatesMore)
            } else {
                KeyboardManager.instance.switchKeyboard()
                (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
            }
        }

        override fun onClickMenu(skbMenuMode: SkbMenuMode) = onSettingsMenuClick(skbMenuMode)

        override fun onClickClearCandidate() {
            resetToIdleState()
            KeyboardManager.instance.switchKeyboard()
        }

        override fun onClickClearClipBoard() {
            DataBaseKT.instance.clipboardDao().deleteAllExceptKeep()
            (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.showClipBoardView(SkbMenuMode.ClipBoard)
        }
    }

    fun onSettingsMenuClick(skbMenuMode: SkbMenuMode, extra: Phrase? = null) {
        if (skbMenuMode == SkbMenuMode.AddPhrases) {
            isAddPhrases = true
            KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
            initView(context)
            if (extra != null) {
                DataBaseKT.instance.phraseDao().deleteByContent(extra.content)
                mAddPhrasesLayout.setExtraData(extra)
            } else {
                mAddPhrasesLayout.clearPhrasesContent()
            }
        } else {
            onSettingsMenuClick(this, skbMenuMode)
        }
        mSkbCandidatesBarView.initMenuView()
    }

    fun selectPrefix(position: Int) {
        DevicesUtils.tryPlayKeyDown()
        DevicesUtils.tryVibrate(this)
        DecodingInfo.selectPrefix(position)
        updateCandidate()
    }

    fun showSymbols(symbols: Array<String>) {
        val list = symbols.map { CandidateListItem("📋", it) }.toTypedArray()
        DecodingInfo.cacheCandidates(list, true)
    }

    /**
     * 剪贴板建议：交给候选栏显示成「分词 + 图标 + 内容（小字）+ 关闭」的居中一行，
     * 不再把「分词」当成候选词混在候选列表里（内容一长就要翻到最后才按得到）。
     */
    fun showClipboardSuggestion(content: String) {
        // 分词页面里不显示剪贴板建议行（分词页会盖住候选栏那一条，留着反而碍事）
        if (KeyboardManager.instance.currentContainer is
            com.yuyan.imemodule.keyboard.container.SegmentsContainer) return
        // 先清掉残留候选：否则候选栏一刷新就会把建议行收起来（键盘刚起来时尤其明显）
        DecodingInfo.cacheCandidates(emptyArray(), true)
        mSkbCandidatesBarView.showClipboardSuggestion(content)
    }

    /** 「分词」入口：打开分词页面（照同文，切词结果不在候选区） */
    fun onSegmentRequest() {
        DecodingInfo.segmentClipboardSuggestion(appPrefs.internal.clipboardUpdateContent.getValue())
    }

    /** 分词页面用：把选中的词拼进组合区（选一个变一次），传空串表示清空组合区 */
    fun updateSegmentComposing(text: String) {
        val ic = service.currentInputConnection ?: return
        com.yuyan.inputmethod.util.ImeLog.d("[seg] 组合区更新 len=${text.length} text='${text.take(30)}'")
        // 照同文：整批编辑，并先清掉可能存在的选中 —— 否则 App 会在中途把组合区提交掉
        ic.beginBatchEdit()
        try {
            if (!ic.getSelectedText(0).isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 0)
            }
            // 关键：必须调 setComposingText —— 传空串就等于把组合区里的字删掉。
            // 只调 finishComposingText 是把字「提交」掉（留在屏幕上），
            // 于是取消选择时前面的字撤不掉、再选又接在后面越积越多。
            ic.setComposingText(text, 1)
            if (text.isEmpty()) {
                ic.finishComposingText()
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * 分词页面用：收尾上屏 —— 把组合区的字提交掉（不能走 updateSegmentComposing("")，
     * 那是把字删掉，用户选好的词就丢了）。
     */
    fun commitSegmentComposing() {
        service.currentInputConnection?.finishComposingText()
    }

    /** 分词页面用：进去时把候选栏的剪贴板建议行收起来 */
    fun hideClipboardSuggestionBar() {
        mSkbCandidatesBarView.hideClipboardSuggestion()
    }

    fun requestHideSelf() {
        service.requestHideSelf(0)
        // 收起后立刻重算 insets，把触摸区让出去。
        // 否则收起的输入法窗口仍占着触摸区，系统边缘返回手势会被吃掉。
        service.updateInputViewShown()
    }

    private fun sendKeyEvent(keyCode: Int) {
        if (isAddPhrases) {
            mAddPhrasesLayout.sendKeyEvent(keyCode)
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                isAddPhrases = false
                initView(context)
                onSettingsMenuClick(SkbMenuMode.Phrases)
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> service.sendEnterKeyEvent()
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    service.sendCombinationKeyEvents(keyCode, shift = hasSelection)
                    if (hasSelectionAll) hasSelectionAll = false
                }
                else -> service.sendCombinationKeyEvents(keyCode)
            }
        }
    }

    private fun setComposingText(text: CharSequence) {
        if (!isAddPhrases) service.setComposingText(text)
    }

    private fun commitText(text: String) {
        if (isAddPhrases) mAddPhrasesLayout.commitText(text)
        else service.commitText(StringUtils.converted2FlowerTypeface(text))
    }

    private fun commitPairSymbol(text: String) {
        if (isAddPhrases) {
            mAddPhrasesLayout.commitText(text)
        } else {
            if (appPrefs.input.symbolPairInput.getValue()) {
                service.commitText(text + SymbolPreset[text]!!)
                postDelayed(300) { service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT) }
            } else {
                service.commitText(text)
            }
        }
    }

    private fun commitTextEditMenu(id: Int?) {
        id?.let { service.commitTextEditMenu(it) }
    }

    fun performEditorAction(editorAction: Int) = service.performEditorAction(editorAction)

    private fun commitDecInfoText(resultText: String?) {
        resultText ?: return
        if (isAddPhrases) {
            mAddPhrasesLayout.commitText(resultText)
        } else {
            service.commitText(StringUtils.converted2FlowerTypeface(resultText))
            if (InputModeSwitcher.isEnglish){
                service.finishComposingText()
                if(appPrefs.input.abcSpaceAuto.getValue()) service.commitText(" ")
                resetToIdleState()
            }
        }
    }

    private fun initNavbarBackground(service: ImeService) {
        service.window.window?.also { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                win.navigationBarColor = Color.TRANSPARENT
            } else {
                win.insetsController?.apply {
                    hide(WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) win.isNavigationBarContrastEnforced = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val env = EnvironmentSingleton.instance
            env.systemNavbarWindowsBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val fullDisplayEnable = appPrefs.internal.fullDisplayKeyboardEnable.getValue()
            mLlKeyboardBottomHolder.minimumHeight = when {
                // 悬浮模式也要给「移动条高度」：这里原来给 0，而 initView 的悬浮分支给的是
                // heightForKeyboardMove —— 两处不一致，insets 一变（打字/布局过程中会反复变）
                // holder 就在 38↔0 之间翻，键盘根高度跟着 891↔786 跳、按键区被挤，
                // 键位网格按 720 绘制、容器只有 653 → 最下面一行被裁（用户实测）。
                env.keyboardModeFloat -> env.heightForKeyboardMove
                fullDisplayEnable -> env.heightForFullDisplayBar + env.systemNavbarWindowsBottom
                else -> env.systemNavbarWindowsBottom
            }
            insets
        }
    }

    /**
     * 分词页会把工具栏挂到候选栏那一行上。回到普通输入时恢复候选栏 ——
     * 从分享/别的 App 回来时 onStartInputView 不一定被调到，所以 onWindowShown 也要兜一次。
     */
    private fun restoreCandidatesBarIfNeeded() {
        if (KeyboardManager.instance.currentContainer is
            com.yuyan.imemodule.keyboard.container.SegmentsContainer) return
        if (mSkbCandidatesBarView.visibility != View.VISIBLE) {
            mSkbCandidatesBarView.visibility = View.VISIBLE
        }
        mSkbCandidatesBarView.setCustomRow(null)
        updateCandidateBar()
    }

    fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        InputModeSwitcher.requestInputWithSkb(editorInfo)
        restoreCandidatesBarIfNeeded()
        if (!restarting) {
            resetToIdleState()
            val clipboard = appPrefs.clipboard
            if (clipboard.clipboardSuggestion.getValue()) {
                val internal = appPrefs.internal
                val lastTime = internal.clipboardUpdateTime.getValue()
                if (System.currentTimeMillis() - lastTime <= clipboardItemTimeout * 1000) {
                    val content = internal.clipboardUpdateContent.getValue()
                    if (content.isNotBlank()) {
                        showClipboardSuggestion(content)
                        internal.clipboardUpdateTime.setValue(0L)
                    }
                }
            }
        }
    }

    fun onWindowShown() {
        chinesePrediction = appPrefs.input.chinesePrediction.getValue()
        restoreCandidatesBarIfNeeded()
    }

    fun onWindowHidden() {
        if (isAddPhrases) {
            isAddPhrases = false
            mAddPhrasesLayout.addPhrasesHandle()
            initView(context)
        }
        KeyboardManager.instance.switchKeyboard()
        resetToIdleState()
    }

    private var selStart = 0
    private var selEnd = 0
    private var oldCandidatesEnd = 0

    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesEnd: Int) {
        selStart = newSelStart
        selEnd = newSelEnd
        if (InputModeSwitcher.isEnglish ) {
            if (oldCandidatesEnd == candidatesEnd) {
                service.finishComposingText()
                resetToIdleState()
            }
            oldCandidatesEnd = candidatesEnd
            return
        }
        if (oldSelStart == newSelStart) return
        when {
            InputModeSwitcher.isNumberSkb -> {
                val textBeforeCursor = service.getTextBeforeCursor(500)
                if (textBeforeCursor.isBlank()) resetCandidateWindow()
                else CustomEngine.parseExpressionAtEnd(textBeforeCursor).let { CustomEngine.expressionCalculator(textBeforeCursor, it).let(::showSymbols) }
            }
            chinesePrediction && InputModeSwitcher.isChinese
                    && KeyboardManager.instance.currentContainer !is com.yuyan.imemodule.keyboard.container.SettingsContainer -> {
                val textBeforeCursor = service.getTextBeforeCursor(10)
                if (textBeforeCursor.isBlank()) resetCandidateWindow()
                else {
                    DecodingInfo.getAssociateWord(textBeforeCursor)
                    updateCandidate()
                }
            }
            else -> resetCandidateWindow()
        }
    }
}