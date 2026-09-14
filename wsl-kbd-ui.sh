#!/bin/bash
UI=/usr/share/kasmvnc/www/assets/ui-BOjwDkC7.js
echo "=== keydown listeners ==="
grep -oE '.{60}addEventListener\("keydown".{40}' "$UI" | head -4
echo "=== keysym mapping sample ==="
grep -oE '.{40}keysym.{60}' "$UI" | head -8
