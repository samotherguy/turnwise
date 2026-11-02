#!/bin/sh -e
# Run on the phone as root (doas sh install.sh) from the directory holding these files.
apk add py3-pillow terminus-font
install -m755 screen.py /usr/local/bin/waker-screen.py
install -m755 waker-menu waker-login /usr/local/bin/
install -m755 waker-screen.initd /etc/init.d/waker-screen
rc-update add waker-screen default 2>/dev/null || true
rc-update del tinydm default 2>/dev/null || true
# tty1: autologin, the other ttys stay as they were
sed -i 's|^tty1::respawn:.*|tty1::respawn:/sbin/getty -n -l /usr/local/bin/waker-login 38400 tty1|' /etc/inittab
grep -q waker-menu /home/abhyuday/.profile 2>/dev/null || printf '\n[ "$(tty)" = /dev/tty1 ] && exec /usr/local/bin/waker-menu\n' >> /home/abhyuday/.profile
chown abhyuday:abhyuday /home/abhyuday/.profile
rc-service waker-screen restart
kill -HUP 1          # init re-reads inittab and respawns tty1 with the autologin
echo installed
