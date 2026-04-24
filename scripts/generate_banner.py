#!/usr/bin/env python3
"""Generate branded banner for README: pistons_0.png icon (gold tinted) + TracQi + MxT, transparent bg."""

from PIL import Image, ImageDraw, ImageFont
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "documentation" / "images" / "banner.png"
FONT_PATH = str(ROOT / "src" / "main" / "resources" / "fonts" / "Orbitron-Bold.ttf")
ICON_PATH = ROOT / "src" / "main" / "resources" / "pistons_0.png"

GOLD = (249, 185, 37, 255)        # #F9B925
GOLD_DIM = (249, 185, 37, 180)    # dimmed for subtitle
TRANSPARENT = (0, 0, 0, 0)

# Canvas
W, H = 800, 620
img = Image.new("RGBA", (W, H), TRANSPARENT)
draw = ImageDraw.Draw(img)

# ── Piston Icon (load, tint gold, center at top) ────────────────────────
icon = Image.open(ICON_PATH).convert("RGBA")

# Scale icon to desired height
ICON_H = 280
scale = ICON_H / icon.height
icon = icon.resize((int(icon.width * scale), ICON_H), Image.LANCZOS)

# Tint white pixels to gold: replace RGB with gold, preserve original alpha
arr = np.array(icon)
alpha = arr[:, :, 3]  # original alpha channel
# Set RGB to gold color, weighted by the original luminance
lum = arr[:, :, :3].max(axis=2).astype(float) / 255.0
arr[:, :, 0] = (lum * GOLD[0]).astype(np.uint8)
arr[:, :, 1] = (lum * GOLD[1]).astype(np.uint8)
arr[:, :, 2] = (lum * GOLD[2]).astype(np.uint8)
arr[:, :, 3] = alpha
icon = Image.fromarray(arr)

# Center icon horizontally, place at top
icon_x = (W - icon.width) // 2
icon_y = 20
img.paste(icon, (icon_x, icon_y), icon)

# ── Text ─────────────────────────────────────────────────────────────────
draw = ImageDraw.Draw(img)  # refresh after paste
font_tracqi = ImageFont.truetype(FONT_PATH, 72)
font_me7 = ImageFont.truetype(FONT_PATH, 44)

text_y = icon_y + ICON_H + 20

# "TracQi"
bbox = draw.textbbox((0, 0), "TracQi", font=font_tracqi)
tw = bbox[2] - bbox[0]
draw.text(((W - tw) // 2, text_y), "TracQi", font=font_tracqi, fill=GOLD)

# "MxT"
text_y2 = text_y + (bbox[3] - bbox[1]) + 10
bbox2 = draw.textbbox((0, 0), "MxT", font=font_me7)
tw2 = bbox2[2] - bbox2[0]
draw.text(((W - tw2) // 2, text_y2), "MxT", font=font_me7, fill=GOLD_DIM)

# ── Crop to content ──────────────────────────────────────────────────────
bbox_all = img.getbbox()
if bbox_all:
    pad = 20
    crop = (
        max(0, bbox_all[0] - pad),
        max(0, bbox_all[1] - pad),
        min(W, bbox_all[2] + pad),
        min(H, bbox_all[3] + pad),
    )
    img = img.crop(crop)

img.save(OUT, "PNG")
print(f"✓ banner.png ({img.size[0]}x{img.size[1]}) saved to {OUT}")
