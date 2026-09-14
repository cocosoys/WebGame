#!/bin/bash
# =============================================================================
# WebGame 云游戏执行面 —— 一键更新
# -----------------------------------------------------------------------------
# 作用：把仓库中的最新 control_client.py 同步到执行面并重启管控服务，
#       保持"运行代码 == 仓库代码"。
# 用法：
#   sudo bash update.sh                     # 默认用本脚本同目录的 control_client.py
#   sudo bash update.sh --repo /path/to/WebGame/server-cloud/executor
#   sudo bash update.sh --skip-restart      # 只同步文件，不重启服务
# =============================================================================
set -euo pipefail

APP_USER="${APP_USER:-webgame}"
CTRL_PORT="${CTRL_PORT:-25576}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$SCRIPT_DIR"
SKIP_RESTART=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)   REPO_DIR="${2:?}"; shift 2 ;;
    --skip-restart) SKIP_RESTART=1; shift ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

log() { echo -e "\033[1;32m[update]\033[0m $*"; }
die() { echo -e "\033[1;31m[update!]\033[0m $*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || die "请用 root 运行（sudo bash update.sh）"
SRC="$REPO_DIR/control_client.py"
[[ -f "$SRC" ]] || die "找不到源文件: $SRC"

HOME_DIR="$(getent passwd "$APP_USER" | cut -d: -f6)"
log "同步 $SRC -> $HOME_DIR/control_client.py"
install -o root -g root -m 0755 "$SRC" "$HOME_DIR/control_client.py"

# 语法自检
python3 -m py_compile "$HOME_DIR/control_client.py" || die "Python 语法检查失败，请检查代码"
log "Python 语法检查通过"

if [[ "$SKIP_RESTART" -eq 1 ]]; then
  log "已跳过重启（--skip-restart）"
  exit 0
fi

log "重启 webgame-cc.service ..."
systemctl restart webgame-cc.service || die "重启失败: journalctl -u webgame-cc --no-pager"
sleep 2
systemctl is-active --quiet webgame-cc.service || die "服务未激活"

if python3 -c "import socket;s=socket.socket();s.settimeout(3);s.connect(('127.0.0.1',${CTRL_PORT}));print('ok')" >/dev/null 2>&1; then
  log "更新完成，管控端口 ${CTRL_PORT} 探测通过（TCP_OK）"
else
  warn_echo() { echo -e "\033[1;33m[update!]\033[0m $*"; }
  warn_echo "服务已重启但 ${CTRL_PORT} 暂未监听（等服务器插件重连后建立）"
fi
log "完成"
