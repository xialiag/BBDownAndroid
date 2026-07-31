package com.bbdown.app.core

import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.bbdown.app.BuildConfig

/**
 * FFmpeg 版本信息工具类。
 *
 * 提供两层版本信息：
 * 1. 编译时选择的版本（BuildConfig.FFMPEG_VERSION）—— 由 Gradle 属性 -PffmpegVersion=6/8 决定
 * 2. 运行时实际 FFmpeg 库版本 —— 通过 FFmpegKitConfig 读取
 *
 * 使用场景：
 *   - 在设置页面显示当前 FFmpeg 版本
 *   - 需要版本差异化处理时通过 isV8() / isV6() 判断
 *   - 日志输出中记录版本信息，便于排查问题
 */
object FFmpegVersion {

    /** 编译时选择的 FFmpeg 主版本号（6 或 8） */
    val compiledMajorVersion: Int by lazy {
        try {
            BuildConfig.FFMPEG_VERSION.toInt()
        } catch (e: Exception) {
            6  // 默认值
        }
    }

    /** 编译时使用的 AAR 文件名 */
    val aarName: String by lazy {
        try {
            BuildConfig.FFMPEG_AAR_NAME
        } catch (e: Exception) {
            "unknown"
        }
    }

    /** 运行时 FFmpeg 库版本字符串（如 "6.1.6" 或 "8.1.2"） */
    val runtimeVersionString: String by lazy {
        try {
            FFmpegKitConfig.getFFmpegVersion()
        } catch (e: Throwable) {
            // 用 Throwable 而非 Exception：NoClassDefFoundError 是 Error 子类，
            // 若 smart-exception-java 依赖缺失时仍能优雅降级，不致启动闪退。
            "unknown"
        }
    }

    /** FFmpegKit 库版本字符串 */
    val kitVersionString: String by lazy {
        try {
            FFmpegKitConfig.getVersion()
        } catch (e: Throwable) {
            "unknown"
        }
    }

    /** 是否为 FFmpeg 8.x（编译时选择） */
    fun isV8(): Boolean = compiledMajorVersion >= 8

    /** 是否为 FFmpeg 6.x（编译时选择） */
    fun isV6(): Boolean = compiledMajorVersion in 6..7

    /**
     * 获取可读的版本描述字符串，用于 UI 展示
     * 例如："FFmpeg 6.1.6 (6.x)" 或 "FFmpeg 8.1.2 (8.x)"
     */
    fun getDisplayString(): String {
        val majorLabel = if (isV8()) "8.x" else "6.x"
        return "FFmpeg $runtimeVersionString ($majorLabel)"
    }

    /**
     * 获取完整的版本诊断信息，用于日志
     */
    fun getDiagnosticString(): String {
        return buildString {
            append("FFmpegVersion: compiled=v${compiledMajorVersion}")
            append(", aar=${aarName}")
            append(", ffmpeg=${runtimeVersionString}")
            append(", kit=${kitVersionString}")
            append(", isV8=${isV8()}")
        }
    }

    /**
     * 在日志中记录版本信息（应用启动时调用）
     */
    fun logVersionInfo() {
        Logger.i("FFmpegVersion", getDiagnosticString())
    }
}
