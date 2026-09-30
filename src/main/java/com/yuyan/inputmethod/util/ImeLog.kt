package com.yuyan.inputmethod.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 输入链路调试日志：写 /sdcard/yuyan/ime.log（约 512KB 自动轮转）。
 * 用于真机排查"打不出字"类问题——按键进引擎、候选刷新、上屏全程留痕。
 * 写入失败（无权限等）静默忽略，绝不影响输入。
 */
object ImeLog {

    private val lock = Any()

    fun d(msg: String) {
        synchronized(lock) {
            try {
                val f = File(RimeWorkspace.SD_RIME_DIR, "ime.log")
                if (f.length() > 512 * 1024) f.delete()
                f.parentFile?.mkdirs()
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                f.appendText("$ts $msg\n")
            } catch (_: Exception) {
            }
        }
    }
}
