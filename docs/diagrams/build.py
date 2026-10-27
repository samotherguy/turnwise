"""Draws the architecture diagrams in docs/ as SVG.

    python3 docs/diagrams/build.py

Boxes have edge anchors and every connector is an orthogonal path between two anchors, so a line always
starts and ends on a box edge. The script refuses to write a diagram when a connector misses an edge,
cuts through a box it is not attached to, crosses another connector, or when text overflows its box.
"""
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).resolve().parent.parent
FONT = "system-ui, -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"
INK, MUTED, LINE = "#1F2328", "#57606A", "#3B4148"
STYLE = {   # fill, stroke, title colour
    "panel": ("#F2FBF5", "#1DB954", "#0E7A36"),
    "frame": ("#FFFFFF", "#B7E4C7", "#0E7A36"),
    "box": ("#FFFFFF", "#8C959F", INK),
    "actor": ("#EEF4FF", "#6E8FD6", "#1F3F86"),
    "ext": ("#FFF6EE", "#F68B35", "#9A4A12"),
    "cloud": ("#FFF1F0", "#E5534B", "#9A1D17"),
}
CHAR_W = 0.58   # conservative average glyph width as a fraction of font size


class Box:
    def __init__(self, d, key, x, y, w, h, title, lines=(), kind="box", container=False, title_size=14):
        self.key, self.x, self.y, self.w, self.h = key, x, y, w, h
        self.title, self.lines, self.kind, self.container, self.title_size = title, list(lines), kind, container, title_size
        d.boxes[key] = self

    def at(self, side, v):
        """A point on an edge: side l/r takes a y, side t/b takes an x."""
        if side in "lr":
            assert self.y < v < self.y + self.h, (self.key, side, v)
            return (self.x if side == "l" else self.x + self.w, v)
        assert self.x < v < self.x + self.w, (self.key, side, v)
        return (v, self.y if side == "t" else self.y + self.h)

    def inside(self, px, py, pad=0.5):
        return self.x + pad < px < self.x + self.w - pad and self.y + pad < py < self.y + self.h - pad


class Diagram:
    def __init__(self, w, h, title, subtitle):
        self.w, self.h, self.title, self.subtitle = w, h, title, subtitle
        self.boxes, self.links, self.badges, self.notes, self.raw = {}, [], [], [], []

    def link(self, a, b, pts, label=None, dashed=False, both=False, label_at=0, label_dy=0, label_left=False):
        """pts: the anchor on box a, any bends, then the anchor on box b."""
        self.links.append(dict(a=a, b=b, pts=pts, label=label, dashed=dashed, both=both, label_at=label_at, label_dy=label_dy, label_left=label_left))

    def badge(self, n, key):
        """Numbered badge on a box's top-left corner, keyed to the 'How it works' list."""
        b = self.boxes[key]
        self.badges.append((n, b.x, b.y))

    def note(self, x, y, text, size=12, anchor="start", color=MUTED, weight="normal"):
        self.notes.append((x, y, text, size, anchor, color, weight))

    # ---------- checks ----------
    def check(self):
        def on_edge(b, p):
            x, y = p
            on_v = (abs(x - b.x) < 0.01 or abs(x - b.x - b.w) < 0.01) and b.y <= y <= b.y + b.h
            on_h = (abs(y - b.y) < 0.01 or abs(y - b.y - b.h) < 0.01) and b.x <= x <= b.x + b.w
            return on_v or on_h

        segs = []
        for k, l in enumerate(self.links):
            a, b, pts = self.boxes[l["a"]], self.boxes[l["b"]], l["pts"]
            assert on_edge(a, pts[0]), f"link {k}: starts off the edge of {a.key}: {pts[0]}"
            assert on_edge(b, pts[-1]), f"link {k}: ends off the edge of {b.key}: {pts[-1]}"
            for p, q in zip(pts, pts[1:]):
                assert p[0] == q[0] or p[1] == q[1], f"link {k}: diagonal segment {p}->{q}"
                for t in (i / 40 for i in range(41)):
                    x, y = p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t
                    for box in self.boxes.values():
                        if not box.container and box.inside(x, y):
                            raise AssertionError(f"link {k} ({a.key}->{b.key}) runs through {box.key} at {(x, y)}")
                segs.append((k, p, q))
        for i, (k1, p1, q1) in enumerate(segs):
            for k2, p2, q2 in segs[i + 1:]:
                if k1 == k2:
                    continue
                h1, h2 = p1[1] == q1[1], p2[1] == q2[1]
                if h1 == h2:
                    continue
                (hp, hq), (vp, vq) = ((p1, q1), (p2, q2)) if h1 else ((p2, q2), (p1, q1))
                x, y = vp[0], hp[1]
                if min(hp[0], hq[0]) < x < max(hp[0], hq[0]) and min(vp[1], vq[1]) < y < max(vp[1], vq[1]):
                    raise AssertionError(f"links {k1} and {k2} cross at {(x, y)}")
        for b in self.boxes.values():
            for i, text in enumerate([b.title] + b.lines):
                size = b.title_size if i == 0 else 12
                width = len(text) * size * CHAR_W
                assert width <= b.w - 16, f"{b.key}: '{text}' is ~{width:.0f}px, box is {b.w}px"
            need = 18 + b.title_size + 16 * len(b.lines)
            if not b.container:
                assert need <= b.h, f"{b.key}: text needs {need}px, box is {b.h}px"

    # ---------- drawing ----------
    def svg(self):
        self.check()
        o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}" font-family="{FONT}">',
             '<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
             f'<path d="M0,0 L10,5 L0,10 z" fill="{LINE}"/></marker></defs>',
             f'<rect width="{self.w}" height="{self.h}" fill="#FFFFFF"/>',
             f'<rect x="0" y="0" width="14" height="{self.h}" fill="#1DB954"/>',
             f'<text x="36" y="40" font-size="22" font-weight="700" fill="{INK}">{escape(self.title)}</text>',
             f'<text x="36" y="64" font-size="13" fill="{MUTED}">{escape(self.subtitle)}</text>']
        for b in sorted(self.boxes.values(), key=lambda b: not b.container):   # containers first, underneath
            fill, stroke, tc = STYLE[b.kind]
            r = 10 if b.container else 8
            o.append(f'<rect x="{b.x}" y="{b.y}" width="{b.w}" height="{b.h}" rx="{r}" fill="{fill}" stroke="{stroke}" stroke-width="{1.6 if b.kind in ("panel", "actor", "ext", "cloud") else 1.1}"/>')
            if b.container:
                o.append(f'<text x="{b.x + 14}" y="{b.y + 22}" font-size="{b.title_size}" font-weight="700" fill="{tc}">{escape(b.title)}</text>')
                for i, t in enumerate(b.lines):
                    o.append(f'<text x="{b.x + 14}" y="{b.y + 40 + 15 * i}" font-size="12" fill="{MUTED}">{escape(t)}</text>')
            else:
                cx = b.x + b.w / 2
                top = b.y + (b.h - (b.title_size + 16 * len(b.lines))) / 2 + b.title_size - 2
                o.append(f'<text x="{cx}" y="{top}" text-anchor="middle" font-size="{b.title_size}" font-weight="700" fill="{tc}">{escape(b.title)}</text>')
                for i, t in enumerate(b.lines):
                    o.append(f'<text x="{cx}" y="{top + 17 + 16 * i}" text-anchor="middle" font-size="12" fill="{MUTED}">{escape(t)}</text>')
        for l in self.links:
            d = "M" + " L".join(f"{x},{y}" for x, y in l["pts"])
            dash = ' stroke-dasharray="6 4"' if l["dashed"] else ""
            start = ' marker-start="url(#arrow)"' if l["both"] else ""
            o.append(f'<path d="{d}" fill="none" stroke="{LINE}" stroke-width="1.5"{dash} marker-end="url(#arrow)"{start}/>')
            if l["label"]:
                (x1, y1), (x2, y2) = l["pts"][l["label_at"]], l["pts"][l["label_at"] + 1]
                rows = l["label"].split("\n")
                for j, t in enumerate(rows):
                    if x1 == x2:   # vertical: text to the right of the line, clear of the arrowheads
                        tx = x1 - 7 if l["label_left"] else x1 + 7
                        ty, anchor = (y1 + y2) / 2 + l["label_dy"] + 4 + (j - (len(rows) - 1) / 2) * 13, "end" if l["label_left"] else "start"
                    else:          # horizontal: text just above the line
                        tx, ty, anchor = (x1 + x2) / 2, y1 - 6 - (len(rows) - 1 - j) * 13, "middle"
                    o.append(f'<text x="{tx}" y="{ty}" text-anchor="{anchor}" font-size="11" fill="{MUTED}" '
                             f'paint-order="stroke" stroke="#FFFFFF" stroke-width="4" stroke-linejoin="round">{escape(t)}</text>')
        for n, x, y in self.badges:
            o.append(f'<circle cx="{x}" cy="{y}" r="11" fill="#FFD54F" stroke="{INK}" stroke-width="1"/>')
            o.append(f'<text x="{x}" y="{y + 4.5}" text-anchor="middle" font-size="12" font-weight="700" fill="{INK}">{n}</text>')
        o.extend(self.raw)
        for x, y, text, size, anchor, color, weight in self.notes:
            o.append(f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-size="{size}" font-weight="{weight}" fill="{color}">{escape(text)}</text>')
        o.append("</svg>")
        return "\n".join(o)


def legend(d, x, y):
    d.note(x, y, "Legend", 12, color=INK, weight="700")
    for i, (kind, label) in enumerate([("actor", "People and their devices"), ("panel", "Turnwise"),
                                       ("ext", "Your other machine or edge"), ("cloud", "Leaves your tailnet")]):
        fill, stroke, _ = STYLE[kind]
        xx = x + 60 + i * 215
        d.raw.append(f'<rect x="{xx}" y="{y - 11}" width="14" height="14" rx="3" fill="{fill}" stroke="{stroke}" stroke-width="1.4"/>')
        d.note(xx + 22, y, label, 12)
    d.raw.append(f'<path d="M{x + 935},{y - 4} L{x + 975},{y - 4}" stroke="{LINE}" stroke-width="1.5" stroke-dasharray="6 4"/>')
    d.note(x + 983, y, "data leaves the tailnet", 12)


# ---------- 1. the system ----------
def system():
    d = Diagram(1320, 950, "Turnwise: system architecture",
                "Everything runs on your machines and your tailnet. Only an owner's question sent to Claude leaves it.")
    # people and their devices
    phone = Box(d, "phone", 30, 150, 220, 96, "Phone", ["Turnwise Android app", "always-on rolling buffer"], "actor")
    web = Box(d, "web", 30, 290, 220, 80, "Phone or laptop", ["Turnwise web app (PWA)"], "actor")
    cc = Box(d, "cc", 30, 620, 220, 96, "Claude Code", ["CLI and skill, on the", "Turnwise machine"], "actor")
    # the edge
    ts = Box(d, "ts", 300, 150, 170, 220, "Tailscale Serve", ["HTTPS on :8446", "", "adds the visitor's", "tailnet login to", "every request"], "ext")
    # the server
    Box(d, "srv", 510, 100, 520, 800, "Turnwise machine (GPU)", ["One Python service, systemd user unit"], "panel", container=True)
    api = Box(d, "api", 640, 160, 300, 110, "FastAPI · :8470", ["REST API and the web app", "one notes root per tailnet login", "no login header = local = owner"])
    work = Box(d, "work", 530, 330, 210, 330, "GPU worker", ["one job at a time", "", "1  ffmpeg: mono WAV", "2  DeepFilterNet (CPU)", "3  pyannote: who spoke", "4  Qwen3-ASR + MMS align", "5  match known voices", "6  LLM: Hinglish, English,", "    title, summary, actions"], title_size=14)
    chat = Box(d, "chat", 810, 330, 200, 170, "Chat engine", ["bge-m3 index per note", "retrieve turns", "answer with citations", "sessions kept"])
    store = Box(d, "store", 640, 720, 300, 120, "Notes on disk", ["audio, meta, status per note", "transcript.json and .md", "chunks index, chats, voices"])
    # outside the server
    llm = Box(d, "llm", 1080, 450, 210, 390, "LLM box (optional)", ["Ollama on another", "machine on the tailnet", "", "qwen3.6:35b-a3b", "bge-m3 embeddings", "", "woken by Wake-on-LAN", "powers itself off", "when idle"], "ext")
    claude = Box(d, "claude", 1080, 330, 210, 90, "Anthropic", ["Claude Code headless,", "owner only"], "cloud")

    d.link("phone", "ts", [phone.at("r", 198), ts.at("l", 198)], "HTTPS")
    d.link("web", "ts", [web.at("r", 330), ts.at("l", 330)], "HTTPS")
    d.link("ts", "api", [ts.at("r", 200), api.at("l", 200)], "login header")
    d.link("cc", "api", [cc.at("r", 668), (490, 668), (490, 245), api.at("l", 245)], "local, no header", label_at=2)
    d.link("api", "work", [api.at("b", 690), work.at("t", 690)], "queue a job")
    d.link("api", "chat", [api.at("b", 900), chat.at("t", 900)], "ask")
    d.link("api", "store", [api.at("b", 790), store.at("t", 790)], "read notes,\nstream audio", both=True, label_dy=-205)
    d.link("work", "store", [work.at("b", 690), store.at("t", 690)], "write")
    d.link("chat", "store", [chat.at("b", 900), store.at("t", 900)], "read turns")
    d.link("work", "llm", [work.at("b", 560), (560, 860), (1060, 860), (1060, 825), llm.at("l", 825)], "rewrite and summarise", label_at=1)
    d.link("chat", "llm", [chat.at("r", 480), llm.at("l", 480)], "embed,\nanswer", both=True)
    d.link("chat", "claude", [chat.at("r", 375), claude.at("l", 375)], "owner's choice", dashed=True, both=True)
    for n, key in [(1, "phone"), (2, "ts"), (3, "api"), (4, "work"), (5, "llm"), (6, "store"), (7, "chat"), (8, "claude"), (9, "cc")]:
        d.badge(n, key)
    legend(d, 36, 935)
    return d


# ---------- 2. the Android app ----------
def android():
    d = Diagram(1320, 920, "Turnwise for Android: app architecture",
                "Kotlin and Jetpack Compose, no third-party libraries beyond AndroidX. One foreground service owns the microphone.")
    you = Box(d, "you", 30, 170, 190, 90, "You, in the app", ["five tabs and the", "screens they open"], "actor")
    you2 = Box(d, "you2", 30, 492, 190, 96, "You, outside it", ["home-screen widget,", "notification buttons"], "actor")
    Box(d, "app", 250, 100, 770, 660, "Turnwise Android app", [], "panel", container=True)
    screens = Box(d, "screens", 270, 140, 730, 170, "Screens (Compose)", [], "frame", container=True)
    names = [("Notes", ["list, search,", "note, transcript"]), ("Ask", ["chats with", "cited sources"]), ("Listen", ["loudness wave,", "pick a moment"]), ("Clips", ["saved clips,", "send, share"]), ("Settings", ["window, server,", "voices"])]
    for i, (t, ls) in enumerate(names):
        Box(d, f"s{i}", 285 + i * 143, 175, 128, 74, t, ls)
    Box(d, "shell", 285, 258, 700, 40, "Shell: tabs, back stack, mini player, Blip and Bolt", [], title_size=13)
    player = Box(d, "player", 285, 340, 330, 90, "PreviewPlayer", ["one MediaPlayer for previews, clips and", "note audio streamed from the server"])
    api = Box(d, "api", 655, 340, 345, 90, "Api client", ["notes, chats, settings, uploads", "HttpURLConnection, org.json, 20 s cap"])
    rec = Box(d, "rec", 270, 460, 730, 280, "Always-on recorder (foreground service, microphone type)", [], "frame", container=True)
    w = 132
    xs = [285 + i * 142 for i in range(5)]
    widget = Box(d, "widget", xs[0], 500, w, 96, "Widget and", ["notification:", "start, stop,", "send last 15 min"])
    svc = Box(d, "svc", xs[1], 500, w, 96, "RecorderService", ["foreground,", "restarts itself"], title_size=13)
    eng = Box(d, "eng", xs[2], 500, w, 96, "RecordingEngine", ["AudioRecord", "16 kHz mono"], title_size=13)
    wr = Box(d, "wr", xs[3], 500, w, 96, "ChunkWriter", ["30 s AAC .m4a", "+ loudness/sec"])
    st = Box(d, "st", xs[4], 500, w, 96, "ChunkStore", ["keeps the last", "N hours, pins", "what is in use"])
    ex = Box(d, "ex", xs[4], 640, w, 86, "ClipExporter", ["joins chunks,", "no re-encoding"])
    server = Box(d, "server", 1080, 330, 210, 110, "Turnwise server", ["over Tailscale, HTTPS", "the same API as the", "web app"], "ext")
    plat = Box(d, "plat", 270, 810, 730, 70, "Android platform", ["AudioRecord · MediaCodec · MediaMuxer · MediaStore (Recordings) · MediaPlayer"], "box")

    d.link("you", "screens", [you.at("r", 215), screens.at("l", 215)])
    d.link("you2", "widget", [you2.at("r", 548), widget.at("l", 548)])
    d.link("screens", "api", [screens.at("b", 760), api.at("t", 760)], "data", both=True)
    d.link("screens", "player", [screens.at("b", 450), player.at("t", 450)], "play", both=True)
    d.link("api", "server", [api.at("r", 385), server.at("l", 385)], "REST, audio", both=True)
    d.link("widget", "svc", [widget.at("r", 548), svc.at("l", 548)])
    d.link("svc", "eng", [svc.at("r", 548), eng.at("l", 548)])
    d.link("eng", "wr", [eng.at("r", 548), wr.at("l", 548)])
    d.link("wr", "st", [wr.at("r", 548), st.at("l", 548)])
    d.link("st", "ex", [st.at("b", 919), ex.at("t", 919)])
    d.link("plat", "eng", [plat.at("t", 635), eng.at("b", 635)], "microphone", label_left=True)
    d.link("wr", "plat", [wr.at("b", 777), plat.at("t", 777)], "encode, mux", both=True, label_left=True)
    d.link("ex", "plat", [ex.at("b", 919), plat.at("t", 919)], "save to\nRecordings")
    for n, key in [(1, "svc"), (2, "wr"), (3, "st"), (4, "s2"), (5, "ex"), (6, "api"), (7, "player"), (8, "widget")]:
        d.badge(n, key)
    legend(d, 36, 905)
    return d


if __name__ == "__main__":
    for name, d in [("architecture.svg", system()), ("android-architecture.svg", android())]:
        (OUT / name).write_text(d.svg())
        print("wrote", OUT / name)
