"""Renders brwsr's launcher icon: a binary tunnel — digits streaming out of a dark core
on a blue/black burst. Recreation of the owner's icon art. If you have the original
artwork, drop a square PNG at art/icon-source.png and re-run: it is used verbatim.

    python3 art/make_icon.py
"""
import math, os, random
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "..", "app", "src", "main", "res")
BLUE, INK, WHITE = (47, 109, 166), (5, 13, 22), (242, 244, 248)
S = 2048

def render():
    rnd = random.Random(3928)
    img = Image.new("RGB", (S, S), BLUE)
    d = ImageDraw.Draw(img)
    c = S / 2
    # Black streaks: jagged wedges flaring from the core.
    for i in range(46):
        a = rnd.uniform(0, 2 * math.pi)
        w = rnd.uniform(0.03, 0.12)
        pts = [(c + math.cos(a) * 150, c + math.sin(a) * 150)]
        steps = 14
        for k in range(steps + 1):
            r = 150 + (S * 0.9) * k / steps
            ww = w * (0.6 + rnd.random() * 0.8)
            pts.append((c + math.cos(a - ww) * r, c + math.sin(a - ww) * r))
        for k in range(steps, -1, -1):
            r = 150 + (S * 0.9) * k / steps
            ww = w * (0.6 + rnd.random() * 0.8)
            pts.append((c + math.cos(a + ww * 0.5) * r, c + math.sin(a + ww * 0.5) * r))
        d.polygon(pts, fill=INK)
    # Ragged dark core.
    core = []
    for k in range(120):
        a = 2 * math.pi * k / 120
        r = S * 0.17 * (0.75 + 0.5 * rnd.random() ** 2)
        core.append((c + math.cos(a) * r, c + math.sin(a) * r))
    d.polygon(core, fill=INK)

    # Digits: tiny near the core, huge at the rim, each turned to face outward.
    font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    for _ in range(520):
        t = rnd.random() ** 1.15
        r = S * (0.16 + 0.62 * t)
        a = rnd.uniform(0, 2 * math.pi)
        size = int(18 + 300 * t ** 2.2)
        if size < 14:
            continue
        ch = rnd.choice("01")
        f = ImageFont.truetype(font_path, size)
        g = Image.new("RGBA", (int(size * 1.0), int(size * 1.3)), (0, 0, 0, 0))
        ImageDraw.Draw(g).text((g.width / 2, g.height / 2), ch, font=f, fill=WHITE + (255,), anchor="mm")
        # Stretch radially for the motion feel, then align with the ray.
        g = g.resize((g.width, int(g.height * (1 + 0.35 * t))))
        g = g.rotate(-math.degrees(a) - 90, expand=True, resample=Image.BICUBIC)
        x, y = c + math.cos(a) * r - g.width / 2, c + math.sin(a) * r - g.height / 2
        layer.alpha_composite(g, (int(x), int(y)))
    img = Image.alpha_composite(img.convert("RGBA"), layer)
    return img.convert("RGB")

def main():
    src = os.path.join(ROOT, "icon-source.png")
    art = Image.open(src).convert("RGB") if os.path.exists(src) else render()
    w, h = art.size
    m = min(w, h)
    art = art.crop(((w - m) // 2, (h - m) // 2, (w + m) // 2, (h + m) // 2))
    # The source art has rounded corners baked in; adaptive icons mask their own shape,
    # so zoom past the corners (the 108dp layer is masked to ~72dp anyway).
    inset = int(m * 0.06)
    art = art.crop((inset, inset, m - inset, m - inset))
    for name, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
        out = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(out, exist_ok=True)
        art.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher_art.png"), optimize=True)
    art.resize((512, 512), Image.LANCZOS).save(os.path.join(ROOT, "icon-512.png"), optimize=True)

if __name__ == "__main__":
    main()
