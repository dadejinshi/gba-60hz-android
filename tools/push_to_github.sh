#!/usr/bin/env bash
# 一键把本工程推送到 GitHub（在已登录 gh 的环境执行）
# 用法： REPO=dadejinshi/gba-60hz-android bash tools/push_to_github.sh
set -euo pipefail

REPO="${REPO:-dadejinshi/gba-60hz-android}"
BRANCH="${BRANCH:-main}"

echo "→ 目标仓库: https://github.com/$REPO"

# 1) 确认 gh 已登录
if ! gh auth status >/dev/null 2>&1; then
  echo "✗ gh 未登录，请先执行: gh auth login"
  exit 1
fi

# 2) 若仓库不存在则创建（公开）
if ! gh repo view "$REPO" >/dev/null 2>&1; then
  echo "→ 创建公开仓库 $REPO ..."
  gh repo create "$REPO" --public --source=. --remote=origin --push
else
  echo "→ 仓库已存在，配置远端并推送..."
  git remote remove origin 2>/dev/null || true
  git remote add origin "https://github.com/$REPO.git"
  git branch -M "$BRANCH"
  git push -u origin "$BRANCH"
fi

echo ""
echo "✅ 推送完成"
echo "→ 出包：打开 https://github.com/$REPO/actions"
echo "   选 'Build GBA 60Hz APK' → Run workflow → 跑完在 Artifacts 下载 APK"
