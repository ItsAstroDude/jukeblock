"""
Generates the mod icon: a music disc, drawn as pixel art to match the in-game glyphs.

Authored at 32x32 and upscaled with nearest-neighbour, so every edge lands on a pixel
boundary the way Minecraft's own art does. A smooth vector-style icon would look foreign
next to the rest of the mod list.

    python tools/icons/make_mod_icon.py

Writes src/main/resources/assets/jukeblock/icon.png (256x256). PLAN.md phase 4 requires
it under 100 KB — CosmicNotify's was 5.1 MB.
"""

import os
import zlib
import struct

SRC = 32
SCALE = 8

BG = (0x21, 0x21, 0x29, 0xFF)          # charcoal, matches the panel
BG_EDGE = (0x18, 0x18, 0x1F, 0xFF)     # corner shading
DISC = (0x14, 0x14, 0x19, 0xFF)        # vinyl
DISC_EDGE = (0x2E, 0x2E, 0x38, 0xFF)   # rim highlight
ACCENT = (0x53, 0xE0, 0x76, 0xFF)      # Liquid Lens green
ACCENT_DIM = (0x33, 0x8C, 0x4C, 0xFF)
HOLE = (0x21, 0x21, 0x29, 0xFF)


def write_png(path, width, height, rgba):
    raw = b"".join(
        b"\x00" + bytes(rgba[y * width * 4:(y + 1) * width * 4]) for y in range(height)
    )

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def main():
    px = [[BG for _ in range(SRC)] for _ in range(SRC)]
    c = (SRC - 1) / 2.0

    for y in range(SRC):
        for x in range(SRC):
            dx, dy = x - c, y - c
            d = (dx * dx + dy * dy) ** 0.5

            # Rounded corners, so the icon doesn't read as a hard square in the list.
            corner = max(abs(dx), abs(dy))
            if corner > 14.6 and d > 18.5:
                px[y][x] = BG_EDGE

            if d <= 14.2:
                px[y][x] = DISC_EDGE
            if d <= 13.2:
                px[y][x] = DISC
            # Groove: a thin accent ring, the bit that reads as "music disc" at 32px.
            if 6.6 <= d <= 8.4:
                px[y][x] = ACCENT
            if 8.4 < d <= 9.2:
                px[y][x] = ACCENT_DIM
            # Spindle hole.
            if d <= 2.6:
                px[y][x] = HOLE

    # Upscale.
    w = h = SRC * SCALE
    buf = bytearray()
    for y in range(h):
        row = px[y // SCALE]
        for x in range(w):
            buf += bytes(row[x // SCALE])

    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.abspath(os.path.join(here, "..", ".."))
    out = os.path.join(repo, "src", "main", "resources", "assets", "jukeblock", "icon.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    write_png(out, w, h, buf)
    size = os.path.getsize(out)
    print(f"wrote {out}  ({w}x{h}, {size / 1024:.1f} KB)")
    assert size < 100 * 1024, "icon must stay under 100 KB (PLAN.md phase 4)"


if __name__ == "__main__":
    main()
