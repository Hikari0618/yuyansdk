package com.yuyan.imemodule.ui.activity

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.yuyan.imemodule.database.DataBaseKT

/**
 * 剪贴板条目编辑页。
 *
 * 照同文（Trime）的 ClipEditActivity：编辑走独立 Activity，而不是在输入法窗口里塞 EditText。
 * 原因是输入法无法给自己的窗口输入文字（输入法服务不了自己进程内的输入框），
 * 独立 Activity 是普通界面，输入法可以正常输入。
 */
class ClipEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONTENT = "content"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val oldContent = intent.getStringExtra(EXTRA_CONTENT).orEmpty()

        val editText = EditText(this).apply {
            setText(oldContent)
            setSelection(oldContent.length)
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
        }
        val cancelButton = Button(this).apply { text = "取消" }
        val saveButton = Button(this).apply { text = "保存" }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(cancelButton)
            addView(saveButton)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(editText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(buttonRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        cancelButton.setOnClickListener { finish() }
        saveButton.setOnClickListener {
            val newContent = editText.text.toString()
            if (newContent.isNotBlank() && newContent != oldContent) {
                val dao = DataBaseKT.instance.clipboardDao()
                // 按内容定位原条目再改内容（列表里按内容去重，内容就是键）
                dao.findByContent(oldContent)?.let {
                    it.content = newContent
                    dao.update(it)
                }
            }
            finish()
        }
    }
}
