package com.yuyan.imemodule.application

import android.annotation.SuppressLint
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemeManager.prefs
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.ClipboardHelper
import com.yuyan.imemodule.utils.AssetUtils.copyFileOrDir
import com.yuyan.imemodule.utils.thread.ThreadPoolUtils
import com.yuyan.inputmethod.core.Kernel
import com.yuyan.inputmethod.util.RimeSyncScheduler
import java.io.File

class Launcher {
    lateinit var context: Context
        private set

    fun initData(context: Context) {
        this.context = context
        currentInit()
        onInitDataChildThread()
    }

    private fun currentInit() {
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        ThemeManager.init(context.resources.configuration)
        DataBaseKT.instance.sideSymbolDao().getAllSideSymbolPinyin()  //操作一次查询，提前创建数据库，避免使用时才创建数据库
        ClipboardHelper.init()
    }

    /**
     * 可以在子线程初始化的操作
     */
    private fun onInitDataChildThread() {
        ThreadPoolUtils.executeSingleton {
            // 复制词库文件
            val dataDictVersion = AppPrefs.getInstance().internal.dataDictVersion.getValue()
            if (dataDictVersion < CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM) {
                // 复制前先保护「用户状态文件」：这三个在 assets/rime 里也存在，
                // 而下面是 overwrite=true 的全量复制，会把用户的东西覆盖掉：
                //   installation.yaml → sync_dir / installation_id
                //   user.yaml         → 用户 profile
                //   default.yaml      → schema_list 等自定义
                // （此前只靠「版本号没变就不复制」回避，导致方案文件更新传不下去，
                //   用户必须清空数据才能生效 —— 这是用户实测踩到的坑。）
                val rimeDir = File(CustomConstant.RIME_DICT_PATH)
                val userStateFiles = listOf("installation.yaml", "user.yaml", "default.yaml")
                val backup = userStateFiles.associateWith { name ->
                    File(rimeDir, name).takeIf { it.exists() }?.let {
                        runCatching { it.readText() }.getOrNull()
                    }
                }
                //rime词库
                copyFileOrDir(context, "rime", "", CustomConstant.RIME_DICT_PATH, true)
                copyFileOrDir(context, "hw", "", CustomConstant.HW_DICT_PATH, true)
                // 还原用户状态文件
                backup.forEach { (name, text) ->
                    if (text != null) runCatching { File(rimeDir, name).writeText(text) }
                }
                AppPrefs.getInstance().internal.dataDictVersion.setValue(CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM)
            }
            Kernel.resetIme()  // 解决词库复制慢，导致先调用初始化问题
            RimeSyncScheduler.schedule(context)  // 启动定时同步调度器
            YuyanEmojiCompat.init(context)
            //初始化键盘主题
            val isFollowSystemDayNight = prefs.followSystemDayNightTheme.getValue()
            if (isFollowSystemDayNight) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        val instance = Launcher()
    }
}
