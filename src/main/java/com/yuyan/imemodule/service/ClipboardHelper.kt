package com.yuyan.imemodule.service

import android.content.ClipboardManager.OnPrimaryClipChangedListener
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.clipboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** 剪贴板落库专用作用域：本 object 与进程同生命周期，任务都是有界的小写入，不会堆积。 */
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init() {
        Launcher.instance.context.clipboardManager.addPrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        val prefs = AppPrefs.getInstance().clipboard
        if (!prefs.clipboardListening.getValue()) return
        val item = Launcher.instance.context.clipboardManager.primaryClip?.getItemAt(0) ?: return
        // 单条上限：同文（Trime）入库时【不做任何截断】（DatabaseBean.fromClipData 存全文），
        // 只在列表/建议条【显示时】截断。这里同样改成"基本不限"——1,000,000 字只是防御
        // 异常超大内容（比如误复制几十 MB）撑爆数据库，正常文本永远碰不到。
        // 旧值 20000 会让长文（小说章节、代码片段、日志）静默丢掉后半截，属于隐性数据丢失。
        val data = item.text?.toString()?.take(1_000_000) ?: return
        if (data.isBlank()) return

        // ★ 规则过滤也要在 IO 线程做 ★
        // 本方法是系统在【主线程】回调的。默认两条规则为空时几乎零成本，但用户一旦在设置里
        // 写了 clipboardOutputRules / clipboardCompareRules，就要对整段文本跑正则（compareRules
        // 还会做 replace 折叠）—— 25 万字的长文本足以把键盘进程主线程卡死几十秒，
        // 表现为输入法唤不起来、系统手势也失灵。所以和落库一起放到 ioScope。
        ioScope.launch {
            // 过滤规则：命中任一正则 → 不记录
            if (compileRules(prefs.clipboardOutputRules.getValue()).any { it.containsMatchIn(data) }) return@launch
            // 去重规则：删掉匹配内容后为空（纯空白、纯符号等无意义内容）→ 不记录
            val compareRules = compileRules(prefs.clipboardCompareRules.getValue())
            if (compareRules.isNotEmpty() &&
                compareRules.fold(data) { acc, rule -> rule.replace(acc, "") }.isEmpty()
            ) return@launch

            // 落库同样在 IO 线程（findByContent / insert / getCount / deleteOldest 都是同步磁盘 I/O）
            val dao = DataBaseKT.instance.clipboardDao()
            val exist = dao.findByContent(data)
            if (exist != null) {
                // 内容去重：已存在就不重复插入，只刷新时间；置顶项保持置顶
                if (exist.isKeep != 0) return@launch
                dao.deleteByContent(data)
            }
            dao.insert(Clipboard(content = data))
            val num = max(dao.getCount() - prefs.clipboardHistoryLimit.getValue(), 0)
            dao.deleteOldest(num)

            if (prefs.clipboardSuggestion.getValue()) {
                // 先写内容再写时间：时间的变化用来触发建议行，必须保证此时内容已是新的。
                // 必须回到主线程写偏好 —— 监听者（ImeService/InputView）会碰 UI。
                withContext(Dispatchers.Main) {
                    AppPrefs.getInstance().internal.clipboardUpdateContent.setValue(data)
                    AppPrefs.getInstance().internal.clipboardUpdateTime.setValue(System.currentTimeMillis())
                }
            }
        }
    }

    /** 把「每行一个正则」的文本编译成规则表；某行写错只忽略该行，不影响其它行 */
    private fun compileRules(raw: String): List<Regex> =
        raw.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
}
