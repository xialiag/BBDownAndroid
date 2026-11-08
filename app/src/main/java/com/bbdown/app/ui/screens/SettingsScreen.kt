package com.bbdown.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bbdown.app.ui.AppViewModel
import com.bbdown.app.ui.components.Pill
import com.bbdown.app.ui.components.SectionTitle
import com.bbdown.app.ui.style.Style
import com.bbdown.app.ui.style.page
import com.bbdown.app.ui.theme.AppTheme
import com.bbdown.app.ui.theme.Dimens

private val THREAD_OPTIONS = listOf("4", "8", "16")

private val MODE_LABELS = listOf(
    "all" to "完整下载",
    "video_only" to "仅视频",
    "audio_only" to "仅音频",
    "subtitle_only" to "仅字幕",
    "cover_only" to "仅封面",
    "danmaku_only" to "仅弹幕",
)

/**
 * 设置页。所有修改即时保存（与 WebView 版 onchange 即 saveSetting 一致）。
 * 动效：展开的调试面板用 expandVertically；开关项用 Pill 颜色插值。
 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onRequestAllFilesAccess: () -> Unit = {},
    onRequestBatteryExempt: () -> Unit = {},
) {
    val c = AppTheme.colors
    var showDebug by remember { mutableStateOf(false) }
    var showCrash by remember { mutableStateOf(false) }
    // 原版 input value="${s.output_dir||''}" —— 预填实际路径
    var outDirDraft by remember { mutableStateOf(vm.settings["output_dir"] ?: "") }

    LaunchedEffect(showDebug) {
        if (showDebug) vm.loadDebugLogs()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Style.H1("设置")

        // ---------- 主题模式（原版 3 段：深色/浅色/跟随系统）----------
        // .theme-toggle{ display:flex; align-items:center; justify-content:space-between;
        //                padding:12px 0; border-bottom:1px solid border-light; margin-bottom:8px }
        Row(
            Modifier.fillMaxWidth()
                .drawBehind {
                    val t = 1.dp.toPx()
                    drawLine(c.borderLight, Offset(0f, size.height - t / 2), Offset(size.width, size.height - t / 2), t)
                }
                .padding(vertical = 12.dp, horizontal = 0.dp)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("主题模式", color = c.fg, fontSize = 13.sp)          // .tt-label
                Text("切换深色/浅色外观", color = c.fgDim, fontSize = 11.sp,  // .tt-desc
                    modifier = Modifier.padding(top = 2.dp))
            }
            // .theme-switch{ gap:4px; background:pill-bg; border:1px solid pill-border; radius:10px; padding:3px }
            val themes = listOf("dark" to "深色", "light" to "浅色", "system" to "跟随系统")
            val cur = vm.settings["theme"] ?: "dark"
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(c.pillBg)
                    .border(1.dp, c.pillBorder, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                themes.forEach { (v, label) ->
                    val active = cur == v
                    Text(
                        label,
                        color = if (active) Color.White else c.fgDim,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) c.accentBtn else Color.Transparent)
                            .clickable { vm.saveSetting("theme", v) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // ---------- 下载目录（输入框 + 检查权限）----------
        FieldLabel("下载目录")
        Style.Input(value = outDirDraft, onValueChange = { outDirDraft = it; vm.saveSetting("output_dir", it) },
            placeholder = "应用私有目录")
        Spacer(Modifier.height(6.dp))
        Text(
            "默认使用应用私有存储（无需权限）。如需保存到公共目录（如 Movies），需授予「所有文件访问权限」",
            color = c.fgDim, fontSize = 11.sp, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(10.dp))
        // 权限行：左侧状态 + 右侧授权按钮（原版 .opt-item 结构）
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(c.cardBg).border(1.dp, c.cardBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("文件访问权限", color = c.fg, fontSize = 13.sp)
                Text(
                    if (vm.storageGranted) "已授权，可写入公共目录" else "检查失败（未授权，使用私有目录）",
                    color = if (vm.storageGranted) c.success else c.error,
                    fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Style.Button("授予权限", onRequestAllFilesAccess, kind = Style.Kind.Secondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        // 电池优化行（原版 .opt-item）
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(c.cardBg).border(1.dp, c.cardBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("电池优化", color = c.fg, fontSize = 13.sp)
                Text("已加入白名单，后台下载不被杀", color = c.fgDim, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Style.Button("打开设置", onRequestBatteryExempt, kind = Style.Kind.Secondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))

        // ---------- 线程数（原版分段控件 4/8/16）----------
        FieldLabel("线程数")
        Style.SegmentedTabs(
            options = listOf("4", "8", "16"),
            selected = when (vm.settings["threads"]) { "4" -> 0; "16" -> 2; else -> 1 },
            onSelect = { vm.saveSetting("threads", listOf("4", "8", "16")[it]) },
        )

        // ---------- 下载内容 ----------
        FieldLabel("默认模式")
        Style.SelectBox(
            options = MODE_LABELS.map { it.second },
            selectedIndex = MODE_LABELS.indexOfFirst { it.first == (vm.settings["downloadMode"] ?: "all") }.coerceAtLeast(0),
            onSelect = { vm.saveSetting("downloadMode", MODE_LABELS[it].first) },
        )

        // 批量清晰度（原版下拉：自动 / 固定 8K / 4K / 1080P）
        FieldLabel("批量清晰度")
        Style.SelectBox(
            options = listOf("自动（每视频取最高）", "8K", "4K", "1080P", "720P"),
            selectedIndex = 0,
            onSelect = { },
        )

        // ---------- 编码优先级 ----------
        FieldLabel("编码优先级")
        Style.SegmentedTabs(
            options = listOf("AVC", "HEVC", "AV1"),
            selected = when (vm.settings["preferCodec"]) { "hevc" -> 1; "av1" -> 2; else -> 0 },
            onSelect = { vm.saveSetting("preferCodec", listOf("avc", "hevc", "av1")[it]) },
        )

        // ---------- 文件名模板 ----------
        SectionTitle("文件名模板")
        PatternRow(vm, "单P", "filePattern", "{pageTitle}")
        PatternRow(vm, "多P", "filePatternMultiPage", "{pageTitle} P{pageNumber}")
        PatternRow(vm, "合集", "filePatternCollection", "{collectionIndex}. {pageTitle}")
        PatternRow(vm, "合集多P", "filePatternCollectionMultiPage", "{collectionIndex}. {videoTitle} P{pageNumber}")
        Text(
            "可用变量：{videoTitle} {pageTitle} {pageNumber} {collectionIndex} {bvid} {ownerMid}",
            color = c.fgDim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        // ---------- 开关 ----------
        SectionTitle("选项")
        listOf(
            "skipSubtitle" to "跳过字幕",
            "skipCover" to "跳过封面",
            "skipAi" to "跳过AI字幕",
            "skipMux" to "跳过混流",
            "downloadDanmaku" to "附带弹幕",
            "videoAscending" to "视频升序",
            "audioAscending" to "音频升序",
            "forceHttp" to "强制HTTP",
            "check_update" to "检查更新",
        ).forEach { (key, label) ->
            // .switch-row：真开关滑块，不是"开/关"文字胶囊
            Style.SwitchRow(
                label = label,
                checked = vm.settings[key] == "true",
                onCheckedChange = { vm.saveSetting(key, if (it) "true" else "false") },
            )
        }

        // ---------- 调试 ----------
        SectionTitle("调试")
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("调试服务器", color = c.fg, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
            Style.Switch(
                checked = vm.settings["debug_server"] == "true",
                onCheckedChange = { vm.saveSetting("debug_server", if (it) "true" else "false") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            Style.Button(if (showDebug) "收起日志" else "查看日志", { showDebug = !showDebug }, fontSize = 11.sp)
            if (showDebug) Style.Button("清空", { vm.clearDebugLogs() }, kind = Style.Kind.Danger, fontSize = 11.sp)
        }

        AnimatedVisibility(
            visible = showDebug,
            enter = expandVertically(tween(240)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(140)),
        ) {
            Box(
                Modifier.fillMaxWidth().heightIn(max = 380.dp)
                    .clip(RoundedCornerShape(Dimens.radiusMd))
                    .background(c.bgInput)
                    .padding(10.dp),
            ) {
                Text(
                    vm.debugLogs.ifEmpty { "（暂无日志）" },
                    color = c.fgDim,
                    fontSize = 9.5.sp,
                    fontFamily = com.bbdown.app.ui.theme.MonoFamily,
                    lineHeight = 13.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        // ---------- 崩溃日志（原版 h2 崩溃日志 + 查看崩溃日志 按钮）----------
        Style.H2("崩溃日志")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Style.Button("查看崩溃日志", {
                showCrash = !showCrash
                if (showCrash) vm.loadCrashLogs()
            }, fontSize = 12.sp)
            if (showCrash && vm.crashLogs.isNotEmpty()) {
                Style.Button("清空", { vm.clearCrashLogs() }, kind = Style.Kind.Danger, fontSize = 12.sp)
            }
        }
        if (showCrash) {
            Spacer(Modifier.height(8.dp))
            if (vm.crashLogs.isEmpty()) {
                Text("暂无崩溃日志", color = c.fgDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                vm.crashLogs.forEach { (name, size, mtime) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp)).background(c.cardBg)
                            .border(1.dp, c.cardBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, color = c.fg, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${size / 1024} KB · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(mtime))}",
                                color = c.fgDim, fontSize = 10.sp)
                        }
                        Style.Button("删除", { vm.deleteCrashLog(name) }, kind = Style.Kind.Ghost, fontSize = 11.sp)
                    }
                }
            }
        }

        // ---------- 关于 ----------
        SectionTitle("关于")
        Text("BBDown — B站视频下载器", color = c.fg, fontSize = 12.sp)
        Text("基于 BBDown 移植 · Jetpack Compose 重写", color = c.fgDim, fontSize = 10.5.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 24.dp))
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = c.fgDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
        content()
    }
}

/** 文件名模板输入行：失焦即保存 */
@Composable
private fun PatternRow(vm: AppViewModel, label: String, key: String, default: String) {
    val c = AppTheme.colors
    var text by remember(key) { mutableStateOf(vm.settings[key] ?: default) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = c.fgDim, fontSize = 10.5.sp, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it; vm.saveSetting(key, it) },
            singleLine = true,
            textStyle = TextStyle(color = c.fg, fontSize = 11.sp),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.radiusSm))
                .background(c.bgInput)
                .border(1.dp, c.borderLight, RoundedCornerShape(Dimens.radiusSm))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** 帮助页：静态说明 */
@Composable
fun HelpScreen(vm: AppViewModel) {
    val c = AppTheme.colors
    // 原版帮助页：h2 分区 + 逐行 p，无卡片包裹
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).page()) {
        Style.H1("使用说明")

        HelpSection("支持的链接", listOf(
            "· 视频: https://www.bilibili.com/video/BV1xxx 或 https://b23.tv/xxx 短链",
            "· 番剧: https://www.bilibili.com/bangumi/play/epxxx(/ssxxx) 或 /bangumi/media/mdxxx",
            "· 课程: https://www.bilibili.com/cheese/play/epxxx(/ssxxx)",
            "· 纯ID: BV1xxx, av12345, ep12345, ss1234, md12345",
        ))
        HelpSection("下载模式", listOf(
            "· 完整下载: 视频+音频合并+字幕+封面+弹幕",
            "· 仅视频: 视频+音频合并(不含字幕/弹幕等附加)",
            "· 仅音频: 只下载音频流(m4a)",
            "· 仅字幕: 下载字幕文件(srt)",
            "· 仅封面: 下载封面图片",
            "· 仅弹幕: 下载弹幕文件(xml)",
        ))
        HelpSection("批量下载", listOf(
            "· 在输入框中用空格分割多个链接",
            "· 例: BV1xxx BV2yyy BV3zzz",
            "· 解析后会显示所有视频，一键批量下载",
        ))
        HelpSection("登录授权", listOf(
            "· 点击左侧「账号」图标进入登录页",
            "· 网页版登录: 使用哔哩哔哩APP扫码，可下载大会员内容",
            "· TV版授权: 使用TV版二维码授权，获取TV端access_token",
            "· 两种登录可同时使用，互不影响",
        ))
        HelpSection("API类型", listOf(
            "· 在设置中切换API类型: WEB / TV / APP / 国际版",
            "· 不同API类型可能获取不同的下载地址和清晰度",
            "· 遇到下载失败时可尝试切换API类型",
        ))
        HelpSection("搜索", listOf(
            "· 点击左侧「搜索」图标",
            "· 需先登录B站账号",
            "· 切换「UP主」或「视频」搜索类型",
            "· 搜索UP主可查看投稿视频列表",
            "· 搜索视频可直接点击下载",
        ))
        HelpSection("收藏夹下载", listOf(
            "· 登录后在账号页查看收藏夹列表",
            "· 点击收藏夹查看视频",
            "· 勾选视频后一键批量下载",
        ))
        HelpSection("关注列表", listOf(
            "· 登录后在账号页查看已关注的UP主",
            "· 点击UP主查看其投稿视频",
            "· 可直接下载投稿视频",
        ))
        HelpSection("合集下载", listOf(
            "· 解析视频时自动检测是否属于合集",
            "· 检测到合集后显示「下载整个合集」按钮",
            "· 合集内容会下载到以合集名命名的子文件夹",
        ))
        HelpSection("任务管理", listOf(
            "· 左右滑动任务卡进入管理模式",
            "· 管理模式下再次滑动任务，自动选中两次滑动之间的所有任务",
            "· 点击任务可单独切换选中，全选按钮可一键选择",
            "· 支持批量暂停、继续/重试、删除",
            "· 支持按状态筛选: 全部 / 进行中 / 已完成 / 失败",
        ))
        HelpSection("日志管理", listOf(
            "· 设置中点击「查看调试日志」查看运行日志",
            "· 设置中点击「查看崩溃日志」查看闪退记录",
            "· 崩溃日志支持单个分享和删除",
            "· 遇到问题时可分享日志文件便于排查",
        ))
        HelpSection("存储说明", listOf(
            "· 默认下载到应用私有目录(Movies/BBDown)",
            "· 在设置中可自定义输出目录",
            "· 如需保存到公共目录，需授予「所有文件访问权限」",
            "· 支持自定义文件命名模板，四种类型独立设置",
        ))
        HelpSection("其他设置", listOf(
            "· 主题: 深色 / 浅色 / 跟随系统",
            "· 下载线程数: 默认8线程,可调4/16",
            "· 视频编码: 自动下载时同清晰度按「编码优先级」选择,默认 AVC,可切换 HEVC / AV1",
            "· 音频: 默认 M4A;Hi-Res FLAC 可在下载页「音频流」中选择",
            "· 批量清晰度: 自动(每视频独立取最高)或固定清晰度(缺失自动回退)",
            "· 可跳过字幕、封面、AI字幕、混流等步骤",
        ))
        Spacer(Modifier.height(24.dp))
    }
}

/** 帮助页分区：h2 标题 + 逐行 p（原版结构，无卡片） */
@Composable
private fun HelpSection(title: String, lines: List<String>) {
    val c = AppTheme.colors
    Column {
        Style.H2(title)
        lines.forEach { line ->
            Text(line, color = c.fg, fontSize = 13.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}
/** `label.field-label{ font-size:11.5px; color:var(--fg-title); margin-bottom:5px; font-weight:500 }` */
@Composable
private fun FieldLabel(text: String) {
    val c = AppTheme.colors
    Text(text, color = c.fgTitle, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 5.dp))
}
