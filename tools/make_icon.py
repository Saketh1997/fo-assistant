"""Renders the launcher icon from the same geometry as BirdView (Fo: white ball, black eyes, smile, pink cheeks).
Writes adaptive-icon layers into res/: a foreground per density, a monochrome layer for themed icons,
and the background colour. Needs Pillow. Run from the project root: python3 tools/make_icon.py"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
SS = 4  # supersampling


def face(s, mono=False):
    """Fo on a transparent s x s square, drawn like BirdView.onDraw (joy 0, eyes open, looking ahead)."""
    S = s * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    cx = cy = S / 2
    rad = 0.44 * S
    if mono:  # themed icons: one solid shape, the face cut out of it
        d = ImageDraw.Draw(img)
        d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(255, 255, 255, 255))
        cut = (0, 0, 0, 0)
    else:
        lx, ly, lr = 0.38 * S, 0.32 * S, 0.62 * S
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=255)
        shade = Image.new("RGBA", (S, S))
        px = shade.load()
        for y in range(S):
            for x in range(S):
                t = min(1.0, math.hypot(x - lx, y - ly) / lr)
                if t < 0.55:  # 0xFFFFFF -> 0xF7F5F3 -> 0xDCD8D4, as the RadialGradient
                    k = t / 0.55
                    c = (255 - 8 * k, 255 - 10 * k, 255 - 12 * k)
                else:
                    k = (t - 0.55) / 0.45
                    c = (247 - 27 * k, 245 - 29 * k, 243 - 31 * k)
                px[x, y] = (int(c[0]), int(c[1]), int(c[2]), 255)
        img.paste(shade, (0, 0), mask)
        for side in (-1, 1):
            bx, by, br = cx + side * 0.28 * S, 0.64 * S, 0.09 * S
            b = Image.new("RGBA", (S, S), (0, 0, 0, 0))
            ImageDraw.Draw(b).ellipse([bx - br, by - br, bx + br, by + br], fill=(255, 138, 138, 110))
            img.alpha_composite(b.filter(ImageFilter.GaussianBlur(br / 2.5)))
        cut = (10, 10, 10, 255)
    for side in (-1, 1):
        ex, ey = cx + side * 0.165 * S, 0.44 * S
        e = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        d = ImageDraw.Draw(e)
        d.ellipse([ex - 0.08 * S, ey - 0.125 * S, ex + 0.08 * S, ey + 0.125 * S], fill=(10, 10, 10, 255))
        if not mono:
            d.ellipse([ex - 0.048 * S, ey - 0.095 * S, ex - 0.010 * S, ey - 0.035 * S], fill=(255, 255, 255, 204))
        e = e.rotate(side * 8, center=(ex, ey), resample=Image.BICUBIC)  # tilted in at the top, as in BirdView
        if mono:
            img.paste(cut, (0, 0), e)
        else:
            img.alpha_composite(e)
    smile = Image.new("L", (S, S), 0)
    ImageDraw.Draw(smile).arc([cx - 0.10 * S, 0.53 * S, cx + 0.10 * S, 0.67 * S], 25, 155, fill=255, width=int(0.04 * S))
    img.paste(cut, (0, 0), smile)
    return img.resize((s, s), Image.LANCZOS)


def layer(px, mono=False):
    """108dp adaptive layer with the face in the middle 72dp, inside the 66dp safe circle."""
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    f = round(px * 72 / 108)
    out.alpha_composite(face(f, mono), ((px - f) // 2, (px - f) // 2))
    return out


for name, k in DENSITIES.items():
    d = f"res/mipmap-{name}"
    os.makedirs(d, exist_ok=True)
    layer(round(108 * k)).save(f"{d}/ic_fo_foreground.png", optimize=True)
    layer(round(108 * k), mono=True).save(f"{d}/ic_fo_monochrome.png", optimize=True)
os.makedirs("res/mipmap-anydpi-v26", exist_ok=True)
with open("res/mipmap-anydpi-v26/ic_launcher.xml", "w") as f:
    f.write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_fo_background" />
    <foreground android:drawable="@mipmap/ic_fo_foreground" />
    <monochrome android:drawable="@mipmap/ic_fo_monochrome" />
</adaptive-icon>
''')
with open("res/values/ic_launcher_background.xml", "w") as f:
    f.write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Wajo's near-black, so the white Fo pops like on their card -->
    <color name="ic_fo_background">#0A0A0A</color>
</resources>
''')
# a flat preview for checking by eye
prev = Image.new("RGBA", (432, 432), (10, 10, 10, 255))
prev.alpha_composite(layer(432))
m = Image.new("L", (432, 432), 0)
ImageDraw.Draw(m).ellipse([0, 0, 431, 431], fill=255)
round_prev = Image.new("RGBA", (432, 432), (240, 240, 240, 255))
round_prev.paste(prev, (0, 0), m)
round_prev.save("build/icon_preview.png")
print("icon written")
