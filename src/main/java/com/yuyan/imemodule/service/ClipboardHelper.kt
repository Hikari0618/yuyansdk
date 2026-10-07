package com.yuyan.imemodule.service

import android.content.ClipboardManager.OnPrimaryClipChangedListener
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.clipboardManager
import kotlin.math.max

/**
 * 剪切板监听
 * 移除使用广播监听方式，解决部分手机后台无法启动监听服务异常(API level 31)。
 *
 * 去重/过滤规则照同文输入法（Trime）：
 *  - 过滤规则 clipboardOutputRules：每行一个正则，文本命中任一 → 不记录
 *  - 去重规则 clipboardCompareRules：每行一个正则，把匹配内容从文本里删掉后为空 → 不记录
 *  - 内容去重：同一内容已存在时不再重复插入，只刷新时间（等于提到最前）
 */
object ClipboardHelper : OnPrimaryClipChangedListener {

    fun init() {
        Launcher.instance.context.clipboardManager.addPrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        val prefs = AppPrefs.getInstance().clipboard
        if (!prefs.clipboardListening.getValue()) return
        val item = Launcher.instance.context.clipboardManager.primaryClip?.getItemAt(0) ?: return
        val data = item.text?.toString()?.take(20000) ?: return
        if (data.isBlank()) return
        // 过滤规则：命中任一正则 → 不记录
        if (compileRules(prefs.clipboardOutputRules.getValue()).any { it.containsMatchIn(data) }) return
        // 去重规则：删掉匹配内容后为空（纯空白、纯符号等无意义内容）→ 不记录
        val compareRules = compileRules(prefs.clipboardCompareRules.getValue())
        if (compareRules.isNotEmpty() &&
            compareRules.fold(data) { acc, rule -> rule.replace(acc, "") }.isEmpty()
        ) return

        val dao = DataBaseKT.instance.clipboardDao()
        val exist = dao.findByContent(data)
        if (exist != null) {
            // 内容去重：已存在就不重复插入，只刷新时间；置顶项保持置顶
            if (exist.isKeep != 0) return
            dao.deleteByContent(data)
        }
        dao.insert(Clipboard(content = data))
        val num = max(dao.getCount() - prefs.clipboardHistoryLimit.getValue(), 0)
        dao.deleteOldest(num)
        if (prefs.clipboardSuggestion.getValue()) {
            AppPrefs.getInstance().internal.clipboardUpdateTime.setValue(System.currentTimeMillis())
            AppPrefs.getInstance().internal.clipboardUpdateContent.setValue(data)
        }
    }

    /** 把「每行一个正则」的文本编译成规则表；某行写错只忽略该行，不影响其它行 */
    private fun compileRules(raw: String): List<Regex> =
        raw.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
}
