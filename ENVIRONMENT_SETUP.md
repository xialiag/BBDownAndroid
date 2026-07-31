# 环境搭建文档

本文档涵盖 BBDown Android 的两类受支持开发环境：

| 环境 | 说明 | 详细笔记 |
|------|------|---------|
| Windows 原生 | 便携式 per-user 工具链，无需管理员权限 | [DEV_ENV_NOTES.md](DEV_ENV_NOTES.md) |
| Linux x86_64 | 标准工具链（Ubuntu/Debian 示例） | 本文档第二章 |
| Linux ARM64 | 本机实际环境，需 ARM64 二进制替换 | [DEV_ENV_NOTES_LINUX_ARM64.md](DEV_ENV_NOTES_LINUX_ARM64.md) |

## 环境要求

| 组件 | 版本要求 | 说明 |
|------|---------|------|
| 操作系统 | Windows 10+ / Linux x86_64 / Linux ARM64 | Windows 推荐原生（见笔记）或 WSL2 |
| JDK | 17+ | 构建 BBDown 需要 |
| Gradle | 7.6.3 | 项目自带 wrapper |
| Android SDK | API 33 + Build-Tools 34.0.0 | compileSdk 33 |
| Android NDK | r22b - r25b | **仅编译 FFmpeg 需要**，构建 APK 不需要 |
| Git | 2.20+ | 源码管理 |
| Python | 3.x | 构建脚本的 AAR 路径修复/产物自检需要 |

> 磁盘空间：Android SDK 约 5 GB；FFmpegKit 源码 + 编译产物约 3 GB（单架构）；BBDown 构建缓存约 500 MB。

---

# 第一章：Windows 原生环境

推荐使用便携式工具链（JDK 17 + Android SDK + PortableGit，全部免安装），完整步骤见
[DEV_ENV_NOTES.md](DEV_ENV_NOTES.md)。要点：

1. 工具链放在 `C:\Users\<用户>\android-dev\`，设置用户环境变量 `JAVA_HOME`、`ANDROID_SDK_ROOT`、`Path`
2. **必须配置代理**：`gradle.properties` 中的 `systemProp.http(s).proxyHost/Port`（本机为 `127.0.0.1:7897`）——仓库默认已注释，Windows 构建前需取消注释
3. 首次下载 Gradle distro 需 `GRADLE_OPTS` 传代理（一次性）
4. `local.properties`：`sdk.dir=C:/Users/<用户>/android-dev/android-sdk`
5. Windows 下 `signV1V2` 重签名任务自动跳过（`app/build.gradle` 内有 Windows 守卫），AGP 默认签名即可安装

# 第二章：Linux x86_64 标准环境

## 2.1 安装 JDK 17

```bash
sudo apt update && sudo apt install openjdk-17-jdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # 写入 ~/.bashrc
```

## 2.2 安装 Android SDK

```bash
wget https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip
unzip commandlinetools-linux-9477386_latest.zip -d /opt/android-sdk/cmdline-tools/
mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest

export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

sdkmanager "platforms;android-33" "build-tools;34.0.0" "platform-tools"
```

项目根目录创建 `local.properties`：

```properties
sdk.dir=/opt/android-sdk
```

## 2.3 构建 APK

```bash
cd BBDownAndroid
./build-apk.sh all release    # 一次构建 FFmpeg 6.x + 8.x
# 或单个版本
./build-apk.sh 6 release
./build-apk.sh 8 debug
```

产物在 `dist/`（自动命名 `BBDown-<版本>-ffmpeg-<FFmpeg版本>-<类型>.apk`），脚本自动执行
AAR 路径修复、native 库自检、签名验证。

# 第三章：Linux ARM64 环境（本机）

本机为 aarch64。Google 官方 Build-Tools 二进制（aapt2/zipalign/aidl 等）只有 x86_64 版，
**必须替换为 ARM64 版**，否则构建或签名阶段报 `Exec format error`。完整笔记见
[DEV_ENV_NOTES_LINUX_ARM64.md](DEV_ENV_NOTES_LINUX_ARM64.md)，要点：

1. SDK 安装与 x86_64 相同（`/opt/android-sdk`，platform 33 + build-tools 34.0.0）
2. 从 ARM64 工具包（本机 `/opt/android-arm-tools`）替换 SDK 中的二进制：
   ```bash
   cd /opt/android-sdk/build-tools/34.0.0
   for t in aapt aidl dexdump split-select zipalign; do
     cp /opt/android-arm-tools/build-tools/$t .
   done
   ```
   x86_64 原文件可先移到 `x86_64-backup/` 备份。
3. **AGP 自带 aapt2 也要补丁**：AGP 使用其 Maven 依赖内的 aapt2 JAR 中的二进制
   （`~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/aapt2/.../aapt2-*-linux.jar`），
   需将该 JAR 内的 `aapt2` 条目替换为 ARM64 版，否则 `processReleaseResources` 阶段失败。
4. `local.properties`：`sdk.dir=/opt/android-sdk`（**不要**残留 Windows 路径）
5. `gradle.properties`：仓库默认代理配置已注释，Linux 下无需代理，直接构建

# 第四章：编译 FFmpegKit AAR（可选，仅需自定义 FFmpeg 时）

> 构建 APK 只需要 `app/libs/` 下的预编译 AAR，本机已提供 v6/v8 两个版本，跳过本章不影响构建。

## 4.1 获取源码与依赖

```bash
git clone https://github.com/arthenica/ffmpeg-kit.git
sudo apt install pkg-config autoconf automake libtool wget curl unzip
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_ROOT=/opt/android-ndk
```

## 4.2 编译（arm64-v8a，full 库集）

```bash
./android.sh --disable-arm-v7a --disable-arm-v7a-neon \
  --disable-x86 --disable-x86-64 \
  --enable-android-media-codec --enable-android-zlib --full
# 产物: prebuilt/bundle-android-aar/ffmpeg-kit/ffmpeg-kit.aar
cp prebuilt/bundle-android-aar/ffmpeg-kit/ffmpeg-kit.aar \
   /path/to/BBDownAndroid/app/libs/ffmpeg-kit-full-v6.aar
```

FFmpeg 8.x 需在 `ffmpeg-kit/src` 下替换 FFmpeg 源码为 8.x（`git checkout n8.1.2`）后重新编译，
AAR 命名为 `ffmpeg-kit-full-v8.aar`。6.x 与 8.x 的源码结构/configure 选项差异由构建脚本自动处理。

## 4.3 AAR 已知坑（重要）

**Windows 反斜杠条目**：若 AAR 的 zip 条目使用反斜杠路径（`jni\arm64-v8a\xxx.so`），
AGP 无法提取 native 库，产出的 APK 会**缺少 .so**（体积显著偏小，安装后 FFmpeg 调用崩溃）。
`build-apk.sh` 会自动检测并重打包修复（需 python3）。手工修复：

```bash
python3 - <<'PYEOF'
import sys, zipfile, os
src = 'app/libs/ffmpeg-kit-full-v8.aar'
tmp = src + '.tmp'
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        name = item.filename.replace('\\', '/')
        zi = zipfile.ZipInfo(name, item.date_time)
        zi.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(zi, zin.read(item.filename))
os.replace(tmp, src)
PYEOF
```

# 第五章：构建 BBDown Android APK

## 5.1 AAR 文件

```
app/libs/
├── ffmpeg-kit-full-v6.aar   # FFmpeg 6.x（可选）
├── ffmpeg-kit-full-v8.aar   # FFmpeg 8.x（可选）
└── ffmpeg-kit-full.aar      # 向后兼容（视为 v6）
```

至少需要一个。`app/build.gradle` 通过 `-PffmpegVersion=6|8` 动态选择。

## 5.2 构建命令

```bash
cd BBDownAndroid

# 推荐：一键构建脚本（自动修复 AAR、自检、拷贝产物到 dist/）
./build-apk.sh              # 两个版本 release
./build-apk.sh 6            # 仅 FFmpeg 6.x
./build-apk.sh 8 debug      # 仅 FFmpeg 8.x debug
./build-apk.sh all release  # 全部

# 直接 Gradle（注意：两次构建会互相清理 apk 目录，产物需立即拷贝）
./gradlew assembleRelease -PffmpegVersion=6
./gradlew assembleRelease -PffmpegVersion=8
```

**已知行为**：Gradle 两次构建（不同 `-PffmpegVersion`）会互相清理 `app/build/outputs/apk/release/`
下的 APK，因此 `build-apk.sh` 在每个版本构建完成后**立即**拷贝到 `dist/`。
直接使用 Gradle 时请自行及时拷贝。

## 5.3 构建产物

```
dist/
├── BBDown-1.9.97-ffmpeg-6.1.6-release.apk   # FFmpeg 6.x
└── BBDown-1.9.97-ffmpeg-8.1.2-release.apk   # FFmpeg 8.x
```

## 5.4 签名验证

```bash
/opt/android-sdk/build-tools/34.0.0/apksigner verify --verbose --min-sdk-version 21 dist/*.apk
# 预期: Verified using v1 scheme: true / v2 scheme: true
```

# 常见问题

### Gradle 下载失败
`gradle.properties` 配置代理，或使用 settings.gradle 中已配置的阿里云镜像。

### Exec format error（仅 ARM64）
某个构建/签名工具还是 x86_64。检查：
```bash
readelf -h /opt/android-sdk/build-tools/34.0.0/aapt2 | grep Machine   # 应为 AArch64
readelf -h /opt/android-sdk/build-tools/34.0.0/zipalign | grep Machine
```
并按第三章替换为 ARM64 版。`~/.gradle/caches/.../aapt2-*-linux.jar` 内的 aapt2 也需检查。

### APK 体积偏小 / 安装后 FFmpeg 崩溃
APK 缺 native 库。`unzip -l app.apk | grep 'lib/arm64-v8a/'` 应为 9 个 .so。
若为 0，说明 AAR 的 zip 条目是反斜杠路径（见 4.3），运行 `build-apk.sh` 自动修复后重建。

### zipalign 验证失败
确保 build-tools 34.0.0 且 ARM64 版 zipalign；`signV1V2` 任务使用 `-p` 页对齐（已配置）。

### 内存不足（OOM）
FFmpeg 8.x 库较大。BBDown 已内置内存自适应（动态降线程数、onTrimMemory 响应、
largeHeap）。批量下载过多仍 OOM 时减少并发数。

# 环境变量汇总

```bash
# 必须
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64    # x86_64 为 ...-amd64
export ANDROID_SDK_ROOT=/opt/android-sdk

# 编译 FFmpeg 时需要
export ANDROID_NDK_ROOT=/opt/android-ndk

# 可选
export PATH=$PATH:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_NDK_ROOT
```

# 快速验证清单

- [ ] `java -version` 输出 17+
- [ ] `readelf -h $ANDROID_SDK_ROOT/build-tools/34.0.0/zipalign | grep Machine` 显示本机架构（ARM64 需 AArch64）
- [ ] `app/libs/` 下存在至少一个 AAR
- [ ] `./build-apk.sh all release` 成功生成两个 APK 于 `dist/`
- [ ] APK 含 9 个 `lib/arm64-v8a/*.so`，apksigner 验证通过
- [ ] APK 安装到设备后可正常启动、下载
