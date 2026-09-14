#!/bin/bash
export DISPLAY=:99
export XAUTHORITY=/home/webgame/.Xauthority
echo "DISPLAY=$DISPLAY"
# 找 xterm 窗口
XTERM_ID=$(xdotool search --class xterm 2>/dev/null | head -1)
echo "xterm id: $XTERM_ID"
if [ -n "$XTERM_ID" ]; then
  xdotool windowactivate --sync "$XTERM_ID" 2>&1 | head -1
  xdotool windowfocus "$XTERM_ID" 2>&1 | head -1
  sleep 0.5
  xdotool key --window "$XTERM_ID" h i 2>&1 | head -1
  sleep 0.5
  xdotool type --window "$XTERM_ID" "HELLO_X11" 2>&1 | head -1
fi
echo DONE
