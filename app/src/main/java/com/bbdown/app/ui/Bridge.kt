package com.bbdown.app.ui

import android.content.Context
import com.bbdown.app.core.BilibiliApi
import com.bbdown.app.core.Http
import com.bbdown.app.core.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 桥接原语层：把原 BBDownBridge 的 63 个 @JavascriptInterface 中真正被 UI 用到的操作
 * 搬成普通 suspend 函数。全部在调用方给的 IO 线程执行，异常直接抛给 ViewModel 处理
 * （不再像 JS 桥那样把错误吞成空列表——那正是"点了没反应"的根因）。
 */

/** 全局 prefs 访问器：与 Bridge 同名同文件，升级后设置自动继承 */
internal val appPrefs: android.content.SharedPreferences
    get() = BBDownApp.instance.getSharedPreferences("bbdown_settings", Context.MODE_PRIVATE)

/** 进程级 Context 持有者（Application 里赋值） */
class BBDownApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    companion object {
        lateinit var instance: BBDownApp
            private set
    }
}

// ==================== 设置 ====================

internal fun bridgeGetAllSettings(): JSONObject? = runCatching {
    val p = appPrefs
    JSONObject().apply {
        put("output_dir", p.getString("output_dir", "") ?: "")
        put("threads", p.getString("threads", "8"))
        put("preferCodec", p.getString("preferCodec", "avc"))
        put("preferAudio", p.getString("preferAudio", "m4a"))
        put("downloadMode", p.getString("downloadMode", "all"))
        put("theme", p.getString("theme", "dark"))
        put("skipSubtitle", p.getString("skipSubtitle", "false"))
        put("skipCover", p.getString("skipCover", "false"))
        put("skipAi", p.getString("skipAi", "true"))
        put("skipMux", p.getString("skipMux", "false"))
        put("downloadDanmaku", p.getString("downloadDanmaku", "false"))
        put("videoAscending", p.getString("videoAscending", "false"))
        put("audioAscending", p.getString("audioAscending", "false"))
        put("forceHttp", p.getString("forceHttp", "false"))
        put("batchQn", p.getString("batchQn", "auto"))
        put("filePattern", p.getString("filePattern", ""))
        put("filePatternMultiPage", p.getString("filePatternMultiPage", "{pageTitle} P{pageNumber}"))
        put("filePatternCollection", p.getString("filePatternCollection", "{collectionIndex}. {pageTitle}"))
        put("filePatternCollectionMultiPage", p.getString("filePatternCollectionMultiPage", "{collectionIndex}. {videoTitle} P{pageNumber}"))
        put("clearOnExit", p.getString("clearOnExit", "false"))
        put("debug_server", p.getString("debug_server", "false"))
        put("check_update", p.getString("check_update", "true"))
    }
}.getOrNull()

internal fun bridgeSetSetting(key: String, value: String) = runCatching {
    appPrefs.edit().putString(key, value).commit()
    when (key) {
        "threads" -> value.toIntOrNull()?.let { com.bbdown.app.TaskManager.threads = it }
        else -> Unit
    }
}

/** 秒 → hh:mm:ss，与 WebView 版 fmtDuration 一致（无法解析返回空串） */
internal fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

internal fun bridgeCookie(): String = Http.cookie

// ==================== 登录 ====================

internal fun bridgeCheckLogin(): LoginSnapshot = runCatching {
    val p = appPrefs
    val webOk = (p.getString("cookie", "") ?: "").contains("SESSDATA=")
    val tvOk = !(p.getString("tv_access_token", "") ?: "").isEmpty()
    val uname = p.getString("web_uname", "")?.ifEmpty { p.getString("tv_uname", "") ?: "" } ?: ""
    val mid = p.getString("web_mid", "")?.ifEmpty { p.getString("tv_mid", "") ?: "" } ?: ""
    val face = p.getString("web_face", "")?.ifEmpty { p.getString("tv_face", "") ?: "" } ?: ""
    LoginSnapshot(webOk, tvOk, uname, mid, face)
}.getOrDefault(LoginSnapshot(false, false, "", "", ""))

internal fun bridgeLogout(all: Boolean) = runCatching {
    val edit = appPrefs.edit()
    if (all) {
        edit.remove("cookie").remove("tv_access_token")
            .remove("web_mid").remove("web_uname").remove("web_face").remove("web_isVip")
            .remove("tv_mid").remove("tv_uname").remove("tv_face").remove("tv_isVip")
    } else {
        edit.remove("cookie").remove("web_mid").remove("web_uname").remove("web_face")
    }
    edit.commit()
    Http.cookie = ""
    if (all) Http.tvToken = ""
    com.bbdown.app.TaskManager.setCookie(Http.cookie)
}

/** 保存登录凭证到 prefs（web 存 cookie；tv 存 cookie + access_token） */
internal fun bridgeSaveLogin(cookie: String, accessToken: String, mode: String) = runCatching {
    val p = appPrefs
    val e = p.edit()
    if (mode == "web") {
        e.putString("cookie", cookie)
        Http.cookie = cookie
        com.bbdown.app.TaskManager.setCookie(cookie)
        // 拉一次 nav 存用户名/UID/头像
        runCatching {
            val nav = org.json.JSONObject(Http.get("https://api.bilibili.com/x/web-interface/nav"))
            val d = nav.getJSONObject("data")
            e.putString("web_mid", d.optString("mid"))
            e.putString("web_uname", d.optString("uname"))
            e.putString("web_face", d.optString("face"))
            e.putBoolean("web_isVip", d.optInt("vipStatus") == 1)
        }
    } else {
        e.putString("cookie", cookie)
        if (accessToken.isNotEmpty()) e.putString("tv_access_token", accessToken)
        Http.cookie = cookie
        if (accessToken.isNotEmpty()) Http.tvToken = accessToken
        com.bbdown.app.TaskManager.setCookie(cookie)
        runCatching {
            val nav = org.json.JSONObject(Http.get("https://api.bilibili.com/x/web-interface/nav"))
            val d = nav.getJSONObject("data")
            e.putString("tv_mid", d.optString("mid"))
            e.putString("tv_uname", d.optString("uname"))
            e.putString("tv_face", d.optString("face"))
            e.putBoolean("tv_isVip", d.optInt("vipStatus") == 1)
        }
    }
    e.commit()
}

// ==================== 解析 ====================

/** 解析一条链接 → 完整视频信息。任何失败都抛异常（不再静默返回空） */
internal fun bridgeResolveVideo(url: String): ResolvedVideo {
    val parsed = BilibiliApi.parseUrl(url)
    val info = BilibiliApi.getVideoInfo(parsed)
    return ResolvedVideo(url = url, info = info)
}

internal fun bridgeGetPlayInfo(resolved: ResolvedVideo): com.bbdown.app.core.PlayInfo {
    val info = resolved.info
    val page = info.pages.firstOrNull()
        ?: throw IllegalStateException("该视频没有可下载的分P")
    return BilibiliApi.getPlayInfo(
        page.aid, page.cid, page.epid,
        info.title.isNotEmpty() && page.epid.isNotEmpty(),
        isCheese = info.isCheese,
    )
}

// ==================== 列表 API ====================

internal fun bridgeSearchUpper(keyword: String): List<UpperItem> =
    BilibiliApi.searchUpper(keyword).map {
        UpperItem(
            mid = it.mid, uname = it.uname, face = it.face, sign = it.sign,
            fans = it.fans, videoCount = it.videoCount,
            officialType = it.officialType, officialDesc = it.officialDesc,
            vipType = it.vipType, vipStatus = it.vipStatus,
        )
    }

internal fun bridgeSearchVideo(keyword: String): List<VideoItem> =
    BilibiliApi.searchVideos(keyword).map {
        VideoItem(
            bvid = it.bvid, title = it.title, pic = it.pic,
            ownerName = it.author, ownerFace = it.ownerFace, ownerMid = it.mid,
            duration = it.duration, play = it.play, danmaku = it.danmaku,
            pubdate = it.pubdate, desc = it.desc,
        )
    }

internal fun bridgeGetUpperVideos(mid: String, page: Int, pageSize: Int = 30): Pair<List<VideoItem>, Int> {
    val (list, total) = BilibiliApi.getUpperVideos(mid, page, pageSize)
    return list.map {
        VideoItem(
            bvid = it.bvid, title = it.title, pic = it.pic,
            ownerMid = mid,
            duration = it.duration, play = it.play, danmaku = it.danmaku,
            created = it.created, desc = it.desc,
        )
    } to total
}

internal fun bridgeGetFollowings(mid: String, page: Int, order: String, tagId: Int): Pair<List<UpperItem>, Int> {
    // 页大小必须恒定 50：B站偏移是 pn*ps，首页 20/后续 50 会漏掉第 21-50 条
    val (list, total) = BilibiliApi.getFollowings(mid, page, 50, order, tagId)
    return list.map {
        UpperItem(
            mid = it.mid, uname = it.uname, face = it.face, sign = it.sign,
            fans = it.fans, videoCount = it.videoCount,
            officialType = it.officialType, officialDesc = it.officialDesc,
            vipType = it.vipType, vipStatus = it.vipStatus,
            special = it.special,
        )
    } to total
}

internal fun bridgeGetFollowTags(): List<Pair<Int, String>> = runCatching {
    val tags = BilibiliApi.getFollowTags()
    tags.map { it.tagid to it.name }
}.getOrDefault(emptyList())

internal fun bridgeGetFavFolders(mid: String): List<FavFolderItem> =
    BilibiliApi.getFavFolders(mid).map {
        FavFolderItem(id = it.id, title = it.title, mediaCount = it.mediaCount, cover = it.cover)
    }

internal fun bridgeGetFavList(mediaId: String, page: Int): Pair<List<VideoItem>, Int> {
    val (list, total) = BilibiliApi.getFavList(mediaId, page, 20)
    return list.map {
        VideoItem(
            bvid = it.bvid, title = it.title, pic = it.pic,
            ownerName = it.upper, ownerFace = it.ownerFace, ownerMid = it.ownerMid,
            duration = formatDuration(it.duration), play = it.play, danmaku = it.danmaku,
            pubdate = it.pubdate, favTime = it.favTime,
        )
    } to total
}

// ==================== 诊断 ====================

internal fun bridgeGetDebugLogs(): String = Logger.getAll()

internal fun bridgeClearDebugLogs() { Logger.clear() }

internal fun bridgeGetCrashLogs(): List<Triple<String, Long, Long>> = runCatching {
    val dir = java.io.File(BBDownApp.instance.getExternalFilesDir(null), "logs")
    dir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
        ?.sortedByDescending { it.lastModified() }
        ?.map { Triple(it.name, it.length(), it.lastModified()) }
        ?: emptyList()
}.getOrDefault(emptyList())

internal fun bridgeClearCrashLogs() {
    runCatching {
        val dir = java.io.File(BBDownApp.instance.getExternalFilesDir(null), "logs")
        dir.listFiles { f -> f.name.startsWith("crash_") }?.forEach { it.delete() }
    }
}

internal fun bridgeDeleteCrashLog(name: String) {
    runCatching {
        val dir = java.io.File(BBDownApp.instance.getExternalFilesDir(null), "logs")
        java.io.File(dir, name).takeIf { it.exists() && it.name.startsWith("crash_") }?.delete()
    }
}

// ==================== 补全原桥接的其余能力 ====================

/** 合集/系列检测（对应原 checkCollection）。返回 null 表示不属于任何合集 */
data class CollectionInfo(
    val seasonId: String,
    val title: String,
    val mid: String,
    val type: String,
    val bvidList: List<String>,
)

internal fun bridgeCheckCollection(bvid: String): CollectionInfo? = runCatching {
    val col = BilibiliApi.checkCollectionComprehensive(bvid) ?: return null
    var list = col.bvidList
    if (list.isEmpty() && col.mid.isNotEmpty() && col.seasonId.isNotEmpty()) {
        list = BilibiliApi.getUgcSeasonArchivesWithMeta(col.mid, col.seasonId).map { it.bvid }
    }
    CollectionInfo(col.seasonId, col.title, col.mid, col.type, list)
}.getOrNull()

/** 打开本地文件（调用系统应用） */
internal fun bridgeOpenFile(path: String) = runCatching {
    val ctx = BBDownApp.instance
    val f = java.io.File(path)
    if (!f.exists()) throw java.io.FileNotFoundException(path)
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    ctx.startActivity(
        android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, guessMime(f.name))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

/** 分享本地文件 */
internal fun bridgeShareFile(path: String) = runCatching {
    val ctx = BBDownApp.instance
    val f = java.io.File(path)
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    ctx.startActivity(
        android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = guessMime(f.name)
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            "分享"
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4" -> "video/mp4"
    "m4a" -> "audio/mp4"
    "flac" -> "audio/flac"
    "mp3" -> "audio/mpeg"
    "srt" -> "application/x-subrip"
    "xml" -> "text/xml"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    else -> "*/*"
}

/** API 类型读写（web/tv/app/intl） */
internal fun bridgeGetApiType(): String = Http.apiType

internal fun bridgeSetApiType(type: String) {
    Http.apiType = type
    appPrefs.edit().putString("api_type", type).commit()
}

/** 缓存大小（对应原 getCacheSizes） */
internal fun bridgeGetCacheSizes(): Map<String, Long> = runCatching {
    val ctx = BBDownApp.instance
    mapOf(
        "cache" to (ctx.cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L),
        "logs" to (java.io.File(ctx.getExternalFilesDir(null), "logs").walkTopDown()
            .filter { it.isFile }.sumOf { it.length() }),
    )
}.getOrDefault(emptyMap())

/** 清缓存（对应原 clearCache，type=cache/logs） */
internal fun bridgeClearCache(type: String) = runCatching {
    val ctx = BBDownApp.instance
    when (type) {
        "logs" -> java.io.File(ctx.getExternalFilesDir(null), "logs").listFiles()?.forEach { it.delete() }
        else -> ctx.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
    }
}
