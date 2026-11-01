#!/bin/bash
# Starts the ComfyUI server in the background on 127.0.0.1:8188 (logs in ~/cats-art/comfy.log).
cd ~/.local/opt/ComfyUI || exit 1
if curl -s -m 2 http://127.0.0.1:8188/system_stats >/dev/null; then echo "already running"; exit 0; fi
nohup venv/bin/python main.py --listen 127.0.0.1 --port 8188 --preview-method none > ~/cats-art/comfy.log 2>&1 &
for i in $(seq 1 60); do curl -s -m 2 http://127.0.0.1:8188/system_stats >/dev/null && { echo "ComfyUI up after $((i*2)) s"; exit 0; }; sleep 2; done
echo "ComfyUI did not come up; see ~/cats-art/comfy.log"; exit 1
