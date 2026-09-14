#!/bin/bash
# WSL -> Windows 25574 connectivity test
ip route show default
python3 - <<'PYEOF'
import socket, subprocess
gw = subprocess.check_output(['ip','route','show','default']).decode().split()[2]
print('GW:', gw)
for host in [gw, '127.0.0.1']:
    s = socket.socket(); s.settimeout(2)
    try:
        s.connect((host, 25574)); print('TCP_OK', host)
    except Exception as e:
        print('TCP_FAIL', host, e)
    finally:
        s.close()
PYEOF
