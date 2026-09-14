#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S1 信令链路验证：模拟浏览器连 /cloud，等待 KASM_URL（0x06）。"""
import base64
import os
import socket
import struct
import sys
import time

HOST, PORT = "127.0.0.1", 25574
USER = sys.argv[1] if len(sys.argv) > 1 else "S1Test"

s = socket.create_connection((HOST, PORT), timeout=10)
key = base64.b64encode(os.urandom(16)).decode()
req = (
    "GET /cloud?user=%s HTTP/1.1\r\n"
    "Host: %s:%d\r\n"
    "Upgrade: websocket\r\n"
    "Connection: Upgrade\r\n"
    "Sec-WebSocket-Key: %s\r\n"
    "Sec-WebSocket-Version: 13\r\n"
    "\r\n" % (USER, HOST, PORT, key)
)
s.sendall(req.encode())

# 读 HTTP 响应头
buf = b""
while b"\r\n\r\n" not in buf:
    chunk = s.recv(4096)
    if not chunk:
        print("FAIL: 连接关闭（无响应）")
        sys.exit(1)
    buf += chunk
head, rest = buf.split(b"\r\n\r\n", 1)
print("HTTP:", head.decode(errors="replace").split("\r\n")[0])
if " 101 " not in head.decode(errors="replace"):
    print("FAIL: 未升级成功")
    sys.exit(1)

data = rest
t0 = time.time()
pong_count = 0
last_send = 0


def send_pong():
    global last_send
    # 应用层 PONG：binary 帧 [type=0x04][u32 ts]（5 字节，客户端帧必须带 MASK）
    payload = bytes([0x04]) + struct.pack(">I", int(time.time() * 1000) & 0xFFFFFFFF)
    mask = os.urandom(4)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    s.sendall(bytes([0x82, 0x80 | len(payload)]) + mask + masked)
    last_send = time.time()


while time.time() - t0 < 150:
    # 每 10s 发一个 PONG 保持活动
    if time.time() - last_send > 10:
        send_pong()
        pong_count += 1
        print("[%ds] 已发送 PONG x%d" % (int(time.time() - t0), pong_count))

    try:
        s.settimeout(3)
        chunk = s.recv(65536)
    except socket.timeout:
        continue
    except ConnectionResetError:
        print("FAIL: 连接被重置（服务器拒绝/关闭）")
        sys.exit(1)
    if not chunk:
        print("FAIL: 连接关闭")
        sys.exit(1)
    data += chunk

    # 解析帧（服务端无 mask）
    while len(data) >= 2:
        b0, b1 = data[0], data[1]
        opcode = b0 & 0x0F
        fin = (b0 >> 7) & 1
        length = b1 & 0x7F
        off = 2
        if length == 126:
            if len(data) < 4:
                break
            length = struct.unpack(">H", data[2:4])[0]
            off = 4
        elif length == 127:
            if len(data) < 10:
                break
            length = struct.unpack(">Q", data[2:10])[0]
            off = 10
        if len(data) < off + length:
            break
        payload = data[off:off + length]
        data = data[off + length:]

        if opcode == 2:  # binary
            if payload and payload[0] == 0x06:
                ulen = payload[1] | (payload[2] << 8)
                url = payload[3:3 + ulen].decode("utf-8", "replace")
                print("\n=== KASM_URL 收到 ===\n%s\n=== 信令链路打通（%.1fs）===" % (url, time.time() - t0))
                sys.exit(0)
            elif payload and payload[0] == 0x05:
                elen = payload[1] | (payload[2] << 8)
                msg = payload[3:3 + elen].decode("utf-8", "replace")
                print("\nFAIL: 服务器错误: %s" % msg)
                sys.exit(1)
            else:
                print("[%.0fs] 二进制帧 type=0x%02x len=%d" % (time.time() - t0, payload[0] if payload else -1, len(payload)))
        elif opcode == 0x9:  # ping
            s.sendall(bytes([0x8A, len(payload)]) + payload)

print("FAIL: 150s 内未收到 KASM_URL")
sys.exit(1)
