package com.bbdown.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 原版图标 SVG —— 直接取自 WebView 版 index.html 的 `<svg viewBox="0 0 24 24">` 内联 path，
 * 保证与 WebView 版图标形状完全一致（Material Icons 的描边粗细与原版不同，不能用）。
 *
 * 解析方式：把 path 的 `d` 属性交给 addPathNodes，Compose 会按 viewBox 24x24 还原。
 */
object BBIcons {

    private fun icon(name: String, vararg paths: Pair<String, Boolean>): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { (d, _) ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = SolidColor(Color.White),   // 实际颜色由 Icon(tint=) 覆盖
                )
            }
        }.build()

    /** 任务列表（4 条横线） */
    val Explorer: ImageVector = icon(
        "explorer",
        "M4 5h16v2H4zm0 4h16v2H4zm0 4h16v2H4zm0 4h16v2H4z" to true,
    )

    /** 新建下载（箭头 + 底部横条） */
    val AddTask: ImageVector = icon(
        "addtask",
        "M11 3h2v9.6l3.3-3.3 1.4 1.4L12 16l-5.7-5.3 1.4-1.4L11 12.6z" to true,
        "M5 18h14v2.4H5z" to true,
    )

    /** 搜索（放大镜） */
    val Search: ImageVector = icon(
        "search",
        "M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 0 1 9.5 14z" to true,
    )

    /** 账号（人像） */
    val Account: ImageVector = icon(
        "account",
        "M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0 2c-5 0-9 2.5-9 6v2h18v-2c0-3.5-4-6-9-6z" to true,
    )

    /** 帮助（灯泡） */
    val Help: ImageVector = icon(
        "help",
        "M12 4a6 6 0 0 0-4 10.5V16a1 1 0 0 0 1 1h6a1 1 0 0 0 1-1v-1.5A6 6 0 0 0 12 4zm-1 15h2v2h-2z" to true,
    )

    /** 设置（齿轮） */
    val Settings: ImageVector = icon(
        "settings",
        "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6a3.6 3.6 0 1 1 0-7.2 3.6 3.6 0 0 1 0 7.2z" to true,
    )
}
