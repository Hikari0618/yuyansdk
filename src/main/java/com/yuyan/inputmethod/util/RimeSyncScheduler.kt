package com.yuyan.inputmethod.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Rime 定时同步（借鉴同文输入法 WorkManager 方案）
 *
 * 优点：进程被杀仍有效、电量低时自动暂停、不重复注册
 */
class RimeSyncWork(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // 检查存储权限
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    Log.w(TAG, "No MANAGE_EXTERNAL_STORAGE permission, skip sync")
                    return Result.failure()
                }
            } else {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        applicationContext, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "No WRITE_EXTERNAL_STORAGE permission, skip sync")
                    return Result.failure()
                }
            }
            Log.i(TAG, "Background sync starting ...")
            val result = RimeSyncUtils.sync()
            prefs().edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            Log.i(TAG, "Background sync done: $result")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "RimeSync"
        private const val WORK_NAME = "rime_periodic_sync"
        private const val PREFS_NAME = "rime_sync_prefs"
        private const val KEY_INTERVAL = "sync_interval_minutes"
        private const val KEY_LAST_SYNC = "last_sync_time"

        private fun prefs(): SharedPreferences {
            val ctx = com.yuyan.imemodule.application.Launcher.instance.context
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        /** 获取同步间隔（分钟），0=关闭 */
        fun getInterval(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_INTERVAL, 0)

        /** 设置同步间隔并更新调度 */
        fun setInterval(context: Context, minutes: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_INTERVAL, minutes).apply()
            schedule(context)
        }

        /** 获取上次同步时间 */
        fun getLastSyncTime(context: Context): Long =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SYNC, 0)

        /** 上次同步时间的可读字符串 */
        fun getLastSyncTimeStr(context: Context): String {
            val ts = getLastSyncTime(context)
            return if (ts > 0) SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
            else "从未同步"
        }

        /** 立即执行一次同步并记录时间 */
        fun doSyncNow(): String {
            val result = RimeSyncUtils.sync()
            prefs().edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            return result
        }

        /** 启动/更新 WorkManager 调度（应用启动时调用） */
        fun schedule(context: Context) {
            val appContext = context.applicationContext
            val workManager = WorkManager.getInstance(appContext)
            val interval = getInterval(context)

            if (interval <= 0) {
                workManager.cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "Sync scheduler canceled (interval=0)")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<RimeSyncWork>(
                interval.toLong(), TimeUnit.MINUTES,
                5, TimeUnit.MINUTES,       // flex interval
            ).setConstraints(constraints).build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.i(TAG, "Sync scheduled every ${interval}min")
        }
    }
}
