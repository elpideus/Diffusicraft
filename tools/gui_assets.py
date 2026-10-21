#!/usr/bin/env python3
"""Draws Diffusicraft's GUI sprites. The files are committed; run this after changing one and commit the result:

    python3 tools/gui_assets.py

The pause menu's relief map button: a folded map, coloured like vanilla's pause menu icons, 15x15.
"""
from block_assets import NS, RESOURCES, png

COLORS = {
    ".": None,
    "o": (74, 50, 34),
    "w": (63, 118, 228),
    "W": (111, 160, 240),
    "g": (95, 160, 64),
    "G": (63, 122, 44),
    "m": (138, 122, 102),
    "s": (240, 240, 240),
    "r": (216, 48, 42),
}

RELIEF_MAP = [
    "...............",
    "ooooooooooooooo",
    "owwwwggggGGgggo",
    "owwwgggGmmGgggo",
    "owWwggggmsmGggo",
    "owwggggmmssmggo",
    "owwwgggmmmmgggo",
    "owwwwgggggrgrgo",
    "owwWwwggggGrggo",
    "owwwwwwgggrgrgo",
    "owwwwwwwggggggo",
    "owwwWwwwwwgggGo",
    "owwwwwwwwwwgggo",
    "ooooooooooooooo",
    "...............",
]


def sprite(art, folds=(5, 10)):
    """The art in colour, its middle panel darker, as a map folded in three."""
    rows = []
    for row in art:
        out = []
        for x, c in enumerate(row):
            color = COLORS[c]
            if color is None:
                out.append((0, 0, 0, 0))
                continue
            if c != "o" and folds[0] <= x < folds[1]:
                color = tuple(round(v * 0.82) for v in color)
            out.append((*color, 255))
        rows.append(out)
    return rows


def main():
    path = RESOURCES / "assets" / NS / "textures" / "gui" / "sprites" / "pause_menu" / "relief_map.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png(sprite(RELIEF_MAP)))


if __name__ == "__main__":
    main()
