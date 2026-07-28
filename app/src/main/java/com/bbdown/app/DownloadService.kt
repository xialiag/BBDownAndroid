package com.bbdown.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.bbdown.app.core.DownloadTask
import com.bbdown.app.core.Logger

/**
 * 前台服务，保障下载任务在后台持续运行，防止系统杀进程导致下载中断。
 * - 有运行中任务时显示常驻通知
 * - 持有 WakeLock 防止 CPU 休眠
 * - 无运行中任务时自动停止
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "bbdown_download"
        private const val CHANNEL_NAME = "下载服务"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Logger.i("DownloadService", "前台服务已启动")
            } catch (e: Exception) {
                Logger.e("DownloadService", "启动前台服务失败", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, DownloadService::class.java))
            } catch (_: Exception) {}
        }

        /** 根据是否有运行中任务，启动或停止服务 */
        fun update(context: Context) {
            val running = TaskManager.all.any { it.isRunning }
            if (running) start(context) else stop(context)
        }
    }

    private var checkThread: Thread? = null
    @Volatile private var running = true
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 立即显示一个基础通知，避免 Android 12+ 因未及时调用 startForeground 而 ANR
        startForeground(NOTIF_ID, buildNotification(null))
        Logger.i("DownloadService", "服务已创建")

        // 获取 WakeLock，防止 CPU 在下载过程中休眠
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BBDown:DownloadWakeLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(60 * 60 * 1000L) // 1小时超时，覆盖大部分下载场景
            Logger.i("DownloadService", "WakeLock 已获取")
        } catch (e: Exception) {
            Logger.w("DownloadService", "获取 WakeLock 失败: ${e.message}")
        }

        // 后台监测线程：每 2 秒刷新通知内容；无运行任务时自动退出
        checkThread = Thread {
            var idleCount = 0
            while (running) {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
                val runningTask = TaskManager.all.firstOrNull { it.isRunning }
                if (runningTask != null) {
                    idleCount = 0
                    // 如果 WakeLock 超时被释放，重新获取
                    try {
                        if (wakeLock?.isHeld == false) {
                            wakeLock?.acquire(10 * 60 * 1000L)
                        }
                    } catch (_: Exception) {}
                    try {
                        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        mgr.notify(NOTIF_ID, buildNotification(runningTask))
                    } catch (_: Exception) {}
                } else {
                    idleCount++
                    // 连续 2 次（4秒）无运行任务，自动停止服务
                    if (idleCount >= 2) {
                        Logger.i("DownloadService", "无运行中任务，停止前台服务")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY  // 被杀后尝试重启
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 应用从最近任务列表移除时，如果有运行中的任务，重启服务
        // 注意：Android 12+（API 31+）在后台启动前台服务会抛出
        // ForegroundServiceStartNotAllowedException，需 try/catch 兜底，避免崩溃
        if (TaskManager.all.any { it.isRunning }) {
            Logger.i("DownloadService", "应用被移除但有运行中任务，重启服务")
            val restartIntent = Intent(applicationContext, DownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(restartIntent)
                } else {
                    applicationContext.startService(restartIntent)
                }
            } catch (e: Exception) {
                Logger.e("DownloadService", "重启前台服务失败", e)
                // Android 12+ 启动前台服务失败时，回退尝试普通 startService 保活
                // （在某些场景下也可能失败，但值得一试，失败同样只记录不崩溃）
                try {
                    applicationContext.startService(restartIntent)
                    Logger.i("DownloadService", "回退使用 startService 重启服务")
                } catch (e2: Exception) {
                    Logger.e("DownloadService", "回退 startService 仍然失败", e2)
                }
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        checkThread?.interrupt()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        Logger.i("DownloadService", "服务已销毁")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "下载任务进行中"
                setShowBadge(false)
            }
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(task: DownloadTask?): Notification {
        val title = if (task != null) "正在下载: ${task.title.take(20)}" else "BBDown 下载服务"
        val content = if (task != null) {
            val pct = Math.round(task.progress * 100)
            "进度 $pct%"
        } else {
            "准备中…"
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
