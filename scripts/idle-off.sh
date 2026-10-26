#!/bin/sh
# Power off after 90 min with no Ollama request, no SSH connection, no running container and no GPU work.
# Turnwise on com-1 wakes this box with Wake-on-LAN when it needs Ollama (WAKE_MAC in its env file).
# ponytail: add one line to the busy checks for anything new that should keep com-2 awake.
IDLE_MIN=90
f=/run/idle-off.last
busy=0
[ "$(ss -Htn state established '( sport = :22 )' | wc -l)" -gt 0 ] && busy=1
[ -n "$(docker ps -q 2>/dev/null)" ] && busy=1
nvidia-smi --query-compute-apps=process_name --format=csv,noheader 2>/dev/null | grep -qvi ollama && busy=1
[ "$(ss -Htn state established '( sport = :11434 )' | wc -l)" -gt 0 ] && busy=1  # a request in flight (Ollama logs it only when done)
journalctl -u ollama --since "-6min" -o cat 2>/dev/null | grep -qE 'POST +"/api/(chat|generate|embed)' && busy=1  # one cron interval: the state file below does the 90-min accounting
if [ $busy = 1 ] || [ ! -f $f ]; then date +%s > $f; exit 0; fi
[ $(( $(date +%s) - $(cat $f) )) -ge $((IDLE_MIN * 60)) ] && systemctl poweroff
exit 0
