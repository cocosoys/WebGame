#!/bin/bash
CR=$(ls -t /home/webgame/mc/.minecraft/crash-reports/crash-*.txt | head -1)
echo "FILE=$CR"
sed -n '1,75p' "$CR"
