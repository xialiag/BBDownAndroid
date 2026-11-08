package com.bbdown.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.ContentScale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bbdown.app.core.DownloadTask
import com.bbdown.app.ui.icons.BBIcons
import com.bbdown.app.ui.components.ActivityIconButton
import com.bbdown.app.ui.components.AppToast
import com.bbdown.app.ui.components.SmoothProgressBar
import com.bbdown.app.ui.screens.AccountScreen
import com.bbdown.app.ui.screens.AddTaskScreen
import com.bbdown.app.ui.screens.ExplorerScreen
import com.bbdown.app.ui.screens.HelpScreen
import com.bbdown.app.ui.screens.SearchScreen
import com.bbdown.app.ui.screens.SettingsScreen
import com.bbdown.app.ui.components.PressableBox
import com.bbdown.app.ui.theme.AppTheme
import com.bbdown.app.ui.theme.Spec
import com.bbdown.app.ui.theme.Dimens

private data class NavItem(val view: View, val icon: ImageVector, val label: String)

// 图标统一用原版 SVG（BBIcons），不用 Material Icons —— 后者描边粗细与原版不一致
private val NAV_ITEMS = listOf(
    NavItem(View.Explorer, BBIcons.Explorer, "任务列表"),
    NavItem(View.AddTask, BBIcons.AddTask, "新建下载"),
    NavItem(View.Search, BBIcons.Search, "搜索"),
    NavItem(View.Account, BBIcons.Account, "账号"),
)

private val NAV_BOTTOM = listOf(
    NavItem(View.Help, BBIcons.Help, "说明"),
    NavItem(View.Settings, BBIcons.Settings, "设置"),
)

/**
 * 主界面骨架：标题栏 / 活动图标栏 / 侧栏 / 编辑区 / 状态栏。
 *
 * 动效说明：
 *  - 视图切换：AnimatedContent 做「横向滑入 + 淡入」，方向由导航顺序决定（往下走从右进，往上走从左进）
 *  - 侧栏：仅任务列表页展开，宽度变化用动画插值，避免突兀
 *  - 状态栏：粉底，进度摘要
 *  - Toast：底部滑入
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppRoot(
    vm: AppViewModel,
    onRequestAllFilesAccess: () -> Unit = {},
    onRequestBatteryExempt: () -> Unit = {},
) {
    val c = AppTheme.colors
    // 原版 JS: const narrow = window.innerWidth < 768 → layout-narrow
    val narrow = LocalConfiguration.current.screenWidthDp < Spec.Layout.narrowBreakpoint
    // .layout-narrow 时侧栏默认收起，点「任务列表」图标才全屏展开
    var showSidebar by remember { mutableStateOf(false) }
    // switchView 里 classList.remove('show-sidebar')：切视图自动收起
    LaunchedEffect(vm.currentView) { if (narrow) showSidebar = false }

    val sidebarVisible = if (narrow) showSidebar else true
    val editorVisible = !(narrow && showSidebar)   // .layout-narrow.show-sidebar #editor{display:none}

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            TitleBar(vm)
            Row(Modifier.weight(1f)) {
                ActivityBar(vm, narrow) {
                    if (narrow) showSidebar = !showSidebar
                }

                // 侧栏：#sidebar flex-basis 动画（窄屏 0↔100%，宽屏 240dp）
                val frac by animateFloatAsState(if (sidebarVisible) 1f else 0f, tween(200), label = "sidebarFrac")
                if (frac > 0.01f) {
                    val w = if (narrow) Spec.Layout.sidebarWidthNarrow else Spec.Layout.sidebarWidthWide
                    Box(
                        Modifier
                            .width(if (narrow) w * frac else w)
                            .fillMaxHeight()
                            .background(c.bgSidebar)
                            .alpha(frac),
                    ) { SidebarContent(vm) }
                }

                if (editorVisible) {
                    AnimatedContent(
                        targetState = vm.currentView,
                        transitionSpec = {
                            val forward = orderOf(targetState) >= orderOf(initialState)
                            val dir = if (forward) 1 else -1
                            (slideInHorizontally(tween(260)) { dir * it / 6 } + fadeIn(tween(200))) with
                                (slideOutHorizontally(tween(220)) { -dir * it / 8 } + fadeOut(tween(150)))
                        },
                        label = "viewSwitch",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { view ->
                        Box(Modifier.fillMaxSize()) {
                            when (view) {
                                View.Explorer -> ExplorerScreen(vm)
                                View.AddTask -> AddTaskScreen(vm)
                                View.Search -> SearchScreen(vm)
                                View.Account -> AccountScreen(vm)
                                View.Settings -> SettingsScreen(vm, onRequestAllFilesAccess, onRequestBatteryExempt)
                                View.Help -> HelpScreen(vm)
                            }
                        }
                    }
                }
            }
            StatusBar(vm)
        }
        AppToast(vm.statusMessage, vm.statusIsError) { vm.consumeToast() }
    }
}

private fun orderOf(v: View): Int = when (v) {
    View.Explorer -> 0
    View.AddTask -> 1
    View.Search -> 2
    View.Account -> 3
    View.Help -> 4
    View.Settings -> 5
}

@Composable
private fun TitleBar(vm: AppViewModel) {
    val c = AppTheme.colors
    // #titlebar{ height:38px; padding:0 10px; border-bottom:1px solid var(--border) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Spec.Layout.titlebarHeight)
            .background(c.bgTitlebar)
            .drawBehind {
                val t = 1.dp.toPx()
                drawLine(c.border, Offset(0f, size.height - t / 2), Offset(size.width, size.height - t / 2), t)
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // .logo{ width:24px; height:24px } —— 原版内联 SVG：粉底圆角 + 白色下载箭头
        Box(
            Modifier.size(Spec.Layout.titleLogo).clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFFAD4E0)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(c.accent),
                contentAlignment = Alignment.Center,
            ) { Text("↓", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.width(8.dp))
        // .title-text{ font-size:12px; letter-spacing:.3px; color:var(--fg-bright); font-weight:500 }
        Text(
            "BBDown — 视频下载器",
            color = c.fgBright,
            fontSize = Spec.Layout.titleFontSize,
            letterSpacing = Spec.Layout.titleLetterSpacing,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActivityBar(vm: AppViewModel, narrow: Boolean = false, onToggleSidebar: () -> Unit = {}) {
    val c = AppTheme.colors
    Column(
        Modifier
            .width(Spec.Layout.activitybarWidth)
            .fillMaxHeight()
            .background(c.bgActivity)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NAV_ITEMS.forEach { item ->
                ActivityIconButton(
                    selected = vm.currentView == item.view,
                    onClick = {
                        if (narrow && item.view == View.Explorer) onToggleSidebar()
                        else vm.navigate(item.view)
                    },
                ) { color -> Icon(item.icon, item.label, tint = color, modifier = Modifier.size(22.dp)) }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NAV_BOTTOM.forEach { item ->
                ActivityIconButton(
                    selected = vm.currentView == item.view,
                    onClick = {
                        if (narrow && item.view == View.Explorer) onToggleSidebar()
                        else vm.navigate(item.view)
                    },
                ) { color -> Icon(item.icon, item.label, tint = color, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}

/** 侧栏：任务列表摘要（对应 WebView 版 sidebar 的任务列表） */
@Composable
private fun SidebarContent(vm: AppViewModel) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxSize()) {
        // .sidebar-header{ height:35px; padding:0 16px; font-size:11px; font-weight:600;
        //                  letter-spacing:.6px; text-transform:uppercase; color:var(--fg-title) }
        Row(
            Modifier.fillMaxWidth().height(35.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "任务列表",
                color = c.fgTitle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp, modifier = Modifier.weight(1f),
            )
            Text("${vm.tasks.size}", color = c.fgDim, fontSize = 11.sp)
        }
        // .sidebar-body{ padding:4px 0 } + 任务卡片列表（与原版 explorer 完全相同的卡片）
        if (vm.tasks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                Text("暂无下载任务", color = c.fgDim, fontSize = 12.sp)
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize().padding(vertical = 4.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(vm.tasks.size) { i ->
                    val t = vm.tasks[i]
                    SidebarTaskCard(t, i + 1, vm.selectedTaskId == t.taskId) { vm.selectedTaskId = t.taskId }
                }
            }
        }
    }
}

/**
 * 侧栏任务卡片。与原版 explorer 的任务卡同构（封面背景 + #序号 + 标题 + 状态），
 * 只是尺寸更紧凑（侧栏 220dp 宽）。
 */
@Composable
private fun SidebarTaskCard(t: DownloadTask, index: Int, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val hasCover = t.pic.isNotEmpty()
    Box(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (hasCover) Modifier
                else Modifier.background(if (selected) c.bgListActive else c.cardBg)
                    .border(1.dp, c.cardBorder, RoundedCornerShape(12.dp))
            )
            .clickable { onClick() },
    ) {
        if (hasCover) {
            coil.compose.AsyncImage(
                model = com.bbdown.app.ui.screens.normalizePic(t.pic),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.42f)))
        }
        Box(Modifier.matchParentSize().padding(10.dp)) {
            Text("#$index", color = c.accent.copy(alpha = 0.6f), fontSize = 11.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart))
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(t.title, color = if (hasCover) Color.White else c.fg, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(
                    statusLabel(t.status) + if (t.isRunning) " · ${(t.progress * 100).roundToInt()}%" else "",
                    color = if (hasCover) Color.White.copy(alpha = 0.8f) else c.fgDim,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

private fun statusLabel(s: Int): String = when (s) {
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

@Composable
private fun SidebarTaskItem(task: DownloadTask, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) c.bgListActive else Color.Transparent,
        animationSpec = tween(180), label = "sideItemBg",
    )
    PressableBox(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                task.title,
                color = if (selected) c.fgBright else c.fg,
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            if (task.isRunning) {
                SmoothProgressBar(task.progress, height = 2.dp)
            } else {
                Text(
                    when (task.status) {
                        DownloadTask.STATUS_DONE -> "已完成"
                        DownloadTask.STATUS_FAILED -> "失败"
                        DownloadTask.STATUS_CANCELED -> "已取消"
                        DownloadTask.STATUS_PAUSED -> "已暂停"
                        else -> "等待中"
                    },
                    color = c.fgDim, fontSize = 9.5.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusBar(vm: AppViewModel) {
    val c = AppTheme.colors
    val running = vm.tasks.count { it.isRunning }
    val done = vm.tasks.count { it.status == DownloadTask.STATUS_DONE }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Spec.Layout.statusbarHeight)
            .background(c.bgStatusbar)
            .navigationBarsPadding()
            // CSS #statusbar{ padding:0 10px; gap:14px; font-size:11.5px }
            //            .sb-right{ margin-left:auto; opacity:.85 }
            .padding(horizontal = Spec.Layout.statusbarPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spec.Layout.statusbarGap),
    ) {
        // .sb-item{ display:flex; align-items:center; gap:5px }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier.size(5.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = if (vm.webLogin || vm.tvLogin) 1f else 0.45f))
            )
            Text(
                vm.userName.ifEmpty { if (vm.webLogin || vm.tvLogin) "已登录" else "未登录" },
                color = Color.White, fontSize = Spec.Layout.statusbarFontSize,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("$running 运行中 · $done 已完成", color = Color.White,
            fontSize = Spec.Layout.statusbarFontSize, maxLines = 1)
        Text("BBDown Android v${com.bbdown.app.BuildConfig.VERSION_NAME}",
            color = Color.White.copy(alpha = 0.85f), fontSize = Spec.Layout.statusbarFontSize,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
    }
}
