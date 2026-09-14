#!/bin/bash
su - webgame -c 'DISPLAY=:99 glxinfo 2>&1 | grep -i "opengl renderer"'
su - webgame -c 'DISPLAY=:99 glxinfo 2>&1 | grep -i "opengl version"'
su - webgame -c 'cd /home/webgame/mc && nohup bash launch.sh > mc.log 2>&1 &'
sleep 35
echo ===
tail -12 /home/webgame/mc/mc.log
echo ===
ps aux | grep -c '[L]aunch'
