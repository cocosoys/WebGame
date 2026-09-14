#!/bin/bash
set -e
# 1. Patch screen.bundle.js: force enableWebRTC=false
SB=/usr/share/kasmvnc/www/screen.bundle.js
cp "$SB" "$SB.bak"
sed -i 's/e\.rfb\.enableWebRTC=e\.getSetting("enable_webrtc",!0,!1)/e.rfb.enableWebRTC=!1/' "$SB"
echo "screen.bundle patched: $(grep -c 'enableWebRTC=!1' "$SB")"

# 2. Patch ui-*.js
UI=$(ls /usr/share/kasmvnc/www/assets/ui-*.js | head -1)
cp "$UI" "$UI.bak"
sed -i 's/o\.rfb\.enableWebRTC=o\.getSetting("enable_webrtc")/o.rfb.enableWebRTC=!1/g' "$UI"
sed -i 's/set enableWebRTC(e){this\._useUdp=e/set enableWebRTC(e){this._useUdp=!1/' "$UI"
echo "ui patched: $(grep -c 'enableWebRTC=!1' "$UI")"

# 3. yaml: disable STUN/UDP
cat > /home/webgame/.vnc/kasmvnc.yaml <<'EOF'
network:
  protocol: http
  interface: 0.0.0.0
  websocket_port: auto
  ssl:
    require_ssl: false
  udp:
    stun_server: off
    public_ip: 127.0.0.1
EOF
echo "yaml updated"

# 4. Restart vncserver with udp disabled
su - webgame -c 'vncserver -kill :99 2>/dev/null; vncserver :99 -geometry 1280x720 -depth 24 -localhost no -SecurityTypes None -disableBasicAuth -udpPort 0' | tail -1
sleep 3
ss -tlnp 2>/dev/null | grep 8542 | head -1
echo "VNC_RESTARTED"
