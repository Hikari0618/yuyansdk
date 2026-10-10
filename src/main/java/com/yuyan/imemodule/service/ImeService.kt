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
                    // 走和新版一致的自定义建议行。
                    // 原来这里是 showSymbols()，那是老的「候选词」机制，和新的建议行不是一套，
                    // 会出现「有时是候选、有时是建议行」甚至不显示的问题。
                    mInputView.showClipboardSuggestion(value)
                }
            }
        }
    }

    /**
     * 键盘正显示时复制文本 → 立刻弹建议行（照同文的做法：剪贴板一变就更新 UI）。
     * 监听「复制时间」而不是「内容」：复制一段相同文本时内容不变，只监听内容不会再触发。
     */
    private val clipboardUpdateTimeListener = ManagedPreference.OnChangeListener<Long> { _, _ ->
        if (!isInputViewShown || !::mInputView.isInitialized) return@OnChangeListener
        if (!getInstance().clipboard.clipboardSuggestion.getValue()) return@OnChangeListener
        val content = getInstance().internal.clipboardUpdateContent.getValue()
        if (content.isNotBlank()) {
            mInputView.showClipboardSuggestion(content)
        }
    }
    override fun onCreate() {
        super.onCreate()
        addOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.registerOnChangeListener(clipboardUpdateContentListener)
        getInstance().internal.clipboardUpdateTime.registerOnChangeListener(clipboardUpdateTimeListener)
    }

    override fun onCreateInputView(): View {
        // 缓存视图：Android 每次 startInput（含侧滑返回转场聚焦）都可能调用本方法，
        // 每次重建会 inflate 整个键盘布局树，严重拖慢系统返回手势
        if (!::mInputView.isInitialized) mInputView = InputView(baseContext, this)
        return mInputView
    }

    override fun onCreateCandidatesView(): View? {
        // 返回 null：不向框架提供「系统级独立候选视图」。
        //
        // 框架会把候选视图放在输入视图【上方】，即使 setCandidatesViewShown(false) 隐藏它，
        // 它占的高度仍会被预留 —— 实测打字时输入视图整体下移 133（ivY 100→233）、
        // 高度少 133（ivH 1954→1821，底边不变），键盘根被顶到内容区最上沿并压扁
        // （h 879→786 = 按键区少 67 + 小横条 26），表现为「上缘被压下、下缘被裁、
        // 小横条变小」。语燕的候选词本来就画在输入视图内的 CandidatesBar 上，
        // 这个独立候选视图是多余的（只有物理键盘模式会用到，见下方 isHardwareKeyboard 分支）。
        return null
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
     *
     * 只重算几何 + 让「已有键盘」按新尺寸重新布局，不重建键盘。
     * 原来是 clearKeyboardMap() + clearKeyboard() + switchKeyboard()：在主线程重新解析
     * 皮肤 XML、丢掉并重建键盘容器与视图 —— 这就是「开着键盘旋转要卡一会」的来源。
     * 同文输入法旋转时几乎什么都不做（只清一下组合），键盘靠布局自适应。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        val perfT0 = android.os.SystemClock.elapsedRealtime()
        super.onConfigurationChanged(newConfig)
        handleHardwareKeyboard(newConfig)
        relayoutForRotation()
        if (isSoftKeyboard && ::mInputView.isInitialized) {
            // 旋转瞬间 resources 里的屏幕尺寸可能还没更新（原来的 delay(200) 就是为这个），
            // 等布局稳定后再校正一次；这次顺便重新应用皮肤（位图缩放只做一次）
            mInputView.postDelayed({ relayoutForRotation(applyTheme = true) }, 120)
        }
        onSystemDarkModeChange(newConfig.isDarkMode())
        com.yuyan.inputmethod.util.ImeLog.d(
            "[perf] onConfigurationChanged total=${android.os.SystemClock.elapsedRealtime() - perfT0}ms " +
                "orientation=${newConfig.orientation}"
        )
    }

    /** 横竖屏切换后的重新布局：纯几何计算 + 重新测量，不重建视图 */
    private fun relayoutForRotation(applyTheme: Boolean = false) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        EnvironmentSingleton.instance.initData(baseContext)
        val t1 = android.os.SystemClock.elapsedRealtime()
        if (isSoftKeyboard && ::mInputView.isInitialized) {
            // 已缓存的键盘按新几何重算按键矩形（SoftKey 存的是相对比例，不重新解析皮肤）
            KeyboardLoaderUtil.instance.reapplySkbCoreSize()
            val t2 = android.os.SystemClock.elapsedRealtime()
            KeyboardManager.instance.relayoutCurrentKeyboard()
            val t3 = android.os.SystemClock.elapsedRealtime()
            if (applyTheme) mInputView.initView(baseContext)
            val t4 = android.os.SystemClock.elapsedRealtime()
            com.yuyan.inputmethod.util.ImeLog.d(
                "[perf] relayout(applyTheme=$applyTheme) initData=${t1 - t0} reapply=${t2 - t1} " +
                    "relayout=${t3 - t2} initView=${t4 - t3} total=${t4 - t0}"
            )
        } else if (isHardwareKeyboard && ::mCandidateView.isInitialized) {
            mCandidateView.initView()
        }
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
        // 框架在「输入视图已经显示」的情况下会反复调用 setInputView
        // （旋转时：onConfigurationChanged → resetStateForNewConfiguration → showWindow
        //   → prepareWindow → updateInputViewShown → setInputView），
        // 而 framework 内部是直接往 mInputFrame 上 addView(view)：此时 view 还挂在原父容器上，
        // 于是抛 IllegalStateException: The specified child already has a parent
        // —— 用户日志里刷屏的 15 次 CRASH，每次横竖屏切换必崩。
        // 关闭键盘时旋转不崩，正是因为不走 showWindow/updateInputViewShown 这条链路。
        // 交给框架前先把 view 从原父容器摘下来。
        (view.parent as? ViewGroup)?.removeView(view)
        super.setInputView(view)
        applyInputViewHeight(view)
    }

    override fun setCandidatesView(view: View?) {
        // 与 setInputView 同一个坑：框架 prepareWindow() 里会再次 setCandidatesView()，
        // 内部同样直接 addView → 候选视图已挂在原父容器上就抛 IllegalStateException。
        // （堵住 setInputView 之后，崩溃就转移到了这里，仍是旋转必崩。）
        (view?.parent as? ViewGroup)?.removeView(view)
        super.setCandidatesView(view)
    }

    override fun setExtractView(view: View?) {
        // 同上，预防性处理（全屏提取视图）
        (view?.parent as? ViewGroup)?.removeView(view)
        super.setExtractView(view)
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
        // 窗口高度也必须跟着设。只设视图的 layoutParams 时窗口仍是 WRAP_CONTENT，
        // 视图（MATCH_PARENT）在 wrap 的窗口里只能按内容撑开 —— 悬浮模式下输入视图
        // 高度就成了「键盘高 + 位移」：位移加多少视图长多少，键盘下界被撑出屏幕
        //（用户实测「往上划下界逐渐消失」），而且打字重新应用位移时这个 wrap 高度
        // 又参与 clamp，把位置改写（「继续往下挤」）。
        val dialog = window as? android.app.Dialog
        val winAttrs = dialog?.window?.attributes
        if (winAttrs != null && winAttrs.height != target) {
            winAttrs.height = target
            dialog?.window?.setAttributes(winAttrs)
        }
        // 侧滑返回失效时看这行：窗口是不是又变成整屏了（整屏会吃掉系统边缘手势）
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
                    // 浮键盘：窗口是整屏的（可拖到任意位置），触摸区必须只圈 mSkbRoot
                    // （键盘本体+候选栏，窗口内坐标）——之前圈整个输入视图等于全屏认领触摸，
                    // 键盘外很大一块都是死区（用户实测悬浮模式一大块点不动）。
                    val kbLoc = IntArray(2)
                    if (::mInputView.isInitialized) mInputView.mSkbRoot.getLocationInWindow(kbLoc)
                    val kbW = if (::mInputView.isInitialized) mInputView.mSkbRoot.width else 0
                    val kbH = if (::mInputView.isInitialized) mInputView.mSkbRoot.height else 0
                    touchableRegion.set(kbLoc[0], kbLoc[1], kbLoc[0] + kbW, kbLoc[1] + kbH)
                } else if (!isInputViewShown) {
                    // 键盘已收起（requestHideSelf）：窗口还在，但不能继续占着触摸区，
                    // 否则收起后系统边缘返回手势会被输入法吃掉
                    // （用户反馈：收起键盘后侧滑失效，杀进程才好、重开输入法又坏）。
                    contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                    visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    touchableRegion.setEmpty()
                } else {
                    // 全部用「窗口内相对坐标」：TOUCHABLE_INSETS_REGION 的坐标是窗口内的，
                    // 用屏幕坐标（getLocationOnScreen）会把 region 顶抬高一个窗口偏移
                    // （窗口收缩后正好把键盘上半划出触摸区 → 一半穿透）。
                    val wloc = IntArray(2)
                    if (::mInputView.isInitialized) mInputView.getLocationInWindow(wloc)
                    val kbLoc = IntArray(2)
                    if (::mInputView.isInitialized) mInputView.mSkbRoot.getLocationInWindow(kbLoc)
                    val kbW = if (::mInputView.isInitialized) mInputView.mSkbRoot.width else 0
                    val kbH = if (::mInputView.isInitialized) mInputView.mSkbRoot.height else 0
                    // contentTopInsets 决定 App 的输入框贴在哪：用键盘本体（mSkbRoot）
                    // 的窗口 y，输入框贴键盘实际顶边，候选栏/待编辑区浮在它上方。
                    contentTopInsets = kbLoc[1]
                    // 触摸区：窗口高度固定（wrap_content 原行为，含全面屏底部条），窗口比
                    // 内容高时（顶部空带/底部条），用精确 REGION 只圈「键盘本体+底条」：
                    // region 之外不认领触摸 → 无死区，键盘全可点。候选栏（待编辑区）在
                    // mSkbRoot 内部，一起被圈住。
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    touchableRegion.set(kbLoc[0], kbLoc[1], kbLoc[0] + kbW, kbLoc[1] + kbH)
                    val dm = resources.displayMetrics
                    com.yuyan.inputmethod.util.ImeLog.d(
                        "[display] w=${dm.widthPixels} h=${dm.heightPixels} density=${dm.density} " +
                            "orientation=${resources.configuration.orientation} " +
                            "inputView=${wloc[0]},${wloc[1]},${wloc[0] + mInputView.width},${wloc[1] + mInputView.height} " +
                            "skbRoot=${kbLoc[0]},${kbLoc[1]},${kbLoc[0] + kbW},${kbLoc[1] + kbH} " +
                            (if (::mInputView.isInitialized) mInputView.mSkbCandidatesBarView.debugSize() else "")
                    )
                }
            } else if (::mCandidateView.isInitialized) {
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
        if (!::mCandidateView.isInitialized) return
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
        // 不显示系统级的独立候选视图（onCreateCandidatesView 提供的那个）。
        //
        // 系统给 IME 的窗口高度 = 输入视图 + 候选视图，候选视图（高度正好是一条候选栏）
        // 一显隐，窗口内容区就跟着变 133 —— 实测输入视图高度在 1954/1821 之间跳、
        // 键盘被挤压、最下面一行被裁（悬浮键盘打字时尤其明显）。
        // 语燕的候选词本来就画在输入视图里的 CandidatesBar 上，这个独立候选视图是多余的。
        setCandidatesViewShown(false)
        com.yuyan.inputmethod.util.ImeLog.d(
            "[hw] hardwareKeyboard=$hardwareKeyboard soft=$isSoftKeyboard" +
                " candView=${if (::mCandidateView.isInitialized) mCandidateView.visibility else -1}"
        )
        currentInputConnection.requestCursorUpdates(if(isHardwareKeyboard)InputConnection.CURSOR_UPDATE_MONITOR else 0)
    }

}
