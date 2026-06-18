package com.assh

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常捕获器：把崩溃堆栈写到 app 外部文件目录的 crash/ 下，
 * 方便无 logcat 时（真机）排查闪退 / 白屏崩溃。
 *
 * 文件路径：Android/data/com.hrlssh/files/crash/crash-<时间>.txt
 * 写完仍交给系统默认处理器（正常崩溃退出），不吞异常。
 */
object CrashHandler {

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(appContext.getExternalFilesDir(null), "crash").apply { mkdirs() }
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(dir, "crash-$ts.txt").writeText(
                    buildString {
                        append("time: ").append(Date()).append('\n')
                        append("thread: ").append(thread.name).append('\n')
                        append("message: ").append(throwable.message).append("\n\n")
                        append(throwable.stackTraceToString())
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
