package com.bbdown.app

import android.content.Context
import android.os.Debug
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.bbdown.app.core.Logger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Native 崩溃检测器：捕获 Java UncaughtExceptionHandler 无法捕获的 native 信号崩溃
 *（如 FFmpegKit 执行时触发的 SIGSEGV/SIGABRT）。
 *
 * 三层机制：
 * 1. 崩溃标记（Crash Marker）— 每次 FFmpeg 调用前写入标记文件（含命令、内存快照、最近日志），
 *    成功后删除。若 native 崩溃导致进程被杀，标记文件残留，下次启动时自动检测并生成
 *    `native_crash_*.txt` 报告。
 * 2. logcat 子进程捕获 — FFmpeg 执行前启动 `logcat *:E` 子进程，输出重定向到文件。
 *    app 崩溃后子进程被 init 接管继续运行，崩溃堆栈（由 debuggerd 写入 logcat）保留在文件中。
 *    下次启动时读取该文件，提取 `Fatal signal` / `backtrace` / `libffmpegkit` 等关键行。
 * 3. crash buffer 转储 — 启动检测到崩溃标记时，执行 `logcat -d -b crash` 转储系统崩溃缓冲区，
 *    其中包含 debuggerd 生成的完整 native 崩溃堆栈（含 PC、so 模块名、函数偏移）。
 *
 * 崩溃日志保存路径：应用私有目录 logs/。
 */
object NativeCrashDetector {

    private const val TAG = "NativeCrashDetector"
    private const val MARKER_NAME = "ffmpeg_executing.marker"
    private const val LOGCAT_TMP = "logcat_capture.tmp"
    private const val LOG_BUFFER_SIZE = 500

    private lateinit var appContext: Context
    private val logBuffer = ArrayDeque<String>()
    private val logLock = Any()
    private var initialized = false
    @Volatile private var logcatProcess: Process? = null

    /** 应用启动时调用：注册日志回调，并检测上次残留的崩溃标记。 */
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true

        // 1. 注册 FFmpegKit 日志回调，捕获所有 FFmpeg 输出到环形缓冲区
        try {
            FFmpegKitConfig.enableLogCallback { log ->
                synchronized(logLock) {
                    logBuffer.addLast("[${log.level}] ${log.message}")
                    while (logBuffer.size > LOG_BUFFER_SIZE) logBuffer.removeFirst()
                }
            }
            // 限制 session 历史大小为 1：默认保留 10 个 session，每个持有 native 内存，
            // 批量下载时连续调用 FFmpeg 会导致 native 内存累积。设为 1 仅保留最新 session。
            FFmpegKitConfig.setSessionHistorySize(1)
            Logger.i(TAG, "FFmpegKit 日志回调已注册, session历史大小=1")
        } catch (e: Throwable) {
            Logger.w(TAG, "注册 FFmpegKit 日志回调失败(非致命): ${e.message}")
        }

        // 2. 检测上次运行残留的崩溃标记文件（含 logcat 捕获 + crash buffer 转储）
        checkCrashMarker()
    }

    // ==================== 崩溃标记机制 ====================

    /** FFmpeg 执行前调用：写入标记文件 + 启动 logcat 子进程。 */
    fun beforeFFmpeg(cmd: List<String>) {
        if (!initialized) return
        try {
            val marker = markerFile()
            marker.parentFile?.mkdirs()
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("===== FFmpeg 执行上下文（崩溃标记） =====")
            pw.println("写入时间: ${Date()}")
            pw.println("线程: ${Thread.currentThread().name}")
            pw.println()
            pw.println("FFmpeg 命令: ffmpeg ${cmd.joinToString(" ")}")
            pw.println()
            pw.println("===== 内存快照 =====")
            pw.println(memorySnapshot())
            pw.println()
            pw.println("===== 最近 FFmpeg 日志（环形缓冲区） =====")
            synchronized(logLock) {
                if (logBuffer.isEmpty()) pw.println("(空)")
                else logBuffer.forEach { pw.println(it) }
            }
            pw.flush()
            marker.writeText(sw.toString(), Charsets.UTF_8)
        } catch (_: Throwable) {}
        // 启动 logcat 子进程，捕获 native 崩溃堆栈
        startLogcatCapture()
    }

    /** FFmpeg 执行成功后调用：删除标记文件 + 停止 logcat 子进程。 */
    fun afterFFmpeg() {
        if (!initialized) return
        stopLogcatCapture()
        try { markerFile().delete() } catch (_: Throwable) {}
    }

    // ==================== logcat 子进程捕获 ====================

    /**
     * 启动 logcat 子进程，输出重定向到 [LOGCAT_TMP]。
     *
     * 原理：Android 的 debuggerd 在 native 崩溃时将完整堆栈写入 logcat（含信号类型、
     * fault addr、backtrace）。`logcat *:E` 捕获 Error+Fatal 级别，包含：
     * - `F libc: Fatal signal 11 (SIGSEGV)...` — 崩溃信号
     * - `E DEBUG: *** *** *** ...` — debuggerd 堆栈头
     * - `E DEBUG: #00 pc 0x... libffmpegkit.so!func` — 堆栈帧
     *
     * app 崩溃（SIGSEGV）时进程被杀，但 logcat 子进程被 init 接管继续运行，
     * 崩溃堆栈保留在文件中供下次启动读取。
     */
    fun startLogcatCapture() {
        try {
            stopLogcatCapture()
            val file = File(logDir(), LOGCAT_TMP)
            // *:E = Error 及以上级别（含 Fatal），捕获 native 崩溃输出
            // -v threadtime = 含时间戳/PID/TID/Tag，便于定位
            val pb = ProcessBuilder("logcat", "-v", "threadtime", "*:E")
            pb.redirectOutput(file)
            pb.redirectErrorStream(true)
            logcatProcess = pb.start()
            Logger.i(TAG, "logcat 崩溃捕获已启动 → $LOGCAT_TMP")
        } catch (e: Throwable) {
            Logger.w(TAG, "启动 logcat 捕获失败(非致命): ${e.message}")
        }
    }

    /** 停止 logcat 子进程并删除临时文件（FFmpeg 成功后调用）。 */
    fun stopLogcatCapture() {
        try {
            logcatProcess?.destroy()
            logcatProcess = null
            File(logDir(), LOGCAT_TMP).takeIf { it.exists() }?.delete()
        } catch (_: Throwable) {}
    }

    // ==================== 崩溃标记检测 ====================

    /** 启动时检测残留标记：若存在，说明上次 FFmpeg 执行发生 native 崩溃。 */
    private fun checkCrashMarker() {
        try {
            val marker = markerFile()
            if (!marker.exists()) return
            val content = marker.readText()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val report = File(marker.parentFile, "native_crash_$ts.txt")
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("╔══════════════════════════════════════════════════╗")
            pw.println("║          BBDown Native 崩溃报告                   ║")
            pw.println("╚══════════════════════════════════════════════════╝")
            pw.println()
            pw.println("检测方式: 崩溃标记文件残留（上次 FFmpeg 执行未正常完成）")
            pw.println("说明: Java 的 UncaughtExceptionHandler 无法捕获 native 信号(SIGSEGV/SIGABRT)，")
            pw.println("      此报告通过检测 FFmpeg 执行前写入的标记文件是否残留来判断是否发生 native 崩溃。")
            pw.println("报告时间: ${Date()}")
            pw.println()
            pw.println(content)
            pw.println()

            // 读取 logcat 子进程捕获文件（app 崩溃后子进程继续写入）
            val logcatFile = File(logDir(), LOGCAT_TMP)
            if (logcatFile.exists()) {
                pw.println("===== logcat 捕获（崩溃时 Error+Fatal 级别） =====")
                val logcatContent = logcatFile.readText()
                val lines = logcatContent.lines().filter { it.isNotBlank() }
                // 查找 native 崩溃相关行
                val crashStart = lines.indexOfFirst {
                    it.contains("*** *** ***") || it.contains("Fatal signal")
                }
                if (crashStart >= 0) {
                    // 从崩溃头开始，取最多 60 行（含 backtrace）
                    val crashLines = lines.drop(crashStart).take(60)
                    crashLines.forEach { pw.println(it) }
                    pw.println()
                    // 如果 logcat 中没有 backtrace 结束标记，也输出崩溃前的最后 20 行
                    if (crashLines.none { it.contains("abort message") }) {
                        pw.println("--- 崩溃前 logcat 末尾 20 行 ---")
                        lines.dropLast(lines.size - crashStart).takeLast(20).forEach { pw.println(it) }
                    }
                } else {
                    // 未找到标准崩溃格式，输出最后 40 行供分析
                    pw.println("(未找到标准 native 崩溃格式，显示最后 40 行)")
                    lines.takeLast(40).forEach { pw.println(it) }
                }
                pw.println()
                logcatFile.delete()
            } else {
                pw.println("===== logcat 捕获 =====")
                pw.println("(logcat 捕获文件不存在，子进程可能未启动或被系统杀死)")
                pw.println()
            }

            // 尝试转储系统 crash buffer（debuggerd 写入的完整崩溃堆栈）
            pw.println("===== Crash Buffer（logcat -d -b crash） =====")
            try {
                val pb = ProcessBuilder("logcat", "-d", "-b", "crash", "-v", "threadtime")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val crashBuf = proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor(3, TimeUnit.SECONDS)
                val extracted = extractCrashSection(crashBuf, "com.bbdown.app")
                if (extracted.isNotBlank()) {
                    pw.println(extracted)
                } else {
                    pw.println("(crash buffer 中未找到 com.bbdown.app 相关崩溃)")
                }
            } catch (e: Throwable) {
                pw.println("(读取 crash buffer 失败: ${e.message})")
            }
            pw.println()

            pw.println("===== 最近应用日志（最后 200 条） =====")
            pw.println(Logger.getRecent(200))
            pw.flush()
            report.writeText(sw.toString(), Charsets.UTF_8)
            marker.delete()
            Logger.e(TAG, "检测到上次 FFmpeg 执行可能发生 native 崩溃，报告已保存: ${report.name}")
        } catch (_: Throwable) {}
    }

    /**
     * 从 crash buffer 输出中提取与 [packageName] 相关的崩溃段。
     *
     * crash buffer 格式（每个崩溃以 `*** *** ***` 开头）：
     * ```
     * *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
     * Build fingerprint: ...
     * ABI: 'arm64'
     * pid: 12340, tid: 12345, name: BBDown-DownloadWorker  >>> com.bbdown.app <<<
     * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
     * backtrace:
     *   #00 pc 0x0000000000012345  libffmpegkit.so!function_name
     *   ...
     * ```
     */
    private fun extractCrashSection(crashBuf: String, packageName: String): String {
        val sections = crashBuf.split("*** *** ***")
        val result = StringBuilder()
        for (section in sections) {
            if (section.contains(packageName)) {
                result.append("*** *** ***").append(section.trimEnd()).append("\n\n")
            }
        }
        return result.toString().trim()
    }

    // ==================== 工具方法 ====================

    /** 获取 native 崩溃日志列表（native_crash_* 和 native_signal_*）。 */
    fun getNativeCrashLogs(context: Context): List<File> {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f ->
            (f.name.startsWith("native_crash_") || f.name.startsWith("native_signal_")) &&
                    f.name.endsWith(".txt")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun logDir(): File =
        File(appContext.getExternalFilesDir(null), "logs").apply { mkdirs() }

    private fun markerFile(): File = File(logDir(), MARKER_NAME)

    private fun memorySnapshot(): String {
        val runtime = Runtime.getRuntime()
        val mb = 1024 * 1024
        return buildString {
            appendLine("  JVM 堆已用: ${runtime.totalMemory() / mb} MB")
            appendLine("  JVM 堆空闲: ${runtime.freeMemory() / mb} MB")
            appendLine("  JVM 堆最大: ${runtime.maxMemory() / mb} MB")
            appendLine("  Native 堆已分配: ${Debug.getNativeHeapAllocatedSize() / mb} MB")
            appendLine("  Native 堆空闲: ${Debug.getNativeHeapFreeSize() / mb} MB")
            appendLine("  Native 堆总大小: ${Debug.getNativeHeapSize() / mb} MB")
        }
    }
}
