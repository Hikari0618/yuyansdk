package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.keyboard.container.BaseContainer
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.HandwritingContainer
import com.yuyan.imemodule.keyboard.container.InputBaseContainer
import com.yuyan.imemodule.keyboard.container.InputViewParent
import com.yuyan.imemodule.keyboard.container.QwertyContainer
import com.yuyan.imemodule.keyboard.container.SegmentsContainer
import com.yuyan.imemodule.keyboard.container.SettingsContainer
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.imemodule.keyboard.container.T9TextContainer
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.singleton.EnvironmentSingleton

/**
 * 键盘显示管理类
 */
class KeyboardManager {
    enum class KeyboardType {
        T9, QWERTY, LX17, QWERTYABC, NUMBER, SYMBOL, SETTINGS, HANDWRITING, CANDIDATES, ClipBoard, TEXTEDIT, SEGMENTS
    }
    private lateinit var mInputView: InputView
    private lateinit var mKeyboardRootView: InputViewParent
    private val keyboards = HashMap<KeyboardType, BaseContainer?>()
    private lateinit var mCurrentKeyboardName: KeyboardType
    var currentContainer: BaseContainer? = null
        private set

    fun setData(keyboardRootView: InputViewParent, inputView: InputView) {
        com.yuyan.inputmethod.util.ImeLog.d("[perf] KeyboardManager.setData：键盘被整体重建")
        keyboards.clear() // TODO 清空缓存界面，发现调用 PinyinService.onCreateInputView时，原输入界面全部会失效。
        mKeyboardRootView = keyboardRootView
        mInputView = inputView
        // 按键区容器（InputViewParent）是 wrap 的 RelativeLayout，但实测会出现
        // 「父 653 < 子 720」——子视图被裁，而键位网格是按 skbHeight(720) 绘制的，
        // 于是最下面一行被切掉（用户实测：悬浮键盘打字时下缘被裁）。
        // 它里面装的所有键盘容器（BaseContainer/ConstraintLayout）本来就都钉在 skbHeight 上，
        // 所以这里也钉成 skbHeight，任何布局抖动都挤不到键盘。
        syncKeyboardRootHeight()
    }

    /**
     * 按当前 env 的 skbHeight 重设键盘根视图（InputViewParent）的 layoutParams 高度。
     *
     * env（键盘高度比 / 横竖屏）变化后，已被缓存的 InputViewParent 实例不会自动跟着变，
     * 需要外部主动同步一次；否则根视图仍钉在旧高度（症状：改高度后键盘根不跟着变、被裁）。
     * 幂等，可重复调用；未初始化时安全返回。原 setData 里的那段逻辑抽到这里，
     * init 调用路径（setData）行为不变。
     */
    fun syncKeyboardRootHeight() {
        if (!::mKeyboardRootView.isInitialized) return
        val env = EnvironmentSingleton.instance
        if (env.skbHeight > 0) {
            mKeyboardRootView.layoutParams = (mKeyboardRootView.layoutParams ?: return).apply {
                height = env.skbHeight
            }
        }
    }

    fun clearKeyboard() {
        keyboards.clear()
        if (::mInputView.isInitialized) mInputView.initView(mInputView.context)
    }

    /**
     * 主题变化（含系统深浅色切换）时，刷新【所有已缓存】键盘。
     *
     * 容器按类型缓存在 keyboards 里、切走时只置 GONE 不重建，因此它们不会自己跟着主题变：
     * 不刷新就会出现「当前键盘颜色对了，切到英文键盘还是上一个模式的颜色」。
     * 由 ImeService 的主题变化监听器调用（ThemeManager.activeTheme 的 setter 会 fireChange）。
     */
    fun updateThemeForAllKeyboards() {
        val theme: Theme = ThemeManager.activeTheme
        keyboards.values.forEach { it?.updateTheme(theme) }
        currentContainer?.updateTheme(theme)
    }

    /**
     * 横竖屏切换后：复用已缓存容器，只让当前键盘按新几何重新布局（不重建视图）。
     * 原来旋转时走 clearKeyboard() + switchKeyboard()，会把容器和键盘视图全部丢掉重建。
     */
    fun relayoutCurrentKeyboard() {
        if (!::mKeyboardRootView.isInitialized) return
        // 横竖屏切换会改变 env.skbHeight，缓存的根视图高度不会自己跟着变，这里一并同步
        syncKeyboardRootHeight()
        currentContainer?.updateSkbLayout()
        if (::mInputView.isInitialized) mInputView.updateCandidateBar()
    }

    fun switchKeyboard(layout: Int = InputModeSwitcher.skbLayout) {
        val keyboardName = when (layout) {
            0x1000 -> KeyboardType.QWERTY
            0x4000 -> KeyboardType.QWERTYABC
            0x3000 -> KeyboardType.HANDWRITING
            0x5000 -> KeyboardType.NUMBER
            0x6000 -> KeyboardType.LX17
            0x8000 -> KeyboardType.TEXTEDIT
            else -> KeyboardType.T9
        }
        switchKeyboard(keyboardName)
        if (::mInputView.isInitialized)mInputView.updateCandidateBar()
    }

    fun switchKeyboard(keyboardName: KeyboardType) {
        if (!::mKeyboardRootView.isInitialized) {
            com.yuyan.inputmethod.util.ImeLog.d("[kb] switchKeyboard($keyboardName) 被忽略：rootView 未初始化")
            return
        }
        com.yuyan.inputmethod.util.ImeLog.d("[kb] switchKeyboard($keyboardName) 请求")
        var container = keyboards[keyboardName]
        if (container == null) {
            container = when (keyboardName) {
                KeyboardType.CANDIDATES ->  CandidatesContainer(Launcher.instance.context, mInputView)
                KeyboardType.HANDWRITING -> HandwritingContainer(Launcher.instance.context, mInputView)
                KeyboardType.NUMBER -> T9TextContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER)
                KeyboardType.QWERTY -> QwertyContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN)
                KeyboardType.SETTINGS -> SettingsContainer(Launcher.instance.context, mInputView)
                KeyboardType.SYMBOL -> SymbolContainer(Launcher.instance.context, mInputView)
                KeyboardType.QWERTYABC -> QwertyContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC)
                KeyboardType.LX17 -> T9TextContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_LX17)
                KeyboardType.ClipBoard -> ClipBoardContainer(Launcher.instance.context, mInputView)
                KeyboardType.SEGMENTS -> SegmentsContainer(Launcher.instance.context, mInputView)
                KeyboardType.TEXTEDIT -> QwertyContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT)
                else ->  T9TextContainer(Launcher.instance.context, mInputView, AppPrefs.getInstance().internal.inputDefaultMode.getValue() and InputModeSwitcher.MASK_SKB_LAYOUT)
            }
            container.updateSkbLayout()
            keyboards[keyboardName] = container
        }
        // 先更新 currentContainer 再 showView：showView 内部改可见性会立刻触发容器的
        // onVisibilityChanged，此时若 currentContainer 还是旧值，新容器会以为自己「没被显示」。
        currentContainer = container
        mKeyboardRootView.showView(container)
        // 分词页面会自己把候选栏藏掉，切回别的键盘时恢复（候选栏是常驻视图，不恢复就再也不显示）
        if (mInputView.mSkbCandidatesBarView.visibility != android.view.View.VISIBLE) {
            mInputView.mSkbCandidatesBarView.visibility = android.view.View.VISIBLE
        }
        mCurrentKeyboardName = keyboardName
    }

    val isInputKeyboard: Boolean
        get() = currentContainer is InputBaseContainer

    companion object {
        private var mInstance: KeyboardManager? = null
        @JvmStatic
        val instance: KeyboardManager
            get() {
                if (null == mInstance) {
                    mInstance = KeyboardManager()
                }
                return mInstance!!
            }
    }
}