#!/bin/bash
# ============================================================
# BBDown Android APK 构建脚本
# 支持 Linux(x86_64 / ARM64) 与 Windows(Git Bash / MSYS2)
#
# 用法：
#   ./build-apk.sh              # 构建全部 FFmpeg 版本 release
#   ./build-apk.sh 6            # 仅 FFmpeg 6.x release
#   ./build-apk.sh 8            # 仅 FFmpeg 8.x release
#   ./build-apk.sh 9            # 仅 FFmpeg 9.x release
#   ./build-apk.sh all          # 全部版本 release
#   ./build-apk.sh 6 debug      # FFmpeg 6.x debug
#   ./build-apk.sh 9 debug      # FFmpeg 9.x debug
#
# 脚本自动处理：
#   1. AAR 路径修复：FFmpeg 8 AAR 若含 Windows 反斜杠条目(jni\arm64-v8a\)，
#      重打包为正斜杠，否则 AGP 无法提取 .so，产物会缺 native 库
#   2. 产物互覆盖防护：两次构建(Gradle)会互相清理 release 目录，
#      每个版本构建完成后立即拷贝到 dist/ 并重命名
#   3. 产物自检：native 库数量 + apksigner 签名验证
#   4. Windows 环境检查 gradle.properties 代理配置
# ============================================================

set -e

# ===== 版本映射(用于产物命名) =====
FF6_LABEL="6.1.6"
FF8_LABEL="8.1.2"
FF9_LABEL="9.0"

# ===== 参数解析 =====
FFMPEG_VERSION="${1:-all}"
BUILD_TYPE="${2:-release}"

case "$FFMPEG_VERSION" in
  6|8|9|all) ;;
  *) echo "错误：FFmpeg 版本必须是 6、8、9 或 all(当前: $FFMPEG_VERSION)"; echo "用法：$0 [6|8|9|all] [debug|release]"; exit 1 ;;
esac
case "$BUILD_TYPE" in
  debug|release) ;;
  *) echo "错误：构建类型必须是 debug 或 release(当前: $BUILD_TYPE)"; exit 1 ;;
esac

# ===== 路径设置 =====
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${SCRIPT_DIR}/app"
LIBS_DIR="${APP_DIR}/libs"
DIST_DIR="${SCRIPT_DIR}/dist"
OUTPUT_DIR="${APP_DIR}/build/outputs/apk/${BUILD_TYPE}"

# ===== 环境检测 =====
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ENV_NAME="windows" ;;
  Linux*) ENV_NAME="linux" ;;
  *) ENV_NAME="unknown" ;;
esac
ARCH="$(uname -m)"
echo "============================================================"
echo " BBDown Android APK 构建"
echo "============================================================"
echo " 环境:      ${ENV_NAME} / ${ARCH}"
echo " FFmpeg:    v${FFMPEG_VERSION}"
echo " 构建类型:  ${BUILD_TYPE}"
echo "============================================================"
echo ""

PYTHON_BIN="$(command -v python3 || command -v python || true)"

# ===== Windows 代理检查 =====
if [ "$ENV_NAME" = "windows" ]; then
  if ! grep -qE '^systemProp\.(http|https)\.proxyHost=127\.0\.0\.1' gradle.properties 2>/dev/null; then
    echo "⚠ 警告：Windows 环境需要代理才能下载依赖，但 gradle.properties 中未找到 127.0.0.1 代理配置。"
    echo "  请取消 gradle.properties 中 systemProp.http(s).proxy* 的注释(端口按本机实际代理修改)。"
    echo ""
  fi
fi

# ===== local.properties 检查 =====
if [ -f local.properties ]; then
  SDK_DIR="$(grep '^sdk.dir=' local.properties | cut -d= -f2)"
  if echo "$SDK_DIR" | grep -q '^C:'; then
    echo "⚠ 警告：local.properties 指向 Windows 路径($SDK_DIR)，当前环境为 $ENV_NAME。"
    echo "  请改为本机 SDK 路径，例如：sdk.dir=/opt/android-sdk"
    exit 1
  fi
fi

# ===== AAR 检查与修复 =====
check_aar() {
  local ver="$1"
  local aar="${LIBS_DIR}/ffmpeg-kit-full-v${ver}.aar"
  if [ ! -f "$aar" ]; then
    echo "✗ 错误：未找到 ${aar}"
    echo "  请将 FFmpeg ${ver}.x 编译的 AAR 命名为 ffmpeg-kit-full-v${ver}.aar 放入 app/libs/"
    exit 1
  fi
  echo "✓ 找到 AAR: $(basename "$aar") ($(du -h "$aar" | cut -f1))"

  # 修复 Windows 反斜杠 zip 条目：jni\arm64-v8a\xxx.so → jni/arm64-v8a/xxx.so
  if [ -n "$PYTHON_BIN" ]; then
    "$PYTHON_BIN" - "$aar" <<'PYEOF'
import sys, zipfile, os
src = sys.argv[1]
try:
    zin = zipfile.ZipFile(src)
except Exception as e:
    print(f"  ⚠ 无法读取 AAR: {e}")
    sys.exit(0)
bad = [i for i in zin.infolist() if '\\' in i.filename]
if not bad:
    print("  AAR 路径正常(正斜杠)")
    sys.exit(0)
tmp = src + '.tmp'
with zipfile.ZipFile(src) as zin2, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin2.infolist():
        name = item.filename.replace('\\', '/')
        zi = zipfile.ZipInfo(name, item.date_time)
        zi.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(zi, zin2.read(item.filename))
os.replace(tmp, src)
print(f"  ✓ 已修复 {len(bad)} 个反斜杠路径条目(否则 AGP 无法提取 .so)")
PYEOF
  else
    echo "  ⚠ 未找到 python，跳过 AAR 路径检查(如产物缺少 native 库请安装 python 后重试)"
  fi
}

# ===== 版本名(用于产物命名) =====
VERSION_NAME="$(grep 'versionName' "${APP_DIR}/build.gradle" | sed 's/.*"\(.*\)".*/\1/' | head -1)"

# ===== 构建单个版本 =====
build_one() {
  local ver="$1"
  local label
  case "$ver" in
    6) label="$FF6_LABEL" ;;
    8) label="$FF8_LABEL" ;;
    9) label="$FF9_LABEL" ;;
  esac
  echo ""
  echo ">>> 开始构建 FFmpeg v${ver} (${BUILD_TYPE}) ..."
  echo ""

  if [ "$ENV_NAME" = "windows" ]; then
    ./gradlew.bat "assemble${BUILD_TYPE^}" -PffmpegVersion="$ver" --console=plain
  else
    ./gradlew "assemble${BUILD_TYPE^}" -PffmpegVersion="$ver" --console=plain
  fi

  # ===== 拷贝产物到 dist/ (立即拷贝，防止下次构建清理) =====
  local apk="${OUTPUT_DIR}/app-${BUILD_TYPE}-ff${ver}.apk"
  if [ ! -f "$apk" ]; then
    echo "✗ 错误：构建成功但未找到产物 $apk"
    exit 1
  fi
  mkdir -p "$DIST_DIR"
  local out_name="BBDown-${VERSION_NAME}-ffmpeg-${label}-${BUILD_TYPE}.apk"
  cp "$apk" "${DIST_DIR}/${out_name}"
  echo "✓ 产物已保存: dist/${out_name} ($(du -h "${DIST_DIR}/${out_name}" | cut -f1))"

  # ===== 自检：native 库 =====
  if [ -n "$PYTHON_BIN" ]; then
    local so_count
    so_count="$("$PYTHON_BIN" -c "
import zipfile, sys
z = zipfile.ZipFile('$apk')
n = sum(1 for i in z.infolist() if i.filename.startswith('lib/arm64-v8a/') and i.filename.endswith('.so'))
print(n)
")"
    if [ "$so_count" -eq 0 ]; then
      echo "✗ 错误：APK 内未发现 native 库(lib/arm64-v8a/*.so)！"
      echo "  请检查 AAR 是否含 jni/arm64-v8a/ 条目，或重新运行本脚本执行路径修复"
      exit 1
    fi
    echo "✓ native 库: ${so_count} 个 (arm64-v8a)"
  fi

  # ===== 自检：签名 =====
  local apksigner=""
  if [ "$ENV_NAME" = "windows" ]; then
    [ -f "${SDK_DIR}/build-tools/34.0.0/apksigner.bat" ] && apksigner="${SDK_DIR}/build-tools/34.0.0/apksigner.bat"
  else
    [ -f "${SDK_DIR}/build-tools/34.0.0/apksigner" ] && apksigner="${SDK_DIR}/build-tools/34.0.0/apksigner"
  fi
  if [ -n "$apksigner" ]; then
    if "$apksigner" verify --min-sdk-version 21 "$apk" >/dev/null 2>&1; then
      echo "✓ 签名验证: OK (v1+v2)"
    else
      echo "⚠ 签名验证未通过(apksigner verify 失败)，请检查 signingConfig"
    fi
  fi
}

# ===== 主流程 =====
VERSIONS=()
[ "$FFMPEG_VERSION" = "all" ] && VERSIONS=(6 8 9) || VERSIONS=("$FFMPEG_VERSION")

for v in "${VERSIONS[@]}"; do
  check_aar "$v"
done

for v in "${VERSIONS[@]}"; do
  build_one "$v"
done

echo ""
echo "============================================================"
echo " 全部构建完成！产物位于 dist/"
echo "============================================================"
ls -la "$DIST_DIR" | grep '\.apk'
