package com.yuyan.imemodule.data.emojicon

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.view.inputmethod.EditorInfo
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object YuyanEmojiCompat {
    var mEditorInfo:EditorInfo? = null
    private var metadataVersion: Int = 0
    private var replaceAll: Boolean = false
    var isWeChatInput: Boolean = false
    var isQQChatInput: Boolean = false
    private lateinit var systemFontPaint: Paint
    private lateinit var instanceNoReplace: InstanceHandler
    private lateinit var instanceReplaceAll: InstanceHandler

    /** init() 是否已执行。EmojiconData 是 object，其初始化时机不确定，可能在 Launcher
     *  后台线程调用 init() 之前就首次访问本单例；调用方据此判断能否安全过滤表情。 */
    val isInitialized: Boolean get() = ::instanceNoReplace.isInitialized

    /** init() 之前的兜底空流：此时 instanceNoReplace/instanceReplaceAll 还是 lateinit，
     *  直接读会抛 UninitializedPropertyAccessException 崩溃；返回这个常驻空流让调用方
     *  安全地拿到 null（init() 完成后重新取值即可）。 */
    private val emptyInstanceFlow = MutableStateFlow<EmojiCompat?>(null)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Initialize this helper and its EmojiCompat instances with given [context]. Immediately begins loading the emoji
     * metadata in a background thread. After this method has been called, it is safe to call [getAsFlow].
     */
    fun init(context: Context) {
        // 幂等：本 object 常驻、init() 可能被重复调用；若不拦，每次都会新建两个
        // InstanceHandler 并再起两个 load 协程，造成句柄与加载任务累积（重复加载同一份元数据）。
        if (::instanceNoReplace.isInitialized) return
        systemFontPaint = Paint().apply {
            typeface = Typeface.DEFAULT
        }
        instanceNoReplace = InstanceHandler(context, replaceAll = false)
        instanceReplaceAll = InstanceHandler(context, replaceAll = true)

        scope.launch {
            instanceNoReplace.load()
        }
        scope.launch {
            instanceReplaceAll.load()
        }
    }

    fun setEditorInfo(editorInfo: EditorInfo?) {
        this.mEditorInfo = editorInfo
        metadataVersion = editorInfo?.extras?.getInt(EmojiCompat.EDITOR_INFO_METAVERSION_KEY, 0) ?: 0
        replaceAll = editorInfo?.extras?.getBoolean(EmojiCompat.EDITOR_INFO_REPLACE_ALL_KEY, false) ?: false
        isWeChatInput = isWechatInput(editorInfo)
        isQQChatInput = isQQChatInput(editorInfo)

    }

    private fun isWechatInput(editorInfo: EditorInfo?): Boolean {
        if(editorInfo == null || editorInfo.packageName != "com.tencent.mm") return  false
        return editorInfo.extras?.getBoolean("IS_CHAT_EDITOR") == true
    }

    private fun isQQChatInput(editorInfo: EditorInfo?): Boolean {
        if(editorInfo == null || editorInfo.packageName != "com.tencent.mobileqq") return  false
        val bundle = editorInfo.extras ?: return false
        return bundle.getInt("SOGOU_EXPRESSION_WEBP") == 1 || bundle.getInt("SOGOU_EXPRESSION") == 1 || bundle.getInt("SUPPORT_SOGOU_EXPRESSION") == 1
    }

    fun getEmojiMatch(emojiCompat:EmojiCompat?, emoji:String):Boolean {
        // systemFontPaint 只在 init() 里赋值：init() 之前调用本方法（见 getAsFlow 注释）
        // 读 lateinit 会崩；这里用 isInitialized 兜底，未初始化时退化为「不匹配」。
        return emojiCompat?.getEmojiMatch(emoji, metadataVersion) == EmojiCompat.EMOJI_SUPPORTED ||
            (::systemFontPaint.isInitialized && systemFontPaint.hasGlyph(emoji))
    }

    /**
     * Gets the current EmojiCompat instance based on [replaceAll] and sets it as the default instance if
     * [setAsDefaultInstance] is true. Calling this method before [init] will cause an exception to be thrown.
     *
     * @return A state flow providing the latest EmojiCompat instance for given args. The flow may provide null if
     *  EmojiCompat is still loading or if it has failed.
     */
    @SuppressLint("RestrictedApi")
    fun getAsFlow(setAsDefaultInstance: Boolean = true): StateFlow<EmojiCompat?> {
        // init() 之前 instanceNoReplace/instanceReplaceAll 仍是 lateinit，直接访问会抛
        // UninitializedPropertyAccessException（EmojiconData 首次访问早于 init 时即崩）。
        // 返回空流，让调用方拿到 null 而不是崩溃。
        if (!::instanceNoReplace.isInitialized) return emptyInstanceFlow
        val instanceFlow = if (replaceAll) {
            instanceReplaceAll.publishedInstanceFlow
        } else {
            instanceNoReplace.publishedInstanceFlow
        }
        val instance = instanceFlow.value
        if (setAsDefaultInstance && instance != null) {
            // This API is not really supposed to be used by third-party apps, but it is really handy and does
            // exactly what we need, so we suppress the restriction here
            EmojiCompat.reset(instance)
        }
        return instanceFlow
    }

    private class InstanceHandler(context: Context, replaceAll: Boolean = false) {
        private val initCallback: EmojiCompat.InitCallback = object : EmojiCompat.InitCallback() {
            override fun onInitialized() {
                super.onInitialized()
                publishedInstanceFlow.value = instance
            }
        }

        private val config: EmojiCompat.Config? = DefaultEmojiCompatConfig.create(context)?.apply {
            setReplaceAll(replaceAll)
            setMetadataLoadStrategy(EmojiCompat.LOAD_STRATEGY_MANUAL)
            registerInitCallback(initCallback)
        }

        // Despite its name, `EmojiCompat.reset()` actually creates a new instance, exactly what we need
        private val instance: EmojiCompat? = if (config != null) EmojiCompat.reset(config) else null
        val publishedInstanceFlow = MutableStateFlow<EmojiCompat?>(null)

        /**
         * Manually loads the EmojiCompat instance. Call this method on a background thread to avoid blocking main.
         *
         * @see EmojiCompat.load
         */
        fun load() {
            instance?.load()
        }
    }
}
