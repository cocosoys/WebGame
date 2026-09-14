#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WebGame 执行面控制客户端（参考实现，S1）
========================================
插件（控制面）↔ 执行面 的管控契约服务端。运行在 WSL2 容器（Ubuntu-22.04）内，
以 webgame 用户启动。职责：

  * 监听管控 TCP 端口（默认 127.0.0.1:25576），接收插件下行指令；
  * SPAWN  -> 分配 display/kasmPort，启动 Xvnc + Forge MC 客户端（独立实例目录），
              轮询进服信号后上报 READY（采集端点）；
  * KILL   -> 终止实例（MC + 对应 display 的 Xvnc）；
  * STATUS -> 上报实例列表与容量；
  * PING   -> PONG。

协议： [u32 length BE][u8 type][payload: URL 编码 key=value 行，\n 分隔]
下行: 1=SPAWN 2=KILL 3=STATUS 4=CONFIG 5=PING
上行: 0x81=SPAWN_ACK 0x82=READY 0x83=STOPPED 0x84=STATUS_RPT 0x85=PONG 0x86=ERROR

用法：  python3 control_client.py [--host 127.0.0.1] [--port 25576] \
        [--mc-base /home/webgame/mc] [--instances /home/webgame/instances]
"""

import argparse
import os
import re
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.parse
from collections import OrderedDict

# ---------------- 协议常量 ----------------
C_SPAWN, C_KILL, C_STATUS, C_CONFIG, C_PING = 1, 2, 3, 4, 5
S_SPAWN_ACK, S_READY, S_STOPPED, S_STATUS_RPT, S_PONG, S_ERROR = 0x81, 0x82, 0x83, 0x84, 0x85, 0x86

ST_SPAWNING, ST_STARTING, ST_READY, ST_STOPPED, ST_FAILED = (
    "spawning", "starting", "ready", "stopped", "failed")

# ---------------- 实例状态 ----------------
instances = {}            # instanceId -> dict
instances_lock = threading.Lock()
next_seq = [0]
base_display = 99         # :99 起
base_kasm_port = 18500    # websockify 桥端口 18500 起（KasmVNC WebSocket 模式本机 EADDRINUSE，改用独立 websockify）
capacity = 4              # 执行面可承载实例上限

# ---------------- 工具 ----------------

def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def urlencode(s):
    return urllib.parse.quote(str(s), safe="")


def urldecode(s):
    return urllib.parse.unquote(s)


def parse_fields(payload):
    fields = OrderedDict()
    if not payload:
        return fields
    for line in payload.decode("utf-8", "replace").split("\n"):
        if "=" in line:
            k, v = line.split("=", 1)
            fields[urldecode(k)] = urldecode(v)
    return fields


def build_frame(typ, fields):
    body = "\n".join("%s=%s" % (urlencode(k), urlencode(v)) for k, v in fields.items())
    payload = body.encode("utf-8")
    return struct.pack(">IB", len(payload), typ) + payload


def read_frame(conn):
    head = conn.recv(5)
    if len(head) < 5:
        return None, None
    (length, typ) = struct.unpack(">IB", head)
    if length < 0 or length > (1 << 20):
        return None, None
    payload = b""
    while len(payload) < length:
        chunk = conn.recv(length - len(payload))
        if not chunk:
            return None, None
        payload += chunk
    return typ, payload


def send(conn, typ, fields):
    try:
        conn.sendall(build_frame(typ, fields))
        return True
    except Exception:
        return False


def run_cmd(args, timeout=10):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except Exception as e:
        return -1, str(e)


def display_in_use(disp):
    # Xvnc 锁文件或 X socket 任一存在即视为占用（KasmVNC 会写 /tmp/.X{N}-lock）
    for p in ("/tmp/.X%d-lock" % disp, "/tmp/.X11-unix/X%d" % disp):
        out = run_cmd(["bash", "-lc", "test -e '%s' && echo YES || echo NO" % p], timeout=5)
        if out[0] == 0 and "YES" in out[1]:
            return True
    return False


# ---------------- 实例生命周期 ----------------

def spawn_instance(fields):
    """SPAWN：分配 display/kasmPort，起 Xvnc + MC，异步等待就绪。

    幂等语义（配合插件侧 resume 重发 SPAWN）：
      * 实例已存在且 SPAWNING/STARTING/READY → 复用：READY 时补发一次
        READY 上报（插件 onInstanceReady 据此重新下发采集端点），
        不重复起进程；STARTING/SPAWNING 时保持等待既有 worker 完成。
      * 实例已存在但 STOPPED/FAILED（残留记录）→ 移除记录，走正常启动。
    """
    instance_id = fields.get("instanceId", "")
    username = fields.get("username", "WebPlayer")
    server = fields.get("server", "127.0.0.1")
    port = int(fields.get("port", "25574") or 25574)
    xmx = fields.get("xmx", "2G")
    client_dir = fields.get("clientDir", "/home/webgame/mc")
    # 容器分辨率（插件透传 width/height；缺省 1280x720）。校验范围并取偶数，
    # 非法值回落默认——防止 Xvfb -screen 参数注入与极端分辨率拖垮软渲染。
    try:
        width = max(640, min(2560, int(fields.get("width", "1280") or 1280))) & ~1
    except (TypeError, ValueError):
        width = 1280
    try:
        height = max(360, min(1440, int(fields.get("height", "720") or 720))) & ~1
    except (TypeError, ValueError):
        height = 720

    if not instance_id:
        return False, "缺 instanceId"
    if not re.match(r"^[A-Za-z0-9_]{1,16}$", username):
        return False, "非法用户名: %s" % username

    with instances_lock:
        if instance_id in instances:
            inst = instances[instance_id]
            st = inst.get("state")
            if st in (ST_SPAWNING, ST_STARTING, ST_READY):
                log("SPAWN 幂等复用 %s state=%s display=:%d kasm=%d"
                    % (instance_id, st, inst.get("display", -1), inst.get("kasmPort", -1)))
                if st == ST_READY:
                    def _re_ready():
                        time.sleep(0.3)
                        broadcast(S_READY, OrderedDict([
                            ("instanceId", instance_id),
                            ("display", ":%d" % inst["display"]),
                            ("kasmPort", str(inst["kasmPort"])),
                            ("host", "127.0.0.1"),
                            ("readyAt", str(int(inst.get("readyAt") or time.time())))]))
                    threading.Thread(target=_re_ready, daemon=True).start()
                return True, ""
            log("SPAWN 清理残留记录 %s state=%s" % (instance_id, st))
            instances.pop(instance_id, None)
        if len(instances) >= capacity:
            return False, "执行面容量已满 (%d)" % capacity
        disp = base_display + len(instances)
        kasm_port = base_kasm_port + len(instances)
        inst = {
            "id": instance_id, "username": username, "state": ST_SPAWNING,
            "display": disp, "kasmPort": kasm_port,
            "server": server, "port": port, "xmx": xmx,
            "clientDir": client_dir, "width": width, "height": height,
            "proc": None, "startedAt": time.time(),
        }
        instances[instance_id] = inst

    log("SPAWN %s user=%s display=:%d kasm=%d res=%dx%d" % (instance_id, username, disp, kasm_port, width, height))
    t = threading.Thread(target=instance_worker, args=(instance_id,), daemon=True)
    t.start()
    return True, ""


def instance_worker(instance_id):
    inst = instances.get(instance_id)
    if inst is None:
        return
    inst["state"] = ST_STARTING

    # 1) 准备实例目录与启动脚本（按实例 ID 隔离，同用户多实例互不干扰）
    inst_root = os.path.join(ARGS.instances, instance_id)
    inst["instRoot"] = inst_root
    os.makedirs(inst_root, exist_ok=True)
    # 清理上一轮残留日志：launch.sh 用 >> 追加 client-stdout.log，
    # 旧会话的 "joined the game" 残留在 tail 会让 wait_ready 误判提前 READY
    for old in (os.path.join(inst_root, "logs", "latest.log"),
                os.path.join(inst_root, "client-stdout.log")):
        try:
            if os.path.exists(old):
                os.remove(old)
        except OSError as _e:
            log("  清理旧日志失败 %s: %s" % (old, _e))
    # inst_root 整体归 webgame 所有（MC 以 webgame 运行，需写日志/gameDir/natives；
    # 残留的 root 属主文件一并回收）
    run_cmd(["chown", "-R", "webgame:webgame", inst_root], timeout=10)
    # 1.5) 实例 options.txt：禁用"失焦暂停" + 低画质软渲染优化预设 + 强制全屏。
    # llvmpipe 软渲染下，高画质/远视距/VBO 会显著拖慢世界渲染（长时间停留加载背景），
    # 因此强制低画质：近视距 2chunk、maxFps 30、关粒子/平滑光照/阴影/VBO/mipmap。
    # fullscreen:true 让 MC 以 Xvfb 屏幕分辨率全屏渲染（否则 854x480 窗口居中，
    # Xvfb 背景大面积黑边会随画面传到浏览器）。
    opt = os.path.join(inst_root, "options.txt")
    low_gfx = {
        "pauseOnLostFocus": "false",
        "fullscreen": "true",
        "graphics": "fast",
        "renderDistance": "2",
        "maxFps": "30",
        "particles": "2",
        "ao": "0",
        "smoothLighting": "false",
        "entityShadows": "false",
        "mipmapLevels": "0",
        "vboUse": "false",
        "vsync": "false",
    }
    try:
        _content = ""
        if os.path.exists(opt):
            with open(opt, encoding="utf-8", errors="replace") as f:
                _content = f.read()
        # 保留既有键值，仅覆盖/追加优化项（MC 读入后重写会保留有效键）
        for _k, _v in low_gfx.items():
            if re.search(r"^%s:.*$" % re.escape(_k), _content, flags=re.M):
                _content = re.sub(r"^%s:.*$" % re.escape(_k), "%s:%s" % (_k, _v), _content, flags=re.M)
            else:
                _content += "%s:%s\n" % (_k, _v)
        with open(opt, "w", encoding="utf-8") as f:
            f.write(_content)
        log("  已写入实例 options.txt 优化预设（失焦暂停关闭 + 低画质软渲染）")
    except Exception as e:
        log("  写 options.txt 失败: %s" % e)
    launch_sh = os.path.join(inst_root, "launch.sh")
    stdout_log = os.path.join(inst_root, "client-stdout.log")

    mc_base = inst["clientDir"] or ARGS.mc_base
    launch = build_launch_script(
        username=inst["username"], server=inst["server"], port=inst["port"],
        xmx=inst["xmx"], mc_base=mc_base, inst_root=inst_root)
    try:
        with open(launch_sh, "w") as f:
            f.write(launch)
        os.chmod(launch_sh, 0o755)
    except Exception as e:
        fail_instance(instance_id, "写启动脚本失败: %s" % e)
        return

    # 2) 启动 Xvfb（Xorg 标准栈 + x11vnc 提供 RFB）。
    #    KasmVNC Xvnc 的 GLX 与 MC 1.12.2 世界渲染存在兼容问题（进服冻结主菜单帧），
    #    换 Xvfb（X.Org Foundation GLX 1.4，mesa 软渲染）——路线 A。
    #    浏览器 WS 隧道(25574 /kasm/{sid}/{port}/websockify) -> websockify -> x11vnc RFB
    disp = inst["display"]
    kasm_port = inst["kasmPort"]
    rfb_port = 5900 + disp
    if display_in_use(disp):
        log("  display :%d 占用，回收后重启" % disp)
        run_cmd(["bash", "-lc",
                 "pkill -f 'Xvfb :%d ' 2>/dev/null; pkill -f 'x11vnc -display :%d ' 2>/dev/null; "
                 "pkill -f 'websockify %d ' 2>/dev/null; sleep 1; true"
                 % (disp, disp, kasm_port)], timeout=10)
    # 清残留 X 锁（Xvfb 被强杀时 /tmp/.X{disp}-lock 残留，导致 "Server is already
    # active for display" 启动失败）
    run_cmd(["bash", "-lc", "rm -f /tmp/.X%d-lock /tmp/.X11-unix/X%d 2>/dev/null; true"
             % (disp, disp)], timeout=5)
    # 释放 display 对应 TCP 端口（Xvfb 的 -ac 会 TCP 监听 6000+disp；强杀残留的
    # TIME_WAIT/占用会导致 "Cannot establish any listening sockets" 启动失败）
    run_cmd(["bash", "-lc", "fuser -k %d/tcp 2>/dev/null; sleep 1; true" % (6000 + disp)], timeout=10)
    # 2.0) Xvfb（webgame 身份，后台；-ac 允许 x11vnc/客户端连接；分辨率由 SPAWN 透传，
    #      默认 1280x720——MC 全屏后画面=屏幕分辨率，浏览器端黑框随之消失）
    rc, out = run_cmd([
        "su", "-", "webgame", "-c",
        "nohup Xvfb :%d -screen 0 %dx%dx24 -ac >/tmp/xvfb-%d.log 2>&1 &" % (disp, inst["width"], inst["height"], disp)],
        timeout=15)
    if rc != 0:
        fail_instance(instance_id, "Xvfb 启动失败: %s" % out.strip())
        return
    # 2.0.1) Xvfb 存活验证（nohup 的 su 返回 0 不代表 Xvfb 成功启动——立即 pgrep，
    #        失败直接 fail，避免后续 xdpyinfo 轮询空转 18 分钟）
    time.sleep(2)
    r = run_cmd(["bash", "-lc", "pgrep -f 'Xvfb :%d ' >/dev/null && echo ALIVE || echo DEAD" % disp], timeout=8)
    if r[0] != 0 or "ALIVE" not in r[1]:
        fail_instance(instance_id, "Xvfb :%d 启动失败（未存活，见 /tmp/xvfb-%d.log）" % (disp, disp))
        return
    log("  Xvfb :%d (%dx%d) 存活" % (disp, inst["width"], inst["height"]))
    # 2.0.3) x11vnc（webgame 身份，RFB 服务器；-forever 断线续听 / -shared 多客户端 /
    #        -noxdamage 规避软渲染下 xdamage 问题）
    rc, out = run_cmd([
        "su", "-", "webgame", "-c",
        "nohup x11vnc -display :%d -rfbport %d -nopw -forever -shared -noxdamage "
        ">/tmp/x11vnc-%d.log 2>&1 &" % (disp, rfb_port, disp)],
        timeout=15)
    if rc != 0:
        fail_instance(instance_id, "x11vnc 启动失败: %s" % out.strip())
        return
    # 2.0.5) websockify 桥（root 身份，WS:kasmPort -> RFB:rfbPort；--web 提供
    #        noVNC 静态资源 vnc.html/*（KasmVNC 客户端与 x11vnc RFB 不兼容——连上即断，
    #        标准 noVNC 客户端是 x11vnc 的正配），插件 httpForward 经此取页面）
    rc, out = run_cmd(["bash", "-lc",
        "nohup websockify --web /usr/share/novnc %d 127.0.0.1:%d >/tmp/ws-%d.log 2>&1 & sleep 2; "
        "ss -tln | grep -q ':%d ' && echo WS_OK || echo WS_FAIL"
        % (kasm_port, rfb_port, disp, kasm_port)], timeout=15)
    if rc != 0 or "WS_OK" not in out:
        fail_instance(instance_id, "websockify 桥启动失败: %s" % out.strip()[:200])
        return
    log("  Xvfb :%d + x11vnc :%d + websockify :%d 已启动" % (disp, rfb_port, kasm_port))

    # 2.5) 等待 Xvnc 完全就绪：xdpyinfo 真实连接 X 成功才算就绪
    # （socket/端口存在不代表 X 内部完成初始化，刚重启时偶发 MC 静默退出；
    #   Xvnc 启动时 dxg GPU 探测可能拖慢就绪到 60s+，等待放宽到 120s）
    x_ok = False
    for _try in range(90):
        # Xvfb 中途退出立即 fail（避免 xdpyinfo 连接挂起空转）
        r = run_cmd(["bash", "-lc", "pgrep -f 'Xvfb :%d ' >/dev/null || echo XDEAD" % disp], timeout=8)
        if r[0] == 0 and "XDEAD" in r[1]:
            fail_instance(instance_id, "Xvfb :%d 中途退出（见 /tmp/xvfb-%d.log）" % (disp, disp))
            return
        r = run_cmd([
            "su", "-", "webgame", "-c",
            "DISPLAY=:%d xdpyinfo >/dev/null 2>&1 && echo YES || echo NO" % disp],
            timeout=8)
        if r[0] == 0 and "YES" in r[1]:
            x_ok = True
            break
        time.sleep(1)
    if not x_ok:
        fail_instance(instance_id, "Xvnc 就绪超时（xdpyinfo 无法连接 :%d）" % disp)
        return
    time.sleep(3)

    # 2.6) 启动轻量 WM（openbox）：MC 的鼠标 grab / 焦点管理依赖 WM，
    # 无 WM 裸 Xvnc 下视角鼠标注入会失效（Kasm 容器标准做法）。
    # 用 systemd-run 托管，避免 nohup 后台进程随 wsl.exe 会话退出而丢失
    # （X root 的 _NET_SUPPORTING_WM_CHECK 属性在 openbox 异常退出后残留，探测属性不可靠）
    r = run_cmd([
        "bash", "-lc",
        "systemctl stop openbox-%d 2>/dev/null; systemctl reset-failed openbox-%d 2>/dev/null; "
        "systemd-run --unit=openbox-%d --uid=webgame --gid=webgame "
        "--setenv=HOME=/home/webgame --setenv=DISPLAY=:%d openbox >/dev/null 2>&1; "
        "sleep 2; systemctl is-active openbox-%d" % (disp, disp, disp, disp, disp)],
        timeout=25)
    if r[0] == 0 and "active" in r[1]:
        log("  openbox-%d 已启动（systemd 托管，EWMH 就绪）" % disp)
    else:
        log("  openbox-%d 启动异常（%s）—— 不阻断，MC 仍将启动" % (disp, r[1].strip() if r[0] == 0 else "rc=%d" % r[0]))

    # 3+4) 启动 MC 并等待就绪；若 MC 在 Xvnc 竞态下静默退出（Forge 加载后无
    # 异常消失），自动清理重启，最多 3 次尝试
    def mc_alive():
        r = run_cmd([
            "bash", "-lc",
            "pgrep -f 'gameDir %s' >/dev/null && echo Y || echo N" % inst_root],
            timeout=5)
        return r[0] == 0 and "Y" in r[1]

    ready = False
    for attempt in range(3):
        # 每次尝试前清理旧日志，避免上次残留的进服信号误判
        for old in (os.path.join(inst_root, "logs", "latest.log"), stdout_log):
            try:
                if os.path.exists(old):
                    os.remove(old)
            except OSError:
                pass
        try:
            proc = subprocess.Popen(
                ["su", "-", "webgame", "-c",
                 "cd %s && DISPLAY=:%d nohup bash launch.sh >> %s 2>&1 & echo $!" % (
                     inst_root, disp, stdout_log)],
                shell=False)
            proc.wait(timeout=10)
        except Exception as e:
            fail_instance(instance_id, "MC 启动失败: %s" % e)
            return
        ready = wait_ready(inst_root, stdout_log, timeout=240, alive_check=mc_alive)
        if ready:
            break
        if mc_alive():
            # 进程还活着但 120s 未就绪 —— 真超时，不重试
            fail_instance(instance_id, "客户端启动超时（240s 未进入服务器）")
            return
        log("  MC 第 %d 次尝试静默退出，清理后重启" % (attempt + 1))
        run_cmd(["bash", "-lc", "pkill -f 'gameDir %s'" % inst_root], timeout=5)
        time.sleep(3)
    if not ready:
        fail_instance(instance_id, "MC 连续 3 次启动未就绪")
        return

    # 4.5) MC 窗口铺满虚拟屏幕（云游戏全屏化）：虚拟屏 NxM，MC 初始窗口
    # 854x480 只占中央留黑边，resize 到虚拟屏尺寸消除黑边（浏览器侧 canvas
    # 100% 拉伸后即为铺满画面）。windowmove 0 0 尽力归位（openbox 可能覆盖）
    r = run_cmd([
        "su", "-", "webgame", "-c",
        "export DISPLAY=:%d; "
        "GEO=$(xdpyinfo | grep dimensions | awk '{print $2}'); "
        "W=$(xdotool search --name Minecraft | head -1); "
        "[ -n \"$W\" ] && xdotool windowsize $W $GEO; "
        "[ -n \"$W\" ] && xdotool windowmove $W 0 0; "
        "echo FULLSCREEN:$GEO:$W" % disp], timeout=15)
    if r[0] == 0 and "FULLSCREEN" in r[1]:
        log("  MC 窗口已铺满虚拟屏: %s" % (r[1].strip() or "?"))
    else:
        log("  MC 全屏化失败: %s" % (r[1].strip() if r[0] == 0 else "rc=%d" % r[0]))

    inst["state"] = ST_READY
    inst["readyAt"] = time.time()
    log("READY %s display=:%d kasm=%d" % (instance_id, disp, inst["kasmPort"]))

    fields = OrderedDict()
    fields["instanceId"] = instance_id
    fields["display"] = ":%d" % disp
    fields["kasmPort"] = str(inst["kasmPort"])
    fields["host"] = "127.0.0.1"
    fields["readyAt"] = str(int(inst["readyAt"]))
    broadcast(S_READY, fields)


def build_launch_script(username, server, port, xmx, mc_base, inst_root):
    """生成 Forge 1.12.2 离线客户端启动脚本（复用 S0 验证过的命令形态）。"""
    lib = os.path.join(mc_base, ".minecraft", "libraries")
    versions = os.path.join(mc_base, ".minecraft", "versions")
    version_id = "1.12.2-Forge_14.23.5.2864"
    version_jar = os.path.join(versions, version_id, version_id + ".jar")
    assets = os.path.join(mc_base, ".minecraft", "assets")
    natives = os.path.join(mc_base, ".minecraft", "natives-linux")

    cp_parts = ["$(find %s -name '*.jar' | tr '\\n' ':')" % lib, version_jar]
    classpath = ":".join(cp_parts)

    return """#!/bin/bash
# WebGame 执行面实例脚本（S1 契约生成）
cd {inst_root}
export LWJGL_DISABLE_XRANDR=true
# WSL2 dxg GPU 接口不稳定（dxgkio_query_adapter_info Ioctl failed），
# MC/LWJGL 初始化会触发 WSL 实例崩溃重启 → 强制纯软件渲染，完全绕开 /dev/dxg
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
# llvmpipe 多线程 SIGSEGV/死锁；单线程 llvmpipe 世界渲染仍挂起（进服瞬间冻结主菜单帧）
# 换 Mesa softpipe（经典软渲染，GL 2.1 上下文，lwjgl2 时代兼容性最好）——慢但稳
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=softpipe
export LP_NUM_THREADS=1
export MESA_GL_VERSION_OVERRIDE=2.1
export MESA_GLSL_VERSION_OVERRIDE=120
exec java -Xmx{xmx} \\
  -Djava.library.path={natives} \\
  -Dfml.ignoreInvalidMinecraftCertificates=true \\
  -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true \\
  -cp "{classpath}" \\
  net.minecraft.launchwrapper.Launch \\
  --username {username} --version {version_id} \\
  --gameDir {inst_root} --assetsDir {assets} --assetIndex 1.12 \\
  --uuid {uuid} --accessToken 0 --userType legacy \\
  --versionType Forge \\
  --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker \\
  --server {server} --port {port}
""".format(
        inst_root=inst_root, xmx=xmx, natives=natives, classpath=classpath,
        username=username, version_id=version_id, assets=assets,
        uuid=uuid_for(username), server=server, port=port)


def uuid_for(name):
    h = abs(hash(name)) & 0xFFFFFFFF
    return "%08x-1234-5678-9abc-def000000000" % h


def wait_ready(inst_root, stdout_log, timeout=240, alive_check=None):
    """轮询 latest.log / client-stdout.log 出现进服信号。

    信号口径（避免把启动早期的宽泛日志误判为就绪）：
      * "Setting user" 只是 launchwrapper 早期输出，不代表任何就绪 —— 不采用；
      * "Connecting to" 出现即开始连服务器，再留进服缓冲（资源加载/握手）；
      * "joined the game" 已确认进入 —— 立即就绪。
    alive_check：MC 进程存活探针（callable -> bool）；进程消失且未就绪时快速
    返回 False，让上层立即走崩溃重试，而不是干等满 timeout。
    """
    latest = os.path.join(inst_root, "logs", "latest.log")
    deadline = time.time() + timeout
    last_sizes = {}
    connected_at = None
    while time.time() < deadline:
        if alive_check is not None and not alive_check():
            log("  MC 进程已退出，wait_ready 提前终止")
            return False
        for f in (latest, stdout_log):
            try:
                size = os.path.getsize(f)
            except OSError:
                continue
            if size == last_sizes.get(f):
                continue
            last_sizes[f] = size
            try:
                with open(f, "rb") as fh:
                    fh.seek(max(0, size - 8192))
                    tail = fh.read().decode("utf-8", "replace")
                if "joined the game" in tail:
                    return True
                if connected_at is None and "Connecting to" in tail:
                    connected_at = time.time()
                    log("  %s 开始连接服务器，等待进服缓冲 10s" % inst_root)
            except Exception:
                pass
        if connected_at is not None and time.time() - connected_at >= 10:
            return True
        time.sleep(2)
    return False


def fail_instance(instance_id, reason):
    with instances_lock:
        inst = instances.pop(instance_id, None)
    if inst is None:
        return
    log("FAIL %s reason=%s" % (instance_id, reason))
    fields = OrderedDict()
    fields["instanceId"] = instance_id
    fields["code"] = "spawn_failed"
    fields["message"] = reason
    broadcast(S_ERROR, fields)


def kill_instance(instance_id):
    with instances_lock:
        inst = instances.pop(instance_id, None)
    if inst is None:
        return False
    log("KILL %s" % instance_id)
    # 终止该实例 MC 进程（按 gameDir 与 --username 双模式匹配，兼容 launch.sh 的引号参数）
    uname = inst["username"]
    inst_root = inst.get("instRoot", "")
    run_cmd(["bash", "-lc",
             "pkill -f 'net.minecraft.launchwrapper.Launch.*--username \\\"%s\\\"' 2>/dev/null; "
             "pkill -f '--gameDir %s' 2>/dev/null; pkill -f 'launchwrapper.*%s' 2>/dev/null || true" % (
                 uname, inst_root, inst_root)], timeout=10)
    # Xvfb 留给 idle 回收（其它实例可能复用 display 池），仅停 MC、x11vnc 与 websockify 桥
    kasm_port = inst.get("kasmPort", 0)
    disp = inst.get("display", 0)
    if kasm_port:
        run_cmd(["bash", "-lc", "pkill -f 'websockify %d ' 2>/dev/null || true" % kasm_port], timeout=5)
    if disp:
        run_cmd(["bash", "-lc", "pkill -f 'x11vnc -display :%d ' 2>/dev/null || true" % disp], timeout=5)
    inst["state"] = ST_STOPPED
    fields = OrderedDict()
    fields["instanceId"] = instance_id
    fields["reason"] = "killed"
    broadcast(S_STOPPED, fields)
    return True


def status_report():
    fields = OrderedDict()
    with instances_lock:
        parts = []
        for i in instances.values():
            parts.append("%s:%s" % (i["id"], i["state"]))
        fields["instances"] = ",".join(parts)
        fields["capacity"] = str(capacity)
        fields["runs"] = str(len(instances))
    return fields


# ---------------- 广播（单连接模式：直接回给当前连接） ----------------

def broadcast(typ, fields):
    with conns_lock:
        for c in list(conns):
            send(c, typ, fields)


conns = []
conns_lock = threading.Lock()


# ---------------- 全量清理（管控连接断开时调用） ----------------

def cleanup_all_instances(reason):
    """管控连接断开：服务器已重启或不可用，清理全部实例（含残留 MC/Xvnc），
    避免旧实例与重启后服务器的同序号实例 id 冲突（旧实例 STOPPED/READY 事件
    误匹配新会话）。"""
    log("管控连接断开，清理全部实例（%s）" % reason)
    for iid in list(instances.keys()):
        try:
            kill_instance(iid)
        except Exception as _e:
            log("清理 %s 异常: %s" % (iid, _e))
    instances.clear()

# ---------------- 连接处理 ----------------

def handle_conn(conn, addr):
    with conns_lock:
        conns.append(conn)
    log("连接建立: %s" % (addr,))
    try:
        while True:
            typ, payload = read_frame(conn)
            if typ is None:
                break
            fields = parse_fields(payload) if payload else OrderedDict()
            handle_cmd(conn, typ, fields)
    except Exception as e:
        log("连接异常: %s" % e)
    finally:
        with conns_lock:
            if conn in conns:
                conns.remove(conn)
        try:
            conn.close()
        except Exception:
            pass
        log("连接关闭: %s" % (addr,))
        cleanup_all_instances("conn closed %s" % (addr,))


def handle_cmd(conn, typ, fields):
    if typ == C_SPAWN:
        ok, reason = spawn_instance(fields)
        resp = OrderedDict()
        resp["instanceId"] = fields.get("instanceId", "")
        resp["ok"] = "1" if ok else "0"
        if reason:
            resp["reason"] = reason
        send(conn, S_SPAWN_ACK, resp)
    elif typ == C_KILL:
        instance_id = fields.get("instanceId", "")
        ok = kill_instance(instance_id)
        resp = OrderedDict()
        resp["instanceId"] = instance_id
        resp["ok"] = "1" if ok else "0"
        if not ok:
            resp["reason"] = "实例不存在"
        send(conn, S_SPAWN_ACK, resp)
    elif typ == C_STATUS:
        send(conn, S_STATUS_RPT, status_report())
    elif typ == C_CONFIG:
        log("CONFIG 下发: %s" % fields)
        send(conn, S_SPAWN_ACK, OrderedDict([("ok", "1")]))
    elif typ == C_PING:
        resp = OrderedDict()
        resp["ts"] = fields.get("ts", "0")
        send(conn, S_PONG, resp)
    else:
        log("未知指令 type=%d" % typ)


# ---------------- 主程序 ----------------

def main():
    global ARGS
    parser = argparse.ArgumentParser(description="WebGame 执行面控制客户端")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25576)
    parser.add_argument("--mc-base", default="/home/webgame/mc")
    parser.add_argument("--instances", default="/home/webgame/instances")
    ARGS = parser.parse_args()

    os.makedirs(ARGS.instances, exist_ok=True)

    # 启动清理：杀掉上一轮残留的 MC 客户端进程与 Xvfb/x11vnc/websockify（实例状态由内存
    # 重建，残留进程会占 display/端口）
    try:
        subprocess.run(["bash", "-lc",
                        "pkill -f launchwrapper; pkill -f 'net.minecraft.launchwrapper'; "
                        "pkill -f 'Xvfb :'; pkill -f 'x11vnc -display :'; pkill -f websockify; true"],
                       timeout=10, capture_output=True)
        log("启动清理完成（残留 MC/Xvfb/x11vnc 已回收）")
    except Exception as _e:
        log("启动清理警告: %s" % _e)
    instances.clear()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((ARGS.host, ARGS.port))
    srv.listen(8)
    log("执行面控制客户端就绪 %s:%d (mc-base=%s, instances=%s)"
        % (ARGS.host, ARGS.port, ARGS.mc_base, ARGS.instances))

    def on_sig(signum, frame):
        log("收到信号 %d，退出" % signum)
        sys.exit(0)

    signal.signal(signal.SIGINT, on_sig)
    signal.signal(signal.SIGTERM, on_sig)

    while True:
        try:
            conn, addr = srv.accept()
            threading.Thread(target=handle_conn, args=(conn, addr), daemon=True).start()
        except socket.timeout:
            continue
        except Exception as _e:
            # accept 偶发异常（服务器重启导致 WSL 端口转发短暂中断时 EINTR/ECONNABORTED
            # 等），绝不能让它击穿主循环——否则 systemd 判定崩溃重启，SPAWN 窗口失败
            log("accept 异常: %s，1s 后重试" % _e)
            time.sleep(1)


if __name__ == "__main__":
    main()
