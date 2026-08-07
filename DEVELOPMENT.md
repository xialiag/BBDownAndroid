# 开发文档

本文档描述 BBDown Android 的架构设计、核心模块、桥接机制和扩展方法。

## 整体架构

应用采用 **WebView + Native Bridge** 混合架构。前端用 HTML/JS/CSS 渲染 UI，Native 用 Kotlin 处理网络请求、文件下载、FFmpeg 混流等系统级操作。两层通过 `addJavascriptInterface` 注入的 `AndroidBridge` 对象通信。

```
┌─────────────────────────────────────────────────────┐
│                    MainActivity                       │
│  ┌───────────────────────────────────────────────┐  │
│  │                   WebView                       │  │
│  │  ┌─────────┐  ┌──────────┐  ┌──────────────┐ │  │
│  │  │ app.js  │  │ app.css  │  │ index.html   │ │  │
│  │  └────┬────┘  └──────────┘  └──────────────┘ │  │
│  │       │ callBridge() / __onBridge()            │  │
│  └───────┼────────────────────────────────────────┘  │
│          │ AndroidBridge (@JavascriptInterface)      │
│  ┌───────▼────────────────────────────────────────┐  │
│  │              BBDownBridge.kt                    │  │
│  │  登录 | 解析 | 搜索 | 任务管理 | 设置 | 文件   │  │
│  └──┬────────┬──────────┬──────────┬─────────────┘  │
│     │        │          │          │                 │
│  ┌──▼──┐ ┌──▼──────┐ ┌─▼────────┐ ┌▼──────────┐    │
│  │Bili │ │TaskMgr  │ │Download  │ │TaskStore  │    │
│  │Api  │ │         │ │Engine    │ │           │    │
│  └──┬──┘ └────┬────┘ └────┬─────┘ └───────────┘    │
│     │         │           │                          │
│  ┌──▼──┐ ┌───▼─────┐ ┌──▼──────────┐               │
│  │Http │ │Download │ │FFmpegMuxer  │               │
│  │     │ │Service  │ │             │               │
│  └─────┘ └─────────┘ └─────────────┘               │
└─────────────────────────────────────────────────────┘
```

## 核心模块

### MainActivity

应用的唯一 Activity，负责 WebView 生命周期管理和权限请求。

初始化顺序：安装 CrashHandler → 设置状态栏 → 初始化 TaskManager → 设置输出目录 → 恢复持久化任务 → 创建 WebView → 注册 JS 桥接 → 加载 assets 页面 → 请求存储权限 → 注册内存回调 → 记录 FFmpeg 版本。

回退键拦截：调用 JS `handleBackButton()` 判断是否在应用内回退，根页面 2 秒内再按退出应用。

生命周期保活：`onPause`/`onStop`/`onDestroy` 均调用 `TaskManager.saveAll()` 持久化任务状态。`onStop` 时若有运行中任务则启动 DownloadService 前台服务。

### BBDownBridge

JS 与 Native 通信的桥梁，通过 `@JavascriptInterface` 注解暴露约 50 个方法。所有方法在子线程执行，避免阻塞 WebView 线程。

**回调机制**：JS 端调用 `callBridge(method, ...args)` 时生成递增的 `reqId`，Native 完成后通过 `webView.evaluateJavascript` 调用 `window.__onBridge(reqId, {ok, data})` 或 `window.__onBridge(reqId, {error, msg})` 回调。

**进度推送**：批量解析等长时间操作通过 `window.__onBatchParseProgress(reqId, progress)` 实时推送进度。

**单向推送**：登录态变更通过 `__onLoginUpdate`、收藏夹更新通过 `__onFavFoldersUpdate` 由 Native 主动通知 JS。

方法按功能分类：

| 分类 | 主要方法 |
|------|---------|
| 登录 | `getQrCode`, `pollQrLogin`, `getTvQrCode`, `pollTvLogin`, `checkLogin`, `logout`, `openBiliApp` |
| 解析 | `parseUrl`, `parseBatch`, `getVideoInfo`, `getPlayInfo`, `checkCollection`, `getFavFolders`, `getFavList` |
| 搜索 | `searchUpper`, `getUpperVideos`, `getFollowings`, `getFollowTags`, `searchVideo` |
| 任务 | `addTask`, `addBatchTasks`, `getTasks`, `cancelTask`, `pauseTask`, `resumeTask`, `removeTask`, `retryTask` |
| 设置 | `getApiType`, `setApiType`, `setSetting`, `getSetting`, `getAllSettings` |
| 文件 | `checkStoragePermission`, `requestManageStorage`, `openFile`, `shareFile` |
| 调试 | `jsLog`, `getDebugLogs`, `saveLogsToFile`, `getCrashLogs`, `getAppVersion` |

### BilibiliApi

B站 API 的完整封装，约 2200 行 Kotlin 代码。移植自原版 BBDown 的 C# 实现。

**登录签名体系**：

- **Web API**：使用 WBI 签名（`Wbi.kt`），从导航接口获取 `img_key` 和 `sub_key`，按打乱表截取后拼接 MD5。
- **TV API**：使用 TV APP 签名（`tvSign`/`tvSignMd5`），参数排序后 MD5 加密。
- **App API**：需通过 B站 App 授权获取 token。
- **指纹 Cookie**：`ensureFingerprintCookie` 生成 `buvid3`/`b_nut`/`b_lsid` 等浏览器指纹，规避风控。

**URL 解析**：支持 `b23.tv` 短链（HTTP 301 重定向）、BV/av 号、ep（剧集）、ss（季）、md（漫画）等多种格式。`parseUrl` 返回 `ParsedId` 对象，包含类型和 ID。

**播放地址获取**：`getPlayInfo` 按 API 类型分派到 `getPlayInfoWeb`/`getPlayInfoTv`/`getPlayInfoApp`/`getPlayInfoIntl`。返回的 DASH 数据通过 `parseDash` 解析为 `VideoTrack` 和 `AudioTrack` 列表。

**收藏夹与合集**：`getFavFolders`/`getFavList` 处理收藏夹；`checkUgcSeason`/`checkCollectionComprehensive` 处理合集和列表，支持分页加载全部内容。

**VIP 与认证信息补全**：搜索 API 不返回 VIP/认证数据，通过 `fetchUpperCardInfo` 批量获取用户名片信息补全。

### DownloadEngine

单任务下载流程的编排器，支持六种模式：

| 模式 | 输出 | 流程 |
|------|------|------|
| `all` | MP4（视频+音频+封面+字幕+弹幕） | 下载音视频 → FFmpeg 混流 → 附加封面/字幕/弹幕 |
| `video_only` | MP4（仅视频） | 下载视频 → FFmpeg 注入元数据 |
| `audio_only` | M4A（仅音频） | 下载音频 → FFmpeg 注入元数据+封面 |
| `subtitle_only` | SRT | 下载字幕 |
| `cover_only` | JPG | 下载封面 |
| `danmaku_only` | ASS/XML | 下载弹幕并转换 |

**选轨逻辑**：`selectVideo` 按 codec 优先级和清晰度选择视频流，`selectAudio` 按 codec 偏好（FLAC > M4A）和带宽选择音频流。

**文件名生成**：`buildFileName` 支持 16 个变量模板，通过 `sanitize` 清理非法字符。

### MultiThreadDownloader

多线程分片下载器，核心特性：

- **分片策略**：先通过 `Range: bytes=0-0` 查询文件大小，然后按线程数分片。
- **断点续传**：每 3 秒将进度写入 `.dl` 侧边文件（JSON 格式），包含 url/total/parts 数组。恢复时自动检测并续传。
- **并发写入**：使用 `RandomAccessFile` 多线程并发写入不同分片，64KB 缓冲。
- **HTTP 回退**：HTTP 403（番剧 CDN 拒绝 HTTP）自动回退到 HTTPS。
- **取消机制**：通过 `canceled` 标志位 + `InterruptedException` 中断线程。

### FFmpegMuxer

基于 FFmpegKit 的混流器，移植自原版 BBDown 的 `MuxAV` 方法。

**混流命令**（`muxWithMetadata`）：

```
ffmpeg -loglevel warning -y \
  -i video.vpart -i audio.apart -i cover.jpg -i subtitle.srt \
  -map 0 -map 1 -map 2 -map 3 \
  -disposition:v:1 attached_pic \
  -metadata:s:s:0 title="中文（简体）" -metadata:s:s:0 language=chi \
  -c:v copy -c:a copy -c:s mov_text \
  -metadata title="..." -metadata artist="..." -metadata album="..." \
  -metadata description="..." -metadata creation_time="2024-01-01T00:00:00.000000Z" \
  -movflags faststart -strict unofficial -strict -2 -f mp4 -- output.mp4
```

**仅注入元数据**（`injectMetadataOnly`）：用于 audio_only 或 skipMux 场景，单文件输入，输出临时 `.meta.mp4` 后替换原文件。

**封面处理**：`ensureJpegCover` 检测 WebP 格式并转换为 JPEG（iTunes 元数据仅支持 JPEG/PNG）。

### TaskManager

单例任务管理器，维护 `ConcurrentHashMap<String, DownloadTask>`。

**线程池**：固定 3 线程，每个任务独立异常隔离（try/catch Throwable）。OOM 时标记失败并触发 GC。

**内存自适应**：`getEffectiveThreads()` 根据可用内存动态调整线程数：

| 可用内存 | 线程数 |
|---------|--------|
| >128 MB | 用户设定值 |
| 64-128 MB | 4 |
| <64 MB | 2 |

**任务去重**：`dupKey` 由 `url|cid` 列表组成，相同内容不重复添加。

### TaskStore

JSON 持久化，写入 `context.filesDir/tasks.json`。原子写入：先写 `.tmp` 文件再 `Files.move(ATOMIC_MOVE)`，低版本回退 `renameTo`。

### DownloadService

前台服务（`foregroundServiceType="dataSync"`），防止后台下载被系统杀死。

- 启动时立即 `startForeground`（防 Android 12+ ANR）
- 获取 `PARTIAL_WAKE_LOCK`（10 分钟超时，可重获）
- 守护线程每 2 秒刷新通知，连续 4 秒无运行任务则 `stopSelf`
- `onTaskRemoved` 时若有运行任务尝试重启服务

### CrashHandler

全局崩溃捕获器，崩溃时：

1. 记录到 Logger 内存缓冲
2. 写入崩溃文件 `logs/crash_*.txt`（含设备信息、版本、内存、运行任务、完整 cause 链、线程状态、最近 200 条日志）
3. 调用 `TaskStore.save()` 持久化任务
4. 持久化登录态到 SharedPreferences
5. 后台线程崩溃仅记录不终止应用

## 前端架构

前端为单页应用，所有逻辑在 `app.js` 中（约 3000+ 行）。

**状态管理**：全局 `state` 对象管理当前视图、登录态、任务列表、批量解析结果等。

**桥接封装**：

```javascript
// Promise 封装
async function callBridge(method, ...args) {
  const reqId = ++_bseq;
  return new Promise((resolve, reject) => {
    _bres[reqId] = { ok: resolve, err: reject };
    AndroidBridge[method](reqId, ...args);
  });
}

// 带进度的封装
async function callBridgeProgress(method, ...args, onProgress) {
  const reqId = ++_bseq;
  _bres[reqId] = { ok: resolve, err: reject };
  _bprog[reqId] = onProgress;
  AndroidBridge[method](reqId, ...args);
}

// Native 回调入口
window.__onBridge = function(reqId, result) {
  const cb = _bres[reqId];
  if (!cb) return;
  delete _bres[reqId];
  if (result.ok) cb.ok(result.data);
  else cb.err(result.error);
};
```

**图片缓存**：LRU 策略，最多 80 条 blob URL。通过 Native `fetchImage` 下载图片返回 base64，绕过 CORS 限制。内存压力时 Native 调用 JS `clearImgCache()` 清理。

## FFmpeg 版本适配

项目支持 FFmpeg 6.x、8.x、9.x 三套版本，通过编译时参数选择：

### 构建层（build.gradle）

```groovy
def ffmpegVersion = project.findProperty('ffmpegVersion') ?: '6'
def versionedAar = file("libs/ffmpeg-kit-full-v${ffmpegVersion}.aar")
def ffmpegAarFile = versionedAar.exists() ? versionedAar : file('libs/ffmpeg-kit-full.aar')

// 注入 BuildConfig
buildConfigField "String", "FFMPEG_VERSION", "\"${ffmpegVersion}\""
```

### 运行时层（FFmpegVersion.kt）

```kotlin
val compiledMajorVersion = BuildConfig.FFMPEG_VERSION.toInt()
fun isV8() = compiledMajorVersion >= 8   // 8.x / 9.x 共用新 fftools 目录结构
fun isV6() = compiledMajorVersion in 6..7
```

### Native 层（Android.mk）

从预编译头文件检测 `LIBAVFORMAT_VERSION_MAJOR`，自动选择正确的源文件列表和编译参数。FFmpeg 8.x/9.x 使用目录结构（`fftools/ffmpeg.c`），6.x 使用扁平文件名（`fftools_ffmpeg.c`）。

## 扩展开发

### 添加新的 JS-Native 接口

1. 在 `BBDownBridge.kt` 中添加 `@JavascriptInterface` 方法：

```kotlin
@JavascriptInterface
fun myMethod(reqId: String, param: String) {
    try {
        // 业务逻辑
        ok(reqId, resultData)
    } catch (e: Exception) {
        err(reqId, e.message ?: "未知错误")
    }
}
```

2. 在 `app.js` 中调用：

```javascript
const result = await callBridge('myMethod', 'paramValue');
```

### 添加新的下载模式

1. 在 `Models.kt` 的 `DownloadTask` 中添加模式常量
2. 在 `DownloadEngine.kt` 的 `execute` 方法中添加分支
3. 在 `app.js` 的下载选项 UI 中添加选项

### 添加新的 B站 API

1. 在 `BilibiliApi.kt` 中添加方法，使用 `Http.get`/`Http.postForm` 发送请求
2. 如需签名，使用 `wbiSign`（Web）或 `tvSign`（TV）
3. 在 `BBDownBridge.kt` 中添加 `@JavascriptInterface` 包装
4. 在 `app.js` 中调用

## 调试

### 日志查看

应用内设置页面可查看调试日志和崩溃日志。也可通过 `adb logcat` 过滤标签：

```bash
adb logcat | grep -E "BBDown|FFmpegMuxer|DownloadEngine|BilibiliApi|TaskManager|Memory"
```

### FFmpeg 命令调试

`FFmpegMuxer.execute` 会将完整命令记录到日志，可在调试日志中查看。失败时记录日志尾部 1500 字符。

### 网络调试

`Http.kt` 的请求和响应默认不记录详细日志。可在 `BilibiliApi.kt` 中临时添加 `Logger.d` 调用。

## 构建变体

| 参数 | 说明 |
|------|------|
| `-PffmpegVersion=6` | 使用 FFmpeg 6.x AAR（默认） |
| `-PffmpegVersion=8` | 使用 FFmpeg 8.x AAR |
| `-PffmpegVersion=9` | 使用 FFmpeg 9.x AAR（最新） |
| `debug` | Debug 构建（使用 release 签名，便于覆盖安装） |
| `release` | Release 构建（v1+v2 双签名） |

APK 输出文件名自动带版本标识：`app-release-ff6.apk` / `app-release-ff8.apk` / `app-release-ff9.apk`。
