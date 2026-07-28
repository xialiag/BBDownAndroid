package com.bbdown.app.core

import android.os.Debug
import java.io.File

/**
 * 下载引擎，编排单个任务的完整流程。
 * 支持所有 BBDown 兼容的下载模式：
 * - all: 完整下载(视频+音频+混流+字幕+封面+弹幕)
 * - video_only: 仅下载视频流
 * - audio_only: 仅下载音频流
 * - subtitle_only: 仅下载字幕
 * - cover_only: 仅下载封面
 * - danmaku_only: 仅下载弹幕
 */
object DownloadEngine {

    fun execute(task: DownloadTask, outputDir: File, threads: Int = 8) {
        try {
            // 任务开始前检查是否已被暂停或取消（排队中被暂停/删除的情况）
            // 必须在设置 STATUS_PARSING 之前检查，否则会覆盖已设置的暂停/取消状态
            if (task.status == DownloadTask.STATUS_PAUSED) {
                Logger.i("DownloadEngine", "任务已被暂停，跳过执行: ${task.title}")
                return
            }
            if (task.status == DownloadTask.STATUS_CANCELED) {
                Logger.i("DownloadEngine", "任务已被取消，跳过执行: ${task.title}")
                return
            }
            task.status = DownloadTask.STATUS_PARSING
            task.errorMsg = ""
            task.downloadedBytes = 0
            task.totalBytes = 0
            val totalPages = task.pages.size.coerceAtLeast(1)
            val outputs = ArrayList<String>()
            // 合集/系列下载到以合集名命名的子文件夹；单视频直接下载到输出目录，不嵌套同名文件夹
            val workDir = if (task.collectionTitle.isNotEmpty()) {
                File(outputDir, sanitize(task.collectionTitle)).apply { mkdirs() }
            } else {
                outputDir.apply { mkdirs() }
            }

            // 元数据补全（修复5）：合集下载时前端可能未传 upperName/desc/pubTime（字段名不匹配
            // 或 downloadCollection 仅传 url/title/pic/pages），此处通过 getVideoInfo 自动补全，
            // 确保混流/注入时元数据完整。仅当 upperName 为空时才请求，避免额外 API 开销。
            var effectiveUpperName = task.upperName
            var effectiveDesc = task.desc
            var effectivePubTime = task.pubTime
            if (effectiveUpperName.isEmpty() && task.url.isNotEmpty()) {
                try {
                    Logger.i("DownloadEngine", "upperName 为空，通过 getVideoInfo 补全元数据: ${task.url}")
                    val parsed = BilibiliApi.parseUrl(task.url)
                    val info = BilibiliApi.getVideoInfo(parsed)
                    if (info.upperName.isNotEmpty()) effectiveUpperName = info.upperName
                    if (effectiveDesc.isEmpty()) effectiveDesc = info.desc
                    if (effectivePubTime == 0L) effectivePubTime = info.pubTime
                    Logger.i("DownloadEngine", "元数据补全: upperName=${effectiveUpperName}, pubTime=${effectivePubTime}")
                } catch (e: Exception) {
                    Logger.w("DownloadEngine", "元数据补全失败(不影响下载): ${e.message}")
                }
            }

            for ((pageIdx, page) in task.pages.withIndex()) {
                // 检查取消和暂停状态
                if (task.status == DownloadTask.STATUS_CANCELED) return
                if (task.status == DownloadTask.STATUS_PAUSED) {
                    Logger.i("DownloadEngine", "任务在分P循环中被暂停: ${task.title} (page ${pageIdx + 1})")
                    return
                }
                val baseProgress = pageIdx.toFloat() / totalPages
                val pageWeight = 1f / totalPages

                when (task.downloadMode) {
                    "subtitle_only" -> {
                        task.status = DownloadTask.STATUS_DOWNLOADING
                        val subTracks = downloadSubtitles(task, page, workDir, task.skipAi)
                        for (t in subTracks) outputs.add(t.file.absolutePath)
                        task.progress = baseProgress + pageWeight
                    }
                    "cover_only" -> {
                        task.status = DownloadTask.STATUS_DOWNLOADING
                        downloadCover(task, workDir, outputs)
                        task.progress = baseProgress + pageWeight
                    }
                    "danmaku_only" -> {
                        task.status = DownloadTask.STATUS_DOWNLOADING
                        downloadDanmaku(page, workDir, outputs)
                        task.progress = baseProgress + pageWeight
                    }
                    else -> {
                        // all / video_only / audio_only — 需要解析 playurl
                        // cid 为空时自动获取（合集元数据可能不包含 cid）
                        var effectivePage = page
                        if (page.cid.isEmpty() && page.aid.isNotEmpty()) {
                            try {
                                Logger.i("DownloadEngine", "cid 为空，自动获取: aid=${page.aid}, bvid=${task.url}")
                                val parsed = BilibiliApi.parseUrl(task.url)
                                val info = BilibiliApi.getVideoInfo(parsed)
                                if (info.pages.isNotEmpty()) {
                                    val matchedPage = info.pages.find { it.index == page.index } ?: info.pages[0]
                                    effectivePage = PageInfo(
                                        index = page.index,
                                        aid = page.aid,
                                        cid = matchedPage.cid,
                                        epid = page.epid,
                                        title = page.title.ifEmpty { matchedPage.title },
                                        duration = page.duration
                                    )
                                    Logger.i("DownloadEngine", "自动获取 cid 成功: ${matchedPage.cid}")
                                }
                            } catch (e: Exception) {
                                Logger.e("DownloadEngine", "自动获取 cid 失败: ${e.message}")
                            }
                        }
                        val play = BilibiliApi.getPlayInfo(effectivePage.aid, effectivePage.cid, effectivePage.epid,
                            task.title.isNotEmpty() && effectivePage.epid.isNotEmpty(),
                            isCheese = task.isCheese)
                        if (play.videos.isEmpty() && play.audios.isEmpty())
                            throw IllegalStateException("无可用音视频流（可能需要登录 Cookie 或大会员，或被风控限制）")
                        if (play.videos.isEmpty() && task.downloadMode != "audio_only")
                            throw IllegalStateException("无可用视频流（可能需要登录 Cookie 或大会员）")
                        if (play.audios.isEmpty())
                            throw IllegalStateException("无可用音频流（可能需要登录 Cookie 或大会员）")

                        val pageLabel = if (totalPages > 1) ".P${page.index}" else ""
                        // 先选轨，再构建文件名（文件名变量需要轨道信息）
                        val selectedVideo = if (task.downloadMode != "audio_only") {
                            selectVideo(play, task.videoId, task.preferCodec, task.videoAscending)
                                ?: play.videos.firstOrNull()
                        } else null
                        // 所有模式都选择音频：all/video_only 需要混流，audio_only 直接输出
                        val selectedAudio = selectAudio(play, task.preferAudio, task.audioAscending)
                        val baseName = buildFileName(task, page, totalPages > 1, selectedVideo, selectedAudio)

                        // 累计本页实际下载字节数
                        var pageDownloaded = 0L
                        var pageTotal = 0L

                        // 下载视频
                        var vFile: File? = null
                        if (task.downloadMode != "audio_only") {
                            val video = selectedVideo
                            if (video != null) {
                                task.status = DownloadTask.STATUS_DOWNLOADING
                                // 混流模式用临时名（混流后删除）；skipMux 模式用最终名
                                val vExt = if (task.skipMux) ".mp4" else ".vpart"
                                vFile = File(workDir, "${baseName}${pageLabel}${vExt}")
                                if (video.size > 0) pageTotal += video.size.toLong()
                                val vUrl = if (task.forceHttp && effectivePage.epid.isEmpty()) BilibiliApi.forceHttp(video.baseUrl) else video.baseUrl
                                val vDownloader = MultiThreadDownloader(threads, task.cookie) { d, t, s ->
                                    pageDownloaded = (baseProgress * 1_000_000).toLong() + d
                                    if (t > 0) pageTotal = pageTotal.coerceAtLeast(t)
                                    task.downloadedBytes = pageDownloaded
                                    task.totalBytes = (baseProgress * 1_000_000).toLong() + pageTotal
                                    task.speed = s
                                    if (t > 0) task.progress = baseProgress + (d.toFloat() / t) * pageWeight * 0.9f
                                }
                                com.bbdown.app.TaskManager.registerDownloader(task.taskId, vDownloader)
                                vDownloader.download(vUrl, vFile)
                                // 下载完成后用实际文件大小更新
                                pageDownloaded = (baseProgress * 1_000_000).toLong() + vFile.length()
                                task.downloadedBytes = pageDownloaded
                                if (task.status == DownloadTask.STATUS_CANCELED) { vFile.delete(); return }
                                if (task.status == DownloadTask.STATUS_PAUSED) return
                                outputs.add(vFile.absolutePath)
                            }
                        }

                        // 下载音频（所有模式都下载：all/video_only 需要混流，audio_only 直接输出）
                        var aFile: File? = null
                        val audio = selectedAudio
                        if (audio != null) {
                            task.status = DownloadTask.STATUS_DOWNLOADING
                            // 根据编码选择正确扩展名：FLAC → .flac，其他 → .m4a
                            val audioExt = if (audio.codecs == "FLAC") "flac" else "m4a"
                            // audio_only 模式下用干净文件名作为最终输出；混流模式用临时名（混流后删除）
                            val aSuffix = if (task.downloadMode == "audio_only") ".$audioExt" else if (task.skipMux) ".$audioExt" else ".apart"
                            val aTarget = File(workDir, "${baseName}${pageLabel}${aSuffix}")
                            aFile = aTarget
                            val aUrl = if (task.forceHttp && effectivePage.epid.isEmpty()) BilibiliApi.forceHttp(audio.baseUrl) else audio.baseUrl
                            val aPageBase = pageDownloaded
                            val aDownloader = MultiThreadDownloader(threads, task.cookie) { d, t, s ->
                                if (t > 0) pageTotal = pageTotal.coerceAtLeast(t)
                                task.downloadedBytes = aPageBase + d
                                task.totalBytes = (baseProgress * 1_000_000).toLong() + pageTotal
                                task.speed = s
                                if (t > 0) task.progress = baseProgress + pageWeight * 0.9f + (d.toFloat() / t) * pageWeight * 0.05f
                            }
                            com.bbdown.app.TaskManager.registerDownloader(task.taskId, aDownloader)
                            aDownloader.download(aUrl, aTarget)
                            pageDownloaded = aPageBase + aTarget.length()
                            task.downloadedBytes = pageDownloaded
                            if (task.status == DownloadTask.STATUS_CANCELED) { vFile?.delete(); aTarget.delete(); return }
                            if (task.status == DownloadTask.STATUS_PAUSED) return
                            outputs.add(aTarget.absolutePath)
                        }

                        // 混流合并（all 和 video_only 模式且未跳过混流）
                        // video_only 模式也混流视频+音频，与原版 BBDown 一致（输出含音频流的完整视频）
                        if ((task.downloadMode == "all" || task.downloadMode == "video_only") && !task.skipMux && vFile != null) {
                            // 混流前检查暂停和取消状态
                            if (task.status == DownloadTask.STATUS_CANCELED) { vFile.delete(); aFile?.delete(); return }
                            if (task.status == DownloadTask.STATUS_PAUSED) return
                            task.status = DownloadTask.STATUS_MUXING
                            task.progress = baseProgress + pageWeight * 0.95f
                            // 混流输出文件名：有 filePattern 时用自定义名称，否则用默认格式
                            val outName = if (task.filePattern.isNotEmpty()) {
                                buildFileName(task, page, totalPages > 1, selectedVideo, selectedAudio)
                            } else {
                                sanitize(if (totalPages > 1) "${page.index}. ${page.title}" else task.title)
                            }
                            val outFile = File(workDir, "$outName.mp4")
                            // 先下载封面（如需嵌入元数据）— 使用原始URL，保留原始格式（与 DotNet 版一致）
                            var coverFile: File? = null
                            if (!task.skipCover && task.pic.isNotEmpty()) {
                                try {
                                    val bytes = BilibiliApi.downloadCover(task.pic)
                                    coverFile = File(workDir, sanitize(task.title) + ".cover")
                                    coverFile.writeBytes(bytes)
                                } catch (_: Exception) {}
                            }
                            // 混流前下载字幕（all/video_only 模式且未跳过字幕时），嵌入到输出文件
                            // 原版 BBDown: !SkipSubtitle && !DanmakuOnly && !CoverOnly 时均下载字幕
                            var subTracks: List<FFmpegMuxer.SubtitleTrack> = emptyList()
                            if ((task.downloadMode == "all" || task.downloadMode == "video_only") && !task.skipSubtitle) {
                                subTracks = downloadSubtitles(task, effectivePage, workDir, task.skipAi)
                            }
                            // 混流并写入元数据+封面+字幕（参考原版 BBDown 的 MuxAV）
                            checkMemoryBeforeMux()
                            val ok = FFmpegMuxer.muxWithMetadata(
                                vFile, aFile, outFile,
                                title = if (totalPages > 1) page.title else task.title,
                                album = if (totalPages > 1) task.title else "",
                                artist = effectiveUpperName,
                                desc = effectiveDesc,
                                pubTime = effectivePubTime,
                                coverFile = coverFile,
                                subtitles = subTracks
                            )
                            vFile.delete()
                            aFile?.delete()
                            // 混流成功后删除临时字幕文件（与原版 BBDown 一致）
                            if (ok) {
                                for (t in subTracks) {
                                    if (t.file.exists()) t.file.delete()
                                }
                            }
                            if (!ok) throw IllegalStateException("音视频合并失败")
                            // 替换输出列表为最终文件
                            outputs.removeAll { it.endsWith(".vpart") || it.endsWith(".apart") }
                            outputs.add(outFile.absolutePath)
                            // 封面处理：video_only 模式下封面已嵌入，删除临时文件
                            // all 模式下保留封面作为独立输出（与原版 BBDown 一致）
                            if (task.downloadMode == "video_only") {
                                coverFile?.let { cf ->
                                    if (cf.exists()) {
                                        cf.delete()
                                        Logger.i("DownloadEngine", "已清除封面临时文件(已嵌入): ${cf.name}")
                                    }
                                }
                            } else {
                                coverFile?.let { outputs.add(it.absolutePath) }
                            }
                        } else if (task.downloadMode == "audio_only" ||
                                   (task.downloadMode == "all" && task.skipMux) ||
                                   (task.downloadMode == "video_only" && task.skipMux)) {
                            // 非混流模式：对下载的文件单独注入元数据
                            // 仅 audio_only 或 skipMux 时走此分支（video_only+skipMux 时视频和音频分别注入）
                            if (task.status == DownloadTask.STATUS_CANCELED) return
                            if (task.status == DownloadTask.STATUS_PAUSED) return
                            // 下载封面（用于嵌入元数据）— 使用原始URL，保留原始格式（与 DotNet 版一致）
                            var coverFile: File? = null
                            if (!task.skipCover && task.pic.isNotEmpty()) {
                                try {
                                    val bytes = BilibiliApi.downloadCover(task.pic)
                                    coverFile = File(workDir, sanitize(task.title) + ".cover")
                                    coverFile.writeBytes(bytes)
                                } catch (_: Exception) {}
                            }
                            // 下载字幕（all/audio_only/video_only 模式且未跳过字幕时），嵌入到输出文件
                            // 原版 BBDown: !SkipSubtitle && !DanmakuOnly && !CoverOnly 时均下载字幕（含 audio_only）
                            var subTracks: List<FFmpegMuxer.SubtitleTrack> = emptyList()
                            if ((task.downloadMode == "all" || task.downloadMode == "audio_only" || task.downloadMode == "video_only") && !task.skipSubtitle) {
                                subTracks = downloadSubtitles(task, effectivePage, workDir, task.skipAi)
                            }
                            val metaTitle = if (totalPages > 1) page.title else task.title
                            val metaAlbum = if (totalPages > 1) task.title else ""
                            // 对视频文件注入元数据+字幕
                            var videoMetaInjected = false
                            if (vFile != null && vFile.exists() && vFile.name.endsWith(".mp4")) {
                                task.status = DownloadTask.STATUS_MUXING
                                try {
                                    checkMemoryBeforeMux()
                                    videoMetaInjected = FFmpegMuxer.injectMetadataOnly(
                                        vFile, title = metaTitle, album = metaAlbum,
                                        artist = effectiveUpperName, desc = effectiveDesc,
                                        pubTime = effectivePubTime,
                                        coverFile = coverFile,
                                        subtitles = subTracks
                                    )
                                } catch (e: Exception) {
                                    Logger.w("DownloadEngine", "视频元数据注入失败(不影响下载): ${e.message}")
                                }
                            }
                            // 对音频文件注入元数据+字幕（仅 m4a，FLAC 不支持 MP4 box 结构）
                            // FFmpeg 会自动将 fragmented MP4 转为标准 MP4，无需单独 remux
                            var audioMetaInjected = false
                            val aFileNN = aFile
                            if (aFileNN != null && aFileNN.exists() && aFileNN.name.endsWith(".m4a")) {
                                task.status = DownloadTask.STATUS_MUXING
                                try {
                                    checkMemoryBeforeMux()
                                    audioMetaInjected = FFmpegMuxer.injectMetadataOnly(
                                        aFileNN, title = metaTitle, album = metaAlbum,
                                        artist = effectiveUpperName, desc = effectiveDesc,
                                        pubTime = effectivePubTime,
                                        coverFile = coverFile,
                                        subtitles = subTracks
                                    )
                                } catch (e: Exception) {
                                    Logger.w("DownloadEngine", "音频元数据注入失败(不影响下载): ${e.message}")
                                }
                            }
                            // 元数据注入成功后清除封面临时文件和字幕临时文件
                            val metaInjected = videoMetaInjected || audioMetaInjected
                            if (metaInjected) {
                                coverFile?.let { cf ->
                                    if (cf.exists()) {
                                        cf.delete()
                                        Logger.i("DownloadEngine", "已清除封面临时文件(已嵌入): ${cf.name}")
                                    }
                                }
                                // 字幕已嵌入，删除临时 .srt 文件（与原版 BBDown 一致）
                                for (t in subTracks) {
                                    if (t.file.exists()) t.file.delete()
                                }
                            } else if ((task.downloadMode == "all" || task.downloadMode == "video_only") && task.skipMux) {
                                // 元数据注入失败时保留封面和字幕文件作为独立输出（skipMux 模式）
                                coverFile?.let { outputs.add(it.absolutePath) }
                                for (t in subTracks) outputs.add(t.file.absolutePath)
                            } else {
                                // video_only/audio_only 模式下元数据注入失败：删除封面临时文件
                                coverFile?.let { cf ->
                                    if (cf.exists()) {
                                        cf.delete()
                                        Logger.i("DownloadEngine", "已清除封面临时文件(元数据注入失败): ${cf.name}")
                                    }
                                }
                                // 字幕注入失败也删除临时文件
                                for (t in subTracks) {
                                    if (t.file.exists()) t.file.delete()
                                }
                            }
                        }

                        // 字幕已在混流/元数据注入阶段下载并嵌入，无需重复下载

                        // 附加下载：封面（仅在 all 模式且元数据注入失败、跳过混流或无视频文件时单独下载）
                        if (task.downloadMode == "all" &&
                            !task.skipCover && task.pic.isNotEmpty() && task.skipMux && vFile == null) {
                            downloadCover(task, workDir, outputs)
                        }

                        // 附加下载：弹幕
                        if (task.downloadMode == "all" && task.downloadDanmaku) {
                            downloadDanmaku(effectivePage, workDir, outputs)
                        }

                        task.progress = baseProgress + pageWeight
                    }
                }
            }

            task.outputFiles = outputs
            task.progress = 1f
            task.speed = 0
            // 最终校准：确保已完成的任务显示正确的字节数
            if (task.totalBytes > 0) {
                task.downloadedBytes = task.totalBytes
            } else {
                // totalBytes 未知时，从输出文件计算实际大小
                var totalSize = 0L
                for (f in outputs) {
                    try { totalSize += File(f).length() } catch (_: Exception) {}
                }
                task.totalBytes = totalSize
                task.downloadedBytes = totalSize
            }
            task.status = DownloadTask.STATUS_DONE
            task.finishTime = System.currentTimeMillis() / 1000
        } catch (e: InterruptedException) {
            // 暂停或取消都通过中断线程实现
            if (task.status != DownloadTask.STATUS_PAUSED) {
                task.status = DownloadTask.STATUS_CANCELED
            }
        } catch (e: Exception) {
            // 暂停状态下不覆盖为失败
            if (task.status != DownloadTask.STATUS_PAUSED && task.status != DownloadTask.STATUS_CANCELED) {
                Logger.e("DownloadEngine", "任务失败: ${task.title} - ${e.javaClass.simpleName}: ${e.message}", e)
                task.status = DownloadTask.STATUS_FAILED
                task.errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
            }
        }
    }

    // ==================== 附加资源下载 ====================

    private fun downloadSubtitles(task: DownloadTask, page: PageInfo, workDir: File, skipAi: Boolean): List<FFmpegMuxer.SubtitleTrack> {
        val tracks = ArrayList<FFmpegMuxer.SubtitleTrack>()
        try {
            val subs = BilibiliApi.getSubtitles(page.aid, page.cid)
            for (sub in subs) {
                if (skipAi && sub.ai) continue
                if (sub.subtitleUrl.isEmpty()) continue
                val srtContent = BilibiliApi.downloadSubtitleAsSrt(sub.subtitleUrl)
                if (srtContent.isNotEmpty()) {
                    val lang = if (sub.lanDoc.isNotEmpty()) sub.lanDoc else sub.lan
                    val subFile = File(workDir, sanitize(task.title) + ".${lang}.srt")
                    subFile.writeText(srtContent, Charsets.UTF_8)
                    val (isoCode, lanDoc) = BilibiliApi.getSubtitleCode(sub.lan)
                    tracks.add(FFmpegMuxer.SubtitleTrack(
                        file = subFile,
                        lan = sub.lan,
                        isoCode = isoCode,
                        lanDoc = lanDoc
                    ))
                }
            }
        } catch (_: Exception) {}
        return tracks
    }

    private fun downloadCover(task: DownloadTask, workDir: File, outputs: ArrayList<String>) {
        try {
            if (task.pic.isEmpty()) return
            // 使用原始URL，保留原始格式（与 DotNet 版一致）
            val bytes = BilibiliApi.downloadCover(task.pic)
            val coverFile = File(workDir, sanitize(task.title) + ".cover")
            coverFile.writeBytes(bytes)
            outputs.add(coverFile.absolutePath)
        } catch (_: Exception) {}
    }

    private fun downloadDanmaku(page: PageInfo, workDir: File, outputs: ArrayList<String>) {
        try {
            val xml = BilibiliApi.getDanmakuXml(page.cid)
            if (xml.isNotEmpty()) {
                // 转换为 ASS 格式（参考 DotNet BBDown 的 DanmakuUtil）
                val ass = DanmakuUtil.convertXmlToAss(xml)
                if (ass.isNotEmpty()) {
                    val assFile = File(workDir, sanitize("P${page.index}.${page.title}") + ".danmaku.ass")
                    assFile.writeText(ass, Charsets.UTF_8)
                    outputs.add(assFile.absolutePath)
                }
                // 同时保存原始 XML（供其他工具使用）
                val danmakuFile = File(workDir, sanitize("P${page.index}.${page.title}") + ".danmaku.xml")
                danmakuFile.writeText(xml, Charsets.UTF_8)
                outputs.add(danmakuFile.absolutePath)
            }
        } catch (_: Exception) {}
    }

    // ==================== 选轨逻辑 ====================

    private fun selectVideo(play: PlayInfo, qn: String, preferCodec: String, ascending: Boolean): VideoTrack? {
        val byCodec = play.videos.filter { it.codecs.equals(preferCodec, true) }
        val pool = if (byCodec.isNotEmpty()) byCodec else play.videos
        val byQn = pool.filter { it.id == qn }
        val candidates = if (byQn.isNotEmpty()) byQn else pool
        return if (ascending) candidates.minByOrNull { it.bandwidth }
               else candidates.maxByOrNull { it.bandwidth }
    }

    private fun selectAudio(play: PlayInfo, prefer: String, ascending: Boolean): AudioTrack? {
        if (play.audios.isEmpty()) return null
        val pref = when (prefer.uppercase()) {
            "FLAC" -> play.audios.filter { it.codecs == "FLAC" }
            "M4A" -> play.audios.filter { it.codecs == "M4A" }
            else -> play.audios
        }
        val pool = if (pref.isNotEmpty()) pref else play.audios
        return if (ascending) pool.minByOrNull { it.bandwidth }
               else pool.maxByOrNull { it.bandwidth }
    }

    // ==================== 文件命名 ====================

    /**
     * 构建文件名，支持 DotNet BBDown 的全部文件名变量。
     * 变量列表（与 DotNet BBDown 一致）：
     *   <videoTitle> <pageNumber> <pageNumberWithZero> <pageTitle>
     *   <bvid> <aid> <cid> <dfn> <res> <fps> <videoCodecs> <videoBandwidth>
     *   <audioCodecs> <audioBandwidth> <ownerName> <ownerMid> <publishDate>
     */
    private fun buildFileName(
        task: DownloadTask, page: PageInfo, multiPage: Boolean,
        video: VideoTrack? = null, audio: AudioTrack? = null
    ): String {
        if (task.filePattern.isNotEmpty()) {
            // 发布时间格式: yyyy-MM-dd_HH-mm-ss
            val pubDateStr = if (task.pubTime > 0) {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
                sdf.format(java.util.Date(task.pubTime * 1000))
            } else ""
            return task.filePattern
                .replace("<videoTitle>", sanitize(task.title))
                .replace("<pageNumber>", page.index.toString())
                .replace("<pageNumberWithZero>", String.format("%02d", page.index))
                .replace("<pageTitle>", sanitize(page.title))
                .replace("<bvid>", sanitize(task.bvid))
                .replace("<aid>", page.aid)
                .replace("<cid>", page.cid)
                .replace("<dfn>", video?.dfn ?: "")
                .replace("<res>", video?.res ?: "")
                .replace("<fps>", video?.fps ?: "")
                .replace("<videoCodecs>", video?.codecs ?: "")
                .replace("<videoBandwidth>", video?.bandwidth?.toString() ?: "")
                .replace("<audioCodecs>", audio?.codecs ?: "")
                .replace("<audioBandwidth>", audio?.bandwidth?.toString() ?: "")
                .replace("<ownerName>", sanitize(task.upperName))
                .replace("<ownerMid>", task.ownerMid)
                .replace("<publishDate>", pubDateStr)
        }
        return sanitize(if (multiPage) "P${page.index}.${page.title}" else task.title)
    }

    fun sanitize(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "video" }
    }

    /**
     * 混流/元数据注入前检查 JVM 堆和 native 堆空闲内存，不足时主动触发 GC（修复4）。
     * FFmpegKit 在 native 层执行时需要额外内存（通常为输入文件大小的 1-2 倍），
     * 内存不足会直接触发 SIGSEGV（无法被 Java UncaughtExceptionHandler 捕获）。
     */
    private fun checkMemoryBeforeMux() {
        try {
            val runtime = Runtime.getRuntime()
            val jvmFreeMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
            val nativeFreeMB = Debug.getNativeHeapFreeSize() / (1024 * 1024)
            if (jvmFreeMB < 32 || nativeFreeMB < 8) {
                Logger.w("DownloadEngine", "混流前内存偏低: JVM空闲=${jvmFreeMB}MB, Native空闲=${nativeFreeMB}MB, 触发GC")
                System.gc()
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
            }
        } catch (_: Exception) {}
    }
}
