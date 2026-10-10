#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""WebGame input-agent GUI 直驱命令客户端。

用法:
  python gui_cmd.py <port> <cmd>
    cmd: 6=打开聊天框  7=诊断GameSettings  8=切换视角  9=关闭GUI

协议（agent TCP 下行，Java DataInputStream 大端）:
  单字节 type（0-9），无附加载荷。
"""
import socket
import struct
import sys

CMDS = {"6": "GUI_CHAT(open)", "7": "VIEW_DIAG", "8": "VIEW_TOGGLE", "9": "CLOSE_GUI"}

def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    port = int(sys.argv[1])
    cmd = sys.argv[2]
    if cmd not in CMDS:
        print("cmd must be one of: " + ", ".join(CMDS.keys()))
        sys.exit(1)
    s = socket.create_connection(("127.0.0.1", port), timeout=5)
    s.sendall(struct.pack("B", int(cmd)))
    s.close()
    print("sent %s -> %s" % (cmd, CMDS[cmd]))

if __name__ == "__main__":
    main()
