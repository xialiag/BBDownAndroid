package com.bbdown.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bbdown.app.core.AudioTrack
import com.bbdown.app.core.BilibiliApi
import com.bbdown.app.core.DownloadTask
import com.bbdown.app.core.PageInfo
import com.bbdown.app.core.PlayInfo
import com.bbdown.app.core.VideoInfo
import com.bbdown.app.core.VideoTrack
import com.bbdown.app.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 六个主视图，与 WebView 版 currentView 一一对应 */
enum class View { Explorer, AddTask, Search, Account, Settings, Help }

/** UP 主条目（搜索结果 / 关注列表共用） */
data class UpperItem(
    val mid: String = "",
    val uname: String = "",
    val face: String = "",
    val sign: String = "",
    val fans: Int = 0,
    val videoCount: Int = 0,
    val officialType: Int = -1,
    val officialDesc: String = "",
    val vipType: Int = 0,
    val vipStatus: Int = 0,
    val special: Int = 0,
)

/** 视频卡片条目（投稿列表 / 收藏夹 / 搜索结果共用） */
data class VideoItem(
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val ownerName: String = "",
    val ownerFace: String = "",
    val ownerMid: String = "",
    val duration: String = "",
    val play: Int = 0,
    val danmaku: Int = 0,
    val pubdate: Long = 0,
    val created: Long = 0,
    val favTime: Long = 0,
    val desc: String = "",
)

/** 收藏夹 */
data class FavFolderItem(val id: String = "", val title: String = "", val mediaCount: Int = 0, val cover: String = "")

/**
 * 分页列表的通用状态：成功/加载中/失败（含回滚基准）三态。
 * 与 WebView 版同一契约：请求失败保留上一页数据，只有首页失败才显示错误态。
 */
data class PagedState<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val totalPages: Int get() = if (total <= 0) 1 else ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
    val hasMore: Boolean get() = items.size < total
    companion object { const val pageSize = 30 }
}

/**
 * 全局 UI 状态容器。所有耗时操作走 viewModelScope + IO 线程，
 * 结果回 Main 线程写入 Compose State，供 Composable 直接观察。
 *
 * 设计要点（沿用 WebView 版已验证的语义）：
 * - 分页失败回滚页码与列表，避免"页码已跳但数据没到"的永久空洞
 * - 请求令牌：连点时只让最后一次响应落地
 * - 非业务异常一律以错误态呈现，绝不伪装成"空结果"
 */
class AppViewModel : ViewModel() {

    // ==================== 全局 ====================
    var currentView by mutableStateOf(View.Explorer)
        private set
    var darkTheme by mutableStateOf(true)
        private set
    var statusMessage by mutableStateOf<String?>(null)   // snackbar 文案
    var statusIsError by mutableStateOf(false)

    /** 任务快照：轮询 TaskManager，供列表/详情观察 */
    var tasks by mutableStateOf<List<DownloadTask>>(emptyList())
        private set
    var selectedTaskId by mutableStateOf<String?>(null)
    var manageMode by mutableStateOf(false)
    /** 任务筛选：all / running / done / failed（原版 _taskFilter） */
    var taskFilter by mutableStateOf("all")
    /** 管理模式下选中的任务 id */
    val taskSelected = mutableStateListOf<String>()

    /** 按筛选条件过滤任务 */
    fun filteredTasks(): List<DownloadTask> = when (taskFilter) {
        "running" -> tasks.filter { it.isRunning || it.status == DownloadTask.STATUS_PENDING }
        "done" -> tasks.filter { it.status == DownloadTask.STATUS_DONE }
        "failed" -> tasks.filter { it.status == DownloadTask.STATUS_FAILED || it.status == DownloadTask.STATUS_CANCELED }
        else -> tasks
    }

    fun changeTaskFilter(f: String) { taskFilter = f }

    fun toggleTaskSelect(id: String) {
        if (taskSelected.contains(id)) taskSelected.remove(id) else taskSelected.add(id)
        if (taskSelected.isEmpty()) manageMode = false
    }

    fun toggleSelectAllTasks() {
        val list = filteredTasks()
        if (taskSelected.size == list.size) taskSelected.clear() else {
            taskSelected.clear(); taskSelected.addAll(list.map { it.taskId })
        }
    }

    fun pauseSelectedTasks() { taskSelected.toList().forEach { TaskManager.pause(it) } }

    fun resumeOrRetrySelectedTasks() {
        taskSelected.toList().forEach { id ->
            TaskManager.get(id)?.let {
                if (it.status == DownloadTask.STATUS_PAUSED) TaskManager.resumePausedTask(it)
                else TaskManager.resumeTask(it)
            }
        }
    }

    fun deleteSelectedTasks() {
        taskSelected.toList().forEach { TaskManager.remove(it) }
        taskSelected.clear(); manageMode = false
        toast("已删除选中任务")
    }

    /** 登录态 */
    var webLogin by mutableStateOf(false)
        private set
    var tvLogin by mutableStateOf(false)
        private set
    var userName by mutableStateOf("")
        private set
    var userId by mutableStateOf("")
        private set
    var userFace by mutableStateOf("")
        private set

    // ==================== 设置 ====================
    val settings = mutableStateMapOf<String, String>()

    // ==================== 新建下载 ====================
    var addTaskInput by mutableStateOf("")
    var parsedUrl by mutableStateOf<String?>(null)
    var videoInfo by mutableStateOf<VideoInfo?>(null)
    var playInfo by mutableStateOf<PlayInfo?>(null)
    var selectedPages = mutableStateListOf<Int>()
    var selectedVideoId by mutableStateOf("")
    var selectedAudioId by mutableStateOf("")
    var addTaskLoading by mutableStateOf(false)
    var addTaskError by mutableStateOf<String?>(null)

    /** 批量解析结果 */
    var batchLoading by mutableStateOf(false)
    var batchProgress by mutableStateOf(0)
    var batchResults by mutableStateOf<List<VideoItem>>(emptyList())
    var batchErrors by mutableStateOf<List<String>>(emptyList())

    // ==================== 搜索 ====================
    var searchMode by mutableStateOf(0)   // 0=UP主 1=视频
    var searchKeyword by mutableStateOf("")
    var searchLoading by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)
    var upperResults by mutableStateOf<List<UpperItem>>(emptyList())
    var videoResults by mutableStateOf<List<VideoItem>>(emptyList())

    /** 当前浏览的 UP 主投稿列表（搜索结果点进去） */
    var currentUpper by mutableStateOf<UpperItem?>(null)
    var upperVideos: PagedState<VideoItem> by mutableStateOf(PagedState())
        private set
    private var upperReqSeq = 0

    // ==================== 账号 ====================
    var followings: PagedState<UpperItem> by mutableStateOf(PagedState())
        private set
    private var followReqSeq = 0
    var followTags by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    var currentFollowTag by mutableStateOf(0)
    var followOrder by mutableStateOf("attention")

    var favFolders by mutableStateOf<List<FavFolderItem>>(emptyList())
        private set
    var favFoldersLoading by mutableStateOf(false)
    var favFoldersError by mutableStateOf<String?>(null)
    var currentFavFolder by mutableStateOf<FavFolderItem?>(null)
    var favList: PagedState<VideoItem> by mutableStateOf(PagedState())
        private set
    private var favReqSeq = 0

    // ==================== 调试/诊断 ====================
    var debugLogs by mutableStateOf("")
    var crashLogs by mutableStateOf<List<Triple<String, Long, Long>>>(emptyList())

    private var pollJob: Job? = null

    fun attach(context: Context) {
        refreshLogin()
        loadAllSettings()
        startPolling()
        // 权限状态由 Activity 提供（写入探测判定），此处刷新一次
        storageGranted = (context as? com.bbdown.app.MainActivity)?.hasStorageAccess() ?: false
    }

    fun refreshStorageGranted(activity: com.bbdown.app.MainActivity) {
        storageGranted = activity.hasStorageAccess()
    }

    // ==================== 导航 ====================

    fun navigate(view: View) {
        if (currentView == view) return
        currentView = view
        // 离开搜索页时清掉 UP 投放态，避免下次进搜索还停在旧的投稿列表
        if (view != View.Search) currentUpper = null
    }

    /**
     * 返回键：按层级逐级退（详情→列表、UP投稿→搜索、收藏夹→账号、非根视图→任务列表）。
     * 返回 true 表示已消费，false 表示已在根页面（由 Activity 处理"再按一次退出"）。
     */
    fun handleBack(): Boolean {
        selectedTaskId?.let { selectedTaskId = null; return true }
        if (manageMode) { manageMode = false; return true }
        if (currentFavFolder != null) { backFromFavFolder(); return true }
        if (currentUpper != null) { backFromUpper(); return true }
        if (currentView != View.Explorer) { navigate(View.Explorer); return true }
        return false
    }

    fun toast(msg: String, isError: Boolean = false) {
        statusMessage = msg
        statusIsError = isError
    }

    fun consumeToast() { statusMessage = null }

    // ==================== 任务轮询 ====================

    /** 200ms 轮询任务列表：进度条要跟手，且 TaskManager.all 只是内存读，成本低 */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                tasks = TaskManager.all
                delay(300)
            }
        }
    }

    fun toggleTheme() {
        darkTheme = !darkTheme
        saveSetting("theme", if (darkTheme) "dark" else "light")
    }

    // ==================== 设置 ====================

    private fun loadAllSettings() = viewModelScope.launch(Dispatchers.IO) {
        // 设置项由 Bridge 的 getAllSettings 组装，这里直接复用它保证键集合一致
        val json = bridgeGetAllSettings()
        if (json != null) {
            for (k in json.keys()) settings[k] = json.optString(k, "")
            darkTheme = settings["theme"] != "light"
        }
    }

    /** 请求公共存储写入权（跳系统授权页），返回后由 Activity 重建时重新判定 */
    var needsStorageGrant by mutableStateOf(false)

    /** 是否已获得「所有文件访问」权限（写入探测判定，见 MainActivity.resolveOutputDir） */
    var storageGranted by mutableStateOf(false)

    fun requestStorageAccess() {
        needsStorageGrant = true
    }

    fun consumeStorageRequest() { needsStorageGrant = false }

    fun saveSetting(key: String, value: String) {
        settings[key] = value
        if (key == "theme") darkTheme = value != "light"
        viewModelScope.launch(Dispatchers.IO) { bridgeSetSetting(key, value) }
    }

    // ==================== 登录 ====================

    fun refreshLogin() = viewModelScope.launch {
        val info = withContext(Dispatchers.IO) { bridgeCheckLogin() } ?: return@launch
        webLogin = info.webLogin
        tvLogin = info.tvLogin
        userName = info.uname
        userId = info.mid
        userFace = info.face
    }

    fun logout(all: Boolean = true) = viewModelScope.launch {
        withContext(Dispatchers.IO) { bridgeLogout(all) }
        refreshLogin()
        toast("已退出登录")
    }

    // ==================== 新建下载 ====================

    fun updateAddTaskInput(v: String) { addTaskInput = v }

    fun clearAddTaskState() {
        parsedUrl = null; videoInfo = null; playInfo = null
        selectedPages.clear(); selectedVideoId = ""; selectedAudioId = ""
        addTaskError = null; batchResults = emptyList(); batchErrors = emptyList()
    }

    /** 解析输入：单条走 parseUrl+getVideoInfo+getPlayInfo，多条走批量解析 */
    fun parseAndLoad(raw: String) = viewModelScope.launch {
        val input = raw.trim()
        if (input.isEmpty()) { toast("请输入视频链接或 BV 号", true); return@launch }
        val parts = input.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size > 1) { parseBatch(parts); return@launch }

        addTaskLoading = true
        addTaskError = null
        videoInfo = null; playInfo = null; parsedUrl = null
        try {
            val result = withContext(Dispatchers.IO) { bridgeResolveVideo(parts[0]) }
            if (result == null) {
                addTaskError = "解析失败：无法识别该链接"
                toast("解析失败", true)
            } else {
                parsedUrl = result.url
                videoInfo = result.info
                selectedPages.clear()
                selectedPages.addAll(result.info.pages.map { it.index })
                playInfo = null
                // 流信息是次要信息，失败不阻断下载（与 WebView 版一致）
                playInfo = runCatching { withContext(Dispatchers.IO) { bridgeGetPlayInfo(result) } }.getOrNull()
                // 原版：解析后自动检测合集
                detectCollection(result.info.bvid.ifEmpty { parts[0] })
                selectedVideoId = playInfo?.videos?.firstOrNull { it.codecs.contains("avc") }?.id
                    ?: playInfo?.videos?.firstOrNull()?.id ?: ""
                selectedAudioId = playInfo?.audios?.firstOrNull()?.id ?: ""
            }
        } catch (e: Exception) {
            addTaskError = e.message ?: e.javaClass.simpleName
            toast(addTaskError!!, true)
        } finally {
            addTaskLoading = false
        }
    }

    private suspend fun parseBatch(parts: List<String>) {
        batchLoading = true
        batchProgress = 0
        batchResults = emptyList(); batchErrors = emptyList()
        try {
            val (ok, bad) = withContext(Dispatchers.IO) {
                val okList = mutableListOf<VideoItem>()
                val errList = mutableListOf<String>()
                // 并发 4 路解析，避免一口气几十个请求被风控
                parts.chunked(4).forEach { chunk ->
                    coroutineScope {
                        chunk.map { url ->
                            async {
                                runCatching { bridgeResolveVideo(url) }.getOrNull()
                                    ?.let { it.url to it.info }
                            }
                        }.awaitAll().forEach { pair ->
                            if (pair == null) errList.add("解析失败")
                            else {
                                okList.add(VideoItem(
                                    bvid = pair.second.bvid.ifEmpty { pair.first },
                                    title = pair.second.title,
                                    pic = pair.second.pic,
                                    ownerName = pair.second.upperName,
                                    ownerMid = pair.second.ownerMid,
                                    duration = "",
                                    pubdate = pair.second.pubTime,
                                ))
                            }
                        }
                    }
                    batchProgress = (okList.size + errList.size)
                }
                okList to errList
            }
            batchResults = ok
            batchErrors = bad
            toast("批量解析完成：成功 ${ok.size} 个${if (bad.isNotEmpty()) "，失败 ${bad.size} 个" else ""}")
        } catch (e: Exception) {
            toast("批量解析失败：${e.message}", true)
        } finally {
            batchLoading = false
        }
    }

    /** 从搜索结果/投稿列表点视频卡片：切到新建下载页并直接解析该 BV 号 */
    fun navigateToAddTaskWith(bvid: String) {
        clearAddTaskState()
        addTaskInput = bvid
        navigate(View.AddTask)
        parseAndLoad(bvid)
    }

    // ==================== 合集下载 ====================

    /** 检测到的合集信息（null=不属于合集） */
    var collectionInfo by mutableStateOf<CollectionInfo?>(null)
    /** 合集内视频列表 */
    var collectionVideos by mutableStateOf<List<VideoItem>>(emptyList())
    /** 合集内选中的 bvid */
    val collectionSelected = mutableStateListOf<String>()
    var collectionLoading by mutableStateOf(false)

    /** 检测当前解析的视频是否属于合集/系列（原版 checkCollection） */
    private suspend fun detectCollection(bvid: String) {
        if (bvid.isEmpty()) return
        collectionLoading = true
        try {
            val col = withContext(Dispatchers.IO) { bridgeCheckCollection(bvid) }
            if (col == null || col.bvidList.isEmpty()) {
                collectionInfo = null
                collectionVideos = emptyList()
            } else {
                collectionInfo = col
                // 逐个取元数据（标题/封面），失败则用 bvid 占位
                val metas = withContext(Dispatchers.IO) {
                    col.bvidList.chunked(4).flatMap { chunk ->
                        coroutineScope {
                            chunk.map { bv ->
                                async {
                                    runCatching { bridgeResolveVideo(bv) }.getOrNull()
                                        ?.let { VideoItem(bvid = it.info.bvid.ifEmpty { bv }, title = it.info.title, pic = it.info.pic, ownerName = it.info.upperName, ownerMid = it.info.ownerMid) }
                                        ?: VideoItem(bvid = bv, title = bv)
                                }
                            }.awaitAll()
                        }
                    }
                }
                collectionVideos = metas
                collectionSelected.clear()
                collectionSelected.addAll(metas.map { it.bvid })
            }
        } catch (_: Exception) {
            collectionInfo = null
        } finally {
            collectionLoading = false
        }
    }

    fun toggleCollectionItem(bvid: String) {
        if (collectionSelected.contains(bvid)) collectionSelected.remove(bvid) else collectionSelected.add(bvid)
    }

    fun selectAllCollection(all: Boolean) {
        collectionSelected.clear()
        if (all) collectionSelected.addAll(collectionVideos.map { it.bvid })
    }

    /** 下载选中的合集视频（原版 downloadSelectedCollection） */
    fun submitCollectionDownload() = viewModelScope.launch {
        val col = collectionInfo ?: return@launch
        val targets = collectionVideos.filter { collectionSelected.contains(it.bvid) }
        if (targets.isEmpty()) { toast("请至少选择一个视频", true); return@launch }
        var ok = 0; var fail = 0
        withContext(Dispatchers.IO) {
            targets.forEachIndexed { idx, item ->
                try {
                    val resolved = bridgeResolveVideo(item.bvid)
                    val info = resolved.info
                    if (info.pages.isEmpty()) { fail++; return@forEachIndexed }
                    TaskManager.add(DownloadTask(
                        taskId = "${System.currentTimeMillis()}_$idx",
                        url = resolved.url, title = info.title, pic = info.pic, pages = info.pages,
                        videoId = "", preferCodec = settings["preferCodec"] ?: "avc",
                        preferAudio = settings["preferAudio"] ?: "m4a", cookie = bridgeCookie(),
                        downloadMode = settings["downloadMode"] ?: "all",
                        downloadDanmaku = settings["downloadDanmaku"] == "true",
                        skipMux = settings["skipMux"] == "true",
                        skipSubtitle = settings["skipSubtitle"] == "true",
                        skipCover = settings["skipCover"] == "true",
                        skipAi = settings["skipAi"] != "false",
                        videoAscending = settings["videoAscending"] == "true",
                        audioAscending = settings["audioAscending"] == "true",
                        filePattern = settings["filePatternCollection"] ?: "",
                        forceHttp = settings["forceHttp"] == "true",
                        isCheese = info.isCheese, upperName = info.upperName, desc = info.desc,
                        pubTime = info.pubTime, bvid = info.bvid, ownerMid = info.ownerMid,
                        collectionTitle = col.title, collectionIndex = idx + 1,
                    ))
                    ok++
                } catch (_: Exception) { fail++ }
            }
        }
        toast("已加入 $ok 个合集任务${if (fail > 0) "，$fail 个失败" else ""}")
        collectionInfo = null; collectionVideos = emptyList(); collectionSelected.clear()
        clearAddTaskState(); addTaskInput = ""
        navigate(View.Explorer)
    }

    /** 批量下载：为每个解析成功的视频创建任务（原版 doBatchDownload） */
    fun submitBatchDownload() = viewModelScope.launch {
        val items = batchResults
        if (items.isEmpty()) { toast("没有可下载的视频", true); return@launch }
        var ok = 0
        var fail = 0
        withContext(Dispatchers.IO) {
            for (item in items) {
                try {
                    val resolved = bridgeResolveVideo(item.bvid.ifEmpty { item.title })
                    val info = resolved.info
                    if (info.pages.isEmpty()) { fail++; continue }
                    val task = DownloadTask(
                        taskId = "${System.currentTimeMillis()}_$ok",
                        url = resolved.url,
                        title = info.title,
                        pic = info.pic,
                        pages = info.pages,
                        videoId = "",
                        preferCodec = settings["preferCodec"] ?: "avc",
                        preferAudio = settings["preferAudio"] ?: "m4a",
                        cookie = bridgeCookie(),
                        downloadMode = settings["downloadMode"] ?: "all",
                        downloadDanmaku = settings["downloadDanmaku"] == "true",
                        skipMux = settings["skipMux"] == "true",
                        skipSubtitle = settings["skipSubtitle"] == "true",
                        skipCover = settings["skipCover"] == "true",
                        skipAi = settings["skipAi"] != "false",
                        videoAscending = settings["videoAscending"] == "true",
                        audioAscending = settings["audioAscending"] == "true",
                        filePattern = resolveFilePattern(info.pages.size > 1),
                        forceHttp = settings["forceHttp"] == "true",
                        isCheese = info.isCheese,
                        upperName = info.upperName,
                        desc = info.desc,
                        pubTime = info.pubTime,
                        bvid = info.bvid,
                        ownerMid = info.ownerMid,
                    )
                    TaskManager.add(task)
                    ok++
                } catch (_: Exception) { fail++ }
            }
        }
        toast("已加入 $ok 个任务${if (fail > 0) "，$fail 个失败" else ""}")
        batchResults = emptyList()
        batchErrors = emptyList()
        addTaskInput = ""
        navigate(View.Explorer)
    }

    fun togglePage(index: Int) {
        if (selectedPages.contains(index)) selectedPages.remove(index) else selectedPages.add(index)
    }

    fun selectAllPages(all: Boolean, pages: List<Int>) {
        selectedPages.clear()
        if (all) selectedPages.addAll(pages)
    }

    fun selectVideo(id: String) { selectedVideoId = id }
    fun selectAudio(id: String) { selectedAudioId = id }

    /** 提交下载任务 */
    fun submitDownload() = viewModelScope.launch {
        val info = videoInfo ?: run { toast("请先解析视频", true); return@launch }
        val url = parsedUrl ?: info.bvid
        val pages = info.pages.filter { selectedPages.contains(it.index) }
        if (pages.isEmpty()) { toast("请至少选择一个分P", true); return@launch }
        try {
            withContext(Dispatchers.IO) {
                val task = DownloadTask(
                    taskId = System.currentTimeMillis().toString(),
                    url = url,
                    title = info.title,
                    pic = info.pic,
                    pages = pages,
                    videoId = selectedVideoId,
                    preferCodec = settings["preferCodec"] ?: "avc",
                    preferAudio = selectedAudioId.ifEmpty { settings["preferAudio"] ?: "m4a" },
                    cookie = bridgeCookie(),
                    downloadMode = settings["downloadMode"] ?: "all",
                    downloadDanmaku = settings["downloadDanmaku"] == "true",
                    skipMux = settings["skipMux"] == "true",
                    skipSubtitle = settings["skipSubtitle"] == "true",
                    skipCover = settings["skipCover"] == "true",
                    skipAi = settings["skipAi"] != "false",
                    videoAscending = settings["videoAscending"] == "true",
                    audioAscending = settings["audioAscending"] == "true",
                    filePattern = resolveFilePattern(pages.size > 1),
                    forceHttp = settings["forceHttp"] == "true",
                    isCheese = info.isCheese,
                    upperName = info.upperName,
                    desc = info.desc,
                    pubTime = info.pubTime,
                    bvid = info.bvid,
                    ownerMid = info.ownerMid,
                )
                TaskManager.add(task)
            }
            toast("已加入下载队列")
            clearAddTaskState()
            addTaskInput = ""
            navigate(View.Explorer)
        } catch (e: Exception) {
            toast("添加任务失败：${e.message}", true)
        }
    }

    private fun resolveFilePattern(multiPage: Boolean): String {
        val key = if (multiPage) "filePatternMultiPage" else "filePattern"
        return settings[key] ?: ""
    }

    // ==================== 任务操作 ====================

    fun pauseTask(id: String) { TaskManager.pause(id); toast("已暂停") }
    fun resumeTask(id: String) { TaskManager.get(id)?.let { TaskManager.resumePausedTask(it) } }
    fun cancelTask(id: String) { TaskManager.cancel(id); toast("已取消") }
    fun retryTask(id: String) { TaskManager.get(id)?.let { TaskManager.resumeTask(it) } }
    fun removeTask(id: String) {
        TaskManager.remove(id)
        if (selectedTaskId == id) selectedTaskId = null
    }
    fun clearFinished() { TaskManager.clearFinished(); toast("已清理已完成任务") }

    // ==================== 搜索 ====================

    fun changeSearchMode(mode: Int) {
        if (searchMode == mode) return
        searchMode = mode
        upperResults = emptyList(); videoResults = emptyList(); searchError = null
    }

    fun search(keyword: String) = viewModelScope.launch {
        val kw = keyword.trim()
        if (kw.isEmpty()) { toast("请输入关键词", true); return@launch }
        searchKeyword = kw
        searchLoading = true
        searchError = null
        upperResults = emptyList(); videoResults = emptyList()
        try {
            if (searchMode == 0) {
                val list = withContext(Dispatchers.IO) { bridgeSearchUpper(kw) }
                upperResults = list
                if (list.isEmpty()) searchError = "未找到相关UP主"
            } else {
                val list = withContext(Dispatchers.IO) { bridgeSearchVideo(kw) }
                videoResults = list
                if (list.isEmpty()) searchError = "未找到相关视频"
            }
        } catch (e: Exception) {
            searchError = e.message ?: "搜索失败"
        } finally {
            searchLoading = false
        }
    }

    /** 打开 UP 主投稿列表 */
    fun openUpper(upper: UpperItem) = viewModelScope.launch {
        currentUpper = upper
        upperReqSeq++
        upperVideos = PagedState(loading = true)
        loadUpperVideos(1)
    }

    fun backFromUpper() {
        currentUpper = null
        upperVideos = PagedState()
    }

    fun loadUpperVideos(page: Int) = viewModelScope.launch {
        val upper = currentUpper ?: return@launch
        val token = ++upperReqSeq
        val prev = upperVideos
        upperVideos = prev.copy(loading = true, error = null)
        try {
            val (items, total) = withContext(Dispatchers.IO) { bridgeGetUpperVideos(upper.mid, page) }
            if (token != upperReqSeq) return@launch
            upperVideos = PagedState(items = items, total = total, page = page)
        } catch (e: Exception) {
            if (token != upperReqSeq) return@launch
            // 失败回滚：保留上一页，只有首页失败才显示错误态
            upperVideos = if (prev.items.isEmpty()) {
                prev.copy(loading = false, error = e.message ?: "加载失败", page = 1)
            } else {
                prev.copy(loading = false)
            }.also { if (prev.items.isNotEmpty()) toast("加载失败：${e.message}", true) }
        }
    }

    // ==================== 账号：登录流程 ====================

    /** 登录页视图：main(未登录/已登录主视图) / auth(扫码) / manage(授权管理) */
    var loginView by mutableStateOf("main")
    /** 当前扫码方式：web / tv */
    var loginMode by mutableStateOf("web")
    /** 二维码 base64 PNG（已含 data:image/png;base64, 前缀） */
    var qrImage by mutableStateOf("")
    /** 二维码状态文字 */
    var qrStatus by mutableStateOf("等待生成二维码…")
    var qrLoading by mutableStateOf(false)
    private var qrPollJob: Job? = null
    private var currentQrKey = ""

    /** 生成当前方式的二维码并开始轮询 */
    fun startQrLogin(mode: String) {
        loginMode = mode
        qrImage = ""
        qrStatus = "生成二维码中…"
        qrLoading = true
        qrPollJob?.cancel()
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    if (mode == "web") BilibiliApi.getQrCode() else BilibiliApi.getTvQrCode()
                }
                qrImage = if (info.image.startsWith("data:")) info.image else "data:image/png;base64,${info.image}"
                currentQrKey = if (mode == "web") info.qrcodeKey else info.url
                qrStatus = "等待扫码…"
                qrLoading = false
                pollQr(mode, currentQrKey)
            } catch (e: Exception) {
                qrStatus = "二维码生成失败：${e.message}"
                qrLoading = false
            }
        }
    }

    private fun pollQr(mode: String, key: String) {
        qrPollJob?.cancel()
        qrPollJob = viewModelScope.launch {
            var attempts = 0
            while (isActive && attempts < 120) {
                delay(1500)
                attempts++
                val r = runCatching {
                    withContext(Dispatchers.IO) {
                        if (mode == "web") BilibiliApi.pollQrLogin(key) else BilibiliApi.pollTvLogin(key)
                    }
                }.getOrNull() ?: continue
                when (r.code) {
                    0 -> {
                        qrStatus = "登录成功"
                        saveLoginResult(r, mode)
                        refreshLogin()
                        loginView = "main"
                        toast("登录成功")
                        return@launch
                    }
                    86038 -> { qrStatus = "二维码已过期，请刷新"; return@launch }
                    86090 -> qrStatus = "已扫码，请在手机上确认"
                    86101 -> qrStatus = "等待扫码…"
                }
            }
        }
    }

    private fun saveLoginResult(r: BilibiliApi.LoginResult, mode: String) {
        // 判据必须含 SESSDATA，否则 ticket 回退会写成"幻觉登录"
        if (mode == "web" && !r.cookie.contains("SESSDATA=")) {
            qrStatus = "登录凭证获取失败，请重试"
            return
        }
        bridgeSaveLogin(r.cookie, r.accessToken, mode)
    }

    fun cancelQrLogin() {
        qrPollJob?.cancel()
        qrImage = ""
        qrStatus = "等待生成二维码…"
    }

    fun switchLoginMode() {
        loginView = "main"
        loginMode = if (loginMode == "web") "tv" else "web"
        cancelQrLogin()
    }

    fun changeLoginView(v: String) {
        loginView = v
        if (v != "auth") cancelQrLogin()
    }

    // ==================== UP投稿 / 收藏夹 批量选择 ====================

    /** UP投稿列表的选择模式与选中集合（原版 _upperSelMode / _upperSelected） */
    var upperSelectMode by mutableStateOf(false)
    val upperSelected = mutableStateListOf<String>()

    fun toggleUpperSelectMode() {
        upperSelectMode = !upperSelectMode
        if (!upperSelectMode) upperSelected.clear()
    }

    fun toggleUpperItem(bvid: String) {
        if (upperSelected.contains(bvid)) upperSelected.remove(bvid) else upperSelected.add(bvid)
    }

    fun selectAllUpper(all: Boolean) {
        upperSelected.clear()
        if (all) upperSelected.addAll(upperVideos.items.map { it.bvid })
    }

    /** 下载选中的 UP 投稿（原版 downloadSelectedUpperVideos） */
    fun submitUpperDownload() = viewModelScope.launch {
        if (upperSelected.isEmpty()) { toast("请先选择视频", true); return@launch }
        val targets = upperSelected.toList()
        withContext(Dispatchers.IO) { targets.forEach { bv -> runCatching { addTaskFromBvid(bv) } } }
        toast("已加入 ${targets.size} 个任务")
        upperSelectMode = false
        upperSelected.clear()
        navigate(View.Explorer)
    }

    /** 收藏夹全部下载（原版 downloadAllFav） */
    fun downloadAllFav() = viewModelScope.launch {
        val folder = currentFavFolder ?: return@launch
        val total = favList.total
        if (total <= 0) { toast("收藏夹为空", true); return@launch }
        toast("开始下载收藏夹全部 $total 个视频…")
        var ok = 0
        withContext(Dispatchers.IO) {
            var page = 1
            while (true) {
                val (items, _) = runCatching { bridgeGetFavList(folder.id, page) }.getOrNull() ?: break
                if (items.isEmpty()) break
                items.forEach { v -> runCatching { addTaskFromBvid(v.bvid); ok++ } }
                if (items.size < 20) break
                page++
                if (page > 50) break   // 安全上限
            }
        }
        toast("收藏夹下载：已加入 $ok 个任务")
        navigate(View.Explorer)
    }

    /** 由 bvid 建任务入队（内部复用） */
    private fun addTaskFromBvid(bvid: String) {
        val resolved = bridgeResolveVideo(bvid)
        val info = resolved.info
        if (info.pages.isEmpty()) return
        TaskManager.add(DownloadTask(
            taskId = "${System.currentTimeMillis()}_${bvid.hashCode()}",
            url = resolved.url, title = info.title, pic = info.pic, pages = info.pages,
            videoId = "", preferCodec = settings["preferCodec"] ?: "avc",
            preferAudio = settings["preferAudio"] ?: "m4a", cookie = bridgeCookie(),
            downloadMode = settings["downloadMode"] ?: "all",
            downloadDanmaku = settings["downloadDanmaku"] == "true",
            skipMux = settings["skipMux"] == "true",
            skipSubtitle = settings["skipSubtitle"] == "true",
            skipCover = settings["skipCover"] == "true",
            skipAi = settings["skipAi"] != "false",
            videoAscending = settings["videoAscending"] == "true",
            audioAscending = settings["audioAscending"] == "true",
            filePattern = resolveFilePattern(info.pages.size > 1),
            forceHttp = settings["forceHttp"] == "true",
            isCheese = info.isCheese, upperName = info.upperName, desc = info.desc,
            pubTime = info.pubTime, bvid = info.bvid, ownerMid = info.ownerMid,
        ))
    }

    // ==================== 账号：关注 ====================

    fun loadFollowings(page: Int = 1, reset: Boolean = page == 1) = viewModelScope.launch {
        val token = ++followReqSeq
        val prev = followings
        followings = if (reset) PagedState(loading = true) else prev.copy(loading = true, error = null)
        try {
            val (items, total) = withContext(Dispatchers.IO) {
                bridgeGetFollowings(userId, page, followOrder, currentFollowTag)
            }
            if (token != followReqSeq) return@launch
            val merged = if (reset) items else prev.items + items
            followings = PagedState(items = merged, total = total, page = page)
        } catch (e: Exception) {
            if (token != followReqSeq) return@launch
            followings = prev.copy(loading = false, error = if (prev.items.isEmpty()) e.message else null)
            toast("加载关注列表失败：${e.message}", true)
        }
    }

    fun changeFollowTag(tagId: Int) { currentFollowTag = tagId; loadFollowings(1, true) }
    fun changeFollowOrder(order: String) { followOrder = order; loadFollowings(1, true) }

    fun loadFollowTags() = viewModelScope.launch {
        followTags = runCatching { withContext(Dispatchers.IO) { bridgeGetFollowTags() } }.getOrDefault(emptyList())
    }

    // ==================== 账号：收藏夹 ====================

    fun loadFavFolders() = viewModelScope.launch {
        favFoldersLoading = true
        favFoldersError = null
        try {
            val list = withContext(Dispatchers.IO) { bridgeGetFavFolders(userId) }
            favFolders = list
        } catch (e: Exception) {
            favFoldersError = e.message ?: "加载失败"
        } finally {
            favFoldersLoading = false
        }
    }

    fun openFavFolder(folder: FavFolderItem) = viewModelScope.launch {
        currentFavFolder = folder
        favReqSeq++
        favList = PagedState(loading = true)
        loadFavList(1)
    }

    fun backFromFavFolder() { currentFavFolder = null; favList = PagedState() }

    fun loadFavList(page: Int) = viewModelScope.launch {
        val folder = currentFavFolder ?: return@launch
        val token = ++favReqSeq
        val prev = favList
        favList = prev.copy(loading = true, error = null)
        try {
            val (items, total) = withContext(Dispatchers.IO) { bridgeGetFavList(folder.id, page) }
            if (token != favReqSeq) return@launch
            favList = PagedState(items = items, total = total, page = page)
        } catch (e: Exception) {
            if (token != favReqSeq) return@launch
            favList = if (prev.items.isEmpty()) {
                prev.copy(loading = false, error = e.message ?: "加载失败", page = 1)
            } else {
                prev.copy(loading = false)
            }.also { if (prev.items.isNotEmpty()) toast("加载失败：${e.message}", true) }
        }
    }

    // ==================== API 类型 / 缓存管理 ====================

    /** 当前 API 类型：web / tv / app / intl（原版 _apiType） */
    var apiType by mutableStateOf("web")

    fun loadApiType() = viewModelScope.launch {
        apiType = runCatching { withContext(Dispatchers.IO) { bridgeGetApiType() } }.getOrDefault("web")
    }

    fun changeApiType(type: String) {
        apiType = type
        viewModelScope.launch(Dispatchers.IO) { runCatching { bridgeSetApiType(type) } }
        toast("API 类型已切换为 ${type.uppercase()}")
    }

    /** 缓存大小（cache / logs 字节数） */
    var cacheSizes by mutableStateOf<Map<String, Long>>(emptyMap())

    fun loadCacheSizes() = viewModelScope.launch {
        cacheSizes = runCatching { withContext(Dispatchers.IO) { bridgeGetCacheSizes() } }.getOrDefault(emptyMap())
    }

    fun clearCache(type: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { bridgeClearCache(type) } }
        loadCacheSizes()
        toast("已清理${if (type == "logs") "日志" else "缓存"}")
    }

    // ==================== 诊断 ====================

    fun loadDebugLogs() = viewModelScope.launch {
        debugLogs = runCatching { withContext(Dispatchers.IO) { bridgeGetDebugLogs() } }.getOrDefault("")
    }

    fun clearDebugLogs() = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { bridgeClearDebugLogs() } }
        debugLogs = ""
    }

    fun clearCrashLogs() = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { bridgeClearCrashLogs() } }
        crashLogs = emptyList()
    }

    fun deleteCrashLog(name: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { bridgeDeleteCrashLog(name) } }
        loadCrashLogs()
    }

    fun loadCrashLogs() = viewModelScope.launch {
        crashLogs = runCatching { withContext(Dispatchers.IO) { bridgeGetCrashLogs() } }.getOrDefault(emptyList())
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }

    // ==================== 桥接原语 ====================
    // 这些方法把原 BBDownBridge 的 @JavascriptInterface 搬成普通方法。
    // 具体实现放在 Bridge.kt（同一包内 extension），保持本文件专注状态管理。
}

/** parseUrl+getVideoInfo 的解析结果 */
data class ResolvedVideo(val url: String, val info: VideoInfo)

/** 登录态快照 */
data class LoginSnapshot(
    val webLogin: Boolean,
    val tvLogin: Boolean,
    val uname: String,
    val mid: String,
    val face: String,
)
