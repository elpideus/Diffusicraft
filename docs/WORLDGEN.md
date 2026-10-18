# World generation

Diffusicraft generates the overworld from Terrain Diffusion's elevation and climate. The rest is Minecraft's own
`minecraft:noise` chunk generator, with Diffusicraft's noise settings and density functions. The overworld
therefore stays a vanilla `NoiseBasedChunkGenerator` with a vanilla multi-noise biome source. Several things work
unchanged because of this:

- structures and structure mods;
- biome mods that extend the overworld biome table (TerraBlender, Biomes O' Plenty, BYG);
- C2ME's parallel generation;
- tools that call the generator directly, such as Distant Horizons and Voxy SeedGen.

## World types

Diffusicraft adds three world types and makes the first one the default:

- **on the Create World screen**, `WorldCreationUiStateMixin` selects it;
- **on dedicated servers**, `DedicatedServerPropertiesMixin` makes it the default `level-type`.

| World type | `level-type` | Horizontal | Vertical | Build height |
|---|---|---|---|---|
| Diffusicraft | `diffusicraft:standard` | 15 m per block | 10 m per block, eased above 1 km | −64 to 319 (vanilla) |
| Diffusicraft Grand | `diffusicraft:grand` | 5 m per block | 4 m per block, eased above 4.8 km | −256 to 2031 |
| Diffusicraft Earth 1:1 | `diffusicraft:earth` | 1 m per block | 1 m per block, eased above 1.2 km | −256 to 2031 |

A world type that needs the models cannot be created until they are downloaded. Pressing Create opens the resource
screen instead. A dedicated server downloads them at startup, and generation waits for the download.

### From meters to blocks

`WorldScale` holds the numbers.

**Horizontally**, a block covers a fixed number of meters. The model's 30 m pixels are interpolated with Catmull-Rom
splines, so the surface stays smooth at any scale.

**Vertically**, elevation maps linearly up to a knee, then eases towards a ceiling:

```text
blocks(e) = e / v                                            while e / v <= knee
blocks(e) = knee + (cap - knee) * tanh((e / v - knee) / (cap - knee))   above
```

Below the knee, hills and valleys keep their real proportions. Above it, only the highest mountains are compressed to
fit the build height. Oceans use the same curve with their own numbers, so abyssal plains stay above the bottom of
the world.

| Scale | Land: m per block, knee, cap | Highest surface | Ocean: m per block, knee, cap | Lowest sea floor |
|---|---|---|---|---|
| standard | 10, 100, 240 | y = 303 | 20, 20, 45 | y = 18 |
| grand | 4, 1200, 1940 | y = 2003 | 8, 40, 160 | y = −97 |
| earth | 1, 1200, 1940 | y = 2003 | 1, 60, 200 | y = −137 |

The sea is at y = 63 in every scale. The sea floor lies at least one block under it, so a sea shallower than one
block's worth of meters still holds water instead of showing as a dry shelf flush with the water. Earth 1:1
compresses mountains above about 1.2 km until the build height can grow beyond 4064 (see [ROADMAP.md](ROADMAP.md)).

## How a column is generated

```text
WorldPipeline (30 m pixels, per seed)
   │  PipelineSource: creates the pipeline on first use; caches windows under <world>/diffusicraft/
   ▼
TerrainField: 256² pixel tiles, Catmull-Rom elevation, plus an 8-pixel grid of
   │          mean elevation, relief and sea-level climate
   ▼
density functions: diffusicraft:surface, diffusicraft:climate, diffusicraft:spawn_suitability,
   │                diffusicraft:hollows (the Hollows model)
   ▼
noise settings diffusicraft:<scale>: noise router, aquifers, material rule diffusicraft:<scale>
   ▼                                  (vanilla's surface rules + diffusicraft:strata, the Geology model)
vanilla NoiseBasedChunkGenerator: caves, aquifers, ores, surface blocks, features and structures
```

### `TerrainField`

`TerrainField` holds one seed's terrain. It reads the pipeline in tiles of 256 × 256 pixels (7.7 km), with a
32-pixel margin. For each tile it keeps two things:

- **The padded elevation.** Columns sample it with a Catmull-Rom spline.
- **A grid every 8 pixels (240 m).** It holds slowly varying values, sampled bilinearly:
  - the mean elevation and relief (standard deviation) over a 1.4 km box, from summed-area tables;
  - the climate reduced to sea level, plus its lapse rate.

  The temperature at a column is then the sea-level temperature plus the lapse rate times the column's own
  elevation, so summits are cold even between grid points.

Tiles are computed once, by the first thread that needs them; other threads wait for that result. The 96 most recently
used tiles stay in memory, about 40 MB. The terrain seed is derived from the world seed, so worlds with the same seed
match.

### Biome parameters

The biome source is vanilla's `multi_noise` overworld preset, which reads six parameters. `ClimateMapping` gives each
one a physical meaning, with breakpoints at the edges of vanilla's own bands:

| Parameter | Computed from |
|---|---|
| temperature | Annual mean at the column's elevation. The hot band (desert, badlands) also requires dry air. |
| humidity (vegetation) | Precipitation over the Köppen aridity threshold `20 × (T + 7)` mm. |
| continentalness | Offshore: the water depth. On the coast: the height above the sea. Inland: the mean elevation. |
| erosion | Local relief: rugged land is low, flat land is high. Flat, low and wet land is put in the swamp band. |
| weirdness | Whether the column is a local peak or a hollow. The sign comes from smooth noise and only picks variants. |
| depth | Blocks below the surface, capped at 60 blocks above the sea. |

Some adjustments move biomes away from vanilla's defaults:

- **Snow.** Snowy slopes and peaks need temperatures below 1 °C. A warmer mountain is moved into the plateau and
  forest bands. Between 1 and 8 °C, a summit becomes stony peaks.
- **Windswept and rivers.** Erosion never reaches the windswept band, and weirdness never reaches the river band.
  Both come from the terrain, not from noise.

Biome mods that add entries to the overworld table get the same parameters, so they appear where the real conditions
match.

### Density and caves

The settings set `final_density` to vanilla's overworld density, with two changes:

- vanilla's `sloped_cheese` terrain is replaced by `diffusicraft:<scale>/terrain`, which is `(surface − y) / 16`;
- deeper than 25 blocks below the surface, the density is the minimum of vanilla's caves and
  [the Hollows](#the-hollows).

Everything else is vanilla, so worlds keep:

- cheese, spaghetti and noodle caves;
- cave entrances, pillars, aquifers and the deep dark;
- structure blending (the beardifier).

The top of the column fades to air 16 blocks above the highest possible surface.

`chunk_surface_level` is the exact surface rather than vanilla's estimate. Surface rules therefore place grass, sand
and snow at the right height.

The aquifers get `diffusicraft:<scale>/aquifer_surface` instead. On land it is the same surface. Under the sea it is
at most 12 blocks under sea level. Vanilla's aquifers fill a column with the sea only where its surface plus 8 is
below sea level, and leave anything shallower mostly dry. With the exact surface, every sea less than 9 blocks deep
became a dry plain dotted with dry pits.

### The ground

The material rule `diffusicraft:<scale>` is vanilla's overworld rule with the underground replaced. Bedrock and the
large copper and iron veins stay vanilla. Then `diffusicraft:strata` (`StrataRule`) runs vanilla's surface rules
first, so biomes keep their grass, sand, terracotta and snow. Where they ask for nothing, or for plain stone as
mountain biomes do, the block comes from the `Geology` model. Mountain sides and cliffs therefore show the strata.

`Geology` is a pure function of the geology seed, the scale and the column's terrain. It holds no Minecraft classes and
is tested on its own. From the top down:

| Layer | Where | Rocks |
|---|---|---|
| Sedimentary cover | From the surface down to a depth set by the basin: up to about 2 km of rock under lowlands and shelves, a thin skin on rugged uplands | Beds 1–8 blocks thick that follow the height, not the surface, so valley walls cut through them. The basin decides the rocks: limestone, chalk, shale and sandstone where there was a sea; sandstone and shale on land; coal seams in swampy basins; red sandstone and rock salt where it is hot and dry |
| Fold belts | Rugged land over thick sediment | The beds fold steeply; deep shale turns to slate, then schist, and limestone to marble |
| Basement | Below the cover, down to about y = 4 | Granite, gneiss, stone, schist or andesite with tuff beds, by region, with lenses of diorite, granite, andesite or marble |
| Lower crust | From about y = 4 | Deepslate, then gabbro |
| Mantle | Above the magma layer | Peridotite, pierced by rare kimberlite pipes; a quarter of the pipes reach the surface |
| Magma layer | The last blocks above the bedrock | Magma blocks, blackstone and basalt |

The deep layers share the room between y = 0 and the bottom of the world, so they are thicker in the tall worlds.
Caves fill with lava up to 10 blocks above the bottom of the world (`NoiseBasedChunkGeneratorMixin`). Vanilla fixes
that level at y = −54, which in a world going down to −256 would flood 200 blocks of caves with lava.

**Ores.** Vanilla's scattered ores still generate, and they also spawn in the new rocks through the ore replaceable
tags. On top of them, `Geology` places ore bodies: ellipsoids up to ten blocks across whose mineral depends on the
host rock:

| Host rock | Ore bodies |
|---|---|
| Granite, diorite, andesite | Copper, gold, iron; sulfur in andesite |
| Tuff | Sulfur, cinnabar, gold |
| Stone, gneiss | Iron, copper, gold; redstone near the deepslate |
| Schist | Gold, iron, copper; emeralds under high mountains |
| Marble | Lapis, iron |
| Sedimentary rocks | Iron and copper; coal in the coal seams |
| Deepslate, gabbro | Redstone, iron, gold, lapis, copper and diamonds |
| Peridotite | Diamonds, redstone, sulfur, cinnabar |
| Kimberlite pipes | Diamonds, scattered, at any depth |

Bodies are commoner below y = 0. Ores in deep rocks take their deepslate form. Prospecting by rock pays off: marble
lenses carry lapis, and kimberlite at the surface marks a pipe of diamonds below.

**New rocks.** Limestone, chalk, shale, rock salt, slate, marble, schist, gneiss, gabbro, peridotite and kimberlite
(`DiffusicraftBlocks`). They drop themselves and are mined with any pickaxe. Most count as stone for tools and
furnaces, and so do granite, diorite, andesite and tuff, because a basement can be granite for hundreds of blocks. They
join `#minecraft:base_stone_overworld`, so moss, sculk, dripstone and vanilla's ore blobs spread through them.

### The Hollows

The Hollows are caverns far larger than vanilla's caves, and larger the deeper they lie. `Hollows` is a pure model,
like `Geology`, seeded from the world seed and `diffusicraft:hollows`. `diffusicraft:hollows` (`HollowsFunction`)
samples it as a density, negative in the open.

| Kind | Depth | Size (standard world) | How often |
|---|---|---|---|
| Hollow | In the basement, centred between y = 6 and y = 36 (117 in the tall worlds) | 14–30 blocks in radius, 10–22 high | A third of 112-block cells |
| Deep Hollow | In the deepslate, from halfway down to the lava level up to y = −8 | 20–42 in radius, 14–28 high | A third of 144-block cells |
| Grand Hollow | 40–60 % of the way up from the lava level to y = 0 | 70–160 in radius, 22–30 high | A sixth of 448-block cells |
| Ancient Grand Hollow | 30–45 % of the way up | 150–320 in radius, 26–38 high | An eighth of 896-block cells |
| Deep Grand Hollow | Just above the magma layer, its floor under the lava level | 100–230 in radius, 18–26 high | A seventh of 640-block cells |
| Deep noodle caverns | From just above the lava level to y = −12, in about two fifths of the world | Tunnels 5–8 blocks across | |

Heights scale with the room between the lava level and y = 0. In the tall worlds the Grand Hollows are up to 135
blocks high and the Ancient ones up to 170.

- **Shape.** Each cavern is an ellipse in plan with a lobed outline, a domed ceiling over a flatter floor, and walls
  warped by 3D noise. Caverns of different kinds can overlap and merge.
- **Pillars.** Grand and Ancient Grand Hollows have rock pillars that flare where they meet the floor and the ceiling.
- **Light.** `StrataRule` puts patches of glowstone in the ceilings of Grand Hollows and shroomlight in those of
  Ancient ones.
- **Lava seas.** Deep Grand Hollows reach below the floor limit, which cuts them flat well under the lava level, so
  most of their floor lies under a lava sea. A floor that only just dipped under the lava would stay dry: the world
  interpolates the density over 8 blocks of height and fades it to solid towards the bottom.
- **Roof and floor.** At least 28 blocks of rock stay between a hollow and the surface, so they never open into the
  sky or the sea. Nothing is carved within 6 blocks of the bottom, so the bedrock is whole. Only the lava seas sink
  lower: their lava rests on the bedrock, 3 blocks above the bottom.

Vanilla's aquifers can flood parts of a hollow with water, as they do vanilla's caves. The density is sampled at the
corners of the 4 × 8 × 4 cells that vanilla interpolates. Each sample takes a few tens of nanoseconds.

### Spawn and far-away searches

`diffusicraft:spawn_suitability` scores land between 100 and 700 m highest. It falls off towards the sea and on high
mountains. It reads only the coarse map, at 7.7 km per pixel. The spawn search covers a few kilometres and would
otherwise run the full model over all of it.

Stronghold rings have the same problem on a larger scale. Before the first chunk generates, each of the 128
strongholds searches the biomes within 112 blocks of its ring position, up to about 20 000 blocks out.
`ChunkGeneratorStructureStateMixin` runs that search inside `CoarseClimate.call`. In that scope the climate functions
describe each column from the coarse map alone (`TerrainField.coarseColumn`):

- the elevation is the coarse pixel's mean;
- the relief is estimated from how far the pixel's 5th percentile lies below that mean;
- the climate is the coarse climate.

The result is deterministic, but it can differ from the biomes that are finally generated in the details. Strongholds
are underground, so this does not matter.

Other searches still read the full terrain and can take a while on new land:

- `/locate biome`;
- `/locate structure`;
- explorer maps;
- eyes of ender.

## Files

| Path | What |
|---|---|
| `worldgen/` | `WorldScale`, `TerrainSource`, `TerrainField`, `ClimateMapping`, `CoarseClimate`, `TerrainFields` (one field per seed, model lifetime), `PipelineSource`, `DiffusicraftWorldgen` (registration) |
| `mixin/`, `client/mixin/` | The default world type, the check for missing models on Create, and the coarse stronghold search |
| `worldgen/density/` | The density function types: `SurfaceFunction`, `ClimateFunction`, `SpawnSuitabilityFunction`, `HollowsFunction` |
| `worldgen/geology/` | `Geology` (the rock and ore model), `Rock`, `Ore`, `Hollows` (the cavern model), `GeoNoise` |
| `worldgen/material/` | `StrataRule`, the material rule type that applies the geology and lights the Hollows |
| `block/` | `DiffusicraftBlocks`, the new rocks |
| `data/diffusicraft/worldgen/` | Noise settings, material rules, density functions and world presets for each scale |
| `data/diffusicraft/dimension_type/` | The tall overworld dimension types (grand and earth) |
| `tools/worldgen_data.py` | Writes all of the data above. Edit it, run `python3 tools/worldgen_data.py`, and commit the output. |
| `tools/block_assets.py` | Draws the rock textures and writes the block states, models, loot tables and tags of the blocks. `--preview sheet.png` writes a sheet of the textures for review. |

## Lifetime and storage

- **Loading.** The models load when the first chunk needs terrain, not when the world opens.
- **Unloading.** They are freed when the server stops, which gives the GPU memory back to the game.
- **Disk cache.** Pipeline windows are cached in `<world>/diffusicraft/<model revision>/<terrain seed>/`. Reopening a
  world is fast, and the terrain stays the same even if newer models are installed later.

## Tests

- `GeologyTest` checks the layer order, that lowlands are covered in sediment, determinism, ore rarity and diamond
  depth, and the sampling speed.
- `HollowsTest` checks that every kind appears, the share of open ground, the roof and the bedrock, the lava seas,
  the height of the Grand Hollows in the tall worlds, determinism and the sampling speed.
- `WorldScaleTest`, `ClimateMappingTest` and `TerrainFieldTest` run with `./gradlew build` and need no models.
  `TerrainFieldTest` uses an analytic terrain to check:
  - interpolation;
  - continuity across tile borders;
  - eviction;
  - concurrent tile computation;
  - the coarse column.
- `WorldGenerationGameTest` is a client game test and needs the models. It creates a world with the default settings
  and checks that it uses Diffusicraft. It then saves a biome map, a height map, in-game views around the spawn and
  views of the tallest Grand Hollow and a lava sea nearby to `build/run/clientGameTest/screenshots`. It generates on the CPU unless `-Pdiffusicraft.device` names another device.
