#!/usr/bin/env python3
"""Console UI helper for the waker phone: power key toggles the backlight, the screen goes dark after a quiet
spell, and the bottom of the panel is an on-screen keyboard that types into the Linux console through uinput.
Framebuffer only: the Adreno never initialises on this phone and any DRM compositor hangs the SoC."""
import mmap, os, select, struct, time
from fcntl import ioctl
from PIL import Image, ImageDraw, ImageFont

BL = "/sys/class/backlight/994000.dsi.0"
PWRKEY, TOUCH, TTY = "/dev/input/event0", "/dev/input/event1", "/dev/tty1"
IDLE_S, LEVEL = 90, 0.6
FLAG = "/run/screen-on"
EV = struct.Struct("qqHHi")                                 # input_event on aarch64
KEY_POWER, BTN_TOUCH, ABS_X, ABS_Y, ABS_MT_X, ABS_MT_Y, SYN = 116, 330, 0, 1, 53, 54, 0
KC = dict(esc=1, bksp=14, tab=15, enter=28, ctrl=29, shift=42, space=57, up=103, left=105, right=106, down=108)
for i, c in enumerate("1234567890-="): KC[c] = 2 + i
for i, c in enumerate("qwertyuiop[]"): KC[c] = 16 + i
for i, c in enumerate("asdfghjkl;'`"): KC[c] = 30 + i
KC["\\"] = 43
for i, c in enumerate("zxcvbnm,./"): KC[c] = 44 + i
SHIFTED = dict(zip("1234567890-=[];'`\\,./", "!@#$%^&*()_+{}:\"~|<>?"))
# rows of (label, keycode-name, width in 1/40 of the screen width)
ROWS = [
    [(c, c, 4) for c in "1234567890"],
    [(c, c, 4) for c in "qwertyuiop"],
    [(c, c, 4) for c in "asdfghjkl;"],
    [("Shift", "shift", 6)] + [(c, c, 4) for c in "zxcvbnm"] + [("Bksp", "bksp", 6)],
    [(c, c, 4) for c in "-=[]\\/,.`'"],
    [("Esc", "esc", 4), ("Tab", "tab", 4), ("Ctrl", "ctrl", 4), ("", "space", 10), ("<", "left", 3), ("v", "down", 3), ("^", "up", 3), (">", "right", 3), ("Enter", "enter", 6)],
]

def rd(p): return open(p).read().strip()
def wr(p, v): open(p, "w").write(str(v))

class Screen:
    def __init__(self):
        self.max = int(rd(f"{BL}/max_brightness")); self.on = True; self.last = time.time(); self.set(True)
    def set(self, on):
        self.on = on
        wr(f"{BL}/brightness", int(self.max * LEVEL) if on else 0); wr(f"{BL}/bl_power", 0 if on else 4)
        if on: open(FLAG, "w").close(); self.last = time.time()
        elif os.path.exists(FLAG): os.unlink(FLAG)
    def touch(self): self.last = time.time()
    def tick(self):
        if self.on and time.time() - self.last > IDLE_S: self.set(False)

class Keyboard:
    def __init__(self):
        self.w, self.h = map(int, rd("/sys/class/graphics/fb0/virtual_size").split(","))
        self.bpp = int(rd("/sys/class/graphics/fb0/bits_per_pixel")); self.stride = int(rd("/sys/class/graphics/fb0/stride"))
        self.fb = mmap.mmap(os.open("/dev/fb0", os.O_RDWR), self.stride * self.h)
        self.kh = self.h // 3 // len(ROWS) * len(ROWS); self.y0 = self.h - self.kh; self.rh = self.kh // len(ROWS)
        self.font = ImageFont.load_default(size=self.rh // 3)
        self.keys = []                                       # (x0, y0, x1, y1, label, name)
        for r, row in enumerate(ROWS):
            x = 0
            for label, name, units in row:
                kw = self.w * units // 40
                self.keys.append((x, r * self.rh, x + kw, (r + 1) * self.rh, label, name)); x += kw
        self.shift = self.ctrl = False
        self.ui = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        ioctl(self.ui, 0x40045564, 1)                        # UI_SET_EVBIT EV_KEY
        for code in KC.values(): ioctl(self.ui, 0x40045565, code)   # UI_SET_KEYBIT
        os.write(self.ui, struct.pack("80sHHHHi", b"waker-touch-keyboard", 3, 1, 1, 1, 0) + b"\0" * 1024)
        ioctl(self.ui, 0x5501)                               # UI_DEV_CREATE
        self.fit_console(); self.draw()
    def fit_console(self):
        """Keep the console above the keyboard: shrink tty1's rows to the free height (re-done after setfont).
        The column count never changes with rows, so the font width comes from it; console fonts are 2:1 (8x16, 16x32)."""
        try:
            fd = os.open(TTY, os.O_RDWR); rows, cols = struct.unpack("HHHH", ioctl(fd, 0x5413, b"\0" * 8))[:2]
            want = self.y0 // (2 * (self.w // max(cols, 1)))
            if rows != want: ioctl(fd, 0x5414, struct.pack("HHHH", want, cols, 0, 0))
            os.close(fd)
        except OSError: pass
    def blit(self, img, x, y):
        raw = img.tobytes("raw", "BGRX" if self.bpp == 32 else "BGR;16")
        bpl = img.width * (self.bpp // 8)
        for row in range(img.height):
            off = (y + row) * self.stride + x * (self.bpp // 8)
            self.fb[off:off + bpl] = raw[row * bpl:(row + 1) * bpl]
    def key_img(self, k, pressed=False):
        x0, y0, x1, y1, label, name = k
        img = Image.new("RGB", (x1 - x0, y1 - y0), (24, 24, 28)); d = ImageDraw.Draw(img)
        fill = (90, 140, 230) if pressed or (name == "shift" and self.shift) or (name == "ctrl" and self.ctrl) else (58, 58, 66)
        d.rounded_rectangle((4, 4, img.width - 5, img.height - 5), 10, fill=fill)
        text = label
        if self.shift and len(label) == 1: text = label.upper() if label.isalpha() else SHIFTED.get(label, label)
        d.text((img.width / 2, img.height / 2), text, fill=(240, 240, 240), font=self.font, anchor="mm")
        return img
    def draw(self, only=None):
        for k in ([only] if only else self.keys): self.blit(self.key_img(k, only is not None), k[0], self.y0 + k[1])
    def emit(self, code, value):
        os.write(self.ui, EV.pack(0, 0, 1, code, value) + EV.pack(0, 0, 0, 0, 0))
    def tap(self, x, y):
        y -= self.y0
        for k in self.keys:
            if k[0] <= x < k[2] and k[1] <= y < k[3]:
                name = k[5]
                if name == "shift": self.shift = not self.shift; self.draw(); return
                if name == "ctrl": self.ctrl = not self.ctrl; self.draw(); return
                self.draw(k)
                mods = [KC["shift"]] * self.shift + [KC["ctrl"]] * self.ctrl
                for m in mods: self.emit(m, 1)
                self.emit(KC[name], 1); self.emit(KC[name], 0)
                for m in mods: self.emit(m, 0)
                self.shift = self.ctrl = False
                time.sleep(0.08); self.draw()
                return

def absinfo(fd, axis):
    return struct.unpack("6i", ioctl(fd, 0x80184540 + axis, b"\0" * 24))[1:3]   # min, max

def main():
    screen = Screen()
    kb = None
    try: kb = Keyboard()
    except Exception as e: print("keyboard disabled:", e, flush=True)
    pk = os.open(PWRKEY, os.O_RDONLY | os.O_NONBLOCK); tp = os.open(TOUCH, os.O_RDONLY | os.O_NONBLOCK)
    xmin, xmax = absinfo(tp, ABS_X); ymin, ymax = absinfo(tp, ABS_Y)
    x = y = 0; down = False; pending = False; last_fit = 0
    while True:
        for fd in select.select([pk, tp], [], [], 1.0)[0]:
            data = os.read(fd, EV.size * 64)
            for i in range(0, len(data) - EV.size + 1, EV.size):
                _, _, typ, code, value = EV.unpack_from(data, i)
                if fd == pk:
                    if typ == 1 and code == KEY_POWER and value == 1: screen.set(not screen.on)
                    continue
                if typ == 3 and code in (ABS_X, ABS_MT_X): x = value
                elif typ == 3 and code in (ABS_Y, ABS_MT_Y): y = value
                elif typ == 1 and code == BTN_TOUCH: down = value == 1; pending = down
                elif typ == 0 and code == SYN and pending:
                    pending = False; screen.touch()
                    if kb and screen.on:
                        kb.tap((x - xmin) * kb.w // max(xmax - xmin, 1), (y - ymin) * kb.h // max(ymax - ymin, 1))
        screen.tick()
        if kb and screen.on and time.time() - last_fit > 2: kb.fit_console(); kb.draw(); last_fit = time.time()

if __name__ == "__main__":
    main()
