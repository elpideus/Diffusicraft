#!/usr/bin/env python3
"""Writes Diffusicraft's biomes: the ones the climate refines vanilla's picks into (see BiomeRefiner.java), their trees
and plants, their ground (a material rule) and their tags, the giant trees that grow as structures, and the spreads of
the villages and pillager outposts.

The files are committed; run this after changing a biome and commit the result:

    python3 tools/biomes.py [path/to/minecraft-common.jar]

The jar defaults to the one Loom unpacked for the Minecraft version in gradle.properties. Each new biome starts from a
vanilla template, whose underground features, carvers and monsters it keeps, so caves and ores look the same under
every biome. The game requires every biome to list its features in one global order, which other mods' biomes share:
see features_of() for how these biomes stay out of their way. See docs/WORLDGEN.md.
"""
import json
import sys
import zipfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
ROOT = PROJECT / "src" / "main" / "resources" / "data"
NS = "diffusicraft"

# GenerationStep.Decoration, in order.
STEPS = 11
LOCAL_MODIFICATIONS, VEGETAL_DECORATION, TOP_LAYER_MODIFICATION = 2, 9, 10


def write(path, data):
    path = ROOT / path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def mc(name):
    return name if ":" in name else f"minecraft:{name}"


def ours(name):
    return f"{NS}:{name}"


# Blocks and placement building blocks, in vanilla's 26.3 format.

def log(block):
    return {"id": mc(block), "properties": {"axis": "y"}}


def leaves(block):
    return {"id": mc(block), "properties": {"distance": "7", "persistent": "false", "waterlogged": "false"}}


def uniform(lo, hi):
    return {"type": "minecraft:uniform", "min_inclusive": lo, "max_inclusive": hi}


def weighted(entries):
    """Blocks by weight: a name, or a state such as `leaves` makes."""
    return {"type": "minecraft:weighted", "entries": [
        {"data": block if isinstance(block, dict) else mc(block), "weight": w} for block, w in entries]}


def straight(base, a, b):
    return {"type": "minecraft:straight_trunk_placer", "base_height": base, "height_rand_a": a, "height_rand_b": b}


def two_layers(limit=1, lower=0, upper=1):
    return {"type": "minecraft:two_layers_feature_size", "limit": limit, "lower_size": lower, "upper_size": upper}


def tree(trunk, trunk_placer, foliage, foliage_placer, size, decorators=(), ignore_vines=True,
         below="minecraft:soil_beneath_tree"):
    return {"type": "minecraft:tree", "below_trunk_provider": below, "decorators": list(decorators),
            "foliage_placer": foliage_placer, "foliage_provider": leaves(foliage), "ignore_vines": ignore_vines,
            "minimum_size": size, "trunk_placer": trunk_placer, "trunk_provider": log(trunk)}


def simple(entries):
    return {"type": "minecraft:simple_block", "to_place": weighted(entries)}


def boulder(state, extra=()):
    ground = {"type": "minecraft:matching_block_tag", "tag": "minecraft:forest_rock_can_place_on"}
    if extra:
        ground = {"type": "minecraft:any_of", "predicates": [
            ground, {"type": "minecraft:matching_blocks", "blocks": [mc(b) for b in extra]}]}
    return {"type": "minecraft:block_blob", "can_place_on": ground, "state": mc(state)}


def placed(feature, placement):
    return {"feature": feature, "placement": placement}


def survives(sapling):
    return {"type": "minecraft:block_predicate_filter",
            "predicate": {"type": "minecraft:would_survive", "state": mc(sapling)}}


def checked(feature, sapling):
    return placed(feature, [survives(sapling)])


def per_chunk(counts):
    """A count drawn from {count: weight}."""
    return {"type": "minecraft:count", "count": {"type": "minecraft:weighted_list", "distribution": [
        {"data": n, "weight": w} for n, w in counts.items()]}}


def trees(feature, counts, sapling):
    return placed(feature, [per_chunk(counts), {"type": "minecraft:in_square"},
                            {"type": "minecraft:surface_water_depth_filter", "max_water_depth": 0},
                            {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"}, survives(sapling),
                            {"type": "minecraft:biome"}])


def groves(feature, count, sapling, size=48, offset=-0.3):
    """Trees in groves with open ground between them: `count` tries per chunk, kept only where vanilla's
    biome-information noise, `size` blocks to its unit, passes -`offset`. The noise count rounds noise + offset up, so
    it is one try inside a grove and none outside it."""
    return placed(feature, [{"type": "minecraft:count", "count": count}, {"type": "minecraft:in_square"},
                            {"type": "minecraft:noise_based_count", "noise_to_count_ratio": 1, "noise_factor": size,
                             "noise_offset": offset},
                            {"type": "minecraft:surface_water_depth_filter", "max_water_depth": 0},
                            {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"}, survives(sapling),
                            {"type": "minecraft:biome"}])


def patch(feature, tries, spread=7, chance=None, count=None, on=None, heightmap="MOTION_BLOCKING", survive=None):
    """Vanilla's patch: `count` centers per chunk, or one in `chance` chunks, each trying `tries` spots around it.
    `on` lists the blocks the spot must stand on; `survive` names a block that must be able to stand there."""
    placement = []
    if chance:
        placement.append({"type": "minecraft:rarity_filter", "chance": chance})
    if count:
        placement.append({"type": "minecraft:count", "count": count})
    trapezoid = {"type": "minecraft:trapezoid", "min": -spread, "max": spread, "plateau": 0}
    free = {"type": "minecraft:matching_block_tag", "tag": "minecraft:air"}
    if on:
        free = {"type": "minecraft:all_of", "predicates": [free, {
            "type": "minecraft:matching_blocks", "blocks": [mc(b) for b in on], "offset": [0, -1, 0]}]}
    if survive:
        free = {"type": "minecraft:all_of", "predicates": [free, {"type": "minecraft:would_survive",
                                                                  "state": mc(survive)}]}
    placement += [{"type": "minecraft:in_square"}, {"type": "minecraft:heightmap", "heightmap": heightmap},
                  {"type": "minecraft:biome"}, {"type": "minecraft:count", "count": tries},
                  {"type": "minecraft:offset", "x": trapezoid, "z": trapezoid,
                   "y": {"type": "minecraft:trapezoid", "min": -3, "max": 3, "plateau": 0}},
                  {"type": "minecraft:block_predicate_filter", "predicate": free}]
    return placed(feature, placement)


def scattered(feature, chance=None, count=None):
    """Features that find their own ground, such as boulders and moss: one in `chance` chunks or `count` per chunk.
    They start under the leaves, so moss grows on the forest floor rather than on the canopy."""
    placement = []
    if chance:
        placement.append({"type": "minecraft:rarity_filter", "chance": chance})
    if count:
        placement.append({"type": "minecraft:count", "count": count})
    return placed(feature, placement + [{"type": "minecraft:in_square"},
                                        {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING_NO_LEAVES"},
                                        {"type": "minecraft:biome"}])


def giants(feature, chance=None, counts=None, lone=False):
    """Giant trees: one in `chance` chunks, or a count drawn from `counts`, each near the middle of its chunk, where
    its crown has the most room (see GiantTreeFeature.java). `lone` giants grow in vanilla's biomes, and only in
    Diffusicraft worlds."""
    placement = [{"type": f"{NS}:diffusicraft_world"}] if lone else []
    if chance:
        placement.append({"type": "minecraft:rarity_filter", "chance": chance})
    if counts:
        placement.append(per_chunk(counts))
    return placed(feature, placement + [{"type": "minecraft:offset", "x": uniform(4, 11), "y": 0, "z": uniform(4, 11)},
                                        {"type": "minecraft:surface_water_depth_filter", "max_water_depth": 0},
                                        {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"},
                                        {"type": "minecraft:biome"}])


def lianas(feature, lone=False):
    """Lianas: once per chunk, from its corner, over all its columns (see LianaFeature.java). `lone` ones grow in
    vanilla's biomes, and only in Diffusicraft worlds."""
    placement = [{"type": f"{NS}:diffusicraft_world"}] if lone else []
    return placed(feature, placement + [{"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING"},
                                        {"type": "minecraft:biome"}])


def canopy(feature, count, depth=16):
    """Free vines among the crowns: `count` tries per chunk, down to `depth` blocks below the top of the canopy.
    Vanilla's jungles scatter theirs between y = 64 and 100, which misses the crowns of most jungles that stand higher."""
    return placed(feature, [{"type": "minecraft:count", "count": count}, {"type": "minecraft:in_square"},
                            {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING"},
                            {"type": "minecraft:offset", "x": 0, "y": uniform(-depth, -1), "z": 0},
                            {"type": "minecraft:biome"}])


def hollow(feature, surface, count):
    """Features of the Grand Hollows' floors or ceilings: `count` columns per chunk, each moved onto a floor or a
    ceiling of the Grand Hollow it crosses (see HollowSurfacePlacement.java). Vanilla's way of finding cave floors
    misses nearly all of a hollow a hundred blocks tall."""
    return placed(feature, [{"type": "minecraft:count", "count": count}, {"type": "minecraft:in_square"},
                            {"type": f"{NS}:hollow_surface", "surface": surface}, {"type": "minecraft:biome"}])


def hollow_patch(feature, count, tries, spread=6):
    """A patch of plants around `count` spots per chunk on the floors of the Grand Hollows."""
    trapezoid = {"type": "minecraft:trapezoid", "min": -spread, "max": spread, "plateau": 0}
    return placed(feature, hollow(feature, "floor", count)["placement"] + [
        {"type": "minecraft:count", "count": tries},
        {"type": "minecraft:offset", "x": trapezoid, "z": trapezoid,
         "y": {"type": "minecraft:trapezoid", "min": -2, "max": 2, "plateau": 0}},
        {"type": "minecraft:block_predicate_filter",
         "predicate": {"type": "minecraft:matching_block_tag", "tag": "minecraft:air"}}])


# Configured features. Names are diffusicraft's unless they carry a namespace.
FEATURES = {
    # Krummholz: spruce bent into knee-high mats by the wind at the tree line.
    "krummholz": tree("spruce_log", straight(1, 1, 0), "spruce_leaves",
                      {"type": "minecraft:bush_foliage_placer", "height": 2, "offset": 1, "radius": 2},
                      two_layers(0, 0, 0)),
    # Dwarf birch and willow scrub of the tundra.
    "tundra_shrub": tree("birch_log", straight(1, 0, 0), "birch_leaves",
                         {"type": "minecraft:bush_foliage_placer", "height": 2, "offset": 1, "radius": 1},
                         two_layers(0, 0, 0)),
    # Sitka spruce and redwood-sized conifers of the temperate rainforest.
    "giant_spruce": tree("spruce_log", {"type": "minecraft:giant_trunk_placer", "base_height": 20, "height_rand_a": 6,
                                        "height_rand_b": 8}, "spruce_leaves",
                         {"type": "minecraft:mega_pine_foliage_placer", "crown_height": uniform(14, 20), "offset": 0,
                          "radius": 0}, two_layers(1, 1, 2),
                         [{"type": "minecraft:alter_ground", "provider": "minecraft:podzol_beneath_tree"}], False),
    # Broad, vine-hung canopy trees of the cloud forest.
    "cloud_forest_tree": tree("jungle_log", straight(5, 3, 2), "jungle_leaves",
                              {"type": "minecraft:blob_foliage_placer", "height": 3, "offset": 0, "radius": 3},
                              two_layers(1, 0, 1),
                              [{"type": "minecraft:trunk_vine"}, {"type": "minecraft:leave_vine", "probability": 0.3}],
                              False),
    # Crooked, moss-draped oaks of the cloud forest's ridges.
    "gnarled_oak": tree("oak_log", {"type": "minecraft:bending_trunk_placer", "base_height": 5, "height_rand_a": 2,
                                    "height_rand_b": 0, "bend_length": uniform(1, 3), "min_height_for_leaves": 3},
                        "oak_leaves", {"type": "minecraft:random_spread_foliage_placer", "radius": 3, "offset": 0,
                                       "foliage_height": 2, "leaf_placement_attempts": 60},
                        two_layers(), [{"type": "minecraft:leave_vine", "probability": 0.2}], False),
    # Olives and holm oaks: short, forked, with flat grey-green crowns.
    "olive": tree("oak_log", {"type": "minecraft:forking_trunk_placer", "base_height": 3, "height_rand_a": 1,
                              "height_rand_b": 1}, "oak_leaves",
                  {"type": "minecraft:acacia_foliage_placer", "radius": 2, "offset": 0}, two_layers(1, 0, 2)),
    # Stone pines, the Mediterranean's umbrellas.
    "stone_pine": tree("spruce_log", {"type": "minecraft:forking_trunk_placer", "base_height": 6, "height_rand_a": 2,
                                      "height_rand_b": 2}, "spruce_leaves",
                       {"type": "minecraft:acacia_foliage_placer", "radius": 3, "offset": 0}, two_layers(1, 0, 2)),
    # Italian cypresses: tall, narrow columns.
    "cypress": tree("spruce_log", straight(9, 3, 0), "spruce_leaves",
                    {"type": "minecraft:spruce_foliage_placer", "radius": 1, "offset": uniform(0, 1),
                     "trunk_height": uniform(1, 2)}, two_layers(2, 0, 1)),
    # Waist-high evergreen scrub: the maquis itself.
    "maquis_shrub": tree("oak_log", straight(1, 0, 0), "oak_leaves",
                         {"type": "minecraft:bush_foliage_placer", "height": 2, "offset": 1, "radius": 2},
                         two_layers(0, 0, 0)),
    # Teak and sal: tall, straight trunks under broad crowns, bare in the dry season.
    "teak": tree("jungle_log", straight(9, 3, 2), "jungle_leaves",
                 {"type": "minecraft:fancy_foliage_placer", "height": 3, "offset": 2, "radius": 3}, two_layers(1, 0, 1)),
    # Laurels, camphors and evergreen oaks: tall trunks under dense, rounded crowns of glossy leaves.
    "laurel": tree("oak_log", straight(5, 2, 1), "oak_leaves",
                   {"type": "minecraft:fancy_foliage_placer", "height": 4, "offset": 2, "radius": 3},
                   two_layers(1, 0, 1)),
    # Live oaks: short, massive trunks under crowns wider than they are tall, hung with grey moss.
    "live_oak": tree("dark_oak_log", {"type": "minecraft:dark_oak_trunk_placer", "base_height": 4,
                                      "height_rand_a": 2, "height_rand_b": 1}, "oak_leaves",
                     {"type": "minecraft:dark_oak_foliage_placer", "radius": 0, "offset": 0},
                     {"type": "minecraft:three_layers_feature_size", "upper_size": 2},
                     [{"type": "minecraft:pale_moss", "ground_probability": 0.0, "leaves_probability": 0.15,
                       "trunk_probability": 0.0}]),
    # Frailejones: woolly, silver-green rosettes held up on trunks clad in their own dead leaves, a few feet tall.
    "frailejon": tree("pale_oak_log", straight(1, 2, 0), "pale_oak_leaves",
                      {"type": "minecraft:blob_foliage_placer", "height": 1, "offset": 0, "radius": 2},
                      two_layers(0, 0, 0)),
    # Polylepis, the queñua: gnarled little trees of many stems, with red, peeling bark, which grow higher than any
    # other.
    "polylepis": tree("mangrove_log", {"type": "minecraft:forking_trunk_placer", "base_height": 3, "height_rand_a": 1,
                                       "height_rand_b": 1},
                      "mangrove_leaves", {"type": "minecraft:random_spread_foliage_placer", "radius": 2, "offset": 0,
                                          "foliage_height": 2, "leaf_placement_attempts": 50}, two_layers()),
    # Black spruce: thin and spindly, starved on the peat.
    "bog_spruce": tree("spruce_log", straight(4, 2, 1), "spruce_leaves",
                       {"type": "minecraft:spruce_foliage_placer", "radius": 1, "offset": uniform(0, 1),
                        "trunk_height": uniform(1, 2)}, two_layers(2, 0, 1)),
    # Larches: tall, straight trunks under narrow, open cones of soft, light green needles, which they shed for the
    # winter. Their wood is spruce; their needles are oak leaves, which take the biome's light green.
    "larch": tree("spruce_log", straight(9, 3, 2), "oak_leaves",
                  {"type": "minecraft:spruce_foliage_placer", "radius": 2, "offset": uniform(0, 1),
                   "trunk_height": uniform(4, 6)}, two_layers(2, 0, 2)),
    # Mountain birch: low, crooked and often many-stemmed, bent by the wind and the weight of the snow.
    "mountain_birch": tree("birch_log", {"type": "minecraft:bending_trunk_placer", "base_height": 3,
                                         "height_rand_a": 1, "height_rand_b": 0, "bend_length": uniform(1, 2),
                                         "min_height_for_leaves": 2},
                           "birch_leaves", {"type": "minecraft:random_spread_foliage_placer", "radius": 2, "offset": 0,
                                            "foliage_height": 2, "leaf_placement_attempts": 40}, two_layers()),
    # Young larches, and old ones stunted where the frozen ground leaves them a hand's depth of soil.
    "stunted_larch": tree("spruce_log", straight(4, 2, 0), "oak_leaves",
                          {"type": "minecraft:spruce_foliage_placer", "radius": 1, "offset": uniform(0, 1),
                           "trunk_height": uniform(1, 2)}, two_layers(1, 0, 1)),
    # Creosote bush and saltbush: low mounds a few strides apart, each holding its own patch of ground. A radius of 1
    # would leave the stem's top bare half the time, since the placer drops a lone centre leaf at random.
    "desert_shrub": tree("acacia_log", straight(1, 0, 0), "acacia_leaves",
                         {"type": "minecraft:bush_foliage_placer", "height": 1, "offset": 0, "radius": 2},
                         two_layers(0, 0, 0)),
    # Mesquite, palo verde and the thorn trees of the Old World's deserts: low and spreading, with thin, flat crowns.
    "mesquite": tree("acacia_log", {"type": "minecraft:forking_trunk_placer", "base_height": 2, "height_rand_a": 1,
                                    "height_rand_b": 1}, "acacia_leaves",
                     {"type": "minecraft:acacia_foliage_placer", "radius": 2, "offset": 0}, two_layers(1, 0, 2)),
    # Thorn bushes, catclaw, mimosa and the young acacias of the thorn scrub: a dense mound on a stem or two, as tall
    # as a man or a little taller.
    "thorn_bush": tree("acacia_log", straight(2, 1, 0), "acacia_leaves",
                       {"type": "minecraft:bush_foliage_placer", "height": 1, "offset": 0, "radius": 2},
                       two_layers(0, 0, 0)),
    # Baobabs and bottle trees: a fat, bare trunk under a flat crown of short branches, standing over the scrub.
    "baobab": tree("acacia_wood", {"type": "minecraft:giant_trunk_placer", "base_height": 9, "height_rand_a": 3,
                                   "height_rand_b": 0}, "acacia_leaves",
                   {"type": "minecraft:acacia_foliage_placer", "radius": 3, "offset": 0}, two_layers(1, 1, 2)),
    # Junipers: short and bushy on a few crooked, grey stems, their scaly foliage grey-green.
    "juniper": tree("acacia_log", {"type": "minecraft:forking_trunk_placer", "base_height": 2, "height_rand_a": 1,
                                   "height_rand_b": 1}, "oak_leaves",
                    {"type": "minecraft:random_spread_foliage_placer", "radius": 2, "offset": 0,
                     "foliage_height": 3, "leaf_placement_attempts": 45}, two_layers(1, 0, 1)),
    # Pinyon pines: low, with rounded, uneven crowns of dark needles.
    "pinyon": tree("spruce_log", straight(3, 1, 0), "spruce_leaves",
                   {"type": "minecraft:random_spread_foliage_placer", "radius": 2, "offset": 0,
                    "foliage_height": 3, "leaf_placement_attempts": 55}, two_layers(1, 0, 1)),
    "tundra_ground_cover": simple([("short_grass", 4), (ours("cottongrass"), 2), ("fern", 1)]),
    "tundra_moss_vegetation": simple([("moss_carpet", 3), ("short_grass", 2)]),
    "alpine_grass": simple([("short_grass", 6), ("fern", 1)]),
    "alpine_flowers": simple([(ours("edelweiss"), 3), (ours("gentian"), 3), ("azure_bluet", 1), ("oxeye_daisy", 1)]),
    "steppe_grass": simple([("short_grass", 6), ("short_dry_grass", 3), ("tall_dry_grass", 1)]),
    # Wild tulips, poppies and alliums of the spring steppe.
    # Paintbrush and globemallow, wild onion and the odd bluebell among the bunchgrass.
    "juniper_flowers": simple([("orange_tulip", 3), ("red_tulip", 1), ("allium", 2), ("cornflower", 1)]),
    "steppe_flowers": simple([("red_tulip", 2), ("orange_tulip", 2), ("poppy", 3), ("dandelion", 2), ("allium", 1)]),
    # The meadow-steppe's grass: greener and taller than the steppe's.
    "forest_steppe_grass": simple([("short_grass", 6), ("tall_grass", 2), ("short_dry_grass", 1)]),
    # Sage, ox-eye daisies, poppies and buttercups of the meadow-steppe in early summer.
    "forest_steppe_flowers": simple([("cornflower", 3), ("oxeye_daisy", 3), ("poppy", 2), ("dandelion", 2),
                                     ("azure_bluet", 1)]),
    "maquis_grass": simple([("short_grass", 3), ("short_dry_grass", 2)]),
    "maquis_flowers": simple([(ours("lavender"), 4), ("allium", 2), ("poppy", 2), ("cornflower", 1), ("dandelion", 1)]),
    "heather": simple([(ours("heather"), 1)]),
    # Purple moor grass, cottongrass in the wet hollows, bracken.
    "moor_grass": simple([("short_grass", 4), (ours("cottongrass"), 2), ("fern", 1)]),
    "heath_grass": simple([("short_grass", 5), ("fern", 1), ("bush", 1)]),
    # Cottongrass and sedges over the sphagnum.
    "bog_ground_cover": simple([(ours("cottongrass"), 4), ("short_grass", 3), ("moss_carpet", 2), ("fern", 1)]),
    # Grass, Labrador tea and a few ferns under the larches.
    "larch_ground_cover": simple([("short_grass", 4), ("bush", 2), ("fern", 1)]),
    # Reindeer lichen, pale grey-green, in mats between the trees.
    "reindeer_lichen": simple([("pale_moss_carpet", 1)]),
    "monsoon_grass": simple([("short_grass", 3), ("short_dry_grass", 2), ("fern", 1)]),
    "laurel_ground_cover": simple([("fern", 3), ("short_grass", 3), ("bush", 1)]),
    # Camellias and rhododendrons.
    "azaleas": simple([("azalea", 3), ("flowering_azalea", 2)]),
    # Tussock grass of the páramo, green and straw-coloured.
    "paramo_grass": simple([("short_grass", 4), ("short_dry_grass", 2), ("tall_grass", 1), ("fern", 1)]),
    # Gentians, yellow groundsels and blue lupins between the tussocks.
    "paramo_flowers": simple([(ours("gentian"), 3), ("dandelion", 3), ("cornflower", 2), ("oxeye_daisy", 1)]),
    # Ichu: golden bunchgrass from one horizon to the other.
    "puna_grass": simple([("short_dry_grass", 4), ("tall_dry_grass", 3), ("short_grass", 1)]),
    "scrub_grass": simple([("short_dry_grass", 5), ("tall_dry_grass", 2), ("short_grass", 1)]),
    # Bluebunch wheatgrass, needlegrass and straw-coloured cheatgrass between the shrubs.
    "shrub_steppe_grass": simple([("short_dry_grass", 4), ("short_grass", 3), ("tall_dry_grass", 1)]),
    # Big sagebrush, a waist-high mound of silvery leaves; persistent, since no log holds it.
    "sagebrush": simple([({**leaves("oak_leaves"), "properties": {**leaves("oak_leaves")["properties"],
                                                                   "persistent": "true"}}, 1)]),
    # Desert marigolds and sand verbena, after the rain.
    "desert_flowers": simple([("dandelion", 3), ("allium", 1)]),
    # Big bluestem, switchgrass and Indiangrass, head-high by the end of the summer.
    "prairie_grass": simple([("tall_grass", 6), ("short_grass", 3), ("short_dry_grass", 1)]),
    # Blazing stars (alliums), sunflowers, fleabane (ox-eye daisies), goldenrod and black-eyed Susans (dandelions),
    # coneflowers (pink tulips) and spiderwort (cornflowers).
    "prairie_flowers": simple([("allium", 3), ("sunflower", 1), ("oxeye_daisy", 2), ("dandelion", 3),
                               ("pink_tulip", 2), ("cornflower", 1)]),
    # Lilies of the valley and wood anemones, which flower in the spring before the trees come into leaf.
    "woodland_flowers": simple([("lily_of_the_valley", 3), ("azure_bluet", 2)]),
    "boulder": boulder("stone", ["sand", "gravel", "coarse_dirt"]),
    # Yareta: cushions so dense and hard that they look like green boulders. They grow a centimetre a year.
    "yareta": boulder("moss_block", ["gravel", "coarse_dirt", "stone"]),
    "limestone_boulder": boulder(ours("limestone")),
    # The granite tors of the moors.
    "granite_boulder": boulder("granite"),
}
FEATURES["tundra_moss_patch"] = {
    "type": "minecraft:vegetation_patch", "depth": 1, "extra_bottom_block_chance": 0.0,
    "extra_edge_column_chance": 0.3, "ground_state": {"id": "minecraft:moss_block"},
    "replaceable": "#minecraft:moss_replaceable", "surface": "floor", "vegetation_chance": 0.6,
    "vegetation_feature": placed(ours("tundra_moss_vegetation"), []), "vertical_range": 5,
    "xz_radius": uniform(2, 5)}
FEATURES["lichen_patch"] = {
    "type": "minecraft:vegetation_patch", "depth": 1, "extra_bottom_block_chance": 0.0,
    "extra_edge_column_chance": 0.5, "ground_state": {"id": "minecraft:pale_moss_block"},
    "replaceable": "#minecraft:moss_replaceable", "surface": "floor", "vegetation_chance": 0.5,
    "vegetation_feature": placed(ours("reindeer_lichen"), []), "vertical_range": 5, "xz_radius": uniform(2, 4)}
# Pools of dark water sunk into the peat, as on a raised bog. The patch turns the ground into peat, then fills the top
# of it with water wherever the water would be held on every side.
FEATURES["bog_pool"] = {
    "type": "minecraft:waterlogged_vegetation_patch", "depth": 2, "extra_bottom_block_chance": 0.5,
    "extra_edge_column_chance": 0.4, "ground_state": {"id": ours("peat")},
    "replaceable": "#minecraft:lush_ground_replaceable", "surface": "floor", "vegetation_chance": 0.0,
    "vegetation_feature": placed(ours("bog_ground_cover"), []), "vertical_range": 3, "xz_radius": uniform(2, 5)}

# The glowing silk threads that the larvae of fungus gnats let down from the ceilings of the glowworm grottoes: mostly
# short, a few long, each ending in a drop that holds the light.
FEATURES["glowworm_silk"] = {
    "type": "minecraft:block_column", "direction": "down", "prioritize_tip": True,
    "allowed_placement": {"type": "minecraft:matching_block_tag", "tag": "minecraft:air"},
    "layers": [
        {"height": {"type": "minecraft:weighted_list", "distribution": [
            {"data": uniform(0, 2), "weight": 4}, {"data": uniform(2, 5), "weight": 1}]},
         "provider": {"id": ours("glowworm_silk"), "properties": {"tip": "false"}}},
        {"height": 1, "provider": {"id": ours("glowworm_silk"), "properties": {"tip": "true"}}}]}
# Still pools over beds of clay, where the stream that carved the grotto slows down.
FEATURES["grotto_pool"] = {
    "type": "minecraft:waterlogged_vegetation_patch", "depth": 2, "extra_bottom_block_chance": 0.6,
    "extra_edge_column_chance": 0.5, "ground_state": {"id": "minecraft:clay"},
    "replaceable": "#minecraft:lush_ground_replaceable", "surface": "floor", "vegetation_chance": 0.0,
    "vegetation_feature": placed("minecraft:glow_lichen", []), "vertical_range": 4, "xz_radius": uniform(3, 7)}
FEATURES["selenite_beam"] = {"type": f"{NS}:selenite_beam"}
# Cubes of halite, grown into one another out of the floors and ceilings of the salt caverns (see HaliteFeature.java).
FEATURES["halite"] = {"type": f"{NS}:halite", "state": {"id": ours("rock_salt")}}
# Straws of salt, the crust that the brine dripping from the ceiling leaves behind (see SpireFeature.java).
FEATURES["salt_stalactite"] = {"type": f"{NS}:spire", "state": {"id": ours("salt_crust")}, "height": uniform(2, 9),
                               "radius": 0.08}
# Brine pools, sunk into the floor and ringed with salt, like vanilla's sulfur pools.
FEATURES["brine_pool"] = {
    "type": "minecraft:lake", "barrier": {"id": ours("salt_crust")}, "can_place_feature": {"type": "minecraft:true"},
    "can_replace_with_air_or_fluid": {"type": "minecraft:not", "predicate": {
        "type": "minecraft:matching_block_tag", "tag": "minecraft:features_cannot_replace"}},
    "can_replace_with_barrier": {"type": "minecraft:not", "predicate": {
        "type": "minecraft:matching_block_tag", "tag": "minecraft:lava_pool_stone_cannot_replace"}},
    "fluid": {"id": "minecraft:water", "properties": {"level": "0"}}}
# Spires of ice: ice stalagmites rising from the floors of the ice caves, and great icicles hanging from their ceilings
# (see SpireFeature.java). Vanilla's ice spikes fall short of the ground below y = 50.
FEATURES["ice_spire"] = {"type": f"{NS}:spire", "state": {"id": "minecraft:packed_ice"}, "height": uniform(4, 24),
                         "radius": 0.12}
# Icicles: mostly a block or two, the last of clear ice.
FEATURES["icicle"] = {
    "type": "minecraft:block_column", "direction": "down", "prioritize_tip": True,
    "allowed_placement": {"type": "minecraft:matching_block_tag", "tag": "minecraft:air"},
    "layers": [
        {"height": {"type": "minecraft:weighted_list", "distribution": [
            {"data": uniform(0, 1), "weight": 4}, {"data": uniform(2, 4), "weight": 1}]},
         "provider": {"id": "minecraft:packed_ice"}},
        {"height": 1, "provider": {"id": "minecraft:ice"}}]}
# Lianas: the vines that hang from the crowns, grown down towards the floor (see LianaFeature.java).
FEATURES["lianas"] = {"type": f"{NS}:lianas", "chance": 0.6}
FEATURES["sparse_lianas"] = {"type": f"{NS}:lianas", "chance": 0.3}


def undergrowth(cover, leaf="jungle_leaves"):
    """The undergrowth of a rainforest's floor, `cover` thick: thickets of shrubs and ferns, and clearings between them
    (see UndergrowthFeature.java). The shrubs' leaves are persistent, as some have no stem."""
    persistent = leaves(leaf)
    persistent["properties"]["persistent"] = "true"
    return {"type": f"{NS}:undergrowth", "cover": cover, "stem": log("jungle_log"), "leaves": persistent}


FEATURES["undergrowth"] = undergrowth(0.8)
FEATURES["cloud_undergrowth"] = undergrowth(0.6, "oak_leaves")
FEATURES["jungle_undergrowth"] = undergrowth(0.7)
FEATURES["sparse_undergrowth"] = undergrowth(0.35)
# Columnar cacti, saguaros and cardones: twice a man's height, and in flower now and then.
FEATURES["columnar_cactus"] = {
    "type": "minecraft:block_column", "direction": "up", "prioritize_tip": False,
    "allowed_placement": {"type": "minecraft:matching_block_tag", "tag": "minecraft:air"},
    "layers": [
        {"height": uniform(4, 6), "provider": {"id": "minecraft:cactus", "properties": {"age": "0"}}},
        {"height": {"type": "minecraft:weighted_list", "distribution": [
            {"data": 0, "weight": 2}, {"data": 1, "weight": 1}]}, "provider": {"id": "minecraft:cactus_flower"}}]}


def giant(form, wood, foliage, lo, hi):
    """A giant tree of `form`, `lo` to `hi` blocks tall at 1:1, less in smaller worlds (see GiantTreeFeature.java)."""
    return {"type": f"{NS}:giant_tree", "form": form, "wood": log(wood), "leaves": leaves(foliage),
            "height": uniform(lo, hi)}


# Giant trees, fantasy giants of real ones: coast redwoods twice their height, in the red-brown bark of mangrove
# wood; ancient oaks; and kapoks, the emergents of the rainforest. The redwoods' crowns are narrow enough for a
# feature; the oaks and kapoks are structures (see STRUCTURES).
FEATURES["redwood"] = giant("redwood", "mangrove_wood", "spruce_leaves", 110, 230)

# The falls of the rivers where they step down, with pools at their feet (see RiverFallFeature.java).
FEATURES["river_falls"] = {"type": f"{NS}:river_falls", "deepest_pool": 4}
# Boulders: the erratics the ice left in the cold lands, in a chunk of forty at most, and the rocks fallen at the feet
# of rock faces eight blocks high or more (see ErraticFeature.java and RockfallFeature.java).
FEATURES["erratic"] = {"type": f"{NS}:erratic", "chance": 0.025}
FEATURES["rockfall"] = {"type": f"{NS}:rockfall", "min_rise": 8, "chance": 0.35}

# Placed features, in the order they run within a step.
PLACED = {
    "giant_spruce_checked": checked(ours("giant_spruce"), "spruce_sapling"),
    "cloud_forest_tree_checked": checked(ours("cloud_forest_tree"), "jungle_sapling"),
    "gnarled_oak_checked": checked(ours("gnarled_oak"), "oak_sapling"),
    "olive_checked": checked(ours("olive"), "oak_sapling"),
    "stone_pine_checked": checked(ours("stone_pine"), "spruce_sapling"),
    "cypress_checked": checked(ours("cypress"), "spruce_sapling"),
    "maquis_shrub_checked": checked(ours("maquis_shrub"), "oak_sapling"),
    "bog_spruce_checked": checked(ours("bog_spruce"), "spruce_sapling"),
    "mountain_birch_checked": checked(ours("mountain_birch"), "birch_sapling"),
    "krummholz_checked": checked(ours("krummholz"), "spruce_sapling"),
    "larch_checked": checked(ours("larch"), "spruce_sapling"),
    "stunted_larch_checked": checked(ours("stunted_larch"), "spruce_sapling"),
    "teak_checked": checked(ours("teak"), "jungle_sapling"),
    "laurel_checked": checked(ours("laurel"), "oak_sapling"),
    "live_oak_checked": checked(ours("live_oak"), "dark_oak_sapling"),
    "azalea_tree_checked": checked("minecraft:azalea_tree", "azalea"),
    "frailejon_checked": checked(ours("frailejon"), "pale_oak_sapling"),
    "polylepis_checked": checked(ours("polylepis"), "oak_sapling"),
    "desert_shrub_checked": checked(ours("desert_shrub"), "acacia_sapling"),
    "mesquite_checked": checked(ours("mesquite"), "acacia_sapling"),
    "thorn_bush_checked": checked(ours("thorn_bush"), "acacia_sapling"),
    "juniper_checked": checked(ours("juniper"), "oak_sapling"),
    "pinyon_checked": checked(ours("pinyon"), "spruce_sapling"),
    "boulders": scattered(ours("boulder"), chance=4),
    "boulders_rare": scattered(ours("boulder"), chance=12),
    "limestone_boulders": scattered(ours("limestone_boulder"), chance=5),
    "granite_tors": scattered(ours("granite_boulder"), chance=5),
    "bog_pools": scattered(ours("bog_pool"), count=3),
    "paramo_pools": scattered(ours("bog_pool"), chance=3),
    "yaretas": scattered(ours("yareta"), chance=3),
    "trees_tundra": trees(ours("tundra_shrub"), {0: 3, 1: 2, 2: 1}, "birch_sapling"),
    "trees_alpine_tundra": trees(ours("krummholz"), {0: 4, 1: 2, 2: 1}, "spruce_sapling"),
    "trees_temperate_rainforest": trees(ours("trees_temperate_rainforest"), {12: 9, 14: 1}, "spruce_sapling"),
    "trees_cloud_forest": trees(ours("trees_cloud_forest"), {12: 9, 14: 1}, "jungle_sapling"),
    "trees_maquis": trees(ours("trees_maquis"), {1: 3, 2: 3, 3: 2, 4: 1}, "oak_sapling"),
    # An open woodland, a few strides between the trees, as dense as the rock lets it be.
    "trees_juniper_woodland": trees(ours("trees_juniper_woodland"), {1: 2, 2: 3, 3: 3, 5: 2}, "oak_sapling"),
    "trees_steppe": trees(ours("trees_steppe"), {0: 24, 1: 1}, "oak_sapling"),
    "trees_moorland": trees(ours("trees_moorland"), {0: 14, 1: 1}, "birch_sapling"),
    "trees_heath": trees(ours("trees_heath"), {0: 4, 1: 2, 2: 1}, "birch_sapling"),
    "trees_bog": trees(ours("trees_bog"), {0: 6, 1: 3, 2: 1}, "spruce_sapling"),
    "trees_larch_taiga": trees(ours("trees_larch_taiga"), {3: 2, 5: 4, 7: 3, 9: 1}, "spruce_sapling"),
    "trees_monsoon_forest": trees(ours("trees_monsoon_forest"), {4: 3, 6: 4, 8: 2}, "jungle_sapling"),
    "trees_laurel_forest": trees(ours("trees_laurel_forest"), {8: 9, 10: 1}, "oak_sapling"),
    "trees_paramo": trees(ours("trees_paramo"), {0: 2, 1: 3, 2: 3, 4: 2}, "oak_sapling"),
    "trees_puna": trees(ours("polylepis"), {0: 16, 1: 1}, "oak_sapling"),
    "trees_desert_scrub": trees(ours("trees_desert_scrub"), {1: 2, 2: 3, 3: 3, 4: 1}, "acacia_sapling"),
    # Thickets with bare ground and dry grass between them, a few bushes standing alone, and a baobab every few chunks.
    "trees_thorn_scrub": groves(ours("trees_thorn_scrub"), 8, "acacia_sapling", size=32, offset=-0.2),
    "lone_trees_thorn_scrub": trees(ours("trees_thorn_scrub"), {0: 2, 1: 3, 2: 2}, "acacia_sapling"),
    "baobabs": trees(ours("baobab"), {0: 7, 1: 1}, "acacia_sapling"),
    # Bur oaks standing alone in the grass, a chunk in twenty.
    "trees_prairie": trees(ours("trees_prairie"), {0: 19, 1: 1}, "oak_sapling"),
    "trees_mixed_forest": trees(ours("trees_mixed_forest"), {10: 9, 11: 1}, "spruce_sapling"),
    # Under the giants: a few spruces, firs and fallen trunks; oaks and birches far apart, as in a wood pasture; and
    # the jungle's own trees, thinned out by the shade.
    "trees_redwood_forest": trees(ours("trees_redwood_forest"), {3: 3, 4: 2, 6: 1}, "spruce_sapling"),
    "trees_ancient_woodland": trees(ours("trees_ancient_woodland"), {1: 2, 2: 3, 3: 1}, "oak_sapling"),
    "trees_old_growth_jungle": trees(ours("trees_old_growth_jungle"), {20: 9, 24: 1}, "jungle_sapling"),
    "trees_forest_steppe": groves(ours("trees_forest_steppe"), 16, "birch_sapling"),
    "lone_trees_forest_steppe": trees(ours("trees_forest_steppe"), {0: 3, 1: 1}, "birch_sapling"),
    # Clumps of trees where the snow drifts deepest and shelters them, with open tundra between.
    "trees_forest_tundra": groves(ours("trees_forest_tundra"), 12, "spruce_sapling", size=24, offset=-0.2),
    "lone_trees_forest_tundra": trees(ours("trees_forest_tundra"), {0: 2, 1: 2, 2: 1}, "spruce_sapling"),
    "patch_tundra_grass": patch(ours("tundra_ground_cover"), 24, count=3),
    "tundra_moss": scattered(ours("tundra_moss_patch"), chance=2),
    "rainforest_moss": scattered("minecraft:moss_patch", count=1),
    "patch_alpine_grass": patch(ours("alpine_grass"), 32, count=4),
    "patch_alpine_flowers": patch(ours("alpine_flowers"), 48, spread=6, chance=2),
    "patch_steppe_grass": patch(ours("steppe_grass"), 32, count=6),
    "patch_juniper_flowers": patch(ours("juniper_flowers"), 24, chance=2),
    "patch_steppe_flowers": patch(ours("steppe_flowers"), 48, chance=3),
    "patch_forest_steppe_grass": patch(ours("forest_steppe_grass"), 32, count=10),
    "patch_forest_steppe_flowers": patch(ours("forest_steppe_flowers"), 48, spread=6, count=1),
    "patch_maquis_grass": patch(ours("maquis_grass"), 32, count=3),
    "patch_maquis_bush": patch("minecraft:bush", 24, spread=5, count=2),
    "patch_maquis_flowers": patch(ours("maquis_flowers"), 48, spread=6, chance=2),
    "patch_sagebrush": patch("minecraft:bush", 12, spread=6, chance=3),
    # Sagebrush a stride or two apart: the low bushes, and mounds of leaves that stand on the ground, never in the air.
    "patch_sage": patch("minecraft:bush", 16, spread=6, count=3),
    "patch_sage_mounds": patch(ours("sagebrush"), 12, spread=6, count=2,
                               on=["grass_block", "coarse_dirt", "gravel", "dirt"]),
    "patch_shrub_steppe_grass": patch(ours("shrub_steppe_grass"), 32, count=4),
    "patch_heather_moor": patch(ours("heather"), 64, count=4),
    "patch_moor_grass": patch(ours("moor_grass"), 32, count=3),
    "patch_heather_heath": patch(ours("heather"), 64, count=3),
    "patch_heath_grass": patch(ours("heath_grass"), 32, count=2),
    "patch_bog_ground_cover": patch(ours("bog_ground_cover"), 32, count=4),
    "patch_heather_bog": patch(ours("heather"), 32, count=1),
    "patch_larch_ground_cover": patch(ours("larch_ground_cover"), 32, count=3),
    # Lingonberries, as sweet berries.
    "patch_lingonberries": patch("minecraft:berry_bush", 32, spread=6, chance=3),
    "lichen": scattered(ours("lichen_patch"), count=1),
    "patch_monsoon_grass": patch(ours("monsoon_grass"), 32, count=4),
    # The dry season's fallen leaves.
    "patch_monsoon_leaf_litter": patch("minecraft:leaf_litter", 48, count=3),
    "patch_laurel_ground_cover": patch(ours("laurel_ground_cover"), 32, count=4),
    "patch_azaleas": patch(ours("azaleas"), 24, spread=5, count=2),
    "patch_laurel_leaf_litter": patch("minecraft:leaf_litter", 48, count=2),
    "patch_paramo_grass": patch(ours("paramo_grass"), 48, count=5),
    "patch_paramo_flowers": patch(ours("paramo_flowers"), 32, spread=6, chance=2),
    "patch_puna_grass": patch(ours("puna_grass"), 48, count=6),
    "patch_desert_bush": patch("minecraft:bush", 16, spread=6, count=1),
    "patch_scrub_grass": patch(ours("scrub_grass"), 32, count=2),
    "patch_desert_flowers": patch(ours("desert_flowers"), 32, spread=6, chance=6),
    "patch_thorn_scrub_grass": patch(ours("scrub_grass"), 32, count=4),
    "patch_thorn_scrub_bush": patch("minecraft:bush", 24, spread=5, count=2),
    "patch_prairie_grass": patch(ours("prairie_grass"), 64, count=8),
    "patch_prairie_flowers": patch(ours("prairie_flowers"), 48, spread=6, count=2),
    # Cacti grow only on the sand.
    "columnar_cacti": patch(ours("columnar_cactus"), 3, spread=6, chance=3, survive="cactus"),
    "patch_woodland_flowers": patch(ours("woodland_flowers"), 32, spread=6, chance=3),
    "glowworm_silk_ceilings": hollow(ours("glowworm_silk"), "ceiling", 48),
    "grotto_pools": hollow(ours("grotto_pool"), "floor", 2),
    "selenite_beams_floor": hollow(ours("selenite_beam"), "floor", 2),
    "selenite_beams_ceiling": hollow(ours("selenite_beam"), "ceiling", 1),
    "halite_floor": hollow(ours("halite"), "floor", 3),
    "halite_ceiling": hollow(ours("halite"), "ceiling", 2),
    "salt_stalactites": hollow(ours("salt_stalactite"), "ceiling", 16),
    "brine_pools": hollow(ours("brine_pool"), "floor", 3),
    "ice_spires_floor": hollow(ours("ice_spire"), "floor", 4),
    "ice_spires_ceiling": hollow(ours("ice_spire"), "ceiling", 4),
    "icicles": hollow(ours("icicle"), "ceiling", 32),
    "giant_mushrooms": hollow("minecraft:mushroom_island_vegetation", "floor", 4),
    "hollow_brown_mushrooms": hollow_patch("minecraft:brown_mushroom", 4, 24),
    "hollow_red_mushrooms": hollow_patch("minecraft:red_mushroom", 2, 24),
    # Vanilla's cave plants, dripstone and sulfur, placed for the Grand Hollows. Besides the biomes here, Java adds them
    # to vanilla's lush, dripstone and sulfur caves (see DiffusicraftWorldgen.addHollowFeatures), in this order.
    "hollow_dripstone_floor": hollow("minecraft:pointed_dripstone", "floor", 24),
    "hollow_dripstone_ceiling": hollow("minecraft:pointed_dripstone", "ceiling", 24),
    "hollow_moss_floor": hollow("minecraft:moss_patch", "floor", 8),
    "hollow_moss_ceiling": hollow("minecraft:moss_patch_ceiling", "ceiling", 8),
    "hollow_lush_clay": hollow("minecraft:lush_caves_clay", "floor", 3),
    "hollow_cave_vines": hollow("minecraft:cave_vine", "ceiling", 32),
    "hollow_spore_blossoms": hollow("minecraft:spore_blossom", "ceiling", 4),
    # Vanilla's sulfur caves: spikes, thickets of them, and pools ringed with sulfur on the floors.
    "hollow_sulfur_spikes_floor": hollow("minecraft:sulfur_spike", "floor", 24),
    "hollow_sulfur_spikes_ceiling": hollow("minecraft:sulfur_spike", "ceiling", 24),
    "hollow_sulfur_clusters": hollow("minecraft:sulfur_spike_cluster", "floor", 3),
    "hollow_sulfur_pools": hollow("minecraft:sulfur_pool", "floor", 2),
    # The redwoods, after every smaller tree, whose crowns they grow through. Groves of them in their own biomes, and
    # lone ones elsewhere: Java adds the lone ones to vanilla's biomes (see DiffusicraftWorldgen.addLoneGiants).
    "redwoods": giants(ours("redwood"), counts={0: 1, 1: 2}),
    "rainforest_redwoods": giants(ours("redwood"), chance=6),
    "lone_redwood": giants(ours("redwood"), chance=16, lone=True),
    # The floors of the rainforests, under the trees and the giants: once per chunk, as the lianas, and in the last
    # step, top_layer_modification, after the oaks and kapoks that grow as structures early in it. The vanilla jungles'
    # undergrowth is only in Diffusicraft worlds: Java adds it (see DiffusicraftWorldgen.addLianas).
    "undergrowth": lianas(ours("undergrowth")),
    "cloud_undergrowth": lianas(ours("cloud_undergrowth")),
    "jungle_undergrowth": lianas(ours("jungle_undergrowth"), lone=True),
    "sparse_jungle_undergrowth": lianas(ours("sparse_undergrowth"), lone=True),
    # After the giants, in the last step too, whose crowns get vines and whose vines grow too, as do the free ones. The
    # vanilla jungles' lianas are only in Diffusicraft worlds: Java adds them (see DiffusicraftWorldgen.addLianas).
    "canopy_vines": canopy("minecraft:vines", 64),
    "lianas": lianas(ours("lianas")),
    "jungle_lianas": lianas(ours("lianas"), lone=True),
    "sparse_jungle_lianas": lianas(ours("sparse_lianas"), lone=True),
    # In no biome: Java places them after vanilla's water springs in Diffusicraft worlds, in this order (see
    # DiffusicraftWorldgen.AFTER_SPRINGS). Each feature picks its place in the chunk itself.
    "river_falls": placed(ours("river_falls"), []),
    "erratics": placed(ours("erratic"), []),
    "rockfall": placed(ours("rockfall"), []),
}
FEATURES["trees_temperate_rainforest"] = {"type": "minecraft:random_selector", "default": "minecraft:spruce_checked",
                                          "features": [
                                              {"chance": 0.3, "feature": ours("giant_spruce_checked")},
                                              {"chance": 0.3, "feature": "minecraft:mega_spruce_checked"},
                                              {"chance": 0.06, "feature": "minecraft:fancy_oak_checked"},
                                              {"chance": 0.06, "feature": "minecraft:fallen_spruce_tree"}]}
FEATURES["trees_redwood_forest"] = {"type": "minecraft:random_selector", "default": "minecraft:spruce_checked",
                                    "features": [
                                        {"chance": 0.25, "feature": "minecraft:mega_spruce_checked"},
                                        {"chance": 0.15, "feature": "minecraft:fallen_spruce_tree"}]}
FEATURES["trees_ancient_woodland"] = {"type": "minecraft:random_selector", "default": "minecraft:oak_checked",
                                      "features": [
                                          {"chance": 0.4, "feature": "minecraft:fancy_oak_checked"},
                                          {"chance": 0.2, "feature": "minecraft:birch_checked"},
                                          {"chance": 0.1, "feature": "minecraft:fallen_oak_tree"}]}
FEATURES["trees_old_growth_jungle"] = {"type": "minecraft:random_selector", "default": "minecraft:jungle_tree",
                                       "features": [
                                           {"chance": 0.3, "feature": "minecraft:mega_jungle_tree_checked"},
                                           {"chance": 0.35, "feature": "minecraft:jungle_bush"},
                                           {"chance": 0.03, "feature": "minecraft:fallen_jungle_tree"}]}
FEATURES["trees_cloud_forest"] = {"type": "minecraft:random_selector", "default": ours("cloud_forest_tree_checked"),
                                  "features": [
                                      {"chance": 0.35, "feature": ours("gnarled_oak_checked")},
                                      {"chance": 0.25, "feature": "minecraft:jungle_bush"},
                                      {"chance": 0.02, "feature": "minecraft:fallen_jungle_tree"}]}
FEATURES["trees_maquis"] = {"type": "minecraft:random_selector", "default": ours("maquis_shrub_checked"),
                            "features": [
                                {"chance": 0.3, "feature": ours("olive_checked")},
                                {"chance": 0.15, "feature": ours("stone_pine_checked")},
                                {"chance": 0.12, "feature": ours("cypress_checked")}]}
# Rowan and birch in the moors' sheltered spots.
FEATURES["trees_moorland"] = {"type": "minecraft:random_selector", "default": "minecraft:birch_checked",
                              "features": [{"chance": 0.3, "feature": "minecraft:spruce_checked"}]}
# Birch, Scots pine and juniper over the heather, and the bees of heather honey.
FEATURES["trees_heath"] = {"type": "minecraft:random_selector", "default": "minecraft:birch_bees_002",
                           "features": [{"chance": 0.35, "feature": "minecraft:pine_checked"},
                                        {"chance": 0.15, "feature": "minecraft:spruce_checked"}]}
# Black spruce, Scots pine and downy birch, few and far between.
FEATURES["trees_bog"] = {"type": "minecraft:random_selector", "default": ours("bog_spruce_checked"),
                         "features": [{"chance": 0.25, "feature": "minecraft:pine_checked"},
                                      {"chance": 0.2, "feature": "minecraft:birch_checked"}]}
# Larches, young and stunted, with birches and Scots pines where the ground thaws deeper, and fallen trunks.
FEATURES["trees_larch_taiga"] = {"type": "minecraft:random_selector", "default": ours("larch_checked"),
                                 "features": [{"chance": 0.25, "feature": ours("stunted_larch_checked")},
                                              {"chance": 0.12, "feature": "minecraft:birch_checked"},
                                              {"chance": 0.08, "feature": "minecraft:pine_checked"},
                                              {"chance": 0.02, "feature": "minecraft:fallen_spruce_tree"}]}
# Teak and sal, thorny acacias where it is driest, and jungle bushes under them.
FEATURES["trees_monsoon_forest"] = {"type": "minecraft:random_selector", "default": ours("teak_checked"),
                                    "features": [{"chance": 0.2, "feature": "minecraft:acacia_checked"},
                                                 {"chance": 0.2, "feature": "minecraft:jungle_bush"},
                                                 {"chance": 0.03, "feature": "minecraft:fallen_jungle_tree"}]}
# Laurels and live oaks, azalea trees for the camellias and rhododendrons, and fallen trunks.
FEATURES["trees_laurel_forest"] = {"type": "minecraft:random_selector", "default": ours("laurel_checked"),
                                   "features": [{"chance": 0.3, "feature": ours("live_oak_checked")},
                                                {"chance": 0.1, "feature": ours("azalea_tree_checked")},
                                                {"chance": 0.1, "feature": "minecraft:jungle_bush"},
                                                {"chance": 0.03, "feature": "minecraft:fallen_oak_tree"}]}
# Frailejones, and here and there a queñua.
FEATURES["trees_paramo"] = {"type": "minecraft:random_selector", "default": ours("frailejon_checked"),
                            "features": [{"chance": 0.05, "feature": ours("polylepis_checked")}]}
# Creosote bush and saltbush, and mesquite here and there.
FEATURES["trees_desert_scrub"] = {"type": "minecraft:random_selector", "default": ours("desert_shrub_checked"),
                                  "features": [{"chance": 0.12, "feature": ours("mesquite_checked")}]}
# Thorn bushes, low thorny mounds and mesquites, with now and then an acacia standing over them.
FEATURES["trees_thorn_scrub"] = {"type": "minecraft:random_selector", "default": ours("thorn_bush_checked"),
                                 "features": [{"chance": 0.3, "feature": ours("desert_shrub_checked")},
                                              {"chance": 0.25, "feature": ours("mesquite_checked")},
                                              {"chance": 0.06, "feature": "minecraft:acacia_checked"}]}
# Bur oaks, broad and low, which the prairie's fires do not kill.
FEATURES["trees_prairie"] = {"type": "minecraft:random_selector", "default": "minecraft:oak_checked",
                             "features": [{"chance": 0.4, "feature": "minecraft:fancy_oak_checked"}]}
# Norway spruce and Scots pine, with birches and aspens, and oaks, limes and maples, which drop their leaves on the
# needles: the conifers and the broadleaves each make up about half of the trees. Now and then a fallen trunk.
FEATURES["trees_mixed_forest"] = {"type": "minecraft:random_selector", "default": "minecraft:spruce_checked",
                                  "features": [{"chance": 0.3, "feature": "minecraft:birch_leaf_litter"},
                                               {"chance": 0.2, "feature": "minecraft:pine_checked"},
                                               {"chance": 0.25, "feature": "minecraft:oak_leaf_litter"},
                                               {"chance": 0.05, "feature": "minecraft:fancy_oak_leaf_litter"},
                                               {"chance": 0.02, "feature": "minecraft:fallen_spruce_tree"},
                                               {"chance": 0.02, "feature": "minecraft:fallen_birch_tree"}]}
# Birches and aspens, and oaks where the black earth is deepest, as in the Ukrainian forest-steppe. Now and then a
# pine, and a fallen trunk.
FEATURES["trees_forest_steppe"] = {"type": "minecraft:random_selector", "default": "minecraft:birch_leaf_litter",
                                   "features": [{"chance": 0.35, "feature": "minecraft:oak_leaf_litter"},
                                                {"chance": 0.08, "feature": "minecraft:fancy_oak_leaf_litter"},
                                                {"chance": 0.05, "feature": "minecraft:pine_checked"},
                                                {"chance": 0.02, "feature": "minecraft:fallen_birch_tree"}]}
# Black spruce, thin and spindly, with crooked birches and wind-bent mats of spruce, and a taller white spruce in the
# shelter of a clump. Now and then a fallen trunk.
FEATURES["trees_forest_tundra"] = {"type": "minecraft:random_selector", "default": ours("bog_spruce_checked"),
                                   "features": [{"chance": 0.3, "feature": ours("mountain_birch_checked")},
                                                {"chance": 0.12, "feature": ours("krummholz_checked")},
                                                {"chance": 0.08, "feature": "minecraft:spruce_checked"},
                                                {"chance": 0.01, "feature": "minecraft:fallen_spruce_tree"}]}
# Junipers, pinyons and young junipers.
FEATURES["trees_juniper_woodland"] = {"type": "minecraft:random_selector", "default": ours("juniper_checked"),
                                      "features": [{"chance": 0.35, "feature": ours("pinyon_checked")},
                                                   {"chance": 0.25, "feature": ours("maquis_shrub_checked")}]}
FEATURES["trees_steppe"] = {"type": "minecraft:random_selector", "default": "minecraft:oak_checked",
                            "features": [{"chance": 0.4, "feature": "minecraft:birch_checked"}]}


def great_tree(form, wood, foliage, lo, hi, biomes, chance=1.0):
    """A giant tree whose crown spreads wider than a feature may write: a structure, written a chunk at a time (see
    GiantTreeStructure.java). `lo` to `hi` blocks tall at 1:1, less in smaller worlds; in `biomes`, with `chance` in
    each chunk the set gives it. It grows after every smaller tree, whose crowns it grows through."""
    return {"type": f"{NS}:giant_tree", "biomes": biomes, "spawn_overrides": {}, "step": "top_layer_modification",
            "form": form, "wood": log(wood), "leaves": leaves(foliage), "height": uniform(lo, hi), "chance": chance}


# The broadleaf giants: the ancient oaks and the kapoks, in groves in their own biomes and alone in vanilla's, where they
# grow only in Diffusicraft worlds. Each has a biome tag of its own, which lists `biomes`.
STRUCTURES = {
    "giant_oak": great_tree("oak", "oak_wood", "oak_leaves", 48, 80, [ours("ancient_woodland")]),
    "lone_giant_oak": great_tree("oak", "oak_wood", "oak_leaves", 48, 80,
                                 ["minecraft:forest", "minecraft:flower_forest", "minecraft:birch_forest",
                                  "minecraft:dark_forest"], 0.375),
    "lone_giant_oak_plains": great_tree("oak", "oak_wood", "oak_leaves", 48, 80,
                                        ["minecraft:plains", "minecraft:sunflower_plains"], 0.1875),
    "kapok": great_tree("kapok", "jungle_wood", "jungle_leaves", 80, 130, [ours("old_growth_jungle")], 0.7),
    "lone_kapok": great_tree("kapok", "jungle_wood", "jungle_leaves", 80, 130, ["minecraft:jungle"], 0.9),
}
# One set for them all, a chunk in each cell of 3 by 3, so that no two stand closer than a chunk apart: in each biome
# only its own giant grows. Not in a village.
GIANT_TREES = {
    "placement": {"type": "minecraft:random_spread", "exclusion_zone": {"chunk_count": 6,
                                                                        "other_set": ours("villages")},
                  "salt": 1529730183, "separation": 1, "spacing": 3},
    "structures": [{"structure": ours(name), "weight": 1} for name in STRUCTURES]}

# The spreads of the villages and pillager outposts in a Diffusicraft world: vanilla's, three times as wide. Its plains,
# steppes and taigas run on for thousands of blocks, where vanilla's spread puts a village in every cell of 34 by 34
# chunks, one every 400 blocks. These sets list no structures: in a Diffusicraft world vanilla's sets of the same names
# take their placements (see ChunkGeneratorStructureStateMixin.java), so every village vanilla, a datapack or a mod
# lists in them still generates. Vanilla's salts; the outposts keep their distance from these villages.
SPREADS = {
    "villages": {"placement": {"type": "minecraft:random_spread", "salt": 10387312, "separation": 24, "spacing": 102},
                 "structures": []},
    "pillager_outposts": {
        "placement": {"type": "minecraft:random_spread", "exclusion_zone": {"chunk_count": 10,
                                                                            "other_set": ours("villages")},
                      "frequency": 0.2, "frequency_reduction_method": "legacy_type_1", "salt": 165745296,
                      "separation": 24, "spacing": 96},
        "structures": []},
}


def spawn(mob, weight, lo, hi=None):
    return {"type": mc(mob), "count": lo if hi is None else uniform(lo, hi), "weight": weight}


# The tags of the Grand Hollows' biomes, which sit underground like vanilla's cave biomes.
CAVE_TAGS = ["c:is_cave", "c:is_underground", "minecraft:has_structure/mineshaft",
             "minecraft:has_structure/trial_chambers", "minecraft:is_overworld", "minecraft:stronghold_biased_to"]
# The dripstone of the template of the caves that grow none.
DRIPSTONE = ["minecraft:large_dripstone", "minecraft:dripstone_cluster", "minecraft:pointed_dripstone"]

# Each biome: its vanilla template, what it changes, the vegetation it grows and the tags it carries. `fog` is the
# distance at which the fog closes in, in blocks; `without` lists the template's features it leaves out; `top` lists
# the features it adds to the last step, after the giants that grow as structures.
BIOMES = {
    "tundra": {
        "template": "snowy_plains", "temperature": 0.2, "downfall": 0.3,
        "effects": {"grass_color": "#8f9a5a", "foliage_color": "#77874a", "dry_foliage_color": "#8a6b45"},
        "creatures": [spawn("rabbit", 10, 2, 3), spawn("fox", 4, 2, 4), spawn("wolf", 2, 2, 4)],
        "local": ["boulders"],
        "vegetation": ["minecraft:glow_lichen", "trees_tundra", "patch_tundra_grass", "tundra_moss",
                       "minecraft:patch_berry_common", "minecraft:brown_mushroom_normal",
                       "minecraft:red_mushroom_normal"],
        "tags": ["c:is_cold/overworld", "c:is_sparse_vegetation/overworld", "c:is_plains",
                 "minecraft:has_structure/igloo", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/pillager_outpost", "minecraft:has_structure/ruined_portal_standard",
                 "minecraft:has_structure/trial_chambers", "minecraft:has_structure/village_snowy",
                 "minecraft:is_overworld", "minecraft:spawns_cold_variant_farm_animals",
                 "minecraft:spawns_cold_variant_frogs", "minecraft:spawns_snow_foxes",
                 "minecraft:spawns_white_rabbits", "minecraft:stronghold_biased_to"],
    },
    "alpine_tundra": {
        "template": "meadow", "temperature": 0.2, "downfall": 0.6,
        "effects": {"grass_color": "#87a159", "foliage_color": "#6d8a46", "dry_foliage_color": "#8a6b45"},
        "creatures": [spawn("goat", 5, 1, 3), spawn("rabbit", 4, 2, 3), spawn("sheep", 2, 2, 4)],
        "local": ["boulders"],
        "vegetation": ["minecraft:glow_lichen", "trees_alpine_tundra", "patch_alpine_grass", "patch_alpine_flowers",
                       "tundra_moss"],
        "tags": ["c:is_cold/overworld", "c:is_mountain/slope", "c:is_sparse_vegetation/overworld",
                 "minecraft:has_structure/abandoned_camp_meadow", "minecraft:has_structure/trial_chambers",
                 "minecraft:is_mountain", "minecraft:is_overworld", "minecraft:spawns_cold_variant_farm_animals",
                 "minecraft:stronghold_biased_to"],
    },
    "cloud_forest": {
        "template": "jungle", "temperature": 0.7, "downfall": 1.0, "fog": 128.0,
        "effects": {"grass_color": "#4f9b3c", "foliage_color": "#3e8f30"},
        "attributes": {"minecraft:visual/fog_color": "#c3ced4", "minecraft:visual/sky_color": "#9db4c8",
                       "minecraft:audio/background_music": {"default": {
                           "max_delay": 24000, "min_delay": 12000, "sound": "minecraft:music.overworld.jungle"}}},
        "creatures": [spawn("parrot", 30, 1, 2), spawn("chicken", 6, 4), spawn("pig", 4, 4)],
        "local": ["minecraft:forest_rock"],
        "vegetation": ["minecraft:glow_lichen", "trees_cloud_forest", "rainforest_moss", "minecraft:patch_large_fern",
                       "minecraft:patch_grass_jungle", "minecraft:brown_mushroom_normal",
                       "minecraft:red_mushroom_normal", "minecraft:patch_firefly_bush_near_water"],
        "top": ["cloud_undergrowth", "canopy_vines", "lianas"],
        "tags": ["c:is_wet/overworld", "c:is_dense_vegetation/overworld", "c:is_temperate/overworld", "c:is_rare",
                 "c:is_tree/jungle", "c:primary_wood_type/jungle", "minecraft:has_structure/jungle_temple",
                 "minecraft:has_structure/mineshaft", "minecraft:has_structure/trail_ruins",
                 "minecraft:has_structure/trial_chambers", "minecraft:is_overworld", "minecraft:stronghold_biased_to"],
    },
    "temperate_rainforest": {
        "template": "old_growth_spruce_taiga", "temperature": 0.4, "downfall": 1.0, "fog": 320.0,
        "effects": {"grass_color": "#5c9c44", "foliage_color": "#4a8b38"},
        "attributes": {"minecraft:visual/fog_color": "#b8c8d0"},
        "local": ["minecraft:forest_rock"],
        "vegetation": ["minecraft:glow_lichen", "trees_temperate_rainforest", "rainforest_moss",
                       "minecraft:patch_large_fern", "minecraft:patch_grass_taiga",
                       "minecraft:brown_mushroom_old_growth", "minecraft:red_mushroom_old_growth",
                       "minecraft:brown_mushroom_normal", "minecraft:red_mushroom_normal",
                       "minecraft:patch_firefly_bush_near_water", "minecraft:patch_berry_common",
                       "rainforest_redwoods"],
        "tags": ["c:is_wet/overworld", "c:is_dense_vegetation/overworld", "c:is_temperate/overworld", "c:is_old_growth",
                 "c:is_rare", "c:is_forest", "c:is_tree/coniferous", "c:primary_wood_type/spruce",
                 "minecraft:has_structure/abandoned_camp_old_growth_spruce_taiga", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/trail_ruins", "minecraft:has_structure/trial_chambers",
                 "minecraft:is_overworld", "minecraft:is_taiga", "minecraft:stronghold_biased_to"],
    },
    # Groves of redwoods, in the fog of the rainforest's coasts, over ferns and a few spruces.
    "redwood_forest": {
        "template": "old_growth_spruce_taiga", "temperature": 0.45, "downfall": 1.0, "fog": 256.0,
        "effects": {"grass_color": "#5f9a46", "foliage_color": "#4f8a3a"},
        "attributes": {"minecraft:visual/fog_color": "#bccad0"},
        "local": ["minecraft:forest_rock"],
        "vegetation": ["minecraft:glow_lichen", "minecraft:patch_large_fern", "trees_redwood_forest", "rainforest_moss",
                       "minecraft:patch_grass_taiga", "minecraft:brown_mushroom_old_growth",
                       "minecraft:red_mushroom_old_growth", "minecraft:patch_firefly_bush_near_water",
                       "minecraft:patch_berry_common", "redwoods"],
        "tags": ["c:is_wet/overworld", "c:is_dense_vegetation/overworld", "c:is_temperate/overworld", "c:is_old_growth",
                 "c:is_rare", "c:is_forest", "c:is_tree/coniferous", "c:primary_wood_type/spruce",
                 "minecraft:has_structure/abandoned_camp_old_growth_spruce_taiga", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/trail_ruins", "minecraft:has_structure/trial_chambers",
                 "minecraft:is_overworld", "minecraft:is_taiga", "minecraft:stronghold_biased_to"],
    },
    # Ancient oaks over open woodland and glades on the plains, as in Sherwood or Białowieża, made giants.
    "ancient_woodland": {
        "template": "forest", "temperature": 0.7, "downfall": 0.8,
        "effects": {"grass_color": "#6aa043", "foliage_color": "#59a034"},
        "local": ["minecraft:forest_rock"],
        "vegetation": ["minecraft:glow_lichen", "minecraft:forest_flowers", "trees_ancient_woodland",
                       "minecraft:patch_bush", "minecraft:flower_default", "minecraft:patch_grass_forest",
                       "minecraft:brown_mushroom_normal", "minecraft:red_mushroom_normal",
                       "minecraft:patch_firefly_bush_near_water", "patch_woodland_flowers"],
        "tags": ["c:is_temperate/overworld", "c:is_forest", "c:is_old_growth", "c:is_rare", "c:is_tree/deciduous",
                 "c:primary_wood_type/oak", "minecraft:has_structure/abandoned_camp_forest",
                 "minecraft:has_structure/mineshaft", "minecraft:has_structure/ruined_portal_standard",
                 "minecraft:has_structure/trial_chambers", "minecraft:is_forest", "minecraft:is_overworld",
                 "minecraft:stronghold_biased_to"],
    },
    # Primary rainforest: kapoks rising out of the canopy.
    "old_growth_jungle": {
        "template": "jungle", "temperature": 0.95, "downfall": 0.95,
        "effects": {"grass_color": "#4e9f2c", "foliage_color": "#3f9a22"},
        "vegetation": ["minecraft:glow_lichen", "trees_old_growth_jungle", "minecraft:flower_warm",
                       "minecraft:patch_grass_jungle", "minecraft:patch_large_fern", "minecraft:brown_mushroom_normal",
                       "minecraft:red_mushroom_normal", "minecraft:patch_firefly_bush_near_water",
                       "minecraft:patch_melon", "rainforest_moss"],
        "top": ["undergrowth", "canopy_vines", "lianas"],
        "tags": ["c:is_wet/overworld", "c:is_hot/overworld", "c:is_dense_vegetation/overworld", "c:is_old_growth",
                 "c:is_rare", "c:is_jungle", "c:is_tree/jungle", "c:primary_wood_type/jungle",
                 "minecraft:has_structure/jungle_temple", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/trail_ruins", "minecraft:has_structure/trial_chambers",
                 "minecraft:is_jungle", "minecraft:is_overworld", "minecraft:spawns_warm_variant_farm_animals",
                 "minecraft:stronghold_biased_to"],
    },
    "maquis": {
        "template": "plains", "temperature": 0.9, "downfall": 0.35,
        "effects": {"grass_color": "#93a457", "foliage_color": "#7f9452", "dry_foliage_color": "#a08a4a",
                    "water_color": "#3a8fd8"},
        "creatures": [spawn("sheep", 12, 4), spawn("goat", 4, 1, 3), spawn("pig", 6, 4), spawn("chicken", 8, 4),
                      spawn("cow", 4, 4), spawn("rabbit", 4, 2, 3), spawn("donkey", 2, 1, 3),
                      spawn("horse", 2, 2, 4)],
        "local": ["limestone_boulders"],
        "vegetation": ["minecraft:glow_lichen", "trees_maquis", "patch_maquis_bush", "patch_maquis_grass",
                       "patch_maquis_flowers", "minecraft:patch_sugar_cane", "minecraft:patch_firefly_bush_near_water"],
        "tags": ["c:is_dry/overworld", "c:is_temperate/overworld", "c:is_tree/deciduous",
                 "minecraft:has_structure/mineshaft", "minecraft:has_structure/pillager_outpost",
                 "minecraft:has_structure/ruined_portal_standard", "minecraft:has_structure/trail_ruins",
                 "minecraft:has_structure/trial_chambers", "minecraft:has_structure/village_plains",
                 "minecraft:is_overworld", "minecraft:stronghold_biased_to"],
    },
    "cold_desert": {
        "template": "desert", "monsters": "plains", "temperature": 0.4, "downfall": 0.05, "precipitation": True,
        "effects": {"grass_color": "#aaa866", "foliage_color": "#9f9a50", "dry_foliage_color": "#a1825a"},
        "attributes": {"minecraft:visual/sky_color": "#82acff"},
        "creatures": [spawn("rabbit", 12, 2, 3), spawn("camel", 2, 1), spawn("horse", 1, 2, 4),
                      spawn("goat", 1, 1, 2)],
        "local": ["boulders_rare"],
        "vegetation": ["minecraft:glow_lichen", "patch_sagebrush", "minecraft:patch_dry_grass_desert",
                       "minecraft:patch_dead_bush_2"],
        "tags": ["c:is_desert", "c:is_dry/overworld", "c:is_cold/overworld", "c:is_sandy",
                 "c:is_sparse_vegetation/overworld", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/pillager_outpost", "minecraft:has_structure/ruined_portal_desert",
                 "minecraft:has_structure/trial_chambers", "minecraft:has_structure/village_desert",
                 "minecraft:is_overworld", "minecraft:stronghold_biased_to"],
    },
    # Hot desert shrubland: creosote bush and saltbush a few strides apart over stony ground and gravel, cacti on the
    # sand, mesquite here and there. Most of the world's deserts look like this, from the Mojave and the Sonoran Desert
    # to the Karoo and the edges of the Sahara; only the driest are bare sand. Armadillos, as in the Chihuahuan Desert.
    "desert_scrub": {
        "template": "desert", "temperature": 2.0, "downfall": 0.1,
        "effects": {"grass_color": "#b5aa68", "foliage_color": "#8f9a50", "dry_foliage_color": "#a8865a"},
        "creatures": [spawn("rabbit", 12, 2, 3), spawn("armadillo", 6, 1, 2), spawn("camel", 1, 1)],
        "local": ["boulders"],
        "vegetation": ["minecraft:glow_lichen", "trees_desert_scrub", "patch_desert_bush", "patch_scrub_grass",
                       "patch_desert_flowers", "columnar_cacti", "minecraft:patch_dry_grass_desert",
                       "minecraft:patch_dead_bush_2", "minecraft:patch_cactus_desert"],
        "tags": ["c:is_desert", "c:is_dry/overworld", "c:is_hot/overworld", "c:is_sparse_vegetation/overworld",
                 "minecraft:has_structure/desert_pyramid", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/pillager_outpost", "minecraft:has_structure/ruined_portal_desert",
                 "minecraft:has_structure/trial_chambers", "minecraft:has_structure/village_desert",
                 "minecraft:is_overworld", "minecraft:spawns_gold_rabbits",
                 "minecraft:spawns_warm_variant_farm_animals", "minecraft:spawns_warm_variant_frogs",
                 "minecraft:stronghold_biased_to"],
    },
    # Reg and hamada, the gravel plains and bare rock that make most of the Sahara: too dry even for scrub. A few dead
    # bushes and boulders, and camels crossing.
    "stony_desert": {
        "template": "desert", "temperature": 2.0, "downfall": 0.0,
        "creatures": [spawn("rabbit", 4, 2, 3), spawn("camel", 2, 1)],
        "local": ["boulders_rare"],
        "vegetation": ["minecraft:glow_lichen", "minecraft:patch_dead_bush_2"],
        "tags": ["c:is_desert", "c:is_dry/overworld", "c:is_hot/overworld", "c:is_sparse_vegetation/overworld",
                 "minecraft:has_structure/desert_pyramid", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/ruined_portal_desert", "minecraft:has_structure/trial_chambers",
                 "minecraft:is_overworld", "minecraft:spawns_gold_rabbits",
                 "minecraft:spawns_warm_variant_farm_animals", "minecraft:stronghold_biased_to"],
    },
    # Tropical thorn scrub: thickets of thorn bushes, mesquite and acacia as tall as a man, over red earth, dry grass
    # and drifts of red sand where the cacti grow, with a baobab standing over it all every few chunks. The driest
    # savannas, the Sahel, the Caatinga and the Chaco, the thorn forests of India and of Tamaulipas, and the bushland of
    # Kenya. Armadillos, as in the Chaco; goats and donkeys, as in the Sahel; guinea fowl (chickens).
    "thorn_scrub": {
        "template": "savanna", "temperature": 2.0, "downfall": 0.1,
        "effects": {"grass_color": "#aea558", "foliage_color": "#8e9446", "dry_foliage_color": "#a8865a"},
        "creatures": [spawn("armadillo", 8, 1, 2), spawn("goat", 6, 1, 3), spawn("rabbit", 6, 2, 3),
                      spawn("donkey", 4, 1, 3), spawn("chicken", 4, 2, 4), spawn("horse", 2, 2, 4)],
        "vegetation": ["minecraft:glow_lichen", "baobabs", "trees_thorn_scrub", "lone_trees_thorn_scrub",
                       "patch_thorn_scrub_bush", "patch_thorn_scrub_grass", "patch_desert_flowers", "columnar_cacti",
                       "minecraft:patch_dead_bush_2", "minecraft:patch_firefly_bush_near_water"],
        "tags": ["c:is_savanna", "c:is_dry/overworld", "c:is_hot/overworld",
                 "minecraft:has_structure/abandoned_camp_savanna", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/pillager_outpost", "minecraft:has_structure/ruined_portal_standard",
                 "minecraft:has_structure/trial_chambers", "minecraft:has_structure/village_savanna",
                 "minecraft:is_overworld", "minecraft:is_savanna", "minecraft:spawns_warm_variant_farm_animals",
                 "minecraft:spawns_warm_variant_frogs", "minecraft:stronghold_biased_to"],
    },
    "steppe": {
        "template": "plains", "temperature": 0.65, "downfall": 0.25,
        "effects": {"grass_color": "#a9b15e", "foliage_color": "#93a04c", "dry_foliage_color": "#a68a52"},
        "creatures": [spawn("horse", 8, 2, 6), spawn("donkey", 2, 1, 3), spawn("sheep", 8, 4), spawn("cow", 6, 4),
                      spawn("rabbit", 6, 2, 3), spawn("chicken", 4, 4), spawn("fox", 2, 2, 3)],
        "vegetation": ["minecraft:glow_lichen", "trees_steppe", "minecraft:patch_tall_grass_2", "patch_steppe_grass",
                       "patch_steppe_flowers", "minecraft:patch_bush"],
        "tags": ["c:is_plains", "c:is_dry/overworld", "c:is_temperate/overworld",
                 "minecraft:has_structure/mineshaft", "minecraft:has_structure/pillager_outpost",
                 "minecraft:has_structure/ruined_portal_standard", "minecraft:has_structure/trial_chambers",
                 "minecraft:has_structure/village_plains", "minecraft:is_overworld",
                 "minecraft:stronghold_biased_to"],
    },
    # Sagebrush steppe: grey-green shrubs knee- to waist-high a stride or two apart over dry bunchgrass, bare ground and
    # gravel, where the winter's rain soaks below the grass's roots or there is too little rain for a sward: the Great
    # Basin and the Columbia Plateau, Patagonia, the wormwood steppe of Kazakhstan. Wild horses and burros, jackrabbits
    # (rabbits), foxes and coyotes (wolves).
    "shrub_steppe": {
        "template": "plains", "temperature": 0.7, "downfall": 0.15,
        "effects": {"grass_color": "#a4a676", "foliage_color": "#b9c2a6", "dry_foliage_color": "#a08a5c"},
        "creatures": [spawn("rabbit", 12, 2, 3), spawn("horse", 4, 2, 6), spawn("donkey", 2, 1, 3),
                      spawn("sheep", 4, 4), spawn("fox", 2, 2, 3), spawn("wolf", 1, 2, 4)],
        "local": ["boulders_rare"],
        "vegetation": ["minecraft:glow_lichen", "patch_sage_mounds", "patch_sage", "patch_shrub_steppe_grass",
                       "patch_juniper_flowers", "minecraft:patch_dead_bush_2"],
        "tags": ["c:is_plains", "c:is_dry/overworld", "c:is_temperate/overworld", "c:is_sparse_vegetation/overworld",
                 "minecraft:has_structure/mineshaft", "minecraft:has_structure/pillager_outpost",
                 "minecraft:has_structure/ruined_portal_standard", "minecraft:has_structure/trial_chambers",
                 "minecraft:has_structure/village_plains", "minecraft:is_overworld",
                 "minecraft:stronghold_biased_to"],
    },
    # Open woods of juniper and pinyon pine on semi-arid hills, mesas and mountainsides, over bunchgrass, sagebrush
    # and bare, stony ground: the Great Basin's ranges and the Colorado Plateau, the foothills of the Tien Shan, the
    # hills of Anatolia and Iran. Wild horses and burros, bighorns (goats), rabbits, foxes and coyotes (wolves).
    "juniper_woodland": {
        "template": "plains", "temperature": 0.7, "downfall": 0.2,
        "effects": {"grass_color": "#a3a865", "foliage_color": "#7d9a80", "dry_foliage_color": "#9c8456"},
        "creatures": [spawn("rabbit", 10, 2, 3), spawn("horse", 4, 2, 6), spawn("donkey", 2, 1, 3),
                      spawn("goat", 4, 1, 3), spawn("fox", 3, 2, 3), spawn("wolf", 2, 2, 4)],
        "local": ["boulders"],
        "vegetation": ["minecraft:glow_lichen", "trees_juniper_woodland", "patch_steppe_grass", "patch_sagebrush",
                       "patch_juniper_flowers", "minecraft:patch_dead_bush_2"],
        "tags": ["c:is_dry/overworld", "c:is_temperate/overworld", "c:is_sparse_vegetation/overworld",
                 "c:is_tree/coniferous", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/pillager_outpost", "minecraft:has_structure/ruined_portal_standard",
                 "minecraft:has_structure/trial_chambers", "minecraft:has_structure/village_desert",
                 "minecraft:is_overworld", "minecraft:stronghold_biased_to"],
    },
    # Between the steppe and the forest: groves of birch and aspen, and of oak where the soil is deepest, in a meadow-
    # steppe of tall grass and flowers. From Ukraine across Russia and Siberia to the Altai, in Manchuria, and the
    # aspen parkland of the Canadian prairies. The plains' farm animals, and the steppe's horses and foxes.
    "forest_steppe": {
        "template": "plains", "temperature": 0.6, "downfall": 0.45,
        "effects": {"grass_color": "#9db35b", "foliage_color": "#7ea24a", "dry_foliage_color": "#a28650"},
        "creatures": [spawn("sheep", 10, 4), spawn("cow", 8, 4), spawn("pig", 6, 4), spawn("chicken", 8, 4),
                      spawn("horse", 5, 2, 6), spawn("rabbit", 4, 2, 3), spawn("fox", 2, 2, 3)],
        "vegetation": ["minecraft:glow_lichen", "trees_forest_steppe", "lone_trees_forest_steppe",
                       "minecraft:patch_tall_grass_2", "patch_forest_steppe_grass", "patch_forest_steppe_flowers",
                       "minecraft:patch_bush"],
        "tags": ["c:is_plains", "c:is_temperate/overworld", "c:is_tree/deciduous",
                 "minecraft:has_structure/mineshaft", "minecraft:has_structure/pillager_outpost",
                 "minecraft:has_structure/ruined_portal_standard", "minecraft:has_structure/trial_chambers",
                 "minecraft:has_structure/village_plains", "minecraft:is_overworld",
                 "minecraft:stronghold_biased_to"],
    },
    # Tallgrass prairie: a sea of grass head-high by the end of the summer, bright with wildflowers and sunflowers, a
    # lone bur oak here and there, on deep black soil over loess. The fires and the grazers keep the trees off ground
    # their climate would wood: the tallgrass prairie from Illinois to Kansas and the pampas of Argentina and Uruguay.
    # Bison (cows), wild horses, prairie chickens (chickens), rabbits, foxes and coyotes (wolves).
    "prairie": {
        "template": "plains", "temperature": 0.8, "downfall": 0.5,
        "effects": {"grass_color": "#a0b75a", "foliage_color": "#7f9f4a", "dry_foliage_color": "#a88c52"},
        "creatures": [spawn("cow", 12, 4, 8), spawn("horse", 6, 2, 6), spawn("sheep", 4, 4), spawn("chicken", 6, 4),
                      spawn("pig", 2, 4), spawn("rabbit", 6, 2, 3), spawn("fox", 2, 2, 3), spawn("wolf", 1, 2, 4)],
        "vegetation": ["minecraft:glow_lichen", "minecraft:patch_tall_grass_2", "trees_prairie",
                       "minecraft:patch_grass_plain", "patch_prairie_grass", "patch_prairie_flowers",
                       "minecraft:patch_sugar_cane", "minecraft:patch_firefly_bush_near_water"],
        "tags": ["c:is_plains", "c:is_temperate/overworld", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/pillager_outpost", "minecraft:has_structure/ruined_portal_standard",
                 "minecraft:has_structure/trial_chambers", "minecraft:has_structure/village_plains",
                 "minecraft:is_overworld", "minecraft:stronghold_biased_to"],
    },
    "moorland": {
        "template": "plains", "temperature": 0.4, "downfall": 0.9,
        "effects": {"grass_color": "#7d8a4c", "foliage_color": "#6a7a40", "dry_foliage_color": "#8a6b45"},
        "creatures": [spawn("sheep", 14, 2, 4), spawn("rabbit", 6, 2, 3), spawn("horse", 3, 2, 5),
                      spawn("cow", 2, 2, 4), spawn("fox", 2, 2, 3)],
        "local": ["granite_tors"],
        "vegetation": ["minecraft:glow_lichen", "trees_moorland", "patch_heather_moor", "patch_moor_grass",
                       "minecraft:patch_berry_rare"],
        "tags": ["c:is_wet/overworld", "c:is_temperate/overworld", "c:is_sparse_vegetation/overworld",
                 "minecraft:has_structure/mineshaft", "minecraft:has_structure/pillager_outpost",
                 "minecraft:has_structure/ruined_portal_standard", "minecraft:has_structure/trail_ruins",
                 "minecraft:has_structure/trial_chambers", "minecraft:has_structure/village_plains",
                 "minecraft:is_overworld", "minecraft:stronghold_biased_to"],
    },
    "heath": {
        "template": "plains", "temperature": 0.6, "downfall": 0.5,
        "effects": {"grass_color": "#8d9a52", "foliage_color": "#76904a", "dry_foliage_color": "#9a7f4e"},
        "creatures": [spawn("sheep", 12, 2, 4), spawn("rabbit", 8, 2, 3), spawn("cow", 4, 2, 4), spawn("chicken", 4, 4),
                      spawn("horse", 2, 2, 4), spawn("fox", 2, 2, 3)],
        "vegetation": ["minecraft:glow_lichen", "trees_heath", "patch_heather_heath", "patch_heath_grass",
                       "minecraft:patch_berry_rare"],
        "tags": ["c:is_plains", "c:is_temperate/overworld", "c:is_sparse_vegetation/overworld",
                 "minecraft:has_structure/mineshaft", "minecraft:has_structure/pillager_outpost",
                 "minecraft:has_structure/ruined_portal_standard", "minecraft:has_structure/trail_ruins",
                 "minecraft:has_structure/trial_chambers", "minecraft:has_structure/village_plains",
                 "minecraft:is_overworld", "minecraft:stronghold_biased_to"],
    },
    # Tropical dry forest: teak and sal over dry grass, bamboo and drifts of fallen leaves, with acacias where it is
    # driest. India, Southeast Asia, the dry forests of Central America and Bolivia, and the north of Australia. The red
    # junglefowl, which every chicken comes from, and the wild boar live here.
    "monsoon_forest": {
        "template": "jungle", "temperature": 0.95, "downfall": 0.6,
        "effects": {"grass_color": "#9ea84e", "foliage_color": "#8c9c3c", "dry_foliage_color": "#a8743a"},
        "creatures": [spawn("chicken", 8, 2, 4), spawn("pig", 6, 2, 4), spawn("parrot", 6, 1, 2),
                      spawn("ocelot", 2, 1)],
        "local": ["minecraft:forest_rock"],
        "vegetation": ["minecraft:glow_lichen", "minecraft:bamboo_light", "trees_monsoon_forest",
                       "minecraft:flower_warm", "patch_monsoon_grass", "patch_monsoon_leaf_litter",
                       "minecraft:patch_sugar_cane", "minecraft:patch_firefly_bush_near_water"],
        "tags": ["c:is_hot/overworld", "c:is_forest", "c:is_tree/deciduous", "c:primary_wood_type/jungle",
                 "minecraft:has_structure/jungle_temple", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/ruined_portal_jungle", "minecraft:has_structure/trail_ruins",
                 "minecraft:has_structure/trial_chambers", "minecraft:is_overworld",
                 "minecraft:spawns_warm_variant_farm_animals", "minecraft:spawns_warm_variant_frogs",
                 "minecraft:stronghold_biased_to"],
    },
    # Humid subtropical forest: laurels, camphors and evergreen oaks with dense, glossy crowns, live oaks hung with
    # grey moss, camellias and rhododendrons under them, ferns and groves of bamboo. Southern China, Taiwan and the
    # south of Japan, the foothills of the Himalayas, Madeira and the Canaries, and the American Southeast.
    "laurel_forest": {
        "template": "forest", "temperature": 0.8, "downfall": 0.9,
        "effects": {"grass_color": "#68a443", "foliage_color": "#4c8f30", "dry_foliage_color": "#8f6a3c"},
        "creatures": [spawn("pig", 6, 2, 4), spawn("rabbit", 4, 2, 3), spawn("fox", 4, 2, 4),
                      spawn("armadillo", 2, 1, 2), spawn("panda", 1, 1, 2)],
        "vegetation": ["minecraft:glow_lichen", "minecraft:bamboo_light", "trees_laurel_forest",
                       "patch_laurel_ground_cover", "patch_azaleas", "minecraft:patch_large_fern",
                       "patch_laurel_leaf_litter", "minecraft:brown_mushroom_normal", "minecraft:red_mushroom_normal",
                       "minecraft:patch_sugar_cane", "minecraft:patch_firefly_bush_near_water"],
        "tags": ["c:is_wet/overworld", "c:is_temperate/overworld", "c:is_dense_vegetation/overworld", "c:is_forest",
                 "c:is_tree/deciduous", "c:primary_wood_type/oak", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/ruined_portal_standard", "minecraft:has_structure/trail_ruins",
                 "minecraft:has_structure/trial_chambers", "minecraft:is_forest", "minecraft:is_overworld",
                 "minecraft:stronghold_biased_to"],
    },
    # Tropical alpine grassland, wet: tussock grass, cushions of moss and dark pools under the clouds, and the
    # frailejones standing over them like people. The northern Andes; the afro-alpine moors of Mount Kenya, with their
    # giant groundsels, look much the same.
    "paramo": {
        "template": "meadow", "temperature": 0.3, "downfall": 0.9, "fog": 192.0,
        "effects": {"grass_color": "#a0a362", "foliage_color": "#7f8c4f", "dry_foliage_color": "#9a7d4c"},
        "attributes": {"minecraft:visual/fog_color": "#c4cacd", "minecraft:visual/sky_color": "#a2b4c4"},
        "creatures": [spawn("rabbit", 6, 2, 3), spawn("llama", 3, 2, 4), spawn("sheep", 3, 2, 4),
                      spawn("fox", 2, 1, 2)],
        "local": ["paramo_pools", "boulders"],
        "vegetation": ["minecraft:glow_lichen", "trees_paramo", "patch_paramo_grass", "patch_paramo_flowers",
                       "tundra_moss"],
        "tags": ["c:is_wet/overworld", "c:is_cold/overworld", "c:is_mountain/slope", "c:is_sparse_vegetation/overworld",
                 "c:is_rare", "minecraft:has_structure/mineshaft", "minecraft:has_structure/trial_chambers",
                 "minecraft:is_mountain", "minecraft:is_overworld", "minecraft:spawns_cold_variant_farm_animals",
                 "minecraft:stronghold_biased_to"],
    },
    # Tropical alpine grassland, dry: golden ichu under a deep blue sky, cushions of yareta among the stones, tola
    # scrub, rare groves of queñua, and herds of llamas. The Altiplano, from Peru to the north of Chile and Argentina.
    "puna": {
        "template": "meadow", "temperature": 0.3, "downfall": 0.2,
        "effects": {"grass_color": "#b4a55e", "foliage_color": "#8f9150", "dry_foliage_color": "#a68a52"},
        "attributes": {"minecraft:visual/sky_color": "#6e9bff"},
        "creatures": [spawn("llama", 10, 2, 6), spawn("rabbit", 6, 2, 3), spawn("sheep", 2, 2, 4),
                      spawn("fox", 2, 1, 2)],
        "local": ["yaretas", "boulders"],
        "vegetation": ["minecraft:glow_lichen", "trees_puna", "patch_puna_grass", "patch_sagebrush"],
        "tags": ["c:is_dry/overworld", "c:is_cold/overworld", "c:is_plateau", "c:is_sparse_vegetation/overworld",
                 "minecraft:has_structure/mineshaft", "minecraft:has_structure/trial_chambers",
                 "minecraft:has_structure/village_desert", "minecraft:is_mountain", "minecraft:is_overworld",
                 "minecraft:spawns_cold_variant_farm_animals", "minecraft:stronghold_biased_to"],
    },
    # Raised bogs of the cool, wet lowlands: sphagnum and peat around dark pools, cottongrass, heather on the
    # hummocks, and a few spindly trees. The swamp's monsters: slimes, witches and the bogged.
    "bog": {
        "template": "swamp", "temperature": 0.35, "downfall": 0.9,
        "effects": {"grass_color": "#8b9a55", "grass_color_modifier": "none", "foliage_color": "#6f8045",
                    "dry_foliage_color": "#8a6b45", "water_color": "#6a5a3c"},
        "creatures": [spawn("frog", 10, 2, 5), spawn("rabbit", 4, 2, 3), spawn("fox", 3, 2, 4), spawn("wolf", 2, 2, 4)],
        "local": ["bog_pools"],
        "vegetation": ["minecraft:glow_lichen", "trees_bog", "patch_bog_ground_cover", "patch_heather_bog",
                       "minecraft:patch_waterlily", "minecraft:patch_berry_rare", "minecraft:brown_mushroom_normal",
                       "minecraft:red_mushroom_normal", "minecraft:patch_firefly_bush_near_water"],
        "tags": ["c:is_wet/overworld", "c:is_swamp", "c:is_cold/overworld", "c:is_sparse_vegetation/overworld",
                 "minecraft:allows_surface_slime_spawns", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/ruined_portal_swamp", "minecraft:has_structure/swamp_hut",
                 "minecraft:has_structure/trial_chambers", "minecraft:is_overworld",
                 "minecraft:spawns_cold_variant_frogs", "minecraft:stronghold_biased_to"],
    },
    # The larch forests of eastern Siberia, where the winters fall below -25 °C and the ground stays frozen a metre
    # down: open stands of larches, light green in summer, over grass, Labrador tea, lingonberries and reindeer lichen.
    # Yakutian horses, reindeer as sheep and cattle, foxes and wolves.
    "larch_taiga": {
        "template": "taiga", "temperature": 0.25, "downfall": 0.4,
        "effects": {"grass_color": "#86a85a", "foliage_color": "#9fc95b", "dry_foliage_color": "#a08a4a"},
        "creatures": [spawn("rabbit", 8, 2, 3), spawn("fox", 6, 2, 4), spawn("wolf", 6, 2, 4), spawn("horse", 4, 2, 5),
                      spawn("sheep", 6, 4), spawn("cow", 4, 4)],
        "vegetation": ["minecraft:glow_lichen", "trees_larch_taiga", "patch_larch_ground_cover", "lichen",
                       "tundra_moss", "patch_lingonberries", "minecraft:brown_mushroom_taiga",
                       "minecraft:red_mushroom_taiga", "minecraft:patch_firefly_bush_near_water"],
        "tags": ["c:is_cold/overworld", "c:is_forest", "c:is_tree/coniferous", "c:primary_wood_type/spruce",
                 "minecraft:has_structure/abandoned_camp_taiga", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/pillager_outpost", "minecraft:has_structure/ruined_portal_standard",
                 "minecraft:has_structure/trail_ruins", "minecraft:has_structure/trial_chambers",
                 "minecraft:has_structure/village_taiga", "minecraft:is_overworld", "minecraft:is_taiga",
                 "minecraft:spawns_cold_variant_farm_animals", "minecraft:stronghold_biased_to"],
    },
    # The forest-tundra, where the taiga thins out towards the tree line: clumps of stunted black spruce and crooked
    # birch, with open tundra of lichen, moss, dwarf birch, Labrador tea and lingonberries between them, as along
    # Hudson Bay, in northern Quebec and Labrador and on the Kola Peninsula. Reindeer (sheep) winter on the lichen;
    # foxes, wolves and rabbits.
    "forest_tundra": {
        "template": "taiga", "temperature": 0.2, "downfall": 0.5,
        "effects": {"grass_color": "#8a9e5c", "foliage_color": "#74904a", "dry_foliage_color": "#8a6b45"},
        "creatures": [spawn("rabbit", 10, 2, 3), spawn("fox", 6, 2, 4), spawn("wolf", 4, 2, 4), spawn("sheep", 4, 4)],
        "local": ["boulders_rare"],
        "vegetation": ["minecraft:glow_lichen", "trees_forest_tundra", "lone_trees_forest_tundra", "trees_tundra",
                       "patch_tundra_grass", "patch_larch_ground_cover", "lichen", "tundra_moss",
                       "patch_lingonberries", "minecraft:brown_mushroom_taiga", "minecraft:red_mushroom_taiga"],
        "tags": ["c:is_cold/overworld", "c:is_sparse_vegetation/overworld", "c:is_tree/coniferous",
                 "c:is_tree/deciduous", "c:primary_wood_type/spruce", "minecraft:has_structure/abandoned_camp_taiga",
                 "minecraft:has_structure/igloo", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/pillager_outpost", "minecraft:has_structure/ruined_portal_standard",
                 "minecraft:has_structure/trail_ruins", "minecraft:has_structure/trial_chambers",
                 "minecraft:has_structure/village_taiga", "minecraft:is_overworld", "minecraft:is_taiga",
                 "minecraft:spawns_cold_variant_farm_animals", "minecraft:spawns_cold_variant_frogs",
                 "minecraft:stronghold_biased_to"],
    },
    # The hemiboreal forest between the taiga and the broadleaf woods: southern Finland and Sweden, the Baltic, the
    # forests around Moscow, the Great Lakes and New England, Manchuria and Hokkaido. Ferns, bilberries and lilies of
    # the valley under the trees, and mushrooms in the moss. The taiga's wolves, foxes and rabbits.
    "mixed_forest": {
        "template": "taiga", "temperature": 0.45, "downfall": 0.8,
        "effects": {"grass_color": "#74ad5a", "foliage_color": "#5fa046", "dry_foliage_color": "#a2804a"},
        "vegetation": ["minecraft:glow_lichen", "minecraft:patch_large_fern", "trees_mixed_forest",
                       "minecraft:patch_grass_taiga_2", "patch_woodland_flowers", "minecraft:brown_mushroom_taiga",
                       "minecraft:red_mushroom_taiga", "minecraft:patch_firefly_bush_near_water",
                       "minecraft:patch_berry_common"],
        "tags": ["c:is_temperate/overworld", "c:is_forest", "c:is_tree/coniferous", "c:is_tree/deciduous",
                 "minecraft:has_structure/abandoned_camp_taiga", "minecraft:has_structure/mineshaft",
                 "minecraft:has_structure/pillager_outpost", "minecraft:has_structure/ruined_portal_standard",
                 "minecraft:has_structure/trail_ruins", "minecraft:has_structure/trial_chambers",
                 "minecraft:has_structure/village_taiga", "minecraft:is_forest", "minecraft:is_overworld",
                 "minecraft:stronghold_biased_to"],
    },
    # Drowned glacial valleys: deep, still, cold water between steep walls, with kelp and seagrass along the sides.
    # The cold ocean's fish and squid, wrecks and ruins, but no monuments: those keep to the open deep sea.
    "fjord": {
        "template": "cold_ocean", "temperature": 0.5, "downfall": 0.5,
        "effects": {"water_color": "#2d6a8c"},
        "vegetation": ["minecraft:glow_lichen", "minecraft:seagrass_cold", "minecraft:kelp_cold"],
        "tags": ["c:is_cold/overworld", "minecraft:has_structure/ocean_ruin_cold",
                 "minecraft:has_structure/trial_chambers", "minecraft:is_ocean", "minecraft:is_overworld",
                 "minecraft:spawns_cold_variant_farm_animals"],
    },
    # Dry lake beds: a crust of salt from one horizon to the other, and nothing growing.
    "salt_flat": {
        "template": "desert", "temperature": 0.7, "downfall": 0.0,
        "creatures": [spawn("rabbit", 6, 2, 3), spawn("llama", 2, 2, 4)],
        "vegetation": ["minecraft:glow_lichen"],
        "tags": ["c:is_desert", "c:is_dry/overworld", "c:is_sparse_vegetation/overworld",
                 "minecraft:has_structure/mineshaft", "minecraft:has_structure/ruined_portal_desert",
                 "minecraft:has_structure/trial_chambers", "minecraft:is_overworld", "minecraft:stronghold_biased_to"],
    },
    # The biomes of the Grand Hollows, one to a cavern (see HollowBiomes.java). Glowworm grottoes, like those of
    # Waitomo in New Zealand: ceilings strung with the glowing silk of fungus-gnat larvae, over still pools on clay.
    "glowworm_grotto": {
        "template": "dripstone_caves", "temperature": 0.6, "downfall": 0.9,
        "effects": {"water_color": "#2b5d6e"},
        "vegetation": ["minecraft:glow_lichen", "glowworm_silk_ceilings", "grotto_pools", "hollow_dripstone_floor",
                       "hollow_dripstone_ceiling"],
        "tags": CAVE_TAGS,
    },
    # Caverns of selenite beams, like the Cave of the Crystals at Naica, in Mexico: hot, dry, and lifeless now that
    # the water that grew them has been pumped away. No dripstone: the crystals grew under water.
    "selenite_caverns": {
        "template": "dripstone_caves", "temperature": 1.0, "downfall": 0.0,
        "without": DRIPSTONE,
        "local": ["selenite_beams_floor", "selenite_beams_ceiling"],
        "vegetation": [],
        "tags": CAVE_TAGS,
    },
    # Salt caverns, cut through the salt beds under the deserts by what little rain falls there, like Malham under
    # Mount Sodom or the Namakdan cave on Qeshm: a crust of salt, straws of it hanging from the ceilings, brine pools,
    # and cubes of halite larger than any on Earth. Husks, from the deserts above.
    "salt_caverns": {
        "template": "dripstone_caves", "temperature": 1.0, "downfall": 0.0, "monsters": "desert",
        "effects": {"water_color": "#7fc8c0"},
        "without": DRIPSTONE,
        "local": ["halite_floor", "halite_ceiling", "salt_stalactites"],
        "vegetation": ["brine_pools"],
        "tags": CAVE_TAGS,
    },
    # Ice caves, like the Eisriesenwelt in Austria or Scărișoara in Romania, where the winter's cold air sinks in and
    # stays all summer: floors of old ice under drifts of snow, spires of ice rising from them and hanging from the
    # ceilings, and lakes frozen solid (see HollowLakes.java). Strays, from the snows above.
    "ice_caves": {
        "template": "dripstone_caves", "temperature": -0.5, "downfall": 0.4, "monsters": "snowy_plains",
        "effects": {"water_color": "#3d57d6"},
        "without": DRIPSTONE,
        "local": ["ice_spires_floor", "ice_spires_ceiling"],
        "vegetation": ["icicles"],
        "tags": CAVE_TAGS,
    },
    # The forest of giant mushrooms that Verne set by the Lidenbrock Sea, on mycelium, lit by shroomlight.
    "mushroom_forest": {
        "template": "lush_caves", "temperature": 0.6, "downfall": 0.9,
        "attributes": {"minecraft:visual/ambient_particles": {
            "argument": [{"particle": {"type": "minecraft:mycelium"}, "probability": 0.01}], "modifier": "append"}},
        "vegetation": ["minecraft:glow_lichen", "giant_mushrooms", "hollow_brown_mushrooms", "hollow_red_mushrooms"],
        "tags": CAVE_TAGS,
    },
}
# Vanilla biomes the refiner swaps between, which the source must be able to return.
REFINED_VANILLA = ["minecraft:swamp", "minecraft:mangrove_swamp", "minecraft:forest", "minecraft:birch_forest",
                   "minecraft:taiga", "minecraft:savanna", "minecraft:snowy_plains",
                   "minecraft:snowy_slopes"]


# The ground of the new biomes, run before vanilla's overworld surface rule (see worldgen_data.py).

def block(state):
    return {"type": "minecraft:block", "result_state": mc(state)}


def when(condition, rule):
    return {"type": "minecraft:condition", "if_true": condition, "then_run": rule}


def biome_is(*names):
    """Our biomes by name; vanilla's by id."""
    return {"type": "minecraft:biome", "biome_is": [n if ":" in n else ours(n) for n in names]}


def noise(lo=-1.7976931348623157e308, hi=1.7976931348623157e308):
    return {"type": "minecraft:noise_threshold", "noise": "minecraft:surface", "min_threshold": lo,
            "max_threshold": hi}


def sequence(*rules):
    return {"type": "minecraft:sequence", "sequence": list(rules)}


STEEP = {"type": "minecraft:steep"}
# The soil under the top block, which vanilla makes dirt.
SUBSOIL = {"type": "minecraft:not", "invert": {
    "type": "minecraft:stone_depth", "offset": 0, "add_surface_depth": False, "secondary_depth_range": 0,
    "surface_type": "floor"}}
# Laterite: the red, leached soil of the tropics, and the red earths of the humid subtropics.
TROPICAL = ["minecraft:jungle", "minecraft:sparse_jungle", "minecraft:bamboo_jungle", "minecraft:savanna",
            "minecraft:savanna_plateau", "minecraft:windswept_savanna", "cloud_forest", "monsoon_forest",
            "laurel_forest", "old_growth_jungle", "thorn_scrub"]
SURFACE_RULE = sequence(
    when("minecraft:on_floor", when("minecraft:not_underwater", sequence(
        # Scree and bare rock between the alpine grass.
        when(biome_is("alpine_tundra"), sequence(
            when(STEEP, block("stone")), when(noise(0.25), block("gravel")), when(noise(hi=-0.4), block("stone")),
            block("grass_block"))),
        # Red laterite showing through the dry grass.
        when(biome_is("monsoon_forest"), sequence(
            when(noise(0.45), block(ours("laterite"))), when(noise(hi=-0.5), block("coarse_dirt")),
            block("grass_block"))),
        # Leaf mould on red earth.
        when(biome_is("laurel_forest"), sequence(
            when(noise(0.35), block("podzol")), when(noise(hi=-0.55), block("coarse_dirt")), block("grass_block"))),
        # Black, wet soil under the tussocks, and cushion bogs of moss.
        when(biome_is("paramo"), sequence(
            when(STEEP, block("stone")), when(noise(0.3), block("moss_block")),
            when(noise(hi=-0.5), block("coarse_dirt")), block("grass_block"))),
        # Stony ground between the bunchgrass.
        when(biome_is("puna"), sequence(
            when(STEEP, block("stone")), when(noise(0.3), block("gravel")), when(noise(hi=-0.45), block("coarse_dirt")),
            block("grass_block"))),
        # Gravel plains and sand, as in the Gobi.
        when(biome_is("cold_desert"), sequence(
            when(noise(0.2), block("gravel")), when(noise(hi=-0.35), block("coarse_dirt")),
            when("minecraft:on_ceiling", block("sandstone")), block("sand"))),
        # Stony ground and gravel pavement between the shrubs, and sand where the wind heaps it up.
        when(biome_is("desert_scrub"), sequence(
            when("minecraft:on_ceiling", block("coarse_dirt")), when(noise(0.2), block("sand")),
            when(noise(hi=-0.45), block("gravel")), block("coarse_dirt"))),
        # Red earth and coarse dirt between the thickets, and drifts of red sand where the cacti grow.
        when(biome_is("thorn_scrub"), sequence(
            when("minecraft:on_ceiling", block("coarse_dirt")), when(noise(0.45), block("red_sand")),
            when(noise(hi=-0.45), block(ours("laterite"))), when(noise(hi=-0.1), block("coarse_dirt")),
            block("grass_block"))),
        # Gravel pavement, bare sandstone where it is steep and on the hamada, and sand the wind has left.
        when(biome_is("stony_desert"), sequence(
            when(STEEP, block("sandstone")), when("minecraft:on_ceiling", block("sandstone")),
            when(noise(0.35), block("sand")), when(noise(hi=-0.4), block("sandstone")), block("gravel"))),
        when(biome_is("tundra", "forest_tundra"), sequence(
            when(noise(0.4), block("coarse_dirt")), when(noise(hi=-0.45), block("podzol")), block("grass_block"))),
        # Needle litter, and ground churned bare by the frost.
        when(biome_is("larch_taiga"), sequence(
            when(noise(0.3), block("podzol")), when(noise(hi=-0.55), block("coarse_dirt")), block("grass_block"))),
        # Needles under the spruces and pines, grass under the broadleaves.
        when(biome_is("mixed_forest"), sequence(when(noise(0.35), block("podzol")), block("grass_block"))),
        when(biome_is("steppe"), sequence(when(noise(0.55), block("coarse_dirt")), block("grass_block"))),
        # Bare, pale ground between the shrubs, gravel pavement, and the rock where it is steep.
        when(biome_is("shrub_steppe"), sequence(
            when(STEEP, block("stone")), when(noise(0.2), block("coarse_dirt")),
            when(noise(hi=-0.5), block("gravel")), block("grass_block"))),
        # Thin, stony soil between the bunchgrass, and the rock where it is steep.
        when(biome_is("juniper_woodland"), sequence(
            when(STEEP, block("stone")), when(noise(0.3), block("coarse_dirt")),
            when(noise(hi=-0.5), block("gravel")), block("grass_block"))),
        # Limestone breaking through thin terra rossa.
        when(biome_is("maquis"), sequence(
            when(STEEP, block(ours("limestone"))), when(noise(hi=-0.55), block(ours("limestone"))),
            when(noise(0.35), block(ours("terra_rossa"))), block("grass_block"))),
        when(biome_is("temperate_rainforest", "cloud_forest"), sequence(
            when(noise(0.15), block("podzol")), block("grass_block"))),
        # The redwoods' deep litter of needles, and leaf mould under the old oaks.
        when(biome_is("redwood_forest"), sequence(
            when(noise(-0.2), block("podzol")), when(noise(hi=-0.6), block("coarse_dirt")), block("grass_block"))),
        when(biome_is("ancient_woodland"), sequence(when(noise(0.5), block("podzol")), block("grass_block"))),
        when(biome_is("salt_flat"), sequence(when(STEEP, block("sandstone")), block(ours("salt_crust")))),
        # Bare peat and sphagnum bog between the grass and heather.
        when(biome_is("moorland"), sequence(
            when(STEEP, block("stone")), when(noise(0.3), block(ours("peat"))),
            when(noise(hi=-0.5), block("moss_block")), block("grass_block"))),
        # Sandy, leached soil.
        when(biome_is("heath"), sequence(
            when(noise(0.45), block("coarse_dirt")), when(noise(hi=-0.6), block("podzol")), block("grass_block"))),
        # Sphagnum, bare peat, and grass on the drier hummocks.
        when(biome_is("bog"), sequence(
            when(noise(0.4), block(ours("peat"))), when(noise(hi=0.05), block("moss_block")), block("grass_block"))),
    ))),
    when("minecraft:under_floor", sequence(
        when(biome_is("cold_desert", "desert_scrub", "stony_desert"), block("sandstone")),
        when(biome_is("alpine_tundra", "paramo", "puna", "juniper_woodland", "shrub_steppe"),
             when(STEEP, block("stone"))),
        when(biome_is("maquis"), when(STEEP, block(ours("limestone")))),
        when(biome_is("salt_flat"), block(ours("rock_salt"))),
        when(biome_is("moorland"), when(STEEP, block("stone"))),
        # Peat all the way down to the rock.
        when(biome_is("bog"), block(ours("peat"))),
        when(SUBSOIL, sequence(
            when(biome_is("moorland", "tundra", "forest_tundra", "paramo"), block(ours("peat"))),
            when(biome_is("maquis"), block(ours("terra_rossa"))),
            when(biome_is("steppe", "shrub_steppe", "prairie"), block(ours("loess"))),
            when(biome_is(*TROPICAL), block(ours("laterite"))))),
    )),
)


# Under water: the inverse of vanilla's not_underwater, whose water is any fluid.
UNDER_WATER = {"type": "minecraft:not", "invert": "minecraft:not_underwater"}
# Above the lava level, so that the floors of the lava seas stay rock.
ABOVE_LAVA = {"type": "minecraft:y_above", "anchor": {"above_bottom": 10}, "surface_depth_multiplier": 0,
              "add_stone_depth": False}

# The ground of the Grand Hollows' biomes, run below the surface (see worldgen_data.py).
CAVE_RULE = sequence(
    # The beds of the lakes in the basins (see HollowLakes): clay in the wet caves, sand by the Lidenbrock Sea, and
    # gravel washed down among the dripstone.
    when("minecraft:on_floor", when(UNDER_WATER, when(ABOVE_LAVA, sequence(
        when(biome_is("glowworm_grotto", "minecraft:lush_caves"), block("clay")),
        when(biome_is("mushroom_forest"), block("sand")),
        when(biome_is("minecraft:dripstone_caves"), block("gravel")))))),
    # Mycelium over a few blocks of dirt, on which the giant mushrooms grow.
    when(biome_is("mushroom_forest"), sequence(
        when("minecraft:on_floor", block("mycelium")), when("minecraft:under_floor", block("dirt")))),
    # A white crust of gypsum, as calcite, over the floor and the ceiling the crystals grew from.
    when(biome_is("selenite_caverns"), sequence(
        when("minecraft:on_floor", block("calcite")), when("minecraft:on_ceiling", block("calcite")))),
    # Clay where the stream has left it.
    when(biome_is("glowworm_grotto"), when("minecraft:on_floor", when(noise(0.2), block("clay")))),
    # A white crust of salt over the floor, and the salt itself, bare, in the ceiling.
    when(biome_is("salt_caverns"), sequence(
        when("minecraft:on_floor", block(ours("salt_crust"))), when("minecraft:under_floor", block(ours("rock_salt"))),
        when("minecraft:on_ceiling", block(ours("rock_salt"))))),
    # Old ice on the floor, under drifts of snow, and in the ceiling, blue where it is oldest.
    when(biome_is("ice_caves"), sequence(
        when("minecraft:on_floor", sequence(when(noise(0.1), block("snow_block")), block("packed_ice"))),
        when("minecraft:under_floor", block("packed_ice")),
        when("minecraft:on_ceiling", sequence(when(noise(0.4), block("blue_ice")), block("packed_ice"))))),
)


def find_jar():
    if len(sys.argv) > 1:
        return Path(sys.argv[1])
    props = dict(line.split("=", 1) for line in (PROJECT / "gradle.properties").read_text().splitlines()
                 if "=" in line and not line.startswith("#"))
    return Path.home() / ".gradle" / "caches" / "fabric-loom" / props["minecraft_version"].strip() / \
        "minecraft-common.jar"


def vanilla_data(jar):
    """Vanilla's biomes, configured features and placed features, by path."""
    biomes, configured, placed = {}, {}, {}
    folders = {"data/minecraft/worldgen/biome/": biomes, "data/minecraft/worldgen/feature/": configured,
               "data/minecraft/worldgen/placed_feature/": placed}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            for folder, into in folders.items():
                if name.startswith(folder) and name.endswith(".json"):
                    into[name[len(folder):-5]] = json.loads(z.read(name))
    return biomes, configured, placed


def check_references(configured, placed):
    """A placed feature names a configured feature, and a selector picks among placed features: the game only says
    which reference is unbound, not where it is."""
    def exists(ref, ours_, vanilla):
        namespace, path = ref.split(":")
        return path in (ours_ if namespace == NS else vanilla)

    for name, feature in PLACED.items():
        if isinstance(feature["feature"], str) and not exists(feature["feature"], FEATURES, configured):
            raise SystemExit(f"placed feature {name}: {feature['feature']} is not a configured feature")
    for name, feature in FEATURES.items():
        if feature["type"] == "minecraft:random_selector":
            for ref in [feature["default"]] + [f["feature"] for f in feature["features"]]:
                if isinstance(ref, str) and not exists(ref, PLACED, placed):
                    raise SystemExit(f"feature {name}: {ref} is not a placed feature")


def feature_rank(feature):
    """Diffusicraft's features in the one order every biome lists them in: the ones above first, then the copies."""
    path = feature.split(":")[1]
    return (0, list(PLACED).index(path)) if path in PLACED else (1, path)


def features_of(name, spec, template, placed, copies):
    """The biome's features, step by step.

    The game needs one order of features that every biome agrees with, and other mods' biomes take part in it. So a
    vanilla feature keeps its name only where the template lists it too, in the template's order, which vanilla
    itself already imposes. Any other vanilla feature runs as a copy of its own (collected in `copies`), and all of
    Diffusicraft's run after vanilla's."""
    without = set(spec.get("without", []))
    steps = [[f for f in template["features"][s] if f not in without] if s < len(template["features"]) else []
             for s in range(STEPS)]
    steps[VEGETAL_DECORATION] = []
    for step, key in ((LOCAL_MODIFICATIONS, "local"), (VEGETAL_DECORATION, "vegetation"),
                      (TOP_LAYER_MODIFICATION, "top")):
        steps[step] += spec.get(key, [])
    for step, features in enumerate(steps):
        vanilla, own = [], []
        for f in dict.fromkeys(features):
            if ":" not in f:
                if f not in PLACED:
                    raise SystemExit(f"{name}: unknown feature {f}")
                own.append(ours(f))
                continue
            path = f.split(":")[1]
            if path not in placed:
                raise SystemExit(f"{name}: {f} is not a vanilla placed feature")
            if f in template["features"][step]:
                vanilla.append(f)
            else:
                if path in PLACED:
                    raise SystemExit(f"{name}: the copy of {f} would replace {ours(path)}")
                copies[path] = placed[path]
                own.append(ours(path))
        vanilla.sort(key=template["features"][step].index)
        steps[step] = vanilla + sorted(dict.fromkeys(own), key=feature_rank)
    return steps


def make_biome(name, spec, vanilla, placed, copies):
    template = vanilla[spec["template"]]
    spawns = json.loads(json.dumps(template["attributes"]["minecraft:gameplay/natural_mob_spawns"]))
    categories = spawns["argument"]["spawns_by_category"]
    if "monsters" in spec:
        categories["monster"] = vanilla[spec["monsters"]]["attributes"][
            "minecraft:gameplay/natural_mob_spawns"]["argument"]["spawns_by_category"]["monster"]
    if "creatures" in spec:
        categories["creature"] = spec["creatures"]
    attributes = dict(template["attributes"])
    attributes["minecraft:gameplay/natural_mob_spawns"] = spawns
    attributes.update(spec.get("attributes", {}))
    if "fog" in spec:
        attributes["minecraft:visual/fog_end_distance"] = spec["fog"]
    if spec["temperature"] < 1.0:
        attributes.pop("minecraft:gameplay/snow_golem_melts", None)
    effects = dict(template["effects"])
    effects.update(spec.get("effects", {}))
    return {
        "attributes": dict(sorted(attributes.items())),
        "carvers": template["carvers"],
        "downfall": spec["downfall"],
        "effects": dict(sorted(effects.items())),
        "features": features_of(name, spec, template, placed, copies),
        "has_precipitation": spec.get("precipitation", template["has_precipitation"]),
        "temperature": spec["temperature"],
    }


def main():
    vanilla, configured, placed = vanilla_data(find_jar())
    check_references(configured, placed)
    for name, feature in FEATURES.items():
        write(f"{NS}/worldgen/feature/{name}.json", feature)
    for name, feature in PLACED.items():
        write(f"{NS}/worldgen/placed_feature/{name}.json", feature)
    tags, copies = {}, {}
    for name, spec in BIOMES.items():
        write(f"{NS}/worldgen/biome/{name}.json", make_biome(name, spec, vanilla, placed, copies))
        for tag in spec["tags"]:
            tags.setdefault(tag, []).append(ours(name))
    for name, feature in copies.items():
        write(f"{NS}/worldgen/placed_feature/{name}.json", feature)
    for tag, members in sorted(tags.items()):
        namespace, path = tag.split(":")
        write(f"{namespace}/tags/worldgen/biome/{path}.json", {"replace": False, "values": members})
    write(f"{NS}/tags/worldgen/biome/refinements.json",
          {"replace": False, "values": [ours(n) for n in BIOMES] + REFINED_VANILLA})
    for name, structure in STRUCTURES.items():
        write(f"{NS}/tags/worldgen/biome/has_structure/{name}.json", {"replace": False, "values": structure["biomes"]})
        write(f"{NS}/worldgen/structure/{name}.json", dict(structure, biomes=f"#{NS}:has_structure/{name}"))
    write(f"{NS}/worldgen/structure_set/giant_trees.json", GIANT_TREES)
    for name, spread in SPREADS.items():
        write(f"{NS}/worldgen/structure_set/{name}.json", spread)
    write(f"{NS}/worldgen/material_rule/biome_surface.json", SURFACE_RULE)
    write(f"{NS}/worldgen/material_rule/biome_caves.json", CAVE_RULE)


if __name__ == "__main__":
    main()
