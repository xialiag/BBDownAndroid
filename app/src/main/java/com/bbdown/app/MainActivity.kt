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
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bbdown.app.core.DownloadTask
import com.bbdown.app.core.FFmpegVersion
import com.bbdown.app.core.Logger
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var backPressedTime: Long = 0
    private var didCleanOnExit = false  // 防止 onStop + onDestroy 重复清理

    companion object {
        private const val REQ_STORAGE = 1001
        private const val REQ_MANAGE_STORAGE = 1002
        private const val REQ_NOTIFICATION = 1003
        private const val BACK_PRESS_INTERVAL = 2000L

        // 流选择器已移至 BBDownBridge.showStreamPickerDialog（原生对话框）
        // 流选择器已移至 BBDownBridge.showStreamPickerDialog（原生对话框）
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

        // 初始化默认输出目录（应用私有存储，无需权限）
        TaskManager.outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "BBDown").apply { mkdirs() }

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

        webView = WebView(this)
        // WebView 背景色根据用户主题动态匹配，避免 HTML 加载前的白色闪烁，
        // 同时保证深色/浅色/跟随系统三种主题下背景与 CSS --bg 一致。
        applyWebTheme(getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE)
            .getString("theme", "dark") ?: "dark")
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // 使用默认缓存模式：app.js/app.css 的 URL 含版本号，版本不变时正常缓存加速启动。
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            domStorageEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (url.contains("index.html") && url.startsWith("file://")) {
                    try {
                        val original = assets.open("index.html").bufferedReader().use { it.readText() }
                        val inject = """<script>
(function(){
  var _origAdd = AndroidBridge.addTask;
  function fmtSz(b){if(!b||b<=0)return'';if(b>=1048576)return'~'+(b/1048576).toFixed(2)+' MB';return'~'+(b/1024).toFixed(1)+' KB';}
  function fetchStreams(url,cb){
    var rid=++window._bseq;
    window._bres[rid]={resolve:function(d){cb(d);},reject:function(e){toast('获取流失败:'+e,'err');}};
    try{AndroidBridge.getAvailableStreams(rid,url);}catch(e){toast('获取流失败:'+e,'err');}
  }
  function showOverlay(data,task){
    var vids=data.videos||[],auds=data.audios||[];
    if(!vids.length&&!auds.length){toast('无可用流','err');return;}
    var old=document.getElementById('sp-ov');if(old)old.remove();
    var ov=document.createElement('div');ov.id='sp-ov';
    ov.style.cssText='position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.85);z-index:99999;display:flex;align-items:center;justify-content:center;padding:20px 0';
    var cd=document.createElement('div');
    cd.style.cssText='background:var(--bg,#1a1a2e);border:1px solid var(--border,#333);border-radius:12px;padding:20px;max-width:420px;width:90%;max-height:85vh;overflow-y:auto;color:var(--fg,#e0e0e0)';
    var h='<div style="font-size:16px;font-weight:bold;margin-bottom:16px;text-align:center">'+(data.title||'选择流')+'</div>';
    if(vids.length){
      h+='<div style="font-size:12px;color:var(--fg-dim,#888);margin-bottom:6px">视频流 ('+vids.length+')</div>';
      h+='<div id="sp-vw"></div>';
    }
    if(auds.length){
      h+='<div style="font-size:12px;color:var(--fg-dim,#888);margin:12px 0 6px">音频流 ('+auds.length+')</div>';
      h+='<div id="sp-aw"></div>';
    }
    h+='<div style="display:flex;gap:8px;margin-top:16px">';
    h+='<button id="sp-ok" class="btn btn-primary" style="flex:1">开始下载</button>';
    h+='<button id="sp-no" class="btn" style="flex:1">取消</button></div>';
    cd.innerHTML=h;ov.appendChild(cd);document.body.appendChild(ov);
    function mkLabel(v){
      var p=[v.dfn||''];if(v.res)p.push(v.res);p.push(v.codecs||'');if(v.fps)p.push(v.fps+'fps');p.push(v.bandwidth+'kbps');
      var sz=v.size?v.size*(data.dur||0)/8:0;if(!sz&&v.bandwidth&&data.dur)sz=v.bandwidth*1000*data.dur/8;if(sz>0)p.push(fmtSz(sz));
      return p.join(' | ');
    }
    var vOpts=vids.map(function(v){return[v.id,mkLabel(v)];});
    var aOpts=auds.map(function(a){var p=[a.codecs||'',a.bandwidth+'kbps'];var sz=a.bandwidth&&data.dur?a.bandwidth*1000*data.dur/8:0;if(sz>0)p.push(fmtSz(sz));return[a.id,p.join(' | ')];});
    var vid='sp_vs',aid='sp_as';
    if(vids.length&&typeof window.csHTML==='function'){document.getElementById('sp-vw').innerHTML=window.csHTML(vid,vOpts,vOpts[0][0]);window._csOpts[vid]=vOpts;window._csVal[vid]=vOpts[0][0];}
    if(auds.length&&typeof window.csHTML==='function'){document.getElementById('sp-aw').innerHTML=window.csHTML(aid,aOpts,aOpts[0][0]);window._csOpts[aid]=aOpts;window._csVal[aid]=aOpts[0][0];}
    document.getElementById('sp-no').onclick=function(){ov.remove();};
    ov.onclick=function(e){if(e.target===ov)ov.remove();};
  }
  AndroidBridge.addTask=function(reqId,tj){
    try{var t=typeof tj==='string'?JSON.parse(tj):tj;}catch(e){return _origAdd.call(AndroidBridge,reqId,tj);}
    var url=t.url||'';if(!url)return _origAdd.call(AndroidBridge,reqId,tj);
    fetchStreams(url,function(d){
      if(d.videos.length<=3){_origAdd.call(AndroidBridge,reqId,tj);return;}
      showOverlay(d,t);
      document.getElementById('sp-ok').onclick=function(){
        var vids2=d.videos||[],auds2=d.audios||[];
        if(vids2.length)t.videoId=window._csVal['sp_vs']||vids2[0].id;
        if(auds2.length)t.preferAudio=window._csVal['sp_as']||auds2[0].id;
        document.getElementById('sp-ov').remove();
        _origAdd.call(AndroidBridge,reqId,JSON.stringify(t));
      };
    });
  };
})();
</script>"""
                        val modified = original.replace(
                            "<script src=\"app.js",
                            "$inject\n<script src=\"app.js"
                        )
                        val input = java.io.ByteArrayInputStream(modified.toByteArray(Charsets.UTF_8))
                        return android.webkit.WebResourceResponse("text/html", "UTF-8", input)
                    } catch (e: Exception) { }
                }
                return null
            }
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(BBDownBridge(this, webView), "AndroidBridge")

        if (savedInstanceState == null) {
            // 直接加载 index.html。app.js/app.css 的 URL 中包含版本号（如 ?v=1.9.5），
            // 版本不变时 WebView 正常缓存（启动快），版本变化时 URL 变化自动加载新代码。
            webView.loadUrl("file:///android_asset/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }

        // 请求存储权限
        requestStoragePermission()

        // 注册内存压力回调，记录低内存事件便于排查闪退
        registerMemoryCallbacks()

        // 启动后静默检查 GitHub 更新（开关默认开；有新版本才弹窗提示）
        webView.postDelayed({
            try {
                if (getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE).getString("check_update", "true") == "true") {
                    webView.evaluateJavascript("try{window.__onCheckUpdate&&window.__onCheckUpdate();}catch(e){}", null)
                }
            } catch (_: Exception) {}
        }, 6000)

        // 记录 FFmpeg 版本信息（编译时选择 + 运行时实际版本）
        FFmpegVersion.logVersionInfo()

        // 拦截安卓回退键：先在应用内回退（关闭弹窗/退出管理模式/返回上一页），
        // 已在根页面时提示"再按一次退出应用"，2秒内再按才真正退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){try{return handleBackButton();}catch(e){return false;}})()"
                ) { result ->
                    val handled = result?.trim()?.equals("true", ignoreCase = true) == true
                    if (!handled) {
                        // 已在根页面：2秒内再按一次退出
                        if (System.currentTimeMillis() - backPressedTime < BACK_PRESS_INTERVAL) {
                            // 退出前直接清理已完成任务，不依赖 onStop/isFinishing 生命周期标志
                            doCleanupOnExit()
                            finish()
                        } else {
                            backPressedTime = System.currentTimeMillis()
                            Toast.makeText(this@MainActivity, "再按一次退出应用", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    /** 根据用户主题动态设置 WebView 背景色（与 assets/app.css 的 --bg 保持一致）。
     *  theme: "dark" / "light" / "system"（system 时跟随系统暗色模式）。
     *  由 onCreate 初始调用，并由 BBDownBridge.updateNativeTheme 在用户切换主题时回调。 */
    fun applyWebTheme(theme: String) {
        val resolved = if (theme == "system") {
            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        } else theme
        // 与 app.css 中 --bg 取值严格一致：深色 #1a1a1e，浅色 #f5f5f7
        val color = if (resolved == "light") "#f5f5f7" else "#1a1a1e"
        webView.setBackgroundColor(Color.parseColor(color))
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

    /** 主动释放内存：清理 WebView 缓存、图片缓存，触发 GC */
    private fun releaseMemory() {
        try {
            // 清理 WebView 缓存
            if (::webView.isInitialized) {
                webView.clearCache(true)
            }
            // 通知 JS 层清除图片缓存（blob URL 会被回收）
            if (::webView.isInitialized) {
                webView.evaluateJavascript("try{clearImgCache();}catch(e){}", null)
            }
            // 建议 JVM 回收内存
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
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
