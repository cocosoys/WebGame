#!/bin/bash
# Clean restart MC
pkill -f launchwrapper 2>/dev/null
pkill -f 'net.minecraft.launchwrapper' 2>/dev/null
sleep 2
ps aux | grep -c '[L]aunch'
su - webgame -c 'cd /home/webgame/mc && nohup bash launch.sh > mc.log 2>&1 &'
echo "MC_LAUNCHED"
sleep 50
echo "=== process ==="
ps aux | grep '[L]aunch' | grep -v grep | wc -l
echo "=== tail ==="
tail -3 /home/webgame/mc/mc.log
