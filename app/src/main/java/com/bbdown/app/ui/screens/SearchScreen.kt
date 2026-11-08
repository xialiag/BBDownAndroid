package com.bbdown.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import com.bbdown.app.ui.icons.BBIcons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.bbdown.app.ui.UpperItem
import com.bbdown.app.ui.VideoItem
import com.bbdown.app.ui.components.EmptyState
import com.bbdown.app.ui.components.ErrorRow
import com.bbdown.app.ui.components.LoadingRow
import com.bbdown.app.ui.components.Pill
import com.bbdown.app.ui.components.PressableBox
import com.bbdown.app.ui.components.StaggeredItem
import com.bbdown.app.ui.style.Style
import com.bbdown.app.ui.style.page
import com.bbdown.app.ui.theme.AppTheme
import com.bbdown.app.ui.theme.Spec
import com.bbdown.app.ui.theme.Dimens

/**
 * 搜索页：UP主 / 视频 双模式，支持搜索结果点进 UP 投稿列表。
 * 动效：
 *  - 模式切换用 AnimatedContent 横向滑动
 *  - 结果列表逐项错峰淡入
 *  - 进入 UP 投稿 / 返回 有左右转场
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SearchScreen(vm: AppViewModel) {
    val c = AppTheme.colors
    var keyword by remember { mutableStateOf(vm.searchKeyword) }

    // UP 投稿列表 与 搜索结果 之间切换
    AnimatedContent(
        targetState = vm.currentUpper != null,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(200))) with fadeOut(tween(150))
            } else {
                fadeIn(tween(200)) with (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(150)))
            }
        },
        label = "searchUpper",
    ) { inUpper ->
        if (inUpper) {
            vm.currentUpper?.let { UpperVideoList(vm, it) }
        } else {
            Column(Modifier.fillMaxSize().page()) {
                Style.H1("搜索")
                // 原版：未登录时显示引导（.detail-empty 空状态 + 去登录按钮）
                if (!vm.webLogin && !vm.tvLogin) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            BBIcons.Account, contentDescription = null,
                            tint = c.fgDim.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("搜索功能需要登录B站账号", color = c.fg, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("登录后可搜索UP主和视频，并直接下载", color = c.fgDim, fontSize = 12.sp)
                        Spacer(Modifier.height(16.dp))
                        Style.Button("去登录", { vm.navigate(com.bbdown.app.ui.View.Account) },
                            kind = Style.Kind.Primary)
                    }
                    return@Column
                }
                Style.Lead("搜索B站UP主或视频，支持直接下载")

                // .search-type-tabs：整体容器 + 内部按钮（选中项 accent 底白字）
                Style.SegmentedTabs(
                    options = listOf("UP主", "视频"),
                    selected = vm.searchMode,
                    onSelect = { vm.changeSearchMode(it) },
                )

                // 输入行：input + 「搜索」按钮等高
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Style.Input(
                        value = keyword,
                        onValueChange = { keyword = it },
                        modifier = Modifier.weight(1f),
                        placeholder = if (vm.searchMode == 0) "输入UP主名称…" else "输入视频关键词…",
                        height = 44.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Style.Button(
                        "搜索", { vm.search(keyword) },
                        kind = Style.Kind.Primary,
                        enabled = !vm.searchLoading,
                        height = 44.dp,
                    )
                }
                Spacer(Modifier.height(10.dp))

                Spacer(Modifier.height(8.dp))
                AnimatedVisibility(visible = vm.searchLoading, enter = fadeIn(tween(150)), exit = fadeOut(tween(120))) {
                    LoadingRow("搜索中…")
                }
                vm.searchError?.let { ErrorRow(it, { vm.search(keyword) }) }

                if (!vm.searchLoading && vm.searchError == null) {
                    if (vm.searchMode == 0) {
                        UpperResultList(vm)
                    } else {
                        VideoResultList(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpperResultList(vm: AppViewModel) {
    val c = AppTheme.colors
    if (vm.upperResults.isEmpty()) {
        EmptyState("搜索UP主，查看投稿视频列表，支持直接下载")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("找到 ${vm.upperResults.size} 个UP主", color = c.fgDim, fontSize = 11.5.sp,
                modifier = Modifier.padding(vertical = 6.dp))
        }
        itemsIndexed(vm.upperResults, key = { _, u -> u.mid }) { i, u ->
            StaggeredItem(i) { UpperCard(u) { vm.openUpper(u) } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun UpperCard(u: UpperItem, onClick: () -> Unit) {
    val c = AppTheme.colors
    PressableBox(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Spec.UpperCard.radius))
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(Spec.UpperCard.radius))
            .padding(Spec.UpperCard.padding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(u.face, u.uname, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.uname, color = c.fgBright, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (u.officialType >= 0) {
                        Spacer(Modifier.width(4.dp))
                        Text("V", color = c.accent, fontSize = 9.sp,
                            modifier = Modifier.clip(CircleShape).background(c.accent.copy(alpha = 0.2f))
                                .padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                }
                Text("UID: ${u.mid}", color = c.fgDim, fontSize = 10.sp)
                if (u.sign.isNotEmpty()) {
                    Text(u.sign, color = c.fgDim, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtCount(u.fans) + "粉", color = c.fgDim, fontSize = 10.sp)
                Text("投稿 ${u.videoCount}", color = c.fgDim, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun VideoResultList(vm: AppViewModel) {
    val c = AppTheme.colors
    if (vm.videoResults.isEmpty()) {
        EmptyState("搜索视频，点击即可解析下载")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("找到 ${vm.videoResults.size} 个视频", color = c.fgDim, fontSize = 11.5.sp,
                modifier = Modifier.padding(vertical = 6.dp))
        }
        itemsIndexed(vm.videoResults, key = { _, v -> v.bvid }) { i, v ->
            StaggeredItem(i) {
                VideoCard(v) { vm.navigateToAddTaskWith(v.bvid) }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** UP 主投稿视频列表（带分页） */
@Composable
private fun UpperVideoList(vm: AppViewModel, upper: UpperItem) {
    val c = AppTheme.colors
    val state = vm.upperVideos
    Column(Modifier.fillMaxSize().page()) {
        // 顶部返回栏
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Style.Button("← 返回", { vm.backFromUpper() }, kind = Style.Kind.Ghost, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Text(upper.uname + " 的投稿", color = c.fgBright, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // 选择模式工具条 / 普通提示行（原版 renderUpperVideoList）
        if (vm.upperSelectMode) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("已选 ${vm.upperSelected.size} 个", color = c.fgDim, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Style.Button("全选本页", { vm.selectAllUpper(true) }, fontSize = 11.sp)
                Style.Button("取消", { vm.selectAllUpper(false) }, fontSize = 11.sp)
                Style.Button("下载选中", { vm.submitUpperDownload() },
                    kind = Style.Kind.Primary, fontSize = 11.sp)
                Style.Button("退出", { vm.toggleUpperSelectMode() }, fontSize = 11.sp)
            }
        } else {
            Text("${state.total} 个投稿 · 长按视频可批量选择", color = c.fgDim, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }

        when {
            state.loading && state.items.isEmpty() -> LoadingRow("正在加载投稿视频…")
            state.error != null && state.items.isEmpty() -> ErrorRow(state.error, { vm.loadUpperVideos(1) })
            state.items.isEmpty() -> EmptyState("该UP主暂无投稿视频")
            else -> {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(state.items, key = { _, v -> v.bvid + v.title }) { i, v ->
                        StaggeredItem(i) {
                            VideoCard(
                                v,
                                checked = if (vm.upperSelectMode) vm.upperSelected.contains(v.bvid) else null,
                                onLongClick = { if (!vm.upperSelectMode) { vm.toggleUpperSelectMode(); vm.toggleUpperItem(v.bvid) } },
                            ) {
                                if (vm.upperSelectMode) vm.toggleUpperItem(v.bvid)
                                else vm.navigateToAddTaskWith(v.bvid)
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        PaginationBar(
                            page = state.page,
                            totalPages = state.totalPages,
                            loading = state.loading,
                            onPrev = { vm.loadUpperVideos(state.page - 1) },
                            onNext = { vm.loadUpperVideos(state.page + 1) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

/** 分页条：上一页 / 第 X / Y 页 / 下一页 */
@Composable
internal fun PaginationBar(
    page: Int,
    totalPages: Int,
    loading: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val c = AppTheme.colors
    // CSS .fav-pagination{ gap:10px; margin-top:14px; justify-content:center }
    //     .page-info{ font-size:12px; color:var(--fg-dim) }
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page > 1) Style.Button("上一页", onPrev, fontSize = 12.sp, enabled = !loading)
        Text("第 $page / $totalPages 页", color = c.fgDim, fontSize = 12.sp)
        if (page < totalPages) Style.Button("下一页", onNext, fontSize = 12.sp, enabled = !loading)
    }
}

/** 视频卡片（投稿/收藏夹/搜索结果共用） */
@Composable
internal fun VideoCard(
    v: VideoItem,
    checked: Boolean? = null,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
) {
    val c = AppTheme.colors
    PressableBox(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Spec.VcItem.radius))
            .background(c.cardBg)
            .border(
                if (checked == true) 2.dp else 1.dp,
                if (checked == true) c.accent else c.cardBorder,
                RoundedCornerShape(Spec.VcItem.radius)
            )
            .padding(Spec.VcItem.padding),
    ) {
        Row {
            Box(
                Modifier.width(120.dp).aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(Dimens.radiusSm)).background(c.bgInput),
            ) {
                if (v.pic.isNotEmpty()) {
                    AsyncImage(
                        model = normalizePic(v.pic), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
                if (v.duration.isNotEmpty()) {
                    Text(
                        v.duration,
                        color = androidx.compose.ui.graphics.Color.White, fontSize = 9.5.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(v.title, color = c.fgBright, fontSize = Spec.VcItem.titleSize,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                if (v.ownerName.isNotEmpty()) {
                    Text(v.ownerName, color = c.fgDim, fontSize = 10.5.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(
                        v.play.takeIf { it > 0 }?.let { "播放 ${fmtCount(it)}" },
                        v.danmaku.takeIf { it > 0 }?.let { "弹幕 ${fmtCount(it)}" },
                    ).joinToString(" · "),
                    color = c.fgDim, fontSize = 10.sp,
                )
            }
        }
    }
}

/** 头像：无图时用首字占位，避免空白方块 */
@Composable
internal fun Avatar(face: String, name: String, size: androidx.compose.ui.unit.Dp) {
    val c = AppTheme.colors
    Box(
        Modifier.size(size).clip(CircleShape).background(c.bgInput),
        contentAlignment = Alignment.Center,
    ) {
        if (face.isNotEmpty()) {
            AsyncImage(
                model = normalizePic(face), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            Text(
                name.take(1).ifEmpty { "?" },
                color = c.fgDim, fontSize = (size.value / 2.2f).sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 数字格式化：1.2万 / 1.5亿 */
internal fun fmtCount(n: Int): String = when {
    n <= 0 -> "0"
    n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
    n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
    else -> n.toString()
}
