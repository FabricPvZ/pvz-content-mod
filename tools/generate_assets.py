#!/usr/bin/env python3
"""generate_assets.py - builds the mod's PNG art from the player's own Plants vs. Zombies sprites.

The mod ships three images, all of them recoloured retail art (no hand-drawn placeholders):

  packet_sniper_pea.png   72x70 seed-packet icon  <- reanim/PeaShooter_Head.png, hue-rotated
  packet_sun_orchid.png   72x70 seed-packet icon  <- reanim/SunFlower_head.png, hue-rotated + scaled
  zombie_torso.png        53x63 reanimation image <- reanim/Zombie_body.png, hue-rotated

The two packet icons are composited into a 72x70 canvas because that is the cel size of the retail
`images/packet_plants.png` sheet, which is drawn through the very same call
(`PvzpCommon::PvzpDrawImageCelScaledF`) as a mod plant's fixed packet art. The torso keeps the exact
dimensions of the retail image because it overrides the `Zombie_body` track of a reanimation.

Sources are extracted from the retail `main.pak` with the workspace tool
`tools/pvz_pak.py`, i.e. this script needs a real PvZ installation (the same one the game needs to
run). The generated PNGs are committed, so nobody has to run this script to build or use the mod.

Usage:
    python3 tools/generate_assets.py                    # auto-detect the game, write the PNGs
    python3 tools/generate_assets.py --game-dir <dir>   # point at the install (the dir with main.pak)
    python3 tools/generate_assets.py --check            # do not write, only report what would change
"""

import argparse
import colorsys
import hashlib
import os
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
TEXTURE_DIR = PROJECT / "src" / "main" / "resources" / "assets" / "pvz-content-mod" / "textures"
DEFAULT_PAK_TOOL = PROJECT.parent / "tools" / "pvz_pak.py"

# The retail packet sheet's cel size: images/packet_plants.png is 650x70 with 9 cels.
PACKET_CANVAS = (72, 70)

GAME_DIR_CANDIDATES = [
    Path.home() / "Library/Application Support/Steam/steamapps/common/Plants Vs Zombies/Plants vs. Zombies.app/Contents/Resources",
    Path.home() / "Library/Application Support/Steam/steamapps/common/Plants vs. Zombies/Plants vs. Zombies.app/Contents/Resources",
    Path("C:/Program Files (x86)/Steam/steamapps/common/Plants vs. Zombies"),
    Path.home() / ".steam/steam/steamapps/common/Plants vs. Zombies",
]


# --------------------------------------------------------------------------- PNG codec


def read_png(data):
    """Decodes an 8-bit PNG (colour types 0/2/3/6) into (width, height, [(r, g, b, a), ...])."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")

    width = height = bit_depth = colour_type = None
    palette = b""
    transparency = b""
    idat = b""
    pos = 8
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, bit_depth, colour_type, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            if interlace:
                raise ValueError("interlaced PNG is not supported")
        elif kind == b"PLTE":
            palette = chunk
        elif kind == b"tRNS":
            transparency = chunk
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break

    if bit_depth != 8 or colour_type not in (0, 2, 3, 6):
        raise ValueError(f"unsupported PNG: bit depth {bit_depth}, colour type {colour_type}")

    channels = {0: 1, 2: 3, 3: 1, 6: 4}[colour_type]
    raw = zlib.decompress(idat)
    stride = width * channels
    rows = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        line = bytearray(raw[offset + 1:offset + 1 + stride])
        offset += 1 + stride
        for i in range(stride):
            a = line[i - channels] if i >= channels else 0
            b = previous[i]
            c = previous[i - channels] if i >= channels else 0
            if filter_type == 1:
                line[i] = (line[i] + a) & 0xFF
            elif filter_type == 2:
                line[i] = (line[i] + b) & 0xFF
            elif filter_type == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 0xFF
            elif filter_type == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                prediction = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + prediction) & 0xFF
            elif filter_type != 0:
                raise ValueError(f"unknown PNG filter {filter_type}")
        rows.append(bytes(line))
        previous = line

    pixels = []
    for row in rows:
        for x in range(width):
            if colour_type == 6:
                r, g, b, a = row[4 * x:4 * x + 4]
            elif colour_type == 2:
                r, g, b = row[3 * x:3 * x + 3]
                a = 255
            elif colour_type == 0:
                r = g = b = row[x]
                a = 255
            else:
                index = row[x]
                r, g, b = palette[3 * index:3 * index + 3]
                a = transparency[index] if index < len(transparency) else 255
            pixels.append((r, g, b, a))
    return width, height, pixels


def write_png(path, width, height, pixels):
    """Writes an 8-bit RGBA PNG with filter type 0 on every row."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(pixels[y * width + x])

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    data = b"\x89PNG\r\n\x1a\n"
    data += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    data += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


# --------------------------------------------------------------------------- image ops


def recolour(pixel, hue_shift, saturation=1.0, value=1.0):
    """Rotates the hue of one RGBA pixel, keeping alpha (fully transparent pixels stay untouched)."""
    r, g, b, a = pixel
    if a == 0:
        return (r, g, b, a)
    h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
    h = (h + hue_shift / 360.0) % 1.0
    s = min(1.0, s * saturation)
    v = min(1.0, v * value)
    nr, ng, nb = colorsys.hsv_to_rgb(h, s, v)
    return (round(nr * 255), round(ng * 255), round(nb * 255), a)


def scale_nearest(width, height, pixels, factor):
    """Nearest-neighbour upscale, so recoloured art keeps the retail pixel look."""
    new_width = max(1, round(width * factor))
    new_height = max(1, round(height * factor))
    out = []
    for y in range(new_height):
        for x in range(new_width):
            out.append(pixels[min(height - 1, int(y / factor)) * width + min(width - 1, int(x / factor))])
    return new_width, new_height, out


def composite(canvas_size, width, height, pixels, offset):
    """Places an image into a transparent canvas at (offset_x, offset_y)."""
    canvas_width, canvas_height = canvas_size
    canvas = [(0, 0, 0, 0)] * (canvas_width * canvas_height)
    offset_x, offset_y = offset
    for y in range(height):
        ty = y + offset_y
        if not 0 <= ty < canvas_height:
            continue
        for x in range(width):
            tx = x + offset_x
            if not 0 <= tx < canvas_width:
                continue
            canvas[ty * canvas_width + tx] = pixels[y * width + x]
    return canvas


def trim_bounds(width, height, pixels):
    """Bounding box of the non-transparent pixels, as (left, top, right, bottom)."""
    left, top, right, bottom = width, height, -1, -1
    for y in range(height):
        for x in range(width):
            if pixels[y * width + x][3] != 0:
                left, top = min(left, x), min(top, y)
                right, bottom = max(right, x), max(bottom, y)
    if right < 0:
        return 0, 0, width - 1, height - 1
    return left, top, right, bottom


def centred_canvas(width, height, pixels, hue_shift, scale=1.0, saturation=1.0, value=1.0):
    """Recolours, optionally scales and centres an image in the retail packet-canvas size."""
    pixels = [recolour(p, hue_shift, saturation, value) for p in pixels]
    if scale != 1.0:
        width, height, pixels = scale_nearest(width, height, pixels, scale)

    left, top, right, bottom = trim_bounds(width, height, pixels)
    art_width, art_height = right - left + 1, bottom - top + 1
    if art_width > PACKET_CANVAS[0] or art_height > PACKET_CANVAS[1]:
        shrink = min(PACKET_CANVAS[0] / art_width, PACKET_CANVAS[1] / art_height)
        width, height, pixels = scale_nearest(width, height, pixels, shrink)
        left, top, right, bottom = trim_bounds(width, height, pixels)
        art_width, art_height = right - left + 1, bottom - top + 1

    offset = ((PACKET_CANVAS[0] - art_width) // 2, (PACKET_CANVAS[1] - art_height) // 2)
    return PACKET_CANVAS[0], PACKET_CANVAS[1], composite(PACKET_CANVAS, width, height, pixels,
                                                         (offset[0] - left, offset[1] - top))


# --------------------------------------------------------------------------- extraction


def find_game_dir(explicit):
    if explicit:
        candidates = [Path(explicit)]
    else:
        candidates = GAME_DIR_CANDIDATES
    for directory in candidates:
        if directory and directory.is_dir() and any(
                (directory / name).is_file() for name in ("main.pak", "MAIN.PAK")):
            return directory
    return None


def extract(pak_tool, game_dir, member, out_dir):
    pak = game_dir / ("main.pak" if (game_dir / "main.pak").is_file() else "MAIN.PAK")
    subprocess.run([sys.executable, str(pak_tool), "extract", str(pak), member, str(out_dir)],
                   check=True, capture_output=True)
    extracted = out_dir / member
    if not extracted.is_file():
        raise SystemExit(f"the pak tool did not extract {member}")
    return read_png(extracted.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--game-dir", help="PvZ install (the directory holding main.pak)")
    parser.add_argument("--pak-tool", default=str(DEFAULT_PAK_TOOL), help="path to tools/pvz_pak.py")
    parser.add_argument("--check", action="store_true", help="report without writing the PNGs")
    arguments = parser.parse_args()

    game_dir = find_game_dir(arguments.game_dir)
    if game_dir is None:
        raise SystemExit("Could not find a PvZ installation: pass --game-dir <the directory with main.pak>")
    pak_tool = Path(arguments.pak_tool)
    if not pak_tool.is_file():
        raise SystemExit(f"pak tool not found: {pak_tool}")

    print(f"retail sprites: {game_dir}")
    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)

        pea_head = extract(pak_tool, game_dir, "reanim/PeaShooter_Head.png", tmp_dir)
        peashooter_width, peashooter_height, peashooter_pixels = pea_head
        packet_a = centred_canvas(peashooter_width, peashooter_height, peashooter_pixels,
                                  hue_shift=265.0, saturation=1.15, value=1.0)

        sun_head = extract(pak_tool, game_dir, "reanim/SunFlower_head.png", tmp_dir)
        sunflower_width, sunflower_height, sunflower_pixels = sun_head
        packet_b = centred_canvas(sunflower_width, sunflower_height, sunflower_pixels,
                                  hue_shift=155.0, saturation=1.25, value=0.95, scale=1.35)

        torso = extract(pak_tool, game_dir, "reanim/Zombie_body.png", tmp_dir)
        torso_width, torso_height, torso_pixels = torso
        zombie_torso = (torso_width, torso_height,
                        [recolour(p, hue_shift=150.0, saturation=1.1, value=0.95) for p in torso_pixels])

    outputs = {
        "packet_sniper_pea.png": packet_a,
        "packet_sun_orchid.png": packet_b,
        "zombie_torso.png": zombie_torso,
    }

    changed = False
    for name, (width, height, pixels) in outputs.items():
        path = TEXTURE_DIR / name
        staging = Path(tempfile.mkdtemp()) / name
        write_png(staging, width, height, pixels)
        digest = hashlib.sha256(staging.read_bytes()).hexdigest()
        before = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
        state = "unchanged" if before == digest else ("would change" if arguments.check else "written")
        if before != digest:
            changed = True
            if not arguments.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(staging.read_bytes())
        print(f"{name:26s} {width}x{height} sha256={digest[:16]} {state}")

    if arguments.check and changed:
        raise SystemExit("assets differ from the committed ones: re-run without --check")
    print(f"output: {TEXTURE_DIR}")


if __name__ == "__main__":
    main()
