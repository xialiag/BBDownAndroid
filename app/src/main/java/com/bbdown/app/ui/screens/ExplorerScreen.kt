package com.bbdown.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bbdown.app.core.DownloadTask
import com.bbdown.app.ui.AppViewModel
import com.bbdown.app.ui.components.EmptyState
import com.bbdown.app.ui.style.Style
import com.bbdown.app.ui.style.page
import com.bbdown.app.ui.theme.AppTheme
import com.bbdown.app.ui.theme.MonoFamily
import com.bbdown.app.ui.theme.Spec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 任务列表 / 任务详情。
 *
 * 严格按原版 renderExplorer + renderTaskItemsHTML + renderTaskDetail 的结构实现：
 *  - 无任务：.welcome-hero 空状态
 *  - 有任务：.sb-items.main-task-list 卡片列表（封面背景 + 状态滤镜 + #序号 + BV号 + 进度覆盖层）
 *  - 点卡片：任务详情（.detail-cover / .state-pill / .progress-wrap / .stat-grid）
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ExplorerScreen(vm: AppViewModel) {
    val selected = vm.tasks.find { it.taskId == vm.selectedTaskId }

    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(220))) with fadeOut(tween(160))
            } else {
                fadeIn(tween(220)) with (slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(160)))
            }
        },
        label = "explorerDetail",
    ) { task ->
        if (task != null) TaskDetail(vm, task) else TaskList(vm)
    }
}

// ==================== 任务列表 ====================

@Composable
private fun TaskList(vm: AppViewModel) {
    val tasks = vm.tasks
    if (tasks.isEmpty()) {
        EmptyHero()
        return
    }
    // .sb-items.main-task-list：卡片列表，左右各 4dp 外边距（.task-item margin:0 4px）
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        itemsIndexed(tasks, key = { _, t -> t.taskId }) { idx, t ->
            TaskCard(t, idx + 1) { vm.selectedTaskId = t.taskId }
        }
    }
}

/**
 * 任务卡片。CSS `.task-item`：
 *   display:flex; align-items:center; gap:10px; padding:14px; margin:0 4px;
 *   border-radius:12px; min-height:92px; position:relative; overflow:hidden
 *   .ti-bg{ position:absolute; inset:-4px; background-size:cover;
 *           filter:brightness(.93) saturate(1.02); transform:scale(1.08) }
 *   .ti-shade{ inset:0; box-shadow:inset 0 0 18px 8px rgba(0,0,0,.16) }
 *   .ti-index{ top:6px; left:8px; font-size:12px; font-weight:700; color:rgba(251,114,153,.55) }
 *   .ti-bv{ bottom:6px; right:8px; font-size:9.5px; color:var(--ti-bv-color); font-family:mono }
 *   .ti-progress{ top:0; left:0; bottom:0; background:linear-gradient(90deg,rgba(251,114,153,.18),rgba(251,114,153,.06)) }
 *   .ti-title{ font-size:13px; color:#fff; font-weight:500; text-shadow:0 1px 4px rgba(0,0,0,.7) }
 *   .ti-sub{ font-size:11px; color:rgba(255,255,255,.85); margin-top:4px }
 */
@Composable
private fun TaskCard(t: DownloadTask, index: Int, onClick: () -> Unit) {
    val c = AppTheme.colors
    val hasCover = t.pic.isNotEmpty()
    val pct = (t.progress.coerceIn(0f, 1f) * 100).roundToInt()
    val running = t.isRunning

    val scale by animateFloatAsState(1f, label = "cardScale")

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (hasCover) Modifier
                else Modifier.background(c.cardBg).border(1.dp, c.cardBorder, RoundedCornerShape(12.dp))
            )
            .clickable { onClick() },
    ) {
        // .ti-bg：封面铺满作背景
        if (hasCover) {
            AsyncImage(
                model = normalizePic(t.pic),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().alpha(0.93f),
            )
            // .ti-shade：内阴影压暗，保证文字可读
            Box(
                Modifier.matchParentSize().background(
                    Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.45f))
                    )
                )
            )
        }

        // .ti-progress：下载中从左向右的进度覆盖层
        if (running && t.totalBytes > 0) {
            Box(
                Modifier
                    .fillMaxWidth(t.progress.coerceIn(0f, 1f))
                    .heightIn(min = 92.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(c.accent.copy(alpha = 0.18f), c.accent.copy(alpha = 0.06f))
                        )
                    )
                    .width(0.dp)
            )
            // 右侧进度边界线
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .fillMaxWidth(t.progress.coerceIn(0f, 1f))
                        .heightIn(min = 92.dp)
                )
            }
        }

        // 内容层
        Box(Modifier.matchParentSize().padding(14.dp)) {
            // .ti-index 左上 #序号
            Text(
                "#$index",
                color = c.accent.copy(alpha = 0.55f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.align(Alignment.TopStart),
            )
            // .ti-bv 右下 BV号
            val bv = extractBv(t.url)
            if (bv.isNotEmpty()) {
                Text(
                    bv,
                    color = if (hasCover) Color.White.copy(alpha = 0.45f) else c.fgDim,
                    fontSize = 9.5.sp,
                    fontFamily = MonoFamily,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            // .ti-body 底部对齐：标题 + 状态
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 60.dp)) {
                Text(
                    t.title,
                    color = if (hasCover) Color.White else c.fg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(t.status)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        buildString {
                            append(statusText(t.status))
                            if (t.pages.size > 1) append(" · ${t.pages.size}P")
                            if (running) append(" · $pct%")
                        },
                        color = if (hasCover) Color.White.copy(alpha = 0.85f) else c.fgDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 状态小圆点（原版 taskStatusDot） */
@Composable
private fun StatusDot(status: Int) {
    val c = AppTheme.colors
    val color = when (status) {
        DownloadTask.STATUS_DONE -> c.success
        DownloadTask.STATUS_FAILED -> c.error
        DownloadTask.STATUS_PAUSED -> c.warn
        DownloadTask.STATUS_PARSING, DownloadTask.STATUS_DOWNLOADING, DownloadTask.STATUS_MUXING -> c.accent
        else -> c.fgDim
    }
    // 运行中呼吸
    val infinite = rememberInfiniteTransition(label = "dot")
    val a by if (status == DownloadTask.STATUS_DOWNLOADING || status == DownloadTask.STATUS_PARSING || status == DownloadTask.STATUS_MUXING) {
        infinite.animateFloat(1f, 0.4f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "dotA")
    } else {
        remember { androidx.compose.runtime.mutableStateOf(1f) }
    }
    Box(Modifier.size(6.dp).alpha(a).clip(RoundedCornerShape(percent = 50)).background(color))
}

private fun statusText(status: Int): String = when (status) {
    DownloadTask.STATUS_PENDING -> "等待中"
    DownloadTask.STATUS_PARSING -> "解析中"
    DownloadTask.STATUS_DOWNLOADING -> "下载中"
    DownloadTask.STATUS_MUXING -> "合并中"
    DownloadTask.STATUS_DONE -> "已完成"
    DownloadTask.STATUS_FAILED -> "失败"
    DownloadTask.STATUS_CANCELED -> "已取消"
    DownloadTask.STATUS_PAUSED -> "已暂停"
    else -> "未知"
}

private fun extractBv(url: String): String {
    val m = Regex("[Bb][Vv]1[0-9A-Za-z]{9}").find(url)
    return m?.value ?: ""
}

// ==================== 空状态 ====================

/** .welcome-hero：64px logo + 26px/200 标题 + 12px 粉色副标题 + 13px 提示 */
@Composable
private fun EmptyHero() {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spec.WelcomeHero.padH, vertical = Spec.WelcomeHero.padTop),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 原版内联 SVG：viewBox 0 0 24 24
        Canvas(Modifier.size(Spec.WelcomeHero.logoSize)) {
            val u = size.width / 24f
            drawRoundRect(
                Color(0xFFFAD4E0),
                Offset(2 * u, 2 * u),
                Size(20 * u, 20 * u),
                CornerRadius(5 * u, 5 * u),
            )
            val arrow = Path().apply {
                moveTo(11 * u, 7 * u); lineTo(13 * u, 7 * u); lineTo(13 * u, 12 * u)
                lineTo(14.5f * u, 10.5f * u); lineTo(16 * u, 12 * u); lineTo(12 * u, 16 * u)
                lineTo(8 * u, 12 * u); lineTo(9.5f * u, 10.5f * u); lineTo(11 * u, 12 * u)
                close()
            }
            drawPath(arrow, Color.White)
            drawRoundRect(
                Color.White,
                Offset(7.5f * u, 16.8f * u),
                Size(9 * u, 2 * u),
                CornerRadius(0.5f * u, 0.5f * u),
            )
        }
        Spacer(Modifier.height(Spec.WelcomeHero.logoMarginBottom))
        Text("BBDown", color = c.fgBright, fontSize = Spec.WelcomeHero.h1Size,
            fontWeight = Spec.WelcomeHero.h1Weight, letterSpacing = Spec.WelcomeHero.h1LetterSpacing)
        Spacer(Modifier.height(Spec.WelcomeHero.verMarginTop))
        Text("B站视频下载器", color = c.accent, fontSize = Spec.WelcomeHero.verSize,
            letterSpacing = Spec.WelcomeHero.verLetterSpacing)
        Spacer(Modifier.height(14.dp))
        // .lead margin-top:14px
        Text("点击左侧「新建下载」开始", color = c.fgDim, fontSize = 13.sp)
    }
}

// ==================== 任务详情 ====================

/**
 * 任务详情。CSS：
 *   .detail-cover{ width:100%; max-height:200px; object-fit:contain; border-radius:8px; margin-bottom:14px }
 *   h1{ font-size:20px; font-weight:300 }  .detail-meta{ font-size:12px; color:fg-dim }
 *   .state-pill{ font-size:11px; padding:3px 9px; border-radius:10px; font-weight:600 }
 *   .progress-bar{ height:6px; border-radius:3px }  .progress-info{ font-size:11.5px; mono }
 *   .stat-grid{ grid 2列; gap:8px }  .stat-card{ padding:12px; radius:8px }
 */
@Composable
private fun TaskDetail(vm: AppViewModel, t: DownloadTask) {
    val c = AppTheme.colors
    val pct = (t.progress.coerceIn(0f, 1f) * 100).roundToInt()
    val running = t.isRunning

    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        // 标题
        item {
            Text(t.title, color = c.fgBright, fontSize = 20.sp, fontWeight = FontWeight.Light,
                lineHeight = 27.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    if (t.pages.size > 1) append("${t.pages.size} 个分P · ")
                    append("创建于 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t.createTime * 1000))}")
                },
                color = c.fgDim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))
        }
        // 封面
        if (t.pic.isNotEmpty()) {
            item {
                AsyncImage(
                    model = normalizePic(t.pic),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp)).background(c.cardBg),
                )
                Spacer(Modifier.height(14.dp))
            }
        }
        // 状态胶囊
        item {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(
                        when (t.status) {
                            DownloadTask.STATUS_DONE -> c.success.copy(alpha = 0.15f)
                            DownloadTask.STATUS_FAILED -> c.error.copy(alpha = 0.15f)
                            DownloadTask.STATUS_PAUSED -> c.warn.copy(alpha = 0.15f)
                            else -> c.accent.copy(alpha = 0.15f)
                        }
                    )
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(t.status)
                Spacer(Modifier.width(5.dp))
                Text(statusText(t.status), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = when (t.status) {
                        DownloadTask.STATUS_DONE -> c.success
                        DownloadTask.STATUS_FAILED -> c.error
                        DownloadTask.STATUS_PAUSED -> c.warn
                        else -> c.accent
                    })
            }
            Spacer(Modifier.height(14.dp))
        }
        // 进度
        item {
            SmoothProgress(t.progress, t.status)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    buildString {
                        append("$pct%")
                        if (running && t.speed > 0) append(" · ${fmtSpeed(t.speed)}")
                    },
                    color = c.fgDim, fontSize = 11.5.sp, fontFamily = MonoFamily,
                )
                Text(
                    if (t.status == DownloadTask.STATUS_DONE && t.totalBytes > 0) fmtBytes(t.totalBytes)
                    else "${fmtBytes(t.downloadedBytes)} / ${if (t.totalBytes > 0) fmtBytes(t.totalBytes) else "?"}",
                    color = c.fgDim, fontSize = 11.5.sp, fontFamily = MonoFamily,
                )
            }
            Spacer(Modifier.height(14.dp))
        }
        // 错误
        if (t.errorMsg.isNotEmpty()) {
            item {
                Text(t.errorMsg, color = c.error, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)).background(c.errorBg)
                        .padding(10.dp))
                Spacer(Modifier.height(10.dp))
            }
        }
        // .stat-grid 2 列
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("状态", statusText(t.status), Modifier.weight(1f))
                StatCard("分P数", "${t.pages.size}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
        }
        // 输出文件
        if (t.outputFiles.isNotEmpty()) {
            item { Style.H2("输出文件") }
            items(t.outputFiles.size) { i ->
                val f = t.outputFiles[i]
                Text(
                    f.substringAfterLast('/'),
                    color = c.fg, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(8.dp)).background(c.cardBg)
                        .border(1.dp, c.cardBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }
        }
        // 操作按钮
        item {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                when {
                    running -> {
                        Style.Button("暂停", { vm.pauseTask(t.taskId) }, height = 36.dp)
                        Style.Button("取消", { vm.cancelTask(t.taskId) }, kind = Style.Kind.Danger, height = 36.dp)
                    }
                    t.status == DownloadTask.STATUS_PAUSED ->
                        Style.Button("继续下载", { vm.resumeTask(t.taskId) }, kind = Style.Kind.Primary, height = 36.dp)
                    t.status == DownloadTask.STATUS_FAILED || t.status == DownloadTask.STATUS_CANCELED ->
                        Style.Button("重试", { vm.retryTask(t.taskId) }, kind = Style.Kind.Primary, height = 36.dp)
                }
                Style.Button("删除", { vm.removeTask(t.taskId); vm.selectedTaskId = null },
                    kind = Style.Kind.Ghost, height = 36.dp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** `.stat-card{ padding:12px; radius:8px }` + `.sc-label{ 10.5px uppercase }` + `.sc-value{ 16px mono }` */
@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(8.dp)).padding(12.dp),
    ) {
        Text(label.uppercase(), color = c.fgDim, fontSize = 10.5.sp, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = c.fgBright, fontSize = 16.sp, fontFamily = MonoFamily)
    }
}

/** `.progress-bar{ height:6px; radius:3px }` + `.progress-fill` 渐变 + shimmer */
@Composable
private fun SmoothProgress(progress: Float, status: Int) {
    val c = AppTheme.colors
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(420), label = "prog")
    Box(
        Modifier.fillMaxWidth().height(6.dp)
            .clip(RoundedCornerShape(3.dp)).background(c.borderLight)
    ) {
        Box(
            Modifier.fillMaxWidth(animated).height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(
                    when (status) {
                        DownloadTask.STATUS_DONE -> androidx.compose.ui.graphics.SolidColor(c.success)
                        DownloadTask.STATUS_FAILED -> androidx.compose.ui.graphics.SolidColor(c.error)
                        else -> Brush.horizontalGradient(listOf(c.accent, c.accentBtnHover))
                    }
                )
        )
    }
}

// ==================== 工具 ====================

internal fun fmtBytes(b: Long): String = when {
    b <= 0 -> "0 B"
    b >= 1_073_741_824 -> String.format("%.2f GB", b / 1_073_741_824.0)
    b >= 1_048_576 -> String.format("%.2f MB", b / 1_048_576.0)
    b >= 1024 -> String.format("%.1f KB", b / 1024.0)
    else -> "$b B"
}

internal fun fmtSpeed(bps: Long): String = if (bps <= 0) "" else "${fmtBytes(bps)}/s"

/** B站图片地址规整 */
internal fun normalizePic(pic: String): String {
    if (pic.isEmpty()) return ""
    var p = pic
    if (p.startsWith("//")) p = "https:$p"
    else if (!p.startsWith("http")) p = "https://$p"
    if (p.contains("hdslb.com") && !p.contains("@")) p = "$p@336w_189h_1c.webp"
    return p
}
