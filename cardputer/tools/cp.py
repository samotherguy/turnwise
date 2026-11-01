#!/usr/bin/env python3
"""Talk to the Cardputer over USB serial: cp.py status | ls | dump <name> <out.wav> | <any command>"""
import sys, time, serial
PORT = "/dev/ttyACM0"
def main():
    args = sys.argv[1:] or ["status"]
    s = serial.Serial(PORT, 115200, timeout=2)
    time.sleep(0.3); s.reset_input_buffer()
    if args[0] == "dump":
        name, out = args[1], args[2]
        s.write(f"dump {name}\n".encode())
        head = s.readline().decode(errors="replace").strip()
        while head and not head.startswith("DUMP"): head = s.readline().decode(errors="replace").strip()
        if not head.startswith("DUMP"): print("no DUMP header:", head); return 1
        size = int(head.split()[2]); data = b""
        s.timeout = 10
        while len(data) < size:
            chunk = s.read(min(65536, size - len(data)))
            if not chunk: break
            data += chunk
        open(out, "wb").write(data); print(f"{name}: {len(data)}/{size} bytes -> {out}")
        return 0
    s.write((" ".join(args) + "\n").encode())
    t_end = time.time() + 3
    while time.time() < t_end:
        line = s.readline().decode(errors="replace").rstrip()
        if line: print(line); t_end = time.time() + 0.6
    return 0
if __name__ == "__main__": sys.exit(main())
