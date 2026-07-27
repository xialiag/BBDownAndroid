# 环境搭建文档

本文档涵盖从零搭建开发环境、编译 FFmpegKit AAR、到构建 BBDown Android APK 的完整流程。

## 环境要求

### 基础环境

| 组件 | 版本要求 | 说明 |
|------|---------|------|
| 操作系统 | Linux x86_64 / macOS | Windows 推荐 WSL2 |
| JDK | 17+ | 构建 BBDown 需要 |
| Gradle | 7.6.3 | 项目自带 wrapper |
| Android SDK | API 33+ | compileSdk 33 |
| Android NDK | r22b - r25b | 编译 FFmpeg 需要 |
| Git | 2.20+ | 源码管理 |

### 磁盘空间

| 用途 | 空间 |
|------|------|
| Android SDK + NDK | 约 5 GB |
| FFmpegKit 源码 + 编译产物 | 约 3 GB（单架构） |
| BBDown 源码 + 构建缓存 | 约 500 MB |

## 第一步：安装 JDK

```bash
# Ubuntu / Debian
sudo apt update
sudo apt install openjdk-17-jdk

# macOS (Homebrew)
brew install openjdk@17

# 验证
java -version
# openjdk version "17.0.x" ...
```

设置 `JAVA_HOME`：

```bash
# Linux
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# macOS
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# 写入 shell 配置
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
source ~/.bashrc
```

## 第二步：安装 Android SDK

### 方式一：Android Studio（推荐）

下载 [Android Studio](https://developer.android.com/studio)，通过 SDK Manager 安装：

- Android SDK Platform 33 (Android 13)
- Android SDK Build-Tools 34.0.0
- Android SDK Platform-Tools
- Android SDK Command-line Tools

### 方式二：命令行工具

```bash
# 下载 commandlinetools
wget https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip
unzip commandlinetools-linux-9477386_latest.zip -d /opt/android-sdk/cmdline-tools/
mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest

# 设置环境变量
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# 安装组件
sdkmanager "platforms;android-33" "build-tools;34.0.0" "platform-tools"
```

### 配置 local.properties

在 BBDownAndroid 项目根目录创建 `local.properties`：

```properties
sdk.dir=/opt/android-sdk
```

## 第三步：安装 Android NDK（编译 FFmpeg 需要）

如果只需要构建 BBDown APK（已有预编译 AAR），可跳过此步。

```bash
# 方式一：通过 sdkmanager 安装
sdkmanager "ndk;25.2.9519653"  # NDK r25b

# 方式二：手动下载
# 从 https://developer.android.com/ndk/downloads 下载对应平台 NDK
unzip android-ndk-r25b-linux.zip -d /opt/
mv /opt/android-ndk-r25b /opt/android-ndk

# 设置环境变量
export ANDROID_NDK_ROOT=/opt/android-ndk
export PATH=$PATH:$ANDROID_NDK_ROOT

# 验证
$ANDROID_NDK_ROOT/ndk-build --version
```

### NDK 版本兼容性

| NDK 版本 | FFmpeg 6.x | FFmpeg 8.x | 说明 |
|---------|-----------|-----------|------|
| r22b | 兼容 | 兼容 | 最低推荐版本 |
| r23b | 兼容 | 兼容 | |
| r24 | 兼容 | 兼容 | |
| r25b | 兼容 | 兼容 | 推荐 |
| r26+ | 未测试 | 部分兼容 | stdbit.h 问题 |

## 第四步：编译 FFmpegKit AAR

### 获取 ffmpeg-kit 源码

```bash
git clone https://github.com/arthenica/ffmpeg-kit.git
cd ffmpeg-kit
```

### 安装构建依赖

```bash
# Ubuntu / Debian
sudo apt install pkg-config autoconf automake libtool wget curl unzip

# macOS
brew install pkg-config autoconf automake libtool
```

### 编译 FFmpeg 6.x 版本

```bash
# 设置环境变量
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_ROOT=/opt/android-ndk

# 仅编译 arm64-v8a 架构，启用 full 库集
./android.sh \
  --disable-arm-v7a \
  --disable-arm-v7a-neon \
  --disable-x86 \
  --disable-x86-64 \
  --enable-android-media-codec \
  --enable-android-zlib \
  --full

# 编译产物位于：
# prebuilt/bundle-android-aar/ffmpeg-kit/ffmpeg-kit.aar
```

将编译好的 AAR 命名为 `ffmpeg-kit-full-v6.aar` 并放入 BBDown 的 `app/libs/` 目录：

```bash
cp prebuilt/bundle-android-aar/ffmpeg-kit/ffmpeg-kit.aar \
   /path/to/BBDownAndroid/app/libs/ffmpeg-kit-full-v6.aar
```

### 编译 FFmpeg 8.x 版本

FFmpeg 8.x 需要从源码替换 ffmpeg 源码目录：

```bash
# 1. 获取 FFmpeg 8.x 源码
cd /path/to/ffmpeg-kit/src
rm -rf ffmpeg
git clone https://git.ffmpeg.org/ffmpeg.git ffmpeg
cd ffmpeg
git checkout n8.1.2  # 或最新稳定版本

# 2. 重新编译（构建脚本会自动检测版本并应用对应补丁）
cd /path/to/ffmpeg-kit
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_ROOT=/opt/android-ndk

./android.sh \
  --disable-arm-v7a \
  --disable-arm-v7a-neon \
  --disable-x86 \
  --disable-x86-64 \
  --enable-android-media-codec \
  --enable-android-zlib \
  --full

# 3. 将 AAR 命名为 v8
cp prebuilt/bundle-android-aar/ffmpeg-kit/ffmpeg-kit.aar \
   /path/to/BBDownAndroid/app/libs/ffmpeg-kit-full-v8.aar
```

### FFmpeg 6.x 与 8.x 的差异

构建系统通过版本检测自动处理以下差异：

**源文件结构**：
- 6.x 使用扁平文件名（`fftools_ffmpeg.c`）
- 8.x 使用目录结构（`fftools/ffmpeg.c`），并新增 `ffmpeg_dec.c`、`ffmpeg_enc.c`、`ffmpeg_sched.c` 等文件

**configure 选项**：
- 6.x 支持 `--disable-postproc`
- 8.x 已移除该选项

**编译标志**：
- 8.x 额外需要 `-Wno-single-bit-bitfield-constant-conversion` 等警告抑制

**头文件**：
- 6.x 使用 `fftools_ffmpeg.h`
- 8.x 使用 `fftools/ffmpeg.h`（构建脚本通过 sed 自动替换）

### 在 Termux 中编译（可选）

在 Android 设备上使用 Termux 编译：

```bash
# 安装 Termux（从 F-Droid 下载，不要用 Play Store 版）
pkg update && pkg upgrade
pkg install git wget curl unzip openjdk-17 clang make pkg-config

# 安装 Android SDK（简版）
# 注意：Termux 环境下 NDK 可能需要额外配置
```

Termux 编译注意事项：
- 需要设备 root 或 proot 模拟
- 编译时间较长（arm64 设备约 1-2 小时）
- 内存不足时减少并行任务数

## 第五步：构建 BBDown Android APK

### 准备 AAR 文件

将编译好的 AAR 文件放入 `app/libs/` 目录：

```
app/libs/
├── ffmpeg-kit-full-v6.aar   # FFmpeg 6.x（可选）
├── ffmpeg-kit-full-v8.aar   # FFmpeg 8.x（可选）
└── ffmpeg-kit-full.aar      # 向后兼容（视为 v6）
```

至少需要一个 AAR 文件。如果只构建一个版本，可使用 `ffmpeg-kit-full.aar` 命名。

### 构建命令

```bash
cd BBDownAndroid

# 方式一：使用便捷脚本
./build-apk.sh 6 release    # FFmpeg 6.x release
./build-apk.sh 8 release    # FFmpeg 8.x release
./build-apk.sh 6 debug      # FFmpeg 6.x debug
./build-apk.sh 8 debug      # FFmpeg 8.x debug

# 方式二：直接使用 Gradle
./gradlew assembleRelease -PffmpegVersion=6
./gradlew assembleRelease -PffmpegVersion=8

# 方式三：在 gradle.properties 中固定版本（取消注释最后一行）
# ffmpegVersion=6
```

### 构建产物

```
app/build/outputs/apk/release/
├── app-release-ff6.apk    # FFmpeg 6.x 版本
└── app-release-ff8.apk    # FFmpeg 8.x 版本
```

APK 文件名中的 `ff6`/`ff8` 标识对应编译时选择的 FFmpeg 版本。

### 签名验证

构建脚本自动执行 v1+v2 双签名。验证签名：

```bash
# 使用 apksigner 验证
/opt/android-sdk/build-tools/34.0.0/apksigner verify --verbose --min-sdk-version 21 app-release-ff6.apk

# 预期输出：
# Verified using v1 scheme (JAR signing): true
# Verified using v2 scheme (APK Signature Scheme v2): true
```

## 常见问题

### Gradle 下载失败

如果 Gradle 下载超时或失败，可配置代理或使用国内镜像：

在 `gradle.properties` 中添加：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=18080
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=18080
```

在 `settings.gradle` 中使用阿里云镜像（项目已配置）：

```groovy
maven { url 'https://maven.aliyun.com/repository/google' }
maven { url 'https://maven.aliyun.com/repository/public' }
```

### AAR 文件未找到

构建时如果报错 `ffmpeg-kit-full.aar not found`，检查：

1. AAR 文件是否在 `app/libs/` 目录下
2. 文件名是否正确（`ffmpeg-kit-full-v6.aar` 或 `ffmpeg-kit-full.aar`）
3. 使用 `-PffmpegVersion` 指定的版本是否有对应的 AAR 文件

### FFmpeg 编译报错

**`Unknown option '--disable-postproc'`**：FFmpeg 8.x 已移除此选项。构建脚本会自动检测版本并跳过，如手动修改了 `ffmpeg.sh`，请确保版本检测逻辑正确。

**`stdbit.h file not found`**：NDK 版本过低不支持 C23。使用 NDK r25b 或更高版本，或创建兼容头文件。

**`duplicate symbol: show_help_default`**：同时链接了 `ffmpeg.c` 和 `ffprobe.c` 导致符号冲突。构建脚本通过 `__attribute__((weak))` 解决，如仍报错检查 `Android.mk` 的源文件列表。

### 内存不足（OOM）

FFmpeg 8.x 库文件比 6.x 更大，可能导致运行时 OOM。BBDown 已实现内存自适应：

- 低内存时自动降低下载线程数
- 监听 `TRIM_MEMORY_RUNNING_CRITICAL` 主动释放缓存
- WebView 缓存清理 + GC 触发

如仍遇到 OOM，可在 `build.gradle` 中启用 `largeHeap`（已启用）。

### zipalign 验证失败

如果签名后 zipalign 验证失败，可能是构建工具版本问题。确保 `buildToolsVersion` 为 `34.0.0`，并使用 `-p` 参数进行页对齐。

## 环境变量汇总

```bash
# 必须设置
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_SDK_ROOT=/opt/android-sdk

# 编译 FFmpeg 时需要
export ANDROID_NDK_ROOT=/opt/android-ndk

# 可选：加速下载
export PATH=$PATH:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_NDK_ROOT
```

## 快速验证清单

- [ ] `java -version` 输出 17+
- [ ] `$ANDROID_SDK_ROOT/platform-tools/adb` 可执行
- [ ] `./gradlew --version` 输出 Gradle 7.6.3
- [ ] `app/libs/` 目录下存在至少一个 AAR 文件
- [ ] `./build-apk.sh 6 release` 成功生成 APK
- [ ] APK 安装后可正常启动
