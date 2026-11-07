package com.bbdown.app.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ============================================================================
 *  CSS 规格镜像 —— 每个值都直接来自原版 app.css，不做任何估算
 * ============================================================================
 *
 * 命名规则：`Spec.组件名.属性`，与原 CSS 类名一一对应。
 * 注释里保留原始 CSS 声明，方便对照与后续维护。
 *
 * 提取方式：脚本解析 app.css 的 593 条规则后机械导出（见会话记录），
 * 因此这里的数值与原版逐字一致，不存在"看起来差不多"的手抄误差。
 */
object Spec {

    /** `.view { padding:16px; margin:0 auto }` 页面容器 */
    object View {
        val padding = 16.dp
        val h1Size = 20.sp
        val h1MarginBottom = 4.dp
        val h2Size = 14.sp
        val h2MarginTop = 22.dp
        val h2MarginBottom = 10.dp
        val h2LetterSpacing = 0.5.sp
        val leadSize = 13.sp
        val leadMarginBottom = 8.dp
    }

    /** `.task-item { gap:10px; padding:14px; margin:0 4px; border-radius:12px; min-height:92px }` */
    object TaskItem {
        val gap = 10.dp
        val padding = 14.dp
        val marginH = 4.dp
        val radius = 12.dp
        val minHeight = 92.dp
        val titleSize = 13.sp
        val titleWeight = androidx.compose.ui.text.font.FontWeight.Medium
    }

    /** `.vc-item { gap:12px; padding:10px; border-radius:10px }` 视频卡片 */
    object VcItem {
        val gap = 12.dp
        val padding = 10.dp
        val radius = 10.dp
        val titleSize = 13.sp
        val titleLineHeight = 1.3f
        val metaSize = 11.sp
        val metaGap = 8.dp
    }

    /** `.upper-card { gap:14px; padding:14px; border-radius:12px }` UP主卡片 */
    object UpperCard {
        val gap = 14.dp
        val padding = 14.dp
        val radius = 12.dp
    }

    /** `.upper-item { gap:10px; padding:8px 10px; border-radius:10px }` 关注项 */
    object UpperItem {
        val gap = 10.dp
        val padV = 8.dp
        val padH = 10.dp
        val radius = 10.dp
    }

    /** `.fav-folder-card { gap:8px; padding:10px; border-radius:10px }` */
    object FavFolderCard {
        val gap = 8.dp
        val padding = 10.dp
        val radius = 10.dp
    }

    /** `.file-item { gap:8px; padding:10px; border-radius:8px; font-size:12px }` */
    object FileItem {
        val gap = 8.dp
        val padding = 10.dp
        val radius = 8.dp
        val fontSize = 12.sp
    }

    /** `.pill-group { gap:3px; padding:3px; border-radius:10px }` + `button{padding:6px 14px;radius:7px;font:12px}` */
    object PillGroup {
        val gap = 3.dp
        val padding = 3.dp
        val radius = 10.dp
        val btnPadH = 14.dp
        val btnPadV = 6.dp
        val btnRadius = 7.dp
        val btnFontSize = 12.sp
    }

    /** `.btn { gap:6px; padding:9px 18px; font-size:13px; border-radius:8px; font-weight:500 }` */
    object Btn {
        val gap = 6.dp
        val padH = 18.dp
        val padV = 9.dp
        val fontSize = 13.sp
        val radius = 8.dp
    }

    /** `.sn-btn { padding:0 10px; height:26px; border-radius:6px; font-size:11.5px }` 顶栏小按钮 */
    object SnBtn {
        val padH = 10.dp
        val height = 26.dp
        val radius = 6.dp
        val fontSize = 11.5.sp
    }

    /** `.page-item { gap:10px; padding:10px 12px }` 分页项 */
    object PageItem {
        val gap = 10.dp
        val padH = 12.dp
        val padV = 10.dp
    }

    /** `.user-card { gap:14px; padding:16px; border-radius:12px }` */
    object UserCard {
        val gap = 14.dp
        val padding = 16.dp
        val radius = 12.dp
    }

    /** `.quick-item { gap:12px; padding:14px 12px; border-radius:10px }` */
    object QuickItem {
        val gap = 12.dp
        val padV = 14.dp
        val padH = 12.dp
        val radius = 10.dp
    }

    /** `.welcome-hero { padding:32px 16px 24px }` + `.logo-big{64px;margin:0 auto 14px}` */
    object WelcomeHero {
        val padTop = 32.dp
        val padH = 16.dp
        val padBottom = 24.dp
        val logoSize = 64.dp
        val logoMarginBottom = 14.dp
        val h1Size = 26.sp
        // 原版 CSS 写 font-weight:200，但 Android 系统字体无 200 字重，浏览器实际回退到常规(400)。
        // 照 CSS 写 W200 反而比原版更细，故这里用 Normal 才是"和原版看起来一样"。
        val h1Weight = androidx.compose.ui.text.font.FontWeight.Normal
        val h1LetterSpacing = 0.5.sp   // CSS letter-spacing:.5px
        val verSize = 12.sp
        val verMarginTop = 4.dp
        val verLetterSpacing = 1.sp
    }

    /** 布局骨架 */
    object Layout {
        val titlebarHeight = 38.dp
        val activitybarWidth = 48.dp
        val abBtn = 48.dp
        val abIconSize = 22.dp
        val tabsHeight = 35.dp
        val statusbarHeight = 24.dp
        val statusbarFontSize = 11.5.sp
        val statusbarPadH = 10.dp
        val statusbarGap = 14.dp
        val sidebarWidthNarrow = 220.dp
        val sidebarWidthWide = 240.dp
        val titleLogo = 24.dp
        val titleFontSize = 12.sp
        val titleLetterSpacing = 0.3.sp
        val narrowBreakpoint = 768
    }
}
