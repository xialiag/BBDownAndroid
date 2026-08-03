package com.bbdown.app.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/** 一条带全局自增 seq 的日志(seq 供调试服务器增量拉取) */
data class LogLine(
    val seq: Long,
    val time: String,
    val level: String,
    val tag: String,
    val msg: String,
) {
    override fun toString(): String = "[$time][$level][$tag] $msg"
}

/**
 * 调试日志系统，记录应用运行日志，可在设置页面查看。
 * - 支持日志级别：D(调试) / I(信息) / W(警告) / E(错误)
 * - 内存环形缓冲区，自动裁剪旧日志
 * - 同时输出到 logcat 便于 adb 调试
 * - 自动落盘到 logs/debug_yyyy-MM-dd.log(按天轮转，保留 7 天，供调试服务器读历史日志)
 * - 支持导出到文件（带时间戳和分隔符）
 */
object Logger {
    private val logs = ConcurrentLinkedDeque<LogLine>()
    private val seqGen = java.util.concurrent.atomic.AtomicLong(1)
    private const val MAX_LOGS = 1000
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }
    private val fileTimeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }
    private val dayFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    // ===== 落盘（历史日志）=====
    @Volatile private var logDir: File? = null
    private val fileLock = Any()
    @Volatile private var currentDay = ""
    private var currentFile: File? = null

    /** 初始化日志目录（应用启动时调用；同时清理 7 天前的历史日志文件） */
    fun init(dir: File) {
        try {
            dir.mkdirs()
            synchronized(fileLock) { logDir = dir }
            try {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                dir.listFiles { f -> f.name.startsWith("debug_") && f.name.endsWith(".log") && f.lastModified() < cutoff }
                    ?.forEach { runCatching { it.delete() } }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /** 写一行到当天的历史日志文件（失败不影响主流程） */
    private fun writeFile(level: String, tag: String, msg: String) {
        val dir = logDir ?: return
        try {
            synchronized(fileLock) {
                val day = dayFormat.get()!!.format(Date())
                if (currentDay != day) {
                    currentDay = day
                    currentFile = File(dir, "debug_$day.log")
                }
                val f = currentFile ?: return
                f.appendText("[" + fileTimeFormat.get()!!.format(Date()) + "][$level][$tag] $msg\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {}
    }

    fun d(tag: String, msg: String) {
        add("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        add("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        add("W", tag, msg)
    }

    fun e(tag: String, msg: String) {
        add("E", tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println(msg)
        pw.println("  异常类型: ${throwable.javaClass.name}")
        pw.println("  异常消息: ${throwable.message}")
        pw.println("  堆栈跟踪:")
        throwable.printStackTrace(pw)
        // 遍历完整 cause 链
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 10) {
            depth++
            pw.println("  Caused by (${depth}): ${cause.javaClass.name}: ${cause.message}")
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        add("E", tag, sw.toString().trim())
    }

    private fun add(level: String, tag: String, msg: String) {
        val time = dateFormat.get()!!.format(Date())
        logs.addLast(LogLine(seqGen.getAndIncrement(), time, level, tag, msg))
        writeFile(level, tag, msg)
        // 也输出到 logcat
        android.util.Log.println(when(level){
            "E" -> android.util.Log.ERROR
            "W" -> android.util.Log.WARN
            "I" -> android.util.Log.INFO
            else -> android.util.Log.DEBUG
        }, tag, msg)
        while (logs.size > MAX_LOGS) logs.pollFirst()
    }

    fun getAll(): String {
        return logs.joinToString("\n")
    }

    /** 获取最近 N 条日志 */
    fun getRecent(count: Int): String {
        val all = logs.toList()
        val start = maxOf(0, all.size - count)
        return all.subList(start, all.size).joinToString("\n")
    }

    /** 获取最近 N 条日志(结构化,调试服务器用) */
    fun recentLines(count: Int): List<LogLine> {
        val all = logs.toList()
        val start = maxOf(0, all.size - count)
        return all.subList(start, all.size)
    }

    /** 获取 seq 之后的增量日志(调试服务器长轮询用) */
    fun since(seq: Long): List<LogLine> = logs.toList().filter { it.seq > seq }

    /** 当前最大日志 seq(日志水位,无日志时为 0) */
    fun maxSeq(): Long = logs.peekLast()?.seq ?: 0

    /** 获取日志条数 */
    fun getCount(): Int = logs.size

    /** 导出日志到文件（带文件头和时间戳） */
    fun exportToFile(targetFile: File) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("====== BBDown 调试日志 ======")
        pw.println("导出时间: ${Date()}")
        pw.println("日志条数: ${logs.size}")
        pw.println("==========================================")
        pw.println()
        pw.println(getAll())
        pw.flush()
        targetFile.writeText(sw.toString(), Charsets.UTF_8)
    }

    fun clear() {
        logs.clear()
    }
}
