package com.yuyan.imemodule.service

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.yuyan.imemodule.candidate.CandidateView
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager.OnThemeChangeListener
import com.yuyan.imemodule.data.theme.ThemeManager.addOnChangedListener
import com.yuyan.imemodule.data.theme.ThemeManager.onSystemDarkModeChange
import com.yuyan.imemodule.data.theme.ThemeManager.removeOnChangedListener
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.utils.isDarkMode
import com.yuyan.imemodule.view.preference.ManagedPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag

/**
 * Main class of the Pinyin input method. 输入法服务
 */
class ImeService : InputMethodService() {
    private var isHardwareKeyboard = false
    private var isSoftKeyboard = false
    private lateinit var mInputView: InputView
    private lateinit var mCandidateView: CandidateView
    private val onThemeChangeListener = OnThemeChangeListener { _: Theme? ->
        if (isHardwareKeyboard) { if (::mCandidateView.isInitialized) mCandidateView.updateTheme() }
        else if (::mInputView.isInitialized) mInputView.updateTheme()
    }
    private val clipboardUpdateContent = getInstance().internal.clipboardUpdateContent
    private val clipboardUpdateContentListener = ManagedPreference.OnChangeListener<String> { _, value ->
        if(isSoftKeyboard && ::mInputView.isInitialized && getInstance().clipboard.clipboardSuggestion.getValue()){
            if(value.isNotBlank()) {
                if(KeyboardManager.instance.currentContainer is ClipBoardContainer
                    && (KeyboardManager.instance.currentContainer as ClipBoardContainer).getMenuMode() == SkbMenuMode.ClipBoard ){
                    (KeyboardManager.instance.currentContainer as ClipBoardContainer).showClipBoardView(SkbMenuMode.ClipBoard)
                } else {
                    mInputView.showSymbols(arrayOf(value))
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        addOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.registerOnChangeListener(clipboardUpdateContentListener)
    }

    override fun onCreateInputView(): View {
        // 缓存视图：Android 每次 startInput（含侧滑返回转场聚焦）都可能调用本方法，
        // 每次重建会 inflate 整个键盘布局树，严重拖慢系统返回手势
        if (!::mInputView.isInitialized) mInputView = InputView(baseContext, this)
        return mInputView
    }

    override fun onCreateCandidatesView(): View {
        if (!::mCandidateView.isInitialized) mCandidateView = CandidateView(baseContext, this)
        return mCandidateView
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return if(getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()) true else super.onEvaluateInputViewShown()
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        YuyanEmojiCompat.setEditorInfo(editorInfo)
        handleHardwareKeyboard()
        if (isHardwareKeyboard && ::mCandidateView.isInitialized)mCandidateView.onStartInput(editorInfo, restarting)
        super.onStartInput(editorInfo, restarting)
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        if (isSoftKeyboard && ::mInputView.isInitialized)mInputView.onStartInputView(editorInfo, restarting)
        super.onStartInputView(editorInfo, restarting)
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.unregisterOnChangeListener(clipboardUpdateContentListener)
    }

    /**
     * 横竖屏切换
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleHardwareKeyboard(newConfig)
        CoroutineScope(Dispatchers.Main).launch {
            delay(200) //延时，解决获取屏幕尺寸不准确。
            EnvironmentSingleton.instance.initData(baseContext)
            if (isSoftKeyboard) {
                KeyboardLoaderUtil.instance.clearKeyboardMap()
                KeyboardManager.instance.clearKeyboard()
                KeyboardManager.instance.switchKeyboard()
            } else if(isHardwareKeyboard && ::mCandidateView.isInitialized){
                mCandidateView.initView()
            }
        }
        onSystemDarkModeChange(newConfig.isDarkMode())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 0 != event.getRepeatCount()  长按物理按键或 Shift/Meta/Ctrl的组合按键时，交由系统处理;有个特殊组合键：Ctrl+SPACE切换语言
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyDown(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyDown(keyCode, event)
        else if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.processKeyDown(keyCode, event) || super.onKeyUp(keyCode, event)
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized) mCandidateView.processKeyDown(keyCode, event) || super.onKeyUp(keyCode, event)
        else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyDown(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyDown(keyCode, event)
        else if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.processKeyUp(event) || super.onKeyUp(keyCode, event)
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized) mCandidateView.processKeyUp(event) || super.onKeyUp(keyCode, event)
        else super.onKeyDown(keyCode, event)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        applyInputViewHeight(view)
    }

    /** IME 窗口高度：只有浮键盘（可拖到屏幕任意位置）和加词面板需要整屏窗口。
     *  普通模式让窗口只占键盘高度——窗口盖满整屏会把系统侧滑返回手势整个吃掉，
     *  表现为「从边缘滑了完全没反应，换其他输入法就正常」。 */
    private fun applyInputViewHeight(view: View) {
        val floatMode = EnvironmentSingleton.instance.keyboardModeFloat
        val addPhrases = (view as? InputView)?.isAddPhrases == true
        val needFullScreen = floatMode || addPhrases
        val target = if (needFullScreen) ViewGroup.LayoutParams.MATCH_PARENT
        else ViewGroup.LayoutParams.WRAP_CONTENT
        val layoutParams = view.layoutParams ?: return
        if (layoutParams.height != target) {
            layoutParams.height = target
            view.setLayoutParams(layoutParams)
        }
        // 侧滑返回失效时看这行：窗口是不是又变成整屏了（整屏会吃掉系统边缘手势）
        val winAttrs = (window as? android.app.Dialog)?.window?.attributes
        com.yuyan.inputmethod.util.ImeLog.d(
            "[window] float=$floatMode addPhrases=$addPhrases target=" +
                (if (target == ViewGroup.LayoutParams.MATCH_PARENT) "MATCH_PARENT" else "WRAP_CONTENT") +
                " winH=" + winAttrs?.height + " viewH=" + view.height
        )
    }

    override fun onEvaluateFullscreenMode(): Boolean = false //修复横屏之后输入框遮挡问题


    override fun onComputeInsets(outInsets: Insets) {
        // 注意：Insets/Region 都是「窗口内相对坐标」。窗口是 WRAP_CONTENT、顶=输入视图顶，
        // 用 getLocationOnScreen（屏幕坐标）会把 region 顶抬高整整一个窗口偏移——
        // 窗口收缩后正好把键盘上半划出触摸区（用户实测一半点击穿透）。一律用窗口坐标。
        val (x, y) = if (isSoftKeyboard && ::mInputView.isInitialized) intArrayOf(0, 0).also {if(mInputView.isAddPhrases) mInputView.mAddPhrasesLayout.getLocationInWindow(it) else mInputView.mSkbRoot.getLocationInWindow(it) }
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized) intArrayOf(0, 0).also {mCandidateView.mSkbRoot.getLocationInWindow(it) }
        else intArrayOf(0, 0)
        outInsets.apply {
            if(isSoftKeyboard || !isHardwareKeyboard){
                if(EnvironmentSingleton.instance.keyboardModeFloat) {
                    contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                    visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    // 浮键盘：触摸区必须覆盖整个输入视图（含候选栏/待编辑区）。
                    // 只圈 mSkbRoot 会让待编辑区点击穿透（用户实测）。
                    val floc = IntArray(2)
                    if (::mInputView.isInitialized) mInputView.getLocationOnScreen(floc)
                    val fw = if (::mInputView.isInitialized) mInputView.width else 0
                    val fh = if (::mInputView.isInitialized) mInputView.height else 0
                    touchableRegion.set(floc[0], floc[1], floc[0] + fw, floc[1] + fh)
                } else if (!isInputViewShown) {
                    // 键盘已收起（requestHideSelf）：窗口还在，但不能继续占着触摸区，
                    // 否则收起后系统边缘返回手势会被输入法吃掉
                    // （用户反馈：收起键盘后侧滑失效，杀进程才好、重开输入法又坏）。
                    contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                    visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    touchableRegion.setEmpty()
                } else {
                    // 全部用「窗口内相对坐标」：窗口是 WRAP_CONTENT、顶=输入视图顶，
                    // 用屏幕坐标（getLocationOnScreen）会把 region/contentTop 抬高一个
                    // 窗口偏移——窗口收缩后正好把键盘上半划出触摸区（用户实测一半穿透）。
                    val wloc = IntArray(2)
                    if (::mInputView.isInitialized) mInputView.getLocationInWindow(wloc)
                    val ww = if (::mInputView.isInitialized) mInputView.width else 0
                    val wh = if (::mInputView.isInitialized) mInputView.height else 0
                    // contentTopInsets 决定 App 的输入框贴在哪：用键盘本体（mSkbRoot）
                    // 的窗口 y，输入框贴键盘实际顶边，候选栏/待编辑区浮在它上方。
                    val kbTop = if (y > 0) y else wloc[1]
                    contentTopInsets = kbTop
                    // 触摸区：窗口高度已精确等于「候选栏+键盘」内容（InputView.onMeasure
                    // 按 mSkbRoot.bottom 定高），REGION 圈整个输入视图即可：不多（无死区）
                    // 不少（键盘/候选/待编辑全可点）。不用 VISIBLE——系统按可见帧推算，不精确。
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    touchableRegion.set(wloc[0], wloc[1], wloc[0] + ww, wloc[1] + wh)
                    val dm = resources.displayMetrics
                    com.yuyan.inputmethod.util.ImeLog.d(
                        "[display] w=${dm.widthPixels} h=${dm.heightPixels} density=${dm.density} " +
                            "orientation=${resources.configuration.orientation} " +
                            "inputView=${wloc[0]},${wloc[1]},${wloc[0] + ww},${wloc[1] + wh} " +
                            "skbRootY=$y skbRootH=${if (::mInputView.isInitialized) mInputView.mSkbRoot.height else 0} " +
                            (if (::mInputView.isInitialized) mInputView.mSkbCandidatesBarView.debugSize() else "")
                    )
                }
            } else {
                contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(x, y, x + mCandidateView.mSkbRoot.width, y + mCandidateView.mSkbRoot.height)
            }
        }
        com.yuyan.inputmethod.util.ImeLog.d(
            "[insets] x=$x y=$y contentTop=${outInsets.contentTopInsets} visibleTop=${outInsets.visibleTopInsets} screenH=${EnvironmentSingleton.instance.mScreenHeight} touch=${outInsets.touchableInsets} region=${outInsets.touchableRegion.bounds.left},${outInsets.touchableRegion.bounds.top},${outInsets.touchableRegion.bounds.right},${outInsets.touchableRegion.bounds.bottom}"
        )
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd)
        // 待编辑区/候选词出现或消失后，触摸区必须跟着内容走：onComputeInsets 只在
        // 窗口变化时重算，这里强制重算一次，否则待编辑区落在触摸区外、点击直接穿透。
        if (isSoftKeyboard && isInputViewShown) updateInputViewShown()
    }

    private val cursorAnchorPosition = FloatArray(2)
    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (!isHardwareKeyboard || cursorAnchorInfo == null) return
        cursorAnchorPosition[0] = cursorAnchorInfo.insertionMarkerHorizontal
        cursorAnchorPosition[1] = cursorAnchorInfo.insertionMarkerBottom
        val matrix = cursorAnchorInfo.getMatrix()
        if (matrix != null) {
            matrix.mapPoints(cursorAnchorPosition)
        }
        mCandidateView.updatePosition(cursorAnchorPosition)
    }

    override fun onWindowShown() {
        if (isSoftKeyboard) mInputView.onWindowShown()
        // 浮键盘开关可能在两次 startInput 之间被切换，每次显示窗口时重新定高
        if (isSoftKeyboard && ::mInputView.isInitialized) applyInputViewHeight(mInputView)
        super.onWindowShown()
    }

    override fun onWindowHidden() {
        if(isSoftKeyboard) mInputView.onWindowHidden()
        super.onWindowHidden()
    }

    /**
     * 模拟Enter按键点击
     */
    fun sendEnterKeyEvent() {
        val inputConnection = getCurrentInputConnection()
        YuyanEmojiCompat.mEditorInfo?.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL || imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                inputConnection.performEditorAction(actionId)
            } else when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> inputConnection.performEditorAction(action)
            }
        }
    }

    fun sendCombinationKeyEvents(keyEventCode: Int, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection.sendKeyEvent(
            KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    /**
     * 向输入框提交预选词
     */
    fun setComposingText(text: CharSequence) {
        currentInputConnection.setComposingText(text, 1)
    }


    /**
     * 结束提交预选词
     */
    fun finishComposingText() {
        currentInputConnection.finishComposingText()
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String) {
        currentInputConnection.commitText(StringUtils.converted2FlowerTypeface(text), 1)
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String, newCursorPosition: Int) {
        currentInputConnection.commitText(StringUtils.converted2FlowerTypeface(text), newCursorPosition)
    }

    fun getTextBeforeCursor(length:Int) : String {
        return currentInputConnection.getTextBeforeCursor(length, 0).toString()
    }

    fun commitTextEditMenu(id:Int) {
        currentInputConnection.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction:Int) {
        currentInputConnection.performEditorAction(editorAction)
    }

    fun deleteSurroundingText(length:Int) {
        currentInputConnection.deleteSurroundingText(length, 0)
    }

    fun setSelection(start: Int, end: Int) {
        currentInputConnection.setSelection(start, end)
    }

    fun handleHardwareKeyboard(newConfig: Configuration? = null) {
        val hardwareKeyboard = if (getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()) false
            else if (newConfig != null) (newConfig.keyboard != Configuration.KEYBOARD_NOKEYS)
            else resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
        isSoftKeyboard = !hardwareKeyboard
        isHardwareKeyboard = hardwareKeyboard
        setCandidatesViewShown(isHardwareKeyboard)
        currentInputConnection.requestCursorUpdates(if(isHardwareKeyboard)InputConnection.CURSOR_UPDATE_MONITOR else 0)
    }

}
