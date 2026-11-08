package com.bbdown.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import coil.compose.AsyncImage
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bbdown.app.ui.AppViewModel
import com.bbdown.app.ui.FavFolderItem
import com.bbdown.app.ui.UpperItem
import com.bbdown.app.ui.components.EmptyState
import com.bbdown.app.ui.components.ErrorRow
import com.bbdown.app.ui.components.LoadingRow
import com.bbdown.app.ui.components.Pill
import com.bbdown.app.ui.components.PressableBox
import com.bbdown.app.ui.components.SectionTitle
import com.bbdown.app.ui.components.StaggeredItem
import com.bbdown.app.ui.components.StatusBadge
import com.bbdown.app.ui.style.Style
import com.bbdown.app.ui.style.page
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
    val anyLogin = vm.webLogin || vm.tvLogin

    // 原版三视图：未登录 → 二维码；已登录 → 主视图；manage → 授权管理
    when {
        vm.loginView == "manage" && anyLogin -> { AuthManageView(vm); return }
        !anyLogin -> { LoginView(vm); return }
    }

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
            LazyColumn(Modifier.fillMaxSize().page()) {
                item {
                    Style.H1("账号管理")
                    Spacer(Modifier.height(12.dp))
                }

                // 登录状态卡
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            // CSS .login-section{ background:card-bg; border:1px solid card-border;
                            //                     border-radius:14px; padding:20px }
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.cardBg)
                            .border(1.dp, c.cardBorder, RoundedCornerShape(14.dp))
                            .padding(20.dp),
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(vm.userFace, vm.userName, 46.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        vm.userName.ifEmpty { if (vm.webLogin || vm.tvLogin) "已登录" else "未登录" },
                                        color = c.fgBright, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                    )
                                    if (vm.userId.isNotEmpty()) {
                                        Text("UID: ${vm.userId}", color = c.fgDim, fontSize = 11.5.sp, fontFamily = com.bbdown.app.ui.theme.MonoFamily)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        LoginPill("网页版", vm.webLogin)
                                        LoginPill("TV版", vm.tvLogin)
                                    }
                                }
                            }
                            if (vm.webLogin || vm.tvLogin) {
                                Spacer(Modifier.height(12.dp))
                                Style.Button("退出登录", { vm.logout(true) }, kind = Style.Kind.Danger, fontSize = 11.sp)
                            } else {
                                Spacer(Modifier.height(12.dp))
                                Text("网页版登录已失效，请到 B站 App 重新扫码后回来刷新",
                                    color = c.warn, fontSize = 11.sp)
                                Spacer(Modifier.height(8.dp))
                                Style.Button("刷新登录态", { vm.refreshLogin() }, kind = Style.Kind.Primary, fontSize = 11.sp)
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
                                FollowTag(name, vm.currentFollowTag == id) { vm.changeFollowTag(id) }
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
                                Style.Button(
                                    "加载更多 (${vm.followings.items.size}/${vm.followings.total})",
                                    { vm.loadFollowings(vm.followings.page + 1, false) },
                                    modifier = Modifier.fillMaxWidth(), fontSize = 11.sp,
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
                    else -> item {
                        // CSS .fav-folder-grid{ display:grid; grid-template-columns:1fr 1fr; gap:8px }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.favFolders.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { f ->
                                        Box(Modifier.weight(1f)) { FavFolderCard(f) { vm.openFavFolder(f) } }
                                    }
                                    if (row.size == 1) Box(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * 关注项。CSS：
 *   .upper-item{ gap:10px; padding:8px 10px; border:1px solid card-border; radius:10px }
 *   .upper-sign{ font-size:11px; color:fg-dim; flex:1 }
 *   .upper-fans{ font-size:10.5px; color:fg-title }
 *   .upper-arrow{ opacity:.4; color:fg-dim }   // 右侧 › 箭头
 *   .vip-name{ color:#FB7299 }                  // 大会员粉色名字
 *   .special-star{ color:#FFB027; font-size:11px; margin-left:2px }
 */
@Composable
private fun FollowItem(u: UpperItem, onClick: () -> Unit) {
    val c = AppTheme.colors
    val isVip = u.vipType > 0 && u.vipStatus == 1
    PressableBox(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Spec.UpperItem.radius))
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(Spec.UpperItem.radius))
            .padding(horizontal = Spec.UpperItem.padH, vertical = Spec.UpperItem.padV),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(u.face, u.uname, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        u.uname,
                        color = if (isVip) Color(0xFFFB7299) else c.fg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (u.special == 1) {
                        Text("★", color = Color(0xFFFFB027), fontSize = 11.sp,
                            modifier = Modifier.padding(start = 2.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        u.sign.ifEmpty { "" },
                        color = c.fgDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (u.fans > 0) {
                        Text("${fmtCount(u.fans)}粉", color = c.fgTitle, fontSize = 10.5.sp,
                            modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            // 右侧 › 箭头（opacity .4）
            Text("›", color = c.fgDim.copy(alpha = 0.4f), fontSize = 16.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun FavFolderCard(f: FavFolderItem, onClick: () -> Unit) {
    val c = AppTheme.colors
    PressableBox(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.cardBg).border(1.dp, c.cardBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // .ff-icon 28x28 圆角 8，accent-btn 实底白图标
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(c.accentBtn),
                contentAlignment = Alignment.Center,
            ) { Text("★", color = Color.White, fontSize = 13.sp) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(f.title, color = c.fg, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${f.mediaCount} 个视频", color = c.fgDim, fontSize = 10.5.sp)
            }
        }
    }
}

/** 关注分组标签。CSS `.follow-tag-item{ padding:4px 10px; border-radius:14px; border:1px solid card-border }` */
@Composable
private fun FollowTag(label: String, active: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) c.accent else c.cardBg)
            .border(1.dp, if (active) c.accent else c.cardBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, color = if (active) Color.White else c.fgDim, fontSize = 12.sp)
    }
}

/** 收藏夹内视频列表（带分页） */
@Composable
private fun FavVideoList(vm: AppViewModel, folder: FavFolderItem) {
    val c = AppTheme.colors
    val state = vm.favList
    Column(Modifier.fillMaxSize().page()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Style.Button("← 返回", { vm.backFromFavFolder() }, kind = Style.Kind.Ghost, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Text(folder.title, color = c.fgBright, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // 原版 downloadAllFav：一键下载整个收藏夹
            Style.Button("下载全部", { vm.downloadAllFav() },
                kind = Style.Kind.Primary, fontSize = 11.sp)
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

/**
 * 登录态徽标。CSS：
 *   .ls-pill{ display:inline-flex; align-items:center; gap:4px; font-size:11px; font-weight:500;
 *             padding:2px 9px; border-radius:10px; color:fg-dim;
 *             background:pill-bg; border:1px solid pill-border }
 *   .ls-pill .ls-pill-dot{ width:6px; height:6px; border-radius:50%; background:fg-dim; opacity:.5 }
 *   .ls-pill.on{ color:success; background:badge-done-bg; border-color:rgba(137,209,133,.3) }
 */
@Composable
private fun LoginPill(label: String, on: Boolean) {
    val c = AppTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) c.badgeDoneBg else c.pillBg)
            .border(1.dp, if (on) c.success.copy(alpha = 0.3f) else c.pillBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape)
                .background(if (on) c.success else c.fgDim.copy(alpha = 0.5f))
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (on) label else "$label 未登录",
            color = if (on) c.success else c.fgDim,
            fontSize = 11.sp, fontWeight = FontWeight.Medium,
        )
    }
}

// ==================== 登录视图（未登录） ====================

/**
 * 未登录时的二维码登录页。CSS：
 *   .login-section{ background:card-bg; border:1px solid card-border; border-radius:14px; padding:20px }
 *   .qr-login-box{ display:flex; flex-direction:column; align-items:center; padding:20px 16px }
 *   .qr-desc{ font-size:12.5px; color:fg-dim; margin-bottom:20px; text-align:center }
 *   .login-switch-hint{ text-align:center; padding:14px 0 2px; font-size:12px; color:fg-dim }
 *   .login-switch-link{ color:accent; font-weight:500 }
 */
@Composable
private fun LoginView(vm: AppViewModel) {
    val c = AppTheme.colors
    val mode = vm.loginMode
    LaunchedEffect(mode) { vm.startQrLogin(mode) }

    Column(Modifier.fillMaxSize().page()) {
        Style.H1("账号管理")
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.cardBg)
                .border(1.dp, c.cardBorder, RoundedCornerShape(14.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (mode == "web") "使用哔哩哔哩手机APP扫码登录网页版"
                else "使用哔哩哔哩手机APP扫码授权TV版",
                color = c.fgDim, fontSize = 12.5.sp,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            // 二维码 240x240
            Box(
                Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                if (vm.qrImage.isNotEmpty()) {
                    AsyncImage(
                        model = vm.qrImage, contentDescription = "登录二维码",
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = c.accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("生成二维码中…", color = c.fgDim, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(vm.qrStatus, color = if (vm.qrStatus.contains("成功")) c.success else c.fgDim, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Style.Button("刷新二维码", { vm.startQrLogin(vm.loginMode) }, kind = Style.Kind.Primary)
        }
        // .login-switch-hint
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                "使用${if (mode == "web") "TV版授权" else "网页版登录"}登录 →",
                color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { vm.switchLoginMode() },
            )
        }
    }
}

// ==================== 授权管理视图 ====================

@Composable
private fun AuthManageView(vm: AppViewModel) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxSize().page()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("← 返回", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { vm.changeLoginView("main") }.padding(end = 10.dp))
            Style.H1("授权管理")
        }
        Spacer(Modifier.height(12.dp))
        AuthManageItem("网页版登录", vm.webLogin, "已登录", "未登录",
            onAuth = { vm.loginMode = "web"; vm.changeLoginView("auth") },
            onCancel = { vm.logout(false) })
        Spacer(Modifier.height(8.dp))
        AuthManageItem("TV版授权", vm.tvLogin, "已授权", "未授权",
            onAuth = { vm.loginMode = "tv"; vm.changeLoginView("auth") },
            onCancel = { vm.logout(true) })
    }
}

@Composable
private fun AuthManageItem(
    title: String, active: Boolean, okText: String, noText: String,
    onAuth: () -> Unit, onCancel: () -> Unit,
) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.cardBg)
            .border(1.dp, if (active) c.accent.copy(alpha = 0.4f) else c.cardBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.fgBright, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                if (active) okText else noText,
                color = if (active) c.success else c.fgDim,
                fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (active) Style.Button("取消授权", onCancel, kind = Style.Kind.Danger, fontSize = 12.sp)
        else Style.Button("去授权", onAuth, fontSize = 12.sp)
    }
}
