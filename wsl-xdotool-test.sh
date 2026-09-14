#!/bin/bash
export DISPLAY=:99
export XAUTHORITY=/home/webgame/.Xauthority
echo "DISPLAY=$DISPLAY XA=$XAUTHORITY"
xdotool getactivewindow getwindowname 2>&1 | head -2
xdotool key h i
sleep 1
echo INJECTED
