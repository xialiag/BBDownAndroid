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
