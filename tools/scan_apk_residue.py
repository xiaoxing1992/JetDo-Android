#!/usr/bin/env python3
"""APK 私有资产 / 闭源 SDK 残留扫描。

用途：F-Droid 上架前自检 —— 确认产物里既没有闭源 SDK，也没有把自建域名 /
服务器 IP / 个人账号名编进 APK。

两个要点：
1. **SDK 公开类名可以写死在脚本里**（com/tencent/smtt 等是公开信息，
   写在仓库里不构成泄露，而且正是要检测的目标）。
2. **私有资产字面量必须放在仓库外**。扫描 APK 是为了证明产物干净；
   把"要查的域名/IP"直接写进 Git 仓库，等于在脱敏完成后又把真实资产
   写回仓库 —— 扫的是 APK，泄露的是 Git 历史。所以私有词表走
   `--patterns` 指定的本地文件，默认 `tools/scan-patterns.local.txt`
   （已在 .gitignore 中）。

用法：
    # 扫一个 APK
    python3 tools/scan_apk_residue.py app/build/outputs/apk/release/app-release-unsigned.apk

    # 指定本地私有词表
    python3 tools/scan_apk_residue.py <apk> --patterns ~/my-patterns.txt

    # 直接扫已解压目录
    python3 tools/scan_apk_residue.py /tmp/apkfd --dir

退出码：全部干净返回 0，有残留返回 1。
"""

import argparse
import os
import sys
import zipfile

# 闭源 SDK 与 Google 专有依赖的公开类名/包名 —— 直接写死，不构成泄露
BUILTIN_PATTERNS = [
    b"com/tencent/smtt",     # 腾讯 TBS/X5
    b"tbssdk",
    b"com/tencent/bugly",   # 腾讯 Bugly
    b"Lorg/acra",           # ACRA
    b"com/google/firebase",
    b"com/google/android/gms",  # GMS
    b"com/google/android/play",
    b"androidx.browser",    # 悬空依赖，已从构建移除
]


def load_extra_patterns(path: str | None) -> list[bytes]:
    """从仓库外的本地词表读取私有资产字面量。"""
    if not path:
        return []
    if not os.path.exists(path):
        print(f"⚠️  私有词表不存在：{path}（跳过私有资产检查）")
        return []
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                out.append(line.encode("utf-8"))
    return out


def collect_blobs(apk_path: str, as_dir: bool) -> list[bytes]:
    """把 APK（或已解压目录）里的每个文件读成 bytes。"""
    blobs = []
    if as_dir:
        for dp, _, fns in os.walk(apk_path):
            for fn in fns:
                fp = os.path.join(dp, fn)
                try:
                    with open(fp, "rb") as f:
                        blobs.append(f.read())
                except OSError:
                    pass
    else:
        with zipfile.ZipFile(apk_path) as z:
            for name in z.namelist():
                if name.endswith("/"):
                    continue
                try:
                    blobs.append(z.read(name))
                except (KeyError, OSError):
                    pass
    return blobs


def main() -> int:
    ap = argparse.ArgumentParser(description="扫描 APK 中的闭源 SDK 与私有资产残留")
    ap.add_argument("target", help="APK 路径，或 --dir 时的解压目录")
    ap.add_argument("--patterns", default="tools/scan-patterns.local.txt",
                    help="私有资产词表路径（仓库外，默认 tools/scan-patterns.local.txt）")
    ap.add_argument("--dir", action="store_true", help="target 是解压目录而非 APK")
    args = ap.parse_args()

    if not os.path.exists(args.target):
        print(f"❌ 目标不存在：{args.target}")
        return 2

    blobs = collect_blobs(args.target, args.dir)
    if not blobs:
        print(f"❌ 未读到任何内容：{args.target}")
        return 2

    pats = BUILTIN_PATTERNS + load_extra_patterns(args.patterns)
    print(f"扫描目标：{args.target}（{len(blobs)} 个文件，{len(pats)} 个模式）")
    print("-" * 58)

    dirty = 0
    for p in pats:
        total = sum(b.count(p) for b in blobs)
        if total:
            dirty += 1
        mark = "✅" if total == 0 else "❌"
        print(f"{mark} {p.decode('utf-8', errors='replace'):26s} = {total}")

    print("-" * 58)
    if dirty:
        print(f"❌ {dirty} 项存在残留，需处理")
        return 1
    print("✅ 全部 0：产物中无闭源 SDK、无私有资产字面量")
    return 0


if __name__ == "__main__":
    sys.exit(main())
