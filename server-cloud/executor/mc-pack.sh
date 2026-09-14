#!/bin/bash
# =============================================================================
# WebGame 云游戏执行面 —— 打包 MC 客户端（Forge 1.12.2 完整安装）
# -----------------------------------------------------------------------------
# 作用：把源执行面的 MC 客户端（含 .minecraft 版本/库/资源/natives）打包为
#       mc-backup.tar.gz，用于备份或迁移到新服务器（配合 mc-import.sh）。
# 用法：
#   sudo bash mc-pack.sh                     # 打包到当前目录 mc-backup.tar.gz
#   sudo bash mc-pack.sh /path/out.tar.gz    # 指定输出文件
#   APP_USER=xxx bash mc-pack.sh             # 指定用户
# =============================================================================
set -euo pipefail

APP_USER="${APP_USER:-webgame}"
MC_BASE="${MC_BASE:-/home/${APP_USER}/mc}"
OUT="${1:-${PWD}/mc-backup.tar.gz}"

log() { echo -e "\033[1;32m[pack]\033[0m $*"; }
die() { echo -e "\033[1;31m[pack!]\033[0m $*" >&2; exit 1; }

[[ -d "$MC_BASE/.minecraft" ]] || die "MC 目录不存在: $MC_BASE/.minecraft（请先确认已安装 MC 客户端）"
[[ -e "$OUT" ]] && die "输出文件已存在: $OUT（先删除或换路径）"

log "打包 $MC_BASE -> $OUT ..."
log "（包含 versions / libraries / assets / natives，可能需要数分钟）"
tar czf "$OUT" -C "$(dirname "$MC_BASE")" "$(basename "$MC_BASE")"

SIZE=$(du -h "$OUT" | cut -f1)
log "完成: $OUT ($SIZE)"
log "迁移到新机器: sudo bash mc-import.sh $OUT"
