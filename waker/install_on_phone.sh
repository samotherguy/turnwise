#!/bin/sh
# Run as root on the waker phone after copying waker.py to /home/abhyuday/. Idempotent.
set -e
# Your box's Tailscale address comes from waker.env beside this script (gitignored); see waker.env.example.
. "$(dirname "$0")/waker.env"
: "${BOX_TS_IP:?set in waker.env}" "${BOX_TS_HOST:?set in waker.env}"
grep -q "$BOX_TS_HOST" /etc/hosts || echo "$BOX_TS_IP $BOX_TS_HOST" >> /etc/hosts
grep -q ^UPSTREAM /etc/waker.conf || printf 'UPSTREAM=https://%s:8446\nPROXY_PORT=8447\nWAKE_WAIT=150\nREADY_PATH=/api/me\n' "$BOX_TS_HOST" >> /etc/waker.conf
install -m755 /home/abhyuday/waker.py /usr/local/bin/waker.py
python3 /usr/local/bin/waker.py --check
rc-service waker restart; sleep 2; rc-service waker status
tailscale serve --bg --https=8446 http://127.0.0.1:8447
echo "proxy: $(wget -qO- --timeout=20 http://127.0.0.1:8447/api/me)"
