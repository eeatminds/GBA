"""Draws the launcher PNGs, Android TV images and Play Store graphics from the same shapes
as res/drawable/lemuroid_launcher_foreground.xml: two handhelds joined by a link cable.
Run: python3 scripts/make_icons.py (APP_NAME overrides the name on the banners)."""
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "lemuroid-app", "src", "main", "res")
STORE = os.path.join(ROOT, "docs", "store")
NAME = os.environ.get("APP_NAME", "GBA/GBC Emulator")
SS = 4  # supersampling for smooth edges

# Background: a diagonal gradient over the 108dp adaptive icon canvas.
TOP_LEFT = np.array([0x7B, 0x5C, 0xFF], dtype=float)
BOTTOM_RIGHT = np.array([0x3A, 0x1F, 0xB5], dtype=float)
WHITE = (255, 255, 255, 255)
GOLD = (0xFF, 0xD8, 0x6B, 255)
INK = (0x3A, 0x1F, 0xB5, 255)

# Where the artwork sits on the 108dp canvas (centre and size), for the banners.
ART_CENTER = (54.0, 55.0)
ART_HEIGHT = 43.0


def rounded_rect(x, y, w, h, r, steps=10):
    points = []
    for cx, cy, start in ((x + w - r, y + r, -90), (x + w - r, y + h - r, 0), (x + r, y + h - r, 90), (x + r, y + r, 180)):
        for i in range(steps + 1):
            a = math.radians(start + 90 * i / steps)
            points.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return points


def circle(cx, cy, r, steps=32):
    return [(cx + r * math.cos(2 * math.pi * i / steps), cy + r * math.sin(2 * math.pi * i / steps)) for i in range(steps)]


def handheld(body):
    """One handheld in its own coordinates (centre at 0,0), as (colour, outline) pairs."""
    return [
        (body, rounded_rect(-10, -14, 20, 28, 4)),
        (INK, rounded_rect(-6.5, -10.5, 13, 10, 1.6)),
        (INK, rounded_rect(-7, 4, 6.4, 2.2, 0.7)),
        (INK, rounded_rect(-4.9, 1.9, 2.2, 6.4, 0.7)),
        (INK, circle(3.6, 6.3, 1.6)),
        (INK, circle(6.4, 3.9, 1.6)),
    ]


# (x, y, degrees clockwise, body colour), the same as the vector's groups.
HANDHELDS = ((40, 49, -10, WHITE), (68, 49, 10, GOLD))
CABLE = ((42.3, 62), (42, 81), (66, 81), (65.7, 62))
CABLE_WIDTH = 2.8


def place(points, tx, ty, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    return [(tx + x * c - y * s, ty + x * s + y * c) for x, y in points]


def bezier(p0, p1, p2, p3, steps=64):
    out = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        out.append(tuple(u**3 * a + 3 * u * u * t * b + 3 * u * t * t * c + t**3 * d for a, b, c, d in zip(p0, p1, p2, p3)))
    return out


def draw_art(draw, to_px, unit):
    """Draws the cable and both handhelds; to_px maps canvas units to pixels."""
    cable = [to_px(p) for p in bezier(*CABLE)]
    width = CABLE_WIDTH * unit
    draw.line(cable, fill=WHITE, width=max(1, round(width)), joint="curve")
    for x, y in (cable[0], cable[-1]):
        draw.ellipse([x - width / 2, y - width / 2, x + width / 2, y + width / 2], fill=WHITE)
    for tx, ty, degrees, body in HANDHELDS:
        for colour, shape in handheld(body):
            draw.polygon([to_px(p) for p in place(shape, tx, ty, degrees)], fill=colour)


def gradient(width, height, to_canvas):
    """Background image; to_canvas maps pixel grids to canvas units (u, v)."""
    ys, xs = np.mgrid[0:height, 0:width].astype(float)
    u, v = to_canvas(xs, ys)
    t = np.clip((u + v) / 216.0, 0, 1)[..., None]
    rgb = TOP_LEFT * (1 - t) + BOTTOM_RIGHT * t
    alpha = np.full((height, width, 1), 255.0)
    return Image.fromarray(np.concatenate([rgb, alpha], axis=2).round().astype(np.uint8), "RGBA")


def icon(size, shape):
    """shape: "square" (full bleed, for stores and TV), "rounded" or "round" (old launchers)."""
    big = size * SS
    # Old launchers show the middle 72dp of the 108dp canvas, like adaptive icons do.
    scale = big / 72.0
    img = gradient(big, big, lambda x, y: (18 + x / scale, 18 + y / scale))
    draw_art(ImageDraw.Draw(img), lambda p: ((p[0] - 18) * scale, (p[1] - 18) * scale), scale)
    if shape != "square":
        mask = Image.new("L", (big, big), 0)
        m = ImageDraw.Draw(mask)
        if shape == "round":
            m.ellipse([0, 0, big - 1, big - 1], fill=255)
        else:
            m.rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.22), fill=255)
        img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


def font(size, bold=True):
    names = ("DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf") if bold else (
        "DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
    for path in names:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def banner(width, height, text):
    big_w, big_h = width * SS, height * SS
    img = gradient(big_w, big_h, lambda x, y: (x / big_w * 108, y / big_h * 108))
    draw = ImageDraw.Draw(img)
    unit = big_h * 0.62 / ART_HEIGHT
    cx, cy = big_h * 0.5, big_h / 2
    draw_art(draw, lambda p: (cx + (p[0] - ART_CENTER[0]) * unit, cy + (p[1] - ART_CENTER[1]) * unit), unit)

    # The name on two lines when it has a space ("GBA/GBC" over "Emulator").
    first, _, second = text.partition(" ")
    start = big_h * 0.98
    available = big_w - start - big_h * 0.1
    size = int(big_h * 0.24)
    while size > 8 and max(draw.textlength(line, font=font(size)) for line in (first, second or first)) > available:
        size = int(size * 0.93)
    if second:
        draw.text((start, cy - size * 0.08), first, fill=WHITE, font=font(size), anchor="ls")
        draw.text((start, cy + size * 0.08), second, fill=GOLD, font=font(size), anchor="lt")
    else:
        draw.text((start, cy), first, fill=WHITE, font=font(size), anchor="lm")
    return img.resize((width, height), Image.LANCZOS)


def main():
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, size in sizes.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        icon(size, "rounded").save(os.path.join(folder, "lemuroid_launcher.png"))
        icon(size, "round").save(os.path.join(folder, "lemuroid_launcher_round.png"))
    banner(320, 180, NAME).convert("RGB").save(os.path.join(RES, "mipmap-xhdpi", "ic_banner.png"))
    icon(160, "square").save(os.path.join(RES, "mipmap-xhdpi", "lemuroid_tv_channel.png"))
    os.makedirs(STORE, exist_ok=True)
    # Google Play wants a full square; it rounds the corners itself.
    icon(512, "square").save(os.path.join(STORE, "icon-512.png"))
    banner(1024, 500, NAME).convert("RGB").save(os.path.join(STORE, "feature-graphic-1024x500.png"))


if __name__ == "__main__":
    main()
