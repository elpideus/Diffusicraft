# Other world generation mods

A survey of the biome and terrain mods for Minecraft, taken on 2026-10-27: what each one changes in the overworld,
which decides how Diffusicraft keeps its terrain when it is installed (see
[COMPATIBILITY.md](COMPATIBILITY.md#biome-mods)). Structures are left out for now.

Versions and licences come from Modrinth. What each mod changes was read from its jar or its source where it has one;
what only its description says is marked *(description)*. Only Regions Unexplored, Tectonic, Lithostitched, Moderner
Beta and Terra (MIT), Terra's overworld pack (CC-BY-4.0) and Climate Rivers (MPL-2.0) allow taking more than ideas.
Diffusicraft generates its data and textures itself (`tools/`), so nothing is copied from any of them.

## Biome mods

| Mod | Newest | Licence | How it places its biomes | Changes besides biomes |
|---|---|---|---|---|
| Biomes O' Plenty | 26.3 (26.3.0.0.15) | All rights reserved | TerraBlender regions | None |
| Oh The Biomes We've Gone | 1.21.11 (4.4.2) | All rights reserved | TerraBlender regions | None |
| Regions Unexplored | 26.1.2 (0.7.0) | MIT | Lithostitched regions and `replace_partially` of vanilla biomes | Its inferno cave wraps vanilla's `fluid_level_floodedness` |
| Nature's Spirit | 26.1.2 (2.3.0) | Its own | Lithostitched regions | Optional packs re-skin vanilla biomes |
| Terrestria | 26.3 (8.2.0-alpha.1) | LGPL-3.0 | Biolith, replacing 17.5 to 32.5% of a vanilla biome | Surface builders that reshape the caldera, canyons, dunes and volcanic islands |
| Traverse | 26.2 (9.1.0-beta.1) | LGPL-3.0 | Biolith, 20% | None |
| Promenade | 26.3 (6.0.2) | Polyform Shield | Biolith data files, 10 to 20% | None |
| Wilder Wild (FrozenLib) | 26.3 (4.3.3) | Its own | Mixins into vanilla's `OverworldBiomeBuilder`, which also move vanilla's biomes | Its surface rules, through FrozenLib |
| William Wythers' Overhauled Overworld | 26.3 (3.0.1) | CC-BY-NC-ND-4.0 | Most likely overrides vanilla's biome table and biomes (no source) | Optional add-ons for rivers, cliffs and tepuis |
| Ecologics | 26.3 (2.7.2) | Mixed | No biomes; features and mobs in vanilla's | None |
| Expanded Ecosphere | 1.21.1 (3.4.7) | CC-BY-ND-4.0 | Replaces the overworld | Everything |
| Biome Makeover | 1.20.1, abandoned | Its own | No biomes; makes over four of vanilla's | None |
| Atmospheric, Environmental, Autumnity | NeoForge 1.21.1, no Fabric | Abnormals | Blueprint biome slices | None |
| Alex's Caves | 1.20.1, Forge only | LGPL-3.0 | Its own rare cave sampler | Cave biomes only |

Every one of them asks for its biomes by vanilla's six climate parameters, through one of three libraries:

- **TerraBlender** divides the world into regions by a noise of its own, each with the biome table of one mod, and
  wraps the overworld's material rule so that each mod's rules run first in its own biomes.
- **Biolith** wraps the parameter list of every multi-noise source of the overworld dimension type, and replaces a
  share of a vanilla biome where a noise of its own says so. Its surface rules replace the noise settings'
  `materialRule` field, and its surface builders change the terrain inside `MaterialSystem.buildSurface`.
- **Lithostitched** (Regions Unexplored, Nature's Spirit, Terralith, Tectonic, Climate Rivers) adds a field to the
  parameter list of every multi-noise source and runs worldgen modifiers: `add_features`, `set_material_rule`, which
  changes a material rule by its id, `wrap_noise_router` and `wrap_aquifers` by the level's key, and biome injectors.

FrozenLib adds its surface rules to the noise settings of the dimension types in its tag
`frozenlib:overworld`.

## Terrain mods

| Mod | Newest | Licence | What it changes |
|---|---|---|---|
| Terralith | 26.3 (2.6.5) | Stardust Labs | Vanilla's overworld biome table (1708 entries), 39 vanilla biomes, 18 placed features, the canyon carver, the overworld's surface rules (`surface`, `biome_surface`, `stony_peaks`, `under_biome_surface`, `underground`), vanilla's noises (`aquifer_*`, `cave_cheese`, `spaghetti_*_thickness`, `erosion`, `temperature` and others) and 26 `minecraft:overworld/*` density functions; adds 101 biomes |
| Tectonic | 26.3 | MIT | The `minecraft:overworld` noise settings and router, three snowy surface rules, the noises `badlands_pillar_roof` and `badlands_surface`, the height of the `minecraft:overworld` dimension type (−64 to 320, or 640), the lava level of settings whose path is `overworld`, the snow line, and the blending of every chunk saved without its marker |
| Climate Rivers | 26.3 (26.3.1) | MPL-2.0 | Puts its rule before `minecraft:overworld/surface` and swaps `minecraft:river` for a warm, lukewarm, cold or dry river by temperature |
| Continents | 26.3 | Stardust Labs | `minecraft:overworld/base_continents` and `continents` |
| Larger Biomes | 26.3 | All rights reserved | Most likely the overworld's climate density functions (no source) |
| Geophilic | 26.3 | All rights reserved | 279 vanilla biomes' features: boulders, fallen logs, puddles, pillars, ponds |
| Cascades | 26.3 (1.0.7) | CC-BY-NC-ND-4.0 | Every vanilla noise settings, the overworld's density functions, 11 noises and 50 biomes |
| Larion | 1.21.4 | Apache-2.0 | The `minecraft:overworld` dimension type (−128 to 511) and noise settings, 8 overworld density functions and two carvers |
| JJThunder To The Max | 26.2 (0.9.0) | All rights reserved | The overworld's dimension type (up to y = 2032) and noise settings *(description)* |
| Big Globe | 26.1.2 (6.3.0) | All rights reserved | Nothing of vanilla's: its own chunk generator and world presets |
| Terra | 1.21.8 (6.6.6) | MIT, pack CC-BY-4.0 | Nothing of vanilla's: its own generator and world presets |
| Moderner Beta | 26.3 (5.0.0 alpha) | MIT | Nothing of vanilla's: its own generators |
| YUNG's Better Caves | 26.1.2 | LGPL-3.0 | The overworld's caves *(description)* |
| Expanded Ecosphere | 1.21.1 | CC-BY-ND-4.0 | The whole overworld |

What they touch falls into four groups:

- **Their own generator** (Big Globe, Terra, Moderner Beta). A world is theirs or Diffusicraft's; nothing mixes.
- **Vanilla's data by its id** (Terralith, Cascades, Continents, Larion, JJThunder, Larger Biomes). They reach a
  Diffusicraft world only through what its data shares with vanilla's: the cave density functions and noises
  `minecraft:overworld/caves/*`, `cave_cheese`, `noodle*`, the aquifer noises, the material rules
  `minecraft:overworld/surface`, `bedrock_floor` and the ore veins, the `minecraft:overworld` dimension type of the
  Vanilla size, vanilla's biome table and vanilla's biomes.
- **Lithostitched's modifiers** (Terralith, Tectonic, Climate Rivers). They target the level, the material rules, the
  dimension type and the biome tags, not the noise settings, so a Diffusicraft world is in their reach too.
- **Biome JSONs only** (Geophilic, William Wythers', Ecologics). They decorate the biomes Diffusicraft places, which is
  welcome.
