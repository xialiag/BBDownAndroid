package com.bbdown.app

import android.app.ActivityManager
import android.content.Context
import com.bbdown.app.core.DownloadEngine
import com.bbdown.app.core.DownloadTask
import com.bbdown.app.core.Http
import com.bbdown.app.core.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 任务管理器：单例，维护全部下载任务并在后台线程池执行。
 * - 并行下载（已解除API限速限制），支持多任务同时执行
 * - 每个任务独立异常隔离，单任务失败不影响其他任务
 * - 内存压力检测：批量下载时自动降低线程数，防止 OOM 闪退
 * - 任务持久化：每次状态变更自动保存，应用退出后可恢复
 */
object TaskManager {
    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val downloaders = ConcurrentHashMap<String, com.bbdown.app.core.MultiThreadDownloader>()
    // 全局递增序列号：确保批量下载按添加顺序执行，不因 createTime 精度不足而乱序
    private val seqCounter = AtomicLong(0)
    // 并行下载（已解除API限速限制），支持多任务同时执行
    private val executor = Executors.newFixedThreadPool(3, object : ThreadFactory {
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "BBDown-DownloadWorker")
            t.isDaemon = false
            // 捕获工作线程未处理异常，写入崩溃日志，避免静默退出
            t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                Logger.e("TaskManager", "下载工作线程未捕获异常", e)
                try { TaskStore.save() } catch (_: Exception) {}
            }
            return t
        }
    })
    @Volatile var outputDir: File = File("/sdcard/Download/BBDown")
    @Volatile var threads: Int = 8
    @Volatile var interTaskDelay: Int = 0  // 已禁用：任务间延迟（原防API风控，现已移除限制）

    private var appContext: Context? = null
    private val pendingCount = AtomicInteger(0)

    val all: List<DownloadTask> get() = tasks.values.sortedByDescending { it.seq }

    fun get(id: String): DownloadTask? = tasks[id]

    /** 初始化上下文（应用启动时调用） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun add(task: DownloadTask): DownloadTask {
        // 去重：如果已存在相同 url + 分P cid 的未完成任务，跳过添加，直接返回已有任务。
        // 这解决了"分P视频重复添加导致任务堆积卡在等待"的问题：
        // 应用重启自动续传旧任务后，用户再次添加同一批分P时，重复任务会被跳过。
        val dupKey = task.dupKey()
        val existing = tasks.values.find {
            it.dupKey() == dupKey &&
            it.status != DownloadTask.STATUS_DONE &&
            it.status != DownloadTask.STATUS_CANCELED
        }
        if (existing != null) {
            Logger.i("TaskManager", "任务已存在，跳过重复添加: ${task.title} (已有任务 seq=${existing.seq}, status=${existing.status})")
            return existing
        }
        // 分配全局递增序列号，确保批量下载按添加顺序执行
        if (task.seq == 0L) task.seq = seqCounter.incrementAndGet()
        else if (task.seq > seqCounter.get()) seqCounter.set(task.seq)
        tasks[task.taskId] = task
        pendingCount.incrementAndGet()
        // 启动前台服务，防止后台下载被中断
        try { appContext?.let { DownloadService.start(it) } } catch (_: Exception) {}
        // 保存任务列表
        try { TaskStore.save() } catch (_: Exception) {}
        executor.execute {
            // 单任务异常隔离：任何异常都不会传播到工作线程导致线程池终止
            try {
                // 执行前再次检查任务是否已被取消或暂停（可能在排队期间被删除/暂停）
                if (task.status == DownloadTask.STATUS_CANCELED) {
                    Logger.i("TaskManager", "任务已取消，跳过执行: ${task.title}")
                    return@execute
                }
                if (task.status == DownloadTask.STATUS_PAUSED) {
                    Logger.i("TaskManager", "任务已暂停，跳过执行: ${task.title}")
                    return@execute
                }
                Logger.i("TaskManager", "开始执行任务[seq=${task.seq}](thread=${Thread.currentThread().name}): ${task.title}")
                // 内存压力检测：可用内存不足时降低线程数
                val effectiveThreads = getEffectiveThreads()
                if (effectiveThreads != threads) {
                    Logger.w("TaskManager", "内存压力检测：线程数 ${threads} → ${effectiveThreads}")
                }
                DownloadEngine.execute(task, outputDir, effectiveThreads)
            } catch (e: OutOfMemoryError) {
                Logger.e("TaskManager", "OOM(内存不足)导致任务失败: ${task.title}", e)
                if (task.status != DownloadTask.STATUS_CANCELED) {
                    task.status = DownloadTask.STATUS_FAILED
                    task.errorMsg = "内存不足(OOM)，请减少批量下载数量"
                }
                // 强制 GC 释放内存
                System.gc()
            } catch (e: Throwable) {
                Logger.e("TaskManager", "任务执行异常(已隔离): ${task.title}", e)
                if (task.status != DownloadTask.STATUS_CANCELED) {
                    task.status = DownloadTask.STATUS_FAILED
                    task.errorMsg = e.message ?: e.javaClass.simpleName
                }
            } finally {
                // 确保下载器引用被清理，避免内存泄漏
                downloaders.remove(task.taskId)
                // 通知持久化层更新
                try { TaskStore.save() } catch (_: Exception) {}
                // 任务完成后更新前台服务状态
                val remaining = pendingCount.decrementAndGet()
                if (remaining <= 0) {
                    try { appContext?.let { DownloadService.update(it) } } catch (_: Exception) {}
                }
                // 批量任务间提示 GC（不阻塞线程池，让 GC 自然发生）
                if (pendingCount.get() > 0) {
                    System.gc()
                }
            }
        }
        return task
    }

    /**
     * 根据可用内存动态调整下载线程数，防止 OOM。
     * - 可用内存 > 128MB：使用用户设定的线程数
     * - 可用内存 64~128MB：降至 4 线程
     * - 可用内存 < 64MB：降至 2 线程
     */
    private fun getEffectiveThreads(): Int {
        val ctx = appContext ?: return threads.coerceAtMost(4)
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val availMB = memInfo.availMem / (1024 * 1024)
            when {
                availMB < 64 -> threads.coerceAtMost(2)
                availMB < 128 -> threads.coerceAtMost(4)
                else -> threads
            }
        } catch (_: Exception) { threads.coerceAtMost(4) }
    }

    /** 注册下载器实例，用于取消时中断 */
    fun registerDownloader(taskId: String, downloader: com.bbdown.app.core.MultiThreadDownloader) {
        downloaders[taskId] = downloader
    }

    /** 暂停任务：中断当前下载器或阻止排队中的任务开始，标记状态为暂停（可恢复） */
    fun pause(id: String) {
        tasks[id]?.let { t ->
            if (t.isRunning) {
                // 正在下载/解析/合并中的任务：中断下载器
                t.status = DownloadTask.STATUS_PAUSED
                t.speed = 0
                downloaders[id]?.cancel()
            } else if (t.status == DownloadTask.STATUS_PENDING) {
                // 排队等待中的任务：直接标记暂停，executor 中的任务开始时会检查此状态
                t.status = DownloadTask.STATUS_PAUSED
                t.speed = 0
                Logger.i("TaskManager", "暂停排队中的任务: ${t.title}")
            }
            Unit
        }
        try { TaskStore.save() } catch (_: Exception) {}
    }

    /** 恢复暂停的任务：重新执行下载（支持断点续传） */
    fun resumePausedTask(task: DownloadTask): DownloadTask {
        task.status = DownloadTask.STATUS_PENDING
        task.errorMsg = ""
        task.speed = 0
        tasks[task.taskId] = task
        pendingCount.incrementAndGet()
        try { appContext?.let { DownloadService.start(it) } } catch (_: Exception) {}
        try { TaskStore.save() } catch (_: Exception) {}
        executor.execute {
            try {
                // 恢复前检查任务是否已被取消（可能在排队期间被删除）
                if (task.status == DownloadTask.STATUS_CANCELED) {
                    Logger.i("TaskManager", "恢复任务已取消，跳过执行: ${task.title}")
                    return@execute
                }
                Logger.i("TaskManager", "恢复暂停任务(thread=${Thread.currentThread().name}): ${task.title}")
                val effectiveThreads = getEffectiveThreads()
                DownloadEngine.execute(task, outputDir, effectiveThreads)
            } catch (e: OutOfMemoryError) {
                Logger.e("TaskManager", "OOM导致恢复失败: ${task.title}", e)
                if (task.status != DownloadTask.STATUS_CANCELED && !task.isPaused) {
                    task.status = DownloadTask.STATUS_FAILED
                    task.errorMsg = "内存不足(OOM)，请减少批量下载数量"
                }
                System.gc()
            } catch (e: Throwable) {
                Logger.e("TaskManager", "恢复执行异常(已隔离): ${task.title}", e)
                if (task.status != DownloadTask.STATUS_CANCELED && !task.isPaused) {
                    task.status = DownloadTask.STATUS_FAILED
                    task.errorMsg = e.message ?: e.javaClass.simpleName
                }
            } finally {
                downloaders.remove(task.taskId)
                try { TaskStore.save() } catch (_: Exception) {}
                val remaining = pendingCount.decrementAndGet()
                if (remaining <= 0) {
                    try { appContext?.let { DownloadService.update(it) } } catch (_: Exception) {}
                }
                if (pendingCount.get() > 0) {
                    System.gc()
                    Thread.sleep(200)
                }
            }
        }
        return task
    }

    fun cancel(id: String) {
        tasks[id]?.let { t ->
            t.status = DownloadTask.STATUS_CANCELED
            downloaders[id]?.cancel()
        }
        try { TaskStore.save() } catch (_: Exception) {}
    }

    fun remove(id: String) {
        cancel(id)
        tasks.remove(id)
        try { TaskStore.save() } catch (_: Exception) {}
    }

    fun clearFinished() {
        val toRemove = tasks.values.filter { !it.isRunning && !it.isPaused }.map { it.taskId }
        toRemove.forEach { tasks.remove(it) }
        try { TaskStore.save() } catch (_: Exception) {}
    }

    fun setCookie(cookie: String) {
        Http.cookie = cookie
    }

    /** 从持久化恢复任务列表（应用启动时调用）
     *
     * 运行中的任务自动续传，保持原始下载顺序。
     * 配合 add() 的去重逻辑，避免重复任务堆积。
     */
    fun restoreTasks(saved: List<DownloadTask>) {
        var resumeCount = 0
        for (t in saved) {
            // 恢复序列号，确保全局计数器不冲突
            if (t.seq > seqCounter.get()) seqCounter.set(t.seq)
            // 运行中的任务标记为等待，稍后自动续传
            if (t.isRunning) {
                t.status = DownloadTask.STATUS_PENDING
                t.errorMsg = "应用重启，等待续传"
                resumeCount++
            }
            tasks[t.taskId] = t
        }
        Logger.i("TaskManager", "已恢复 ${saved.size} 个任务，其中 $resumeCount 个待续传")

        // 自动续传被中断的任务：按 seq 排序，保持原始下载顺序
        if (resumeCount > 0) {
            val toResume = saved.filter { it.status == DownloadTask.STATUS_PENDING }
                .sortedBy { it.seq }
            executor.execute {
                for (t in toResume) {
                    Logger.i("TaskManager", "自动续传任务: ${t.title}")
                    resumeTask(t)
                }
            }
        }
    }

    /** 续传任务：重新执行下载引擎（MultiThreadDownloader 会自动检测 .dl 断点文件）。
     *  用于失败/取消后重试，重置进度和错误状态。 */
    fun resumeTask(task: DownloadTask): DownloadTask {
        task.status = DownloadTask.STATUS_PENDING
        task.errorMsg = ""
        task.speed = 0
        // 重试时重置进度显示，但保留 downloadedBytes 供断点续传使用
        task.progress = 0f
        tasks[task.taskId] = task
        pendingCount.incrementAndGet()
        try { appContext?.let { DownloadService.start(it) } } catch (_: Exception) {}
        try { TaskStore.save() } catch (_: Exception) {}
        executor.execute {
            try {
                // 续传前检查任务是否已被取消（可能在排队期间被删除）
                if (task.status == DownloadTask.STATUS_CANCELED) {
                    Logger.i("TaskManager", "续传任务已取消，跳过执行: ${task.title}")
                    return@execute
                }
                Logger.i("TaskManager", "开始执行续传任务[seq=${task.seq}](thread=${Thread.currentThread().name}): ${task.title}")
                val effectiveThreads = getEffectiveThreads()
                DownloadEngine.execute(task, outputDir, effectiveThreads)
            } catch (e: OutOfMemoryError) {
                Logger.e("TaskManager", "OOM(内存不足)导致续传失败: ${task.title}", e)
                if (task.status != DownloadTask.STATUS_CANCELED) {
                    task.status = DownloadTask.STATUS_FAILED
                    task.errorMsg = "内存不足(OOM)，请减少批量下载数量"
                }
                System.gc()
            } catch (e: Throwable) {
                Logger.e("TaskManager", "续传执行异常(已隔离): ${task.title}", e)
                if (task.status != DownloadTask.STATUS_CANCELED) {
                    task.status = DownloadTask.STATUS_FAILED
                    task.errorMsg = e.message ?: e.javaClass.simpleName
                }
            } finally {
                downloaders.remove(task.taskId)
                try { TaskStore.save() } catch (_: Exception) {}
                val remaining = pendingCount.decrementAndGet()
                if (remaining <= 0) {
                    try { appContext?.let { DownloadService.update(it) } } catch (_: Exception) {}
                }
                if (pendingCount.get() > 0) {
                    System.gc()
                    Thread.sleep(200)
                }
            }
        }
        return task
    }

    /** 保存当前所有任务到磁盘（可在 Activity onPause/onDestroy 时调用） */
    fun saveAll() {
        try { TaskStore.save() } catch (e: Exception) {
            Logger.e("TaskManager", "保存任务失败", e)
        }
    }
}
