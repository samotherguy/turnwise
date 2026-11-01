#!/usr/bin/env python3
"""Generate Frito and Ramona as Pixar-style renders with FLUX.1 Kontext through a running ComfyUI (127.0.0.1:8188).

  gen.py poses              one render per cat per pose  -> out/<cat>_<pose>.png
  gen.py variants           eyes-closed and mouth-open edits of each pose -> out/<cat>_<pose>_blink.png / _meow.png
  gen.py cutout             background removed -> sprites/<name>.png (RGBA, trimmed)
  gen.py all

Reference photos in refs/, chosen per pose so Kontext starts from the closest real posture.
Run with the ComfyUI venv python: ~/.local/opt/ComfyUI/venv/bin/python gen.py all
"""
import json, os, sys, time, shutil, urllib.request, uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
COMFY = Path.home() / ".local/opt/ComfyUI"
API = "http://127.0.0.1:8188"
OUT = HERE / "out"; SPR = HERE / "sprites"
STYLE = ("Turn this photo into a cute Pixar-style 3D animated character render of the same cat: "
         "big glossy expressive eyes, soft detailed fur, rounded friendly proportions, subtle smile, "
         "warm soft studio lighting, the whole body visible and centered, plain flat light gray background, no text. ")
CATS = {
    "frito": "The cat is a big, fit, athletic short-haired black cat: large but lean and long-bodied, muscular, definitely not fat and not small, with yellow-green eyes; keep it black and keep it lean. ",
    "ramona": "The cat is a very fat, round, heavy grizzled grey-brown mackerel tabby with a big belly and a wide body: muted taupe salt-and-pepper ticked fur (each hair banded black and tan), black stripes, a slightly lighter grey-tan chest, absolutely not orange and not reddish, a white chin and muzzle, a pink nose, muted green eyes and long white whiskers; keep the muted grey-brown colour, the markings and the fat round body. ",
}
# pose -> (reference photo, pose instruction)
POSES = {
    "ramona": {
        "sit":   ("ramona_loaf.jpg",   "It lies in a compact loaf facing the viewer, body low, front paws tucked under its chest, looking at the camera."),
        "alert": ("ramona_lookup.jpg", "It lies in a loaf facing the viewer with wide round eyes and ears up, startled and curious."),
        "think": ("ramona_yarn.jpg",   "It sits facing the viewer looking down at a small red ball of yarn by its paws."),
        "sleep": ("ramona_asleep.jpg", "It sleeps flat on its back with its huge round belly up, paws in the air, eyes closed, blissful, very fat."),
        "bow":   ("ramona_loaf.jpg",   "It sits on the plain floor, no furniture or cushion, a very fat round cat, with one front paw raised high batting at a toy above, eyes wide."),
    },
    "frito": {
        "sit":   ("frito_sitting.jpg", "It sits upright facing the viewer, looking at the camera, tail curled round its front paws."),
        "alert": ("frito_profile.jpg", "It sits in profile facing right with ears pricked forward, alert, tail up with a hooked tip."),
        "think": ("frito_front.jpg",   "It sits facing the viewer with its chin tilted up, gazing upward thoughtfully."),
        "sleep": ("frito_profile.jpg", "It lies stretched out on its side asleep on the plain floor, long lean body, legs extended, eyes closed, tail trailing behind, relaxed."),
        "bow":   ("frito_front.jpg",   "It is in a playful hunting crouch on the floor, chest low and rump up, one front paw stretched up high reaching for a toy above, eyes wide."),
    },
}
VARIANTS = {
    "blink": "Close the cat's eyes completely, as in a relaxed blink. Keep everything else exactly the same.",
    "meow":  "Open the cat's mouth in a small meow. Keep everything else exactly the same.",
}


def graph(image: str, prompt: str, prefix: str, seed: int) -> dict:
    return {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "flux1-dev-kontext_fp8_scaled.safetensors", "weight_dtype": "fp8_e4m3fn"}},
        "2": {"class_type": "DualCLIPLoader", "inputs": {"clip_name1": "clip_l.safetensors", "clip_name2": "t5xxl_fp8_e4m3fn_scaled.safetensors", "type": "flux"}},
        "3": {"class_type": "VAELoader", "inputs": {"vae_name": "ae.safetensors"}},
        "4": {"class_type": "LoadImage", "inputs": {"image": image}},
        "5": {"class_type": "FluxKontextImageScale", "inputs": {"image": ["4", 0]}},
        "6": {"class_type": "VAEEncode", "inputs": {"pixels": ["5", 0], "vae": ["3", 0]}},
        "7": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["2", 0]}},
        "8": {"class_type": "ReferenceLatent", "inputs": {"conditioning": ["7", 0], "latent": ["6", 0]}},
        "9": {"class_type": "FluxGuidance", "inputs": {"conditioning": ["8", 0], "guidance": 2.5}},
        "10": {"class_type": "ConditioningZeroOut", "inputs": {"conditioning": ["7", 0]}},
        "11": {"class_type": "EmptySD3LatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
        "12": {"class_type": "KSampler", "inputs": {"model": ["1", 0], "positive": ["9", 0], "negative": ["10", 0], "latent_image": ["11", 0],
                                                      "seed": seed, "steps": 20, "cfg": 1.0, "sampler_name": "euler", "scheduler": "simple", "denoise": 1.0}},
        "13": {"class_type": "VAEDecode", "inputs": {"samples": ["12", 0], "vae": ["3", 0]}},
        "14": {"class_type": "SaveImage", "inputs": {"images": ["13", 0], "filename_prefix": prefix}},
    }


def api(path: str, data: dict | None = None):
    req = urllib.request.Request(API + path, data=json.dumps(data).encode() if data else None, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


def stage_input(src: Path, max_side: int = 1024) -> str:
    """Copy a reference into ComfyUI's input folder, downscaled so the encoder is quick."""
    from PIL import Image, ImageOps
    dst = COMFY / "input" / src.name
    if not dst.exists() or dst.stat().st_mtime < src.stat().st_mtime:   # re-stage when the source was regenerated
        im = ImageOps.exif_transpose(Image.open(src)).convert("RGB")
        im.thumbnail((max_side, max_side))
        im.save(dst, quality=92)
    return src.name


def render(image: str, prompt: str, name: str, seed: int = 7) -> Path:
    OUT.mkdir(exist_ok=True)
    target = OUT / f"{name}.png"
    if target.exists():
        print(f"  {name}: exists, skipping"); return target
    t0 = time.time()
    pid = api("/prompt", {"prompt": graph(image, prompt, f"cats/{name}", seed), "client_id": str(uuid.uuid4())})["prompt_id"]
    while True:
        time.sleep(3)
        h = api(f"/history/{pid}")
        if pid in h:
            st = h[pid].get("status", {})
            if st.get("status_str") == "error":
                raise RuntimeError(f"{name}: {json.dumps(st)[:500]}")
            outs = [o for n in h[pid]["outputs"].values() for o in n.get("images", [])]
            if outs:
                src = COMFY / "output" / outs[0].get("subfolder", "") / outs[0]["filename"]
                shutil.copy(src, target)
                print(f"  {name}: {time.time() - t0:.0f}s")
                return target
        print(f"  {name}: {time.time() - t0:.0f}s…", end="\r")


def poses():
    for cat, ps in POSES.items():
        for pose, (ref, instr) in ps.items():
            render(stage_input(HERE / "refs" / ref), STYLE + CATS[cat] + instr, f"{cat}_{pose}")


def variants():
    for cat, ps in POSES.items():
        for pose in ps:
            base = OUT / f"{cat}_{pose}.png"
            if not base.exists():
                continue
            img = stage_input(base)
            for v, instr in VARIANTS.items():
                if v == "meow" and pose not in ("sit", "alert"):
                    continue
                if v == "blink" and pose == "bow":   # the edit tends to redraw the whole cat
                    continue
                if v == "blink" and pose == "sleep":
                    instr = "The cat is fast asleep: both eyes completely shut, no eye visible at all, just closed eyelids, relaxed face. Keep everything else exactly the same."
                render(img, instr, f"{cat}_{pose}_{v}", seed=11 if v == "blink" else 23)


def largest_blob(im):
    """Keep only the biggest connected opaque region, so a stray prop (the ball) does not come along."""
    import numpy as np
    from scipy import ndimage
    a = np.array(im)
    mask = a[..., 3] > 40
    labels, n = ndimage.label(mask)
    if n > 1:
        sizes = ndimage.sum(mask, labels, range(1, n + 1))
        keep = labels == (int(np.argmax(sizes)) + 1)
        a[..., 3] = np.where(keep, a[..., 3], 0)
    from PIL import Image
    return Image.fromarray(a)


def cutout():
    from rembg import remove, new_session
    from PIL import Image
    SPR.mkdir(exist_ok=True)
    session = new_session("isnet-general-use")
    for f in sorted(OUT.glob("*.png")):
        dst = SPR / f.name
        if dst.exists():
            continue
        im = Image.open(f).convert("RGBA")
        cut = remove(im, session=session, alpha_matting=True, alpha_matting_foreground_threshold=240, alpha_matting_background_threshold=15)
        cut = largest_blob(cut)
        bbox = cut.getbbox()
        if bbox:
            cut = cut.crop(bbox)
        cut.save(dst)
        print(f"  {dst.name}: {cut.size}")


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    if what in ("poses", "all"): print("== poses"); poses()
    if what in ("variants", "all"): print("== variants"); variants()
    if what in ("cutout", "all"): print("== cutout"); cutout()
