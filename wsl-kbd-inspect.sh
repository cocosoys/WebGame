#!/bin/bash
SB=/usr/share/kasmvnc/www/screen.bundle.js
echo "=== keydown handlers ==="
grep -oE 'addEventListener\("key[a-z]*"' "$SB" | sort | uniq -c
echo "=== key event usage ==="
grep -oE '\.keyCode|\.which|\.key\b|\.code\b' "$SB" | sort | uniq -c
echo "=== kbd/keysym mapping ==="
grep -oE 'keysym[^,;]{0,50}' "$SB" | head -4
echo "=== keydown function sample ==="
grep -oE 'onKeyDown[^}]{0,300}' "$SB" | head -2
