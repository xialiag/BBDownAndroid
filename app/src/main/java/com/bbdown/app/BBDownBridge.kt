package com.bbdown.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.text.Html
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.bbdown.app.core.BilibiliApi
import com.bbdown.app.core.DownloadTask
import com.bbdown.app.core.Http
import com.bbdown.app.core.Logger
import com.bbdown.app.core.PageInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 暴露给 WebView JS 的原生桥接。所有 B 站下载逻辑在本地执行，无需远程服务。
 * 包含扫码登录、批量下载、调试日志支持。
 */
class BBDownBridge(private val context: Context, private val webView: WebView) {
    private val executor = Executors.newCachedThreadPool()
    private val prefs = context.getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE)
    // getTasks 轮询节流时间戳：进度变化无需每秒写盘/检查服务，状态变更路径已自行保存
    private var lastTaskSaveTime = 0L
    private var lastServiceUpdateTime = 0L

    companion object {
        /** 应用私有存储默认目录（无需权限，Android 11+ 可写） */
        fun defaultOutputDir(context: Context): File {
            return File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "BBDown").apply { mkdirs() }
        }

        /** 检查路径是否为公共存储路径 */
        fun isPublicStoragePath(path: String): Boolean {
            val p = path.trim()
            return (p.startsWith("/storage/emulated/0/") || p.startsWith("/sdcard/")) &&
                   !p.startsWith("/storage/emulated/0/Android/data/") &&
                   !p.startsWith("/sdcard/Android/data/")
        }

        /** 检查是否已授予所有文件访问权限 */
        fun hasAllFilesAccess(): Boolean {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                true // Android 10 及以下使用 requestLegacyExternalStorage 即可
            }
        }

        /** 规范化输出目录：有权限时尊重用户选择的公共存储路径，无权限时重定向到私有存储 */
        fun normalizeOutputDir(context: Context, path: String?): File {
            if (path.isNullOrBlank()) return defaultOutputDir(context)
            if (isPublicStoragePath(path)) {
                if (hasAllFilesAccess()) {
                    // 已授权全部文件访问权限，用户可自由使用公共存储
                    Logger.i("Bridge", "已授权文件访问权限，使用公共存储路径: $path")
                    return File(path).also { it.mkdirs() }
                }
                Logger.w("Bridge", "公共存储路径不可写(未授权)，重定向到应用私有存储: $path")
                return defaultOutputDir(context)
            }
            return File(path).also { it.mkdirs() }
        }
    }

    init {
        Logger.i("Bridge", "BBDownBridge 初始化")
        // 版本迁移：确保更新后设置数据不丢失
        migrateVersion()
        // 恢复所有授权数据
        Http.cookie = prefs.getString("cookie", "") ?: ""
        Http.tvToken = prefs.getString("tv_access_token", "") ?: ""
        Http.apiType = prefs.getString("api_type", "web") ?: "web"
        TaskManager.setCookie(Http.cookie)
        TaskManager.outputDir = normalizeOutputDir(context, prefs.getString("output_dir", null))
        TaskManager.threads = prefs.getString("threads", "8")?.toIntOrNull() ?: 8
        TaskManager.interTaskDelay = 0  // API限速已移除，强制不延迟
        Logger.i("Bridge", "Cookie: ${if(Http.cookie.isNotEmpty()) "已加载" else "空"}, TVToken: ${if(Http.tvToken.isNotEmpty()) "已加载" else "空"}, APIType: ${Http.apiType}, 输出目录: ${TaskManager.outputDir}, 线程: ${TaskManager.threads}, 任务间延迟: ${TaskManager.interTaskDelay}ms")
    }

    /**
     * 版本迁移：检测应用版本变化，确保更新后旧设置数据完整保留。
     * 新增设置项会自动填充默认值，已有设置不受影响。
     */
    private fun migrateVersion() {
        val prefsVersion = prefs.getInt("_app_version_code", 0)
        try {
            @Suppress("DEPRECATION")
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersion = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pkgInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode
            }
            if (prefsVersion == 0) {
                Logger.i("Bridge", "首次初始化或旧版升级，版本: $currentVersion")
            } else if (prefsVersion < currentVersion) {
                Logger.i("Bridge", "应用升级: $prefsVersion -> $currentVersion，保留所有设置数据")
            }
            ensureDefaultSettings()
            prefs.edit().putInt("_app_version_code", currentVersion).apply()
        } catch (e: Exception) {
            Logger.w("Bridge", "版本迁移检查失败: ${e.message}")
        }
    }

    /** 确保所有设置项都有默认值，不覆盖用户已设置的值 */
    private fun ensureDefaultSettings() {
        val defaults = mapOf(
            "threads" to "8",
            "preferCodec" to "avc",
            "preferAudio" to "m4a",
            "downloadMode" to "all",
            "theme" to "dark",
            "skipSubtitle" to "false",
            "skipCover" to "false",
            "skipAi" to "true",
            "skipMux" to "false",
            "downloadDanmaku" to "true",  // all 模式默认下载弹幕
            "videoAscending" to "false",
            "audioAscending" to "false",
            "forceHttp" to "false",
            "debug_server" to "false",
            "check_update" to "true",
            "api_type" to "web",
            "batchQn" to "auto",
            "delayPerPage" to "0",
            "filePattern" to "{pageTitle}",
            "filePatternMultiPage" to "{pageTitle} P{pageNumber}",
            "filePatternCollection" to "{collectionIndex}. {pageTitle}",
            "filePatternCollectionMultiPage" to "{collectionIndex}. {videoTitle} P{pageNumber}"
        )
        val editor = prefs.edit()
        var changed = false
        for ((key, default) in defaults) {
            if (!prefs.contains(key)) {
                editor.putString(key, default)
                changed = true
            }
        }
        if (changed) {
            editor.apply()
            Logger.i("Bridge", "已为新增设置项填充默认值")
        }
    }

    private fun callback(reqId: Int, json: String) {
        webView.post {
            webView.evaluateJavascript("try{window.__onBridge($reqId,$json);}catch(e){}", null)
        }
    }

    private fun ok(reqId: Int, data: Any? = null) {
        val j = JSONObject()
        j.put("ok", true)
        if (data != null) j.put("data", data)
        callback(reqId, j.toString())
    }

    private fun err(reqId: Int, msg: String) {
        Logger.e("Bridge", "reqId=$reqId 错误: $msg")
        val j = JSONObject()
        j.put("ok", false)
        j.put("error", msg)
        callback(reqId, j.toString())
    }

    // ==================== 调试日志 ====================

    /** JS端日志入口，无需回调 */
    @JavascriptInterface
    fun jsLog(msg: String) {
        Logger.d("JS", msg)
    }

    /** 同步获取主题设置（在 HTML <head> 中调用，避免主题闪烁） */
    @JavascriptInterface
    fun getThemeSync(): String {
        return prefs.getString("theme", "dark") ?: "dark"
    }

    /** 主题切换时由 JS 回调，同步更新原生 WebView 背景色，
     *  避免 HTML 重新渲染前的背景色与用户主题不一致（白色闪烁/深色不跟手）。 */
    @JavascriptInterface
    fun updateNativeTheme(theme: String) {
        webView.post {
            (context as? MainActivity)?.applyWebTheme(theme)
        }
    }

    @JavascriptInterface
    fun getDebugLogs(reqId: Int) {
        ok(reqId, JSONObject().put("logs", Logger.getAll()))
    }

    @JavascriptInterface
    fun clearDebugLogs(reqId: Int) {
        Logger.clear()
        ok(reqId)
    }

    /** 获取应用版本信息 → {versionName, versionCode} */
    @JavascriptInterface
    fun getAppVersion(reqId: Int) {
        executor.execute {
            try {
                val j = JSONObject()
                j.put("versionName", appVersionName())
                j.put("versionCode", appVersionCode())
                ok(reqId, j)
            } catch (e: Exception) {
                err(reqId, "获取版本信息失败: ${e.message}")
            }
        }
    }

    private fun appVersionName(): String {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }

    private fun appVersionCode(): Int {
        return try {
            @Suppress("DEPRECATION")
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pkgInfo.longVersionCode.toInt()
            else pkgInfo.versionCode
        } catch (_: Exception) { 0 }
    }

    // ============ 检查 GitHub 更新（固定仓库 xialiag/BBDownAndroid，无需设置） ============

    private val updateRepo = "xialiag/BBDownAndroid"

    /** 解析 "v2.0.0" / "2.0.0-beta1" 为可比较数字段列表 */
    private fun versionParts(v: String): List<Long> {
        return Regex("\\d+").findAll(v).map { it.value.toLong() }.toList()
    }

    private fun versionCompare(a: String, b: String): Int {
        val pa = versionParts(a); val pb = versionParts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0L }; val y = pb.getOrElse(i) { 0L }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /** 检查 GitHub Releases 最新版，结果走 ok/err 回调 → {hasUpdate, current, latest, url, note} */
    @JavascriptInterface
    fun checkUpdate(reqId: Int) {
        executor.execute {
            try {
                val url = "https://api.github.com/repos/$updateRepo/releases/latest"
                val body = Http.get(url)
                val o = JSONObject(body)
                val tag = o.optString("tag_name", "")
                if (tag.isEmpty()) {
                    err(reqId, "仓库不存在或无 Release")
                    return@execute
                }
                val latest = tag.removePrefix("v")
                val current = appVersionName()
                ok(reqId, JSONObject().apply {
                    put("hasUpdate", versionCompare(latest, current) > 0)
                    put("current", current)
                    put("latest", latest)
                    put("url", o.optString("html_url", "https://github.com/$updateRepo/releases"))
                    put("note", o.optString("body", "").take(500))
                })
            } catch (e: Exception) {
                err(reqId, e.message ?: "网络错误")
            }
        }
    }

    /** 用系统浏览器打开链接 */
    @JavascriptInterface
    fun openUrl(reqId: Int, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ok(reqId, null)
        } catch (e: Exception) {
            err(reqId, "无法打开浏览器: ${e.message}")
        }
    }

    /** 保存调试日志到文件 → {path} */
    @JavascriptInterface
    fun saveLogsToFile(reqId: Int) {
        executor.execute {
            try {
                val logDir = File(context.getExternalFilesDir(null), "logs")
                if (!logDir.exists()) logDir.mkdirs()
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val logFile = File(logDir, "bbdown_log_$timestamp.txt")
                Logger.exportToFile(logFile)
                Logger.i("Bridge", "日志已保存到: ${logFile.absolutePath} (共 ${Logger.getCount()} 条)")
                ok(reqId, JSONObject().put("path", logFile.absolutePath))
            } catch (e: Exception) {
                Logger.e("Bridge", "保存日志失败", e)
                err(reqId, "保存日志失败: ${e.message}")
            }
        }
    }

    /** 分享日志文件 */
    @JavascriptInterface
    fun shareLogFile(reqId: Int, path: String) {
        executor.execute {
            try {
                val file = File(path)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "BBDown 调试日志")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "分享日志").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                ok(reqId)
            } catch (e: Exception) { err(reqId, "分享失败: ${e.message}") }
        }
    }

    // ==================== 崩溃日志 ====================

    /** 读取崩溃日志文件列表 → [{filename, time, content, size, path}] */
    @JavascriptInterface
    fun getCrashLogs(reqId: Int) {
        executor.execute {
            try {
                val logDir = File(context.getExternalFilesDir(null), "logs")
                val crashFiles = logDir.listFiles { f ->
                    f.name.startsWith("crash_") || f.name.startsWith("native_crash_") || f.name.startsWith("native_signal_")
                }?.sortedByDescending { it.lastModified() } ?: emptyList()
                val arr = JSONArray()
                for (f in crashFiles) {
                    val j = JSONObject()
                    j.put("filename", f.name)
                    j.put("time", f.lastModified())
                    j.put("size", f.length())
                    j.put("path", f.absolutePath)
                    // 限制单个日志内容长度，避免前端卡顿
                    val content = f.readText(Charsets.UTF_8)
                    j.put("content", if (content.length > 50000) content.substring(0, 50000) + "\n... (截断)" else content)
                    arr.put(j)
                }
                Logger.i("Bridge", "找到 ${crashFiles.size} 个崩溃日志")
                ok(reqId, arr)
            } catch (e: Exception) {
                Logger.e("Bridge", "读取崩溃日志失败", e)
                err(reqId, "读取崩溃日志失败: ${e.message}")
            }
        }
    }

    /** 删除所有崩溃日志 */
    @JavascriptInterface
    fun clearCrashLogs(reqId: Int) {
        executor.execute {
            try {
                val logDir = File(context.getExternalFilesDir(null), "logs")
                val crashFiles = logDir.listFiles { f ->
                    f.name.startsWith("crash_") || f.name.startsWith("native_crash_") || f.name.startsWith("native_signal_")
                } ?: emptyArray()
                var deleted = 0
                for (f in crashFiles) { if (f.delete()) deleted++ }
                Logger.i("Bridge", "已删除 $deleted 个崩溃日志")
                ok(reqId, JSONObject().put("deleted", deleted))
            } catch (e: Exception) {
                err(reqId, "删除崩溃日志失败: ${e.message}")
            }
        }
    }

    /** 删除单个崩溃日志 → {deleted: bool} */
    @JavascriptInterface
    fun deleteCrashLog(reqId: Int, filename: String) {
        executor.execute {
            try {
                val logDir = File(context.getExternalFilesDir(null), "logs")
                // 防止路径穿越：取文件名最后一段，丢弃目录部分
                val safeName = File(filename).name
                val file = File(logDir, safeName)
                if (!file.exists() || !file.canonicalPath.startsWith(logDir.canonicalPath)
                    || !(safeName.startsWith("crash_") || safeName.startsWith("native_crash_") || safeName.startsWith("native_signal_"))) {
                    err(reqId, "日志文件不存在")
                    return@execute
                }
                val deleted = file.delete()
                Logger.i("Bridge", "删除崩溃日志 $filename: $deleted")
                ok(reqId, JSONObject().put("deleted", deleted))
            } catch (e: Exception) {
                err(reqId, "删除崩溃日志失败: ${e.message}")
            }
        }
    }

    /** 分享崩溃日志文件（按文件名） */
    @JavascriptInterface
    fun shareCrashLogFile(reqId: Int, filename: String) {
        executor.execute {
            try {
                val logDir = File(context.getExternalFilesDir(null), "logs")
                // 防止路径穿越：取文件名最后一段，丢弃目录部分
                val safeName = File(filename).name
                val file = File(logDir, safeName)
                if (!file.exists() || !file.canonicalPath.startsWith(logDir.canonicalPath)
                    || !(safeName.startsWith("crash_") || safeName.startsWith("native_crash_") || safeName.startsWith("native_signal_"))) {
                    err(reqId, "日志文件不存在")
                    return@execute
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "BBDown 崩溃日志 - $filename")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "分享崩溃日志").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Logger.i("Bridge", "分享崩溃日志: $filename")
                ok(reqId)
            } catch (e: Exception) {
                Logger.e("Bridge", "分享崩溃日志失败", e)
                err(reqId, "分享失败: ${e.message}")
            }
        }
    }

    /** 获取日志统计信息 → {crashCount, debugCount} */
    @JavascriptInterface
    fun getLogStats(reqId: Int) {
        executor.execute {
            try {
                val logDir = File(context.getExternalFilesDir(null), "logs")
                val crashCount = logDir.listFiles { f ->
                    f.name.startsWith("crash_") || f.name.startsWith("native_crash_") || f.name.startsWith("native_signal_")
                }?.size ?: 0
                val debugCount = Logger.getCount()
                ok(reqId, JSONObject().put("crashCount", crashCount).put("debugCount", debugCount))
            } catch (e: Exception) {
                err(reqId, "获取日志统计失败: ${e.message}")
            }
        }
    }

    // ==================== 图片缓存（封面/头像） ====================

    /** 通过原生 HTTP 下载图片，返回 base64 编码数据（避免 WebView CORS 限制） → {data, type} */
    @JavascriptInterface
    fun fetchImage(reqId: Int, url: String) {
        executor.execute {
            try {
                val bytes = Http.getBytes(url, referer = "https://www.bilibili.com/")
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val type = detectImageType(bytes, url)
                val j = JSONObject()
                j.put("data", base64)
                j.put("type", type)
                ok(reqId, j)
            } catch (e: Exception) {
                Logger.w("Bridge", "fetchImage 失败: $url - ${e.message}")
                err(reqId, "图片下载失败: ${e.message}")
            }
        }
    }

    /** 通过 magic bytes 检测图片类型，回退到 URL 扩展名 */
    private fun detectImageType(bytes: ByteArray, url: String): String {
        if (bytes.size >= 4) {
            // JPEG: FF D8 FF
            if ((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8 && (bytes[2].toInt() and 0xFF) == 0xFF)
                return "image/jpeg"
            // PNG: 89 50 4E 47
            if ((bytes[0].toInt() and 0xFF) == 0x89 && (bytes[1].toInt() and 0xFF) == 0x50 && (bytes[2].toInt() and 0xFF) == 0x4E && (bytes[3].toInt() and 0xFF) == 0x47)
                return "image/png"
            // GIF: 47 49 46 38
            if ((bytes[0].toInt() and 0xFF) == 0x47 && (bytes[1].toInt() and 0xFF) == 0x49 && (bytes[2].toInt() and 0xFF) == 0x46 && (bytes[3].toInt() and 0xFF) == 0x38)
                return "image/gif"
            // WebP: RIFF....WEBP
            if (bytes.size >= 12 && (bytes[0].toInt() and 0xFF) == 0x52 && (bytes[1].toInt() and 0xFF) == 0x49 && (bytes[2].toInt() and 0xFF) == 0x46 && (bytes[3].toInt() and 0xFF) == 0x46 &&
                (bytes[8].toInt() and 0xFF) == 0x57 && (bytes[9].toInt() and 0xFF) == 0x45 && (bytes[10].toInt() and 0xFF) == 0x42 && (bytes[11].toInt() and 0xFF) == 0x50)
                return "image/webp"
        }
        // 回退到 URL 扩展名
        return when {
            url.contains(".png", true) -> "image/png"
            url.contains(".webp", true) -> "image/webp"
            url.contains(".gif", true) -> "image/gif"
            else -> "image/jpeg"
        }
    }

    // ==================== 扫码登录 ====================

    /** 获取扫码登录二维码 → {url, qrcodeKey, image} */
    @JavascriptInterface
    fun getQrCode(reqId: Int) {
        Logger.i("Bridge", "getQrCode reqId=$reqId")
        executor.execute {
            try {
                val qr = BilibiliApi.getQrCode()
                val j = JSONObject()
                j.put("url", qr.url)
                j.put("qrcodeKey", qr.qrcodeKey)
                j.put("image", qr.image)
                ok(reqId, j)
            } catch (e: Exception) {
                Logger.e("Bridge", "getQrCode失败", e)
                err(reqId, "获取二维码失败: ${e.message}")
            }
        }
    }

    /** 轮询扫码状态 → {code, message, cookie?, isLogin?} */
    @JavascriptInterface
    fun pollQrLogin(reqId: Int, qrcodeKey: String) {
        executor.execute {
            try {
                val result = BilibiliApi.pollQrLogin(qrcodeKey)
                val j = JSONObject()
                j.put("code", result.code)
                j.put("message", result.message)
                if (result.code == 0 && result.cookie.isNotEmpty()) {
                    Http.cookie = result.cookie
                    TaskManager.setCookie(result.cookie)
                    val editor = prefs.edit()
                    editor.putString("cookie", result.cookie)
                    editor.commit()
                    j.put("isLogin", true)
                    j.put("loginType", "web")
                    try {
                        val navJson = JSONObject(Http.get("https://api.bilibili.com/x/web-interface/nav"))
                        val navData = navJson.getJSONObject("data")
                        val mid = navData.optString("mid")
                        val uname = navData.optString("uname")
                        val isVip = navData.optInt("vipStatus") == 1
                        val face = navData.optString("face")
                        prefs.edit()
                            .putString("web_mid", mid)
                            .putString("web_uname", uname)
                            .putBoolean("web_isVip", isVip)
                            .putString("web_face", face)
                            .commit()
                        j.put("uname", uname)
                        j.put("mid", mid)
                        j.put("isVip", isVip)
                        j.put("face", face)
                    } catch (_: Exception) {}
                } else {
                    j.put("isLogin", false)
                }
                ok(reqId, j)
            } catch (e: Exception) { err(reqId, "查询登录状态失败: ${e.message}") }
        }
    }

    @JavascriptInterface
    fun logout(reqId: Int) {
        // 退出全部登录
        Http.cookie = ""
        Http.tvToken = ""
        TaskManager.setCookie("")
        val editor = prefs.edit()
        editor.remove("cookie")
        editor.remove("tv_access_token")
        editor.remove("web_uname")
        editor.remove("web_mid")
        editor.remove("web_isVip")
        editor.remove("web_face")
        editor.remove("tv_uname")
        editor.remove("tv_mid")
        editor.remove("tv_isVip")
        editor.remove("tv_face")
        editor.commit()
        Logger.i("Bridge", "已退出全部登录")
        ok(reqId)
    }

    /** 仅退出 WEB 登录 */
    @JavascriptInterface
    fun logoutWeb(reqId: Int) {
        Http.cookie = ""
        TaskManager.setCookie("")
        val editor = prefs.edit()
        editor.remove("cookie")
        editor.remove("web_uname")
        editor.remove("web_mid")
        editor.remove("web_isVip")
        editor.remove("web_face")
        editor.commit()
        Logger.i("Bridge", "已退出WEB登录")
        ok(reqId)
    }

    /** 仅退出 TV 登录 */
    @JavascriptInterface
    fun logoutTv(reqId: Int) {
        Http.tvToken = ""
        val editor = prefs.edit()
        editor.remove("tv_access_token")
        editor.remove("tv_uname")
        editor.remove("tv_mid")
        editor.remove("tv_isVip")
        editor.remove("tv_face")
        editor.commit()
        Logger.i("Bridge", "已退出TV登录")
        ok(reqId)
    }

    // ==================== TV端授权登录 ====================

    /** 获取TV端扫码登录二维码 → {url, qrcodeKey, image} */
    @JavascriptInterface
    fun getTvQrCode(reqId: Int) {
        Logger.i("Bridge", "getTvQrCode reqId=$reqId")
        executor.execute {
            try {
                val qr = BilibiliApi.getTvQrCode()
                val j = JSONObject()
                j.put("url", qr.url)
                j.put("qrcodeKey", qr.qrcodeKey)
                j.put("image", qr.image)
                ok(reqId, j)
            } catch (e: Exception) {
                Logger.e("Bridge", "getTvQrCode失败", e)
                err(reqId, "获取TV二维码失败: ${e.message}")
            }
        }
    }

    /** 轮询TV端扫码登录状态 → {code, message, isLogin?, ...} */
    @JavascriptInterface
    fun pollTvLogin(reqId: Int, authCode: String) {
        executor.execute {
            try {
                val result = BilibiliApi.pollTvLogin(authCode)
                val j = JSONObject()
                j.put("code", result.code)
                j.put("message", result.message)
                if (result.code == 0 && result.cookie.isNotEmpty()) {
                    // TV 登录返回的 cookie 始终保存（覆盖旧的 WEB cookie，因为这是最新授权）
                    val accessToken = result.accessToken
                    if (accessToken.isNotEmpty()) {
                        Http.tvToken = accessToken
                    }
                    Http.cookie = result.cookie
                    TaskManager.setCookie(result.cookie)
                    val editor = prefs.edit()
                    editor.putString("cookie", result.cookie)
                    if (accessToken.isNotEmpty()) {
                        editor.putString("tv_access_token", accessToken)
                    }
                    editor.commit()
                    j.put("isLogin", true)
                    j.put("loginType", "tv")
                    if (accessToken.isNotEmpty()) {
                        j.put("accessToken", accessToken)
                    }
                    try {
                        val navJson = JSONObject(Http.get("https://api.bilibili.com/x/web-interface/nav"))
                        val navData = navJson.getJSONObject("data")
                        val mid = navData.optString("mid")
                        val uname = navData.optString("uname")
                        val isVip = navData.optInt("vipStatus") == 1
                        val face = navData.optString("face")
                        prefs.edit()
                            .putString("tv_mid", mid)
                            .putString("tv_uname", uname)
                            .putBoolean("tv_isVip", isVip)
                            .putString("tv_face", face)
                            .commit()
                        j.put("uname", uname)
                        j.put("mid", mid)
                        j.put("isVip", isVip)
                        j.put("face", face)
                    } catch (_: Exception) {}
                } else {
                    j.put("isLogin", false)
                }
                ok(reqId, j)
            } catch (e: Exception) { err(reqId, "TV登录查询失败: ${e.message}") }
        }
    }

    @JavascriptInterface
    fun checkLogin(reqId: Int) {
        executor.execute {
            try {
                // Phase 1: 立即返回缓存登录数据（不等网络请求），让 UI 先显示用户名
                ok(reqId, buildLoginJson(useCacheOnly = true))

                // Phase 2: 后台获取最新登录状态，完成后推送给 JS 更新 UI
                val j = buildLoginJson(useCacheOnly = false)
                val jsonStr = j.toString()
                webView.post {
                    webView.evaluateJavascript("try{window.__onLoginUpdate($jsonStr);}catch(e){}", null)
                }
            } catch (e: Exception) {
                Logger.e("Bridge", "checkLogin error: ${e.message}")
            }
        }
    }

    /** 构建登录状态 JSON
     * @param useCacheOnly true=仅用缓存（快速返回，不发网络请求），false=发起网络请求获取最新状态
     */
    private fun buildLoginJson(useCacheOnly: Boolean): JSONObject {
        val j = JSONObject()

        // ===== WEB 登录状态 =====
        val webObj = JSONObject()
        val webUname = prefs.getString("web_uname", "") ?: ""
        val webMid = prefs.getString("web_mid", "") ?: ""
        val webFace = prefs.getString("web_face", "") ?: ""
        val webIsVip = prefs.getBoolean("web_isVip", false)
        // 保险措施：内存中 cookie 为空时从磁盘重新加载
        if (Http.cookie.isBlank()) {
            val savedCookie = prefs.getString("cookie", "") ?: ""
            if (savedCookie.isNotBlank()) {
                Http.cookie = savedCookie
                TaskManager.setCookie(savedCookie)
                Logger.i("Bridge", "checkLogin: 内存 cookie 为空，从磁盘恢复成功")
            }
        }
        if (Http.cookie.isNotBlank()) {
            if (useCacheOnly) {
                // 快速模式：直接用缓存数据，不发网络请求
                webObj.put("isLogin", true)
                if (webUname.isNotEmpty()) {
                    webObj.put("uname", webUname)
                    webObj.put("mid", webMid)
                    webObj.put("isVip", webIsVip)
                    webObj.put("face", webFace)
                } else {
                    webObj.put("uname", "").put("mid", "")
                }
            } else try {
                val navJson = JSONObject(Http.get("https://api.bilibili.com/x/web-interface/nav"))
                val code = navJson.optInt("code", -1)
                val navData = navJson.optJSONObject("data")
                // B站 nav 接口返回的 isLogin 是布尔值 true（不是整数 1），需兼容两种格式
                val isLoginFlag = if (navData != null) {
                    navData.opt("isLogin")?.toString()?.equals("true", true) == true ||
                    navData.optInt("isLogin", 0) == 1
                } else false
                if (code == 0 && isLoginFlag && navData != null) {
                    val mid = navData.optString("mid")
                    val uname = navData.optString("uname")
                    val isVip = navData.optInt("vipStatus") == 1
                    val face = navData.optString("face")
                    prefs.edit()
                        .putString("web_mid", mid)
                        .putString("web_uname", uname)
                        .putBoolean("web_isVip", isVip)
                        .putString("web_face", face)
                        .commit()
                    webObj.put("isLogin", true)
                    webObj.put("uname", uname)
                    webObj.put("mid", mid)
                    webObj.put("isVip", isVip)
                    webObj.put("face", face)
                } else {
                    webObj.put("isLogin", false)
                    if (webUname.isNotEmpty()) webObj.put("expired", true).put("uname", webUname)
                }
            } catch (e: Exception) {
                // 网络请求失败但 cookie 仍然存在：标记为离线已登录，避免误判为掉线
                webObj.put("isLogin", true).put("offline", true)
                if (webUname.isNotEmpty()) {
                    webObj.put("uname", webUname).put("mid", webMid)
                    webObj.put("isVip", webIsVip).put("face", webFace)
                } else {
                    webObj.put("uname", "").put("mid", "")
                }
            }
        } else {
            webObj.put("isLogin", false)
        }

        // ===== TV 登录状态 =====
        val tvObj = JSONObject()
        val tvUname = prefs.getString("tv_uname", "") ?: ""
        val tvMid = prefs.getString("tv_mid", "") ?: ""
        val tvFace = prefs.getString("tv_face", "") ?: ""
        val tvIsVip = prefs.getBoolean("tv_isVip", false)
        // 保险措施：内存中 tvToken 为空时从磁盘重新加载
        if (Http.tvToken.isBlank()) {
            val savedToken = prefs.getString("tv_access_token", "") ?: ""
            if (savedToken.isNotBlank()) {
                Http.tvToken = savedToken
                Logger.i("Bridge", "checkLogin: 内存 tvToken 为空，从磁盘恢复成功")
            }
        }
        if (Http.tvToken.isNotBlank()) {
            tvObj.put("isLogin", true)
            tvObj.put("hasToken", true)
            if (tvUname.isNotEmpty()) {
                tvObj.put("uname", tvUname)
                tvObj.put("mid", tvMid)
                tvObj.put("isVip", tvIsVip)
                tvObj.put("face", tvFace)
            }
        } else {
            tvObj.put("isLogin", false)
        }

        j.put("web", webObj)
        j.put("tv", tvObj)
        j.put("isLogin", webObj.optBoolean("isLogin") || tvObj.optBoolean("isLogin"))
        // 顶层 uname/mid：优先 web，web 为空时回退 tv，确保始终有值
        val topUname: String
        val topMid: String
        val topIsVip: Boolean
        val topFace: String
        if (webObj.optBoolean("isLogin") && webObj.optString("uname").isNotEmpty()) {
            topUname = webObj.optString("uname")
            topMid = webObj.optString("mid")
            topIsVip = webObj.optBoolean("isVip")
            topFace = webObj.optString("face")
        } else if (tvObj.optBoolean("isLogin") && tvObj.optString("uname").isNotEmpty()) {
            topUname = tvObj.optString("uname")
            topMid = tvObj.optString("mid")
            topIsVip = tvObj.optBoolean("isVip")
            topFace = tvObj.optString("face")
        } else if (webObj.optBoolean("isLogin")) {
            // web 已登录但 uname 为空（网络失败且本地未缓存），仍返回登录态
            topUname = webUname.ifEmpty { "已登录" }
            topMid = webMid
            topIsVip = webIsVip
            topFace = webFace
        } else if (tvObj.optBoolean("isLogin")) {
            topUname = tvUname.ifEmpty { "已登录" }
            topMid = tvMid
            topIsVip = tvIsVip
            topFace = tvFace
        } else {
            topUname = ""
            topMid = ""
            topIsVip = false
            topFace = ""
        }
        j.put("uname", topUname)
        j.put("mid", topMid)
        j.put("isVip", topIsVip)
        j.put("face", topFace)
        return j
    }

    /** 获取API类型 → {apiType} */
    @JavascriptInterface
    fun getApiType(reqId: Int) {
        executor.execute {
            try {
                val apiType = prefs.getString("api_type", "web") ?: "web"
                Http.apiType = apiType
                ok(reqId, JSONObject().put("apiType", apiType))
            } catch (e: Exception) { err(reqId, "获取API类型失败: ${e.message}") }
        }
    }

    /** 设置API类型 → {ok} */
    @JavascriptInterface
    fun setApiType(reqId: Int, apiType: String) {
        executor.execute {
            try {
                Http.apiType = apiType
                prefs.edit().putString("api_type", apiType).commit()
                Logger.i("Bridge", "API类型设置为: $apiType")
                ok(reqId)
            } catch (e: Exception) { err(reqId, "设置API类型失败: ${e.message}") }
        }
    }

    // ==================== 视频解析 ====================

    @JavascriptInterface
    fun parseUrl(reqId: Int, url: String) {
        executor.execute {
            try {
                val parsed = BilibiliApi.parseUrl(url)
                val j = JSONObject()
                j.put("type", parsed.type)
                j.put("aid", parsed.aid)
                j.put("epId", parsed.epId)
                j.put("bvid", parsed.bvid)
                ok(reqId, j)
            } catch (e: Exception) { err(reqId, "解析链接失败: ${e.message}") }
        }
    }

    /** 批量解析多个链接(空格分割)，返回每个链接的解析结果。
     *  为避免大量链接一次性占用过多内存，分批回传结果（每批 10 个）。
     *  JS 端通过 __onBatchParseProgress 接收增量结果。 */
    @JavascriptInterface
    fun parseBatch(reqId: Int, input: String) {
        Logger.i("Bridge", "parseBatch: $input")
        executor.execute {
            try {
                val urls = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                Logger.i("Bridge", "批量解析 ${urls.size} 个链接")
                val results = JSONArray()
                val chunkSize = 10  // 分批回传，降低内存峰值
                var chunkCount = 0
                for ((idx, url) in urls.withIndex()) {
                    val item = JSONObject()
                    item.put("url", url)
                    try {
                        val parsed = BilibiliApi.parseUrl(url)
                        item.put("type", parsed.type)
                        item.put("aid", parsed.aid)
                        item.put("epId", parsed.epId)
                        item.put("ok", true)

                        // 获取视频信息
                        val info = BilibiliApi.getVideoInfo(parsed)
                        item.put("title", info.title)
                        item.put("pic", info.pic)
                        item.put("desc", info.desc)
                        item.put("upperName", info.upperName)
                        item.put("ownerMid", info.ownerMid)
                        item.put("pubTime", info.pubTime)
                        item.put("isBangumi", info.isBangumi)
                        item.put("isCheese", info.isCheese)
                        item.put("bvid", info.bvid)
                        item.put("play", info.play)
                        item.put("danmaku", info.danmaku)
                        item.put("duration", info.duration)
                        item.put("ownerFace", info.ownerFace)
                        item.put("officialType", info.officialType)
                        item.put("vipType", info.vipType)
                        item.put("vipStatus", info.vipStatus)
                        val pages = JSONArray()
                        for (p in info.pages) {
                            val pj = JSONObject()
                            pj.put("index", p.index); pj.put("aid", p.aid); pj.put("cid", p.cid)
                            pj.put("epid", p.epid); pj.put("title", p.title); pj.put("duration", p.duration)
                            pages.put(pj)
                        }
                        item.put("pages", pages)
                        Logger.i("Bridge", "解析成功: ${info.title}")
                    } catch (e: Exception) {
                        item.put("ok", false)
                        item.put("error", e.message)
                        Logger.w("Bridge", "解析失败: $url - ${e.message}")
                    }
                    results.put(item)
                    chunkCount++
                    // 分批回传进度，让 JS 端尽早渲染已完成项
                    if (chunkCount >= chunkSize || idx == urls.lastIndex) {
                        try {
                            // 深拷贝 results，避免后台线程继续修改导致 ConcurrentModificationException
                            val snapshot = JSONArray(results.toString())
                            val prog = JSONObject()
                            prog.put("done", idx == urls.lastIndex)
                            prog.put("processed", idx + 1)
                            prog.put("total", urls.size)
                            prog.put("items", snapshot)
                            webView.post {
                                webView.evaluateJavascript(
                                    "try{window.__onBatchParseProgress && window.__onBatchParseProgress($reqId,${prog.toString()});}catch(e){}", null)
                            }
                        } catch (_: Exception) {}
                        // 清空已回传的结果，释放内存
                        for (i in 0 until results.length()) results.remove(0)
                        chunkCount = 0
                    }
                }
                ok(reqId, JSONObject().put("total", urls.size))
            } catch (e: Exception) { err(reqId, "批量解析失败: ${e.message}") }
        }
    }

    @JavascriptInterface
    fun getVideoInfo(reqId: Int, type: String, aid: String, epId: String, bvid: String) {
        executor.execute {
            try {
                val parsed = BilibiliApi.ParsedId(type, aid, epId, bvid)
                val info = BilibiliApi.getVideoInfo(parsed)
                val j = JSONObject()
                j.put("title", info.title)
                j.put("pic", info.pic)
                j.put("desc", info.desc)
                j.put("upperName", info.upperName)
                j.put("ownerMid", info.ownerMid)
                j.put("pubTime", info.pubTime)
                j.put("isBangumi", info.isBangumi)
                j.put("isCheese", info.isCheese)
                j.put("bvid", info.bvid)
                j.put("play", info.play)
                j.put("danmaku", info.danmaku)
                j.put("duration", info.duration)
                j.put("ownerFace", info.ownerFace)
                j.put("officialType", info.officialType)
                j.put("vipType", info.vipType)
                j.put("vipStatus", info.vipStatus)
                val pages = JSONArray()
                for (p in info.pages) {
                    val pj = JSONObject()
                    pj.put("index", p.index); pj.put("aid", p.aid); pj.put("cid", p.cid)
                    pj.put("epid", p.epid); pj.put("title", p.title); pj.put("duration", p.duration)
                    pages.put(pj)
                }
                j.put("pages", pages)
                ok(reqId, j)
            } catch (e: Exception) { err(reqId, "获取视频信息失败: ${e.message}") }
        }
    }

    @JavascriptInterface
    fun getPlayInfo(reqId: Int, aid: String, cid: String, epid: String, isBangumi: Boolean, isCheese: Boolean) {
        executor.execute {
            try {
                val play = BilibiliApi.getPlayInfo(aid, cid, epid, isBangumi, isCheese = isCheese)
                val j = JSONObject()
                val vs = JSONArray()
                for (v in play.videos) {
                    val vj = JSONObject()
                    vj.put("id", v.id); vj.put("dfn", v.dfn); vj.put("codecs", v.codecs)
                    vj.put("bandwidth", v.bandwidth); vj.put("res", v.res); vj.put("fps", v.fps)
                    vj.put("size", v.size)
                    vs.put(vj)
                }
                val as_ = JSONArray()
                for (a in play.audios) {
                    val aj = JSONObject()
                    aj.put("id", a.id); aj.put("codecs", a.codecs); aj.put("bandwidth", a.bandwidth)
                    as_.put(aj)
                }
                j.put("videos", vs); j.put("audios", as_); j.put("dur", play.dur)
                ok(reqId, j)
            } catch (e: Exception) { err(reqId, "获取播放流失败: ${e.message}") }
        }
    }

    /**
     * 获取指定 URL 实际可用的流信息（用于下载前预览选择）。
     * 返回格式:
     * {
     *   title: "视频标题",
     *   videos: [{dfn, codecs, res, fps, bandwidth, size, id}],
     *   audios: [{id, codecs, bandwidth}],
     *   subtitles: [{lan,lanDoc}],
     *   pages: [{index, title, cid}]
     * }
     */
    @JavascriptInterface
    fun getAvailableStreams(reqId: Int, url: String) {
        executor.execute {
            try {
                val parsed = BilibiliApi.parseUrl(url)
                val info = BilibiliApi.getVideoInfo(parsed)
                val isBangumi = parsed.epId.isNotEmpty() && parsed.type != "cheese"
                val isCheese = parsed.type == "cheese"
                val firstPage = info.pages.firstOrNull() ?: PageInfo(index = 1, aid = parsed.aid, cid = "", epid = parsed.epId)
                val play = BilibiliApi.getPlayInfo(firstPage.aid, firstPage.cid, firstPage.epid, isBangumi, isCheese = isCheese)

                val j = JSONObject()
                j.put("title", info.title)
                j.put("totalPages", info.pages.size)

                // 去重视频流：按 dfn + codecs 去重，保留带宽最高的
                val videoMap = LinkedHashMap<String, com.bbdown.app.core.VideoTrack>()
                for (v in play.videos) {
                    val key = "${v.dfn}_${v.codecs}"
                    val existing = videoMap[key]
                    if (existing == null || v.bandwidth > existing.bandwidth) {
                        videoMap[key] = v
                    }
                }
                val vs = JSONArray()
                for (v in videoMap.values.sortedByDescending { it.id.toIntOrNull() ?: 0 }) {
                    val vj = JSONObject()
                    vj.put("id", v.id); vj.put("dfn", v.dfn); vj.put("codecs", v.codecs)
                    vj.put("bandwidth", v.bandwidth); vj.put("res", v.res); vj.put("fps", v.fps)
                    vj.put("size", v.size)
                    vs.put(vj)
                }

                // 去重音频流：按 codecs 去重，保留带宽最高的
                val audioMap = LinkedHashMap<String, com.bbdown.app.core.AudioTrack>()
                for (a in play.audios) {
                    val existing = audioMap[a.codecs]
                    if (existing == null || a.bandwidth > existing.bandwidth) {
                        audioMap[a.codecs] = a
                    }
                }
                val as_ = JSONArray()
                for (a in audioMap.values.sortedByDescending { it.bandwidth }) {
                    val aj = JSONObject()
                    aj.put("id", a.id); aj.put("codecs", a.codecs); aj.put("bandwidth", a.bandwidth)
                    as_.put(aj)
                }

                // 分P列表
                val pages = JSONArray()
                for (p in info.pages) {
                    val pj = JSONObject()
                    pj.put("index", p.index); pj.put("title", p.title); pj.put("cid", p.cid)
                    pages.put(pj)
                }

                j.put("videos", vs); j.put("audios", as_)
                j.put("pages", pages); j.put("dur", play.dur)
                ok(reqId, j)
            } catch (e: Exception) { err(reqId, "获取流信息失败: ${e.message}") }
        }
    }

    /**
     * 获取流信息并弹出原生选择对话框，用户选择后回调结果。
     * 格式与原版 BBDown 一致：展示所有可用流，用户点选。
     * 回调: ok(reqId, {videoIndex, audioIndex, video: {...}, audio: {...}})
     */
    @JavascriptInterface
    fun fetchAndPickStream(reqId: Int, url: String) {
        executor.execute {
            try {
                val parsed = BilibiliApi.parseUrl(url)
                val info = BilibiliApi.getVideoInfo(parsed)
                val isBangumi = parsed.epId.isNotEmpty() && parsed.type != "cheese"
                val isCheese = parsed.type == "cheese"
                val firstPage = info.pages.firstOrNull()
                    ?: PageInfo(index = 1, aid = parsed.aid, cid = "", epid = parsed.epId)
                val play = BilibiliApi.getPlayInfo(
                    firstPage.aid, firstPage.cid, firstPage.epid, isBangumi, isCheese = isCheese
                )

                if (play.videos.isEmpty() && play.audios.isEmpty()) {
                    err(reqId, "无可用流（可能需要登录Cookie或大会员）")
                    return@execute
                }

                // 去重视频流
                val videoMap = LinkedHashMap<String, com.bbdown.app.core.VideoTrack>()
                for (v in play.videos) {
                    val key = "${v.dfn}_${v.codecs}"
                    val existing = videoMap[key]
                    if (existing == null || v.bandwidth > existing.bandwidth) videoMap[key] = v
                }
                val videoList = videoMap.values.sortedByDescending { it.id.toIntOrNull() ?: 0 }

                // 去重音频流
                val audioMap = LinkedHashMap<String, com.bbdown.app.core.AudioTrack>()
                for (a in play.audios) {
                    val existing = audioMap[a.codecs]
                    if (existing == null || a.bandwidth > existing.bandwidth) audioMap[a.codecs] = a
                }
                val audioList = audioMap.values.sortedByDescending { it.bandwidth }

                // 格式化视频流列表（与原版 BBDown 一致）
                val videoLabels = videoList.map { v ->
                    val sizeMB = if (v.size > 0) String.format("~%.2f MB", v.size / 1024 / 1024) else ""
                    "[${v.dfn}] [${v.res}] [${v.codecs}] [${v.fps}] [${v.bandwidth} kbps] $sizeMB"
                }
                // 格式化音频流列表
                val audioLabels = audioList.map { a ->
                    val sizeKB = a.bandwidth * (play.dur.coerceAtLeast(1)) / 8 / 1024
                    val sizeStr = if (sizeKB > 1024) String.format("~%.2f MB", sizeKB / 1024.0)
                    else String.format("~%.2f KB", sizeKB.toDouble())
                    "[${a.codecs}] [${a.bandwidth} kbps] $sizeStr"
                }

                // 构建展示文本
                val sb = StringBuilder()
                if (videoList.isNotEmpty()) {
                    sb.appendLine("共计${videoList.size}条视频流:")
                    videoLabels.forEachIndexed { i, label -> sb.appendLine("  $i. $label") }
                }
                if (audioList.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.appendLine()
                    sb.appendLine("共计${audioList.size}条音频流:")
                    audioLabels.forEachIndexed { i, label -> sb.appendLine("  $i. $label") }
                }

                // 在主线程弹出选择对话框
                val activity = context as? android.app.Activity
                if (activity == null) {
                    err(reqId, "无法显示选择对话框")
                    return@execute
                }

                // 存储用户选择的结果
                val resultLock = Object()
                var userCancelled = true
                var selectedVideoIdx = 0
                var selectedAudioIdx = 0

                activity.runOnUiThread {
                    // 视频选择
                    if (videoList.isEmpty()) {
                        // 只有音频，直接选音频
                        pickAudio(reqId, audioLabels, audioList, play, info, url)
                        return@runOnUiThread
                    }

                    val videoArray = videoLabels.toTypedArray()
                    android.app.AlertDialog.Builder(context)
                        .setTitle("选择视频流 - ${info.title.take(30)}")
                        .setSingleChoiceItems(videoArray, 0) { dialog, which ->
                            selectedVideoIdx = which
                            dialog.dismiss()

                            // 接着选音频
                            if (audioList.isEmpty()) {
                                // 只有视频，直接返回
                                val v = videoList[selectedVideoIdx]
                                val result = JSONObject()
                                result.put("videoIndex", selectedVideoIdx)
                                result.put("audioIndex", -1)
                                val vj = JSONObject()
                                vj.put("id", v.id); vj.put("dfn", v.dfn); vj.put("codecs", v.codecs)
                                vj.put("bandwidth", v.bandwidth); vj.put("res", v.res); vj.put("fps", v.fps)
                                vj.put("size", v.size)
                                result.put("video", vj)
                                userCancelled = false
                                ok(reqId, result)
                            } else {
                                val audioArray = audioLabels.toTypedArray()
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("选择音频流")
                                    .setSingleChoiceItems(audioArray, 0) { dialog2, which2 ->
                                        dialog2.dismiss()
                                        selectedAudioIdx = which2
                                        val v = videoList[selectedVideoIdx]
                                        val a = audioList[selectedAudioIdx]
                                        val result = JSONObject()
                                        result.put("videoIndex", selectedVideoIdx)
                                        result.put("audioIndex", selectedAudioIdx)
                                        val vj = JSONObject()
                                        vj.put("id", v.id); vj.put("dfn", v.dfn); vj.put("codecs", v.codecs)
                                        vj.put("bandwidth", v.bandwidth); vj.put("res", v.res); vj.put("fps", v.fps)
                                        vj.put("size", v.size)
                                        result.put("video", vj)
                                        val aj = JSONObject()
                                        aj.put("id", a.id); aj.put("codecs", a.codecs); aj.put("bandwidth", a.bandwidth)
                                        result.put("audio", aj)
                                        userCancelled = false
                                        ok(reqId, result)
                                    }
                                    .setNegativeButton("取消") { d, _ -> d.dismiss(); err(reqId, "用户取消选择") }
                                    .show()
                            }
                        }
                        .setNegativeButton("取消") { d, _ -> d.dismiss(); err(reqId, "用户取消选择") }
                        .show()
                }
            } catch (e: Exception) {
                err(reqId, "获取流信息失败: ${e.message}")
            }
        }
    }

    /** 仅音频模式的流选择 */
    private fun pickAudio(reqId: Int, audioLabels: List<String>, audioList: List<com.bbdown.app.core.AudioTrack>,
                          play: com.bbdown.app.core.PlayInfo, info: com.bbdown.app.core.VideoInfo, url: String) {
        val activity = context as? android.app.Activity ?: return
        val audioArray = audioLabels.toTypedArray()
        activity.runOnUiThread {
            android.app.AlertDialog.Builder(context)
                .setTitle("选择音频流 - ${info.title.take(30)}")
                .setSingleChoiceItems(audioArray, 0) { dialog, which ->
                    dialog.dismiss()
                    val a = audioList[which]
                    val result = JSONObject()
                    result.put("videoIndex", -1)
                    result.put("audioIndex", which)
                    val aj = JSONObject()
                    aj.put("id", a.id); aj.put("codecs", a.codecs); aj.put("bandwidth", a.bandwidth)
                    result.put("audio", aj)
                    ok(reqId, result)
                }
                .setNegativeButton("取消") { d, _ -> d.dismiss(); err(reqId, "用户取消选择") }
                .show()
        }
    }

    @JavascriptInterface
    fun addTask(reqId: Int, taskJson: String) {
        executor.execute {
            try {
                val j = JSONObject(taskJson)
                val pages = ArrayList<PageInfo>()
                val parr = j.getJSONArray("pages")
                for (i in 0 until parr.length()) {
                    val p = parr.getJSONObject(i)
                    pages.add(PageInfo(
                        index = p.getInt("index"), aid = p.optString("aid"), cid = p.optString("cid"),
                        epid = p.optString("epid"), title = p.optString("title"), duration = p.optInt("duration")
                    ))
                }
                val taskId = "t_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(6)
                val task = DownloadTask(
                    taskId = taskId,
                    url = j.optString("url"),
                    title = j.optString("title"),
                    pic = j.optString("pic"),
                    pages = pages,
                    videoId = j.optString("videoId", "80"),
                    preferCodec = j.optString("preferCodec", "avc"),
                    preferAudio = j.optString("preferAudio", "m4a"),
                    cookie = Http.cookie,
                    downloadMode = j.optString("downloadMode", "all"),
                    downloadDanmaku = j.optBoolean("downloadDanmaku", false),
                    skipMux = j.optBoolean("skipMux", false),
                    skipSubtitle = j.optBoolean("skipSubtitle", false),
                    skipCover = j.optBoolean("skipCover", false),
                    skipAi = j.optBoolean("skipAi", true),
                    videoAscending = j.optBoolean("videoAscending", false),
                    audioAscending = j.optBoolean("audioAscending", false),
                    filePattern = j.optString("filePattern", ""),
                    forceHttp = j.optBoolean("forceHttp", false),
                    isCheese = j.optBoolean("isCheese", false),
                    collectionTitle = j.optString("collectionTitle", ""),
                    collectionIndex = j.optInt("collectionIndex", 0),
                    upperName = j.optString("upperName", ""),
                    desc = j.optString("desc", ""),
                    pubTime = j.optLong("pubTime", 0),
                    bvid = j.optString("bvid", ""),
                    ownerMid = j.optString("ownerMid", "")
                )
                Logger.i("Bridge", "添加任务: ${task.title} (${pages.size}P, mode=${task.downloadMode})")
                TaskManager.add(task)
                ok(reqId, JSONObject().put("taskId", taskId))
            } catch (e: Exception) { err(reqId, "添加任务失败: ${e.message}") }
        }
    }

    /**
     * 在 UI 线程弹出流选择对话框，后台线程阻塞等待用户选择。
     * @return Pair<videoId, audioId> 或 null（用户取消）
     */
    private fun showStreamPickerDialog(play: com.bbdown.app.core.PlayInfo, title: String): Pair<String, String>? {
        val latch = CountDownLatch(1)
        var result: Pair<String, String>? = null

        val activity = context as? Activity ?: return null

        activity.runOnUiThread {
            try {
                val dp = (context.resources.displayMetrics.density * 16).toInt()

                val scrollView = ScrollView(context).apply {
                    setPadding(dp * 2, dp, dp * 2, dp)
                }
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }

                // 视频流分组
                var selectedVideoIdx = 0
                if (play.videos.isNotEmpty()) {
                    container.addView(TextView(context).apply {
                        text = "视频流 (${play.videos.size}条)"
                        textSize = 12f
                        setTextColor(0xFF888888.toInt())
                        setPadding(0, dp, 0, dp / 2)
                    })
                    val vg = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
                    play.videos.forEachIndexed { idx, v ->
                        val dfn = v.dfn.ifEmpty { "未知" }
                        val res = if (v.res.isNotEmpty()) "/${v.res}" else ""
                        val codecs = v.codecs.uppercase()
                        val fps = if (v.fps.isNotEmpty()) "/${v.fps}fps" else ""
                        val bw = "${v.bandwidth}kbps"
                        val label = "$dfn$res $codecs$fps $bw"
                        val rb = RadioButton(context).apply {
                            this.text = label
                            textSize = 14f
                            id = idx
                            setPadding(dp / 2, dp / 4, 0, dp / 4)
                        }
                        vg.addView(rb)
                    }
                    vg.check(0)
                    vg.setOnCheckedChangeListener { _, checkedId -> selectedVideoIdx = checkedId }
                    container.addView(vg)
                }

                // 音频流分组
                var selectedAudioIdx = 0
                if (play.audios.isNotEmpty()) {
                    container.addView(TextView(context).apply {
                        text = "音频流 (${play.audios.size}条)"
                        textSize = 12f
                        setTextColor(0xFF888888.toInt())
                        setPadding(0, dp * 2, 0, dp / 2)
                    })
                    val ag = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
                    play.audios.forEachIndexed { idx, a ->
                        val codecs = a.codecs.uppercase()
                        val bw = "${a.bandwidth}kbps"
                        val label = "$codecs $bw"
                        val rb = RadioButton(context).apply {
                            this.text = label
                            textSize = 14f
                            id = idx
                            setPadding(dp / 2, dp / 4, 0, dp / 4)
                        }
                        ag.addView(rb)
                    }
                    ag.check(0)
                    ag.setOnCheckedChangeListener { _, checkedId -> selectedAudioIdx = checkedId }
                    container.addView(ag)
                }

                scrollView.addView(container)

                val dialog = AlertDialog.Builder(context)
                    .setTitle("选择流 - $title")
                    .setView(scrollView)
                    .setCancelable(true)
                    .setPositiveButton("下载") { _, _ ->
                        val vid = if (play.videos.isNotEmpty()) play.videos[selectedVideoIdx].id else ""
                        val aid = if (play.audios.isNotEmpty()) play.audios[selectedAudioIdx].id else ""
                        result = Pair(vid, aid)
                        latch.countDown()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        result = null
                        latch.countDown()
                    }
                    .setOnCancelListener {
                        result = null
                        latch.countDown()
                    }
                    .create()
                dialog.show()
            } catch (e: Exception) {
                Logger.w("Bridge", "对话框创建失败: ${e.message}")
                latch.countDown()
            }
        }

        latch.await(120, TimeUnit.SECONDS)
        return result
    }

    /** 批量添加任务 */
    @JavascriptInterface
    fun addBatchTasks(reqId: Int, tasksJson: String) {
        Logger.i("Bridge", "addBatchTasks")
        executor.execute {
            try {
                val arr = JSONArray(tasksJson)
                val taskIds = JSONArray()
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    val pages = ArrayList<PageInfo>()
                    val parr = j.getJSONArray("pages")
                    for (k in 0 until parr.length()) {
                        val p = parr.getJSONObject(k)
                        pages.add(PageInfo(
                            index = p.getInt("index"), aid = p.optString("aid"), cid = p.optString("cid"),
                            epid = p.optString("epid"), title = p.optString("title"), duration = p.optInt("duration")
                        ))
                    }
                    val taskId = "t_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(6)
                    val task = DownloadTask(
                        taskId = taskId,
                        url = j.optString("url"),
                        title = j.optString("title"),
                        pic = j.optString("pic"),
                        pages = pages,
                        videoId = j.optString("videoId", "80"),
                        preferCodec = j.optString("preferCodec", "avc"),
                        preferAudio = j.optString("preferAudio", "m4a"),
                        cookie = Http.cookie,
                        downloadMode = j.optString("downloadMode", "all"),
                        downloadDanmaku = j.optBoolean("downloadDanmaku", false),
                        skipMux = j.optBoolean("skipMux", false),
                        skipSubtitle = j.optBoolean("skipSubtitle", false),
                        skipCover = j.optBoolean("skipCover", false),
                        skipAi = j.optBoolean("skipAi", true),
                        videoAscending = j.optBoolean("videoAscending", false),
                        audioAscending = j.optBoolean("audioAscending", false),
                        filePattern = j.optString("filePattern", ""),
                        forceHttp = j.optBoolean("forceHttp", false),
                        isCheese = j.optBoolean("isCheese", false),
                        collectionTitle = j.optString("collectionTitle", ""),
                        collectionIndex = j.optInt("collectionIndex", 0),
                        upperName = j.optString("upperName", ""),
                        desc = j.optString("desc", ""),
                        pubTime = j.optLong("pubTime", 0),
                        bvid = j.optString("bvid", ""),
                        ownerMid = j.optString("ownerMid", "")
                    )
                    TaskManager.add(task)
                    taskIds.put(taskId)
                    Logger.i("Bridge", "批量任务 ${i+1}/${arr.length()}: ${task.title} (seq=${task.seq})")
                }
                Logger.i("Bridge", "批量添加完成，共 ${arr.length()} 个任务，按 seq 顺序执行")
                ok(reqId, JSONObject().put("taskIds", taskIds))
            } catch (e: Exception) { err(reqId, "批量添加失败: ${e.message}") }
        }
    }

    @JavascriptInterface
    fun getTasks(reqId: Int) {
        val arr = JSONArray()
        for (t in TaskManager.all) {
            val j = JSONObject()
            j.put("taskId", t.taskId); j.put("title", t.title); j.put("pic", t.pic)
            j.put("url", t.url); j.put("status", t.status)
            // 保留 3 位小数，减小每秒轮询的 JSON 体积（前端按 Math.round(p*100) 展示）
            j.put("progress", Math.round(t.progress * 1000f) / 1000f)
            j.put("downloadedBytes", t.downloadedBytes); j.put("totalBytes", t.totalBytes)
            j.put("speed", t.speed); j.put("errorMsg", t.errorMsg)
            j.put("pageCount", t.pages.size); j.put("outputFiles", JSONArray(t.outputFiles))
            j.put("createTime", t.createTime); j.put("finishTime", t.finishTime)
            j.put("seq", t.seq)
            j.put("isRunning", t.isRunning)
            arr.put(j)
        }
        // 轮询节流：进度变化无需每秒写盘（断点续传由 .dl 文件负责，状态变更路径已自行保存）；
        // 服务启停同样节流，避免批量任务间频繁 start/stop 系统调用
        val now = System.currentTimeMillis()
        if (now - lastTaskSaveTime >= 10_000) {
            lastTaskSaveTime = now
            try { TaskStore.save() } catch (_: Exception) {}
        }
        if (now - lastServiceUpdateTime >= 5_000) {
            lastServiceUpdateTime = now
            try { DownloadService.update(context) } catch (_: Exception) {}
        }
        ok(reqId, arr)
    }

    @JavascriptInterface
    fun cancelTask(reqId: Int, taskId: String) {
        TaskManager.cancel(taskId); ok(reqId)
    }

    /** 暂停任务（可恢复继续下载） */
    @JavascriptInterface
    fun pauseTask(reqId: Int, taskId: String) {
        TaskManager.pause(taskId); ok(reqId)
    }

    /** 恢复暂停的任务 */
    @JavascriptInterface
    fun resumeTask(reqId: Int, taskId: String) {
        executor.execute {
            try {
                val task = TaskManager.get(taskId)
                if (task == null) {
                    err(reqId, "任务不存在")
                    return@execute
                }
                if (!task.isPaused) {
                    err(reqId, "任务未处于暂停状态")
                    return@execute
                }
                TaskManager.resumePausedTask(task)
                ok(reqId)
            } catch (e: Exception) {
                err(reqId, "恢复失败: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun removeTask(reqId: Int, taskId: String) {
        TaskManager.remove(taskId); ok(reqId)
    }

    /** 续传任务（失败/中断后重新下载，支持断点续传） */
    @JavascriptInterface
    fun retryTask(reqId: Int, taskId: String) {
        executor.execute {
            try {
                val task = TaskManager.get(taskId)
                if (task == null) {
                    err(reqId, "任务不存在")
                    return@execute
                }
                if (task.isRunning) {
                    err(reqId, "任务正在运行中")
                    return@execute
                }
                TaskManager.resumeTask(task)
                ok(reqId)
            } catch (e: Exception) {
                err(reqId, "续传失败: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun clearFinished(reqId: Int) {
        TaskManager.clearFinished(); ok(reqId)
    }

    // ==================== 缓存管理 ====================

    /** 获取各类缓存大小 → {tempFiles, logs, webCache, total} */
    @JavascriptInterface
    fun getCacheSizes(reqId: Int) {
        executor.execute {
            try {
                val j = JSONObject()
                // 下载临时文件（.dl/.vpart/.apart/.meta.*）
                val outputDir = TaskManager.outputDir
                var tempSize = 0L
                var tempCount = 0
                if (outputDir.exists()) {
                    outputDir.walkTopDown().forEach { f ->
                        if (f.isFile && (f.name.endsWith(".dl") || f.name.endsWith(".vpart") ||
                                f.name.endsWith(".apart") || f.name.contains(".meta."))) {
                            tempSize += f.length()
                            tempCount++
                        }
                    }
                }
                j.put("tempFiles", tempSize)
                j.put("tempCount", tempCount)
                // 日志文件：区分崩溃日志(crash_*)和调试日志(bbdown_log_*)
                val logDir = File(context.getExternalFilesDir(null), "logs")
                var crashLogSize = 0L
                var crashLogCount = 0
                var debugLogSize = 0L
                var debugLogCount = 0
                if (logDir.exists()) {
                    logDir.listFiles()?.forEach { f ->
                        if (f.isFile) {
                            if (f.name.startsWith("crash_") || f.name.startsWith("native_crash_") || f.name.startsWith("native_signal_")) {
                                crashLogSize += f.length(); crashLogCount++
                            } else {
                                debugLogSize += f.length(); debugLogCount++
                            }
                        }
                    }
                }
                j.put("crashLogs", crashLogSize)
                j.put("crashLogCount", crashLogCount)
                j.put("debugLogs", debugLogSize)
                j.put("debugLogCount", debugLogCount)
                // 兼容旧前端的 logs 字段（总量）
                j.put("logs", crashLogSize + debugLogSize)
                j.put("logCount", crashLogCount + debugLogCount)
                // WebView 缓存
                var webCacheSize = 0L
                val cacheDir = context.cacheDir
                if (cacheDir.exists()) {
                    cacheDir.walkTopDown().forEach { f -> if (f.isFile) webCacheSize += f.length() }
                }
                j.put("webCache", webCacheSize)
                j.put("total", tempSize + crashLogSize + debugLogSize + webCacheSize)
                ok(reqId, j)
            } catch (e: Exception) {
                err(reqId, "获取缓存大小失败: ${e.message}")
            }
        }
    }

    /** 清理指定类型的缓存 */
    @JavascriptInterface
    fun clearCache(reqId: Int, type: String) {
        executor.execute {
            try {
                var cleared = 0L
                when (type) {
                    "temp" -> {
                        val outputDir = TaskManager.outputDir
                        if (outputDir.exists()) {
                            outputDir.walkTopDown().forEach { f ->
                                if (f.isFile && (f.name.endsWith(".dl") || f.name.endsWith(".vpart") ||
                                        f.name.endsWith(".apart") || f.name.contains(".meta."))) {
                                    cleared += f.length()
                                    f.delete()
                                }
                            }
                        }
                    }
                    "logs" -> {
                        val logDir = File(context.getExternalFilesDir(null), "logs")
                        if (logDir.exists()) {
                            logDir.listFiles()?.forEach { f ->
                                if (f.isFile) { cleared += f.length(); f.delete() }
                            }
                        }
                    }
                    "crashLogs" -> {
                        val logDir = File(context.getExternalFilesDir(null), "logs")
                        if (logDir.exists()) {
                            logDir.listFiles()?.forEach { f ->
                                if (f.isFile && f.name.startsWith("crash_")) { cleared += f.length(); f.delete() }
                            }
                        }
                    }
                    "debugLogs" -> {
                        val logDir = File(context.getExternalFilesDir(null), "logs")
                        if (logDir.exists()) {
                            logDir.listFiles()?.forEach { f ->
                                if (f.isFile && !f.name.startsWith("crash_")
                                    && !f.name.startsWith("native_crash_")
                                    && !f.name.startsWith("native_signal_")) {
                                    cleared += f.length(); f.delete()
                                }
                            }
                        }
                    }
                    "webCache" -> {
                        val cacheDir = context.cacheDir
                        if (cacheDir.exists()) {
                            cacheDir.walkTopDown().forEach { f ->
                                if (f.isFile) { cleared += f.length(); f.delete() }
                            }
                        }
                        // 同时让 WebView 清理内存缓存
                        webView.post {
                            android.webkit.WebStorage.getInstance().deleteAllData()
                            webView.clearCache(true)
                        }
                    }
                    "all" -> {
                        // 临时文件
                        val outputDir = TaskManager.outputDir
                        if (outputDir.exists()) {
                            outputDir.walkTopDown().forEach { f ->
                                if (f.isFile && (f.name.endsWith(".dl") || f.name.endsWith(".vpart") ||
                                        f.name.endsWith(".apart") || f.name.contains(".meta."))) {
                                    cleared += f.length(); f.delete()
                                }
                            }
                        }
                        // 日志
                        val logDir = File(context.getExternalFilesDir(null), "logs")
                        if (logDir.exists()) {
                            logDir.listFiles()?.forEach { f ->
                                if (f.isFile) { cleared += f.length(); f.delete() }
                            }
                        }
                        // WebView 缓存
                        val cacheDir = context.cacheDir
                        if (cacheDir.exists()) {
                            cacheDir.walkTopDown().forEach { f ->
                                if (f.isFile) { cleared += f.length(); f.delete() }
                            }
                        }
                        webView.post {
                            android.webkit.WebStorage.getInstance().deleteAllData()
                            webView.clearCache(true)
                        }
                    }
                }
                Logger.i("Bridge", "清理缓存($type): 释放 ${cleared / 1024}KB")
                ok(reqId, JSONObject().put("cleared", cleared))
            } catch (e: Exception) {
                err(reqId, "清理缓存失败: ${e.message}")
            }
        }
    }

    // ==================== 收藏夹 ====================

    /** 获取当前登录用户的收藏夹列表
     *  两阶段返回：先返回缓存数据（如有），再后台刷新并推送最新数据 */
    @JavascriptInterface
    fun getFavFolders(reqId: Int) {
        executor.execute {
            try {
                // 优先使用 web_mid，其次 tv_mid（兼容旧版存储的 mid）
                val mid = prefs.getString("web_mid", "")?.takeIf { it.isNotBlank() }
                    ?: prefs.getString("tv_mid", "")?.takeIf { it.isNotBlank() }
                    ?: prefs.getString("mid", "") ?: ""
                if (mid.isBlank()) {
                    err(reqId, "未登录，无法获取收藏夹")
                    return@execute
                }

                // Phase 1: 如果有缓存，立即返回
                val cachedStr = prefs.getString("fav_folders_cache", "") ?: ""
                val cachedMid = prefs.getString("fav_folders_cache_mid", "") ?: ""
                var hasCache = false
                if (cachedStr.isNotBlank() && cachedMid == mid) {
                    try {
                        val cachedArr = JSONArray(cachedStr)
                        ok(reqId, cachedArr)
                        hasCache = true
                        Logger.d("Bridge", "收藏夹: 返回缓存数据 (${cachedArr.length()} 个)")
                    } catch (_: Exception) {
                        hasCache = false
                    }
                }

                // Phase 2: 后台获取最新收藏夹
                Logger.i("Bridge", "获取收藏夹(网络): mid=$mid")
                val folders = BilibiliApi.getFavFolders(mid)
                val arr = JSONArray()
                for (f in folders) {
                    val j = JSONObject()
                    j.put("id", f.id); j.put("title", f.title)
                    j.put("mediaCount", f.mediaCount); j.put("cover", f.cover)
                    arr.put(j)
                }

                if (hasCache) {
                    // 已返回缓存，推送最新数据到 JS
                    val jsonStr = arr.toString()
                    webView.post {
                        webView.evaluateJavascript("try{window.__onFavFoldersUpdate($jsonStr);}catch(e){}", null)
                    }
                    // 更新缓存
                    prefs.edit()
                        .putString("fav_folders_cache", jsonStr)
                        .putString("fav_folders_cache_mid", mid)
                        .commit()
                } else {
                    // 无缓存，直接返回
                    ok(reqId, arr)
                    prefs.edit()
                        .putString("fav_folders_cache", arr.toString())
                        .putString("fav_folders_cache_mid", mid)
                        .commit()
                }
            } catch (e: Exception) { err(reqId, "获取收藏夹失败: ${e.message}") }
        }
    }

    /** 获取收藏夹内视频列表 */
    @JavascriptInterface
    fun getFavList(reqId: Int, mediaId: String, page: Int) {
        executor.execute {
            try {
                Logger.i("Bridge", "获取收藏夹视频: mediaId=$mediaId, page=$page")
                val (items, total) = BilibiliApi.getFavList(mediaId, page)
                val arr = JSONArray()
                for (item in items) {
                    val j = JSONObject()
                    j.put("bvid", item.bvid); j.put("title", item.title)
                    j.put("pic", item.pic); j.put("ownerName", item.upper)
                    j.put("duration", item.duration)
                    j.put("ownerMid", item.ownerMid)
                    j.put("ownerFace", item.ownerFace)
                    j.put("play", item.play)
                    j.put("danmaku", item.danmaku)
                    j.put("pubdate", item.pubdate)
                    j.put("officialType", item.officialType)
                    j.put("vipType", item.vipType)
                    j.put("vipStatus", item.vipStatus)
                    j.put("favTime", item.favTime)
                    arr.put(j)
                }
                val result = JSONObject()
                result.put("items", arr)
                result.put("total", total)
                result.put("page", page)
                ok(reqId, result)
            } catch (e: Exception) { err(reqId, "获取收藏夹视频失败: ${e.message}") }
        }
    }

    // ==================== 合集检测 ====================

    /** 检测BV号是否属于合集，返回合集信息+视频BV列表
     *  综合检测：先检查ugc_season，未找到则搜索UP主合集/系列列表
     *  支持直播回放等ugc_season字段缺失的场景
     */
    @JavascriptInterface
    fun checkCollection(reqId: Int, bvid: String) {
        executor.execute {
            try {
                Logger.i("Bridge", "检测合集(综合): bvid=$bvid")
                val collection = BilibiliApi.checkCollectionComprehensive(bvid)
                if (collection == null) {
                    ok(reqId, JSONObject().put("found", false))
                    return@execute
                }
                // 尝试获取完整合集视频列表（含元数据）
                var fullList = collection.bvidList
                var metas = collection.videoMetas
                if (collection.mid.isNotEmpty() && collection.seasonId.isNotEmpty()) {
                    if (fullList.isEmpty()) {
                        val apiMetas = BilibiliApi.getUgcSeasonArchivesWithMeta(collection.mid, collection.seasonId)
                        if (apiMetas.isNotEmpty()) {
                            metas = apiMetas
                            fullList = apiMetas.map { it.bvid }
                        }
                    }
                }
                val j = JSONObject()
                j.put("found", true)
                j.put("seasonId", collection.seasonId)
                j.put("title", collection.title)
                j.put("mid", collection.mid)
                j.put("total", fullList.size)
                j.put("type", collection.type)  // "season"=合集, "series"=系列
                val bvArr = JSONArray()
                for (bv in fullList) bvArr.put(bv)
                j.put("bvidList", bvArr)
                // 返回视频元数据列表（标题、封面等），前端可直接展示无需额外API请求
                val metaArr = JSONArray()
                for (m in metas) {
                    val mo = JSONObject()
                    mo.put("bvid", m.bvid)
                    mo.put("aid", m.aid)
                    mo.put("cid", m.cid)
                    mo.put("title", m.title)
                    mo.put("pic", m.pic)
                    mo.put("duration", m.duration)
                    mo.put("pubdate", m.pubdate)
                    mo.put("ownerName", m.ownerName)
                    mo.put("ownerMid", m.ownerMid)
                    mo.put("ownerFace", m.ownerFace)
                    mo.put("play", m.play)
                    mo.put("danmaku", m.danmaku)
                    mo.put("officialType", m.officialType)
                    mo.put("vipType", m.vipType)
                    mo.put("vipStatus", m.vipStatus)
                    metaArr.put(mo)
                }
                j.put("videoMetas", metaArr)
                Logger.i("Bridge", "合集检测完成: ${collection.title}, ${fullList.size}个视频, ${metas.size}个含元数据")
                ok(reqId, j)
            } catch (e: Exception) { err(reqId, "检测合集失败: ${e.message}") }
        }
    }

    // ==================== UP主搜索与投稿视频 ====================

    /** 搜索UP主 → [{mid, uname, face, sign, fans, videoCount, officialType, officialDesc, vipType, vipStatus}] */
    @JavascriptInterface
    fun searchUpper(reqId: Int, keyword: String) {
        executor.execute {
            try {
                Logger.i("Bridge", "搜索UP主: $keyword")
                val results = BilibiliApi.searchUpper(keyword)
                val arr = JSONArray()
                for (u in results) {
                    val j = JSONObject()
                    j.put("mid", u.mid)
                    j.put("uname", u.uname)
                    j.put("face", u.face)
                    j.put("sign", u.sign)
                    j.put("fans", u.fans)
                    j.put("videoCount", u.videoCount)
                    j.put("officialType", u.officialType)
                    j.put("officialDesc", u.officialDesc)
                    j.put("vipType", u.vipType)
                    j.put("vipStatus", u.vipStatus)
                    arr.put(j)
                }
                ok(reqId, arr)
            } catch (e: Exception) { err(reqId, "搜索UP主失败: ${e.message}") }
        }
    }

    /** 获取UP主投稿视频列表 → {items, total, page} */
    @JavascriptInterface
    fun getUpperVideos(reqId: Int, mid: String, page: Int) {
        executor.execute {
            try {
                Logger.i("Bridge", "获取UP主视频: mid=$mid, page=$page")
                val (videos, total) = BilibiliApi.getUpperVideos(mid, page)
                val arr = JSONArray()
                for (v in videos) {
                    val j = JSONObject()
                    j.put("bvid", v.bvid)
                    j.put("title", v.title)
                    j.put("pic", v.pic)
                    j.put("play", v.play)
                    j.put("danmaku", v.danmaku)
                    j.put("duration", v.duration)
                    j.put("created", v.created)
                    j.put("desc", v.desc)
                    j.put("ownerName", "")  // UP投稿视频列表中UP主信息由前端state提供
                    arr.put(j)
                }
                val result = JSONObject()
                result.put("items", arr)
                result.put("total", total)
                result.put("page", page)
                ok(reqId, result)
            } catch (e: Exception) { err(reqId, "获取UP主视频失败: ${e.message}") }
        }
    }

    /** 获取当前登录用户关注列表 → {items, total, page}
     *  orderType: "attention"=最常访问(最近比较在意)，""=按关注时间
     *  tagId: 0=全部，非0=指定分组；特别关注分组 tagid 为 -10
     */
    @JavascriptInterface
    fun getFollowings(reqId: Int, mid: String, page: Int, orderType: String, tagId: Int) {
        executor.execute {
            try {
                Logger.i("Bridge", "获取关注列表: mid=$mid, page=$page, orderType=$orderType, tagId=$tagId")
                val pageSize = if (page == 1) 20 else 50
                val (followings, total) = BilibiliApi.getFollowings(mid, page, pageSize, orderType, tagId)
                val arr = JSONArray()
                for (u in followings) {
                    val j = JSONObject()
                    j.put("mid", u.mid)
                    j.put("uname", u.uname)
                    j.put("face", u.face)
                    j.put("sign", u.sign)
                    j.put("fans", u.fans)
                    j.put("videoCount", u.videoCount)
                    j.put("officialType", u.officialType)
                    j.put("officialDesc", u.officialDesc)
                    j.put("vipType", u.vipType)
                    j.put("vipStatus", u.vipStatus)
                    j.put("special", u.special)
                    arr.put(j)
                }
                val result = JSONObject()
                result.put("items", arr)
                result.put("total", total)
                result.put("page", page)
                ok(reqId, result)
            } catch (e: Exception) { err(reqId, "获取关注列表失败: ${e.message}") }
        }
    }

    /** 获取关注分组(分类)列表 → [{tagid, name, count}] */
    @JavascriptInterface
    fun getFollowTags(reqId: Int) {
        executor.execute {
            try {
                Logger.i("Bridge", "获取关注分组(分类)")
                val tags = BilibiliApi.getFollowTags()
                val arr = JSONArray()
                for (t in tags) {
                    val j = JSONObject()
                    j.put("tagid", t.tagid)
                    j.put("name", t.name)
                    j.put("count", t.count)
                    arr.put(j)
                }
                ok(reqId, arr)
            } catch (e: Exception) { err(reqId, "获取关注分组失败: ${e.message}") }
        }
    }

    /** 刷新关注分组(清除缓存后重新请求) → [{tagid, name, count}] */
    @JavascriptInterface
    fun refreshFollowTags(reqId: Int) {
        executor.execute {
            try {
                Logger.i("Bridge", "刷新关注分组(清除缓存)")
                BilibiliApi.clearFollowTagsCache()
                val tags = BilibiliApi.getFollowTags()
                val arr = JSONArray()
                for (t in tags) {
                    val j = JSONObject()
                    j.put("tagid", t.tagid)
                    j.put("name", t.name)
                    j.put("count", t.count)
                    arr.put(j)
                }
                ok(reqId, arr)
            } catch (e: Exception) { err(reqId, "刷新关注分组失败: ${e.message}") }
        }
    }

    /** 搜索视频 → [{bvid, title, pic, author, mid, play, danmaku, duration, desc}] */
    @JavascriptInterface
    fun searchVideo(reqId: Int, keyword: String) {
        executor.execute {
            try {
                Logger.i("Bridge", "搜索视频: $keyword")
                val results = BilibiliApi.searchVideos(keyword)
                val arr = JSONArray()
                for (v in results) {
                    val j = JSONObject()
                    j.put("bvid", v.bvid)
                    j.put("title", v.title)
                    j.put("pic", v.pic)
                    j.put("author", v.author)
                    j.put("mid", v.mid)
                    j.put("play", v.play)
                    j.put("danmaku", v.danmaku)
                    j.put("duration", v.duration)
                    j.put("desc", v.desc)
                    j.put("pubdate", v.pubdate)
                    j.put("ownerFace", v.ownerFace)
                    j.put("officialType", v.officialType)
                    j.put("vipType", v.vipType)
                    j.put("vipStatus", v.vipStatus)
                    arr.put(j)
                }
                ok(reqId, arr)
            } catch (e: Exception) { err(reqId, "搜索视频失败: ${e.message}") }
        }
    }

    /** 允许通过 Bridge 修改的设置键白名单，防止覆盖 cookie 等敏感键 */
    private val allowedSettingKeys = setOf(
        "output_dir", "threads", "preferCodec", "preferAudio", "downloadMode",
        "skipSubtitle", "skipCover", "skipAi", "skipMux", "downloadDanmaku",
        "videoAscending", "audioAscending", "forceHttp", "batchQn",
        "delayPerPage", "filePattern", "clearOnExit", "theme", "debug_server", "check_update"
    )

    @JavascriptInterface
    fun setSetting(reqId: Int, key: String, value: String) {
        if (key !in allowedSettingKeys) {
            err(reqId, "不允许修改此设置: $key")
            return
        }
        prefs.edit().putString(key, value).commit()
        when (key) {
            "output_dir" -> {
                // 规范化：公共存储路径重定向到应用私有存储
                val normalized = normalizeOutputDir(context, value)
                TaskManager.outputDir = normalized
                // 如果被重定向了，更新存储的值为实际路径
                if (normalized.absolutePath != value) {
                    prefs.edit().putString("output_dir", normalized.absolutePath).commit()
                    Logger.i("Bridge", "output_dir 已重定向: $value -> ${normalized.absolutePath}")
                }
            }
            "threads" -> TaskManager.threads = value.toIntOrNull() ?: 8
            "delayPerPage" -> TaskManager.interTaskDelay = 0  // API限速已移除，忽略延迟设置
            "debug_server" -> {
                // 调试服务器开关：开启即启动 19865 端口 HTTP 服务，关闭即停
                if (value == "true") {
                    com.bbdown.app.core.DebugServer.start(context)
                    Logger.i("Bridge", "调试服务器已开启")
                } else {
                    com.bbdown.app.core.DebugServer.stop()
                    Logger.i("Bridge", "调试服务器已关闭")
                }
            }
        }
        ok(reqId)
    }

    @JavascriptInterface
    fun getSetting(reqId: Int, key: String) {
        ok(reqId, JSONObject().put("value", prefs.getString(key, defaultSetting(key))))
    }

    @JavascriptInterface
    fun getAllSettings(reqId: Int) {
        val j = JSONObject()
        j.put("output_dir", prefs.getString("output_dir", defaultOutputDir(context).absolutePath))
        j.put("threads", prefs.getString("threads", "8"))
        j.put("preferCodec", prefs.getString("preferCodec", "avc"))
        j.put("preferAudio", prefs.getString("preferAudio", "m4a"))
        j.put("downloadMode", prefs.getString("downloadMode", "all"))
        j.put("theme", prefs.getString("theme", "dark"))
        j.put("skipSubtitle", prefs.getString("skipSubtitle", "false"))
        j.put("skipCover", prefs.getString("skipCover", "false"))
        j.put("skipAi", prefs.getString("skipAi", "true"))
        j.put("skipMux", prefs.getString("skipMux", "false"))
        j.put("downloadDanmaku", prefs.getString("downloadDanmaku", "false"))
        j.put("videoAscending", prefs.getString("videoAscending", "false"))
        j.put("audioAscending", prefs.getString("audioAscending", "false"))
        j.put("forceHttp", prefs.getString("forceHttp", "false"))
        j.put("batchQn", prefs.getString("batchQn", "auto"))
        j.put("delayPerPage", prefs.getString("delayPerPage", "0"))
        j.put("filePattern", prefs.getString("filePattern", "{pageTitle}"))
        j.put("filePatternMultiPage", prefs.getString("filePatternMultiPage", "{pageTitle} P{pageNumber}"))
        j.put("filePatternCollection", prefs.getString("filePatternCollection", "{collectionIndex}. {pageTitle}"))
        j.put("filePatternCollectionMultiPage", prefs.getString("filePatternCollectionMultiPage", "{collectionIndex}. {videoTitle} P{pageNumber}"))
        j.put("clearOnExit", prefs.getString("clearOnExit", "false"))
        j.put("debug_server", prefs.getString("debug_server", "false"))
        j.put("check_update", prefs.getString("check_update", "true"))
        ok(reqId, j)
    }

    private fun defaultSetting(key: String): String = when (key) {
        "threads" -> "8"
        "preferCodec" -> "avc"
        "preferAudio" -> "m4a"
        "downloadMode" -> "all"
        "theme" -> "dark"
        "batchQn" -> "auto"
        "delayPerPage" -> "0"
        "filePattern" -> "{pageTitle}"
        "filePatternMultiPage" -> "{pageTitle} P{pageNumber}"
        "filePatternCollection" -> "{collectionIndex}. {pageTitle}"
        "filePatternCollectionMultiPage" -> "{collectionIndex}. {videoTitle} P{pageNumber}"
        "clearOnExit" -> "false"
        "debug_server" -> "false"
        "check_update" -> "true"
        "output_dir" -> defaultOutputDir(context).absolutePath
        else -> ""
    }

    // ==================== 打开B站App ====================

    /** 打开哔哩哔哩手机App，直接跳转到授权确认页面
     *  核心原理：使用 bilibili://browser?url= 深链接在B站App内置浏览器中打开授权URL，
     *  该URL是B站TV端登录的H5授权页面，在B站App内置浏览器中加载时会显示授权确认页。
     *  @param authUrl 二维码中的授权URL（HTTPS格式，如 passport.bilibili.com/x/passport-tv-login/h5/qrcode/auth?auth_code=xxx）
     */
    @JavascriptInterface
    fun openBiliApp(reqId: Int, authUrl: String) {
        try {
            val pm = context.packageManager
            val biliPackage = "tv.danmaku.bili"

            // 检查B站App是否已安装
            val isBiliInstalled = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(biliPackage, 0)
                true
            } catch (_: Exception) {
                false
            }

            if (!isBiliInstalled) {
                try {
                    val marketIntent = Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$biliPackage"))
                    marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(marketIntent)
                    ok(reqId, JSONObject().put("opened", true).put("method", "market").put("fallback", true))
                } catch (_: Exception) {
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://app.bilibili.com"))
                        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(browserIntent)
                        ok(reqId, JSONObject().put("opened", true).put("method", "browser").put("fallback", true))
                    } catch (_: Exception) {
                        err(reqId, "未检测到哔哩哔哩App，请先安装")
                    }
                }
                return
            }

            // ===== 已安装B站App，使用 bilibili://browser?url= 深链接打开授权页面 =====
            // bilibili://browser 是B站App的通用网页浏览器入口（route_type=web），
            // 在内置WebView中加载授权URL，B站App的登录态会自动带入，直接显示授权确认页。
            if (authUrl.isNotBlank()) {
                val encodedUrl = java.net.URLEncoder.encode(authUrl, "UTF-8")
                // 构建多种深链接格式，逐一尝试
                val deepLinks = listOf(
                    "bilibili://browser?url=$encodedUrl",
                    "bilibili://browser?url=$encodedUrl&navhide=1",
                    "bilibili://forward?url=$encodedUrl",
                    "activity://main/web?url=$encodedUrl"
                )

                for (deepLink in deepLinks) {
                    // 方式A：指定B站App包名 + 直接startActivity（跳过resolveActivity检查，
                    //       因为Android 11+包可见性限制可能导致resolveActivity返回null但实际可启动）
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.setPackage(biliPackage)
                        context.startActivity(intent)
                        Logger.i("Bridge", "openBiliApp 成功，深链接: $deepLink")
                        ok(reqId, JSONObject().put("opened", true).put("method", "browser_deep_link").put("auth", true))
                        return
                    } catch (_: android.content.ActivityNotFoundException) {
                    } catch (_: SecurityException) {
                    } catch (_: Exception) {}

                    // 方式B：不指定包名，让系统选择（bilibili:// scheme 只有B站App能处理）
                    if (deepLink.startsWith("bilibili://")) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            Logger.i("Bridge", "openBiliApp 成功(无包名)，深链接: $deepLink")
                            ok(reqId, JSONObject().put("opened", true).put("method", "browser_deep_link_no_pkg").put("auth", true))
                            return
                        } catch (_: android.content.ActivityNotFoundException) {
                        } catch (_: Exception) {}
                    }
                }
            }

            // 兜底：以上深链接均失败，直接启动B站App主界面
            val launchIntent = pm.getLaunchIntentForPackage(biliPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Logger.w("Bridge", "openBiliApp 深链接全部失败，回退到启动App主界面")
                ok(reqId, JSONObject().put("opened", true).put("method", "launch").put("auth", false)
                    .put("hint", "已打开B站App，请手动扫描二维码完成授权"))
                return
            }

            err(reqId, "无法跳转到B站授权页面，请手动扫描二维码")
        } catch (e: Exception) {
            err(reqId, "打开B站App失败: ${e.message}")
        }
    }

    // ==================== 文件访问权限 ====================

    /** 检查是否有所有文件访问权限(Android 11+)或写入权限(Android 10及以下) */
    @JavascriptInterface
    fun checkStoragePermission(reqId: Int) {
        try {
            val granted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                val perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            ok(reqId, JSONObject().put("granted", granted).put("sdkInt", android.os.Build.VERSION.SDK_INT))
        } catch (e: Exception) {
            ok(reqId, JSONObject().put("granted", false).put("error", e.message))
        }
    }

    /** 跳转到系统「所有文件访问权限」设置页(Android 11+) */
    @JavascriptInterface
    fun requestManageStorage(reqId: Int) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ok(reqId, JSONObject().put("opened", true))
            } else {
                // Android 10及以下：请求运行时权限
                val perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                if (context.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    (context as? android.app.Activity)?.let { activity ->
                        androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(perm), 1001)
                    }
                }
                ok(reqId, JSONObject().put("opened", false).put("reason", "runtime_permission"))
            }
        } catch (e: Exception) {
            // 某些定制ROM可能不支持直接跳转，回退到通用设置页
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ok(reqId, JSONObject().put("opened", true).put("fallback", true))
            } catch (e2: Exception) {
                err(reqId, "无法打开权限设置页: ${e2.message}")
            }
        }
    }

    @JavascriptInterface
    fun openFile(reqId: Int, path: String) {
        try {
            val file = File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file)
            val mime = when {
                path.endsWith(".mp4") -> "video/mp4"
                path.endsWith(".m4a") -> "audio/mp4"
                path.endsWith(".srt") -> "text/plain"
                path.endsWith(".xml") -> "text/xml"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".flac") -> "audio/flac"
                else -> "*/*"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ok(reqId)
        } catch (e: Exception) { err(reqId, "打开失败: ${e.message}") }
    }

    @JavascriptInterface
    fun shareFile(reqId: Int, path: String) {
        try {
            val file = File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file)
            // 根据文件扩展名动态设置 MIME 类型
            val mimeType = when {
                path.endsWith(".mp4", true) -> "video/mp4"
                path.endsWith(".m4a", true) -> "audio/mp4"
                path.endsWith(".flac", true) -> "audio/flac"
                path.endsWith(".aac", true) -> "audio/aac"
                path.endsWith(".srt", true) -> "application/x-subrip"
                path.endsWith(".ass", true) -> "text/x-ssa"
                path.endsWith(".xml", true) || path.endsWith(".json", true) -> "text/xml"
                path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
                path.endsWith(".png", true) -> "image/png"
                path.endsWith(".webp", true) -> "image/webp"
                else -> "application/octet-stream"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享文件").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            ok(reqId)
        } catch (e: Exception) { err(reqId, "分享失败: ${e.message}") }
    }
}
