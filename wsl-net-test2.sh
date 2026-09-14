#!/bin/bash
python3 - <<'PYEOF'
import socket
for host in ['127.0.0.1']:
    s = socket.socket(); s.settimeout(3)
    try:
        s.connect((host, 25574)); print('TCP_OK', host, '25574')
    except Exception as e:
        print('TCP_FAIL', host, e)
    finally:
        s.close()
PYEOF
