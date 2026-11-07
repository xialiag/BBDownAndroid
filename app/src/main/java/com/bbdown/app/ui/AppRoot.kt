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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bbdown.app.core.DownloadTask
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
import com.bbdown.app.ui.theme.Dimens

private data class NavItem(val view: View, val icon: ImageVector, val label: String)

private val NAV_ITEMS = listOf(
    NavItem(View.Explorer, Icons.Filled.List, "任务列表"),
    NavItem(View.AddTask, Icons.Filled.Add, "新建下载"),
    NavItem(View.Search, Icons.Filled.Search, "搜索"),
    NavItem(View.Account, Icons.Filled.Person, "账号"),
)

private val NAV_BOTTOM = listOf(
    NavItem(View.Help, Icons.Filled.Info, "说明"),
    NavItem(View.Settings, Icons.Filled.Settings, "设置"),
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
fun AppRoot(vm: AppViewModel, onRequestAllFilesAccess: () -> Unit = {}) {
    val c = AppTheme.colors
    var lastIndex by remember { mutableStateOf(0) }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TitleBar(vm)

            Row(Modifier.weight(1f)) {
                ActivityBar(vm)
                // 侧栏只在任务列表视图展开（对应 WebView 版 240px 侧栏）
                val sidebarExpanded = vm.currentView == View.Explorer
                val sidebarWidth by animateFloatAsState(
                    targetValue = if (sidebarExpanded) 1f else 0f,
                    animationSpec = tween(240),
                    label = "sidebarWidth",
                )
                if (sidebarWidth > 0.01f) {
                    Box(
                        Modifier
                            .width(Dimens.sidebarWidth * sidebarWidth)
                            .fillMaxHeight()
                            .background(c.bgSidebar)
                            .alpha(sidebarWidth),
                    ) {
                        if (sidebarExpanded) SidebarContent(vm)
                    }
                }

                // 主编辑区：视图切换动画
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
                            View.Settings -> SettingsScreen(vm, onRequestAllFilesAccess)
                            View.Help -> HelpScreen(vm)
                        }
                    }
                }
            }

            StatusBar(vm)
        }

        // Toast 浮在最上层
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
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.titleBarHeight)
            .background(c.bgTitlebar)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // logo
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(c.accent.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) { Text("↓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(8.dp))
        Text(
            "BBDown — 视频下载器",
            color = c.fgBright,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 主题切换（带旋转反馈）
        PressableBox(
            onClick = { vm.toggleTheme() },
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(Dimens.radiusMd)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (vm.darkTheme) "☾" else "☀", color = c.fgDim, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ActivityBar(vm: AppViewModel) {
    val c = AppTheme.colors
    Column(
        Modifier
            .width(Dimens.activityBarWidth)
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
                    onClick = { vm.navigate(item.view) },
                ) { color -> Icon(item.icon, item.label, tint = color, modifier = Modifier.size(22.dp)) }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NAV_BOTTOM.forEach { item ->
                ActivityIconButton(
                    selected = vm.currentView == item.view,
                    onClick = { vm.navigate(item.view) },
                ) { color -> Icon(item.icon, item.label, tint = color, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}

/** 侧栏：任务列表摘要（对应 WebView 版 sidebar 的任务列表） */
@Composable
private fun SidebarContent(vm: AppViewModel) {
    val c = AppTheme.colors
    val tasks = vm.tasks
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.tabsHeight).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("任务列表", color = c.fgTitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${tasks.size}", color = c.fgDim, fontSize = 10.5.sp)
        }
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                Text("暂无下载任务", color = c.fgDim, fontSize = 11.5.sp)
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn {
                items(tasks.size) { i ->
                    val t = tasks[i]
                    SidebarTaskItem(t, selected = vm.selectedTaskId == t.taskId) {
                        vm.selectedTaskId = t.taskId
                    }
                }
            }
        }
    }
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
            .height(Dimens.statusBarHeight)
            .background(c.bgStatusbar)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 登录状态点：未登录=半透明白，已登录=实心
        Box(
            Modifier.size(5.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = if (vm.webLogin || vm.tvLogin) 1f else 0.45f))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            vm.userName.ifEmpty { if (vm.webLogin || vm.tvLogin) "已登录" else "未登录" },
            color = Color.White, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text("$running 运行中 · $done 已完成", color = Color.White, fontSize = 10.5.sp)
        Spacer(Modifier.weight(1f))
        Text("Compose 版", color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
    }
}
