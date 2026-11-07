package com.bbdown.app.ui.screens

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bbdown.app.ui.AppViewModel
import com.bbdown.app.ui.FavFolderItem
import com.bbdown.app.ui.UpperItem
import com.bbdown.app.ui.components.AppButton
import com.bbdown.app.ui.components.ButtonKind
import com.bbdown.app.ui.components.EmptyState
import com.bbdown.app.ui.components.ErrorRow
import com.bbdown.app.ui.components.LoadingRow
import com.bbdown.app.ui.components.Pill
import com.bbdown.app.ui.components.PressableBox
import com.bbdown.app.ui.components.SectionTitle
import com.bbdown.app.ui.components.StaggeredItem
import com.bbdown.app.ui.components.StatusBadge
import com.bbdown.app.ui.theme.AppTheme
import com.bbdown.app.ui.theme.Spec
import com.bbdown.app.ui.theme.Dimens

/**
 * 账号页：双登录态展示、关注列表（分组/排序/分页）、收藏夹列表。
 * 动效：收藏夹 → 视频列表用左右转场；列表逐项淡入。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AccountScreen(vm: AppViewModel) {
    val c = AppTheme.colors

    // 首次进入账号页拉取一次数据（沿用 WebView 版的"进入即加载"语义）
    LaunchedEffect(vm.userId) {
        if (vm.userId.isNotEmpty()) {
            vm.loadFollowTags()
            if (vm.followings.items.isEmpty()) vm.loadFollowings(1, true)
            if (vm.favFolders.isEmpty()) vm.loadFavFolders()
        }
    }

    AnimatedContent(
        targetState = vm.currentFavFolder != null,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(200))) with fadeOut(tween(150))
            } else {
                fadeIn(tween(200)) with (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(150)))
            }
        },
        label = "accountFav",
    ) { inFav ->
        if (inFav) {
            vm.currentFavFolder?.let { FavVideoList(vm, it) }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = Dimens.screenPadding)) {
                item {
                    Text("账号管理", color = c.fgBright, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 14.dp))
                    Spacer(Modifier.height(12.dp))
                }

                // 登录状态卡
                item {
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(Dimens.radiusLg))
                            .background(c.cardBg)
                            .border(1.dp, c.cardBorder, RoundedCornerShape(Dimens.radiusLg))
                            .padding(Spec.UserCard.padding),
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(vm.userFace, vm.userName, 46.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        vm.userName.ifEmpty { if (vm.webLogin || vm.tvLogin) "已登录" else "未登录" },
                                        color = c.fgBright, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    )
                                    if (vm.userId.isNotEmpty()) {
                                        Text("UID: ${vm.userId}", color = c.fgDim, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        StatusBadge(if (vm.webLogin) "网页版" else "网页版未登录",
                                            if (vm.webLogin) c.success else c.fgDim)
                                        StatusBadge(if (vm.tvLogin) "TV版" else "TV版未登录",
                                            if (vm.tvLogin) c.success else c.fgDim)
                                    }
                                }
                            }
                            if (vm.webLogin || vm.tvLogin) {
                                Spacer(Modifier.height(12.dp))
                                AppButton("退出登录", { vm.logout(true) }, kind = ButtonKind.Danger, fontSize = 11)
                            } else {
                                Spacer(Modifier.height(12.dp))
                                Text("网页版登录已失效，请到 B站 App 重新扫码后回来刷新",
                                    color = c.warn, fontSize = 11.sp)
                                Spacer(Modifier.height(8.dp))
                                AppButton("刷新登录态", { vm.refreshLogin() }, kind = ButtonKind.Primary, fontSize = 11)
                            }
                        }
                    }
                }

                // 关注列表
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("我的关注", Modifier.weight(1f))
                        if (vm.followings.total > 0) {
                            Text("共 ${vm.followings.total} 个", color = c.fgDim, fontSize = 10.5.sp)
                        }
                    }
                }
                // 分组
                if (vm.followTags.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val visible = listOf(0 to "全部") + vm.followTags.take(4)
                            visible.forEach { (id, name) ->
                                Pill(name, vm.currentFollowTag == id, { vm.changeFollowTag(id) })
                            }
                        }
                    }
                }
                // 排序
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("最常访问", vm.followOrder == "attention", { vm.changeFollowOrder("attention") })
                        Pill("最近关注", vm.followOrder == "", { vm.changeFollowOrder("") })
                    }
                }
                when {
                    vm.followings.loading && vm.followings.items.isEmpty() -> item { LoadingRow("加载关注列表…") }
                    vm.followings.error != null && vm.followings.items.isEmpty() ->
                        item { ErrorRow(vm.followings.error!!, { vm.loadFollowings(1, true) }) }
                    vm.followings.items.isEmpty() -> item { Text("暂无关注", color = c.fgDim, fontSize = 11.5.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    else -> {
                        itemsIndexed(vm.followings.items, key = { _, u -> "f" + u.mid }) { i, u ->
                            StaggeredItem(i) { FollowItem(u) { vm.openUpper(u); vm.navigate(com.bbdown.app.ui.View.Search) } }
                        }
                        item {
                            Spacer(Modifier.height(6.dp))
                            if (vm.followings.hasMore) {
                                AppButton(
                                    "加载更多 (${vm.followings.items.size}/${vm.followings.total})",
                                    { vm.loadFollowings(vm.followings.page + 1, false) },
                                    modifier = Modifier.fillMaxWidth(), fontSize = 11,
                                    enabled = !vm.followings.loading,
                                )
                            } else if (vm.followings.total > 0) {
                                Text("已全部加载 (${vm.followings.total}个)", color = c.fgDim, fontSize = 10.5.sp,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                            }
                        }
                    }
                }

                // 收藏夹
                item { SectionTitle("我的收藏夹") }
                when {
                    vm.favFoldersLoading && vm.favFolders.isEmpty() -> item { LoadingRow("加载收藏夹…") }
                    vm.favFoldersError != null && vm.favFolders.isEmpty() ->
                        item { ErrorRow(vm.favFoldersError!!, { vm.loadFavFolders() }) }
                    vm.favFolders.isEmpty() -> item { Text("暂无收藏夹", color = c.fgDim, fontSize = 11.5.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    else -> itemsIndexed(vm.favFolders, key = { _, f -> "v" + f.id }) { i, f ->
                        StaggeredItem(i) { FavFolderCard(f) { vm.openFavFolder(f) } }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun FollowItem(u: UpperItem, onClick: () -> Unit) {
    val c = AppTheme.colors
    PressableBox(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(Spec.UpperItem.radius))
            .background(c.cardBg).padding(horizontal = Spec.UpperItem.padH, vertical = Spec.UpperItem.padV),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(u.face, u.uname, 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.uname, color = c.fg, fontSize = 12.5.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (u.special == 1) {
                        Spacer(Modifier.width(4.dp))
                        Text("★", color = c.warn, fontSize = 10.sp)
                    }
                }
                if (u.sign.isNotEmpty()) {
                    Text(u.sign, color = c.fgDim, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            if (u.fans > 0) {
                Text(fmtCount(u.fans) + "粉", color = c.fgDim, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun FavFolderCard(f: FavFolderItem, onClick: () -> Unit) {
    val c = AppTheme.colors
    PressableBox(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(Spec.FavFolderCard.radius))
            .background(c.cardBg).border(1.dp, c.cardBorder, RoundedCornerShape(Spec.FavFolderCard.radius))
            .padding(Spec.FavFolderCard.padding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(Dimens.radiusSm))
                    .background(c.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Text("★", color = c.accent, fontSize = 15.sp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(f.title, color = c.fg, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${f.mediaCount} 个视频", color = c.fgDim, fontSize = 10.5.sp)
            }
            Text("›", color = c.fgDim, fontSize = 18.sp)
        }
    }
}

/** 收藏夹内视频列表（带分页） */
@Composable
private fun FavVideoList(vm: AppViewModel, folder: FavFolderItem) {
    val c = AppTheme.colors
    val state = vm.favList
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.screenPadding)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton("← 返回", { vm.backFromFavFolder() }, kind = ButtonKind.Ghost, fontSize = 11)
            Spacer(Modifier.width(8.dp))
            Text(folder.title, color = c.fgBright, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            state.loading && state.items.isEmpty() -> LoadingRow("加载收藏夹视频…")
            state.error != null && state.items.isEmpty() -> ErrorRow(state.error, { vm.loadFavList(1) })
            state.items.isEmpty() -> EmptyState("该收藏夹暂无视频")
            else -> {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(state.items, key = { _, v -> v.bvid + v.title }) { i, v ->
                        StaggeredItem(i) { VideoCard(v) { vm.navigateToAddTaskWith(v.bvid) } }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        PaginationBar(
                            page = state.page,
                            totalPages = state.totalPages,
                            loading = state.loading,
                            onPrev = { vm.loadFavList(state.page - 1) },
                            onNext = { vm.loadFavList(state.page + 1) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}
