package com.bbdown.app.core

import java.text.DecimalFormat

/**
 * 弹幕工具：将 B 站 XML 弹幕转换为 ASS 字幕格式。
 * 完整移植自 DotNet BBDown 的 DanmakuUtil.cs，保持渲染效果一致。
 *
 * 核心参数：
 * - 画布: 1920×1080
 * - 字体: 黑体, 40px
 * - 滚动弹幕持续: 8秒（按文字长度动态调整）
 * - 顶/底弹幕持续: 4秒
 * - 最大轨道数: 13行（屏幕高度的50%）
 * - 超出轨道的弹幕会被丢弃
 */
object DanmakuUtil {

    private const val MONITOR_WIDTH = 1920
    private const val MONITOR_HEIGHT = 1080
    private const val FONT_SIZE = 40
    private const val MOVE_SPEND_TIME = 8.00
    private const val TOP_SPEND_TIME = 4.00
    private const val PROTECT_LENGTH = 50  // 屏占比百分比

    private const val POS_MOVE = 1
    private const val POS_TOP = 2
    private const val POS_BOTTOM = 3

    private data class DanmakuItem(
        val second: Double,
        val mode: Int,
        val color: String,
        val content: String
    )

    /**
     * 将 B 站 XML 弹幕转换为 ASS 格式。
     *
     * XML 格式: <i><d p="time,mode,fontsize,color,timestamp,...">内容</d>...</i>
     * p 属性字段:
     *   [0] = 出现时间(秒)
     *   [1] = 模式(1=滚动,4=底部,5=顶部)
     *   [2] = 字体大小
     *   [3] = 颜色(十进制)
     *   [4] = 时间戳
     */
    fun convertXmlToAss(xml: String): String {
        val items = parseXml(xml)
        if (items.isEmpty()) return ""

        // 按出现时间升序排序
        val sorted = items.sortedBy { it.second }

        val maxLine = MONITOR_HEIGHT * PROTECT_LENGTH / FONT_SIZE / 100  // = 13
        val controller = PositionController(maxLine)

        val sb = StringBuilder()
        sb.append(assHeader())

        for (danmaku in sorted) {
            val height = controller.updatePosition(danmaku.mode, danmaku.second, danmaku.content.length)
            if (height == -1) continue  // 所有轨道被占用，丢弃

            val start = computeTime(danmaku.second)
            val end = computeTime(danmaku.second + if (danmaku.mode == POS_MOVE) MOVE_SPEND_TIME else TOP_SPEND_TIME)

            val effect = when (danmaku.mode) {
                POS_BOTTOM -> "\\an8\\pos(${MONITOR_WIDTH / 2}, ${MONITOR_HEIGHT - FONT_SIZE - height})"
                POS_TOP -> "\\an8\\pos(${MONITOR_WIDTH / 2}, $height)"
                else -> "\\move($MONITOR_WIDTH, $height, ${-danmaku.content.length * FONT_SIZE}, $height)"
            }

            val colorEffect = if (danmaku.color != "FFFFFF") "\\c&${danmaku.color}&" else ""

            // Dialogue: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            sb.append("Dialogue: 2,$start,$end,BBDOWN_Style,,0000,0000,0000,,${effect}${colorEffect}${escapeAssText(danmaku.content)}\n")
        }

        return sb.toString()
    }

    /** 解析 B 站 XML 弹幕 */
    private fun parseXml(xml: String): List<DanmakuItem> {
        val result = ArrayList<DanmakuItem>()
        try {
            // 使用正则提取 <d p="...">内容</d>
            val pattern = Regex("""<d\s+[^>]*p="([^"]*)"[^>]*>(.*?)</d>""", RegexOption.DOT_MATCHES_ALL)
            for (match in pattern.findAll(xml)) {
                val attrs = match.groupValues[1].split(",")
                if (attrs.size < 8) continue

                val content = match.groupValues[2].trim()
                    .replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&#39;", "'")
                if (content.isEmpty()) continue

                val second = attrs[0].toDoubleOrNull() ?: continue
                val modeRaw = attrs[1].trim()
                val mode = when (modeRaw) {
                    "4" -> POS_BOTTOM
                    "5" -> POS_TOP
                    else -> POS_MOVE
                }
                val colorDec = attrs[3].toIntOrNull() ?: 16777215  // 默认白色
                val color = String.format("%06X", colorDec)

                result.add(DanmakuItem(second, mode, color, content))
            }
        } catch (e: Exception) {
            Logger.e("DanmakuUtil", "解析弹幕XML失败: ${e.message}")
        }
        return result
    }

    /**
     * 位置控制器：管理三条轨道队列（滚动/顶部/底部），防止弹幕重叠。
     * 当所有轨道都被占用时返回 -1，该弹幕被丢弃。
     */
    private class PositionController(maxLine: Int) {
        private val moveQueue = DoubleArray(maxLine) { 0.0 }
        private val topQueue = DoubleArray(maxLine) { 0.0 }
        private val bottomQueue = DoubleArray(maxLine) { 0.0 }

        fun updatePosition(mode: Int, time: Double, length: Int): Int {
            val queue = when (mode) {
                POS_TOP -> topQueue
                POS_BOTTOM -> bottomQueue
                else -> moveQueue
            }
            // 滚动弹幕显示时间：弹幕越长显示越久
            val displayTime = if (mode == POS_MOVE) {
                MOVE_SPEND_TIME * (length + 5) * FONT_SIZE / (MONITOR_WIDTH + (length * MOVE_SPEND_TIME))
            } else {
                TOP_SPEND_TIME
            }

            for (i in queue.indices) {
                if (time >= queue[i]) {
                    queue[i] = time + displayTime
                    return i * FONT_SIZE  // y 坐标: 0, 40, 80, ...
                }
            }
            return -1  // 所有轨道被占用
        }
    }

    /** ASS 时间格式: H:MM:SS.ss（小时不补零，分补零，秒两位整数+两位小数） */
    private fun computeTime(seconds: Double): String {
        val totalSec = seconds
        val h = (totalSec / 3600).toInt()
        val m = ((totalSec / 60) % 60).toInt()
        val s = totalSec % 60
        val df = DecimalFormat("00.00")
        return "$h:${String.format("%02d", m)}:${df.format(s)}"
    }

    /** ASS 文本转义：花括号转义 + 换行符转为 \N */
    private fun escapeAssText(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("{", "\\{").replace("}", "\\}")
            .replace("\n", "\\N").replace("\r", "")
    }

    /** ASS 文件头（与 DotNet BBDown 完全一致） */
    private fun assHeader(): String {
        return StringBuilder().apply {
            append("[Script Info]\n")
            append("Script Updated By: BBDown(https://github.com/nilaoda/BBDown)\n")
            append("ScriptType: v4.00+\n")
            append("PlayResX: $MONITOR_WIDTH\n")
            append("PlayResY: $MONITOR_HEIGHT\n")
            append("Aspect Ratio: ${MONITOR_WIDTH}:${MONITOR_HEIGHT}\n")
            append("Collisions: Normal\n")
            append("WrapStyle: 2\n")
            append("ScaledBorderAndShadow: yes\n")
            append("YCbCr Matrix: TV.601\n")
            append("[V4+ Styles]\n")
            append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
            append("Style: BBDOWN_Style, 黑体, $FONT_SIZE, &H00FFFFFF, &H00FFFFFF, &H00000000, &H00000000, 0, 0, 0, 0, 100, 100, 0.00, 0.00, 1, 2, 0, 7, 0, 0, 0, 0\n")
            append("[Events]\n")
            append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        }.toString()
    }
}
