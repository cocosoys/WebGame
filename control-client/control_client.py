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
base_kasm_port = 8542     # 8542 起
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
            "clientDir": client_dir, "proc": None, "startedAt": time.time(),
        }
        instances[instance_id] = inst

    log("SPAWN %s user=%s display=:%d kasm=%d" % (instance_id, username, disp, kasm_port))
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

    # 2) 启动 Xvnc（display 占用则先回收再统一拉起，确保 geometry/参数一致）
    disp = inst["display"]
    if display_in_use(disp):
        log("  display :%d 占用，回收后重启" % disp)
        run_cmd(["su", "-", "webgame", "-c", "vncserver -kill :%d" % disp], timeout=15)
        time.sleep(2)
    rc, out = run_cmd([
        "su", "-", "webgame", "-c",
        "vncserver :%d -geometry 1280x720 -depth 24 -localhost no "
        "-SecurityTypes None -disableBasicAuth -udpPort 0" % disp], timeout=30)
    if rc != 0:
        fail_instance(instance_id, "Xvnc 启动失败: %s" % out.strip())
        return

    # 2.5) 等待 Xvnc 完全就绪：xdpyinfo 真实连接 X 成功才算就绪
    # （socket/端口存在不代表 X 内部完成初始化，刚重启时偶发 MC 静默退出）
    x_ok = False
    for _try in range(30):
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
exec java -Xmx{xmx} \\
  -Djava.library.path={natives} \\
  -Dfml.ignoreInvalidMinecraftCertificates=true \\
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
    # Xvnc 留给 idle 回收（其它实例可能复用 display 池），仅停 MC
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

    # 启动清理：杀掉上一轮残留的 MC 客户端进程与 Xvnc（实例状态由内存重建，残留进程会占 display/端口）
    try:
        subprocess.run(["bash", "-lc",
                        "pkill -f launchwrapper; pkill -f 'net.minecraft.launchwrapper'; pkill -f 'Xvnc :99' || true"],
                       timeout=10, capture_output=True)
        log("启动清理完成（残留 MC/Xvnc 已回收）")
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
        conn, addr = srv.accept()
        threading.Thread(target=handle_conn, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
