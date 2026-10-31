#!/usr/bin/env python3
"""Writes Diffusicraft's world generation data (density functions, material rules, noise settings, dimension types,
world presets).

The files are committed; run this after changing a scale and commit the result:

    python3 tools/worldgen_data.py

Each scale must match the WorldScale enum in src/main/java/io/github/elpideus/diffusicraft/worldgen/WorldScale.java.
See docs/WORLDGEN.md for what the files do.
"""
import json
from pathlib import Path

import vanilla_copies

ROOT = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "data"
NS = "diffusicraft"
SEA_LEVEL = 63

# The world sizes, from 1:32 to 1:1. name: (min_y, height, dimension type, highest surface the size can reach,
# cloud height or None). The clouds float about 3000 m above the sea, in the size's exaggerated heights, over all but
# the highest plateaus; the client draws vanilla's clouds that much larger (client.clouds.CloudScale).
SCALES = {
    "vanilla": (-64, 384, "minecraft:overworld", 303, None),
    "medium": (-256, 1536, f"{NS}:medium", 1163, 532.33),
    "large": (-256, 2560, f"{NS}:large", 2213, 920.33),
    "grand": (-256, 4096, f"{NS}:grand", 3763, 1563.33),
    "extreme": (-256, 8448, f"{NS}:extreme", 7863, 2672.33),
    "ultra": (-256, 16624, f"{NS}:ultra", 15863, 4678.33),
}
# The size a new world starts with, and the only one listed among the world types. The Create World screen picks the
# others with its size slider.
DEFAULT = "ultra"
# Biome parameters, as named by ClimateFunction.Parameter, and the noise router fields they feed.
CLIMATE = {
    "temperature": "temperature",
    "vegetation": "humidity",
    "continents": "continentalness",
    "erosion": "erosion",
    "ridges": "weirdness",
    "depth": "depth",
}


def write(path, data):
    path = ROOT / path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def ref(scale, name):
    return f"{NS}:{scale}/{name}"


def gradient(y0, v0, y1, v1):
    return {"type": "minecraft:gradient", "axis": "y",
            "from_coordinate": y0, "from_value": v0, "to_coordinate": y1, "to_value": v1}


# Caves. Vanilla's own open about 8% of the ground under the surface (cheese caverns most, then the carvers and the
# spaghetti tunnels); Diffusicraft's open a little over a third of that, the largest part of it in noodles.
# How far the cheese noise must fall below zero to open a cavern; vanilla's 0.27 opens one at every other step.
CHEESE_OFFSET = 0.55
# Spaghetti tunnels run only where their gate noise reaches this, which leaves about a quarter of vanilla's 3D tunnels
# and under a fifth of its 2D ones, as whole networks rather than shorter tunnels.
ENTRANCE_GATE = 0.2
SPAGHETTI_2D_GATE = 0.3
# A tunnel closes over this much of its gate noise below the threshold.
GATE_RAMP = 4.0
# Noodles run where the noodle noise reaches this (vanilla: 0), and are wider than vanilla's: half width in ridge
# noise, spread by the thickness noise (vanilla: 0.075 and 0.025).
NOODLE_TOGGLE = -0.25
NOODLE_THICKNESS = 0.09
NOODLE_THICKNESS_SPREAD = 0.025


def noise(name, xz_scale=1.0, y_scale=1.0):
    return {"type": "minecraft:noise", "noise": name, "xz_scale": xz_scale, "y_scale": y_scale}


def gated(function, gate, threshold):
    """`function` where the noise `gate` reaches `threshold`; elsewhere it closes, within a little of the threshold."""
    return {"type": "minecraft:add", "left": function, "right": {"type": "minecraft:clamp", "min": 0.0, "max": 1.0,
            "input": {"type": "minecraft:mul", "left": {"type": "minecraft:add", "left": noise(gate),
                                                        "right": -threshold}, "right": -GATE_RAMP}}}


def cheese(scale):
    """Vanilla's cheese caverns, rarer and smaller: the same noises with a higher offset. They close towards the
    surface, as vanilla's do."""
    return {"type": "minecraft:add",
            "left": {"type": "minecraft:mul", "left": {"type": "minecraft:square", "input": noise(
                "minecraft:cave_layer", 1.0, 8.0)}, "right": 4.0},
            "right": {"type": "minecraft:add",
                      "left": {"type": "minecraft:clamp", "min": -1.0, "max": 1.0, "input": {
                          "type": "minecraft:add", "left": noise("minecraft:cave_cheese", 1.0, 0.6666666666666666),
                          "right": CHEESE_OFFSET}},
                      "right": {"type": "minecraft:clamp", "min": 0.0, "max": 0.5, "input": {
                          "type": "minecraft:add", "left": {"type": "minecraft:mul", "left": ref(scale, "terrain"),
                                                            "right": -0.64}, "right": 1.5}}}}


def noodle(min_y, top):
    """Vanilla's noodle caves, in more of the ground, wider and from the bottom of the world to the top."""
    def ranged(inside, outside):
        return {"type": "minecraft:interpolated", "cell_size_xz": 4, "cell_size_y": 8, "input": {
            "type": "minecraft:range_choice", "input": "minecraft:y", "min_inclusive": float(min_y + 4),
            "max_exclusive": float(top + 1), "when_in_range": inside, "when_out_of_range": outside}}
    ridge = 2.6666666666666665
    thickness = ranged({"type": "minecraft:add", "left": {"type": "minecraft:mul",
                        "left": noise("minecraft:noodle_thickness"), "right": -NOODLE_THICKNESS_SPREAD},
                        "right": -NOODLE_THICKNESS}, 0.0)
    ridges = {"type": "minecraft:mul", "right": 1.5, "left": {
        "type": "minecraft:max",
        "left": {"type": "minecraft:abs", "input": ranged(noise("minecraft:noodle_ridge_a", ridge, ridge), 0.0)},
        "right": {"type": "minecraft:abs", "input": ranged(noise("minecraft:noodle_ridge_b", ridge, ridge), 0.0)}}}
    return {"type": "minecraft:range_choice", "input": ranged(noise("minecraft:noodle"), -1.0),
            "min_inclusive": -1000000.0, "max_exclusive": NOODLE_TOGGLE, "when_in_range": 64.0,
            "when_out_of_range": {"type": "minecraft:add", "left": thickness, "right": ridges}}


def final_density(scale, min_y, top):
    """Vanilla's overworld final density with our terrain in place of sloped_cheese, our caves (see cheese, noodle
    and the gates above), the Hollows, the sea cliffs and the rivers.

    Pillars, blending and structures (the beardifier) are vanilla's own functions. The Hollows join the caves deep
    underground, where vanilla's caves are, too. The sea cliffs and then the rivers come last, so a cliff stays whole
    down to the ground around it and a river cuts its way through a cliff to the sea; their beds seal any cave below
    them.
    The bottom of the world fades to solid and everything above the highest possible surface fades to air.
    """
    terrain = ref(scale, "terrain")
    caves = "minecraft:overworld/caves/"
    underground = {
        "type": "minecraft:max",
        "left": {
            "type": "minecraft:min",
            "left": {"type": "minecraft:min", "left": ref(scale, "caves/cheese"),
                     "right": ref(scale, "caves/entrances")},
            "right": ref(scale, "caves/spaghetti_2d"),
        },
        "right": {"type": "minecraft:range_choice", "input": caves + "pillars",
                  "min_inclusive": -1000000.0, "max_exclusive": 0.03,
                  "when_in_range": -1000000.0, "when_out_of_range": caves + "pillars"},
    }
    # Cave entrances on ground a few blocks above the sea fill with sea water: a pit by the coast becomes a bay and
    # a tunnel a canal. They fade out as the ground falls from 8 to 2 blocks above sea level.
    lowland = {"type": "minecraft:clamp", "min": 0.0, "max": 3.0, "input": {
        "type": "minecraft:mul", "left": {"type": "minecraft:add", "left": ref(scale, "surface"),
                                          "right": -float(SEA_LEVEL + 8)}, "right": -0.5}}
    entrances = {"type": "minecraft:add", "left": {"type": "minecraft:mul", "left": ref(scale, "caves/entrances"),
                                                   "right": 5.0}, "right": lowland}
    shaped = {
        "type": "minecraft:range_choice",
        "input": terrain,
        "min_inclusive": -1000000.0,
        "max_exclusive": 1.5625,
        "when_in_range": {"type": "minecraft:min", "left": terrain, "right": entrances},
        # Deeper than 25 blocks below the surface; the Hollows keep 28 blocks of roof.
        "when_out_of_range": {"type": "minecraft:min", "left": underground, "right": ref(scale, "hollows")},
    }
    faded = {
        "type": "minecraft:lerp",
        "alpha": gradient(min_y, 0.0, min_y + 24, 1.0),
        "first": 0.1171875,
        "second": {"type": "minecraft:lerp", "alpha": gradient(top - 16, 1.0, top, 0.0),
                   "first": -0.078125, "second": shaped},
    }
    ground = {
        "type": "minecraft:min",
        "left": {"type": "minecraft:squeeze", "input": {
            "type": "minecraft:interpolated", "cell_size_xz": 4, "cell_size_y": 8,
            "input": {"type": "minecraft:mul",
                      "left": {"type": "minecraft:blend_density", "input": faded},
                      "right": 0.64}}},
        "right": ref(scale, "caves/noodle"),
    }
    # Sea cliffs, then rivers, build the ground up where it lies lower, then cut it down, block by block (outside
    # the interpolation, so cliff faces, banks and beds stay sharp).
    coast = {
        "type": "minecraft:min",
        "left": {"type": "minecraft:max", "left": ground, "right": ref(scale, "coast_fill")},
        "right": ref(scale, "coast_carve"),
    }
    rivers = {
        "type": "minecraft:min",
        "left": {"type": "minecraft:max", "left": coast, "right": ref(scale, "river_fill")},
        "right": ref(scale, "river_carve"),
    }
    return {"type": "minecraft:add", "left": rivers, "right": {"type": "minecraft:beardifier"}}


def noise_settings(scale, min_y, height):
    erosion, depth = ref(scale, "erosion"), ref(scale, "depth")
    return {
        "sea_level": SEA_LEVEL,
        "disable_mob_generation": False,
        "legacy_random_source": False,
        "default_block": "minecraft:stone",
        "default_fluid": "minecraft:water",
        "noise": {"min_y": min_y, "height": height},
        "noise_router": {
            **{field: ref(scale, field) for field in CLIMATE},
            "chunk_surface_level": ref(scale, "surface"),
            "final_density": ref(scale, "final_density"),
        },
        "aquifers": {
            "barrier": {"type": "minecraft:noise", "noise": "minecraft:aquifer_barrier", "xz_scale": 1.0, "y_scale": 0.5},
            "fluid_level_floodedness": {"type": "minecraft:noise", "noise": "minecraft:aquifer_fluid_level_floodedness",
                                        "xz_scale": 1.0, "y_scale": 0.67},
            "fluid_level_spread": {"type": "minecraft:noise", "noise": "minecraft:aquifer_fluid_level_spread",
                                   "xz_scale": 1.0, "y_scale": 0.7142857142857143},
            "lava": {"type": "minecraft:noise", "noise": "minecraft:aquifer_lava", "xz_scale": 1.0, "y_scale": 1.0},
            # No aquifers in the deep dark, as in vanilla.
            "exclusion": {"type": "minecraft:min",
                          "left": {"type": "minecraft:sub", "left": -0.225, "right": erosion},
                          "right": {"type": "minecraft:max",
                                    "left": {"type": "minecraft:sub", "left": depth, "right": 0.9}, "right": 0.0}},
            "surface_level": ref(scale, "aquifer_surface"),
        },
        # Also how DiffusicraftWorldgen.isDiffusicraft tells Diffusicraft's noise settings apart.
        "material_rule": f"{NS}:{scale}",
        # WorldSpawn places the spawn; an empty target keeps vanilla from searching for one.
        "spawn_target": [],
        "debug_functions": [
            {"function": ref(scale, "final_density"), "label": "N"},
            {"function": ref(scale, "temperature"), "label": "T"},
            {"function": ref(scale, "vegetation"), "label": "V"},
            {"function": ref(scale, "continents"), "label": "C"},
            {"function": ref(scale, "erosion"), "label": "E"},
            {"function": ref(scale, "depth"), "label": "D"},
            {"function": ref(scale, "ridges"), "label": "W"},
            {"function": ref(scale, "surface"), "label": "S"},
        ],
    }


def material_rule(scale):
    """Vanilla's overworld rule with the underground replaced by Diffusicraft's geology (see StrataRule), the
    rivers' water and beds (see RiverRule), and the ground of Diffusicraft's biomes (written by biomes.py): on the
    surface ahead of vanilla's, and in the Grand Hollows.

    Other mods' biomes come first in the surface (see OtherModsRule), with vanilla's rule as those mods leave it and
    the rules they add; everywhere else the rule reads vanilla's own copies (see vanilla_copies.py). The rule is a
    diffusicraft:world, which other mods' additions see as nothing."""
    return {"type": f"{NS}:world", "rule": {"type": "minecraft:sequence", "sequence": [
        "minecraft:bedrock_floor",
        {"type": f"{NS}:river", "scale": scale},
        "minecraft:overworld/copper_ore_vein",
        "minecraft:overworld/iron_ore_vein",
        {"type": f"{NS}:strata", "scale": scale, "surface": {"type": "minecraft:sequence", "sequence": [
            {"type": f"{NS}:other_mods", "rule": {
                "type": "minecraft:condition", "if_true": {"type": "minecraft:above_preliminary_surface"},
                "then_run": "minecraft:overworld/surface"}},
            {"type": "minecraft:condition", "if_true": {"type": "minecraft:above_preliminary_surface"},
             "then_run": {"type": "minecraft:sequence",
                          "sequence": [f"{NS}:biome_surface", "minecraft:overworld/surface"]}},
            {"type": "minecraft:condition", "if_true": {"type": "minecraft:biome", "biome_is": "minecraft:sulfur_caves"},
             "then_run": "minecraft:overworld/sulfur_cave_bands"},
            f"{NS}:biome_caves",
        ]}},
    ]}}


def dimension_type(min_y, height, clouds):
    """The overworld's dimension type, taller."""
    return {
        "ambient_light": 0.0,
        "attributes": {
            "minecraft:audio/ambient_sounds": {"mood": {
                "block_search_extent": 8, "offset": 2.0, "sound": "minecraft:ambient.cave", "tick_delay": 6000}},
            "minecraft:audio/background_music": {
                "creative": {"max_delay": 24000, "min_delay": 12000, "sound": "minecraft:music.creative"},
                "default": {"max_delay": 24000, "min_delay": 12000, "sound": "minecraft:music.game"}},
            "minecraft:gameplay/bed_rule": {"can_set_spawn": "always", "can_sleep": "when_dark",
                                            "error_message": {"translate": "block.minecraft.bed.no_sleep"}},
            "minecraft:gameplay/nether_portal_spawns_piglin": True,
            "minecraft:gameplay/respawn_anchor_works": False,
            "minecraft:gameplay/straw_bed_rule": {"can_set_spawn": "never", "can_sleep": "when_dark",
                                                  "destroy_on_leave": True,
                                                  "error_message": {"translate": "block.minecraft.bed.no_sleep"}},
            "minecraft:visual/ambient_light_color": "#0a0a0a",
            "minecraft:visual/cloud_color": "#ccffffff",
            "minecraft:visual/cloud_height": clouds,
            "minecraft:visual/fog_color": "#c0d8ff",
            "minecraft:visual/sky_color": "#78a7ff",
        },
        "coordinate_scale": 1.0,
        "default_clock": "minecraft:overworld",
        "has_ceiling": False,
        "has_ender_dragon_fight": False,
        "has_skylight": True,
        "height": height,
        "infiniburn": "#minecraft:infiniburn_overworld",
        "logical_height": height,
        "min_y": min_y,
        "monster_spawn_block_light_limit": 0,
        "monster_spawn_light_level": {"type": "minecraft:uniform", "max_inclusive": 7, "min_inclusive": 0},
        "timelines": "#minecraft:in_overworld",
    }


def world_preset(scale, dim_type):
    return {"dimensions": {
        "minecraft:overworld": {"type": dim_type, "generator": {
            "type": "minecraft:noise",
            "biome_source": {"type": f"{NS}:climate", "preset": "minecraft:overworld",
                             "biomes": f"#{NS}:refinements"},
            "settings": f"{NS}:{scale}"}},
        "minecraft:the_end": {"type": "minecraft:the_end", "generator": {
            "type": "minecraft:noise", "biome_source": {"type": "minecraft:the_end"}, "settings": "minecraft:end"}},
        "minecraft:the_nether": {"type": "minecraft:the_nether", "generator": {
            "type": "minecraft:noise", "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"},
            "settings": "minecraft:nether"}},
    }}


def main():
    for scale, (min_y, height, dim_type, max_surface, clouds) in SCALES.items():
        # Density fades to air over the 16 blocks below `top`, which must stay above every surface.
        top = min(max_surface + 21, min_y + height)
        assert max_surface < top - 16, scale
        functions = f"{NS}/worldgen/density_function/{scale}"
        write(f"{functions}/surface.json", {"type": f"{NS}:surface", "scale": scale})
        # Terrain density: positive below the surface, one unit per 16 blocks like vanilla's sloped_cheese. The height
        # comes from a gradient over the whole world: vanilla's minecraft:y stops at y = 4062, where it would leave
        # every taller mountain solid to the top of the world.
        write(f"{functions}/terrain.json", {"type": "minecraft:mul", "right": 0.0625, "left": {
            "type": "minecraft:sub", "left": ref(scale, "surface"),
            "right": gradient(min_y, float(min_y), min_y + height, float(min_y + height))}})
        for field, parameter in CLIMATE.items():
            write(f"{functions}/{field}.json", {"type": f"{NS}:climate", "scale": scale, "parameter": parameter})
        # Vanilla's aquifers only flood a column with the sea where its surface lies 9 or more blocks under sea level;
        # above that they leave pits dry, which would empty every shallow sea. Under the sea they see a surface at
        # least 12 blocks down, so the sea floods everything over its floor.
        write(f"{functions}/aquifer_surface.json", {
            "type": "minecraft:range_choice", "input": ref(scale, "surface"),
            "min_inclusive": -1000000.0, "max_exclusive": float(SEA_LEVEL),
            "when_in_range": {"type": "minecraft:min", "left": ref(scale, "surface"), "right": float(SEA_LEVEL - 12)},
            "when_out_of_range": ref(scale, "surface")})
        write(f"{functions}/hollows.json", {"type": f"{NS}:hollows", "scale": scale, "min_y": min_y})
        write(f"{functions}/caves/cheese.json", cheese(scale))
        write(f"{functions}/caves/entrances.json",
              gated("minecraft:overworld/caves/entrances", f"{NS}:entrance_gate", ENTRANCE_GATE))
        write(f"{functions}/caves/spaghetti_2d.json", gated(
            {"type": "minecraft:add", "left": "minecraft:overworld/caves/spaghetti_2d",
             "right": "minecraft:overworld/caves/spaghetti_roughness_function"},
            f"{NS}:spaghetti_2d_gate", SPAGHETTI_2D_GATE))
        write(f"{functions}/caves/noodle.json", noodle(min_y, top))
        for mode in ("carve", "fill"):
            write(f"{functions}/river_{mode}.json", {"type": f"{NS}:river", "scale": scale, "mode": mode})
            write(f"{functions}/coast_{mode}.json", {"type": f"{NS}:coast", "scale": scale, "mode": mode})
        write(f"{functions}/final_density.json", final_density(scale, min_y, top))
        write(f"{NS}/worldgen/material_rule/{scale}.json", material_rule(scale))
        write(f"{NS}/worldgen/noise_settings/{scale}.json", noise_settings(scale, min_y, height))
        write(f"{NS}/worldgen/world_preset/{scale}.json", world_preset(scale, dim_type))
        if clouds is not None:
            write(f"{NS}/dimension_type/{scale}.json", dimension_type(min_y, height, clouds))
    # Single octaves, as vanilla's noodle toggle, about 250 blocks across.
    for gate in ("entrance_gate", "spaghetti_2d_gate"):
        write(f"{NS}/worldgen/noise/{gate}.json", {"base_amplitude": 0.955388882960065, "base_octave": -8})
    write("minecraft/tags/worldgen/world_preset/normal.json", {"replace": False, "values": [f"{NS}:{DEFAULT}"]})
    # TerraBlender and FrozenLib add their mods' biomes and rules only in dimensions of these types, which list
    # vanilla's overworld alone.
    taller = [dim_type for _, _, dim_type, _, clouds in SCALES.values() if clouds is not None]
    for tag in ("terrablender/tags/dimension_type/overworld_regions", "frozenlib/tags/dimension_type/overworld"):
        write(f"{tag}.json", {"replace": False, "values": taller})
    vanilla_copies.update()


if __name__ == "__main__":
    main()
