package com.liguang.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** 全局崩溃兜底：把未捕获异常写入日志文件，闪退后再也不靠猜 */
object CrashLog {

    fun init(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                val f = File(dir, "crash.log")
                val sw = StringWriter()
                PrintWriter(sw).use { throwable.printStackTrace(it) }
                val entry = "[${System.currentTimeMillis()}] thread=${thread.name}\n$sw\n----\n"
                f.appendText(entry)
            } catch (_: Exception) {
            }
            prev?.uncaughtException(thread, throwable)
        }
    }
}