#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Direct input-agent probe (correct protocol).
Agent TCP protocol (from WebGameInputAgent bytecode):
  type 1 (keyboard): [1, int LWJGL keycode, byte pressed]
  type 3 (mouse btn): [3, int button, byte pressed]
Usage: python agent_key_probe.py <port> <lwjgl_keycode> [hold_seconds]
LWJGL codes: W=17 T=45 F5=87 E=18 Space=57 A=30 S=31 D=32
"""
import socket
import struct
import sys
import time

port = int(sys.argv[1]) if len(sys.argv) > 1 else 65016
keycode = int(sys.argv[2]) if len(sys.argv) > 2 else 17
hold = float(sys.argv[3]) if len(sys.argv) > 3 else 0.3

s = socket.create_connection(("127.0.0.1", port), timeout=5)
print("connected to agent port", port)

# agent 协议 = Java DataInputStream（大端）：type=1 + int keyCode + byte pressed
def frame_key(code, pressed):
    return struct.pack(">BIB", 1, code, 1 if pressed else 0)

s.sendall(frame_key(keycode, True))
print("sent KeyDown code", keycode)
time.sleep(hold)
s.sendall(frame_key(keycode, False))
print("sent KeyUp code", keycode)
time.sleep(0.5)
s.close()
print("done")
