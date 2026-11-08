package com.bbdown.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bbdown.app.ui.AppViewModel
import com.bbdown.app.ui.components.ErrorRow
import com.bbdown.app.ui.components.LoadingRow
import com.bbdown.app.ui.components.Pill
import com.bbdown.app.ui.components.PressableBox
import com.bbdown.app.ui.components.SectionTitle
import com.bbdown.app.ui.components.SmoothProgressBar
import com.bbdown.app.ui.components.StaggeredItem
import com.bbdown.app.ui.style.Style
import com.bbdown.app.ui.style.page
import com.bbdown.app.ui.theme.AppTheme
import com.bbdown.app.ui.theme.Dimens

/** 下载模式选项 */
private val DOWNLOAD_MODES = listOf(
    "all" to "完整下载",
    "video_only" to "仅视频",
    "audio_only" to "仅音频",
    "subtitle_only" to "仅字幕",
    "cover_only" to "仅封面",
    "danmaku_only" to "仅弹幕",
)

private val CODECS = listOf("avc" to "AVC", "hevc" to "HEVC", "av1" to "AV1")

/**
 * 新建下载页。
 * 动效：
 *  - 解析完成时结果区从上方展开淡入
 *  - 批量解析进度条平滑推进
 *  - 分P 选中状态用颜色插值过渡
 */
@Composable
fun AddTaskScreen(vm: AppViewModel) {
    val c = AppTheme.colors
    var keyword by remember { mutableStateOf(vm.addTaskInput) }
    var advanced by remember { mutableStateOf(false) }
    var showCollection by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).page(),
    ) {
        Style.H1("新建下载任务")
        Text("输入B站视频链接，支持批量(空格分隔)", color = c.fgDim, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 8.dp))

        // 输入行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(Dimens.radiusMd))
                    .background(c.bgInput)
                    .border(1.dp, c.borderLight, RoundedCornerShape(Dimens.radiusMd))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (keyword.isEmpty()) {
                    Text("视频/番剧/课程链接，或 BV/av/ep/ss 号", color = c.fgDim, fontSize = 12.sp)
                }
                BasicTextField(
                    value = keyword,
                    onValueChange = { keyword = it; vm.updateAddTaskInput(it) },
                    singleLine = false,
                    maxLines = 3,
                    textStyle = TextStyle(color = c.fg, fontSize = 12.5.sp),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Style.Button(
                if (vm.addTaskLoading) "解析中" else "解析",
                onClick = { vm.parseAndLoad(keyword) },
                kind = Style.Kind.Primary,
                enabled = !vm.addTaskLoading && keyword.isNotBlank(),
            )
        }

        // 批量解析进度
        AnimatedVisibility(
            visible = vm.batchLoading,
            enter = expandVertically(tween(200)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                LoadingRow("正在批量解析…（已处理 ${vm.batchProgress} 个）")
                SmoothProgressBar(
                    progress = if (vm.batchProgress <= 0) 0f else (vm.batchProgress.toFloat() / (vm.batchProgress + 1)),
                )
            }
        }

        vm.addTaskError?.let { err ->
            ErrorRow(err, null, Modifier.padding(top = 10.dp))
        }

        // 单视频解析结果
        AnimatedVisibility(
            visible = vm.videoInfo != null,
            enter = expandVertically(tween(260)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
        ) {
            vm.videoInfo?.let { info ->
                Column(Modifier.padding(top = 14.dp)) {
                    Row {
                        Box(
                            Modifier.width(110.dp).aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(Dimens.radiusMd)).background(c.bgInput),
                        ) {
                            if (info.pic.isNotEmpty()) {
                                AsyncImage(
                                    model = normalizePic(info.pic), contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(info.title, color = c.fgBright, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (info.upperName.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(info.upperName, color = c.fgDim, fontSize = 11.sp)
                            }
                        }
                    }

                    // 合集横幅（原版 .collection-banner：检测到合集时显示入口）
                    vm.collectionInfo?.let { col ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.accent.copy(alpha = 0.12f))
                                .border(1.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("合集：${col.title}", color = c.fgBright, fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("共 ${vm.collectionVideos.size} 个视频", color = c.fgDim, fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp))
                            }
                            Style.Button("下载整个合集", { showCollection = true },
                                kind = Style.Kind.Primary, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // 分P 选择
                    if (info.pages.size > 1) {
                        SectionTitle("分P (${vm.selectedPages.size}/${info.pages.size})")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Style.Button("全选", { vm.selectAllPages(true, info.pages.map { it.index }) }, fontSize = 11.sp)
                            Style.Button("取消全选", { vm.selectAllPages(false, emptyList()) }, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        info.pages.forEach { p ->
                            val sel = vm.selectedPages.contains(p.index)
                            PressableBox(
                                onClick = { vm.togglePage(p.index) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(Dimens.radiusSm))
                                    .background(if (sel) c.bgActive else c.bgInput)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(14.dp).clip(RoundedCornerShape(4.dp))
                                            .background(if (sel) c.accent else c.pillBg)
                                            .border(1.dp, if (sel) c.accent else c.pillBorder, RoundedCornerShape(4.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) { if (sel) Text("✓", color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp) }
                                    Spacer(Modifier.width(8.dp))
                                    Text("P${p.index} ${p.title}", color = c.fg, fontSize = 11.5.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    // 下载模式
                    SectionTitle("内容")
                    Column {
                        DOWNLOAD_MODES.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(bottom = 6.dp)) {
                                row.forEach { (value, label) ->
                                    Pill(label, (vm.settings["downloadMode"] ?: "all") == value,
                                        { vm.saveSetting("downloadMode", value) })
                                }
                            }
                        }
                    }

                    // 编码
                    SectionTitle("视频编码")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CODECS.forEach { (value, label) ->
                            Pill(label, (vm.settings["preferCodec"] ?: "avc") == value,
                                { vm.saveSetting("preferCodec", value) })
                        }
                    }

                    // 流选择（已解析到具体流时）
                    vm.playInfo?.let { play ->
                        if (play.videos.isNotEmpty()) {
                            SectionTitle("视频流 (${play.videos.size})")
                            play.videos.take(8).forEach { v ->
                                val label = listOfNotNull(v.dfn, v.res, v.codecs, v.bandwidth.takeIf { it > 0 }?.let { "${it}kbps" }).joinToString(" · ")
                                val sel = vm.selectedVideoId == v.id
                                PressableBox(
                                    onClick = { vm.selectVideo(v.id) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(Dimens.radiusSm))
                                        .background(if (sel) c.bgActive else c.bgInput)
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                ) {
                                    Text(label, color = if (sel) c.accent else c.fg, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (play.audios.isNotEmpty()) {
                            SectionTitle("音频流 (${play.audios.size})")
                            play.audios.take(6).forEach { a ->
                                val label = listOfNotNull(a.codecs, a.bandwidth.takeIf { it > 0 }?.let { "${it}kbps" }).joinToString(" · ")
                                val sel = vm.selectedAudioId == a.id
                                PressableBox(
                                    onClick = { vm.selectAudio(a.id) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(Dimens.radiusSm))
                                        .background(if (sel) c.bgActive else c.bgInput)
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                ) {
                                    Text(label, color = if (sel) c.accent else c.fg, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // 高级选项
                    Spacer(Modifier.height(12.dp))
                    PressableBox(onClick = { advanced = !advanced }, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            (if (advanced) "▾ " else "▸ ") + "高级选项",
                            color = c.fgTitle, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                    AnimatedVisibility(
                        visible = advanced,
                        enter = expandVertically(tween(220)) + fadeIn(tween(160)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
                    ) {
                        Column {
                            listOf(
                                "skipSubtitle" to "跳过字幕",
                                "skipCover" to "跳过封面",
                                "skipAi" to "跳过AI字幕",
                                "skipMux" to "跳过混流",
                                "downloadDanmaku" to "附带弹幕",
                                "forceHttp" to "强制HTTP",
                            ).forEach { (key, label) ->
                                val on = vm.settings[key] == "true"
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(label, color = c.fg, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Pill(if (on) "开" else "关", on, { vm.saveSetting(key, if (on) "false" else "true") })
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Style.Button(
                        "开始下载",
                        { vm.submitDownload() },
                        kind = Style.Kind.Primary,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // 批量解析结果
        if (vm.batchResults.isNotEmpty() || vm.batchErrors.isNotEmpty()) {
            SectionTitle("批量解析结果：成功 ${vm.batchResults.size} 个" +
                if (vm.batchErrors.isNotEmpty()) "，失败 ${vm.batchErrors.size} 个" else "")
            vm.batchResults.forEachIndexed { i, item ->
                StaggeredItem(i) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(Dimens.radiusSm))
                            .background(c.cardBg).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("✓", color = c.success, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(item.title, color = c.fg, fontSize = 11.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
            vm.batchErrors.forEach { e ->
                Text("✗ $e", color = c.error, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
            Spacer(Modifier.height(14.dp))
            // 原版「批量下载全部」按钮（renderBatchFlow 的 btnAction）
            if (vm.batchResults.isNotEmpty()) {
                Style.Button(
                    "批量下载全部",
                    { vm.submitBatchDownload() },
                    kind = Style.Kind.Primary,
                    fill = true,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // 合集选择面板（原版 renderCollectionSelection）
        if (showCollection && vm.collectionInfo != null) {
            Spacer(Modifier.height(16.dp))
            Style.H2("选择合集视频")
            Text("合集：${vm.collectionInfo?.title} · 共 ${vm.collectionVideos.size} 个视频",
                color = c.fgDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Style.Button("全选", { vm.selectAllCollection(true) }, fontSize = 11.sp)
                Style.Button("取消全选", { vm.selectAllCollection(false) }, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text("${vm.collectionSelected.size}/${vm.collectionVideos.size} 已选",
                    color = c.fgDim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            vm.collectionVideos.forEachIndexed { i, v ->
                val sel = vm.collectionSelected.contains(v.bvid)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) c.bgActive else c.bgInput)
                        .clickable { vm.toggleCollectionItem(v.bvid) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(14.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (sel) c.accent else c.pillBg)
                            .border(1.dp, if (sel) c.accent else c.pillBorder, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) { if (sel) Text("✓", color = Color.White, fontSize = 10.sp) }
                    Spacer(Modifier.width(8.dp))
                    Text("${i + 1}. ${v.title}", color = c.fg, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
            Style.Button("下载选中视频 (${vm.collectionSelected.size})",
                { vm.submitCollectionDownload() }, kind = Style.Kind.Primary, fill = true, fontSize = 14.sp)
        }

        Spacer(Modifier.height(24.dp))
    }
}
