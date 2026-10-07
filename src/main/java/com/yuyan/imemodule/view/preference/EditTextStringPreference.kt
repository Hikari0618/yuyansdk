
package com.yuyan.imemodule.view.preference

import android.content.Context
import android.content.res.TypedArray
import android.text.InputType
import android.view.Gravity
import androidx.preference.EditTextPreference

/**
 * 多行文本偏好（例如「每行一个正则表达式」），持久化 String。
 * 对应 androidx 的 EditTextPreference，但对话框里的输入框是多行、顶部对齐。
 */
class EditTextStringPreference(context: Context) : EditTextPreference(context) {

    private var defValue: String = ""

    /** 输入框提示语 */
    var hint: String = ""

    /** 是否多行输入（默认多行） */
    var multiline: Boolean = true

    private val currentValue: String
        get() = getPersistedString(defValue)

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? {
        return a.getString(index) ?: ""
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        defValue = defaultValue as? String ?: getPersistedString("")
    }

    init {
        setOnBindEditTextListener {
            it.setText(currentValue)
            it.hint = hint
            if (multiline) {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                it.setSingleLine(false)
                it.minLines = 4
                it.gravity = Gravity.TOP or Gravity.START
            }
        }
    }

    object SimpleSummaryProvider : SummaryProvider<EditTextStringPreference> {
        override fun provideSummary(preference: EditTextStringPreference): CharSequence {
            val v = preference.currentValue
            return if (v.isBlank()) "未设置" else v.replace("\n", " ").take(40)
        }
    }
}
