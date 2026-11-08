package com.yuyan.imemodule.data.theme

import android.view.KeyEvent
import com.yuyan.imemodule.data.emojicon.EmojiconData.SymbolPreset
import com.yuyan.imemodule.keyboard.KeyPreset
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbStyleMode
import org.json.JSONObject

/**
 * 主键盘上滑符号的自定义值。
 *
 * 每个语言存两套互不影响的桶：[Buckets.symbol] 给「数字行开启」时用（上滑本来是符号），
 * [Buckets.number] 给「数字行关闭」时用（上滑本来是数字）——键盘两套预设本来就不同，
 * 所以两套各存一份，改哪套不影响另一套。
 * 只记录用户改过的按键，没改过的继续用 [KeyPreset] 的默认值；存全局 SharedPreferences，换皮肤、重启都在。
 */
object CustomSlideSymbols {

    data class Buckets(
        val symbol: Map<Int, String> = emptyMap(),
        val number: Map<Int, String> = emptyMap()
    )

    private const val PREF_KEY = "keyboard_custom_slide_symbols"
    private const val LANG_ZH = "zh"
    private const val LANG_EN = "en"
    private const val BUCKET_SYMBOL = "symbol"
    private const val BUCKET_NUMBER = "number"
    private const val EXPORT_PREFIX = "yuyan://"

    private var cache: Map<String, Buckets>? = null

    /** 主键盘三行字母，按键盘上从左到右、从上到下的顺序 */
    private val firstRow = listOf(
        KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
        KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I,
        KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P,
    )

    private val secondRow = listOf(
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F,
        KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K,
        KeyEvent.KEYCODE_L,
    )

    private val thirdRow = listOf(
        KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
        KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M,
    )

    /** 逗号键的键值随键盘风格变（Google 风格用 ,/。键，其余用左侧逗号键） */
    private fun commaCode(): Int = when (ThemeManager.prefs.skbStyleMode.getValue()) {
        SkbStyleMode.Google -> InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI
        else -> InputModeSwitcher.USER_KEYCODE_LEFT_COMMA
    }

    /**
     * 编辑页要展示的按键：主键盘上除退格、空格之外的全部按键。
     * Google 风格的分词键是 SHIFT 切换键，值统一记在「分词」这个键值上，见 [lookup]。
     */
    fun keyOrder(): List<Int> = firstRow + secondRow +
            listOf(KeyEvent.KEYCODE_APOSTROPHE) + thirdRow +
            listOf(
                InputModeSwitcher.USER_KEYCODE_SYMBOL,
                InputModeSwitcher.USER_KEYCODE_NUMBER,
                commaCode(),
                InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD,
                InputModeSwitcher.USER_KEYCODE_LANG,
                KeyEvent.KEYCODE_ENTER,
            )

    /** 编辑页里显示的键名：优先用预设键名，图标键（中英/回车）另给名字 */
    fun displayName(chinese: Boolean, code: Int): String {
        // 两套预设的键名一致，取哪套都行
        preset(chinese, symbolMode = true)[code]?.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { return it }
        return when (code) {
            KeyEvent.KEYCODE_APOSTROPHE -> "分词"
            InputModeSwitcher.USER_KEYCODE_SYMBOL -> "符号"
            InputModeSwitcher.USER_KEYCODE_NUMBER -> "123"
            InputModeSwitcher.USER_KEYCODE_LANG -> "中英"
            KeyEvent.KEYCODE_ENTER -> "回车"
            else -> ""
        }
    }

    /** 键盘实际生效的那套：数字行开着取符号桶，关着取数字桶 */
    fun overrides(chinese: Boolean): Map<Int, String> {
        val buckets = buckets(chinese)
        return if (numberLineOn()) buckets.symbol else buckets.number
    }

    fun buckets(chinese: Boolean): Buckets = load()[if (chinese) LANG_ZH else LANG_EN] ?: Buckets()

    fun numberLineOn(): Boolean = AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()

    /** 按键上实际生效的自定义值；Google 风格的 SHIFT 键也认「分词」那条 */
    fun lookup(chinese: Boolean, code: Int): String? {
        val overrides = overrides(chinese)
        return overrides[code] ?: if (code == KeyEvent.KEYCODE_SHIFT_LEFT) {
            overrides[KeyEvent.KEYCODE_APOSTROPHE]
        } else null
    }

    /** 默认预设：[symbolMode] 为 true 取「上滑符号」那套，false 取「上滑数字」那套 */
    fun preset(chinese: Boolean, symbolMode: Boolean): Map<Int, Array<String>> = when {
        chinese && symbolMode -> KeyPreset.qwertyPYKeyPreset
        chinese -> KeyPreset.qwertyPYKeyNumberPreset
        symbolMode -> KeyPreset.qwertyKeyPreset
        else -> KeyPreset.qwertyKeyNumberPreset
    }

    fun defaultOf(chinese: Boolean, symbolMode: Boolean, code: Int): String =
        preset(chinese, symbolMode)[code]?.getOrNull(1) ?: ""

    /**
     * 输入法把成对符号打出来时，会把右半边一起提交、再把光标挪到中间，
     * 所以编辑框里拿到的是「（）」。这里还原成按键命令「（」，交给 commitPairSymbol
     * 去输出成对符号（「成对符号自动匹配」关掉时只出一半，和符号页行为一致）。
     */
    fun normalize(input: String): String {
        val text = input.trim()
        if (text.length >= 2) {
            val open = text.substring(0, 1)
            if (SymbolPreset[open] == text.substring(1)) return open
        }
        return text
    }

    fun save(chinese: Buckets, english: Buckets) {
        val json = JSONObject().apply {
            put(LANG_ZH, toJson(chinese))
            put(LANG_EN, toJson(english))
        }
        AppPrefs.getInstance().internal.customSlideSymbols.setValue(json.toString())
        cache = mapOf(LANG_ZH to chinese, LANG_EN to english)
    }

    /** 导出：`yuyan://` 开头，后面是全部四套配置的 JSON */
    fun exportText(): String = EXPORT_PREFIX + JSONObject().apply {
        put(LANG_ZH, toJson(buckets(true)))
        put(LANG_EN, toJson(buckets(false)))
    }.toString()

    /** 导入：带不带 `yuyan://` 前缀都行；内容不是配置时返回 false */
    fun importText(text: String): Boolean {
        val raw = text.trim().removePrefix(EXPORT_PREFIX)
        if (raw.isBlank()) return false
        return try {
            val root = JSONObject(raw)
            val zh = root.optJSONObject(LANG_ZH)
            val en = root.optJSONObject(LANG_EN)
            if (zh == null && en == null) {
                false
            } else {
                save(parseLang(zh), parseLang(en))
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 中英文、两套桶全部清空 */
    fun reset() = save(Buckets(), Buckets())

    private fun toJson(buckets: Buckets) = JSONObject().apply {
        put(BUCKET_SYMBOL, toJson(buckets.symbol))
        put(BUCKET_NUMBER, toJson(buckets.number))
    }

    private fun toJson(map: Map<Int, String>) = JSONObject().apply {
        map.forEach { (code, label) -> put(code.toString(), label) }
    }

    private fun load(): Map<String, Buckets> = cache ?: parseAll().also { cache = it }

    private fun parseAll(): Map<String, Buckets> {
        val raw = AppPrefs.getInstance().internal.customSlideSymbols.getValue()
        if (raw.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            listOf(LANG_ZH, LANG_EN).associateWith { parseLang(root.optJSONObject(it)) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun parseLang(obj: JSONObject?): Buckets {
        if (obj == null) return Buckets()
        val symbol = obj.optJSONObject(BUCKET_SYMBOL)
        val number = obj.optJSONObject(BUCKET_NUMBER)
        if (symbol == null && number == null) {
            // 更早的格式是平铺的「按键 → 符号」，当时改的是哪套就归到哪套
            val flat = parseMap(obj)
            return if (numberLineOn()) Buckets(symbol = flat) else Buckets(number = flat)
        }
        return Buckets(parseMap(symbol), parseMap(number))
    }

    private fun parseMap(obj: JSONObject?): Map<Int, String> {
        if (obj == null) return emptyMap()
        return try {
            buildMap {
                obj.keys().forEach { key ->
                    key.toIntOrNull()?.let { put(it, obj.getString(key)) }
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
