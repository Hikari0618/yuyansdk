package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.MenuAdapter
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.menuSkbFunsPreset
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.SkbFun
import com.yuyan.imemodule.entity.SkbFunItem
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.DoublePinyinSchemaMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.manager.layout.CustomGridLayoutManager
import splitties.dimensions.dp
import java.util.Collections
import java.util.LinkedList

/**
 * 设置键盘容器
 *
 * 设置键盘、切换键盘界面容器。使用RecyclerView + GridLayoutManager。
 */
@SuppressLint("ViewConstructor")
class SettingsContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    private var mRVMenuLayout: RecyclerView? = null
    private var mTheme: Theme? = null
    private var adapter:MenuAdapter? = null
    val funItems: MutableList<SkbFunItem> = LinkedList()   //键盘菜单对象
    init {
        initView(context)
    }

    private fun initView(context: Context) {
        mTheme = activeTheme
        mRVMenuLayout = RecyclerView(context)
        mRVMenuLayout!!.setHasFixedSize(true)
        mRVMenuLayout!!.setItemAnimator(null)
        val count = EnvironmentSingleton.instance.skbWidth/dp(100)
        val layoutManager = CustomGridLayoutManager(context, count)
        mRVMenuLayout!!.setLayoutManager(layoutManager)
        val layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        mRVMenuLayout!!.layoutParams = layoutParams
        this.addView(mRVMenuLayout)
    }

    /**
     * 弹出键盘设置界面
     */
    fun showSettingsView() {
        funItems.clear()
        for(item in DataBaseKT.instance.skbFunDao().getAllMenu()){
            val skbMenuMode = menuSkbFunsPreset[SkbMenuMode.decode(item.name)]
            if(skbMenuMode != null)funItems.add(skbMenuMode)
        }
        adapter = MenuAdapter(context, funItems)
        adapter?.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
            inputView.onSettingsMenuClick(funItems[position].skbMenuMode)
        }
        mRVMenuLayout!!.setAdapter(adapter)
    }

    fun enableDragItem(enable: Boolean) {
        if (enable) {
            val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                    return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0)
                }
                override fun onMove(recyclerView: RecyclerView, oldHolder: RecyclerView.ViewHolder, targetHolder: RecyclerView.ViewHolder): Boolean {
                    //使用集合工具类Collections，分别把中间所有的item的位置重新交换
                    val fromPosition: Int = oldHolder.bindingAdapterPosition //得到拖动ViewHolder的position
                    val toPosition: Int = targetHolder.bindingAdapterPosition //得到目标ViewHolder的position
                    if (fromPosition < toPosition) {
                        for (i in fromPosition until toPosition) {
                            Collections.swap(funItems, i, i + 1)
                        }
                    } else {
                        for (i in fromPosition downTo toPosition + 1) {
                            Collections.swap(funItems, i, i - 1)
                        }
                    }
                    adapter?.notifyItemMoved(fromPosition, toPosition)
                    funItems.forEachIndexed {index, item ->
                        DataBaseKT.instance.skbFunDao().update(SkbFun(name = item.skbMenuMode.name, isKeep = 0, position = index))
                    }
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = true

                override fun isLongPressDragEnabled() = false
            })

            adapter?.dragOverListener = object : MenuAdapter.DragOverListener {
                override fun startDragItem(holder: RecyclerView.ViewHolder) {
                    itemTouchHelper.startDrag(holder)
                }
                override fun onOptionClick(parent: RecyclerView.Adapter<*>?, v: SkbFunItem, position: Int) {
                    val barMenu = DataBaseKT.instance.skbFunDao().getBarMenu(v.skbMenuMode.name)
                    if(barMenu == null){
                        DataBaseKT.instance.skbFunDao().insert(SkbFun(name = v.skbMenuMode.name, isKeep = 1))
                    } else {
                        DataBaseKT.instance.skbFunDao().delete(SkbFun(name = v.skbMenuMode.name, isKeep = 1))
                    }
                    inputView.updateCandidateBar()
                    adapter?.notifyDataSetChanged()
                }
            }
            itemTouchHelper.attachToRecyclerView(mRVMenuLayout)
        } else {
            adapter?.dragOverListener = null
        }
        adapter?.notifyDataSetChanged()
    }

    /** 动态输入选项（同文机制）：从当前方案的 switcher 配置自动读取并展示，
     *  部署好方案后其中的选项（中英、中英标点、半角全角等）自动出现 */
    /** 常见 Rime 开关的中文名（菜单里不再直接显示英文原名） */
    private val RIME_SWITCH_LABELS = mapOf(
        "ascii_mode" to "中英",
        "ascii_punct" to "标点",
        "full_shape" to "全半角",
        "emoji" to "表情",
        "chinese_english" to "翻译",
        "context_reorder" to "上下文调频",
        "abbrev" to "简码",
        "super_tips" to "提示",
        "charset_filter" to "字集",
        "char_priority" to "单字词组",
        "english" to "英文输入"
    )

    fun showRimeSwitchesView() {
        val funItems: MutableList<SkbFunItem> = LinkedList()
        // 读方案 switcher：native 调用单独包一层，异常直接落 ime.log
        val rawSwitches = try {
            com.yuyan.inputmethod.core.Rime.getRimeSwitches()
        } catch (ex: Throwable) {
            com.yuyan.inputmethod.util.ImeLog.d("[switches] getRimeSwitches 异常: " + android.util.Log.getStackTraceString(ex))
            ""
        }
        com.yuyan.inputmethod.util.ImeLog.d("[switches] getRimeSwitches -> ${rawSwitches.length} 字符")
        rawSwitches.lines()
            .filter { it.isNotBlank() }
            .forEach { line ->
                // 格式：key \t 状态0 \t 状态1 ... \t 当前状态下标
                // key 是普通开关名（ascii_mode），或开关组成员列表（s2s,s2t,s2hk,s2tw）
                val p = line.split("\t")
                val key = p[0]
                val cur = p.lastOrNull()?.toIntOrNull() ?: 0
                val states = if (p.size > 2) p.subList(1, p.size - 1) else emptyList()
                val currentLabel = states.getOrElse(cur) {
                    if (cur != 0) "开" else "关"
                }
                val cn = RIME_SWITCH_LABELS[key] ?: ""
                funItems.add(
                    SkbFunItem(
                        if (cn.isEmpty()) currentLabel else "$currentLabel（$cn）",
                        R.drawable.ic_menu_setting,
                        SkbMenuMode.RimeSwitchToggle,
                        key
                    )
                )
            }
        if (funItems.isEmpty()) {
            funItems.add(SkbFunItem("当前方案没有可切换选项", R.drawable.ic_menu_setting, SkbMenuMode.RimeSwitches, ""))
        }
        val adapter = MenuAdapter(context, funItems)
        adapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
            onKeyboardMenuClick(funItems[position])
        }
        mRVMenuLayout!!.setAdapter(adapter)
        com.yuyan.inputmethod.util.ImeLog.d("[switches] adapter 已设置 items=${funItems.size}")
    }
    fun showSkbSelelctModeView() {
        val funItems: MutableList<SkbFunItem> = LinkedList()
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_t9),
                R.drawable.selece_input_mode_py9,
                SkbMenuMode.PinyinT9
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_cn26),
                R.drawable.selece_input_mode_py26,
                SkbMenuMode.Pinyin26Jian
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_hand),
                R.drawable.selece_input_mode_handwriting,
                SkbMenuMode.PinyinHandWriting
            )
        )
        val doublePYSchemaMode = AppPrefs.getInstance().input.doublePYSchemaMode.getValue()
        val doublePinyinSchemaName = when (doublePYSchemaMode) {
            DoublePinyinSchemaMode.flypy -> R.string.double_pinyin_flypy_plus
            DoublePinyinSchemaMode.natural -> R.string.double_pinyin_natural
            DoublePinyinSchemaMode.abc -> R.string.double_pinyin_abc
            DoublePinyinSchemaMode.mspy -> R.string.double_pinyin_mspy
            DoublePinyinSchemaMode.sogou -> R.string.double_pinyin_sougou
            DoublePinyinSchemaMode.ziguang -> R.string.double_pinyin_ziguang
            DoublePinyinSchemaMode.wanxiangPro -> R.string.wanxiang_pro
        }
        funItems.add(
            SkbFunItem(
                mContext.getString(doublePinyinSchemaName),
                R.drawable.selece_input_mode_dpy26,
                SkbMenuMode.Pinyin26Double
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.wanxiang_pro),
                R.drawable.selece_input_mode_dpy26,
                SkbMenuMode.PinyinWanxiangPro
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_pinyin_lx_17),
                R.drawable.selece_input_mode_lx17,
                SkbMenuMode.PinyinLx17
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_stroke),
                R.drawable.selece_input_mode_stroke,
                SkbMenuMode.PinyinStroke
            )
        )
        // 自定义方案（/sdcard/yuyan 导入的任意拼音方案，如万象拼音等）
        com.yuyan.inputmethod.util.RimeWorkspace.customSchemas()
            .filter { it.first != CustomConstant.SCHEMA_ZH_WANXIANG_PRO }
            .forEach { (schemaId, schemaName) ->
                funItems.add(
                    SkbFunItem(
                        schemaName,
                        R.drawable.selece_input_mode_py26,
                        SkbMenuMode.PinyinCustom,
                        schemaId
                    )
                )
            }
        val adapter = MenuAdapter(context, funItems)
        adapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
            onKeyboardMenuClick(funItems[position])
        }
        mRVMenuLayout!!.setAdapter(adapter)
    }

    private fun onKeyboardMenuClick(data: SkbFunItem) {
        val value = when (data.skbMenuMode) {
            SkbMenuMode.RimeSwitchToggle -> {
                // 动态选项：翻转 Rime 开关并刷新列表（data.schemaId 存 switch 名）
                // 开关组（s2s,s2t,s2hk,s2tw）按成员列表轮询到下一项
                val key = data.schemaId
                if (key.contains(",")) {
                    val opts = key.split(",")
                    var cur = 0
                    for (i in opts.indices) {
                        if (com.yuyan.inputmethod.core.Rime.getRimeOption(opts[i])) {
                            cur = i
                            break
                        }
                    }
                    com.yuyan.inputmethod.core.Rime.setOptionGroup(key, (cur + 1) % opts.size)
                } else {
                    val cur = com.yuyan.inputmethod.core.Rime.getRimeOption(key)
                    com.yuyan.inputmethod.core.Rime.setOption(key, !cur)
                }
                showRimeSwitchesView()
                return
            }
            SkbMenuMode.Pinyin26Jian -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, CustomConstant.SCHEMA_ZH_QWERTY)
            SkbMenuMode.PinyinHandWriting -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING, CustomConstant.SCHEMA_ZH_HANDWRITING)
            SkbMenuMode.PinyinLx17 -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_LX17, CustomConstant.SCHEMA_ZH_DOUBLE_LX17)
            SkbMenuMode.PinyinStroke -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_STROKE, CustomConstant.SCHEMA_ZH_STROKE)
            SkbMenuMode.Pinyin26Double -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + AppPrefs.getInstance().input.doublePYSchemaMode.getValue())
            SkbMenuMode.PinyinWanxiangPro -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, CustomConstant.SCHEMA_ZH_WANXIANG_PRO)
            SkbMenuMode.PinyinCustom -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, data.schemaId)
            else -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN, CustomConstant.SCHEMA_ZH_T9)
        }
        InputModeSwitcher.switchModeForSetting(value)
        inputView.resetToIdleState()
    }
}