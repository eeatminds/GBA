"""Draws the launcher PNGs, TV banner and Play Store graphics from the same design
as res/drawable/lemuroid_launcher_foreground.xml. Run: python3 scripts/make_icons.py"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "lemuroid-app", "src", "main", "res")
STORE = os.path.join(ROOT, "docs", "store")
BG = (44, 79, 196, 255)
WHITE = (255, 255, 255, 255)
GOLD = (255, 216, 107, 255)
SS = 4  # supersampling for smooth edges


def rings(draw, cx, cy, radius, stroke, gap):
    for x, color in ((cx - gap / 2, WHITE), (cx + gap / 2, GOLD)):
        r = radius + stroke / 2
        draw.ellipse([x - r, cy - r, x + r, cy + r], outline=color, width=int(stroke))


def icon(size, round_shape):
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if round_shape:
        d.ellipse([0, 0, big - 1, big - 1], fill=BG)
    else:
        d.rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.22), fill=BG)
    # Same geometry as the 108dp adaptive icon, scaled to the full legacy icon.
    scale = big / 72.0
    rings(d, big / 2, big / 2, 15 * scale, 7 * scale, 20 * scale)
    return img.resize((size, size), Image.LANCZOS)


def fit_font(draw, text, size, max_width):
    """Largest bold font (up to `size`) that keeps `text` within `max_width`."""
    for path in ("DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        try:
            while size > 8:
                font = ImageFont.truetype(path, size)
                if draw.textlength(text, font=font) <= max_width:
                    return font
                size = int(size * 0.92)
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def banner(width, height, text):
    big_w, big_h = width * SS, height * SS
    img = Image.new("RGBA", (big_w, big_h), BG)
    d = ImageDraw.Draw(img)
    unit = big_h / 108.0
    rings(d, big_h * 0.55, big_h / 2, 15 * unit, 7 * unit, 20 * unit)
    start = big_h * 1.0
    available = big_w - start - big_h * 0.12
    font = fit_font(d, text, int(big_h * 0.2), available)
    d.text((start, big_h / 2), text, fill=WHITE, font=font, anchor="lm")
    return img.resize((width, height), Image.LANCZOS)


def main():
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, size in sizes.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        icon(size, False).save(os.path.join(folder, "lemuroid_launcher.png"))
        icon(size, True).save(os.path.join(folder, "lemuroid_launcher_round.png"))
    name = os.environ.get("APP_NAME", "RetroLink")
    banner(320, 180, name).convert("RGB").save(os.path.join(RES, "mipmap-xhdpi", "ic_banner.png"))
    os.makedirs(STORE, exist_ok=True)
    icon(512, False).save(os.path.join(STORE, "icon-512.png"))
    banner(1024, 500, name).convert("RGB").save(os.path.join(STORE, "feature-graphic-1024x500.png"))


if __name__ == "__main__":
    main()
