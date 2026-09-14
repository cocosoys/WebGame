#!/bin/bash
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq x11-xserver-utils 2>&1 | tail -1
su - webgame -c 'DISPLAY=:99 xrandr 2>&1 | head -5'
su - webgame -c 'cd /home/webgame/mc && LWJGL_DISABLE_XRANDR=true nohup bash launch.sh > mc.log 2>&1 &'
sleep 40
echo ===
tail -15 /home/webgame/mc/mc.log
echo ===
ps aux | grep -c '[L]aunch'
