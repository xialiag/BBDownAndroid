#!/bin/bash
# ============================================================
# BBDown Android APK 构建脚本
# 支持编译时选择 FFmpeg 版本（6.x 或 8.x）
#
# 用法：
#   ./build-apk.sh          # 默认使用 FFmpeg 6.x 构建
#   ./build-apk.sh 6        # 使用 FFmpeg 6.x 构建
#   ./build-apk.sh 8        # 使用 FFmpeg 8.x 构建
#   ./build-apk.sh 6 debug  # 构建 debug 版本
#   ./build-apk.sh 8 release # 构建 release 版本
# ============================================================

set -e

# ===== 参数解析 =====
FFMPEG_VERSION="${1:-6}"
BUILD_TYPE="${2:-release}"

# 验证版本参数
if [[ "$FFMPEG_VERSION" != "6" && "$FFMPEG_VERSION" != "8" ]]; then
    echo "错误：FFmpeg 版本必须是 6 或 8"
    echo "用法：$0 [6|8] [debug|release]"
    exit 1
fi

# 验证构建类型
if [[ "$BUILD_TYPE" != "debug" && "$BUILD_TYPE" != "release" ]]; then
    echo "错误：构建类型必须是 debug 或 release"
    echo "用法：$0 [6|8] [debug|release]"
    exit 1
fi

# ===== 路径设置 =====
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${SCRIPT_DIR}/app"
LIBS_DIR="${APP_DIR}/libs"
OUTPUT_DIR="${SCRIPT_DIR}/app/build/outputs/apk/${BUILD_TYPE}"

# ===== AAR 文件检查 =====
VERSIONED_AAR="${LIBS_DIR}/ffmpeg-kit-full-v${FFMPEG_VERSION}.aar"
FALLBACK_AAR="${LIBS_DIR}/ffmpeg-kit-full.aar"

echo "============================================================"
echo " BBDown Android APK 构建"
echo "============================================================"
echo " FFmpeg 版本: v${FFMPEG_VERSION}"
echo " 构建类型:    ${BUILD_TYPE}"
echo "============================================================"
echo ""

# 检查 AAR 是否存在
if [[ -f "${VERSIONED_AAR}" ]]; then
    AAR_SIZE=$(du -h "${VERSIONED_AAR}" | cut -f1)
    echo "✓ 找到版本化 AAR: $(basename ${VERSIONED_AAR}) (${AAR_SIZE})"
elif [[ -f "${FALLBACK_AAR}" ]]; then
    AAR_SIZE=$(du -h "${FALLBACK_AAR}" | cut -f1)
    echo "⚠ 未找到 ffmpeg-kit-full-v${FFMPEG_VERSION}.aar"
    echo "  使用向后兼容 AAR: ffmpeg-kit-full.aar (${AAR_SIZE})"
    if [[ "$FFMPEG_VERSION" == "8" ]]; then
        echo "  警告：该 AAR 可能不是 FFmpeg 8.x 版本！"
        echo "  请将 FFmpeg 8.x 编译的 AAR 命名为 ffmpeg-kit-full-v8.aar 并放入 app/libs/"
        echo ""
        read -p "  是否继续构建？(y/N) " -n 1 -r
        echo ""
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            echo "构建已取消。"
            exit 0
        fi
    fi
else
    echo "✗ 错误：未找到任何 FFmpegKit AAR 文件！"
    echo "  请将 AAR 文件放入: ${LIBS_DIR}/"
    echo "  命名方式："
    echo "    ffmpeg-kit-full-v6.aar  (FFmpeg 6.x)"
    echo "    ffmpeg-kit-full-v8.aar  (FFmpeg 8.x)"
    echo "    ffmpeg-kit-full.aar     (向后兼容，视为 v6)"
    exit 1
fi

echo ""

# ===== 执行 Gradle 构建 =====
echo ">>> 开始 Gradle 构建..."
echo ""

cd "${SCRIPT_DIR}"

# 使用 -PffmpegVersion 传递版本参数
./gradlew "assemble${BUILD_TYPE^}" \
    -PffmpegVersion="${FFMPEG_VERSION}" \
    --no-daemon \
    2>&1

echo ""

# ===== 查找构建产物 =====
# APK 文件名包含 ff6 或 ff8 标识
APK_PATTERN="*ff${FFMPEG_VERSION}*.apk"
APK_FILES=$(find "${OUTPUT_DIR}" -name "${APK_PATTERN}" 2>/dev/null || true)

if [[ -z "${APK_FILES}" ]]; then
    # 回退：查找任何 APK
    APK_FILES=$(find "${OUTPUT_DIR}" -name "*.apk" 2>/dev/null || true)
fi

if [[ -z "${APK_FILES}" ]]; then
    echo "✗ 构建失败：未找到 APK 输出文件"
    exit 1
fi

# ===== 输出结果 =====
echo "============================================================"
echo " 构建成功！"
echo "============================================================"
for apk in ${APK_FILES}; do
    APK_SIZE=$(du -h "${apk}" | cut -f1)
    echo " APK: $(basename ${apk}) (${APK_SIZE})"
    echo " 路径: ${apk}"
done
echo ""
echo " FFmpeg 版本: v${FFMPEG_VERSION}"
echo " 构建类型:    ${BUILD_TYPE}"
echo "============================================================"
