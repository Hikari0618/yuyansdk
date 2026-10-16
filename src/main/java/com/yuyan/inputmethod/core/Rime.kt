package com.yuyan.inputmethod.core

import android.content.Context
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import kotlin.system.measureTimeMillis

class Rime(fullCheck: Boolean) {

    init {
        startup(Launcher.instance.context, fullCheck)
    }

    companion object {
        private var instance: Rime? = null
        private var mContext: RimeContext? = null
        private var mStatus: RimeStatus? = null

        @JvmStatic
        fun getInstance(fullCheck: Boolean = false): Rime {
            if (instance == null) instance = Rime(fullCheck)
            return instance!!
        }

        init {
            System.loadLibrary("yuyanime")
            // 原生层日志开关：release 版（BuildConfig.DEBUG=false）不再写
            // /sdcard/yuyan/ime.log，debug 版保持完整埋点。
            try {
                setDebugLog(com.yuyan.imemodule.BuildConfig.DEBUG)
            } catch (_: Throwable) {
            }
        }

        fun startup(context: Context, fullCheck: Boolean) {
            startupRime(context, CustomConstant.RIME_DICT_PATH, CustomConstant.RIME_DICT_PATH, fullCheck)
            updateStatus()
        }

        @JvmStatic
        fun destroy() {
            exitRime()
            instance = null
        }

        fun updateStatus() {
            measureTimeMillis {
                mStatus = getRimeStatus() ?: RimeStatus()
            }
        }

        fun updateContext() {
            measureTimeMillis {
                mContext = getRimeContext() ?: RimeContext()
            }
            updateStatus()
        }

        @JvmStatic
        val isComposing get() = mStatus?.isComposing == true

        @JvmStatic
        fun hasMenu(): Boolean {
            return isComposing && mContext?.menu?.numCandidates != 0
        }

        @JvmStatic
        fun hasRight(): Boolean {
            return hasMenu() && mContext?.menu?.isLastPage == false
        }

        @JvmStatic
        val composition: RimeComposition?
            get() = mContext?.composition

        @JvmStatic
        val compositionText: String
            get() = composition?.preedit ?: ""

        @JvmStatic
        fun processKey(keycode: Int, mask: Int): Boolean {
            if (keycode <= 0 || keycode == 0xffffff) return false
            setRimePageSize(100)
            return processRimeKey(keycode, mask).also {
                updateContext()
            }
        }

        @JvmStatic
        fun replaceKey(caretPos: Int, length: Int, key: String): Boolean {
            return replaceRimeKey(caretPos, length, key).also {
                updateContext()
            }
        }

        @JvmStatic
        fun clearComposition() { clearRimeComposition()
            updateContext()
        }

        @JvmStatic
        fun selectCandidate(index: Int): Boolean {
            return selectRimeCandidate(index).also {
                updateContext()
            }
        }

        /** 「忘记该词」：从用户词库删掉该候选（同 selectCandidate，删完刷新上下文） */
        @JvmStatic
        fun deleteCandidate(index: Int): Boolean {
            return deleteRimeCandidate(index).also {
                updateContext()
            }
        }

        @JvmStatic
        fun setOption(option: String, value: Boolean) {
            setRimeOption(option, value)
        }

        /** 开关组切换：options 是逗号分隔的成员名，index 为要打开的那一项 */
        @JvmStatic
        fun setOptionGroup(options: String, index: Int) {
            setRimeOptionGroup(options, index)
            updateContext()
        }

        /** 移动组合区光标（点击待编辑区字母用），返回是否成功 */
        @JvmStatic
        fun setCaretPos(caretPos: Int): Boolean {
            return setRimeCaretPos(caretPos).also {
                if (it) updateContext()
            }
        }

        @JvmStatic
        fun selectSchema(schemaId: String): Boolean {
            return selectRimeSchema(schemaId).also {
                updateContext()
            }
        }

        fun getAssociateList(key: String?): Array<String?> {
            return getRimeAssociateList(key)
        }

        fun chooseAssociate(index: Int): Boolean {
            return selectRimeAssociate(index)
        }

        @JvmStatic
        external fun startupRime(context: Context, sharedDir: String, userDir: String, fullCheck: Boolean, )

        @JvmStatic
        external fun exitRime()

        /** 原生层调试日志开关（release 版传 false，不写 /sdcard/yuyan/ime.log） */
        @JvmStatic
        external fun setDebugLog(enabled: Boolean)

        @JvmStatic
        external fun setRimePageSize(pageSize:Int)

        @JvmStatic
        external fun processRimeKey(keycode: Int, mask: Int): Boolean

        @JvmStatic
        external fun replaceRimeKey(caretPos: Int, length: Int, key: String?): Boolean

        @JvmStatic
        external fun clearRimeComposition()

        @JvmStatic
        external fun getRimeCommit(): RimeCommit?

        @JvmStatic
        external fun getRimeContext(): RimeContext?

        @JvmStatic
        external fun getRimeStatus(): RimeStatus?

        @JvmStatic
        external fun setRimeOption(option: String, value: Boolean, )
        @JvmStatic
        external fun setRimeOptionGroup(options: String, index: Int, )
        @JvmStatic
        external fun setRimeCaretPos(caretPos: Int): Boolean

        /** 当前方案的 switcher 选项：每行 name\t状态0\t状态1\t当前值(0/1) */
        @JvmStatic
        external fun getRimeSwitches(): String

        @JvmStatic
        external fun getRimeOption(option: String): Boolean

        @JvmStatic
        external fun getCurrentRimeSchema(): String

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        @JvmStatic
        external fun selectRimeCandidate(index: Int): Boolean
        external fun deleteRimeCandidate(index: Int): Boolean

        @JvmStatic
        external fun getRimeKeycodeByName(name: String): Int

        @JvmStatic
        external fun getRimeAssociateList(key: String?): Array<String?>

        @JvmStatic
        external fun selectRimeAssociate(index: Int): Boolean

        // ---- 方案列表：librime levers / switcher API（同文输入法同款机制）----
        // 每行一条 "schema_id\t方案名"；空串表示引擎未就绪或取不到。
        @JvmStatic
        external fun getRimeAvailableSchemas(): String

        @JvmStatic
        external fun getRimeEnabledSchemas(): String

        /** ids 以 \n 分隔（空串 = 清空）。成功后需重新部署才生效。 */
        @JvmStatic
        external fun setRimeEnabledSchemas(ids: String): Boolean

        /** 引擎扫到的【全部可用】方案（含用户自行导入的万象/雾凇等）。引擎未就绪时返回空。 */
        @JvmStatic
        fun availableSchemas(): List<Pair<String, String>> =
            parseSchemaList(getRimeAvailableSchemas())

        /** 当前【生效】的启用清单（default.custom.yaml 的 schema_list 经部署合并后的结果）。 */
        @JvmStatic
        fun enabledSchemaIds(): List<String> =
            parseSchemaList(getRimeEnabledSchemas()).map { it.first }

        /** 覆盖写启用清单 —— 交给 librime 自己写 default.custom.yaml。成功后需重新部署。 */
        @JvmStatic
        fun setEnabledSchemas(ids: List<String>): Boolean =
            setRimeEnabledSchemas(ids.joinToString("\n"))

        /** "schema_id\t方案名" 每行一条 → List<Pair>（名称可能为空：已启用清单不带名字）。 */
        private fun parseSchemaList(raw: String): List<Pair<String, String>> {
            if (raw.isEmpty()) return emptyList()
            return raw.split('\n').filter { it.isNotBlank() }.map { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) line.trim() to ""
                else line.substring(0, tab) to line.substring(tab + 1).trim()
            }
        }
    }
}
