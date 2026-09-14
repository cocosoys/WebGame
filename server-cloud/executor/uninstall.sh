#!/bin/bash
# =============================================================================
# WebGame 云游戏执行面 —— 一键卸载
# -----------------------------------------------------------------------------
# 作用：停止并移除云游戏执行面：
#   * 停止/禁用 webgame-cc.service 并删除 systemd 单元
#   * 终止所有实例（MC 客户端）与 Xvnc/openbox 进程
#   * （可选）删除运行用户与全部数据（--purge）
# 用法：
#   sudo bash uninstall.sh            # 停服务 + 杀进程，保留文件与用户
#   sudo bash uninstall.sh --purge    # 额外删除运行用户与 MC/实例目录
#   sudo bash uninstall.sh --keep-kasmvnc  # 不卸载 KasmVNC 软件包
# =============================================================================
set -euo pipefail

APP_USER="${APP_USER:-webgame}"
PURGE=0
KEEP_KASMVNC=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --purge) PURGE=1; shift ;;
    --keep-kasmvnc) KEEP_KASMVNC=1; shift ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

log() { echo -e "\033[1;32m[uninstall]\033[0m $*"; }
warn() { echo -e "\033[1;33m[uninstall!]\033[0m $*"; }

[[ "$(id -u)" -eq 0 ]] || { echo "请用 root 运行（sudo bash uninstall.sh）" >&2; exit 1; }

log "===== 开始卸载 WebGame 云游戏执行面 ====="

# 1. systemd 单元
if systemctl list-unit-files | grep -q webgame-cc; then
  log "停止并禁用 webgame-cc.service ..."
  systemctl stop webgame-cc.service 2>/dev/null || true
  systemctl disable webgame-cc.service 2>/dev/null || true
  rm -f /etc/systemd/system/webgame-cc.service
  systemctl daemon-reload
fi

# 2. 终止管控/实例/Xvnc/openbox 进程
log "终止实例与虚拟屏进程 ..."
# control_client 自身
pkill -f "control_client.py" 2>/dev/null || true
# 各实例的 MC / launch.sh
pkill -f "net.minecraft.launchwrapper.Launch" 2>/dev/null || true
pkill -f "launch.sh" 2>/dev/null || true
# Xvnc 与 openbox（仅本执行面的 :9x/:10x display 段）
pkill -f "/usr/bin/Xvnc :" 2>/dev/null || true
pkill -f "^/usr/bin/openbox" 2>/dev/null || true
sleep 1

# 3. KasmVNC 软件包（可选保留）
if [[ "$KEEP_KASMVNC" -eq 1 ]]; then
  log "保留 KasmVNC 软件包（--keep-kasmvnc）"
else
  if dpkg -l kasmvncserver >/dev/null 2>&1; then
    log "卸载 kasmvncserver ..."
    apt-get remove -y kasmvncserver >/dev/null 2>&1 || warn "kasmvncserver 卸载失败（可手动 apt-get remove kasmvncserver）"
  fi
fi

# 4. 可选清理数据
if [[ "$PURGE" -eq 1 ]]; then
  if id "$APP_USER" >/dev/null 2>&1; then
    log "删除运行用户 $APP_USER 及其全部数据（--purge）..."
    pkill -u "$APP_USER" 2>/dev/null || true
    userdel -r "$APP_USER" 2>/dev/null || warn "userdel 失败，请手动检查"
  fi
  warn "注意：MC 客户端与实例数据已随用户删除，如需保留请勿使用 --purge"
else
  log "保留用户与数据（如需删除请加 --purge）"
fi

log "===== 卸载完成 ====="
echo "  残留检查:"
echo "    systemctl list-unit-files | grep webgame"
echo "    ps aux | grep -E 'Xvnc|control_client' "
echo "    ls /home/$APP_USER  （数据仍在时）"
