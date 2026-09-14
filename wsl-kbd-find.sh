#!/bin/bash
WWW=/usr/share/kasmvnc/www
for f in $(find "$WWW" -name '*.js' | head -20); do
  n=$(grep -c 'keydown\|KeyDown\|keyCode\|keysym' "$f" 2>/dev/null)
  if [ "$n" -gt 0 ]; then
    echo "== $f : $n =="
    grep -oE 'addEventListener\("key[a-z]*"' "$f" | sort | uniq -c | head -3
    grep -oE '\.keyCode|\.key\b|\.code\b|keysym' "$f" | sort | uniq -c | head -5
  fi
done
