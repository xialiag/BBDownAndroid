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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bbdown.app.ui.AppViewModel
import com.bbdown.app.ui.components.AppButton
import com.bbdown.app.ui.components.ButtonKind
import com.bbdown.app.ui.components.Pill
import com.bbdown.app.ui.components.SectionTitle
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
fun SettingsScreen(vm: AppViewModel, onRequestAllFilesAccess: () -> Unit = {}) {
    val c = AppTheme.colors
    var showDebug by remember { mutableStateOf(false) }
    var outDir by remember { mutableStateOf(vm.settings["output_dir"] ?: "") }

    LaunchedEffect(showDebug) {
        if (showDebug) vm.loadDebugLogs()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.screenPadding),
    ) {
        Text("设置", color = c.fgBright, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))

        // ---------- 下载 ----------
        SectionTitle("下载")
        SettingRow("输出目录") {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "写入公共目录需要「所有文件访问」权限，未授权时自动落到应用私有目录",
                    color = c.fgDim, fontSize = 10.sp, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                AppButton("授权", onRequestAllFilesAccess, fontSize = 10)
            }
            BasicTextField(
                value = outDir,
                onValueChange = { outDir = it },
                singleLine = true,
                textStyle = TextStyle(color = c.fg, fontSize = 11.5.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.radiusSm))
                    .background(c.bgInput)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            AppButton("保存目录", { vm.saveSetting("output_dir", outDir) }, fontSize = 11)
        }

        SettingRow("线程数") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                THREAD_OPTIONS.forEach { n ->
                    Pill(n, (vm.settings["threads"] ?: "8") == n, { vm.saveSetting("threads", n) })
                }
            }
        }

        SettingRow("下载内容") {
            Column {
                MODE_LABELS.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        row.forEach { (v, l) ->
                            Pill(l, (vm.settings["downloadMode"] ?: "all") == v, { vm.saveSetting("downloadMode", v) })
                        }
                    }
                }
            }
        }

        SettingRow("视频编码") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("avc" to "AVC", "hevc" to "HEVC", "av1" to "AV1").forEach { (v, l) ->
                    Pill(l, (vm.settings["preferCodec"] ?: "avc") == v, { vm.saveSetting("preferCodec", v) })
                }
            }
        }

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
            val on = vm.settings[key] == "true"
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = c.fg, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                Pill(if (on) "开" else "关", on, { vm.saveSetting(key, if (on) "false" else "true") })
            }
        }

        // ---------- 外观 ----------
        SectionTitle("外观")
        SettingRow("主题") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("深色", vm.darkTheme, { vm.saveSetting("theme", "dark") })
                Pill("浅色", !vm.darkTheme, { vm.saveSetting("theme", "light") })
            }
        }

        // ---------- 调试 ----------
        SectionTitle("调试")
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("调试服务器", color = c.fg, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
            val on = vm.settings["debug_server"] == "true"
            Pill(if (on) "开" else "关", on, { vm.saveSetting("debug_server", if (on) "false" else "true") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            AppButton(if (showDebug) "收起日志" else "查看日志", { showDebug = !showDebug }, fontSize = 11)
            if (showDebug) AppButton("清空", { vm.clearDebugLogs() }, kind = ButtonKind.Danger, fontSize = 11)
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.screenPadding),
    ) {
        Text("使用说明", color = c.fgBright, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp, bottom = 12.dp))

        HelpBlock(
            "快速开始",
            listOf(
                "1. 左侧「新建下载」粘贴视频链接或 BV 号，点解析",
                "2. 选择分P、清晰度、下载内容后点「开始下载」",
                "3. 在任务列表查看进度，完成后到输出目录取文件",
            ),
        )
        HelpBlock(
            "搜索与批量下载",
            listOf(
                "「搜索」页可搜 UP 主或视频，点卡片直接进入下载",
                "UP 主投稿列表支持分页浏览，点任一视频即可下载",
                "新建下载支持空格分隔多个链接批量解析",
            ),
        )
        HelpBlock(
            "登录说明",
            listOf(
                "网页版登录态会过期（约一个月），到「账号」页重新扫码",
                "TV 版登录更持久，但部分功能仍依赖网页版",
                "登录失效时接口会明确报错，不会假装成功",
            ),
        )
        HelpBlock(
            "常见问题",
            listOf(
                "「无可用音视频流」：多为登录失效或该视频需要大会员",
                "「账号未登录(code=-101)」：网页版登录已过期，重新扫码",
                "下载中断：任务支持断点续传，重试会从断点继续",
                "混流卡住：内置超时机制，超时会自动终止并保留原文件",
            ),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HelpBlock(title: String, lines: List<String>) {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(Dimens.radiusLg))
            .padding(12.dp),
    ) {
        Text(title, color = c.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        lines.forEach {
            Text(it, color = c.fg, fontSize = 11.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
