#!/usr/bin/env python3
"""Regenerate Ship Happens brand raster assets (iOS + Android splash wordmark).

The box glyph geometry below is the source of truth. Run from the repo root:

    python3 scripts/generate_brand_assets.py

Requires Pillow. Android launcher icon / splash icon are hand-authored vector
XML (see app-android/src/main/res); this script only produces the PNGs that
platforms cannot draw from vectors: the iOS app icon and the iOS launch tile
and wordmark.
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BLUE = (0x1E, 0x3A, 0x8F, 255)
WHITE = (0xFF, 0xFF, 0xFF, 255)
FONT = os.path.join(ROOT, "design/src/commonMain/composeResources/font/hanken_800.ttf")
SS = 4  # supersampling factor

# Box glyph in a 24-unit coordinate space. The outline polyline starts/ends at
# the bottom vertex (12,21) so the closure seam is hidden by the front edge; the
# prominent top apex (12,3) stays an interior joint and renders cleanly rounded.
OUTLINE = [(12, 21), (21, 16.5), (21, 7.5), (12, 3), (3, 7.5), (3, 16.5)]
SEAM_TOP = [(3, 7.5), (12, 12), (21, 7.5)]
SEAM_FRONT = [(12, 12), (12, 21)]


def p(rel):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


def draw_box(draw, size, color, stroke_frac, pad_frac):
    """Draw the box glyph centered in a size x size box, inset by pad_frac."""
    inner = size * (1 - 2 * pad_frac)
    off = size * pad_frac
    sc = inner / 24.0
    w = max(1, int(round(size * stroke_frac)))

    def P(pts):
        return [(off + x * sc, off + y * sc) for (x, y) in pts]

    # Close the loop AND overshoot by one segment so the closure vertex is a
    # rounded interior joint and the initial line cap is overdrawn (no notch).
    loop = P(OUTLINE + OUTLINE[:2])
    draw.line(loop, fill=color, width=w, joint="curve")
    draw.line(P(SEAM_TOP), fill=color, width=w, joint="curve")
    draw.line(P(SEAM_FRONT), fill=color, width=w, joint="curve")


def app_icon(px=1024):
    img = Image.new("RGBA", (px * SS, px * SS), BLUE)
    draw_box(ImageDraw.Draw(img), px * SS, WHITE, stroke_frac=0.052, pad_frac=0.30)
    out = p("app-ios/ShipHappens/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png")
    img.resize((px, px), Image.LANCZOS).save(out)


def tile(base=96):
    for scale, suffix in ((1, ""), (2, "@2x"), (3, "@3x")):
        px = base * scale
        img = Image.new("RGBA", (px * SS, px * SS), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        radius = int(px * SS * 0.25)
        d.rounded_rectangle([0, 0, px * SS - 1, px * SS - 1], radius=radius, fill=BLUE)
        draw_box(d, px * SS, WHITE, stroke_frac=0.055, pad_frac=0.28)
        out = p(f"app-ios/ShipHappens/Assets.xcassets/BrandTile.imageset/BrandTile{suffix}.png")
        img.resize((px, px), Image.LANCZOS).save(out)


def _render_wordmark(height, color):
    font = ImageFont.truetype(FONT, int(height * 0.82))
    probe = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    bbox = probe.textbbox((0, 0), "Ship Happens", font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    pad = int(height * 0.2)
    img = Image.new("RGBA", (tw + 2 * pad, th + 2 * pad), (0, 0, 0, 0))
    ImageDraw.Draw(img).text((pad - bbox[0], pad - bbox[1]), "Ship Happens", font=font, fill=color)
    return img


def ios_wordmark(base_h=44):
    for scale, suffix in ((1, ""), (2, "@2x"), (3, "@3x")):
        img = _render_wordmark(base_h * scale, BLUE)
        out = p(f"app-ios/ShipHappens/Assets.xcassets/BrandWordmark.imageset/BrandWordmark{suffix}.png")
        img.save(out)


if __name__ == "__main__":
    app_icon()
    tile()
    ios_wordmark()
    print("brand assets generated")
