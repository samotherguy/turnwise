#!/bin/sh
# Run as root on the waker phone after copying waker.py to /home/abhyuday/. Idempotent.
set -e
grep -q abhyuday-samadder-com /etc/hosts || echo "100.118.250.80 <box>.<tailnet>.ts.net" >> /etc/hosts
grep -q ^UPSTREAM /etc/waker.conf || printf 'UPSTREAM=https://<box>.<tailnet>.ts.net:8446\nPROXY_PORT=8447\nWAKE_WAIT=150\nREADY_PATH=/api/me\n' >> /etc/waker.conf
install -m755 /home/abhyuday/waker.py /usr/local/bin/waker.py
python3 /usr/local/bin/waker.py --check
rc-service waker restart; sleep 2; rc-service waker status
tailscale serve --bg --https=8446 http://127.0.0.1:8447
echo "proxy: $(wget -qO- --timeout=20 http://127.0.0.1:8447/api/me)"
