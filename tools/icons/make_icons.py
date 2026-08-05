"""
Generates the transport icon sheet for Jukeblock.

Drawn as smooth shapes at 64x64 per glyph, not 16x16 pixel art. The panel is a
modern surface with photographic album art rather than a vanilla-styled GUI, and at
GUI scale 4 a 16px glyph is upscaled 4x — every diagonal turns into a staircase.
64px is 1:1 at scale 4 and downsamples cleanly below that.

Each glyph is white with a soft dark halo baked into the texture. The panel tints
the sheet when it draws, and multiplying leaves white as the tint colour and black
as black — so the halo survives tinting and every icon keeps its contrast against
whatever the world is doing behind a translucent panel. Doing that at runtime with
an offset blit produced a visible dark ghost instead.

    python tools/icons/make_icons.py

Writes src/main/resources/assets/jukeblock/textures/gui/icons.png plus a preview.
"""

import os

from PIL import Image, ImageDraw, ImageFilter

SIZE = 64          # per glyph, in the output sheet
SS = 8             # supersample factor while drawing
S = SIZE * SS

# Names in sheet order — must match PlayerScreen.Glyph.
NAMES = ["previous", "play", "pause", "next", "shuffle", "repeat", "repeat_one"]

HALO_SPREAD = 2.0   # how far the dark halo reaches, in output pixels
HALO_ALPHA = 150    # 0-255


def px(v):
    """64-space coordinate -> supersampled canvas coordinate."""
    return v * SS


def triangle(d, cx, cy, half_h, width, flip=False):
    """Play-style triangle, apex right (or left when flipped)."""
    tip = cx - width if flip else cx + width
    d.polygon(
        [(px(cx), px(cy - half_h)), (px(cx), px(cy + half_h)), (px(tip), px(cy))],
        fill=255,
    )


def bar(d, x0, y0, x1, y1, r=3):
    d.rounded_rectangle([px(x0), px(y0), px(x1), px(y1)], radius=px(r), fill=255)


def arrow_head(d, x, y, size, direction):
    """Solid triangular head. `direction` is 'right' or 'left'."""
    s = 1 if direction == "right" else -1
    d.polygon(
        [
            (px(x), px(y - size)),
            (px(x), px(y + size)),
            (px(x + s * size * 1.4), px(y)),
        ],
        fill=255,
    )


def draw_glyph(name):
    img = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(img)
    c = 32

    if name == "play":
        triangle(d, 24, c, 17, 25)

    elif name == "pause":
        bar(d, 21, 15, 28, 49)
        bar(d, 36, 15, 43, 49)

    elif name == "next":
        triangle(d, 17, c, 16, 22)
        bar(d, 42, 16, 48, 48)

    elif name == "previous":
        triangle(d, 47, c, 16, 22, flip=True)
        bar(d, 16, 16, 22, 48)

    elif name == "shuffle":
        # Two paths that swap height, with solid heads on the right.
        w = 5
        d.line([px(8), px(20), px(20), px(20)], fill=255, width=px(w))
        d.line([px(20), px(20), px(42), px(44)], fill=255, width=px(w))
        d.line([px(42), px(44), px(48), px(44)], fill=255, width=px(w))
        arrow_head(d, 48, 44, 8, "right")

        d.line([px(8), px(44), px(20), px(44)], fill=255, width=px(w))
        d.line([px(20), px(44), px(42), px(20)], fill=255, width=px(w))
        d.line([px(42), px(20), px(48), px(20)], fill=255, width=px(w))
        arrow_head(d, 48, 20, 8, "right")

    elif name in ("repeat", "repeat_one"):
        # Rounded loop with a gap at each end, closed by arrowheads.
        d.rounded_rectangle(
            [px(12), px(16), px(52), px(48)],
            radius=px(10),
            outline=255,
            width=px(5),
        )
        # Punch the gaps out, then place the heads there.
        d.rectangle([px(40), px(10), px(56), px(22)], fill=0)
        d.rectangle([px(8), px(42), px(24), px(54)], fill=0)
        arrow_head(d, 44, 16, 8, "right")
        arrow_head(d, 20, 48, 8, "left")

        if name == "repeat_one":
            # A "1" in the middle.
            bar(d, 30, 24, 35, 40, r=1)
            d.polygon(
                [(px(30), px(24)), (px(30), px(29)), (px(25), px(29))],
                fill=255,
            )

    return img.resize((SIZE, SIZE), Image.LANCZOS)


def main():
    sheet = Image.new("RGBA", (SIZE * len(NAMES), SIZE), (0, 0, 0, 0))

    for i, name in enumerate(NAMES):
        alpha = draw_glyph(name)

        # Dark halo: spread the silhouette outward, soften it, and lay it under the
        # glyph. Black survives the panel's colour multiply, so this is what keeps
        # icons legible on a translucent panel.
        halo = alpha.filter(ImageFilter.MaxFilter(int(HALO_SPREAD) * 2 + 1))
        halo = halo.filter(ImageFilter.GaussianBlur(HALO_SPREAD))
        halo = halo.point(lambda v: min(HALO_ALPHA, int(v * HALO_ALPHA / 255)))

        glyph = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        glyph.putalpha(halo)
        white = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 0))
        white.putalpha(alpha)
        glyph = Image.alpha_composite(glyph, white)

        sheet.paste(glyph, (i * SIZE, 0), glyph)

    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.abspath(os.path.join(here, "..", ".."))
    out = os.path.join(repo, "src", "main", "resources", "assets", "jukeblock",
                       "textures", "gui", "icons.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    sheet.save(out)
    print(f"wrote {out}  ({sheet.width}x{sheet.height}, {len(NAMES)} icons, "
          f"{os.path.getsize(out) / 1024:.1f} KB)")
    print("order:", ", ".join(NAMES))

    # Preview on a mid grey so both the glyph and its halo are visible.
    bg = Image.new("RGBA", sheet.size, (0x2A, 0x2A, 0x33, 255))
    Image.alpha_composite(bg, sheet).save(os.path.join(here, "preview.png"))
    print("preview written next to this script")


if __name__ == "__main__":
    main()
