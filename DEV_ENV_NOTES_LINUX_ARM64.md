# 开发环境说明（Linux ARM64 / 本机）

本文档记录 BBDownAndroid 在 **aarch64 Linux** 上的开发环境（当前机器实际环境）。
Windows 环境见 [DEV_ENV_NOTES.md](DEV_ENV_NOTES.md)，通用流程见 [ENVIRONMENT_SETUP.md](ENVIRONMENT_SETUP.md)。

## 工具链位置

| 组件 | 路径 | 说明 |
|------|------|------|
| JDK | `/usr/lib/jvm/java-17-openjdk-arm64` | OpenJDK 17.0.19 (arm64)，PATH 已含 |
| Android SDK | `/opt/android-sdk` | platforms;android-33、build-tools 34.0.0、platform-tools、cmdline-tools |
| ARM64 工具包 | `/opt/android-arm-tools` | aapt/aapt2/aidl/dexdump/split-select/zipalign 的 AArch64 版 |
| Gradle | wrapper 7.6.3 | 已缓存于 `~/.gradle/wrapper/dists/` |

## ARM64 关键处理（构建前必读）

Google 官方 Build-Tools 二进制只有 x86_64 版，aarch64 上直接运行报 `Exec format error`。已处理：

### 1. SDK build-tools 二进制替换

`/opt/android-sdk/build-tools/34.0.0/` 下以下文件已替换为 AArch64 版
（x86_64 原件备份在 `x86_64-backup/`）：

- `aapt`、`aapt2`（SDK 自带 aapt2 已是 ARM64）、`aidl`、`dexdump`、`split-select`、`zipalign`

验证：
```bash
readelf -h /opt/android-sdk/build-tools/34.0.0/zipalign | grep Machine   # 期望 AArch64
```

### 2. AGP 内置 aapt2 补丁

AGP 打包时使用其 Maven 依赖内的 aapt2（`aapt2-7.4.2-8841542-linux.jar`，位于
`~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/aapt2/.../`），
JAR 内的 `aapt2` 条目已替换为 ARM64 二进制。若清空 `~/.gradle` 缓存重新下载，
需重新打补丁（用 `zip -d` 删除原条目 + `zip -g` 加入 ARM64 版），否则
`processReleaseResources` 阶段失败。

### 3. local.properties

```properties
sdk.dir=/opt/android-sdk
```

> 注意：不要使用 Windows 路径（`C:/...`），脚本会检测并报错。

### 4. gradle.properties 代理

仓库中 `systemProp.http(s).proxy*`（原 127.0.0.1:7897，Windows 用）**默认已注释**，
Linux 无代理直连即可。若在 Windows 构建，需取消注释（见 DEV_ENV_NOTES.md）。

## FFmpeg AAR

`app/libs/` 下三个预编译 AAR（来自 https://github.com/xialiag/ffmpeg-kit/releases ）：

- `ffmpeg-kit-full-v6.aar` — FFmpeg 6.1.6（7.0 MB）
- `ffmpeg-kit-full-v8.aar` — FFmpeg 8.1.2（7.6 MB）
- `ffmpeg-kit-full-v9.aar` — FFmpeg 9.0（7.9 MB）

**已知坑**：v8 AAR 若 zip 条目为 Windows 反斜杠（`jni\arm64-v8a\...`），AGP 提取失败，
APK 缺 native 库。`build-apk.sh` 构建前自动检测并用 python 重打包修复（正斜杠）。
修复前后对比：
```bash
unzip -l app/libs/ffmpeg-kit-full-v8.aar | grep 'jni/'   # 修复后应为 jni/arm64-v8a/...
```

## 构建命令

```bash
cd /root/BBDownAndroid

# 一键构建全部版本（自动修复 AAR、自检、拷贝 dist/）
./build-apk.sh all release
./build-apk.sh 6 debug      # 单个版本
./build-apk.sh 9 release

# 直接 Gradle（产物会被下一次构建清理，务必及时拷贝）
./gradlew assembleRelease -PffmpegVersion=6
./gradlew assembleRelease -PffmpegVersion=8
./gradlew assembleRelease -PffmpegVersion=9
```

产物：`dist/BBDown-<版本>-ffmpeg-6.1.6-release.apk`、`-8.1.2-`、`-9.0-`
（每个约 19-21 MB，含 9 个 `lib/arm64-v8a/*.so`）。

## 签名

Linux 下 `signV1V2` 任务自动执行（zipalign -p 页对齐 + apksigner v1+v2 重签名）：
- keystore：`bbdown-release.keystore`（alias=bbdown，storepass/keypass=bbdown123，开发证书）
- 验证：`apksigner verify --min-sdk-version 21 dist/*.apk` → v1=true, v2=true

## 发布流程（Git + GitHub Release）

```bash
# 推送代码
git add -A && git commit -m "..." && git push origin main

# 打 tag 并推送（一个版本号一个 tag，三版本 APK 同放一个 release）
git tag v<版本>
git push origin v<版本>

# 创建 release（GitHub API，需 token 在 ~/.git-credentials）
# POST /repos/xialiag/BBDownAndroid/releases  + 上传 3 个 APK 到 release assets
```

仓库：https://github.com/xialiag/BBDownAndroid（token 用户 xialiag，凭据在 `~/.git-credentials`）。

## 已验证结论（2026-07-31）

- `./build-apk.sh all release`：两个版本构建成功，dist 产物 19.5 MB / 20.6 MB
- 两 APK 均含 9 个 arm64-v8a native 库，apksigner v1+v2 验证通过
- AAR 反斜杠修复生效（修复前 ff8 APK 仅 4.8 MB 且无 .so；修复后 20.6 MB）
- 发布：v1.9.97-ff6 / v1.9.97-ff8 两个 GitHub Release 均含对应 APK
