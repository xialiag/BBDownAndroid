#!/usr/bin/env python3
"""Patch libffmpegkit.so: fix 'Binder threadpool cannot be shrunk after starting' SIGABRT.

根因: ffmpeg-kit 在 Android 15+(API>=35) 上每次执行 ffmpeg 都会调用
ABinderProcess_setThreadPoolMaxThreadCount(1)。宿主 app 的 binder 线程池早已启动
(默认 max 15), AOSP 拒绝 shrink 直接 LOG_ALWAYS_FATAL 杀进程。崩溃发生在混流阶段
(FFmpegMuxer.injectMetadataOnly), 即 audio_only 下载注入封面元数据时。

修复: 把立即数 1 patch 成 64 (mov w0,#1 -> mov w0,#64)。
64 > 默认 max 15, set 只增不缩, 不触发 abort; 后续每次执行 set(64) 等于当前值, 恒安全。

用法: python3 tools/patch-ffmpegkit-binder.py <libffmpegkit.so>
会先在 so 中定位 "mov w0,#1; blr x20" 模式(setThreadPoolMaxThreadCount 调用点)再 patch,
无匹配时报错退出。v6 构建无此代码(旧版无 binder 补丁), 会正常报"无需 patch"。
"""
import re
import struct
import subprocess
import sys


def find_patch_sites(so: str):
    out = subprocess.run(
        ["llvm-objdump", "-d", "--no-show-raw-insn", so],
        capture_output=True, text=True,
    ).stdout
    lines = out.splitlines()
    sites = []
    for i, ln in enumerate(lines):
        m = re.match(r"\s+([0-9a-f]+):\s+mov\s+w0, #0x1\s*(?://.*)?$", ln)
        if not m:
            continue
        nxt = lines[i + 1] if i + 1 < len(lines) else ""
        # set_thread_pool_max 调用点: mov w0,#1 之后紧跟 blr x20
        if "blr" in nxt and "x20" in nxt:
            sites.append(int(m.group(1), 16))
    return sites


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    so = sys.argv[1]
    sites = find_patch_sites(so)
    if not sites:
        print("未找到 patch 点: 该 so 无 binder threadpool init 代码(旧构建), 无需 patch")
        return
    with open(so, "r+b") as f:
        for addr in sites:
            f.seek(addr)
            cur = f.read(4)
            if cur != struct.pack("<I", 0x52800020):
                print(f"0x{addr:x}: 意外字节 {cur.hex()}, 跳过")
                continue
            f.seek(addr)
            f.write(struct.pack("<I", 0x52800800))  # mov w0,#64
            print(f"0x{addr:x}: mov w0,#1 -> mov w0,#64")


if __name__ == "__main__":
    main()
