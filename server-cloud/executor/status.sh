#!/bin/bash
# =============================================================================
# WebGame 云游戏执行面 —— 一键状态检查（只读，无需 root）
# 用法: bash status.sh
# =============================================================================
set -uo pipefail

APP_USER="${APP_USER:-webgame}"
CTRL_PORT="${CTRL_PORT:-25576}"

log() { echo -e "\033[1;36m[status]\033[0m $*"; }
ok()  { echo -e "  \033[1;32m✔\033[0m $*"; }
bad() { echo -e "  \033[1;31m✘\033[0m $*"; }
inf() { echo -e "  \033[1;33m·\033[0m $*"; }

log "===== WebGame 云游戏执行面状态 ====="

# 1. 管控服务
if systemctl list-unit-files 2>/dev/null | grep -q webgame-cc; then
  if systemctl is-active --quiet webgame-cc.service; then
    ok "webgame-cc.service: active"
  else
    bad "webgame-cc.service: $(systemctl is-active webgame-cc.service 2>/dev/null || echo 未安装)"
  fi
else
  bad "webgame-cc.service 未安装（请先 bash deploy.sh）"
fi

# 2. 管控端口
if command -v python3 >/dev/null 2>&1; then
  if python3 -c "import socket;s=socket.socket();s.settimeout(3);s.connect(('127.0.0.1',${CTRL_PORT}));print('ok')" >/dev/null 2>&1; then
    ok "管控端口 ${CTRL_PORT}: 监听中（TCP_OK）"
  else
    bad "管控端口 ${CTRL_PORT}: 未监听（服务端插件未连接或服务未启动）"
  fi
fi

# 3. 运行用户与目录
if id "$APP_USER" >/dev/null 2>&1; then
  ok "运行用户: $APP_USER (uid=$(id -u "$APP_USER"))"
  HOME_DIR="$(getent passwd "$APP_USER" | cut -d: -f6)"
  [[ -f "$HOME_DIR/control_client.py" ]] && ok "control_client.py 已部署" || bad "control_client.py 缺失"
  [[ -d "$HOME_DIR/instances" ]] && ok "实例目录存在" || bad "实例目录缺失"
else
  bad "运行用户 $APP_USER 不存在"
  HOME_DIR=""
fi

# 4. Xvnc / openbox / 实例
XVNC_COUNT=$(pgrep -fc "/usr/bin/Xvnc :" 2>/dev/null || echo 0)
OPENBOX_COUNT=$(pgrep -fc "^/usr/bin/openbox" 2>/dev/null || echo 0)
MC_COUNT=$(pgrep -fc "net.minecraft.launchwrapper.Launch" 2>/dev/null || echo 0)
inf "Xvnc 进程: $XVNC_COUNT 个 | openbox: $OPENBOX_COUNT 个 | MC 客户端: $MC_COUNT 个"

# 5. KasmVNC 端口（8542 起）
if command -v ss >/dev/null 2>&1; then
  KASM_PORTS=$(ss -tlnp 2>/dev/null | grep -E ':(85[4-9][0-9]) ' | awk '{print $4}' | sort -u | tr '\n' ' ')
  [[ -n "$KASM_PORTS" ]] && ok "KasmVNC 监听: $KASM_PORTS" || inf "无 KasmVNC 监听（无活跃实例时正常）"
fi

# 6. 最近日志
if systemctl list-unit-files 2>/dev/null | grep -q webgame-cc; then
  inf "最近日志（journalctl -u webgame-cc --no-pager | tail -5）:"
  journalctl -u webgame-cc --no-pager 2>/dev/null | tail -5 | sed 's/^/    /'
fi

log "===== 检查完成 ====="
