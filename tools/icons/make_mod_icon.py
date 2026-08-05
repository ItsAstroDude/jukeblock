"""
Generates the mod icon: the actual vanilla jukebox block, rendered isometrically,
with notes rising off it.

The textures are read straight out of the Minecraft client jar rather than redrawn,
so the block is the real thing rather than an approximation of it. The projection is
the same 2:1 isometric Minecraft uses for block item icons, sampled nearest-neighbour
so the texture's pixels stay square-edged instead of blurring.

    python tools/icons/make_mod_icon.py [path/to/minecraft-client.jar]

Writes src/main/resources/assets/jukeblock/icon.png (256x256) plus a preview next to
this script. PLAN.md phase 4 requires the icon under 100 KB.

Note the generated icon contains Mojang texture data. That's normal for a Minecraft
mod icon and consistent with how the wider ecosystem works, but it is worth being a
deliberate choice rather than an accident — the previous hand-drawn version is in git
history if a fully original icon is ever preferred.
"""

import glob
import os
import struct
import sys
import zipfile
import zlib

OUT_SIZE = 256

SIDE = "assets/minecraft/textures/block/jukebox_side.png"
TOP = "assets/minecraft/textures/block/jukebox_top.png"

BG = (0x21, 0x21, 0x29, 0xFF)
BG_EDGE = (0x18, 0x18, 0x1F, 0xFF)
NOTE = (0x53, 0xE0, 0x76, 0xFF)
NOTE_DIM = (0x3E, 0xA8, 0x59, 0xFF)

# Minecraft shades block faces by orientation; matching it is what makes the render
# read as a block rather than as three flat stickers.
SHADE_TOP = 1.00
SHADE_LEFT = 0.80
SHADE_RIGHT = 0.62


def find_client_jar():
    if len(sys.argv) > 1:
        return sys.argv[1]
    home = os.path.expanduser("~")
    for pattern in (
        os.path.join(home, ".gradle/caches/fabric-loom/*/minecraft-client.jar"),
        os.path.join(home, ".gradle/caches/fabric-loom/*/minecraft-merged.jar"),
    ):
        hits = sorted(glob.glob(pattern))
        if hits:
            return hits[-1]
    raise SystemExit("Could not find minecraft-client.jar — pass the path as an argument.")


def load_texture(jar, name):
    """Returns (width, height, [(r,g,b,a), ...]) via PIL, which handles palettes."""
    from PIL import Image
    import io

    with zipfile.ZipFile(jar) as z:
        img = Image.open(io.BytesIO(z.read(name))).convert("RGBA")
    return img.width, img.height, img.tobytes()


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


class Face:
    """A texture mapped onto a parallelogram in output space.

    `origin` is where source pixel (0,0) lands; `u` and `v` are the full-width and
    full-height edge vectors. Rendering inverse-maps each output pixel back through
    this, so no interpolation ever happens.
    """

    def __init__(self, tex, origin, u, v, shade):
        self.w, self.h, self.px = tex
        self.o = origin
        self.u = u
        self.v = v
        self.shade = shade
        det = u[0] * v[1] - v[0] * u[1]
        self.inv = (v[1] / det, -v[0] / det, -u[1] / det, u[0] / det)

    def sample(self, x, y):
        dx, dy = x - self.o[0], y - self.o[1]
        a = self.inv[0] * dx + self.inv[1] * dy
        b = self.inv[2] * dx + self.inv[3] * dy
        if not (0.0 <= a < 1.0 and 0.0 <= b < 1.0):
            return None
        sx = min(self.w - 1, int(a * self.w))
        sy = min(self.h - 1, int(b * self.h))
        i = (sy * self.w + sx) * 4
        r, g, bl, al = self.px[i], self.px[i + 1], self.px[i + 2], self.px[i + 3]
        if al == 0:
            return None
        s = self.shade
        return (int(r * s), int(g * s), int(bl * s), 255)


def main():
    jar = find_client_jar()
    print(f"reading vanilla textures from {jar}")
    side = load_texture(jar, SIDE)
    top = load_texture(jar, TOP)

    # 2:1 isometric cube. Sits left of centre so the notes have room upper-right.
    cx, y0 = 114, 48
    w, h, d = 78, 39, 82

    A = (cx, y0)                    # top apex
    B = (cx + w, y0 + h)            # right corner
    C = (cx, y0 + 2 * h)            # front corner
    D = (cx - w, y0 + h)            # left corner

    faces = [
        # Left and right walls hang straight down from the top face's edges.
        Face(side, D, (C[0] - D[0], C[1] - D[1]), (0, d), SHADE_LEFT),
        Face(side, C, (B[0] - C[0], B[1] - C[1]), (0, d), SHADE_RIGHT),
        Face(top, A, (B[0] - A[0], B[1] - A[1]), (D[0] - A[0], D[1] - A[1]), SHADE_TOP),
    ]

    S = OUT_SIZE
    c = (S - 1) / 2.0
    px = []
    for y in range(S):
        row = []
        for x in range(S):
            dx, dy = x - c, y - c
            if max(abs(dx), abs(dy)) > S * 0.457 and (dx * dx + dy * dy) ** 0.5 > S * 0.578:
                row.append(BG_EDGE)
            else:
                row.append(BG)
        px.append(row)

    # Painter's order: walls first, top face last so it wins along the shared edges.
    for face in faces:
        for y in range(S):
            for x in range(S):
                got = face.sample(x + 0.5, y + 0.5)
                if got:
                    px[y][x] = got

    def rect(x0, y0_, x1, y1, color):
        for yy in range(y0_, y1 + 1):
            for xx in range(x0, x1 + 1):
                if 0 <= xx < S and 0 <= yy < S:
                    px[yy][xx] = color

    def eighth_note(ox, oy, u, color):
        """Head bottom-left, stem up the right, flag off the top. `u` scales it."""
        rect(ox + 4 * u, oy, ox + 5 * u - 1, oy + 8 * u - 1, color)          # stem
        rect(ox + 5 * u, oy, ox + 7 * u - 1, oy + 2 * u - 1, color)          # flag
        rect(ox, oy + 6 * u, ox + 4 * u - 1, oy + 9 * u - 1, color)          # head
        rect(ox - u, oy + 7 * u, ox - 1, oy + 9 * u - 1, color)

    eighth_note(186, 26, 8, NOTE)
    eighth_note(126, 8, 6, NOTE_DIM)

    buf = bytearray()
    for row in px:
        for p in row:
            buf += bytes(p)

    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.abspath(os.path.join(here, "..", ".."))
    out = os.path.join(repo, "src", "main", "resources", "assets", "jukeblock", "icon.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    write_png(out, S, S, buf)
    size = os.path.getsize(out)
    print(f"wrote {out}  ({S}x{S}, {size / 1024:.1f} KB)")
    assert size < 100 * 1024, "icon must stay under 100 KB (PLAN.md phase 4)"

    write_png(os.path.join(here, "icon_preview.png"), S, S, buf)
    print("preview written next to this script")


if __name__ == "__main__":
    main()
