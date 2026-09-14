#!/bin/bash
# =============================================================================
# WebGame 云游戏执行面 —— 导入 MC 客户端（配合 mc-pack.sh）
# -----------------------------------------------------------------------------
# 作用：把 mc-backup.tar.gz 解包到本机 MC 目录。
# 用法：
#   sudo bash mc-import.sh                        # 自动用 packages/mc-backup.tar.gz
#   sudo bash mc-import.sh /path/to/mc-backup.tar.gz
#   APP_USER=xxx bash mc-import.sh <包>
# =============================================================================
set -euo pipefail

APP_USER="${APP_USER:-webgame}"
MC_BASE="${MC_BASE:-/home/${APP_USER}/mc}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_DIR="$(dirname "$SCRIPT_DIR")/packages"
TARBALL="${1:-}"

log() { echo -e "\033[1;32m[import]\033[0m $*"; }
die() { echo -e "\033[1;31m[import!]\033[0m $*" >&2; exit 1; }

# 未指定包时，优先使用本地 packages/mc-backup.tar.gz
if [[ -z "$TARBALL" ]]; then
  if [[ -f "$PKG_DIR/mc-backup.tar.gz" ]]; then
    TARBALL="$PKG_DIR/mc-backup.tar.gz"
    log "使用本地包: $TARBALL"
  else
    die "未指定包且 packages/mc-backup.tar.gz 不存在。请用 mc-pack.sh 打包或传入路径"
  fi
fi

[[ -f "$TARBALL" ]] || die "文件不存在: $TARBALL"
[[ "$(id -u)" -eq 0 ]] || die "请用 root 运行（需要 chown）"
id "$APP_USER" >/dev/null 2>&1 || die "用户 $APP_USER 不存在（请先 bash deploy.sh）"

[[ -d "$MC_BASE/.minecraft" ]] && {
  echo "  警告: $MC_BASE/.minecraft 已存在，导入会覆盖。"
  echo "  3 秒后继续（Ctrl+C 取消）..."
  sleep 3
}

log "解包 $TARBALL -> $MC_BASE ..."
mkdir -p "$MC_BASE"
tar xzf "$TARBALL" -C "$(dirname "$MC_BASE")"

log "修正属主 ..."
chown -R "$APP_USER:$APP_USER" "$MC_BASE"

log "校验关键路径:"
for p in \
  "$MC_BASE/.minecraft/versions/1.12.2-Forge_14.23.5.2864/1.12.2-Forge_14.23.5.2864.jar" \
  "$MC_BASE/.minecraft/natives-linux" \
  "$MC_BASE/.minecraft/libraries"; do
  if [[ -e "$p" ]]; then
    log "  ✔ $p"
  else
    echo -e "\033[1;33m  ✘ $p\033[0m"
  fi
done

log "导入完成。验证: bash status.sh 后由插件发起 SPAWN 实测"
