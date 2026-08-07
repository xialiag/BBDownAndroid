# BBDown Android

B站视频下载器 BBDown 的 Android 移植版，支持视频/音频/字幕/弹幕/封面下载，内置 FFmpeg 混流与元数据注入。

## 功能特性

### 下载能力

- 支持解析 BV/av/ep/ss/md 链接、b23.tv 短链、收藏夹、合集/列表、UP 主投稿
- 多线程分片下载，支持断点续传（`.dl` 断点文件）
- 六种下载模式：视频+音频混流、仅视频、仅音频、仅字幕、仅封面、仅弹幕
- 自动选择最高画质/音质，支持 codec 优先级与升降序排列
- 弹幕自动转换为 ASS 字幕格式（13 轨道防重叠，1920x1080 画布）

### 登录与认证

- 四种 API 类型：Web、TV、App、Intl（国际版）
- 扫码登录（Web QR / TV QR）、B站 App 授权登录
- WBI 签名、TV APP 签名、浏览器指纹 Cookie 生成
- 登录态持久化，崩溃恢复后自动恢复

### UI 与交互

- WebView + Native 混合架构，深色/浅色/跟随系统主题
- 视频卡片展示 UP 主认证标志（小闪电）和大会员粉色名称
- 收藏夹批量选择下载，全选/取消合并按钮
- 选择视频后直接进入下载详情页
- 内存压力自适应：低内存时自动降线程、清缓存、触发 GC

### FFmpeg 集成

- 编译时选择 FFmpeg 6.x / 8.x / 9.x（`-PffmpegVersion=6/8/9`）
- 元数据注入完全移植自原版 BBDown（title/artist/album/desc/creation_time）
- 字幕嵌入（`mov_text` 编码）、封面嵌入（`attached_pic`）
- WebP 封面自动转 JPEG
- 输出文件与原版 BBDown 保持一致

## 安装

### 直接下载 APK

从 [Releases](../../releases) 页面下载对应版本的 APK：

- `BBDown-x.x.x-ffmpeg-6.1.6-release.apk` — 基于 FFmpeg 6.x（稳定版，内存占用低）
- `BBDown-x.x.x-ffmpeg-8.1.2-release.apk` — 基于 FFmpeg 8.x
- `BBDown-x.x.x-ffmpeg-9.0-release.apk` — 基于 FFmpeg 9.x（最新版，编码器版本更新）

仅支持 arm64-v8a 架构设备（Android 7.0+）。

### 从源码构建

```bash
# 克隆仓库
git clone <repo-url>
cd BBDownAndroid

# 将 FFmpegKit AAR 放入 app/libs/
# 命名方式：
#   ffmpeg-kit-full-v6.aar  → FFmpeg 6.x
#   ffmpeg-kit-full-v8.aar  → FFmpeg 8.x
#   ffmpeg-kit-full-v9.aar  → FFmpeg 9.x
#   ffmpeg-kit-full.aar     → 向后兼容（视为 v6）

# 构建（推荐：一键构建全部 FFmpeg 版本，自动拷贝产物到 dist/）
./build-apk.sh all release
# 或单个版本
./build-apk.sh 6 release
./build-apk.sh 8 release
./build-apk.sh 9 debug

# 或使用 Gradle 直接构建（注意：多次构建会互相清理 apk 目录，产物需及时拷贝）
./gradlew assembleRelease -PffmpegVersion=6
./gradlew assembleRelease -PffmpegVersion=8
./gradlew assembleRelease -PffmpegVersion=9
```

详细的环境搭建说明（Windows 原生 / Linux x86_64 / Linux ARM64）见
[ENVIRONMENT_SETUP.md](ENVIRONMENT_SETUP.md)，
平台笔记见 [DEV_ENV_NOTES.md](DEV_ENV_NOTES.md)（Windows）与
[DEV_ENV_NOTES_LINUX_ARM64.md](DEV_ENV_NOTES_LINUX_ARM64.md)（Linux ARM64）。

## 使用说明

### 首次使用

1. 安装 APK 后打开应用
2. 点击左上角头像，选择扫码或 App 授权登录 B站
3. 登录成功后即可搜索、浏览收藏夹、下载视频

### 下载视频

1. 在搜索栏输入 BV 号、视频链接或关键词
2. 选择需要下载的视频（收藏夹支持批量选择）
3. 选择下载模式（视频+音频/仅音频等）和画质
4. 点击下载，任务列表实时显示进度和速度

### 文件命名

支持以下变量模板（在设置中配置）：

| 变量 | 说明 |
|------|------|
| `<videoTitle>` | 视频标题 |
| `<pageNumber>` | 分P序号 |
| `<bvid>` | BV号 |
| `<videoCodecs>` | 视频编码 |
| `<audioTitle>` | 音频标题 |
| `<pageNumberTwoDigits>` | 两位P号 |
| `<pageNumberThreeDigits>` | 三位P号 |
| `<pageIndex>` | P索引（从0开始） |
| `<upName>` | UP主名 |
| `<upMid>` | UP主UID |
| `<aid>` | av号 |
| `<cid>` | cid |
| `<dfn>` | 清晰度 |
| `<res>` | 分辨率 |
| `<fps>` | 帧率 |
| `<audioCodec>` | 音频编码 |

### API 类型说明

| API 类型 | 说明 | 特点 |
|---------|------|------|
| Web | 网页端 API | 默认，WBI 签名 |
| TV | 电视端 API | 可下载番剧，TV 签名 |
| App | APP 端 API | 完整功能，需 App 授权 |
| Intl | 国际版 API | 国际版内容 |

## 项目结构

```
BBDownAndroid/
├── app/
│   ├── src/main/
│   │   ├── java/com/bbdown/app/
│   │   │   ├── MainActivity.kt          # 主 Activity，承载 WebView
│   │   │   ├── BBDownBridge.kt          # JS-Native 桥接（50+ 接口）
│   │   │   ├── DownloadService.kt       # 前台下载服务
│   │   │   ├── TaskManager.kt           # 任务管理器（线程池、状态机）
│   │   │   ├── TaskStore.kt             # 任务持久化（JSON）
│   │   │   ├── CrashHandler.kt          # 全局崩溃捕获
│   │   │   └── core/
│   │   │       ├── BilibiliApi.kt       # B站 API 封装
│   │   │       ├── DownloadEngine.kt    # 下载引擎
│   │   │       ├── FFmpegMuxer.kt       # FFmpeg 混流器
│   │   │       ├── MultiThreadDownloader.kt  # 多线程下载器
│   │   │       ├── Http.kt              # HTTP 客户端
│   │   │       ├── Models.kt            # 数据模型
│   │   │       ├── Logger.kt            # 日志系统
│   │   │       ├── FFmpegVersion.kt     # FFmpeg 版本检测
│   │   │       ├── Wbi.kt              # WBI 签名
│   │   │       ├── BvConverter.kt       # BV/av 互转
│   │   │       ├── DanmakuUtil.kt       # 弹幕转 ASS
│   │   │       └── QrCodeUtil.kt        # QR 码生成
│   │   ├── assets/
│   │   │   ├── index.html               # 入口页面
│   │   │   ├── app.js                   # 前端逻辑
│   │   │   └── app.css                  # 样式
│   │   └── res/                         # 资源文件
│   ├── libs/                            # FFmpegKit AAR
│   └── build.gradle                     # 构建配置
├── build-apk.sh                         # 便捷构建脚本
├── DEVELOPMENT.md                       # 开发文档
└── ENVIRONMENT_SETUP.md                 # 环境搭建文档
```

详细架构设计见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 技术栈

- **语言**：Kotlin + JavaScript + CSS
- **架构**：WebView + Native Bridge
- **构建**：Gradle 7.6.3 + AGP 7.4.2
- **多媒体**：FFmpegKit（FFmpeg 6.x / 8.x 可选）
- **网络**：纯 Kotlin HTTP 客户端（支持 gzip/deflate/Cookie/重定向）
- **持久化**：JSON 文件（tasks.json）
- **二维码**：ZXing
- **最低版本**：Android 7.0（API 24）
- **目标架构**：arm64-v8a

## 签名说明

APK 使用 v1 + v2 双签名方案，确保在所有 Android 版本上可覆盖安装。签名密钥：

- keystore: `bbdown-release.keystore`
- alias: `bbdown`
- store/key password: `bbdown123`

构建脚本在 `assembleRelease` 后自动执行 `zipalign` + `apksigner` 重签名。

## 致谢

- [BBDown](https://github.com/nilaoda/BBDown) — 原版 .NET B站下载器
- [FFmpegKit](https://github.com/arthenica/ffmpeg-kit) — FFmpeg Android 集成
- [FFmpeg](https://ffmpeg.org/) — 多媒体处理

## 许可证

本项目仅供学习和个人使用，请遵守 B站用户协议和相关法律法规。
