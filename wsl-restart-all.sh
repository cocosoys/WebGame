#!/bin/bash
# Restart Xvnc + MC after WSL reboot
su - webgame -c 'vncserver :99 -geometry 1280x720 -depth 24 -localhost no -SecurityTypes None -disableBasicAuth -udpPort 0' 2>&1 | tail -1
sleep 3
ss -tlnp 2>/dev/null | grep 8542 | head -1
su - webgame -c 'cd /home/webgame/mc && nohup bash launch.sh > mc.log 2>&1 &'
echo "MC_STARTING"
