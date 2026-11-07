package com.bbdown.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.with
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bbdown.app.core.DownloadTask
import com.bbdown.app.ui.AppViewModel
import com.bbdown.app.ui.components.AppButton
import com.bbdown.app.ui.components.ButtonKind
import com.bbdown.app.ui.components.EmptyState
import com.bbdown.app.ui.components.PressableBox
import com.bbdown.app.ui.components.SmoothProgressBar
import com.bbdown.app.ui.components.StaggeredItem
import com.bbdown.app.ui.components.StatusBadge
import com.bbdown.app.ui.theme.AppTheme
import com.bbdown.app.ui.theme.Dimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 任务状态 → 显示名与颜色 */
private fun statusLabel(status: Int): Pair<String, androidx.compose.ui.graphics.Color> = when (status) {
    DownloadTask.STATUS_PENDING -> "等待中" to androidx.compose.ui.graphics.Color(0xFF858585)
    DownloadTask.STATUS_PARSING -> "解析中" to androidx.compose.ui.graphics.Color(0xFFCCA700)
    DownloadTask.STATUS_DOWNLOADING -> "下载中" to androidx.compose.ui.graphics.Color(0xFFFB7299)
    DownloadTask.STATUS_MUXING -> "混流中" to androidx.compose.ui.graphics.Color(0xFFCCA700)
    DownloadTask.STATUS_DONE -> "已完成" to androidx.compose.ui.graphics.Color(0xFF89D185)
    DownloadTask.STATUS_FAILED -> "失败" to androidx.compose.ui.graphics.Color(0xFFF48771)
    DownloadTask.STATUS_CANCELED -> "已取消" to androidx.compose.ui.graphics.Color(0xFF858585)
    DownloadTask.STATUS_PAUSED -> "已暂停" to androidx.compose.ui.graphics.Color(0xFFCCA700)
    else -> "未知" to androidx.compose.ui.graphics.Color(0xFF858585)
}

private fun fmtBytes(b: Long): String = when {
    b <= 0 -> "0 B"
    b >= 1_073_741_824 -> String.format("%.2f GB", b / 1_073_741_824.0)
    b >= 1_048_576 -> String.format("%.2f MB", b / 1_048_576.0)
    b >= 1024 -> String.format("%.1f KB", b / 1024.0)
    else -> "$b B"
}

private fun fmtSpeed(bps: Long): String = if (bps <= 0) "" else "${fmtBytes(bps)}/s"

/**
 * 任务列表视图。
 * 动效：
 *  - 任务卡片按序淡入上移（StaggeredItem）
 *  - 进度条数值插值（SmoothProgressBar）
 *  - 状态徽标运行中呼吸
 *  - 点击卡片有按压缩放（PressableBox）
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ExplorerScreen(vm: AppViewModel) {
    val c = AppTheme.colors
    val tasks = vm.tasks
    val selected = tasks.find { it.taskId == vm.selectedTaskId }

    // 详情页与列表之间用左右滑入切换（对应 WebView 版的视图替换）
    AnimatedContent(
        targetState = selected != null,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(220))) with fadeOut(tween(160))
            } else {
                fadeIn(tween(220)) with (slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(160)))
            }
        },
        label = "explorerDetail",
    ) { isDetail ->
        if (isDetail && selected != null) {
            TaskDetail(vm, selected)
        } else {
            TaskList(vm, tasks)
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun TaskList(vm: AppViewModel, tasks: List<DownloadTask>) {
    val c = AppTheme.colors
    if (tasks.isEmpty()) {
        EmptyHero()
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("任务列表 (${tasks.size})", color = c.fgBright, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (tasks.any { it.status == DownloadTask.STATUS_DONE || it.status == DownloadTask.STATUS_FAILED || it.status == DownloadTask.STATUS_CANCELED }) {
                    AppButton("清理已完成", { vm.clearFinished() }, fontSize = 11)
                }
            }
        }
        itemsIndexed(tasks, key = { _, t -> t.taskId }) { index, task ->
            StaggeredItem(index) {
                TaskCard(vm, task) { vm.selectedTaskId = task.taskId }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun EmptyHero() {
    val c = AppTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 图标淡入 + 轻微放大，呼应 WebView 版的静态 logo
            val scale by animateFloatAsState(1f, tween(600), label = "hero")
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Text("↓", color = c.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(14.dp))
            Text("BBDown", color = c.fgBright, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("B站视频下载器", color = c.accent, fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
            Text("点击左侧「新建下载」开始", color = c.fgDim, fontSize = 13.sp)
        }
    }
}

/** 单个任务卡片 */
@Composable
private fun TaskCard(vm: AppViewModel, task: DownloadTask, onClick: () -> Unit) {
    val c = AppTheme.colors
    val (label, color) = statusLabel(task.status)
    val running = task.isRunning
    val pct = (task.progress * 100).roundToInt().coerceIn(0, 100)

    PressableBox(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(Dimens.radiusLg))
            .padding(10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 缩略图
                Box(
                    Modifier
                        .width(84.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(Dimens.radiusSm))
                        .background(c.bgInput),
                ) {
                    if (task.pic.isNotEmpty()) {
                        AsyncImage(
                            model = normalizePic(task.pic),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title,
                        color = c.fgBright,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(label, color, pulsing = running)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                running -> "$pct%"
                                task.status == DownloadTask.STATUS_DONE -> fmtBytes(task.downloadedBytes)
                                else -> "${task.pages.size}P"
                            },
                            color = c.fgDim, fontSize = 10.5.sp,
                        )
                    }
                }
            }

            // 进度条：只在有意义的阶段显示，出现/消失都带动画
            AnimatedVisibility(
                visible = running || task.status == DownloadTask.STATUS_PAUSED,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    SmoothProgressBar(progress = task.progress, color = if (task.isPaused) c.warn else c.accent)
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            if (task.totalBytes > 0) "${fmtBytes(task.downloadedBytes)} / ${fmtBytes(task.totalBytes)}" else "",
                            color = c.fgDim, fontSize = 10.5.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(fmtSpeed(task.speed), color = c.accent, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (task.errorMsg.isNotEmpty() && task.status == DownloadTask.STATUS_FAILED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    task.errorMsg,
                    color = c.error,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dimens.radiusSm))
                        .background(c.errorBg)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }

            // 操作按钮：按状态切换，切换时淡入淡出
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    task.isRunning -> {
                        AppButton("暂停", { vm.pauseTask(task.taskId) }, fontSize = 11)
                        AppButton("取消", { vm.cancelTask(task.taskId) }, kind = ButtonKind.Danger, fontSize = 11)
                    }
                    task.status == DownloadTask.STATUS_PAUSED ->
                        AppButton("继续下载", { vm.resumeTask(task.taskId) }, kind = ButtonKind.Primary, fontSize = 11)
                    task.status == DownloadTask.STATUS_FAILED || task.status == DownloadTask.STATUS_CANCELED ->
                        AppButton("重试", { vm.retryTask(task.taskId) }, kind = ButtonKind.Primary, fontSize = 11)
                    else -> Unit
                }
                AppButton("删除", { vm.removeTask(task.taskId) }, kind = ButtonKind.Ghost, fontSize = 11)
            }
        }
    }
}

/** 任务详情：字段逐行淡入 */
@Composable
private fun TaskDetail(vm: AppViewModel, task: DownloadTask) {
    val c = AppTheme.colors
    val (label, color) = statusLabel(task.status)
    val pct = (task.progress * 100).roundToInt().coerceIn(0, 100)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppButton("返回", { vm.selectedTaskId = null }, kind = ButtonKind.Ghost, fontSize = 11)
                Spacer(Modifier.weight(1f))
                StatusBadge(label, color, pulsing = task.isRunning)
            }
        }
        item {
            Text(task.title, color = c.fgBright, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (task.isRunning || task.status == DownloadTask.STATUS_PAUSED) {
                SmoothProgressBar(task.progress, height = 6.dp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("$pct%  ${fmtBytes(task.downloadedBytes)} / ${fmtBytes(task.totalBytes)}", color = c.fgDim, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(fmtSpeed(task.speed), color = c.accent, fontSize = 11.sp)
                }
            }
        }
        item { DetailRow("任务 ID", task.taskId) }
        item { DetailRow("链接", task.url) }
        item { DetailRow("分P数", "${task.pages.size}") }
        item { DetailRow("下载模式", task.downloadMode) }
        item { DetailRow("视频编码", task.preferCodec) }
        if (task.collectionTitle.isNotEmpty()) item { DetailRow("合集", task.collectionTitle) }
        item { DetailRow("创建时间", SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(task.createTime * 1000))) }
        if (task.finishTime > 0) item { DetailRow("完成时间", SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(task.finishTime * 1000))) }
        if (task.errorMsg.isNotEmpty()) {
            item {
                Text(
                    task.errorMsg,
                    color = c.error, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusMd))
                        .background(c.errorBg).padding(10.dp),
                )
            }
        }
        if (task.outputFiles.isNotEmpty()) {
            item {
                Text("输出文件", color = c.fgTitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            itemsIndexed(task.outputFiles) { i, path ->
                StaggeredItem(i) {
                    Text(
                        path.substringAfterLast('/'),
                        color = c.fg, fontSize = 11.5.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusSm))
                            .background(c.bgInput).padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    task.isRunning -> {
                        AppButton("暂停", { vm.pauseTask(task.taskId) }, fontSize = 12)
                        AppButton("取消", { vm.cancelTask(task.taskId) }, kind = ButtonKind.Danger, fontSize = 12)
                    }
                    task.status == DownloadTask.STATUS_PAUSED -> AppButton("继续下载", { vm.resumeTask(task.taskId) }, kind = ButtonKind.Primary, fontSize = 12)
                    task.status == DownloadTask.STATUS_FAILED || task.status == DownloadTask.STATUS_CANCELED -> AppButton("重试", { vm.retryTask(task.taskId) }, kind = ButtonKind.Primary, fontSize = 12)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun DetailRow(k: String, v: String) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = c.fgDim, fontSize = 11.5.sp, modifier = Modifier.width(72.dp))
        Text(v, color = c.fg, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** B站图片地址规整：补协议 + 加 @尺寸后缀减小流量（与 WebView 版 imgSrc 一致） */
internal fun normalizePic(pic: String): String {
    if (pic.isEmpty()) return ""
    var p = pic
    if (p.startsWith("//")) p = "https:$p"
    else if (!p.startsWith("http")) p = "https://$p"
    // i0.hdslb.com 支持 @ 后缀缩放
    if (p.contains("hdslb.com") && !p.contains("@")) p = "$p@336w_189h_1c.webp"
    return p
}
