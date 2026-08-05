"""
Generates the mod icon: Minecraft's jukebox block with notes rising off it.

On-brand for the name, and it reads as "this is a Minecraft mod about music" in the
mod list without anyone having to squint. Authored at 32x32 and upscaled with
nearest-neighbour so every edge lands on a pixel boundary, the way Minecraft's own
art does.

    python tools/icons/make_mod_icon.py

Writes src/main/resources/assets/jukeblock/icon.png (256x256) plus a magnified
preview next to this script. PLAN.md phase 4 requires the icon under 100 KB —
CosmicNotify's was 5.1 MB.
"""

import os
import zlib
import struct

SRC = 32
SCALE = 8

T = None                              # transparent
BG = (0x21, 0x21, 0x29, 0xFF)         # charcoal, matches the panel
BG_EDGE = (0x18, 0x18, 0x1F, 0xFF)

WOOD = (0x9A, 0x6E, 0x45, 0xFF)       # jukebox side planks
WOOD_DARK = (0x7A, 0x55, 0x34, 0xFF)
WOOD_LINE = (0x5E, 0x40, 0x27, 0xFF)  # plank seams
TOP = (0x4B, 0x35, 0x22, 0xFF)        # dark upper band
TOP_LIT = (0x5E, 0x44, 0x2C, 0xFF)
SLOT = (0x1B, 0x12, 0x0D, 0xFF)       # the disc recess
DISC = (0xC4, 0x3A, 0x3A, 0xFF)       # a record sitting in it
OUTLINE = (0x14, 0x0E, 0x0A, 0xFF)

NOTE = (0x53, 0xE0, 0x76, 0xFF)       # Liquid Lens green
NOTE_DIM = (0x3E, 0xA8, 0x59, 0xFF)


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

    # Rounded corners so it doesn't read as a hard square in the mod list.
    for y in range(SRC):
        for x in range(SRC):
            dx, dy = x - c, y - c
            if max(abs(dx), abs(dy)) > 14.6 and (dx * dx + dy * dy) ** 0.5 > 18.5:
                px[y][x] = BG_EDGE

    def rect(x0, y0, x1, y1, color):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if 0 <= x < SRC and 0 <= y < SRC:
                    px[y][x] = color

    # --- the jukebox block ------------------------------------------------
    # Sits low and left; the notes occupy the upper right.
    bx0, by0, bx1, by1 = 3, 12, 21, 28

    rect(bx0, by0, bx1, by1, WOOD)
    rect(bx0, by0, bx1, by0, OUTLINE)
    rect(bx0, by1, bx1, by1, OUTLINE)
    rect(bx0, by0, bx0, by1, OUTLINE)
    rect(bx1, by0, bx1, by1, OUTLINE)

    # Dark upper band with the disc recess — the part that makes it a jukebox
    # rather than a crate.
    rect(bx0 + 1, by0 + 1, bx1 - 1, by0 + 7, TOP)
    rect(bx0 + 1, by0 + 1, bx1 - 1, by0 + 1, TOP_LIT)
    rect(bx0 + 3, by0 + 3, bx1 - 3, by0 + 6, SLOT)

    # The record loaded in the slot. Deliberately a solid bar: a spindle hole needs
    # a pixel of clearance all round to read as a hole, and at two pixels tall it
    # just splits the disc into two blobs that look like eyes.
    rect(bx0 + 5, by0 + 4, bx1 - 5, by0 + 5, DISC)

    # Speckles along the band, echoing the vanilla jukebox texture.
    for x in range(bx0 + 2, bx1 - 1, 4):
        px[by0 + 2][x] = TOP_LIT

    # Plank seams below the band.
    for y in range(by0 + 9, by1):
        for x in range(bx0 + 1, bx1):
            if (y - by0) % 4 == 0:
                px[y][x] = WOOD_LINE
            elif (x * 7 + y * 3) % 11 == 0:
                px[y][x] = WOOD_DARK

    # --- notes ------------------------------------------------------------
    def eighth_note(ox, oy, head, stem):
        """Head bottom-left, stem up the right, flag off the top."""
        rect(ox + 4, oy, ox + 4, oy + 7, stem)      # stem
        rect(ox + 5, oy, ox + 6, oy + 1, stem)      # flag
        rect(ox + 1, oy + 6, ox + 3, oy + 8, head)  # head
        rect(ox, oy + 7, ox, oy + 8, head)

    eighth_note(22, 3, NOTE, NOTE)
    eighth_note(15, 1, NOTE_DIM, NOTE_DIM)

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

    write_png(os.path.join(here, "icon_preview.png"), w, h, buf)
    print("preview written next to this script")


if __name__ == "__main__":
    main()
