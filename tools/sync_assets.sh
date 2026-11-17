#!/usr/bin/env bash
# 把构建好的单文件 HTML 同步进 Android assets。
# 用法：bash tools/sync_assets.sh [源HTML路径]
# 默认源：../GBA-60Hz/dist/GBA-60Hz.html
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SRC="${1:-$ROOT/../GBA-60Hz/dist/GBA-60Hz.html}"
DST="$ROOT/app/src/main/assets/GBA-60Hz.html"

if [[ ! -f "$SRC" ]]; then
  echo "✗ 找不到源文件：$SRC" >&2
  echo "  请先在项目 web/ 目录执行 python3 build.py，或手动指定路径。" >&2
  exit 1
fi

mkdir -p "$(dirname "$DST")"
cp "$SRC" "$DST"
SIZE=$(wc -c < "$DST")
echo "✓ 已同步：$DST"
echo "  大小：$((SIZE / 1024)) KB ($SIZE bytes)"

# 校验关键内联资产存在（占位符应已被替换）
if grep -q '__WASM_B64__' "$DST"; then
  echo "⚠ 警告：成品仍含 __WASM_B64__ 占位符，可能未正确构建！" >&2
fi
