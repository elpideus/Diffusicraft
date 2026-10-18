#!/usr/bin/env python3
"""Writes the assets and data of Diffusicraft's blocks: textures, block states, models, item models, loot tables and
tags. The files are committed; run this after adding or changing a block and commit the result:

    python3 tools/block_assets.py [--preview preview.png]

The textures are drawn here, procedurally, so the repository holds no copied art: 16x16, tileable, a handful of
shades each, in the spirit of vanilla's stone. --preview also writes a sheet of every texture tiled 3x3, for review.
Block names must match DiffusicraftBlocks in src/main/java/io/github/elpideus/diffusicraft/block. Block names in the
language file are kept by hand in assets/diffusicraft/lang/en_us.json.
"""
import argparse
import json
import math
import random
import struct
import zlib
from pathlib import Path

RESOURCES = Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
NS = "diffusicraft"
SIZE = 16


def hexes(*colors):
    return [tuple(int(c[i:i + 2], 16) for i in (1, 3, 5)) for c in colors]


# Tileable noise ---------------------------------------------------------------------------------------------------

def value_noise(rng, period):
    """Smooth noise on a SIZE x SIZE torus with `period` lattice cells per side."""
    lattice = [[rng.random() for _ in range(period)] for _ in range(period)]
    cell = SIZE / period

    def sample(x, y):
        fx, fy = x / cell, y / cell
        x0, y0 = int(math.floor(fx)), int(math.floor(fy))
        tx, ty = fx - x0, fy - y0
        tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        a = lattice[y0 % period][x0 % period]
        b = lattice[y0 % period][(x0 + 1) % period]
        c = lattice[(y0 + 1) % period][x0 % period]
        d = lattice[(y0 + 1) % period][(x0 + 1) % period]
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * ty

    return [[sample(x + 0.5, y + 0.5) for x in range(SIZE)] for y in range(SIZE)]


def fbm(rng, weights=((2, 0.45), (4, 0.3), (8, 0.25))):
    layers = [(value_noise(rng, period), w) for period, w in weights]
    return [[sum(layer[y][x] * w for layer, w in layers) for x in range(SIZE)] for y in range(SIZE)]


def voronoi(rng, count):
    """Nearest and second-nearest distance and cell index of every pixel, for points on the torus."""
    points = [(rng.uniform(0, SIZE), rng.uniform(0, SIZE)) for _ in range(count)]
    out = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            dists = []
            for i, (px, py) in enumerate(points):
                dx = min(abs(x + 0.5 - px), SIZE - abs(x + 0.5 - px))
                dy = min(abs(y + 0.5 - py), SIZE - abs(y + 0.5 - py))
                dists.append((math.hypot(dx, dy), i))
            dists.sort()
            row.append((dists[0][0], dists[1][0], dists[0][1]))
        out.append(row)
    return out


def shade(palette, v):
    v = min(max(v, 0.0), 0.999)
    return palette[int(v * len(palette))]


def grain(rng, amount):
    return (rng.random() - 0.5) * amount


# The rocks --------------------------------------------------------------------------------------------------------
# Each painter returns SIZE rows of SIZE RGB tuples. Bands and veins are periodic in 16 pixels, so every texture tiles.

def bedded(palette, band_periods, band_strength, noise_strength, specks=(), seed=0, warp=1.0):
    def paint():
        rng = random.Random(seed)
        base = fbm(rng)
        wobble = value_noise(rng, 2)
        rows = []
        for y in range(SIZE):
            row = []
            for x in range(SIZE):
                v = 0.5 + (base[y][x] - 0.5) * noise_strength + grain(rng, 0.18)
                yy = y + (wobble[y][x] - 0.5) * 2 * warp
                for period, weight in band_periods:
                    v += math.sin(2 * math.pi * yy / period) * band_strength * weight
                row.append(shade(palette, v))
            rows.append(row)
        sprinkle(rng, rows, specks)
        return rows
    return paint


def sprinkle(rng, rows, specks):
    for color, chance, size in specks:
        for y in range(SIZE):
            for x in range(SIZE):
                if rng.random() < chance:
                    for dy in range(size):
                        for dx in range(size if rng.random() < 0.6 else 1):
                            rows[(y + dy) % SIZE][(x + dx) % SIZE] = color


def veined(palette, vein_colors, seed, frequency=(1, 1), width=0.12):
    """Marble: a soft base cut by thin sinuous veins."""
    def paint():
        rng = random.Random(seed)
        base = fbm(rng, ((2, 0.6), (4, 0.4)))
        warp = fbm(rng, ((2, 0.7), (4, 0.3)))
        rows = []
        for y in range(SIZE):
            row = []
            for x in range(SIZE):
                v = 0.25 + base[y][x] * 0.7 + grain(rng, 0.1)
                phase = 2 * math.pi * (frequency[0] * x + frequency[1] * y) / SIZE + (warp[y][x] - 0.5) * 3.5
                s = abs(math.sin(phase))
                if s < width:
                    row.append(vein_colors[0])
                elif s < width * 2.2:
                    row.append(vein_colors[1])
                else:
                    row.append(shade(palette, v))
            rows.append(row)
        return rows
    return paint


def foliated(palette, seed, amplitude, bands=2, contrast=0.55, specks=(), light_palette=None):
    """Schist and gneiss: wavy bands of foliation. With a light palette, bands alternate dark and light rock."""
    def paint():
        rng = random.Random(seed)
        base = fbm(rng)
        rows = []
        offsets = [rng.uniform(0, 2 * math.pi) for _ in range(2)]
        for y in range(SIZE):
            row = []
            for x in range(SIZE):
                wave = amplitude * math.sin(2 * math.pi * x / SIZE + offsets[0]) \
                    + amplitude * 0.5 * math.sin(4 * math.pi * x / SIZE + offsets[1])
                band = math.sin(2 * math.pi * bands * (y + wave) / SIZE) + (base[y][x] - 0.5) * 0.9
                v = 0.5 + band * contrast * 0.5 + (base[y][x] - 0.5) * 0.6 + grain(rng, 0.2)
                if light_palette is not None:
                    row.append(shade(light_palette if band > 0.15 else palette, v if band > 0.15 else v + 0.2))
                else:
                    row.append(shade(palette, v))
            rows.append(row)
        sprinkle(rng, rows, specks)
        return rows
    return paint


def crystalline(palette, seed, cells, edge, edge_color=None, specks=()):
    """Rock salt and the coarse igneous rocks: grains of slightly different shade, with darker boundaries."""
    def paint():
        rng = random.Random(seed)
        cellmap = voronoi(rng, cells)
        tone = [rng.random() for _ in range(cells)]
        rows = []
        for y in range(SIZE):
            row = []
            for x in range(SIZE):
                d1, d2, i = cellmap[y][x]
                v = 0.15 + tone[i] * 0.75 + grain(rng, 0.12)
                if d2 - d1 < edge:
                    row.append(edge_color if edge_color else shade(palette, v - 0.35))
                else:
                    row.append(shade(palette, v))
            rows.append(row)
        sprinkle(rng, rows, specks)
        return rows
    return paint


def breccia(palette, clasts, seed, cells):
    """Kimberlite: a dark ground mass full of broken fragments of other rock."""
    def paint():
        rng = random.Random(seed)
        cellmap = voronoi(rng, cells)
        base = fbm(rng)
        kind = [rng.randrange(len(clasts) + 2) for _ in range(cells)]
        rows = []
        for y in range(SIZE):
            row = []
            for x in range(SIZE):
                d1, d2, i = cellmap[y][x]
                v = base[y][x] + grain(rng, 0.25)
                if kind[i] < len(clasts) and d1 < 1.7:
                    row.append(shade(clasts[kind[i]], v))
                else:
                    row.append(shade(palette, v))
            rows.append(row)
        return rows
    return paint


# name: (painter, block tags beyond the common ones)
ROCKS = {
    # Pale, with faint bedding and the odd shell fragment.
    "limestone": (bedded(hexes("#9c9480", "#aca48e", "#bcb39c", "#c9c0a8", "#d6ceb6"), ((8, 1.0), (16 / 3, 0.5)),
                         0.12, 0.9, specks=[(hexes("#e6e0cc")[0], 0.02, 1), (hexes("#857d6a")[0], 0.012, 1)], seed=11),
                  ["stone_ore_replaceables"]),
    # Soft and nearly white; dark flint nodules.
    "chalk": (bedded(hexes("#cfcfc4", "#dbdbd0", "#e5e5db", "#eeeee5", "#f6f6ef"), ((16, 1.0),), 0.06, 1.1,
                     specks=[(hexes("#55565c")[0], 0.006, 2)], seed=12), []),
    # Dark, in thin laminations.
    "shale": (bedded(hexes("#3d3f43", "#47494e", "#515459", "#5b5e63", "#66696e"), ((4, 1.0), (16, 0.4)), 0.22, 0.6,
                     seed=13, warp=0.6), ["stone_ore_replaceables"]),
    # Pinkish, in cubic crystals.
    "rock_salt": (crystalline(hexes("#c4a39e", "#d2b4ae", "#dec4be", "#e9d4cf", "#f3e4e0"), seed=14, cells=9,
                              edge=0.7), []),
    # Blue-grey and finely cleaved.
    "slate": (bedded(hexes("#343a41", "#3d444c", "#464e57", "#505861", "#5a636c"), ((16 / 5, 1.0), (16, 0.3)), 0.16,
                     0.5, seed=15, warp=0.4), ["stone_ore_replaceables"]),
    # White with grey veins.
    "marble": (veined(hexes("#d2d0cb", "#dddbd7", "#e7e5e1", "#efeeea", "#f7f6f3"), hexes("#a4a5aa", "#c6c6ca"),
                      seed=16, width=0.1), ["stone_ore_replaceables"]),
    # Silvery, wavy, glinting with mica.
    "schist": (foliated(hexes("#6b6b65", "#7a7a73", "#898981", "#999890", "#aaa99f"), seed=17, amplitude=1.6, bands=3,
                        specks=[(hexes("#d4d2c6")[0], 0.03, 1)]), ["stone_ore_replaceables"]),
    # Bands of dark mica and pink-white feldspar.
    "gneiss": (foliated(hexes("#46434a", "#524e55", "#5f5a61", "#6c676d"), seed=18, amplitude=1.1, bands=2,
                        light_palette=hexes("#968782", "#a89893", "#b9aaa4", "#c8bbb5")), ["stone_ore_replaceables"]),
    # Near black, coarse, with white plagioclase grains.
    "gabbro": (crystalline(hexes("#222524", "#2b2f2e", "#343938", "#3e4342"), seed=19, cells=14, edge=0.45,
                           specks=[(hexes("#8b8f8a")[0], 0.05, 1), (hexes("#2f3b31")[0], 0.05, 1)]),
               ["deepslate_ore_replaceables"]),
    # Olive green olivine.
    "peridotite": (crystalline(hexes("#4c5727", "#5a6730", "#687739", "#768643", "#85954e"), seed=20, cells=16,
                               edge=0.4, specks=[(hexes("#383f1f")[0], 0.04, 1), (hexes("#9ca35c")[0], 0.03, 1)]),
                   ["deepslate_ore_replaceables"]),
    # Blue-green ground mass with fragments.
    "kimberlite": (breccia(hexes("#3b4e4c", "#465a57", "#516663", "#5c716e"),
                           [hexes("#7e8479", "#8f958a", "#a0a69a"), hexes("#2b3533", "#333e3c"),
                            hexes("#636e4f", "#717d5b")], seed=21, cells=14), ["deepslate_ore_replaceables"]),
}
# Rocks hard enough to make tools and furnaces from, like cobblestone. The vanilla ones are here because a Diffusicraft
# basement can be granite or andesite for hundreds of blocks, with no cobblestone to be found.
TOOL_ROCKS = [f"{NS}:{name}" for name in ("limestone", "shale", "slate", "marble", "schist", "gneiss", "gabbro",
                                          "peridotite", "kimberlite")] \
    + ["minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:tuff"]


# Output -----------------------------------------------------------------------------------------------------------

def png(rows):
    raw = b"".join(b"\x00" + bytes(c for pixel in row for c in pixel) for row in rows)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", len(rows[0]), len(rows), 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def write_json(path, data):
    path = RESOURCES / path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def tag(path, values):
    write_json(path, {"replace": False, "values": values})


def preview(textures, path, tiles=3, zoom=4):
    """Every texture tiled `tiles` x `tiles`, scaled up `zoom` times, side by side in rows of four."""
    per_row = 4
    cell = SIZE * tiles * zoom + 8
    names = list(textures)
    rows_out = [[(24, 24, 24)] * (cell * per_row) for _ in range(cell * math.ceil(len(names) / per_row))]
    for n, name in enumerate(names):
        ox, oy = (n % per_row) * cell + 4, (n // per_row) * cell + 4
        for y in range(SIZE * tiles * zoom):
            for x in range(SIZE * tiles * zoom):
                rows_out[oy + y][ox + x] = textures[name][(y // zoom) % SIZE][(x // zoom) % SIZE]
    Path(path).write_bytes(png(rows_out))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", help="also write a sheet of every texture, tiled, to this PNG")
    args = parser.parse_args()
    textures = {}
    for name, (painter, _) in ROCKS.items():
        rows = painter()
        textures[name] = rows
        texture = RESOURCES / "assets" / NS / "textures" / "block" / f"{name}.png"
        texture.parent.mkdir(parents=True, exist_ok=True)
        texture.write_bytes(png(rows))
        write_json(f"assets/{NS}/blockstates/{name}.json", {"variants": {"": {"model": f"{NS}:block/{name}"}}})
        write_json(f"assets/{NS}/models/block/{name}.json",
                   {"parent": "minecraft:block/cube_all", "textures": {"all": f"{NS}:block/{name}"}})
        write_json(f"assets/{NS}/items/{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:block/{name}"}})
        write_json(f"data/{NS}/loot_table/blocks/{name}.json", {
            "type": "minecraft:block",
            "pools": [{"condition": {"type": "minecraft:survives_explosion"},
                       "entries": [{"type": "minecraft:item", "name": f"{NS}:{name}"}], "rolls": 1}],
            "random_sequence": f"{NS}:blocks/{name}"})
    every = [f"{NS}:{name}" for name in ROCKS]
    tag("data/minecraft/tags/block/mineable/pickaxe.json", every)
    # Moss, sculk, dripstone and lush caves spread over base stone, and the vanilla ore blobs (granite, tuff, dirt...)
    # replace it.
    tag("data/minecraft/tags/block/base_stone_overworld.json", every)
    for replaceables in ("stone_ore_replaceables", "deepslate_ore_replaceables"):
        tag(f"data/minecraft/tags/block/{replaceables}.json",
            [f"{NS}:{name}" for name, (_, tags) in ROCKS.items() if replaceables in tags])
    tag("data/minecraft/tags/item/stone_tool_materials.json", TOOL_ROCKS)
    tag("data/minecraft/tags/item/stone_crafting_materials.json", TOOL_ROCKS)
    if args.preview:
        preview(textures, args.preview)


if __name__ == "__main__":
    main()
