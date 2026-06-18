package com.assh

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量文件日志：把关键运行日志追加写到 app 外部文件目录的 log/run.log，
 * 不依赖 logcat（部分机型/IDE 抓不到 app 日志）。供 SSH 连接全过程排查用。
 *
 * 文件路径：Android/data/com.hrlssh/files/log/run.log
 */
object FileLog {

    @Volatile
    private var logFile: File? = null

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** App 启动时调用一次，绑定内部文件目录（run-as 可读，不需存储权限） */
    fun init(context: Context) {
        runCatching {
            val dir = File(context.applicationContext.filesDir, "log").apply { mkdirs() }
            logFile = File(dir, "run.log")
        }
    }

    fun log(tag: String, message: String) {
        val f = logFile ?: return
        runCatching {
            f.appendText("${fmt.format(Date())} [$tag] $message\n")
        }
    }

    /** 清空日志（开始一次新排查前调用） */
    fun clear() {
        runCatching { logFile?.writeText("") }
    }
}
