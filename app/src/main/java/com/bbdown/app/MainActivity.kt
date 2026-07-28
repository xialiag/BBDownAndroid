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
import com.bbdown.app.core.FFmpegVersion
import com.bbdown.app.core.Logger
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var backPressedTime: Long = 0

    companion object {
        private const val REQ_STORAGE = 1001
        private const val REQ_MANAGE_STORAGE = 1002
        private const val BACK_PRESS_INTERVAL = 2000L

        /** 注入 JS：拦截下载，先展示实际可用流让用户选择（原版 BBDown 风格） */
        private const val STREAM_PICKER_JS = """
(function(){
  if(window._streamPickerInjected) return;
  window._streamPickerInjected = true;
  var _origAddTask = window.callBridge;
  if(!_origAddTask) return;

  window.callBridge = function(method){
    var args = Array.prototype.slice.call(arguments);
    if(method !== 'addTask' || args.length < 3) return _origAddTask.apply(this, args);
    var reqId = args[1], taskJson = args[2];
    try { var t = typeof taskJson === 'string' ? JSON.parse(taskJson) : taskJson; } catch(e) { return _origAddTask.apply(this, args); }
    var url = t.url || '';
    if(!url) return _origAddTask.apply(this, args);

    // 异步获取流信息，展示选择器
    var streamReqId = ++window._bseq;
    window._bres[streamReqId] = {
      resolve: function(data) { showStreamPicker(data, t, args, _origAddTask, this); },
      reject: function(err) { toast('获取流信息失败: ' + err, 'err'); }
    };
    try { AndroidBridge.getAvailableStreams(streamReqId, url); } catch(e) { toast('获取流信息失败: ' + e, 'err'); }
    return streamReqId;
  };

  function fmtSize(bytes) {
    if(!bytes || bytes <= 0) return '';
    if(bytes >= 1048576) return '~' + (bytes / 1048576).toFixed(2) + ' MB';
    return '~' + (bytes / 1024).toFixed(1) + ' KB';
  }

  function showStreamPicker(data, task, origArgs, origFn, origCtx) {
    var videos = data.videos || [];
    var audios = data.audios || [];
    if(!videos.length && !audios.length) { toast('无可用流','err'); return; }

    // 移除已有选择器
    var old = document.getElementById('sp-overlay');
    if(old) old.remove();

    // 构建选项列表（原版风格）
    var vLines = videos.map(function(v, i) {
      var parts = ['[' + (v.dfn||'') + ']'];
      if(v.res) parts.push('[' + v.res + ']');
      parts.push('[' + (v.codecs||'') + ']');
      if(v.fps) parts.push('[' + v.fps + ']');
      parts.push('[' + (v.bandwidth||0) + ' kbps]');
      var sz = v.size ? v.size * (v.dur||0) / 8 : 0;
      if(!sz && v.bandwidth && v.dur) sz = v.bandwidth * 1000 * v.dur / 8;
      if(sz > 0) parts.push('[' + fmtSize(sz) + ']');
      return { id: v.id, label: parts.join(' '), idx: i };
    });

    var aLines = audios.map(function(a, i) {
      var parts = ['[' + (a.codecs||'') + ']'];
      parts.push('[' + (a.bandwidth||0) + ' kbps]');
      var sz = a.bandwidth && data.dur ? a.bandwidth * 1000 * data.dur / 8 : 0;
      if(sz > 0) parts.push('[' + fmtSize(sz) + ']');
      return { id: a.id, label: parts.join(' '), idx: i };
    });

    // 创建 overlay
    var ov = document.createElement('div');
    ov.id = 'sp-overlay';
    ov.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.85);z-index:99999;display:flex;align-items:center;justify-content:center;overflow-y:auto;padding:20px 0';

    var card = document.createElement('div');
    card.style.cssText = 'background:var(--bg,#1a1a2e);border:1px solid var(--border,#333);border-radius:12px;padding:20px;max-width:420px;width:90%;max-height:85vh;overflow-y:auto;color:var(--fg,#e0e0e0)';

    var html = '<div style="font-size:16px;font-weight:bold;margin-bottom:12px;text-align:center">选择流 - ' + escHtml(data.title||'') + '</div>';

    if(vLines.length) {
      html += '<div style="font-size:12px;color:var(--fg-dim,#888);margin:8px 0 4px">视频流 (' + vLines.length + '条)</div>';
      html += '<select id="sp-video" style="width:100%;padding:8px;border-radius:6px;border:1px solid var(--border,#333);background:var(--bg2,#252540);color:var(--fg,#e0e0e0);font-size:13px;margin-bottom:12px">';
      vLines.forEach(function(v) { html += '<option value="' + v.id + '">' + (v.idx+1) + '. ' + escHtml(v.label) + '</option>'; });
      html += '</select>';
    }

    if(aLines.length) {
      html += '<div style="font-size:12px;color:var(--fg-dim,#888);margin:8px 0 4px">音频流 (' + aLines.length + '条)</div>';
      html += '<select id="sp-audio" style="width:100%;padding:8px;border-radius:6px;border:1px solid var(--border,#333);background:var(--bg2,#252540);color:var(--fg,#e0e0e0);font-size:13px;margin-bottom:12px">';
      aLines.forEach(function(a) { html += '<option value="' + a.id + '">' + (a.idx+1) + '. ' + escHtml(a.label) + '</option>'; });
      html += '</select>';
    }

    html += '<div style="display:flex;gap:8px;margin-top:12px">';
    html += '<button id="sp-ok" style="flex:1;padding:10px;border-radius:8px;border:none;background:var(--accent,#4fc3f7);color:#000;font-weight:bold;font-size:14px;cursor:pointer">开始下载</button>';
    html += '<button id="sp-cancel" style="flex:1;padding:10px;border-radius:8px;border:1px solid var(--border,#333);background:transparent;color:var(--fg,#e0e0e0);font-size:14px;cursor:pointer">取消</button>';
    html += '</div>';

    card.innerHTML = html;
    ov.appendChild(card);
    document.body.appendChild(ov);

    // 事件绑定
    document.getElementById('sp-ok').onclick = function() {
      var vid = document.getElementById('sp-video');
      var aid = document.getElementById('sp-audio');
      if(vid) { var sel = vid.options[vid.selectedIndex]; task.videoId = vid.value; }
      if(aid) { var sel = aid.options[aid.selectedIndex]; task.preferAudio = aid.value; }
      ov.remove();
      args[2] = JSON.stringify(task);
      origFn.apply(origCtx, args);
    };
    document.getElementById('sp-cancel').onclick = function() { ov.remove(); };
    ov.onclick = function(e) { if(e.target === ov) ov.remove(); };
  }

  function escHtml(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
})();
""";
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 安装全局崩溃捕获器（第一时间安装，确保后续异常都能被记录）
        CrashHandler.install(this)

        // 初始化 Native 崩溃检测器：捕获 Java 无法捕获的 native 信号崩溃（如 FFmpegKit SIGSEGV），
        // 通过崩溃标记文件 + FFmpegKit 日志回调 + Signal 处理器三层机制记录崩溃上下文。
        NativeCrashDetector.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#FB7299")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        // 初始化任务管理器上下文
        TaskManager.init(this)

        // 初始化默认输出目录（应用私有存储，无需权限）
        TaskManager.outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "BBDown").apply { mkdirs() }

        // 初始化任务持久化并恢复上次未完成的任务
        TaskStore.init(this)
        val saved = TaskStore.load()
        if (saved.isNotEmpty()) {
            TaskManager.restoreTasks(saved)
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
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 注入流选择器：拦截下载，先展示实际可用流让用户选择
                view?.evaluateJavascript(STREAM_PICKER_JS, null)
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
        
        // 退出时清理已完成任务（如果用户开启了该选项）
        // 注意：必须在 onStop() 执行，onDestroy() 在应用被系统杀死时不一定调用
        try {
            val prefs = getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE)
            if (prefs.getString("clearOnExit", "false") == "true") {
                val before = TaskManager.all.size
                TaskManager.clearFinished()
                val after = TaskManager.all.size
                if (before != after) {
                    TaskStore.save()
                    Logger.i("MainActivity", "退出清理: 移除 ${before - after} 个已完成任务")
                }
            }
        } catch (e: Exception) {
            Logger.e("MainActivity", "退出清理失败: ${e.message}")
        }
        
        // 如果有运行中的任务，确保前台服务已启动
        if (TaskManager.all.any { it.isRunning }) {
            try { DownloadService.start(this) } catch (_: Exception) {}
        }
        Logger.i("MainActivity", "onStop: 任务已保存，前台服务已更新")
    }

    override fun onDestroy() {
        super.onDestroy()
        // 最终保存一次任务状态
        TaskManager.saveAll()
        Logger.i("MainActivity", "onDestroy: 任务已保存")
    }
}
