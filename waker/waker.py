#!/usr/bin/env python3
"""Turnwise waker: a tiny HTTP service that wakes the GPU box (and the LLM box) over Wake-on-LAN.

    GET /status        -> {"server": true|false, "llm": true|false}   (TCP probes, 1 s each)
    POST /wake         -> sends magic packets for the server; {"sent": true}
    POST /wake/llm     -> same for the LLM box
    POST /off, /off/llm -> ssh SERVER_SSH / LLM_SSH with SSH_KEY and run the forced poweroff command

Wake-on-demand proxy (second listener, 127.0.0.1:PROXY_PORT, fronted by `tailscale serve --https=8446`):
every request is forwarded to UPSTREAM (the box's Turnwise URL); if the box is down it is woken first and the
request held until UPSTREAM answers READY_PATH, at most WAKE_WAIT seconds. The app just points at the waker.
    GET /              -> mobile page with status + Wake buttons

Config in /etc/waker.conf (key=value): SERVER_MAC, SERVER_HOST, SERVER_PORT, LLM_MAC, LLM_HOST, LLM_PORT,
BROADCASTS (comma-separated addresses to send the packet to), LISTEN (host:port).
Standard library only, so it runs on the phone's Alpine without extra packages.
"""
import http.client, json, socket, subprocess, sys, threading, time
from urllib.parse import urlsplit
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CFG = {"PROXY_PORT": "8447", "WAKE_WAIT": "150", "READY_PATH": "/api/me", "SSH_KEY": "/root/.ssh/waker_key", "SERVER_PORT": "8470", "LLM_PORT": "11434", "BROADCASTS": "255.255.255.255", "LISTEN": "0.0.0.0:8490"}
for line in open("/etc/waker.conf"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        CFG[k.strip()] = v.strip()


def magic(mac):
    raw = bytes.fromhex(mac.replace(":", "").replace("-", ""))
    assert len(raw) == 6, mac
    return b"\xff" * 6 + raw * 16


def wake(mac):
    pkt = magic(mac)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for addr in CFG["BROADCASTS"].split(","):
            for port in (9, 7):
                for _ in range(3):   # a few copies: Wi-Fi drops broadcasts now and then
                    s.sendto(pkt, (addr.strip(), port))


def off(target):
    """SSH to user@host with the restricted key; the box's forced command only accepts 'ping' or 'poweroff'."""
    if not target:
        return {"ok": False, "error": "no SSH target configured"}
    r = subprocess.run(["ssh", "-i", CFG["SSH_KEY"], "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
                        "-o", "ConnectTimeout=5", target, "poweroff"], capture_output=True, text=True, timeout=20)
    return {"ok": r.returncode == 0, "output": (r.stdout + r.stderr).strip()}


def up(host, port):
    if not host:
        return None
    try:
        with socket.create_connection((host, int(port)), timeout=1.0):
            return True
    except OSError:
        return False


def status():
    return {"server": up(CFG.get("SERVER_HOST"), CFG["SERVER_PORT"]), "llm": up(CFG.get("LLM_HOST"), CFG["LLM_PORT"])}


PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Waker</title><style>
body{font-family:system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:24px;max-width:480px;margin:auto}
h1{font-weight:600;font-size:22px;margin:8px 0 24px}
.card{background:#1c1c1c;border-radius:14px;padding:18px;margin-bottom:16px;display:flex;align-items:center;justify-content:space-between}
.name{font-size:18px;font-weight:600}.st{font-size:13px;color:#999;margin-top:4px}
.dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:6px;background:#666}
.up .dot{background:#2ecc71}.down .dot{background:#e74c3c}
button{background:#1db954;color:#000;border:0;border-radius:999px;padding:12px 18px;font-size:15px;font-weight:600;margin-left:6px}
button.off{background:#e74c3c;color:#fff}
button:disabled{background:#333;color:#777}
#msg{color:#999;font-size:13px;text-align:center;min-height:1.2em}
</style></head><body><h1>Waker</h1>
<div class="card" id="server"><div><div class="name">com</div><div class="st"><span class="dot"></span><span class="t">checking</span></div></div><div><button onclick="wake('server')">Wake</button><button class="off" onclick="off('server')">Off</button></div></div>
<div class="card" id="llm"><div><div class="name">com-2</div><div class="st"><span class="dot"></span><span class="t">checking</span></div></div><div><button onclick="wake('llm')">Wake</button><button class="off" onclick="off('llm')">Off</button></div></div>
<div id="msg"></div>
<script>
async function poll(){try{const s=await (await fetch('/status',{cache:'no-store'})).json();
for(const k of ['server','llm']){const c=document.getElementById(k);c.className='card '+(s[k]?'up':'down');
c.querySelector('.t').textContent=s[k]?'online':'offline';c.querySelector('button').disabled=!!s[k];c.querySelector('button.off').disabled=!s[k];}}catch(e){}}
async function wake(k){const m=document.getElementById('msg');m.textContent='sending magic packet...';
await fetch(k==='server'?'/wake':'/wake/llm',{method:'POST'});m.textContent='sent, booting takes ~30-60 s';setTimeout(()=>m.textContent='',60000);}
async function off(k){const m=document.getElementById('msg');if(!confirm('Shut down '+(k==='server'?'com':'com-2')+'?'))return;
m.textContent='sending shutdown...';const r=await (await fetch(k==='server'?'/off':'/off/llm',{method:'POST'})).json();
m.textContent=r.ok?'shutting down':'failed: '+(r.error||r.output);setTimeout(()=>m.textContent='',15000);}
poll();setInterval(poll,5000);
</script></body></html>"""

class H(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/status":
            return self._json(200, status())
        if self.path == "/":
            body = PAGE.encode()
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body); return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/wake":
            wake(CFG["SERVER_MAC"]); return self._json(200, {"sent": True, "target": "server"})
        if self.path == "/wake/llm" and CFG.get("LLM_MAC"):
            wake(CFG["LLM_MAC"]); return self._json(200, {"sent": True, "target": "llm"})
        if self.path == "/off":
            return self._json(200, off(CFG.get("SERVER_SSH")))
        if self.path == "/off/llm":
            return self._json(200, off(CFG.get("LLM_SSH")))
        self._json(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))


# ---- wake-on-demand proxy ----
UP = urlsplit(CFG.get("UPSTREAM", ""))
HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers", "transfer-encoding", "upgrade", "host"}
_last_wake = 0.0


def upstream(timeout):
    c = http.client.HTTPSConnection(UP.hostname, UP.port or 443, timeout=timeout)
    c.connect()
    return c


def ready():
    try:
        c = upstream(5); c.request("GET", CFG["READY_PATH"], headers={"Host": UP.netloc}); r = c.getresponse(); r.read(); c.close()
        return r.status == 200
    except (OSError, http.client.HTTPException):
        return False


def ensure_up(wait):
    """Wake the box if needed; wait up to `wait` s for it to answer. One magic packet per 30 s, not per request."""
    global _last_wake
    deadline = time.time() + wait
    while not ready():
        if time.time() - _last_wake > 30:
            wake(CFG["SERVER_MAC"]); _last_wake = time.time()
        if time.time() > deadline:
            return False
        time.sleep(3)
    return True


def dechunk(rfile):
    out = bytearray()
    while True:
        size = int(rfile.readline().split(b";")[0].strip() or b"0", 16)
        if size == 0:
            rfile.readline()   # ponytail: trailers ignored, nothing we proxy sends them
            return bytes(out)
        out += rfile.read(size); rfile.readline()


class Proxy(H):
    protocol_version = "HTTP/1.1"

    def _body(self):
        n = self.headers.get("Content-Length")
        if n:
            return self.rfile.read(int(n))
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            return dechunk(self.rfile)
        return None

    def _any(self):
        body = self._body()   # read first so an upload is not lost while the box boots
        self.close_connection = True
        if not ensure_up(int(CFG["WAKE_WAIT"])):
            return self._json(503, {"error": "server did not come up in time; wake was sent, retry in a minute"})
        hdrs = {k: v for k, v in self.headers.items() if k.lower() not in HOP}
        hdrs["Host"] = UP.netloc
        if body is not None:
            hdrs["Content-Length"] = str(len(body))
        try:
            c = upstream(10); c.sock.settimeout(600)   # long reads: transcription and LLM calls
            c.request(self.command, self.path, body=body, headers=hdrs)
            r = c.getresponse()
            self.send_response(r.status, r.reason)
            for k, v in r.getheaders():
                if k.lower() not in HOP:
                    self.send_header(k, v)
            self.send_header("Connection", "close"); self.end_headers()
            while True:
                chunk = r.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk); self.wfile.flush()
            c.close()
        except (OSError, http.client.HTTPException) as e:
            try: self._json(502, {"error": str(e)})
            except OSError: pass

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = do_HEAD = do_OPTIONS = _any


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--check":   # self-check: packet shape and config parse
        p = magic("00:10:f3:a1:5f:7a")
        assert len(p) == 102 and p[:6] == b"\xff" * 6 and p[6:12] == bytes.fromhex("0010f3a15f7a")
        assert "SERVER_MAC" in CFG, "SERVER_MAC missing from /etc/waker.conf"
        import io
        assert dechunk(io.BytesIO(b"4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n")) == b"Wikipedia"
        print("waker self-check ok"); sys.exit(0)
    host, port = CFG["LISTEN"].rsplit(":", 1)
    if UP.hostname:
        threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", int(CFG["PROXY_PORT"])), Proxy).serve_forever, daemon=True).start()
    ThreadingHTTPServer((host, int(port)), H).serve_forever()
