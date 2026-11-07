package com.bbdown.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bbdown.app.ui.AppRoot
import com.bbdown.app.ui.AppViewModel
import com.bbdown.app.ui.theme.AppTheme
import com.bbdown.app.ui.theme.BBDownTheme
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bbdown.app.core.DownloadTask
import com.bbdown.app.core.FFmpegVersion
import com.bbdown.app.core.Logger
import java.io.File

class MainActivity : AppCompatActivity() {
    /** Compose 侧的 ViewModel 引用（供返回键/主题切换回调使用） */
    var appViewModel: AppViewModel? = null
    private var backPressedTime: Long = 0
    private var didCleanOnExit = false  // 防止 onStop + onDestroy 重复清理

    companion object {
        private const val REQ_STORAGE = 1001
        private const val REQ_MANAGE_STORAGE = 1002
        private const val REQ_NOTIFICATION = 1003
        private const val BACK_PRESS_INTERVAL = 2000L
    }

    /** 请求电池优化白名单（仅首次）：防止国产 ROM 在后台杀死下载服务 */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val prefs = getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("batteryExemptAsked", false)) return
        prefs.edit().putBoolean("batteryExemptAsked", true).apply()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // 拉起系统"忽略电池优化"授权对话框
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            }
        } catch (_: Exception) {
            // 部分 ROM 不支持该 Action，静默忽略
        }
    }

    /** 请求通知权限（Android 13+）：前台服务通知需运行时授权 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 安装全局崩溃捕获器（第一时间安装，确保后续异常都能被记录）
        CrashHandler.install(this)

        // 初始化调试日志落盘（logs/debug_yyyy-MM-dd.log，保留 7 天，调试服务器可读历史日志）
        com.bbdown.app.core.Logger.init(java.io.File(getExternalFilesDir(null), "logs"))

        // 初始化 Native 崩溃检测器：捕获 Java 无法捕获的 native 信号崩溃（如 FFmpegKit SIGSEGV），
        // 通过崩溃标记文件 + FFmpegKit 日志回调 + Signal 处理器三层机制记录崩溃上下文。
        NativeCrashDetector.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#FB7299")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        // 初始化任务管理器上下文
        TaskManager.init(this)

        // 调试服务器（设置开关，默认关）：启动时若已开启则拉起 19865 端口 HTTP 服务
        if (getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE).getString("debug_server", "false") == "true") {
            com.bbdown.app.core.DebugServer.start(this)
        }

        // 请求电池优化白名单(仅首次):防止国产 ROM 杀后台导致下载中断
        requestBatteryOptimizationExemption()
        // 请求通知权限(Android 13+):前台服务通知需运行时授权，否则用户看不到下载进度
        requestNotificationPermission()

        // 初始化输出目录：优先用用户设置为公共存储（有权限时），否则退回应用私有目录。
        // 注意：必须在 TaskManager.init 之后、任务恢复之前设置，否则会覆盖用户设置。
        TaskManager.outputDir = resolveOutputDir()

        // 初始化任务持久化并恢复上次未完成的任务
        TaskStore.init(this)
        val saved = TaskStore.load()
        if (saved.isNotEmpty()) {
            // 先清理已完成任务（兜底保护：退出时可能 onDestroy 未被调用导致清理失败）
            val prefs = getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE)
            if (prefs.getString("clearOnExit", "false") == "true") {
                val finishedCount = saved.count {
                    it.status == DownloadTask.STATUS_DONE ||
                    it.status == DownloadTask.STATUS_FAILED ||
                    it.status == DownloadTask.STATUS_CANCELED
                }
                if (finishedCount > 0) {
                    val cleaned = saved.filter {
                        it.status != DownloadTask.STATUS_DONE &&
                        it.status != DownloadTask.STATUS_FAILED &&
                        it.status != DownloadTask.STATUS_CANCELED
                    }
                    TaskStore.saveDirect(cleaned)
                    Logger.i("MainActivity", "启动清理: 移除 $finishedCount 个已完成任务")
                    if (cleaned.isNotEmpty()) {
                        TaskManager.restoreTasks(cleaned)
                    }
                } else {
                    TaskManager.restoreTasks(saved)
                }
            } else {
                TaskManager.restoreTasks(saved)
            }
        }

        // ==================== Jetpack Compose 内容 ====================
        // 原 WebView + 注入脚本 + assets 三件套已整体替换为 Compose。
        // 业务内核（DownloadEngine/BilibiliApi/FFmpegMuxer/TaskManager）完全不变。
        setContent {
            val vm: AppViewModel = viewModel()
            LaunchedEffect(Unit) {
                appViewModel = vm
                vm.attach(applicationContext)
            }
            BBDownTheme(darkTheme = vm.darkTheme) {
                Surface(Modifier.fillMaxSize(), color = AppTheme.colors.bg) {
                    AppRoot(vm, onRequestAllFilesAccess = { openAllFilesAccessSettings() })
                }
            }
        }

        // 请求存储权限
        requestStoragePermission()

        // 注册内存压力回调，记录低内存事件便于排查闪退
        registerMemoryCallbacks()

        // 启动后静默检查 GitHub 更新（开关默认开）
        if (getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE).getString("check_update", "true") == "true") {
            checkUpdateSilently()
        }

        // 记录 FFmpeg 版本信息（编译时选择 + 运行时实际版本）
        FFmpegVersion.logVersionInfo()

        // 拦截安卓回退键：先在应用内回退（关闭弹窗/退出管理模式/返回上一页），
        // 已在根页面时提示"再按一次退出应用"，2秒内再按才真正退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val handled = appViewModel?.handleBack() == true
                if (!handled) {
                    // 已在根页面：2秒内再按一次退出
                    if (System.currentTimeMillis() - backPressedTime < BACK_PRESS_INTERVAL) {
                        doCleanupOnExit()
                        finish()
                    } else {
                        backPressedTime = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, "再按一次退出应用", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    /** 根据用户主题动态设置 WebView 背景色（与 assets/app.css 的 --bg 保持一致）。
     *  theme: "dark" / "light" / "system"（system 时跟随系统暗色模式）。
     *  由 onCreate 初始调用。Compose 版的主题切换由 ViewModel 直接驱动，不再依赖原 WebView 桥回调。 */
    /**
     * 解析输出目录：沿用用户设置里的公共路径（已授予"所有文件访问"时），
     * 否则退回应用私有目录，保证任何时候都可写。
     */
    private fun resolveOutputDir(): File {
        val privateDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "BBDown")
        val configured = getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE)
            .getString("output_dir", null)
        if (configured.isNullOrBlank()) return privateDir.apply { mkdirs() }
        // 直接用"能否实际写入"判定，比 Environment.isExternalStorageManager() 可靠：
        // 该 ROM 上 appops 已 allow 但 permission flag 仍是 granted=false，isExternalStorageManager() 会误判为无权限。
        val dir = File(configured)
        val writable = try {
            if (dir.mkdirs() || dir.isDirectory) {
                val probe = File(dir, ".bbdown_write_test")
                val ok = probe.createNewFile() || probe.exists()
                probe.delete()
                ok
            } else false
        } catch (_: Exception) { false }
        if (writable) {
            Logger.i("MainActivity", "输出目录: $configured（公共存储可写）")
            return dir
        }
        Logger.w("MainActivity", "输出目录不可写，退回私有目录: $configured -> $privateDir")
        return privateDir.apply { mkdirs() }
    }

    /** 跳转系统「所有文件访问」授权页（公共存储写入所需） */
    fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开授权页，请手动到系统设置授权", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 静默检查 GitHub 更新：有新版本时通过 ViewModel 的 toast 提示（不打断使用） */
    private fun checkUpdateSilently() {
        Thread {
            try {
                val body = com.bbdown.app.core.Http.get("https://api.github.com/repos/xialiag/BBDownAndroid/releases/latest")
                val tag = org.json.JSONObject(body).optString("tag_name", "").removePrefix("v")
                val current = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                if (tag.isNotEmpty() && versionCompare(tag, current) > 0) {
                    runOnUiThread { appViewModel?.toast("发现新版本 v$tag（当前 v$current）") }
                }
                Logger.i("Update", "检查更新: latest=$tag current=$current")
            } catch (e: Exception) {
                Logger.w("Update", "检查更新失败: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    /** 语义化版本比较：a>b 返回正数 */
    private fun versionCompare(a: String, b: String): Int {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    fun applyWebTheme(theme: String) {
        val resolved = if (theme == "system") {
            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        } else theme
        // 窗口底色与 Compose 主题 --bg 一致：深色 #1a1a1e，浅色 #f5f5f7
        val color = if (resolved == "light") "#f5f5f7" else "#1a1a1e"
        window.decorView.setBackgroundColor(Color.parseColor(color))
    }

    /** 注册内存压力监听，在系统内存不足时主动释放资源 */
    private fun registerMemoryCallbacks() {
        // 使用 ComponentCallbacks2 监听内存压力
        object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() {
                Logger.w("Memory", "系统内存极低(onLowMemory)，主动释放缓存")
                TaskManager.saveAll()
                releaseMemory()
            }
            override fun onTrimMemory(level: Int) {
                when (level) {
                    TRIM_MEMORY_RUNNING_LOW -> {
                        Logger.d("Memory", "内存压力: RUNNING_LOW (level=$level)")
                    }
                    TRIM_MEMORY_RUNNING_CRITICAL -> {
                        Logger.w("Memory", "内存压力: RUNNING_CRITICAL (level=$level)，主动释放缓存")
                        TaskManager.saveAll()
                        releaseMemory()
                    }
                    TRIM_MEMORY_COMPLETE -> {
                        Logger.w("Memory", "内存压力: COMPLETE (level=$level)，主动释放缓存")
                        TaskManager.saveAll()
                        releaseMemory()
                    }
                }
            }
        }.also {
            applicationContext.registerComponentCallbacks(it)
        }
    }

    /** 主动释放内存：保存任务状态并触发 GC（Compose 侧图片缓存由 Coil 自行管理） */
    private fun releaseMemory() {
        try {
            System.gc()
        } catch (e: Exception) {
            Logger.w("Memory", "释放内存时异常: ${e.message}")
        }
    }

    /** 请求存储权限：Android <= 9 请求 WRITE_EXTERNAL_STORAGE，Android 11+ 提示 MANAGE_EXTERNAL_STORAGE */
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 及以下：请求运行时写入权限
            val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(perm), REQ_STORAGE)
            }
        }
        // Android 10: requestLegacyExternalStorage=true 即可
        // Android 11+: 应用私有存储默认可写，无需额外权限
        // 如需写入公共目录，用户需手动授予 MANAGE_EXTERNAL_STORAGE（非必须，私有目录已够用）
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                android.util.Log.i("MainActivity", "存储权限已授予")
            } else {
                android.util.Log.w("MainActivity", "存储权限被拒绝，将使用应用私有目录")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 应用进入后台时保存任务状态，防止被杀后任务丢失
        TaskManager.saveAll()
        Logger.i("MainActivity", "onPause: 任务已保存")
    }

    override fun onStop() {
        super.onStop()
        // 应用停止时再次保存，确保数据不丢失
        TaskManager.saveAll()
        
        // 如果有运行中的任务，确保前台服务已启动
        if (TaskManager.all.any { it.isRunning }) {
            try { DownloadService.start(this) } catch (_: Exception) {}
        }
        Logger.i("MainActivity", "onStop: 任务已保存，前台服务已更新")
    }

    /** 退出时清理已完成任务（直接调用，不依赖 isFinishing 等生命周期标志） */
    private fun doCleanupOnExit() {
        if (didCleanOnExit) return
        didCleanOnExit = true
        try {
            val prefs = getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE)
            if (prefs.getString("clearOnExit", "false") == "true") {
                val before = TaskManager.all.size
                TaskManager.clearFinished()
                val after = TaskManager.all.size
                if (before != after) {
                    Logger.i("MainActivity", "退出清理: 移除 ${before - after} 个已完成任务")
                }
            }
        } catch (e: Exception) {
            Logger.e("MainActivity", "退出清理失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 最终保存一次任务状态，并兜底清理（覆盖从最近任务划掉退出的场景）
        TaskManager.saveAll()
        doCleanupOnExit()
        com.bbdown.app.core.DebugServer.stop()
        Logger.i("MainActivity", "onDestroy: 任务已保存")
    }
}
