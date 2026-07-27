package com.bbdown.app.core

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 基于 FFmpeg 的音视频混流器，完全移植自原版 BBDown (BBDownMuxer.MuxAV)。
 * 使用 FFmpeg 命令行做混流和元数据注入，输出与原版 BBDown 完全一致。
 *
 * 元数据字段映射（FFmpeg → iTunes tag）：
 * - title         → ©nam
 * - artist(UP主)  → ©ART
 * - album(合集名) → ©alb
 * - description   → desc
 * - creation_time → mvhd creation_time (MP4 容器级别)
 * - encoder       → ©too (由 FFmpeg 自动写入 "Lavf*")
 */
object FFmpegMuxer {

    /** 字幕文件信息（用于混流时嵌入） */
    data class SubtitleTrack(
        val file: File,
        val lan: String,        // 原始语言码，如 "zh-CN"
        val isoCode: String,    // ISO 639-2 码，如 "chi"
        val lanDoc: String      // 显示名称，如 "中文（简体）"
    )

    /**
     * 混流视频+音频并写入元数据和封面（参考原版 BBDown MuxAV）
     * 用于 all 模式（视频+音频混流）
     */
    fun muxWithMetadata(
        videoFile: File,
        audioFile: File?,
        outputFile: File,
        title: String = "",
        album: String = "",
        artist: String = "",
        desc: String = "",
        pubTime: Long = 0,
        coverFile: File? = null,
        subtitles: List<SubtitleTrack> = emptyList()
    ): Boolean {
        Logger.i("FFmpegMuxer", "=== 混流开始: video=${videoFile.name}, audio=${audioFile?.name}, subs=${subtitles.size} ===")

        val jpegCover = ensureJpegCover(coverFile)

        // 构建 FFmpeg 命令（完全按照原版 BBDown MuxAV 的参数顺序）
        val cmd = mutableListOf("-loglevel", "warning", "-y")

        // 输入流（按原版顺序：视频 → 音频 → 封面 → 字幕）
        val inputCount = mutableListOf<File>()
        inputCount.add(videoFile)
        if (audioFile != null && audioFile.exists()) {
            inputCount.add(audioFile)
        }
        if (jpegCover != null) {
            inputCount.add(jpegCover)
        }
        // 字幕文件作为额外输入
        val validSubs = subtitles.filter { it.file.exists() && it.file.length() > 0 }
        for (sub in validSubs) {
            inputCount.add(sub.file)
        }
        for (input in inputCount) {
            cmd.add("-i"); cmd.add(input.absolutePath)
        }

        // 全量 map（原版 BBDown 使用 -map {i} 映射所有输入）
        for (i in inputCount.indices) {
            cmd.add("-map"); cmd.add(i.toString())
        }

        // 封面 attached_pic 处置
        // muxWithMetadata 总是有视频流（input 0），封面是第二个视频流 → v:1
        if (jpegCover != null) {
            cmd.add("-disposition:v:1"); cmd.add("attached_pic")
        }

        // 字幕流元数据（参考原版 BBDown: -metadata:s:s:{i} title=... language=...）
        for ((i, sub) in validSubs.withIndex()) {
            cmd.add("-metadata:s:s:$i"); cmd.add("title=${escapeMetadata(sub.lanDoc)}")
            cmd.add("-metadata:s:s:$i"); cmd.add("language=${sub.isoCode}")
        }

        // 流复制
        cmd.add("-c:v"); cmd.add("copy")
        cmd.add("-c:a"); cmd.add("copy")

        // 字幕编码转换为 mov_text（与原版 BBDown 一致）
        if (validSubs.isNotEmpty()) {
            cmd.add("-c:s"); cmd.add("mov_text")
        }

        // 元数据
        addMetadata(cmd, title, album, artist, desc, pubTime)

        // 输出封装参数（与原版 BBDown 完全一致）
        cmd.add("-movflags"); cmd.add("faststart")
        cmd.add("-strict"); cmd.add("unofficial")
        cmd.add("-strict"); cmd.add("-2")
        cmd.add("-f"); cmd.add("mp4")
        cmd.add("--"); cmd.add(outputFile.absolutePath)

        val success = execute(cmd, outputFile)
        if (success && jpegCover != null && jpegCover != coverFile) {
            jpegCover.delete()
        }
        return success
    }

    /**
     * 仅注入元数据和封面（不混流，用于 audio_only / video_only 模式）
     * FFmpeg 会自动将 fragmented MP4 转为标准 MP4，无需单独 remux
     *
     * 原版 BBDown 在 audioOnly 模式下清空 videoPath，仅保留 audioPath + pic
     * 在 videoOnly 模式下清空 audioPath，仅保留 videoPath + pic
     */
    fun injectMetadataOnly(
        file: File,
        title: String = "",
        album: String = "",
        artist: String = "",
        desc: String = "",
        pubTime: Long = 0,
        coverFile: File? = null,
        subtitles: List<SubtitleTrack> = emptyList()
    ): Boolean {
        val hasMetadata = title.isNotEmpty() || artist.isNotEmpty() || desc.isNotEmpty() ||
                album.isNotEmpty() || pubTime > 0
        val hasCover = coverFile != null && coverFile.exists() && coverFile.length() > 0
        val hasSubs = subtitles.any { it.file.exists() && it.file.length() > 0 }
        if (!hasMetadata && !hasCover && !hasSubs) {
            Logger.i("FFmpegMuxer", "无元数据可注入，跳过")
            return true
        }

        Logger.i("FFmpegMuxer", "=== 注入元数据: file=${file.name}, size=${file.length()}, subs=${subtitles.size} ===")

        val jpegCover = ensureJpegCover(coverFile)
        val isVideo = file.name.endsWith(".mp4")
        // 使用正确的临时文件扩展名，确保 FFmpeg 能识别输出格式
        val ext = if (isVideo) ".mp4" else ".m4a"
        val outputFile = File(file.parentFile, file.nameWithoutExtension + ".meta" + ext)

        // 构建 FFmpeg 命令（完全按照原版 BBDown MuxAV 的参数顺序）
        val cmd = mutableListOf("-loglevel", "warning", "-y")

        // 输入流
        val inputCount = mutableListOf<File>()
        inputCount.add(file)
        if (jpegCover != null) {
            inputCount.add(jpegCover)
        }
        // 字幕文件作为额外输入
        val validSubs = subtitles.filter { it.file.exists() && it.file.length() > 0 }
        for (sub in validSubs) {
            inputCount.add(sub.file)
        }
        for (input in inputCount) {
            cmd.add("-i"); cmd.add(input.absolutePath)
        }

        // 全量 map（原版 BBDown 使用 -map {i} 映射所有输入）
        for (i in inputCount.indices) {
            cmd.add("-map"); cmd.add(i.toString())
        }

        // 封面 attached_pic 处置
        // 视频文件已有视频流 → 封面是 v:1
        // 音频文件无视频流 → 封面是 v:0
        if (jpegCover != null) {
            val coverStreamIdx = if (isVideo) 1 else 0
            cmd.add("-disposition:v:$coverStreamIdx"); cmd.add("attached_pic")
        }

        // 字幕流元数据
        for ((i, sub) in validSubs.withIndex()) {
            cmd.add("-metadata:s:s:$i"); cmd.add("title=${escapeMetadata(sub.lanDoc)}")
            cmd.add("-metadata:s:s:$i"); cmd.add("language=${sub.isoCode}")
        }

        // 流复制
        cmd.add("-c:v"); cmd.add("copy")
        cmd.add("-c:a"); cmd.add("copy")

        // 字幕编码转换为 mov_text
        if (validSubs.isNotEmpty()) {
            cmd.add("-c:s"); cmd.add("mov_text")
        }

        // 元数据
        addMetadata(cmd, title, album, artist, desc, pubTime)

        // 输出封装参数（与原版 BBDown 完全一致）
        cmd.add("-movflags"); cmd.add("faststart")
        cmd.add("-strict"); cmd.add("unofficial")
        cmd.add("-strict"); cmd.add("-2")
        cmd.add("-f"); cmd.add("mp4")
        cmd.add("--"); cmd.add(outputFile.absolutePath)

        val success = execute(cmd, outputFile)
        if (success) {
            file.delete()
            outputFile.renameTo(file)
            Logger.i("FFmpegMuxer", "元数据注入完成: ${file.name} (${file.length()}字节)")
            if (jpegCover != null && jpegCover != coverFile) {
                jpegCover.delete()
            }
        } else {
            if (outputFile.exists()) outputFile.delete()
        }
        return success
    }

    /**
     * 检查封面格式，如果是 WebP 则用 FFmpeg 转换为 JPEG
     * iTunes 元数据仅支持 JPEG/PNG，不支持 WebP
     */
    private fun ensureJpegCover(coverFile: File?): File? {
        if (coverFile == null || !coverFile.exists() || coverFile.length() == 0L) return null

        try {
            val bytes = ByteArray(12)
            FileInputStream(coverFile).use { it.read(bytes) }
            val isWebP = bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

            if (!isWebP) return coverFile

            Logger.i("FFmpegMuxer", "封面为 WebP，转换为 JPEG")
            val jpegFile = File(coverFile.parentFile, coverFile.nameWithoutExtension + ".jpg")
            val cmd = arrayOf(
                "-y", "-i", coverFile.absolutePath,
                "-c:v", "mjpeg", "-frames:v", "1",
                jpegFile.absolutePath
            )
            val session = FFmpegKit.executeWithArguments(cmd)
            if (ReturnCode.isSuccess(session.returnCode) && jpegFile.exists() && jpegFile.length() > 0) {
                return jpegFile
            }
            Logger.w("FFmpegMuxer", "WebP 转换失败，使用原始封面")
        } catch (e: Exception) {
            Logger.w("FFmpegMuxer", "封面格式检查失败: ${e.message}")
        }
        return coverFile
    }

    /**
     * 添加元数据参数到 FFmpeg 命令
     * 完全按照原版 BBDown MuxAV 的元数据键和顺序
     */
    private fun addMetadata(
        cmd: MutableList<String>,
        title: String, album: String, artist: String,
        desc: String, pubTime: Long
    ) {
        // 原版 BBDown：episodeId 为空时用 title，否则用 episodeId
        // 这里 title 已由调用方决定（分P时传入 page.title，否则传入 task.title）
        if (title.isNotEmpty()) {
            cmd.add("-metadata"); cmd.add("title=${escapeMetadata(title)}")
        }
        // description = 视频描述
        if (desc.isNotEmpty()) {
            cmd.add("-metadata"); cmd.add("description=${escapeMetadata(desc)}")
        }
        // artist = UP主名
        if (artist.isNotEmpty()) {
            cmd.add("-metadata"); cmd.add("artist=${escapeMetadata(artist)}")
        }
        // album = 视频主标题（仅分P时设置）
        if (album.isNotEmpty()) {
            cmd.add("-metadata"); cmd.add("album=${escapeMetadata(album)}")
        }
        // creation_time = 发布时间（UTC ISO8601，与原版格式一致：yyyy-MM-ddTHH:mm:ss.ffffffZ）
        if (pubTime > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            // 原版使用6位微秒，SimpleDateFormat仅支持3位毫秒，补零到6位
            val creationTime = sdf.format(Date(pubTime * 1000)) + "000Z"
            cmd.add("-metadata"); cmd.add("creation_time=$creationTime")
        }
    }

    /**
     * 转义元数据值（参考原版 BBDown EscapeString）
     * 将 " 替换为 '，将 \ 替换为 \\
     */
    private fun escapeMetadata(s: String): String {
        return s.replace("\"", "'").replace("\\", "\\\\")
    }

    /**
     * 执行 FFmpeg 命令并检查结果
     */
    private fun execute(cmd: List<String>, outputFile: File): Boolean {
        val cmdStr = cmd.joinToString(" ") { arg ->
            if (arg.contains(" ") || arg.contains("'") || arg.contains("\\")) "\"$arg\"" else arg
        }
        Logger.i("FFmpegMuxer", "执行: ffmpeg $cmdStr")

        val session = FFmpegKit.executeWithArguments(cmd.toTypedArray())
        val returnCode = session.returnCode

        if (ReturnCode.isSuccess(returnCode)) {
            Logger.i("FFmpegMuxer", "成功: ${outputFile.name} (${outputFile.length()}字节)")
            return outputFile.exists() && outputFile.length() > 0
        } else {
            Logger.e("FFmpegMuxer", "失败: rc=${returnCode.value}")
            val logs = session.allLogsAsString
            if (logs != null && logs.isNotEmpty()) {
                val tail = if (logs.length > 1500) logs.substring(logs.length - 1500) else logs
                Logger.e("FFmpegMuxer", "FFmpeg日志(尾部):\n$tail")
            }
            return false
        }
    }
}
