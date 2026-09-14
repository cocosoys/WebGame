#!/bin/bash
# =============================================================================
# WebGame 云游戏执行面 —— 一键部署
# -----------------------------------------------------------------------------
# 作用：在目标 Linux/WSL2 主机上从零部署云游戏执行面：
#   * 创建运行用户（默认 webgame，加入 sudo）
#   * 安装系统依赖（openbox / xdotool / xdpyinfo / python3 / Java 8 等）
#   * 安装 KasmVNC（可指定本地 deb 或自动下载，默认 1.5.0）
#   * 部署 control_client.py、KasmVNC 配置、xstartup
#   * 创建目录、写入 systemd 单元并启动管控服务
#
# 用法：
#   sudo bash deploy.sh
#   sudo bash deploy.sh --kasm-deb /path/to/kasmvncserver_1.5.0-1_amd64.deb
#   APP_USER=clouduser MC_BASE=/opt/mc sudo bash deploy.sh
#
# 环境变量（均有默认值）：
#   APP_USER   运行用户（默认 webgame）
#   MC_BASE    MC 客户端根目录（默认 /home/$APP_USER/mc）
#   INSTANCES  实例目录（默认 /home/$APP_USER/instances）
#   CTRL_PORT  管控 TCP 端口（默认 25576）
#   KASM_VER   KasmVNC 版本（默认 1.5.0）
#   KASM_DEB   KasmVNC 本地 deb 路径（优先于自动下载）
# =============================================================================
set -euo pipefail

# ---------- 0. 参数与常量 ----------
APP_USER="${APP_USER:-webgame}"
MC_BASE="${MC_BASE:-/home/${APP_USER}/mc}"
INSTANCES="${INSTANCES:-/home/${APP_USER}/instances}"
CTRL_PORT="${CTRL_PORT:-25576}"
KASM_VER="${KASM_VER:-1.5.0}"
KASM_DIST="${KASM_DIST:-jammy}"
KASM_DEB="${KASM_DEB:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_DIR="$(dirname "$SCRIPT_DIR")/packages"

log()  { echo -e "\033[1;32m[deploy]\033[0m $*"; }
warn() { echo -e "\033[1;33m[deploy!]\033[0m $*"; }
die()  { echo -e "\033[1;31m[deploy!]\033[0m $*" >&2; exit 1; }

# 解析 --kasm-deb
while [[ $# -gt 0 ]]; do
  case "$1" in
    --kasm-deb) KASM_DEB="${2:?--kasm-deb 需要参数}"; shift 2 ;;
    *) die "未知参数: $1" ;;
  esac
done

[[ "$(id -u)" -eq 0 ]] || die "请用 root 运行（sudo bash deploy.sh）"

log "========== WebGame 云游戏执行面 一键部署 =========="
log "运行用户: $APP_USER | 管控端口: $CTRL_PORT | KasmVNC: $KASM_VER"

# ---------- 1. 创建运行用户 ----------
if id "$APP_USER" >/dev/null 2>&1; then
  log "用户 $APP_USER 已存在，跳过创建"
else
  log "创建用户 $APP_USER ..."
  useradd -m -s /bin/bash "$APP_USER"
  usermod -aG sudo "$APP_USER"
  log "已创建用户 $APP_USER 并加入 sudo 组"
fi
HOME_DIR="$(getent passwd "$APP_USER" | cut -d: -f6)"

# ---------- 2. 系统依赖（不含 Java 8，见下方独立处理） ----------
log "安装系统依赖 ..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y \
  openbox xdotool x11-utils x11-xserver-utils wmctrl scrot \
  python3 curl ca-certificates gnupg lsb-release \
  xauth x11-xkb-utils xkb-data procps ssl-cert \
  libopenal1 python3-websockify || true

# ---------- 2.1 Java 8：优先本地包，其次 apt，最后提示 ----------
if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -q '1\.8'; then
  if [[ -f "$PKG_DIR/java8-openjdk-amd64.tar.gz" ]]; then
    log "从本地包解压 Java 8（$PKG_DIR/java8-openjdk-amd64.tar.gz）..."
    mkdir -p /usr/lib/jvm
    tar xzf "$PKG_DIR/java8-openjdk-amd64.tar.gz" -C /usr/lib/jvm
    update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java 1000
    update-alternatives --set java /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java
    log "Java 8 就绪: $(java -version 2>&1 | head -1)"
  else
    log "尝试 apt 安装 openjdk-8-jdk-headless ..."
    if ! apt-get install -y openjdk-8-jdk-headless; then
      warn "Java 8 安装失败。请将 java8-openjdk-amd64.tar.gz 放入 packages/ 后重试，"
      warn "或手动安装 Java 8（Temurin/Adoptium 8 / 从已有执行面打包 /usr/lib/jvm/java-8-openjdk-amd64）"
    fi
  fi
fi

# ---------- 3. KasmVNC：优先本地包，其次下载 ----------
if command -v vncserver >/dev/null 2>&1; then
  log "KasmVNC 已安装: $(vncserver --help 2>&1 | head -1 || echo ok)"
else
  KASM_DEB_FILE="${KASM_DEB:-}"
  # 本地 packages/ 优先
  if [[ -z "$KASM_DEB_FILE" ]] && [[ -f "$PKG_DIR/kasmvncserver_${KASM_DIST}_${KASM_VER}_amd64.deb" ]]; then
    KASM_DEB_FILE="$PKG_DIR/kasmvncserver_${KASM_DIST}_${KASM_VER}_amd64.deb"
    log "使用本地 KasmVNC 包: $KASM_DEB_FILE"
  fi
  if [[ -z "$KASM_DEB_FILE" ]]; then
    KASM_DEB_FILE="/tmp/kasmvncserver_${KASM_DIST}_${KASM_VER}_amd64.deb"
    if [[ ! -f "$KASM_DEB_FILE" ]]; then
      log "下载 KasmVNC v${KASM_VER} (${KASM_DIST}) ..."
      curl -fL -o "$KASM_DEB_FILE" \
        "https://github.com/kasmtech/KasmVNC/releases/download/v${KASM_VER}/kasmvncserver_${KASM_DIST}_${KASM_VER}_amd64.deb" \
        || die "KasmVNC 下载失败。请将 deb 放入 packages/ 或使用 --kasm-deb 指定"
    fi
  fi
  [[ -f "$KASM_DEB_FILE" ]] || die "KasmVNC deb 不存在: $KASM_DEB_FILE"
  log "安装 KasmVNC（自动解决依赖）..."
  apt-get install -y "$KASM_DEB_FILE"
fi

# ---------- 4. 部署执行面文件 ----------
log "部署 control_client.py 与配置 ..."
install -o root -g root -m 0755 "$SCRIPT_DIR/control_client.py" "$HOME_DIR/control_client.py"
install -d -o "$APP_USER" -g "$APP_USER" "$HOME_DIR/.vnc"
install -o "$APP_USER" -g "$APP_USER" -m 0644 "$SCRIPT_DIR/kasmvnc/kasmvnc.yaml" "$HOME_DIR/.vnc/kasmvnc.yaml"
install -o "$APP_USER" -g "$APP_USER" -m 0755 "$SCRIPT_DIR/kasmvnc/xstartup" "$HOME_DIR/.vnc/xstartup"

# KasmVNC 密码（默认 webgame，可通过 VNC_PASS 覆盖；KasmVNC 鉴权实际走 Xvnc 启动参数）
VNC_PASS="${VNC_PASS:-webgame}"
log "设置 KasmVNC 密码 ..."
if command -v vncpasswd >/dev/null 2>&1; then
  echo "$VNC_PASS" | vncpasswd -f > "$HOME_DIR/.kasmpasswd" 2>/dev/null || true
  echo "$VNC_PASS" | vncpasswd -f > "$HOME_DIR/.vnc/passwd" 2>/dev/null || true
  chown "$APP_USER:$APP_USER" "$HOME_DIR/.kasmpasswd" "$HOME_DIR/.vnc/passwd" 2>/dev/null || true
fi

# ---------- 5. 目录 ----------
log "创建 MC 与实例目录 ..."
install -d -o "$APP_USER" -g "$APP_USER" "$MC_BASE"
install -d -o "$APP_USER" -g "$APP_USER" "$INSTANCES"
install -d -o "$APP_USER" -g "$APP_USER" "$MC_BASE/.minecraft"

# ---------- 6. systemd 单元 ----------
log "写入 systemd 单元 ..."
UNIT="/etc/systemd/system/webgame-cc.service"
cat > "$UNIT" <<EOF
[Unit]
Description=WebGame Cloud Executor (control_client)
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 ${HOME_DIR}/control_client.py --port ${CTRL_PORT} --mc-base ${MC_BASE} --instances ${INSTANCES}
Restart=on-failure
RestartSec=3
# 注意：必须 root 运行（内部以 su - ${APP_USER} 执行实例命令；
#       以非 root 用户运行会触发 chown 权限异常）
# User=${APP_USER}  <-- 不要启用

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable webgame-cc.service

# ---------- 7. 启动并验证 ----------
log "启动管控服务 ..."
systemctl restart webgame-cc.service
sleep 2
if systemctl is-active --quiet webgame-cc.service; then
  log "webgame-cc.service 已启动"
else
  warn "webgame-cc.service 启动失败，请查看: journalctl -u webgame-cc --no-pager"
fi

# 端口探测
if command -v python3 >/dev/null 2>&1; then
  if python3 -c "import socket;s=socket.socket();s.settimeout(3);s.connect(('127.0.0.1',${CTRL_PORT}));print('ok')" >/dev/null 2>&1; then
    log "管控端口 ${CTRL_PORT} 探测通过（TCP_OK）"
  else
    warn "管控端口 ${CTRL_PORT} 暂未监听（服务端插件连接后即可）"
  fi
fi

# ---------- 8. 摘要 ----------
log "========== 部署完成 =========="
echo ""
echo "  运行用户   : $APP_USER"
echo "  管控端口   : $CTRL_PORT （插件 ControlClient 连接目标）"
echo "  MC 目录    : $MC_BASE"
echo "  实例目录   : $INSTANCES"
echo ""
echo "  下一步："
echo "    1) 导入 MC 客户端（Forge 1.12.2）："
echo "        · 若 packages/mc-backup.tar.gz 已存在：直接  sudo bash mc-import.sh"
echo "        · 否则在源机器执行 mc-pack.sh 得到 tar 包后  sudo bash mc-import.sh <包>"
echo "    2) 验证服务:  bash status.sh"
echo "    3) 更新执行面: bash update.sh"
echo "    4) 卸载执行面: bash uninstall.sh"
echo ""
echo "  本地包目录 : $PKG_DIR"
echo "    - kasmvncserver_${KASM_DIST}_${KASM_VER}_amd64.deb   （已含/自动下载）"
echo "    - java8-openjdk-amd64.tar.gz  （可选，Java 8 离线包）"
echo "    - mc-backup.tar.gz            （可选，MC 客户端离线包）"
echo "  KasmVNC 端口段: 8542 起（每个实例一个，由 control_client 分配）"
echo "  X display 段  : :99 起"
echo "  注意          : 浏览器入口是服务器插件路径，不在本机直接暴露"
