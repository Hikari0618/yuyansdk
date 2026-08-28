package com.yuyan.inputmethod.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rime 定时同步调度器
 * 根据用户设定的间隔（分钟）自动触发同步。
 * 记录上次同步时间，以此计算下次同步时间。
 */
object RimeSyncScheduler {

    private const val TAG = "RimeSync"
    private const val PREFS_NAME = "rime_sync_prefs"
    private const val KEY_INTERVAL = "sync_interval_minutes"  // 0=关闭
    private const val KEY_LAST_SYNC = "last_sync_time"

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 获取同步间隔（分钟），0=关闭 */
    fun getInterval(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL, 0)

    /** 设置同步间隔（分钟），0=关闭 */
    fun setInterval(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL, minutes).apply()
        Log.i(TAG, "Sync interval set to ${minutes}min")
        if (minutes > 0) start(context) else stop()
    }

    /** 获取上次同步时间戳 */
    fun getLastSyncTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC, 0)

    /** 获取上次同步时间的可读字符串 */
    fun getLastSyncTimeStr(context: Context): String {
        val ts = getLastSyncTime(context)
        return if (ts > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
        } else "从未同步"
    }

    /** 启动定时调度 */
    fun start(context: Context) {
        if (running) return
        val interval = getInterval(context)
        if (interval <= 0) return
        running = true
        handler.post(createRunnable(context))
        Log.i(TAG, "Scheduler started, interval=${interval}min")
    }

    /** 停止定时调度 */
    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Scheduler stopped")
    }

    /** 执行一次同步并记录时间 */
    fun doSyncNow(): String {
        val result = RimeSyncUtils.sync()
        // 不管成功失败都记录时间（避免失败时无限重试）
        val context = com.yuyan.imemodule.application.Launcher.instance.context
        prefs(context).edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
        return result
    }

    private fun createRunnable(context: Context): Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val interval = getInterval(context)
            if (interval <= 0) { running = false; return }

            val lastSync = getLastSyncTime(context)
            val now = System.currentTimeMillis()
            val elapsed = now - lastSync
            val intervalMs = interval.toLong() * 60 * 1000

            if (elapsed >= intervalMs) {
                // 到时间了，执行同步
                Thread {
                    doSyncNow()
                    Log.i(TAG, "Auto sync done")
                }.start()
            }

            // 每 60 秒检查一次
            handler.postDelayed(this, 60_000)
        }
    }
}
