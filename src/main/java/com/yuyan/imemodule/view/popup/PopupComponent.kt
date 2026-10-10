
package com.yuyan.imemodule.view.popup

import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import java.util.LinkedList

class PopupComponent private constructor(){
    private var showingEntryUi:PopupEntryUi? = null
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    private var showingContainerUi:PopupContainerUi? = null

    private val popupRadius by lazy {
        ThemeManager.prefs.keyRadius.getValue().toFloat()
    }

    val root by lazy {
        Launcher.instance.context.frameLayout {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = false
            isFocusable = false
        }
    }

    companion object{
        private var instance:PopupComponent?=null
            get() {
                if (field ==null) field = PopupComponent()
                return field
            }
        fun get():PopupComponent{
            return instance!!
        }
    }

    fun showPopup(content: String, bounds: Rect) {
        showingEntryUi?.apply {
            lastShowTime = System.currentTimeMillis()
            setText(content)
            return
        }
        val popup = (freeEntryUi.poll()
            ?: PopupEntryUi(Launcher.instance.context)).apply {
            lastShowTime = System.currentTimeMillis()
            setBackground(ThemeManager.activeTheme, popupRadius)
            setText(content)
        }
        root.apply {
            val (bottom, left) = anchorMargins(bounds)
            add(popup.root, lParams(bounds.width(), bounds.height()) {
                bottomMargin = bottom
                leftMargin = left
            })
        }
        showingEntryUi = popup
    }

    fun showKeyboard(label: String, labelSmall: String, bounds: Rect) {
        showingEntryUi?.setText("") ?: showPopup("", bounds)
        var labels =  (PopupSmallPreset[labelSmall] ?: emptyArray<String>()).plus(PopupPreset[label] ?: emptyArray())
        if(labels.isEmpty())labels = PopupPreset[labelSmall] ?: emptyArray()
        if(labels.isNotEmpty()) {
            reallyShowKeyboard(labels, bounds)
        } else {
            dismissPopup()
        }
    }

    fun showKeyboardMenu(mCurrentKey: SoftKey, bounds: Rect, distanceY: Float) {
        val key = when(mCurrentKey.code) {
            InputModeSwitcher.USER_KEYCODE_LANG ->  Pair(PopupMenuMode.SwitchIME, "🌐")
            InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ->  Pair(PopupMenuMode.EMOJI, "😆")
            KeyEvent.KEYCODE_SHIFT_LEFT -> {
                Pair(PopupMenuMode.EnglishCell, if(AppPrefs.getInstance().input.abcSearchEnglishCell.getValue()) "直输模式" else "拼写模式")
            }
            KeyEvent.KEYCODE_DEL -> Pair(PopupMenuMode.Clear,  "🔙 上滑清空")
            InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> Pair(PopupMenuMode.Move,  "")
            else ->  Pair(PopupMenuMode.Enter,  "↩️ 换行")
        }
        showingEntryUi?.setText("") ?: showPopup("", bounds)
        reallyMenuKeyboard(key, bounds, mCurrentKey.code != KeyEvent.KEYCODE_DEL)
    }

    fun onGestureEvent(distance: Float) {
        showingContainerUi?.onGestureEvent(distance)
    }

    private fun reallyMenuKeyboard(key: Pair<PopupMenuMode, String>, bounds: Rect, isSelect: Boolean,) {
        val popupWidth = EnvironmentSingleton.instance.skbWidth.div(10) * key.second.length / 2
        val keyboardUi = PopupKeyboardMenuUi(bounds, { dismissPopup() }, popupRadius, popupWidth, isSelect, key)
        root.apply {
            val (bottom, left) = anchorMargins(bounds)
            add(keyboardUi.root, lParams {
                bottomMargin = bottom
                leftMargin = left + keyboardUi.offsetX
            })
        }
        dismissPopup()
        showingContainerUi= keyboardUi
    }

    private fun reallyShowKeyboard(keys: Array<String>, bounds: Rect) {
        val popupWidth = EnvironmentSingleton.instance.skbWidth.div(10)
        val keyboardUi = PopupKeyboardUi(bounds, { dismissPopup() }, popupRadius, popupWidth, keys)
        root.apply {
            val (bottom, left) = anchorMargins(bounds)
            add(keyboardUi.root, lParams {
                bottomMargin = bottom
                leftMargin = left + keyboardUi.offsetX
            })
        }
        dismissPopup()
        showingContainerUi= keyboardUi
    }

    fun changeFocus(x: Float, y: Float): Boolean {
        return showingContainerUi?.changeFocus(x, y) ?: false
    }

    fun triggerFocused(): Pair<PopupMenuMode, String> {
        return showingContainerUi?.onTrigger()?:Pair(PopupMenuMode.None, "")
    }

    /**
     * 实时计算气泡相对「气泡根视图」的锚点边距。
     *
     * [root] 在 InputView 里被 addRule(ALIGN_BOTTOM/ALIGN_LEFT, mSkbRoot)，
     * 即它的左下角恒等于 mSkbRoot 的左下角；而 bounds 是 SoftKey 在「键盘视图」内的坐标。
     * 原来用 skbAreaHeight / leftMarginWidth 这两个静态设计常量换算，键盘一旦悬浮/位移
     * 就整体错位。这里改成用键盘视图和 mSkbRoot 的 getLocationInWindow 实时换算，
     * 键盘被移到哪里气泡就跟到哪里。
     * 返回 (bottomMargin, leftMargin)。
     */
    private fun anchorMargins(bounds: Rect): Pair<Int, Int> {
        val inputView = root.parent as? InputView
        val skbRoot = inputView?.mSkbRoot
        val keyboardView = skbRoot?.findViewById<View>(R.id.skb_input_keyboard_view)
        if (skbRoot != null && keyboardView != null) {
            val kbLoc = IntArray(2).also { keyboardView.getLocationInWindow(it) }
            val skbLoc = IntArray(2).also { skbRoot.getLocationInWindow(it) }
            val keyCenterY = kbLoc[1] + (bounds.top + bounds.bottom) / 2
            val bottomMargin = (skbLoc[1] + skbRoot.height) - keyCenterY
            val leftMargin = (kbLoc[0] + bounds.left) - skbLoc[0]
            return bottomMargin to leftMargin
        }
        // 退化：拿不到键盘视图时仍按旧的设计常量估算，避免气泡直接消失
        return (EnvironmentSingleton.instance.skbAreaHeight - (bounds.top + bounds.bottom) / 2) to
            (EnvironmentSingleton.instance.leftMarginWidth + bounds.left)
    }

    fun dismissPopup() {
        dismissPopupContainer()
        showingEntryUi?.also {
            showingEntryUi = null
            root.removeView(it.root)
            freeEntryUi.add(it)
        }
    }

    private fun dismissPopupContainer() {
        showingContainerUi?.also {
            showingContainerUi = null
            root.removeView(it.root)
        }
    }
}
