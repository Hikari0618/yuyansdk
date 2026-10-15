package com.yuyan.imemodule.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.view.widget.ImeEditText
import com.yuyan.inputmethod.util.LX17PinYinUtils
import com.yuyan.inputmethod.util.T9PinYinUtils
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

/**
 * 窗口内编辑器的数据源 —— **常用语与剪贴板共用同一个编辑器**，区别只是存回哪张表。
 *
 * 统一成一个编辑器后的好处：
 *  · 两类条目的「编辑」体验完全一致（都在输入法窗口里改，不跳 Activity）；
 *  · 不必再为剪贴板单开一个 Activity（原 ClipEditActivity 还带来副作用：
 *    编辑页返回后列表数据是旧的，得专门做一次重新读库）。
 */
sealed class EditSource {
    /** 常用语（phrase 表）。phrase == null 表示新增。 */
    data class PhraseSource(val phrase: Phrase?) : EditSource()

    /** 剪贴板（clipboard 表）。content 是主键，oldContent 是要修改的那条。 */
    data class ClipSource(val oldContent: String) : EditSource()
}

class EditPhrasesView(context: Context) : LinearLayout(context){

    companion object {
        /** 编辑器最大高度 = 屏幕高度 × 该比例；超出的内容由编辑框自身滚动。
         *  取 0.4 是折中：长内容（如复制的一大段文字）能改，又不至于把键盘挤出屏幕。 */
        private const val MAX_HEIGHT_SCREEN_RATIO = 0.4f
    }

    private var mEtPhrases: ImeEditText
    private var mEtPhrasesQuickCode:ImeEditText
    private var mEtPhrasesTips:TextView
    /** 「提示 + 快捷码」这一行：只有常用语才需要，编辑剪贴板条目时整行隐藏。 */
    private lateinit var mLlTipContainer:LinearLayout

    /** 当前编辑的数据源（常用语 / 剪贴板） */
    private var mSource: EditSource = EditSource.PhraseSource(null)

    /** 是否在编辑剪贴板条目 —— InputView 保存后据此决定回到哪个列表 */
    val isClipboardMode: Boolean get() = mSource is EditSource.ClipSource

    init {
        orientation = VERTICAL
        mEtPhrasesTips = TextView(context).apply {
            setText(R.string.add_phrases_input_tips)
            setPadding(dp(5))
            textSize = 12f
        }
        mEtPhrasesQuickCode = ImeEditText(context).apply {
            setText(R.string.add_phrases_input_tips)
            setPadding(dp(5))
            background = null
            isCursorVisible = true
            isFocusable = true
            isFocusableInTouchMode = true
            inputType
            textSize = 12f
        }
        mLlTipContainer = LinearLayout(context)
        mLlTipContainer.add(mEtPhrasesTips, lParams(weight = 0f))
        mLlTipContainer.add(mEtPhrasesQuickCode, lParams(weight = 1f))
        mEtPhrases = ImeEditText(context).apply {
            background = null
            isCursorVisible = true
            isFocusable = true
            isFocusableInTouchMode = true
            gravity = Gravity.CENTER_VERTICAL
            textSize = 16f
            minLines = 3
            // 内容超过编辑器高度上限（见 onMeasure）时靠自身滚动；不显示滚动条的话
            // 用户会以为字被截断了
            isVerticalScrollBarEnabled = true
            setPadding(dp(10))
            setHint(R.string.add_phrases_input_hint)
        }
        add(mLlTipContainer, lParams(width = matchParent,height = wrapContent, weight = 0f))
        add(mEtPhrases, lParams(width = matchParent,height = wrapContent, weight = 1f))
    }

    /**
     * 编辑器高度上限：内容很长时（典型：复制了一大段文字要改）不要让编辑区顶满整屏 ——
     * 编辑器挂在键盘【上方】，顶满就把键盘整个挤出屏幕/遮死了。
     *
     * 用 AT_MOST 而不是直接钉死高度：内容短时仍按内容高度（不浪费竖向空间），
     * 超长的部分由 mEtPhrases 自己滚动（EditText 本身可滚动，见其 isVerticalScrollBarEnabled）。
     *
     * 说明：触摸区是按「mSkbRoot ∪ 编辑器实际测量到的矩形」算的（ImeService.unionAddPhrasesRegion），
     * 所以这里把编辑器压矮之后触摸区会跟着收，不会留下死区。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeightPx = (resources.displayMetrics.heightPixels * MAX_HEIGHT_SCREEN_RATIO).toInt()
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        )
    }

    fun handleAddPhrasesView() {
        mEtPhrases.requestFocus()
        mEtPhrases.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable) {
                val pinYinHeadChar  = PinyinHelper.getPinYinHeadChar(editable.toString().take(4))
                if(pinYinHeadChar.isNotBlank()) {
                    mEtPhrasesQuickCode.setText(pinYinHeadChar)
                    mEtPhrasesQuickCode.setSelection(pinYinHeadChar.length)
                }
            }
        })
    }

    /** 新增常用语：清空内容并切回「常用语」数据源 */
    fun clearPhrasesContent() {
        mSource = EditSource.PhraseSource(null)
        mLlTipContainer.visibility = VISIBLE
        mEtPhrases.setText("")
        mEtPhrasesQuickCode.setText("")
    }

    /** 编辑现有常用语 */
    fun setExtraData(extra:Phrase) {
        mSource = EditSource.PhraseSource(extra)
        mLlTipContainer.visibility = VISIBLE
        val content = extra.content
        val qwerty = extra.qwerty
        mEtPhrases.setText(content)
        mEtPhrases.setSelection(content.length)
        mEtPhrasesQuickCode.setText(qwerty)
        mEtPhrasesQuickCode.setSelection(qwerty.length)
    }

    /**
     * 编辑剪贴板条目：同一个编辑器，只是把「提示/快捷码」行藏掉
     * （剪贴板条目只有内容，没有快捷码概念）。
     */
    fun setClipboardData(oldContent:String) {
        mSource = EditSource.ClipSource(oldContent)
        mLlTipContainer.visibility = GONE
        mEtPhrasesQuickCode.setText("")
        mEtPhrases.setText(oldContent)
        mEtPhrases.setSelection(oldContent.length)
    }

    /** 回车保存：按当前数据源分别落到 phrase / clipboard 表 */
    fun addPhrasesHandle() {
        val content = mEtPhrases.text.toString()
        if(content.isBlank()) return
        when(val src = mSource) {
            is EditSource.ClipSource -> saveClipboardEntry(src.oldContent, content)
            is EditSource.PhraseSource -> {
                val pinYinHeadChar  = mEtPhrasesQuickCode.text.toString()
                val pinYinHeadT9 = pinYinHeadChar.map { T9PinYinUtils.pinyin2T9Key(it)}.joinToString("")
                val pinYinHeadLX17 = pinYinHeadChar.map { LX17PinYinUtils.pinyin2Lx17Key(it)}.joinToString("")
                val phrase =  Phrase(content = content, t9 = pinYinHeadT9, qwerty = pinYinHeadChar, lx17 = pinYinHeadLX17)
                DataBaseKT.instance.phraseDao().insert(phrase)
                KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
            }
        }
    }

    /**
     * 剪贴板条目保存（语义与原 ClipEditActivity 完全一致）：
     *  · content 是主键，insert 又是 onConflict=REPLACE —— 若改成一条已存在的内容，
     *    会静默覆盖那条记录（连同它的 isKeep/time），所以先按新内容查重，
     *    命中就不覆盖、提示并中止（不关闭编辑器，让用户接着改）；
     *  · content 就是主键，改内容等于换主键，@Update 按新主键找不到行 → 必须先删旧行再插新行。
     */
    private fun saveClipboardEntry(oldContent:String, newContent:String) {
        val dao = DataBaseKT.instance.clipboardDao()
        if (newContent != oldContent) {
            if (dao.findByContent(newContent) != null) {
                // 用 App 上下文弹 Toast（输入法服务上下文在部分 ROM 上会被吞）
                Toast.makeText(Launcher.instance.context, "已存在相同内容的条目，未保存", Toast.LENGTH_SHORT).show()
                return
            }
            val old = dao.findByContent(oldContent)
            if (old != null) {
                dao.deleteByContent(oldContent)
                dao.insert(
                    Clipboard(
                        content = newContent,
                        isKeep = old.isKeep,
                        time = old.time
                    )
                )
            }
        }
        KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
    }

    fun commitText(text:String) {
        val currentView = if(mEtPhrasesQuickCode.isFocused)mEtPhrasesQuickCode else mEtPhrases
        currentView.commitText(text)
    }

    fun sendKeyEvent(keyCode: Int) {
        val currentView = if(mEtPhrasesQuickCode.isFocused)mEtPhrasesQuickCode else mEtPhrases
        when(keyCode){
            KeyEvent.KEYCODE_DEL ->{
                currentView.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                currentView.onKeyUp(keyCode, KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
            KeyEvent.KEYCODE_ENTER ->{
                addPhrasesHandle()
            }
            else -> {
                val unicodeChar: Char = KeyEvent(KeyEvent.ACTION_DOWN, keyCode).unicodeChar.toChar()
                if (unicodeChar != Character.MIN_VALUE)currentView.commitText(unicodeChar.toString())
            }
        }
    }

    fun updateTheme(theme: Theme) {
        val keyTextColor = ThemeManager.activeTheme.keyTextColor
        setBackgroundColor(theme.barColor)
        mEtPhrases.background = GradientDrawable().apply {
            setColor(ThemeManager.activeTheme.keyBackgroundColor)
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ThemeManager.prefs.keyRadius.getValue().toFloat()
        }
        mEtPhrases.setTextColor(keyTextColor)
        mEtPhrases.setHintTextColor(keyTextColor)
        mEtPhrasesTips.setTextColor(keyTextColor)
        mEtPhrasesQuickCode.setTextColor(keyTextColor)
    }
}
