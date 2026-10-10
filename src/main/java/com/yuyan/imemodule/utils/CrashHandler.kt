package com.yuyan.imemodule.utils

import android.content.Context
import android.os.Build
import java.io.*
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.*
import java.lang.reflect.Field
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlin.system.exitProcess

class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {

    private var mContext: Context? = null
    private val mInfoMap: HashMap<String, String> = HashMap()
    private val mFormatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
    private var mDefaultHandler: Thread.UncaughtExceptionHandler? = null

    companion object {
        val instance: CrashHandler by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
            CrashHandler()
        }
    }

    fun init(context: Context) {
        mContext = context.applicationContext
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (!handleException(throwable) && mDefaultHandler != null) {
            // 自己没兜住（崩溃信息写盘失败）时，交回系统默认处理器，保持原有行为
            mDefaultHandler!!.uncaughtException(thread, throwable)
        } else {
            // 原实现无论是否记录成功都先 Thread.sleep(2000) 再杀进程，
            // 等于每次崩溃都让界面白卡 2 秒；写盘是同步完成的，无需再等，直接杀进程。
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(1)
        }
    }

    private fun handleException(ex: Throwable?): Boolean {
        if (ex == null) {
            return false
        }
        // 把崩溃栈也追加到 /sdcard/yuyan/ime.log：内部 filesDir/crash_logs 要手动导出，
        // 而 ime.log 是日常就在传的日志，这样排查不用再来回要文件
        try {
            val sw = java.io.StringWriter()
            ex.printStackTrace(java.io.PrintWriter(sw))
            java.io.File("/sdcard/yuyan/ime.log")
                .appendText("\n===== CRASH =====\n" + sw.toString() + "\n")
        } catch (e: Throwable) {
            // 落盘失败不能影响原有崩溃处理流程
        }
        collectDeviceInfo(mContext)
        // 原来这里恒定 return true，导致 uncaughtException 里
        // `!handleException(...) && mDefaultHandler != null` 永远为 false —— 系统默认处理器分支不可达。
        // 崩溃信息落盘失败（saveCrashInfoToFile 返回 null）时返回 false，让兜底分支生效。
        return saveCrashInfoToFile(ex) != null
    }

    private fun collectDeviceInfo(ctx: Context?) {
        ctx?.let { context ->
            try {
                val pm: PackageManager = context.packageManager
                val pi: PackageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
                pi.let {
                    mInfoMap["versionName"] = pi.versionName ?: "null"
                    mInfoMap["versionCode"] = pi.versionCode.toString()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val fields: Array<Field> = Build::class.java.declaredFields
        for (field in fields) {
            try {
                field.isAccessible = true
                mInfoMap[field.name] = field.get(null)?.toString() ?: "null"
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveCrashInfoToFile(ex: Throwable): String? {
        val sb = StringBuilder()
        sb.append("=============Build=================\n")
        for ((key, value) in mInfoMap) {
            sb.append("$key=$value\n")
        }
        sb.append("\n=============Throwable=================\n")
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        ex.printStackTrace(printWriter)
        var cause: Throwable? = ex.cause
        while (cause != null) {
            cause.printStackTrace(printWriter)
            cause = cause.cause
        }
        printWriter.close()
        val result = writer.toString()
        sb.append(result)
        return try {
            val timestamp = System.currentTimeMillis()
            val time = mFormatter.format(Date())
            val fileName = "crash-$time-$timestamp.log"
            val outputDir = File(mContext?.filesDir, "crash_logs")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            val outputFile = File(outputDir, fileName)
            val fos = FileOutputStream(outputFile)
            fos.write(sb.toString().toByteArray())
            fos.close()

            outputFile.absolutePath // 返回保存的文件路径
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}