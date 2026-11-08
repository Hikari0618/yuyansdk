package com.yuyan.imemodule.ui.fragment.theme

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayout
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.CustomSlideSymbols
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.resources.styledDrawable

/**
 * 主键盘上滑符号的自定义页。
 *
 * 中文/英文各一套，每套又分「数字行开（上滑符号）」和「数字行关（上滑数字）」两个独立桶，
 * 两个桶互不影响。改动先留在内存里，点「保存」并二次确认后才落盘；直接退出有改动会先问一句。
 */
class CustomSlideSymbolActivity : AppCompatActivity() {

    private class Editing {
        val symbol = mutableMapOf<Int, String>()
        val number = mutableMapOf<Int, String>()
    }

    private lateinit var toolbar: Toolbar
    private lateinit var tabLayout: TabLayout
    private lateinit var modeTabLayout: TabLayout
    private lateinit var modeHint: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var bottomBar: LinearLayout

    private var chinese = true
    /** 编辑「数字行开」那套还是「关」那套；只决定这里改哪个桶，不动键盘设置 */
    private var symbolMode = true

    private val editedZh = Editing()
    private val editedEn = Editing()
    private var savedZh = CustomSlideSymbols.Buckets()
    private var savedEn = CustomSlideSymbols.Buckets()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedZh = CustomSlideSymbols.buckets(true)
        savedEn = CustomSlideSymbols.buckets(false)
        editedZh.symbol.putAll(savedZh.symbol)
        editedZh.number.putAll(savedZh.number)
        editedEn.symbol.putAll(savedEn.symbol)
        editedEn.number.putAll(savedEn.number)
        symbolMode = CustomSlideSymbols.numberLineOn()

        enableEdgeToEdge()
        setContentView(buildUi())
        rebuildList()
    }

    private fun buildUi(): View {
        toolbar = Toolbar(this).apply {
            setBackgroundColor(styledColor(android.R.attr.colorPrimary))
            elevation = dp(4f)
            setTitle(R.string.keyboard_custom_slide_symbol)
        }
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        modeHint = TextView(this).apply {
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(dp(20), dp(6), dp(20), dp(10))
        }

        tabLayout = TabLayout(this).apply {
            addTab(newTab().setText(R.string.custom_slide_symbol_chinese))
            addTab(newTab().setText(R.string.custom_slide_symbol_english))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    chinese = tab.position == 0
                    rebuildList()
                }

                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
        }

        modeTabLayout = TabLayout(this).apply {
            addTab(newTab().setText(R.string.custom_slide_symbol_mode_symbol))
            addTab(newTab().setText(R.string.custom_slide_symbol_mode_number))
        }
        modeTabLayout.getTabAt(if (symbolMode) 0 else 1)?.select()
        modeTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                symbolMode = tab.position == 0
                rebuildList()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        val scrollView = ScrollView(this).apply {
            addView(listContainer)
        }

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(barButton(R.string.reset) { confirmReset() }, barParams())
            addView(barButton(R.string.cancel) { confirmExit() }, barParams())
            addView(barButton(R.string.save) { confirmSave() }, barParams())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tabLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(modeTabLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(modeHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            toolbar.setPadding(toolbar.paddingLeft, statusBars.top, toolbar.paddingRight, toolbar.paddingBottom)
            bottomBar.setPadding(dp(12), dp(8), dp(12), dp(8) + navBars.bottom)
            windowInsets
        }
        onBackPressedDispatcher.addCallback(this) { confirmExit() }
        return root
    }

    private fun barButton(title: Int, onClick: () -> Unit) = Button(this).apply {
        setText(title)
        setOnClickListener { onClick() }
    }

    private fun barParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun rebuildList() {
        modeHint.setText(
            if (symbolMode) R.string.custom_slide_symbol_mode_hint_symbol
            else R.string.custom_slide_symbol_mode_hint_number
        )
        listContainer.removeAllViews()
        CustomSlideSymbols.keyOrder().forEach { code ->
            val keyLabel = CustomSlideSymbols.displayName(chinese, code)
            if (keyLabel.isEmpty()) return@forEach
            listContainer.addView(
                createRow(keyLabel, currentValue(code)) {
                    showEditDialog(code, keyLabel)
                }
            )
            listContainer.addView(divider())
        }
    }

    /** 当前正在编辑的那个桶 */
    private fun activeMap(): MutableMap<Int, String> {
        val editing = if (chinese) editedZh else editedEn
        return if (symbolMode) editing.symbol else editing.number
    }

    /** 列表里显示的值：改过的看改动，没改的看这套预设的默认值 */
    private fun currentValue(code: Int): String {
        val edited = activeMap()
        if (edited.containsKey(code)) return edited.getValue(code)
        return CustomSlideSymbols.defaultOf(chinese, symbolMode, code)
    }

    private fun createRow(keyLabel: String, symbol: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = styledDrawable(android.R.attr.selectableItemBackground)
        setOnClickListener { onClick() }
        addView(TextView(this@CustomSlideSymbolActivity).apply {
            text = keyLabel
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@CustomSlideSymbolActivity).apply {
            text = symbol
            textSize = 20f
            setTextColor(styledColor(android.R.attr.colorAccent))
        })
    }

    private fun divider() = View(this).apply {
        background = styledDrawable(android.R.attr.listDivider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun showEditDialog(code: Int, keyLabel: String) {
        val current = currentValue(code)
        val input = EditText(this).apply {
            setText(current)
            setSelection(current.length)
            hint = getString(R.string.custom_slide_symbol_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(4))
            isSingleLine = true
        }
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.custom_slide_symbol_edit_title, keyLabel))
            .setView(wrapper)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.setting_ime_keyboard_height_sure, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 留空 = 这个键在这套里没有上滑，要能存下去
                val symbol = CustomSlideSymbols.normalize(input.text.toString())
                val edited = activeMap()
                if (symbol == CustomSlideSymbols.defaultOf(chinese, symbolMode, code)) {
                    edited.remove(code)
                } else {
                    edited[code] = symbol
                }
                rebuildList()
                dialog.dismiss()
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun editingToBuckets(editing: Editing) =
        CustomSlideSymbols.Buckets(editing.symbol.toMap(), editing.number.toMap())

    private fun confirmSave() {
        if (!isDirty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.keyboard_custom_slide_symbol)
            .setMessage(R.string.custom_slide_symbol_save_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val zh = editingToBuckets(editedZh)
                val en = editingToBuckets(editedEn)
                CustomSlideSymbols.save(zh, en)
                savedZh = zh
                savedEn = en
                reloadKeyboards()
                toast(R.string.custom_slide_symbol_saved, Toast.LENGTH_LONG)
                finish()
            }
            .show()
    }

    private fun confirmReset() {
        val language = getString(if (chinese) R.string.custom_slide_symbol_chinese else R.string.custom_slide_symbol_english)
        val mode = getString(if (symbolMode) R.string.custom_slide_symbol_mode_symbol else R.string.custom_slide_symbol_mode_number)
        AlertDialog.Builder(this)
            .setTitle(R.string.reset)
            .setMessage(getString(R.string.custom_slide_symbol_reset_confirm_one, language, mode))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset) { _, _ ->
                // 只清当前这一套（当前语言 + 当前模式），其余三套保持不动
                activeMap().clear()
                val zh = editingToBuckets(editedZh)
                val en = editingToBuckets(editedEn)
                CustomSlideSymbols.save(zh, en)
                savedZh = zh
                savedEn = en
                rebuildList()
                reloadKeyboards()
                Toast.makeText(
                    this,
                    getString(R.string.custom_slide_symbol_reset_one_done, language, mode),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // 用自定义 View 当菜单项，纯文字项在 Toolbar 里太小了
        menu.add(R.string.custom_slide_symbol_more).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            actionView = TextView(this@CustomSlideSymbolActivity).apply {
                text = getString(R.string.custom_slide_symbol_more)
                textSize = 26f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(styledColor(android.R.attr.colorControlNormal))
                setPadding(dp(16), dp(6), dp(16), dp(6))
                setOnClickListener { showConfigMenu(it) }
            }
        }
        return true
    }

    private fun showConfigMenu(anchor: View) {
        lateinit var popup: PopupWindow
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(styledColor(android.R.attr.colorBackground))
            }
            addView(popupItem(R.string.custom_slide_symbol_export) {
                popup.dismiss()
                exportConfig()
            })
            addView(popupItem(R.string.custom_slide_symbol_import) {
                popup.dismiss()
                importConfig()
            })
        }
        popup = PopupWindow(content, dp(190), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            animationStyle = R.style.Anim_MenuExpandDown
        }
        popup.showAsDropDown(anchor, 0, dp(4))
    }

    private fun popupItem(title: Int, onClick: () -> Unit) = TextView(this).apply {
        setText(title)
        textSize = 15f
        setTextColor(styledColor(android.R.attr.textColorPrimary))
        setBackground(styledDrawable(android.R.attr.selectableItemBackground))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
    }

    private fun exportConfig() {
        val text = CustomSlideSymbols.exportText()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("yuyan", text))
        toast(R.string.custom_slide_symbol_export_done)
    }

    private fun importConfig() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrBlank() || !CustomSlideSymbols.importText(text)) {
            toast(R.string.custom_slide_symbol_import_failed)
            return
        }
        reloadFromStore()
        rebuildList()
        reloadKeyboards()
        toast(R.string.custom_slide_symbol_import_done)
    }

    /** 导入/重置后把编辑状态拉回与存储一致，避免被当成未保存改动 */
    private fun reloadFromStore() {
        savedZh = CustomSlideSymbols.buckets(true)
        savedEn = CustomSlideSymbols.buckets(false)
        listOf(editedZh, editedEn).forEach { it.symbol.clear(); it.number.clear() }
        editedZh.symbol.putAll(savedZh.symbol)
        editedZh.number.putAll(savedZh.number)
        editedEn.symbol.putAll(savedEn.symbol)
        editedEn.number.putAll(savedEn.number)
    }

    private fun confirmExit() {
        if (!isDirty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.custom_slide_symbol_unsaved_title)
            .setMessage(R.string.custom_slide_symbol_unsaved_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.custom_slide_symbol_exit) { _, _ -> finish() }
            .show()
    }

    private fun isDirty() =
        editingToBuckets(editedZh) != savedZh || editingToBuckets(editedEn) != savedEn

    /** 键盘缓存按主题/皮肤各存一份，改完要清掉，下次弹键盘才会用新的符号 */
    private fun reloadKeyboards() {
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.clearKeyboard()
    }

    private fun toast(title: Int, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, title, duration).show()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            confirmExit()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
