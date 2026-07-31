package com.bbdown.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Looper
import android.os.Process
import com.bbdown.app.core.Logger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃处理器：捕获未处理的异常，写入崩溃日志文件并记录到 Logger。
 * 崩溃日志保存在应用私有目录 logs/crash_*.txt，便于事后排查闪退原因。
 * 包含设备信息、应用版本、内存状态、运行中任务、完整异常链、最近日志等上下文。
 */
class CrashHandler private constructor(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    companion object {
        @Volatile private var instance: CrashHandler? = null

        fun install(context: Context) {
            if (instance == null) {
                instance = CrashHandler(context.applicationContext)
                Thread.setDefaultUncaughtExceptionHandler(instance)
                Logger.i("CrashHandler", "全局崩溃处理器已安装")
            }
        }

        /** 获取所有崩溃日志文件列表（按时间倒序） */
        fun getCrashLogs(context: Context): List<File> {
            val logDir = File(context.getExternalFilesDir(null), "logs")
            if (!logDir.exists()) return emptyList()
            return logDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        // 1. 记录到内存日志
        Logger.e("CrashHandler", "未捕获异常 [${t.name}]", e)

        // 2. 写入崩溃日志文件
        try {
            writeCrashToFile(t, e)
        } catch (_: Exception) {}

        // 3. 保存当前任务状态
        try { TaskStore.save() } catch (_: Exception) {}

        // 4. 持久化登录授权状态（防止崩溃后掉登录）
        try { persistAuthState() } catch (_: Exception) {}

        // 5. 判断是否为主线程崩溃
        val isMainThread = t === Looper.getMainLooper().thread || t.name == "main"
        if (!isMainThread) {
            // 后台线程崩溃：仅记录日志，不终止应用，避免下载等后台异常导致整个应用被杀
            android.util.Log.e("CrashHandler", "后台线程异常 [${t.name}]，应用继续运行: ${e.message}")
            return
        }

        // 主线程崩溃：交给默认处理器（弹出默认崩溃对话框/退出）
        defaultHandler?.uncaughtException(t, e)
    }

    /** 持久化当前登录授权状态到 SharedPreferences，防止崩溃后丢失 */
    private fun persistAuthState() {
        val prefs = context.getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (com.bbdown.app.core.Http.cookie.isNotEmpty()) {
            editor.putString("cookie", com.bbdown.app.core.Http.cookie)
        }
        if (com.bbdown.app.core.Http.tvToken.isNotEmpty()) {
            editor.putString("tv_access_token", com.bbdown.app.core.Http.tvToken)
        }
        editor.commit()
    }

    private fun writeCrashToFile(t: Thread, e: Throwable) {
        val logDir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val ts = dateFormat.format(Date())
        val crashFile = File(logDir, "crash_$ts.txt")

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("╔══════════════════════════════════════════════════╗")
        pw.println("║          BBDown 崩溃报告                          ║")
        pw.println("╚══════════════════════════════════════════════════╝")
        pw.println()
        pw.println("===== 基本信息 =====")
        pw.println("崩溃时间: ${Date()}")
        pw.println("崩溃线程: ${t.name} (id=${t.id}, state=${t.state})")
        pw.println("进程 ID: ${Process.myPid()}")
        pw.println("线程 ID: ${Process.myTid()}")
        pw.println()

        // 应用版本
        pw.println("===== 应用版本 =====")
        try {
            @Suppress("DEPRECATION")
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = pkgInfo.versionName ?: "unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= 28) pkgInfo.longVersionCode else @Suppress("DEPRECATION") pkgInfo.versionCode
            pw.println("版本名: $versionName")
            pw.println("版本号: $versionCode")
            pw.println("包名: ${context.packageName}")
        } catch (_: Exception) {
            pw.println("(版本信息获取失败)")
        }
        pw.println()

        // 设备信息
        pw.println("===== 设备信息 =====")
        pw.println("厂商: ${Build.MANUFACTURER}")
        pw.println("型号: ${Build.MODEL}")
        pw.println("品牌: ${Build.BRAND}")
        pw.println("产品: ${Build.PRODUCT}")
        pw.println("设备: ${Build.DEVICE}")
        pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        pw.println("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        pw.println()

        // 内存状态
        pw.println("===== 内存状态 =====")
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val runtime = Runtime.getRuntime()
            pw.println("系统可用内存: ${memInfo.availMem / (1024*1024)} MB")
            pw.println("系统总内存: ${memInfo.totalMem / (1024*1024)} MB")
            pw.println("低内存阈值: ${memInfo.threshold / (1024*1024)} MB")
            pw.println("系统低内存: ${memInfo.lowMemory}")
            pw.println("JVM 堆已用: ${runtime.totalMemory() / (1024*1024)} MB")
            pw.println("JVM 堆最大: ${runtime.maxMemory() / (1024*1024)} MB")
            pw.println("JVM 堆空闲: ${runtime.freeMemory() / (1024*1024)} MB")
            pw.println("Native 堆: ${Debug.getNativeHeapAllocatedSize() / (1024*1024)} MB")
        } catch (_: Exception) {
            pw.println("(内存信息获取失败)")
        }
        pw.println()

        // 运行中任务
        pw.println("===== 任务状态 =====")
        try {
            val all = TaskManager.all
            val running = all.filter { it.isRunning }
            pw.println("总任务数: ${all.size}")
            pw.println("运行中任务: ${running.size}")
            for (task in running) {
                pw.println("  → ${task.title}")
                pw.println("    状态=${task.status}, 进度=${(task.progress*100).toInt()}%, ${task.pages.size}P")
                pw.println("    URL=${task.url}")
            }
        } catch (_: Exception) {
            pw.println("(任务信息获取失败)")
        }
        pw.println()

        // 异常信息（完整 cause 链）
        pw.println("===== 异常信息 =====")
        pw.println("主异常: ${e.javaClass.name}: ${e.message}")
        pw.println()
        pw.println("--- 主异常堆栈 ---")
        e.printStackTrace(pw)
        pw.println()

        // 遍历完整 cause 链
        var cause = e.cause
        var depth = 0
        while (cause != null && depth < 15) {
            depth++
            pw.println("--- Caused by ($depth): ${cause.javaClass.name}: ${cause.message} ---")
            cause.printStackTrace(pw)
            pw.println()
            cause = cause.cause
        }
        if (depth > 0) {
            pw.println("(共 $depth 层 cause 链)")
        } else {
            pw.println("(无 cause 链)")
        }
        pw.println()

        // 所有线程状态
        pw.println("===== 线程状态 =====")
        try {
            val threads = Thread.getAllStackTraces()
            pw.println("活跃线程数: ${threads.size}")
            for ((thread, stack) in threads.entries.sortedBy { it.key.name }) {
                pw.println("  [${thread.name}] state=${thread.state} prio=${thread.priority}")
                for (i in stack.indices.take(3)) {
                    pw.println("    at ${stack[i]}")
                }
                if (stack.size > 3) pw.println("    ... (${stack.size - 3} more)")
            }
        } catch (_: Exception) {
            pw.println("(线程状态获取失败)")
        }
        pw.println()

        // 最近日志
        pw.println("===== 最近日志 (最后 200 条) =====")
        pw.println(Logger.getRecent(200))
        pw.flush()

        crashFile.writeText(sw.toString(), Charsets.UTF_8)
        android.util.Log.e("CrashHandler", "崩溃日志已保存: ${crashFile.absolutePath}")
    }
}
