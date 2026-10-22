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
    /** 已经应用过主题（initView 里的皮肤缩放）的几何签名；同几何不再重复 initView。 */
    private var themeAppliedSig = Int.MIN_VALUE
    /** 显示前发现几何/模式不一致：等视图 attach 后再重建（不要在 setInputView 里直接 initView）。 */
    private var pendingGeometryRebuild = false
    private lateinit var mCandidateView: CandidateView
    private val onThemeChangeListener = OnThemeChangeListener { _: Theme? ->
        if (isHardwareKeyboard) { if (::mCandidateView.isInitialized) mCandidateView.updateTheme() }
        else if (::mInputView.isInitialized) {
            mInputView.updateTheme()
            // 已缓存的键盘容器（英文/符号/设置等）不会自己跟着主题变 ——
            // 不一起刷新，系统切深浅色后切过去还是上一个模式的颜色。
            KeyboardManager.instance.updateThemeForAllKeyboards()
        }
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
        // IME 侧 initData() 统一用服务上下文算几何（见 EnvironmentSingleton.imeContext 注释）
        EnvironmentSingleton.instance.imeContext = this
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

    /**
     * 键盘每次开始输入时自愈一次：**引擎会话如果丢了方案，按键会被引擎全部拒绝**，
     * 表现为「字母直接上屏、没有待编辑区」，用户只能靠手动换一次方案才恢复。
     *
     * 会话为什么会没有方案（三条都是实测过的路径）：
     *   1. 部署：RimeDeployUtils.deploy() 里 Rime.destroy() + startup(fullCheck=true)
     *      重建了会话，而 Engine::Startup 只 create_session、**从不 select_schema**；
     *   2. 进程被系统回收后重建：Launcher 里那次 initImeSchema 若碰上「方案文件/部署
     *      还没就绪」，select_schema 会失败（Launcher 里「解决词库复制慢，导致先调用
     *      初始化问题」的注释正是在跟这件事打架）；
     *   3. 任何原因导致的会话重建（息屏久了被回收、切换输入框等）。
     *
     * 这里成本极低（键盘弹出时一次 JNI 读），但能把上面全部情况兜住 ——
     * 不依赖「用户自己想起来换一次方案」。
     */
    private fun ensureRimeSchemaReady() {
        try {
            if (com.yuyan.inputmethod.core.Rime.getCurrentRimeSchema().isNotEmpty()) return
            val pref = com.yuyan.imemodule.prefs.AppPrefs.getInstance().internal.pinyinModeRime.getValue()
            com.yuyan.inputmethod.core.Kernel.initImeSchema(pref)
            com.yuyan.inputmethod.util.ImeLog.d(
                "[life] 引擎会话无方案 → 自愈重选 '$pref'，结果='${com.yuyan.inputmethod.core.Rime.getCurrentRimeSchema()}'"
            )
        } catch (e: Throwable) {
            com.yuyan.inputmethod.util.ImeLog.d("[life] 方案自愈失败: " + android.util.Log.getStackTraceString(e))
        }
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        YuyanEmojiCompat.setEditorInfo(editorInfo)
        handleHardwareKeyboard()
        ensureRimeSchemaReady()
        if (isHardwareKeyboard && ::mCandidateView.isInitialized)mCandidateView.onStartInput(editorInfo, restarting)
        super.onStartInput(editorInfo, restarting)
    }

    /**
     * 显示键盘前把几何对齐到「当前真实配置」。
     *
     * 横竖屏来回切之后 env 可能停在旧方向：IME 侧有若干 initData() 调用点，时机/上下文不同
     * 就会用旧 metrics 算出一套尺寸，而键盘容器高度是按 env.skbHeight 钉死的（BaseContainer
     * / InputViewParent.onMeasure 都是 EXACTLY）—— 于是回到横屏悬浮模式时按键区仍是竖屏
     * 非悬浮的尺寸，肉眼就是「键盘内容巨大无比」。
     *
     * 关键：必须在【窗口按 env 设高度之前】调用（setInputView 里）。否则会先按旧 env 把窗口
     * 设成 WRAP_CONTENT、紧接着自愈又改成 MATCH_PARENT —— 两次窗口属性变化就是「闪一下」。
     */
    private fun ensureGeometrySynced(fallbackView: View? = null) {
        val env = EnvironmentSingleton.instance
        val cfg = resources.configuration
        val land = cfg.screenWidthDp > cfg.screenHeightDp
        val floatNow = env.floatModeNow
        val iv: InputView? = when {
            ::mInputView.isInitialized -> mInputView
            fallbackView is InputView -> fallbackView
            else -> null
        }
        // 「布局实际用的模式」与「当前方向该用的模式」不一致才算需要自愈 —— 只比对 env
        // 自己的 isLandscape 会漏掉「布局已经按另一种模式建好」的情况。
        val layoutFloat = iv?.isFloatLayout ?: env.floatModeNow
        val mismatch = env.isLandscape != land || layoutFloat != floatNow ||
            env.skbHeight <= 0 || env.skbWidth <= 0
        if (!mismatch) return
        val oldLand = env.isLandscape
        val oldH = env.skbHeight
        // 只重算几何；真正的重建（键位/内边距/移动条）等视图 attach 后在 onStartInputView 里做 ——
        // 在 setInputView（未 attach/未测量）里 initView 会把悬浮布局建坏（贴角/无移动条）。
        env.initData(this)
        pendingGeometryRebuild = true
        com.yuyan.inputmethod.util.ImeLog.d(
            "[env-fix] 几何自愈 land=$land float=$floatNow " +
                "skbW=${env.skbWidth} skbH=${env.skbHeight} area=${env.heightForCandidatesArea} " +
                "(旧 land=$oldLand skbH=$oldH)"
        )
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        ensureRimeSchemaReady()
        ensureGeometrySynced()
        // 视图此时已 attach：把 setInputView 里发现的几何/模式不一致一次性重建掉
        if (pendingGeometryRebuild) {
            pendingGeometryRebuild = false
            if (::mInputView.isInitialized) mInputView.rebuildForGeometryChange()
        }
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
        val env = EnvironmentSingleton.instance
        val sigBefore = env.geometrySignature
        env.initData(baseContext)
        val changed = env.geometrySignature != sigBefore
        val t1 = android.os.SystemClock.elapsedRealtime()
        if (isSoftKeyboard && ::mInputView.isInitialized) {
            // 几何真的变了才重算/重建：旋转时 setInputView 里已经自愈过一遍时，这里是空转，
            // 重复走一遍会让底部移动条重建 —— 表现为键盘“闪一下”。
            if (changed) {
                // 几何变了：整体重建（键位矩形/内边距/移动条/窗口高度/insets 一次性换掉）。
                // 只重算键位而不重建布局，会出现「内容按旧尺寸画、窗口按新尺寸裁」——
                // 也就是底部一行/右侧一列被裁掉、只有「重置键盘高度」才恢复的那种状态。
                mInputView.rebuildForGeometryChange()
                themeAppliedSig = env.geometrySignature
            } else if (applyTheme && env.geometrySignature != themeAppliedSig) {
                // 120ms 后的兜底：几何没变，但这套几何还没做过主题缩放
                mInputView.initView(baseContext)
                themeAppliedSig = env.geometrySignature
            }
            com.yuyan.inputmethod.util.ImeLog.d(
                "[perf] relayout(applyTheme=$applyTheme) changed=$changed " +
                    "initData=${t1 - t0} total=${android.os.SystemClock.elapsedRealtime() - t0}"
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
        // 先把几何对齐到当前方向，再让下面 applyInputViewHeight 决定窗口高度：
        // 否则会先按旧 env 设 WRAP_CONTENT、紧接着自愈再改 MATCH_PARENT，
        // 窗口属性连改两次 —— 这就是「键盘闪一下」的来源。
        ensureGeometrySynced(view)
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
    /**
     * 几何变化后的统一收尾：窗口高度按当前模式定一次，并让 App 重新取 insets。
     * 非悬浮 → 输入框贴在键盘顶边（随高度变化）；悬浮 → contentTop = 屏幕底，输入框不动。
     * 之前切悬浮/改高度都没调 updateInputViewShown，App 一直用旧的 insets，
     * 表现就是「切到悬浮了输入框还被顶起」「拖高度时输入框不跟着走」。
     */
    fun onGeometryApplied() {
        if (!::mInputView.isInitialized) return
        applyInputViewHeight(mInputView)
        if (isInputViewShown) updateInputViewShown()
    }

    private fun applyInputViewHeight(view: View) {
        // 模式以「布局实际采用的值」为准（见 InputView.isFloatLayout）；但若已判定需要重建
        // （pendingGeometryRebuild），窗口要按目标模式先定好，重建后自然一致。
        val floatMode = if (pendingGeometryRebuild) {
            EnvironmentSingleton.instance.floatModeNow
        } else {
            (view as? InputView)?.isFloatLayout ?: EnvironmentSingleton.instance.floatModeNow
        }
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

    /**
     * 常用语编辑器（EditPhrasesView）在 InputView 里是 `addRule(ABOVE, mSkbRoot)` —— 挂在键盘
     * **上方**，不在 mSkbRoot 的矩形内。而触摸区只圈 mSkbRoot，于是编辑器整块落在触摸区外：
     * 点编辑器会直接穿透到下面的 App（用户实测「常用语编辑界面触摸穿透」）。
     * 编辑器显示时把它并进触摸区即可。
     */
    private fun unionAddPhrasesRegion(region: android.graphics.Region) {
        if (!::mInputView.isInitialized) return
        if (!mInputView.isAddPhrases) return
        val editor = mInputView.mAddPhrasesLayout
        if (!editor.isAttachedToWindow) return
        if (editor.width <= 0 || editor.height <= 0) return
        val loc = IntArray(2)
        editor.getLocationInWindow(loc)
        region.union(
            android.graphics.Rect(loc[0], loc[1], loc[0] + editor.width, loc[1] + editor.height)
        )
    }


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
                    unionAddPhrasesRegion(touchableRegion)
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
                    // 常用语编辑器挂在键盘上方，必须一起圈进来（否则点它穿透到下面的 App）
                    unionAddPhrasesRegion(touchableRegion)
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
        if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.onWindowShown()
        // 浮键盘开关可能在两次 startInput 之间被切换，每次显示窗口时重新定高
        if (isSoftKeyboard && ::mInputView.isInitialized) applyInputViewHeight(mInputView)
        super.onWindowShown()
    }

    override fun onWindowHidden() {
        // 输入法窗口隐藏时收起长按菜单（非聚焦弹窗挂在 IME 窗口上，窗口没了要主动收，
        // 否则下次唤起键盘时可能残留/闪一下）
        com.yuyan.imemodule.view.dismissImeItemMenu()
        if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.onWindowHidden()
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // 【重要】这里不要再做 finishComposingText() 之类的收尾。
        //
        // 曾按代码审查建议加过收尾（结束组合态 + requestCursorUpdates(0)），但用户实测：
        // 触发「退格长按左滑恢复被删文字」之后，输入一个字会立刻消失。
        // 原因是 onFinishInput 在某些操作下会被触发（恢复被删文字会重启输入），
        // 此时结束组合态会把用户正在输入的字直接收掉 —— 表现为输入即消失。
        // 输入会话的收尾交给系统默认行为即可，这里只留一条日志便于确认触发时机。
        com.yuyan.inputmethod.util.ImeLog.d("[life] onFinishInput 触发（不做组合态收尾）")
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
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    /**
     * 向输入框提交预选词
     */
    fun setComposingText(text: CharSequence) {
        currentInputConnection?.setComposingText(text, 1)
    }


    /**
     * 结束提交预选词
     */
    fun finishComposingText() {
        currentInputConnection?.finishComposingText()
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String) {
        currentInputConnection?.commitText(StringUtils.converted2FlowerTypeface(text), 1)
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String, newCursorPosition: Int) {
        currentInputConnection?.commitText(StringUtils.converted2FlowerTypeface(text), newCursorPosition)
    }

    fun getTextBeforeCursor(length:Int) : String {
        // currentInputConnection 是平台类型（可空）：无连接时返回空串，避免 NPE
        return currentInputConnection?.getTextBeforeCursor(length, 0)?.toString() ?: ""
    }

    fun commitTextEditMenu(id:Int) {
        currentInputConnection?.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction:Int) {
        currentInputConnection?.performEditorAction(editorAction)
    }

    fun deleteSurroundingText(length:Int) {
        currentInputConnection?.deleteSurroundingText(length, 0)
    }

    fun setSelection(start: Int, end: Int) {
        currentInputConnection?.setSelection(start, end)
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
        currentInputConnection?.requestCursorUpdates(if(isHardwareKeyboard)InputConnection.CURSOR_UPDATE_MONITOR else 0)
    }

}
