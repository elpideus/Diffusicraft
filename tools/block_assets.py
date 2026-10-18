#!/usr/bin/env python3
"""Writes the assets and data of Diffusicraft's blocks: textures, block states, models, item models, loot tables and
tags. The files are committed; run this after adding or changing a block and commit the result:

    python3 tools/block_assets.py [--preview preview.png]

The textures are drawn here, procedurally, so the repository holds no copied art: 16x16, a handful of shades each, in
the spirit of vanilla's. Block textures tile; plants are sprites on a transparent ground. --preview also writes a sheet
of every texture tiled 3x3, for review.
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


# Soils ------------------------------------------------------------------------------------------------------------

def soil(palette, seed, specks=(), streaks=None, clumps=0.5):
    """Earth, as vanilla's dirt: soft clumps of a few shades, specks of grit, and optionally fibres (`streaks`, a
    colour) lying flat as in peat."""
    def paint():
        rng = random.Random(seed)
        base = fbm(rng, ((4, 0.5), (8, 0.5)))
        rows = []
        for y in range(SIZE):
            row = []
            for x in range(SIZE):
                v = 0.5 + (base[y][x] - 0.5) * clumps * 2 + grain(rng, 0.45)
                row.append(shade(palette, v))
            rows.append(row)
        if streaks is not None:
            for _ in range(7):
                x, y = rng.randrange(SIZE), rng.randrange(SIZE)
                for dx in range(rng.randint(2, 5)):
                    rows[(y + (dx // 3) * rng.choice((-1, 0, 1))) % SIZE][(x + dx) % SIZE] = streaks
        sprinkle(rng, rows, specks)
        return rows
    return paint


# name: (painter, "fuel" if it burns)
SOILS = {
    # Black-brown, half-rotted moss and sedge, with fibres in it.
    "peat": (soil(hexes("#2b1f17", "#33261b", "#3c2d20", "#463526", "#4f3d2b"), 41, streaks=hexes("#6a5236")[0],
                  specks=[(hexes("#1e1610")[0], 0.04, 1)]), "fuel"),
    # The red clay of the Mediterranean's limestone, with chips of the limestone.
    "terra_rossa": (soil(hexes("#6e2f1e", "#7c3824", "#8a412a", "#984b30", "#a65638"), 42,
                         specks=[(hexes("#b8ad94")[0], 0.012, 1), (hexes("#5a2618")[0], 0.04, 1)]), None),
    # Tropical red earth, leached to iron and aluminium, with dark iron pisoliths.
    "laterite": (soil(hexes("#8e3f20", "#a04b26", "#b0582c", "#be6634", "#c9733e"), 43,
                      specks=[(hexes("#5a2412")[0], 0.035, 1), (hexes("#d8935a")[0], 0.025, 1)]), None),
    # Pale yellow wind-blown silt, fine and even.
    "loess": (soil(hexes("#a89068", "#b49c72", "#bfa87e", "#c9b38a", "#d2bd95"), 44, clumps=0.45,
                   specks=[(hexes("#8e7852")[0], 0.04, 1)]), None),
}


# Salt crust -------------------------------------------------------------------------------------------------------

def salt_polygons(seed):
    """The salt flats' crust: white plates bounded by raised, brighter rims, as on the Salar de Uyuni."""
    def paint():
        rng = random.Random(seed)
        cellmap = voronoi(rng, 5)
        base = fbm(rng)
        plate = hexes("#d8d5cc", "#e0ddd5", "#e7e5de", "#eeece6", "#f4f3ee")
        rim, shadow = hexes("#fbfbf8")[0], hexes("#c4bfb3")[0]
        rows = []
        for y in range(SIZE):
            row = []
            for x in range(SIZE):
                d1, d2, _ = cellmap[y][x]
                gap = d2 - d1
                if gap < 0.7:
                    row.append(rim)
                elif gap < 1.5:
                    row.append(shadow)
                else:
                    row.append(shade(plate, 0.2 + base[y][x] * 0.7 + grain(rng, 0.15)))
            rows.append(row)
        sprinkle(rng, rows, [(hexes("#a89c86")[0], 0.012, 1)])
        return rows
    return paint


# The plants -------------------------------------------------------------------------------------------------------
# Drawn on a transparent 16x16 canvas from the ground (y = 15) up, for vanilla's cross model. The game renders a
# texture with transparent pixels as cutout, so the plants need nothing else.

class Canvas:
    def __init__(self):
        self.pixels = [[None] * SIZE for _ in range(SIZE)]

    def put(self, x, y, color):
        x, y = int(round(x)), int(round(y))
        if 0 <= x < SIZE and 0 <= y < SIZE:
            self.pixels[y][x] = color

    def stem(self, x, top, color, lean=0.0, bottom=15):
        """A one-pixel stem from the ground up to `top`, drifting `lean` pixels sideways over its height."""
        for y in range(bottom, top - 1, -1):
            self.put(x + lean * (bottom - y) / max(1, bottom - top), y, color)

    def rgba(self):
        return [[(0, 0, 0, 0) if c is None else (*c, 255) for c in row] for row in self.pixels]


def heather(seed):
    """A low, dense dome of tiny dark leaves, its upper shoots covered in mauve bells."""
    def paint():
        rng = random.Random(seed)
        c = Canvas()
        wood = hexes("#4a3b2a", "#5b4733")
        leaf = hexes("#2f4a26", "#3a572d", "#466636", "#53743d")
        bloom = hexes("#8c4884", "#a35a9a", "#b86eae", "#c986c1")
        for x in (4, 7, 9, 12):
            c.stem(x, 12, rng.choice(wood), rng.uniform(-1, 1))
        for y in range(3, 14):
            half = 7.2 * math.sqrt(max(0.0, 1 - ((13.5 - y) / 10.5) ** 2))
            for x in range(SIZE):
                inside = half - abs(x + 0.5 - 8)
                if inside > 1 or (inside > 0 and rng.random() < 0.6):
                    c.put(x, y, shade(leaf, 0.15 + (13 - y) * 0.06 + grain(rng, 0.5)))
        # Flowering shoots: short spikes of bells on the upper half.
        for _ in range(9):
            x = rng.randint(2, 13)
            top = 3 + int(abs(x + 0.5 - 8) * 0.55) + rng.randint(-1, 1)
            for y in range(top, top + rng.randint(2, 4)):
                c.put(x, y, rng.choice(bloom))
        for _ in range(10):
            c.put(rng.randint(2, 13), rng.randint(5, 11), rng.choice(bloom))
        return c.rgba()
    return paint


def lavender(seed):
    """Grey-green tufts under slender stems, each ending in a spike of violet whorls."""
    def paint():
        rng = random.Random(seed)
        c = Canvas()
        stalk = hexes("#6c8160", "#7a8f6c")
        leaf = hexes("#7c9670", "#8fa883", "#a1b796")
        bloom = hexes("#5f4590", "#7356a8", "#8a6cc0", "#a186d4")
        for x in (2, 4, 7, 9, 12, 14):
            x += rng.choice((-1, 0, 0))
            top = rng.randint(1, 4)
            lean = rng.uniform(-1.2, 1.2)
            c.stem(x, top, rng.choice(stalk), lean)
            for y in range(top, top + 6):
                px = x + lean * (15 - y) / (15 - top)
                c.put(px, y, rng.choice(bloom))
                if (y - top) % 2 == 0:
                    c.put(px + rng.choice((-1, 1)), y, rng.choice(bloom))
        for _ in range(16):
            x, y = rng.randint(2, 13), rng.randint(11, 15)
            c.put(x, y, rng.choice(leaf))
            c.put(x + rng.choice((-1, 1)), y - 1, rng.choice(leaf))
        return c.rgba()
    return paint


def edelweiss(seed):
    """Woolly grey leaves and white, star-shaped flower heads with a yellowish heart."""
    def paint():
        rng = random.Random(seed)
        c = Canvas()
        stalk = hexes("#8a977c", "#9ba68e")
        leaf = hexes("#a3ad96", "#b4bda8", "#c3cab8")
        petal = hexes("#e1e3da", "#eeefe8", "#f8f8f4")
        heart = hexes("#c9bd78", "#d9cf8f", "#b8ae6a")
        for x, y in ((8, 5), (4, 9), (12, 10)):
            c.stem(x, y + 1, rng.choice(stalk))
            for dx, dy, reach in ((0, -1, 2), (0, 1, 2), (-1, 0, 2), (1, 0, 2), (-1, -1, 1), (1, -1, 1), (-1, 1, 1),
                                  (1, 1, 1)):
                for r in range(1, reach + (1 if (x, y) == (8, 5) else 0) + 1):
                    c.put(x + dx * r, y + dy * r, rng.choice(petal))
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    if abs(dx) + abs(dy) < 2:
                        c.put(x + dx, y + dy, rng.choice(heart))
        for x in range(3, 13):
            for y in range(13, 16):
                if rng.random() < 0.55 - abs(x - 8) * 0.04:
                    c.put(x, y, rng.choice(leaf))
        return c.rgba()
    return paint


def gentian(seed):
    """Stemless gentian: a rosette of dark leaves and big, upright trumpets of deep blue."""
    def paint():
        rng = random.Random(seed)
        c = Canvas()
        leaf = hexes("#2c5428", "#386630", "#47783b")
        blue = hexes("#1b3296", "#2443b6", "#2f55cf", "#4569df")
        throat = hexes("#122062")[0]
        stalk = hexes("#3d6a33")[0]
        for left, top, height in ((3, 3, 9), (9, 6, 7)):
            for y in range(top, top + height):
                t = (y - top) / (height - 1)
                width = 4 if t < 0.25 else 3 if t < 0.55 else 2
                x0 = left + (4 - width) // 2
                for x in range(x0, x0 + width):
                    edge = x in (x0, x0 + width - 1)
                    c.put(x, y, blue[0] if edge and t > 0.3 else rng.choice(blue[1:]))
            c.put(left + 1, top, throat)
            c.put(left + 2, top, throat)
            c.put(left, top - 1, rng.choice(blue[2:]))
            c.put(left + 3, top - 1, rng.choice(blue[2:]))
            c.stem(left + 1 + rng.choice((0, 1)), top + height, stalk)
        for x in range(1, 15):
            for y in range(12, 16):
                if rng.random() < 0.6 - abs(x - 7.5) * 0.05:
                    c.put(x, y, rng.choice(leaf))
        return c.rgba()
    return paint


def cottongrass(seed):
    """A sedge of bogs and tundra: thin leaves, and stalks ending in tufts of white cotton."""
    def paint():
        rng = random.Random(seed)
        c = Canvas()
        leaf = hexes("#5b7a3a", "#6b8b45", "#7d9d53")
        stalk = hexes("#6f8a4a")[0]
        cotton = hexes("#dcdfd5", "#ecede6", "#f7f7f3")
        for x in (2, 5, 8, 11, 14):
            c.stem(x + rng.choice((-1, 0)), rng.randint(8, 11), rng.choice(leaf), rng.uniform(-2.5, 2.5))
        for x, top in ((3, rng.randint(2, 4)), (7, rng.randint(1, 3)), (10, rng.randint(3, 5)),
                       (13, rng.randint(2, 4))):
            lean = rng.uniform(-1, 1)
            c.stem(x, top + 2, stalk, lean)
            cx = x + lean
            for dx, dy in ((0, -1), (-1, 0), (0, 0), (1, 0), (-1, 1), (0, 1), (1, 1), (0, 2)):
                if dx == 0 or rng.random() < 0.8:
                    c.put(cx + dx, top + dy, rng.choice(cotton))
        return c.rgba()
    return paint


SALT_CRUST = salt_polygons(30)

# name: (painter, loot, block tags, item tags, dye). Loot "self" drops the plant; "shears" only to shears and silk
# touch, as vanilla's bush. Sheep graze on the heather and cottongrass of the moors.
FLOWER_BLOCK_TAGS = ["small_flowers", "bee_attractive", "washed_away_by_fluids"]
SHRUB_BLOCK_TAGS = ["replaceable_by_trees", "replaceable_by_mushrooms", "washed_away_by_fluids"]
PLANTS = {
    "heather": (heather(31), "self", SHRUB_BLOCK_TAGS + ["bee_attractive", "edible_for_sheep"], ["bee_food"],
                "magenta_dye"),
    "cottongrass": (cottongrass(32), "shears", SHRUB_BLOCK_TAGS + ["replaceable", "edible_for_sheep"], [], None),
    "lavender": (lavender(33), "self", FLOWER_BLOCK_TAGS, ["small_flowers", "bee_food"], "purple_dye"),
    "edelweiss": (edelweiss(34), "self", FLOWER_BLOCK_TAGS, ["small_flowers", "bee_food"], "white_dye"),
    "gentian": (gentian(35), "self", FLOWER_BLOCK_TAGS, ["small_flowers", "bee_food"], "blue_dye"),
}


# Output -----------------------------------------------------------------------------------------------------------

def png(rows):
    """RGB rows, or RGBA ones for textures with transparency."""
    raw = b"".join(b"\x00" + bytes(c for pixel in row for c in pixel) for row in rows)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", len(rows[0]), len(rows), 8, 6 if len(rows[0][0]) == 4 else 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def write_json(path, data):
    path = RESOURCES / path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def tag(path, values):
    write_json(path, {"replace": False, "values": values})


def opaque(pixel, backdrop=(96, 110, 128)):
    """A pixel over the preview's backdrop."""
    if len(pixel) == 3:
        return pixel
    return pixel[:3] if pixel[3] else backdrop


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
                rows_out[oy + y][ox + x] = opaque(textures[name][(y // zoom) % SIZE][(x // zoom) % SIZE])
    Path(path).write_bytes(png(rows_out))


def loot(name, condition):
    write_json(f"data/{NS}/loot_table/blocks/{name}.json", {
        "type": "minecraft:block",
        "pools": [{"condition": condition, "entries": [{"type": "minecraft:item", "name": f"{NS}:{name}"}], "rolls": 1}],
        "random_sequence": f"{NS}:blocks/{name}"})


def cube(name, rows):
    """A full block with the same texture on every side."""
    write_texture(name, rows)
    write_json(f"assets/{NS}/blockstates/{name}.json", {"variants": {"": {"model": f"{NS}:block/{name}"}}})
    write_json(f"assets/{NS}/models/block/{name}.json",
               {"parent": "minecraft:block/cube_all", "textures": {"all": f"{NS}:block/{name}"}})
    write_json(f"assets/{NS}/items/{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:block/{name}"}})
    loot(name, {"type": "minecraft:survives_explosion"})


def cross(name, rows, drop):
    """A plant: two crossed planes in the world, a flat sprite in the hand."""
    write_texture(name, rows)
    write_json(f"assets/{NS}/blockstates/{name}.json", {"variants": {"": {"model": f"{NS}:block/{name}"}}})
    write_json(f"assets/{NS}/models/block/{name}.json",
               {"parent": "minecraft:block/cross", "textures": {"cross": f"{NS}:block/{name}"}})
    write_json(f"assets/{NS}/models/item/{name}.json",
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:block/{name}"}})
    write_json(f"assets/{NS}/items/{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    if drop == "shears":
        loot(name, {"type": "minecraft:any_of", "terms": ["minecraft:tool/can_shear", "minecraft:tool/can_silk_touch"]})
    else:
        loot(name, {"type": "minecraft:survives_explosion"})


def write_texture(name, rows):
    texture = RESOURCES / "assets" / NS / "textures" / "block" / f"{name}.png"
    texture.parent.mkdir(parents=True, exist_ok=True)
    texture.write_bytes(png(rows))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", help="also write a sheet of every texture, tiled, to this PNG")
    args = parser.parse_args()
    textures = {}
    for name, (painter, _) in ROCKS.items():
        textures[name] = painter()
        cube(name, textures[name])
    every = [f"{NS}:{name}" for name in ROCKS]
    # Moss, sculk, dripstone and lush caves spread over base stone, and the vanilla ore blobs (granite, tuff, dirt...)
    # replace it.
    tag("data/minecraft/tags/block/base_stone_overworld.json", every)
    for replaceables in ("stone_ore_replaceables", "deepslate_ore_replaceables"):
        tag(f"data/minecraft/tags/block/{replaceables}.json",
            [f"{NS}:{name}" for name, (_, tags) in ROCKS.items() if replaceables in tags])
    tag("data/minecraft/tags/item/stone_tool_materials.json", TOOL_ROCKS)
    tag("data/minecraft/tags/item/stone_crafting_materials.json", TOOL_ROCKS)

    for name, (painter, kind) in SOILS.items():
        textures[name] = painter()
        cube(name, textures[name])
        if kind == "fuel":
            # Half of coal, slower in a fast furnace as coal is.
            write_json(f"data/{NS}/context_int_provider/cooking/time_{name}.json", {
                "type": "minecraft:div", "left": 800, "right": {
                    "type": "minecraft:conditional", "condition": "minecraft:block/fast_cooking",
                    "on_false": "minecraft:cooking/normal_burn_time_reduction_factor",
                    "on_true": "minecraft:cooking/fast_burn_time_reduction_factor"}})
    soils = [f"{NS}:{name}" for name in SOILS]
    tag("data/minecraft/tags/block/mineable/shovel.json", soils)
    # Dirt, as far as plants, trees, moss and sculk care: grass and flowers grow on them, trees root in them.
    tag("data/minecraft/tags/block/dirt.json", soils)
    tag("data/minecraft/tags/item/dirt.json", soils)

    textures["salt_crust"] = SALT_CRUST()
    cube("salt_crust", textures["salt_crust"])
    tag("data/minecraft/tags/block/mineable/pickaxe.json", every + [f"{NS}:salt_crust"])

    block_tags, item_tags = {}, {}
    for name, (painter, drop, blocks, items, dye) in PLANTS.items():
        textures[name] = painter()
        cross(name, textures[name], drop)
        for t in blocks:
            block_tags.setdefault(t, []).append(f"{NS}:{name}")
        for t in items:
            item_tags.setdefault(t, []).append(f"{NS}:{name}")
        if dye:
            write_json(f"data/{NS}/recipe/{dye}_from_{name}.json", {
                "type": "minecraft:crafting_shapeless", "group": dye, "ingredients": [f"{NS}:{name}"],
                "result": {"id": f"minecraft:{dye}"}})
    for t, values in sorted(block_tags.items()):
        tag(f"data/minecraft/tags/block/{t}.json", values)
    for t, values in sorted(item_tags.items()):
        tag(f"data/minecraft/tags/item/{t}.json", values)
    if args.preview:
        preview(textures, args.preview)


if __name__ == "__main__":
    main()
