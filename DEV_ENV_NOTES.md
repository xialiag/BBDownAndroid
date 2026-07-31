# 开发环境说明（Windows 原生 / 便携工具链）

本文档记录在本机为 BBDownAndroid 搭建的开发环境。环境为「原生 Windows + 便携式 per-user 工具链」，无需管理员权限。

## 工具链位置

所有工具安装在 `C:\Users\xiali\android-dev\`（便携式，免安装）：

| 组件 | 路径 | 版本 |
|------|------|------|
| JDK | `android-dev\jdk-17` | Eclipse Temurin 17.0.19 |
| Android SDK | `android-dev\android-sdk` | cmdline-tools 20.0 / build-tools 34.0.0 / platform-tools 37.0.0 / platforms;android-33 |
| Git | `android-dev\portable-git` | PortableGit 2.55.0.3（含 bash，不含 zip.exe） |

> NDK **未安装**：本项目的原生代码全在预编译 FFmpegKit AAR 的 `.so` 里，`app/build.gradle` 无 `externalNativeBuild`，构建 APK 不需要 NDK。仅当要从源码编译 FFmpeg 时才需安装 NDK r22b–r25b（见 `ENVIRONMENT_SETUP.md`）。

## 环境变量（已持久化到用户注册表 HKCU\Environment）

- `JAVA_HOME` = `C:\Users\xiali\android-dev\jdk-17`
- `ANDROID_SDK_ROOT` = `C:\Users\xiali\android-dev\android-sdk`
- `ANDROID_HOME` = `C:\Users\xiali\android-dev\android-sdk`
- `Path` 前置：`jdk-17\bin`、`android-sdk\cmdline-tools\latest\bin`、`android-sdk\platform-tools`、`portable-git\cmd`、`portable-git\usr\bin`

新开终端即生效。注意：本机网络对 git/Java 直连外网被阻断，必须走代理（见下）。

## 代理（关键）

本机 WinINET 代理为 `127.0.0.1:7897`（Clash/Mihomo 类）。`Invoke-WebRequest` 自动用此代理，但 **git 和 Java/Gradle 默认走 WinHTTP（直连）会被阻断**。处理：

- **Gradle 构建**：`gradle.properties` 中的代理配置**默认已注释**（为兼容无代理的 Linux 环境），Windows 构建前需取消注释：
  ```
  systemProp.http.proxyHost=127.0.0.1
  systemProp.http.proxyPort=7897
  systemProp.https.proxyHost=127.0.0.1
  systemProp.https.proxyPort=7897
  ```
  若代理端口变更，同步修改。`build-apk.sh` 在 Windows 下会自动检测并提醒。
- **Gradle Wrapper 首次下载 distro**：Wrapper 在读取 `gradle.properties` 之前就下载 distro，故需用 `GRADLE_OPTS` 传代理：
  ```
  $env:GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"
  ```
  （distr​​o 下载一次后缓存在 `~/.gradle/wrapper/dists/`，后续构建可省略此变量。）
- **git**：已配置全局 `http.proxy`/`https.proxy = http://127.0.0.1:7897`（`~/.gitconfig`）。

## FFmpeg AAR

两个预编译 AAR 已放入 `app/libs/`（来自 https://github.com/xialiag/ffmpeg-kit/releases/tag/v1.0 ）：

- `ffmpeg-kit-full-v6.aar` — FFmpeg 6.1.6（7.0 MB，稳定版/低内存）
- `ffmpeg-kit-full-v8.aar` — FFmpeg 8.1.2（7.6 MB，编码器最新）

编译时通过 `-PffmpegVersion=6|8` 选择其一，并注入 `BuildConfig.FFMPEG_VERSION`。

## 构建命令

PowerShell 中（新终端环境变量已就绪）：

```powershell
cd C:\Users\xiali\Documents\trae_projects\BBDownAndroid

# 首次构建需给 Wrapper 传代理（distr​​o 下载一次即可）
$env:GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"

# 构建 FFmpeg 6.x debug APK
.\gradlew.bat assembleDebug -PffmpegVersion=6
# 构建 FFmpeg 8.x debug APK
.\gradlew.bat assembleDebug -PffmpegVersion=8
# release 版本
.\gradlew.bat assembleRelease -PffmpegVersion=6
.\gradlew.bat assembleRelease -PffmpegVersion=8
```

产物：`app\build\outputs\apk\debug\app-debug-ff6.apk` / `app-debug-ff8.apk`
（release 在 `apk\release\`）。**每次构建只保留当前版本的 APK，Gradle 会清理上一个**；请用
`build-apk.sh` 构建（自动拷贝到 `dist\` 并重命名），或手动及时拷贝。

一键脚本 `build-apk.sh`（bash，需 Git Bash）推荐用法：

```bash
./build-apk.sh all release   # 一次构建 FFmpeg 6.x + 8.x 并拷贝到 dist/
./build-apk.sh 6 debug       # 单个版本
```

脚本在 Windows 下自动：检查代理配置（缺失时警告）、检查 local.properties 路径、
修复 AAR 反斜杠路径（需 python）、构建后立即拷贝到 `dist\`、自检 native 库与签名。
产物命名：`dist\BBDown-1.9.97-ffmpeg-6.1.6-release.apk` / `-8.1.2-`。

## 对仓库做的改动（均为可逆）

1. **`gradle.properties`**：删除失效代理 `127.0.0.1:18080`，替换为实际可用的 `127.0.0.1:7897`。2026-07 起代理配置**默认注释**（兼容 Linux 无代理环境），Windows 构建前取消注释即可（见上文「代理」章节）。
2. **`app/build.gradle`**：在 `signV1V2` 闭包顶部加 Windows 守卫——Windows 下跳过自定义 v1+v2 重签名（原任务用裸 `zip`/`apksigner`/`zipalign` 无扩展名，Windows 下 `CreateProcess` 失败；AGP 的 release signingConfig 已自带 v1+v2 且 zipalign，产物可直接安装）。Linux/macOS 行为不变。
3. **`gradlew.bat`**：仓库仅含 bash 版 `gradlew`，从 Gradle v7.6.3 tag 补了 Windows 版 `gradlew.bat`。
4. **`local.properties`**：新建，`sdk.dir=C:/Users/xiali/android-dev/android-sdk`。（注意：Linux ARM64 环境中该文件为 `sdk.dir=/opt/android-sdk`，两环境各自维护，勿混用。）
5. **`bbdown-release.keystore`**：新建开发用自签名证书（alias=bbdown，storepass=keypass=bbdown123，RSA 2048，10000 天）。可与 `app/build.gradle` 的 signingConfig 匹配。**注意**：这是开发证书，签名与官方发布版不同，不能覆盖安装官方 APK，但全新安装无碍。

## 验证结果

- ff6 debug APK：19.61 MB，`apksigner verify` → v1=true, v2=true ✓
- ff8 debug APK：20.77 MB，`apksigner verify` → v1=true, v2=true ✓
- 两者均位于 `dist\`。

安装到 arm64-v8a 设备（Android 7.0+）：
```powershell
adb install dist\app-debug-ff6.apk
```

## 备注

- **源码获取**：本机 git 直连 GitHub 被重置，故源码通过 GitHub zipball 下载（非 `git clone`）。若需 git 历史，可在配置好上述 git 代理后执行 `git clone https://github.com/xialiag/BBDownAndroid.git`。
- **构建警告（均无害）**：`SDK XML version 4`（cmdline-tools 与 AGP 版本差异）；`Unable to strip ... libavcodec.so`（无 NDK strip 工具，`.so` 原样打包，APK 略大）；`toUpperCase()` deprecated（源码既有，非环境问题）。
- **签名说明**：Windows 下产物为 AGP 默认 v1+v2 签名，开发安装足够。如需与官方一致的 v1+v2 重签名流程，可在 WSL2/Linux 下运行原 `signV1V2` 任务。
