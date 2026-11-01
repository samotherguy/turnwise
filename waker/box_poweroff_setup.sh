#!/bin/bash
# Run on a box the waker may power off. Lets the phone's key run exactly one thing: poweroff (or "ping" to test).
# Usage: PUB='ssh-ed25519 AAAA… waker-poweroff' bash box_poweroff_setup.sh   (PUB = /root/.ssh/waker_key.pub on the phone)
set -e
: "${PUB:?set PUB to the waker public key}"
sudo tee /usr/local/bin/waker-poweroff >/dev/null <<'EOF'
#!/bin/sh
# Forced command for the waker phone's key. "ping" tests the plumbing; "poweroff" shuts down.
case "$SSH_ORIGINAL_COMMAND" in
  ping) echo pong ;;
  poweroff) echo "powering off"; sudo -n /usr/bin/systemctl poweroff ;;
  *) echo "refused: $SSH_ORIGINAL_COMMAND"; exit 1 ;;
esac
EOF
sudo chmod 755 /usr/local/bin/waker-poweroff
echo "$USER ALL=(root) NOPASSWD: /usr/bin/systemctl poweroff" | sudo tee /etc/sudoers.d/waker-poweroff >/dev/null
sudo chmod 440 /etc/sudoers.d/waker-poweroff && sudo visudo -c -q && echo "sudoers ok"
mkdir -p ~/.ssh && chmod 700 ~/.ssh
grep -q waker-poweroff ~/.ssh/authorized_keys 2>/dev/null || echo "command=\"/usr/local/bin/waker-poweroff\",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding $PUB" >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
echo "box ready: $(grep -c waker-poweroff ~/.ssh/authorized_keys) waker key line(s)"
