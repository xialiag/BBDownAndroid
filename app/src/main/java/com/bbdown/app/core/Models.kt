package com.bbdown.app.core

/** 视频轨道 */
data class VideoTrack(
    var id: String = "",
    var dfn: String = "",
    var codecs: String = "",
    var bandwidth: Long = 0,
    var res: String = "",
    var fps: String = "",
    var baseUrl: String = "",
    var backupUrls: List<String> = emptyList(),
    var size: Double = 0.0,
    var dur: Int = 0
)

/** 音频轨道 */
data class AudioTrack(
    var id: String = "",
    var codecs: String = "",
    var bandwidth: Long = 0,
    var baseUrl: String = "",
    var backupUrls: List<String> = emptyList(),
    var dur: Int = 0
)

/** 分P */
data class PageInfo(
    var index: Int = 0,
    var aid: String = "",
    var cid: String = "",
    var epid: String = "",
    var title: String = "",
    var duration: Int = 0
)

/** 视频信息 */
data class VideoInfo(
    var title: String = "",
    var desc: String = "",
    var pic: String = "",
    var pubTime: Long = 0,
    var upperName: String = "",   // UP主名称，用于写入元数据
    var ownerMid: String = "",    // UP主mid，用于文件名变量
    var isBangumi: Boolean = false,
    var isCheese: Boolean = false,
    var bvid: String = "",
    var play: Int = 0,
    var danmaku: Int = 0,
    var duration: Int = 0,
    var ownerFace: String = "",
    var officialType: Int = -1,
    var vipType: Int = 0,
    var vipStatus: Int = 0,
    var pages: List<PageInfo> = emptyList()
)

/** 解析结果 */
data class PlayInfo(
    var videos: List<VideoTrack> = emptyList(),
    var audios: List<AudioTrack> = emptyList(),
    var dur: Int = 0
)

/** 字幕信息 */
data class SubtitleInfo(
    var lan: String = "",
    var lanDoc: String = "",
    var subtitleUrl: String = "",
    var ai: Boolean = false
)

/** 下载任务 */
class DownloadTask(
    val taskId: String,
    val url: String,
    val title: String,
    val pic: String,
    val pages: List<PageInfo>,
    val videoId: String,
    val preferCodec: String,
    val preferAudio: String,
    val cookie: String,
    // BBDown 兼容下载选项
    val downloadMode: String = "all", // all|video_only|audio_only|subtitle_only|cover_only|danmaku_only
    val downloadDanmaku: Boolean = false,
    val skipMux: Boolean = false,
    val skipSubtitle: Boolean = false,
    val skipCover: Boolean = false,
    val skipAi: Boolean = true,
    val videoAscending: Boolean = false,
    val audioAscending: Boolean = false,
    val filePattern: String = "",
    val forceHttp: Boolean = false,
    val isCheese: Boolean = false,
    val collectionTitle: String = "",  // 合集/系列名称，非空时下载到以该名称命名的子文件夹
    val collectionIndex: Int = 0,      // 视频在合集中的序号（1-based），用于文件命名 P{N}
    // 元数据（参考原版 BBDown，用于混流时写入 MP4）
    val upperName: String = "",       // UP主名称 → artist
    val desc: String = "",            // 视频描述 → description
    val pubTime: Long = 0,            // 发布时间 → creation_time
    val bvid: String = "",            // BV号（用于文件名变量 <bvid>）
    val ownerMid: String = "",        // UP主mid（用于文件名变量 <ownerMid>）
    @Volatile var status: Int = STATUS_PENDING,
    // 进度字段跨线程读写（分片线程写、UI/通知线程读），加 @Volatile 保证可见性
    @Volatile var progress: Float = 0f,
    @Volatile var downloadedBytes: Long = 0,
    @Volatile var totalBytes: Long = 0,
    @Volatile var speed: Long = 0,
    @Volatile var errorMsg: String = "",
    var outputDir: String = "",
    var outputFiles: List<String> = emptyList(),
    var createTime: Long = System.currentTimeMillis() / 1000,
    var finishTime: Long = 0,
    var seq: Long = 0  // 全局递增序列号，确保批量下载顺序不乱序
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_PARSING = 1
        const val STATUS_DOWNLOADING = 2
        const val STATUS_MUXING = 3
        const val STATUS_DONE = 4
        const val STATUS_FAILED = 5
        const val STATUS_CANCELED = 6
        const val STATUS_PAUSED = 7   // 暂停（可恢复继续下载）
    }
    val isRunning get() = status == STATUS_PARSING || status == STATUS_DOWNLOADING || status == STATUS_MUXING
    val isPaused get() = status == STATUS_PAUSED

    /** 任务去重键：url + 分P的 cid 列表。
     *  用于检测重复添加同一批分P视频，避免任务堆积。 */
    fun dupKey(): String {
        val cids = pages.joinToString(",") { it.cid }
        return "${url}|${cids}"
    }
}
