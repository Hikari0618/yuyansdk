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
import com.yuyan.inputmethod.util.RimeWorkspace
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
            // 复制词库文件（需要时/版本号变化才做）
            // 主路径在「重新部署」里（时机可控、结果进部署日志）；这里只是兜底，
            // 避免用户从未部署过时 assets 完全没被复制（那样键盘本身不可用）。
            if (RimeWorkspace.copyAssetsIfNeeded(context)) {
                com.yuyan.inputmethod.util.ImeLog.d("[init] assets/rime 已复制到用户目录（版本号变化）")
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
