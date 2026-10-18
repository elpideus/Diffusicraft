# World generation

Diffusicraft generates the overworld from Terrain Diffusion's elevation and climate. The rest is Minecraft's own
`minecraft:noise` chunk generator, with Diffusicraft's noise settings and density functions. The overworld
therefore stays a vanilla `NoiseBasedChunkGenerator`, and its biome source is vanilla's multi-noise source with one
step added. Several things work unchanged because of this:

- structures and structure mods;
- biome mods that extend the overworld biome table (TerraBlender, Biomes O' Plenty, BYG);
- C2ME's parallel generation;
- tools that call the generator directly, such as Voxy SeedGen.

Distant Horizons gets a generator of its own that reads the terrain without building chunks; see
[COMPATIBILITY.md](COMPATIBILITY.md#distant-horizons).

## World types

Diffusicraft adds three world types and makes the first one the default:

- **on the Create World screen**, `WorldCreationUiStateMixin` selects it;
- **on dedicated servers**, `DedicatedServerPropertiesMixin` makes it the default `level-type`.

| World type | `level-type` | Horizontal | Vertical | Build height |
|---|---|---|---|---|
| Diffusicraft | `diffusicraft:standard` | 15 m per block | 10 m per block, eased above 1 km | −64 to 319 (vanilla) |
| Diffusicraft Grand | `diffusicraft:grand` | 5 m per block | 4 m per block, eased above 8 km | −256 to 2383 |
| Diffusicraft Earth 1:1 | `diffusicraft:earth` | 1 m per block | 1 m per block, eased above 8 km | −256 to 9087 |

A world type that needs the models cannot be created until they are downloaded. Pressing Create opens the resource
screen instead. A dedicated server downloads them at startup, and generation waits for the download.

### From meters to blocks

`WorldScale` holds the numbers.

**Horizontally**, a block covers a fixed number of meters. The model's 30 m pixels are interpolated with Catmull-Rom
splines, so the surface stays smooth at any scale. At Earth 1:1 [gullies](#gullies) cut the slopes more finely than
the pixels.

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
| grand | 4, 2000, 2300 | y = 2363 | 8, 40, 160 | y = −97 |
| earth | 1, 8000, 9000 | y = 9063 | 1, 60, 200 | y = −137 |

The sea is at y = 63 in every scale. The sea floor lies at least one block under it, so a sea shallower than one
block's worth of meters still holds water instead of showing as a dry shelf flush with the water. In Grand and Earth
1:1 the knee lies at 8 km, so every mountain but the Himalaya's highest keeps its real height; Everest stands at
about y = 8,750 in Earth 1:1. Each tall world type ends some 20 blocks above its highest surface, room for the
tallest trees on the highest peak.

**Depth is sized for play.** The real crust is tens of kilometres thick, but the game happens mostly on the surface,
so the underground does not follow the vertical scale:

- the bottom of the world is y = −64 in the standard world and y = −256 in the tall ones, whatever their scale;
- the seas are compressed, as in the table above;
- below the sea, the rock layers use at least 10 m per block, as in the standard world (see [The ground](#the-ground)).

The build height goes to the surface instead. Real mountains rise some 9 km above the sea while the mantle lies
tens of kilometres down, and a world of both would be mostly rock nobody digs. So the tall world types are tall at
the top only: Earth 1:1 is 9,344 blocks tall, all but 319 of them above the sea. Chunks fill only as high as their
ground reaches, so the empty height costs little (see [HEIGHT.md](HEIGHT.md#filling-only-to-the-ground)).

## How a column is generated

```text
WorldPipeline (30 m pixels, per seed)
   │  PipelineSource: creates the pipeline on first use; caches windows under <world>/diffusicraft/
   ▼
TerrainField: 256² pixel tiles, Catmull-Rom elevation, plus an 8-pixel grid of
   │          mean elevation, relief and sea-level climate
   ▼
TerrainSurface: the ground in blocks for one scale, with gullies at Earth 1:1
   ▼
density functions: diffusicraft:surface, diffusicraft:climate, diffusicraft:spawn_suitability,
   │                diffusicraft:hollows (the Hollows model), diffusicraft:river (the Rivers model)
   ▼
noise settings diffusicraft:<scale>: noise router, aquifers, material rule diffusicraft:<scale>
   ▼                                  (vanilla's surface rules + diffusicraft:strata, the Geology model,
   ▼                                   + diffusicraft:river, the rivers' water and beds)
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

### Gullies

Nothing narrower than two of the model's 30 m pixels survives, so at Earth 1:1 a mountainside would be a smooth sheet
between samples 30 blocks apart. Real slopes are cut by gullies and rills that run straight down them, deeper on
steeper ground, while gentle ground stays smooth under its soil. `Gullies` adds them:

- **Where.** On slopes steeper than 0.15 blocks per block (about 9°), in full from 0.6 (31°). Gentler ground keeps the
  model's shape.
- **Shape.** Waves that cross the slope, so their crests and troughs run downhill. They are anchored at jittered
  points and blended with a compact kernel (a kind of Gabor noise), so a gully runs for a few of its widths and fades.
- **Octaves.** Three, 48, 24 and 12 m wide. Each is half as deep as the one before and follows the slope of the
  ground carved so far, so the smaller gullies branch off the sides of the larger ones.
- **Depth.** The waves rise and fall by 6% of their width times the slope, which stops growing at 1.5. On a 45° slope
  the widest rise and fall some 3 blocks; all three together never pass 7.6.

Octaves narrower than 12 blocks are left out: the density is interpolated over cells 4 blocks wide and would turn
them into zigzags. That keeps all three at Earth 1:1 and none in the other worlds, whose blocks are 5 and 15 m wide.

`TerrainSurface` puts the gullies on the terrain, and everything that needs the ground reads it there: the density,
the ceiling of each chunk, the rivers and lakes, the Hollows, the materials (so the sides of the gullies can bare the
rock, see [The ground](#the-ground)) and the LODs whose columns are at most 4 blocks apart. The biomes and the climate
keep the smooth elevation; gullies are too small to change them. The slope comes from the spline's own derivative, so
gentle ground costs one sample, and steep ground about a third of a microsecond more.

### Biome parameters

The biome source starts from vanilla's `multi_noise` overworld preset, which reads six parameters. `ClimateMapping`
gives each one a physical meaning, with breakpoints at the edges of vanilla's own bands:

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
- **Windswept and rivers.** Erosion never reaches the windswept band. Weirdness reaches the river band only in the
  valleys of [the rivers](#rivers), which therefore take vanilla's river biomes, frozen where it is cold. Both come
  from the terrain, not from noise.

Biome mods that add entries to the overworld table get the same parameters, so they appear where the real conditions
match. So do the regions of TerraBlender and the mods built on it, such as Biomes O' Plenty; the refiner below leaves
their biomes as they are. See [COMPATIBILITY.md](COMPATIBILITY.md#biome-mods).

### Refined biomes

Vanilla's table sees five bands of temperature and humidity and nothing of the seasons. That cannot tell tundra from
taiga, or a Mediterranean coast from a monsoon one. So the biome source, `diffusicraft:climate` (`ClimateBiomeSource`),
takes vanilla's pick and hands it to `BiomeRefiner` with the column's whole climate (`BiomeClimate`): annual mean,
temperature seasonality, precipitation and its seasonality, elevation, relief and the variant noise. The refiner may
swap it for one of ten new biomes. The first rule that matches wins:

| Biome | Climate | Replaces |
|---|---|---|
| **Cloud forest** | Tropical mountainsides: 1000–3500 m, 10–20 °C, little seasonality, at least 1500 mm of rain | Any land but peaks, deserts and badlands |
| *(vanilla snow and ice)* | Warmest month below 3 °C, or a year below −10 °C | Kept as vanilla picked them |
| **Salt flat** | Desert (aridity below 0.5) with less than 4 m of relief: the dry beds of old lakes, up on the cold Altiplano too | Plains, forests, taigas, savannas, deserts |
| **Tundra**, **alpine tundra** | Beyond the tree line: warmest month below 10 °C. Alpine on mountains | Any land, peaks included |
| **Moorland** | Rolling uplands (300–1500 m, less than 90 m of relief) with cool summers (warmest month below 15.5 °C), coldest month above −6 °C, at least 800 mm of rain | Plains, forests, taigas, savannas, hills and meadows |
| **Temperate rainforest** | 4–14 °C, coldest month above −3 °C, at least 1600 mm of rain | Plains, forests, taigas, savannas, slopes and hills |
| **Maquis** | 12–21 °C, coldest month at least 2 °C, 250–1000 mm of strongly seasonal rain, below 1200 m | The same, deserts and meadows too, but no other slopes |
| **Heath** | Oceanic lowland below 300 m: 6–12 °C, coldest month above −4 °C, 550–1100 mm of rain. Only where the variant noise is below −0.3, so it comes in patches among the woods | Plains, forests, taigas, savannas |
| **Cold desert** | Desert (aridity below 0.5) cooler than 12 °C | Plains, forests, taigas, savannas, deserts |
| **Steppe** | Semi-arid (aridity below 1) and cooler than 18 °C | Plains, forests, taigas, savannas |

Swamps on tropical coasts (coldest month at least 15 °C, below 10 m) become mangroves, and mangroves elsewhere swamps.
Oceans, shores, rivers, caves and the mushroom fields are never touched, and neither is any biome a mod adds to the
table: only vanilla's own biomes are refined. `BiomeRefiner` is pure and unit-tested.

**Snow line.** Vanilla cools every biome by 0.05 per 40 blocks above y = 80 (`Biome.getHeightAdjustedTemperature`),
so snow falls on any high ground. Diffusicraft's biomes already come from the climate at the real elevation, so that
would count the cold twice: `BiomeMixin` turns the lapse rate off while a Diffusicraft world runs
(`HeightTemperature`). Snow therefore lies where the climate is cold, and the tundra puts the tree line below the snow
line, as on real mountains. On a dedicated server the clients keep vanilla's lapse rate, so high ground can show snow
falling where the server has rain; only the weather on screen differs.

**Grand Hollows.** The deep dark would carpet their floors in sculk. Inside the Grand, Ancient Grand and Deep Grand
Hollows the source picks what vanilla would pick halfway down to the deep dark instead: lush or dripstone caves where
the climate calls for them, and the biome above otherwise. So do their pillars, the smaller caves that cut into them and
the rock around them (`Hollows.nearGrandHollow`): a few dozen blocks of their walls, and at least a dozen blocks under
their floors and over their ceilings. The game looks a biome up as much as a quart off, so deep dark left a few blocks
under a floor would put sculk on it, and sculk veins spread up to twenty blocks from wherever the deep dark starts them.

**Plumbing.** A biome source is shared by every world that uses a preset, so it learns which world it serves from the
climate sampler. `RandomStateMixin` attaches a `BiomeWorld` (the terrain, scale and Hollows) to the random state of
Diffusicraft's noise settings and to every `Climate.Sampler` it creates (`ClimateSamplerMixin`). Every way the game
asks for a biome goes through such a sampler: chunk generation, structure placement, `/locate`, `/place` and the
debug screen. All of them see the refined biomes. In other worlds the sampler carries nothing and the source behaves
exactly like vanilla's. It still extends `MultiNoiseBiomeSource` (its constructor is opened by
`diffusicraft.classtweaker`), so mods looking for a multi-noise source find one.

**The new biomes.** Each starts from a vanilla template. It keeps the template's carvers, underground features and
monsters, so caves and ores look the same under every biome, and replaces the vegetation, the ground, the animals and
the colours:

| Biome | Template | Vegetation | Ground |
|---|---|---|---|
| Tundra | Snowy plains | Dwarf birch scrub, moss mats, berry bushes, boulders | Grass with coarse dirt and podzol over peat |
| Alpine tundra | Meadow | Knee-high krummholz spruce, alpine flowers, moss, boulders | Grass broken by gravel scree and bare rock |
| Cloud forest | Jungle | Vine-hung canopy trees, gnarled oaks, ferns, moss; dense fog (128 blocks) | Grass and podzol over laterite |
| Temperate rainforest | Old-growth spruce taiga | Giant and mega spruces, ferns, moss, mushrooms; light fog | Grass and podzol |
| Maquis | Plains | Evergreen scrub, olives, stone pines, cypresses, bushes, alliums | Thin grass and terra rossa on limestone |
| Cold desert | Desert | Sagebrush, dry grass, scattered boulders; rain and snow | Sand with gravel and coarse dirt, sandstone below |
| Steppe | Plains | Grass, dry grass, tulips and poppies, a lone oak or birch | Grass with patches of coarse dirt over loess |
| Moorland | Plains | Heather, moor grass and cottongrass, granite tors, the odd birch or spruce, berries | Grass with bare peat and sphagnum (moss), peat below |
| Heath | Plains | Heather and grass under scattered birches with bees, Scots pines and junipers | Grass with sandy coarse dirt and podzol |
| Salt flat | Desert | Nothing | Salt crust over rock salt |

The alpine tundra flowers with edelweiss and gentians, the maquis with lavender, and cottongrass grows on the tundra.

Their ground is the material rule `diffusicraft:biome_surface`, which runs ahead of vanilla's overworld surface rule
and leaves every other biome to it. They join vanilla's structure tags (villages, outposts, ruins) and the `c:`
convention tags where they fit.

The game requires one order of features that every biome agrees with, and other mods' biomes take part in it. So a
new biome names a vanilla feature only where its template lists it too, in the template's order. Any other vanilla
feature it uses is a copy under Diffusicraft's name, and Diffusicraft's own features run after vanilla's. No order
these biomes impose can then clash with another mod's.

`tools/biomes.py` writes all of this: the biomes, their features, the surface rule and the tags. It reads the
templates from the Minecraft jar Loom unpacked.

### Density and caves

The settings set `final_density` to vanilla's overworld density, with two changes:

- vanilla's `sloped_cheese` terrain is replaced by `diffusicraft:<scale>/terrain`, which is `(surface − y) / 16`.
  Its `y` is a gradient over the whole world, not vanilla's `minecraft:y`, which stops at y = 4062 and would leave
  the mountains of Earth 1:1 solid up to the top of the world;
- deeper than 25 blocks below the surface, the density is the minimum of vanilla's caves and
  [the Hollows](#the-hollows);
- [the rivers](#rivers) come last: `diffusicraft:<scale>/river_fill` builds the ground up under them and
  `river_carve` cuts it down to their water, so their beds seal any cave below.

Everything else is vanilla, so worlds keep:

- cheese, spaghetti and noodle caves;
- cave entrances, pillars, aquifers and the deep dark;
- structure blending (the beardifier).

The top of the column fades to air 16 blocks above the highest possible surface.

Vanilla's cave entrances fade out on low ground, from 8 blocks above sea level down to 2. Near the coast the aquifers
fill everything under sea level with the sea, so an entrance in a coastal plain became a bay or a straight canal of
sea water. For the same reason the carvers (ravines and vanilla's carved caves) leave a roof of 4 blocks under water,
along rivers and on ground at most 3 blocks above sea level (`CarverRoof`, applied by `NoiseBasedChunkGeneratorMixin`).
A ravine there would become a canal, or drain a river.

`chunk_surface_level` is the exact surface rather than vanilla's estimate. Surface rules therefore place grass, sand
and snow at the right height.

The aquifers get `diffusicraft:<scale>/aquifer_surface` instead. On land it is the same surface. Under the sea it is
at most 12 blocks under sea level. Vanilla's aquifers fill a column with the sea only where its surface plus 8 is
below sea level, and leave anything shallower mostly dry. With the exact surface, every sea less than 9 blocks deep
became a dry plain dotted with dry pits.

### Rivers

Rivers come from the drainage of the coarse map and follow the fine terrain inside each tile (`worldgen/river/`).
Lakes fill the closed basins of the fine terrain away from them. Everything is computed on demand, on any thread, and
comes out the same.

- **Network.** `RiverNetwork` routes water over the coarse map, one cell per tile (7.7 km). Each land cell drains to
  its steepest lower neighbour, judged by its 5th-percentile elevation, which follows the valley floors. Pits are
  breached: a priority flood within 8 cells finds the cheapest way out, and the cells on it are lowered just enough to
  drain. Pits with no way out keep their rivers in a pond. The discharge of a cell is the runoff of everything
  upstream, the precipitation less an evapotranspiration that grows with temperature. A river starts at 4 m³/s.
- **Paths.** `CellRivers` draws the rivers inside a tile. A river crosses a side of the tile at that side's lowest
  point, running straight across, so the tiles on both sides agree without looking at each other. Inside, it follows
  the cheapest path, where low ground is cheap, and tributaries merge into a tree. On level ground the cost carries
  noise, so rivers meander instead of following the pixel grid; on steep ground they stay in their valleys. Paths are
  smoothed, and the water level only ever goes down along them.
- **Width and depth.** About 4√Q meters wide for a discharge of Q m³/s, like real rivers. Streams are widened three
  times at the standard scale and one and a half times at the grand scale so they stay visible, and nothing gets
  wider than 480 m. The depth grows with the width, up to 4, 6 and 10 blocks at the three scales, and shelves towards
  the banks.
- **Columns.** `Rivers.column` says what a river does to a column. The channel is cut down to the water, or built up
  on a footing 4 blocks deep where the river runs above the ground. Banks blend into the terrain over 1.5 blocks
  across per block of height. Where the water steps down a block, it runs over the step as a little fall; the banks
  within 2 blocks of the step rise above the higher water, which would otherwise spill over them and run beside the
  river as a second stream.
- **Water and beds.** The material rule `diffusicraft:river` (`RiverRule`) fills the channel with water on a bed of
  sand and clay in the lowlands, and mostly gravel higher up. The valley, the channel and 3 blocks on each side, takes
  a river biome.
- **Lakes.** `CellLakes` finds the closed basins of a tile with a priority flood, which gives the height at which each
  would spill over. Where the precipitation reaches the evapotranspiration, a basin fills to that height. Drier basins
  fill less, down to nothing where the precipitation is 60% of the evapotranspiration, so deserts keep their dry
  basins. A lake is at least 1 m deep and two pixels across. It lies at least 4 pixels inside its tile, so the tile
  alone decides its columns, and beyond the reach of any river, which would drain it.
- **Lake columns.** A column is in a lake where one of the four pixels around it is and its ground is below the water.
  The water stops at the last whole block below the lake's level, so it never stands above the rim of its basin, and
  a lake shallower than a block does not show. The lake takes the rivers' water rule, on a bed mostly of clay and
  sand, but no river biome. Its shore, two pixels wide, keeps its ground but is solid from below the water up, so no
  cave entrance drains the lake. Where the shore still lies below the water, it rises into a bank as the rivers'
  banks do.
- **Limits.** Where the fine terrain has no valley along the coarse drainage, the river runs on a built-up ridge like
  an embankment. Rivers do not run through lakes: a basin near a river stays dry, and lakes neither feed rivers nor
  take them in. Lakes are left out where their basin reaches the edge of a tile.

### The ground

The material rule `diffusicraft:<scale>` is vanilla's overworld rule with the underground replaced. Bedrock and the
large copper and iron veins stay vanilla. Then `diffusicraft:strata` (`StrataRule`) runs vanilla's surface rules
first, so biomes keep their grass, sand, terracotta and snow. Where they ask for nothing, or for plain stone as
mountain biomes do, the block comes from the `Geology` model. Mountain sides and cliffs therefore show the strata.

**Cliffs and scree.** Soil does not hold on slopes much steeper than 50°, and what weathers out of a cliff falls and
piles up below it. `Erosion` reads the slope of each column from the terrain, in blocks per block as the player sees
it, and changes the top of the ground above the water:

- **Cliffs.** Steeper than 1.2 (about 50°), the soil, sand or snow the biome lays there gives way to the local rock.
- **Scree.** Between 0.45 and 1.2 (24° to 50°), where the ground rises at least as steeply as 45° over the 12 blocks
  uphill, the top three blocks are scree: a little more than half gravel, the rest broken rock (cobblestone, cobbled
  deepslate, or the local rock itself).
- **Elsewhere** the biome's ground stays.

The thresholds wander by up to a fifth over some 16 blocks, so the edges of cliffs and scree follow the land rather than
the terrain's grid. Under the water, the beds keep their sand and gravel. Distant Horizons' LODs run the same rule, so
cliffs show from afar too, except in the farthest LODs, which read the coarse map and are too smooth for them.

The slopes are those the player sees, so the scale matters. The Standard world raises the land 1.5 times more than it
widens it up to 1,000 m, and its steep flanks get the most cliffs; above that it squeezes the height, and its high
ground is gentle. At Earth 1:1 the slopes are the real ones over 30 m, which are seldom steeper than 50°, and the
sides of the [gullies](#gullies) only a little steeper: cliffs are rare there and keep to the highest ranges (see
`ErosionSurveyTest` under [Tests](#tests)).

`Geology` is a pure function of the geology seed, the scale and the column's terrain. It holds no Minecraft classes and
is tested on its own. From the top down:

| Layer | Where | Rocks |
|---|---|---|
| Sedimentary cover | From the surface down to a depth set by the basin: up to about 2 km of rock under lowlands and shelves, but never more than about 210 blocks below the sea; a thin skin on rugged uplands | Beds 1–8 blocks thick that follow the height, not the surface, so valley walls cut through them. The basin decides the rocks: limestone, chalk, shale and sandstone where there was a sea; sandstone and shale on land; coal seams in swampy basins; red sandstone and rock salt where it is hot and dry |
| Fold belts | Rugged land over thick sediment | The beds fold steeply; deep shale turns to slate, then schist, and limestone to marble |
| Basement | Below the cover, down to about y = 4 | Granite, gneiss, stone, schist or andesite with tuff beds, by region, with lenses of diorite, granite, andesite or marble |
| Lower crust | From about y = 4 | Deepslate, then gabbro |
| Mantle | Above the magma layer | Peridotite, pierced by rare kimberlite pipes; a quarter of the pipes reach the surface |
| Magma layer | The last blocks above the bedrock | Magma blocks, blackstone and basalt |

The deep layers share the room between y = 0 and the bottom of the world, so they are thicker in the tall worlds.
Thicknesses in meters follow the world's vertical scale down to the sea, so mountains keep the full height of their
strata. Below the sea, they use at least 10 m per block. A lowland's cover therefore ends within about 210 blocks of
the sea in every world type: without that, Earth 1:1's 1.7 km of sediment would run past the bottom of the world and
leave no basement under the lowlands. Caves fill with lava up to 10 blocks above the bottom of the world (`NoiseBasedChunkGeneratorMixin`). Vanilla fixes
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
join `#minecraft:base_stone_overworld`, so moss, sculk, dripstone and vanilla's ore blobs spread through them. They and
salt crust also join `#minecraft:blocks_motion_no_leaves`. The heightmaps that features, snow and rain read count only
the blocks listed there, and would otherwise take the ground to lie under the rock.

**Soils.** Under the top block, where vanilla puts dirt, some biomes have a soil of their own: peat under the moors
and the tundra, terra rossa under the maquis, loess under the steppe and laterite under the jungles, the savannas and
the cloud forest. Peat and terra rossa also lie bare on top in patches. Soils count as dirt (`#minecraft:dirt`), so
saplings and plants grow on them as on dirt, and they dig with a shovel. Peat burns in a furnace for half as long
as coal (`context_int_provider/cooking/time_peat.json`).

**New plants.** Heather and cottongrass of the moors, lavender of the maquis, edelweiss and gentian above the tree
line, and salt crust, the ground of the salt flats. The flowers join `#minecraft:small_flowers`, so bees visit them;
the plants burn, compost and craft into dye (lavender purple, gentian blue, edelweiss white, heather magenta).
Cottongrass is a grass: it drops only to shears or silk touch, and sheep graze on it and on the heather. They have no
potted form yet.

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

Distant Horizons' far LODs use the same scope (`worldgen.lod.LodSampler`). There the terrain's surface and the rock
strata read the coarse map too, and the rivers are left out, so a far LOD runs no inference beyond the coarse stage.

Other searches still read the full terrain and can take a while on new land:

- `/locate biome`;
- `/locate structure`;
- explorer maps;
- eyes of ender.

## Files

| Path | What |
|---|---|
| `worldgen/` | `WorldScale`, `TerrainSource`, `TerrainField`, `TerrainSurface` (the ground, with its gullies), `ClimateMapping`, `CoarseClimate`, `TerrainFields` (one field per seed, model lifetime), `PipelineSource`, `CarverRoof`, `DiffusicraftWorldgen` (registration) |
| `mixin/`, `client/mixin/` | The default world type, the check for missing models on Create, the coarse stronghold search, the lava level, the carver roof, the world attached to climate samplers, the lapse rate, the material rules run outside chunks and the settings' own material rule, which tells a Diffusicraft world when TerraBlender wraps it |
| `worldgen/density/` | The density function types: `SurfaceFunction`, `ClimateFunction`, `SpawnSuitabilityFunction`, `HollowsFunction`, `RiverFunction` |
| `worldgen/river/` | `Rivers` (what a river or lake does to a column), `RiverNetwork` (the coarse drainage), `CellRivers` (the paths inside a tile), `CellLakes` (the lakes inside a tile), `CellFlows`, `RiverColumn` |
| `worldgen/biome/` | `ClimateBiomeSource` (`diffusicraft:climate`), `BiomeRefiner` (the rules), `BiomeClimate`, `BiomeWorld`, `HeightTemperature` |
| `worldgen/geology/` | `Geology` (the rock and ore model), `Rock`, `Ore`, `Hollows` (the cavern model), `Erosion` (cliffs and scree), `Gullies`, `GeoNoise` |
| `worldgen/material/` | `StrataRule`, the material rule type that applies the geology and lights the Hollows; `RiverRule`, the rivers' water and beds |
| `worldgen/lod/` | `LodSampler`, `LodColumn` and `TreeCover`: columns of the finished surface and its trees without chunks, for LOD mods |
| `compat/dh/` | The Distant Horizons generator; loaded only with Distant Horizons. See [COMPATIBILITY.md](COMPATIBILITY.md) |
| `block/` | `DiffusicraftBlocks`, the new rocks, soils, plants and salt crust |
| `data/diffusicraft/worldgen/` | Noise settings, material rules, density functions and world presets for each scale |
| `data/diffusicraft/dimension_type/` | The tall overworld dimension types (grand and earth) |
| `data/diffusicraft/worldgen/{biome,feature,placed_feature}/`, `material_rule/biome_surface.json`, biome tags | The new biomes, their trees and plants, their ground and their tags |
| `tools/worldgen_data.py` | Writes the noise settings, material rules, density functions, presets and dimension types. Edit it, run `python3 tools/worldgen_data.py`, and commit the output. |
| `tools/biomes.py` | Writes the new biomes, their features, surface rule and tags. Edit it, run `python3 tools/biomes.py`, and commit the output. |
| `tools/block_assets.py` | Draws the textures of the rocks, soils, plants and salt crust, and writes the block states, models, loot tables, dye recipes, peat's burn time and tags of the blocks. `--preview sheet.png` writes a sheet of the textures for review. |

## Lifetime and storage

- **Loading.** The models load when the first chunk needs terrain, not when the world opens.
- **Unloading.** They are freed when the server stops, which gives the GPU memory back to the game.
- **Disk cache.** Pipeline windows are cached in `<world>/diffusicraft/<model revision>/<terrain seed>/`. Reopening a
  world is fast, and the terrain stays the same even if newer models are installed later.

## Tests

- `GeologyTest` checks the layer order, that lowlands are covered in sediment, that Earth 1:1 keeps the standard
  world's layers below the sea and its own scale above it, determinism, ore rarity and diamond depth, and the sampling
  speed.
- `GeologySurveyTest` needs the models. It runs the geology over the same real land at every scale, checks that the
  tall worlds keep about as much basement under the land as the standard world, and draws a cross-section of each to
  `build/geology`. `-Pdiffusicraft.geologyStore=<dir>` keeps the terrain on disk for later runs, in a folder per seed.
- `GulliesTest` checks that only Earth 1:1 is fine enough for gullies, that gentle ground stays smooth, that steeper
  slopes are cut deeper but never past the deepest a gully can go, that the ground changes about three times as
  much across a slope as down it, and that the ground is continuous and set by the seed. `TerrainFieldTest` checks
  the spline's gradient and that only Earth 1:1 changes its ground.
- `ErosionTest` checks that gentle ground keeps its soil, steep ground shows the rock in any direction, and scree
  lies at the foot of a cliff, whichever way it faces, and not on the flat beyond.
- `ErosionSurveyTest` needs the models. At every scale it finds the tile near the origin whose ground rises the most in
  blocks and sorts its land into soil, cliffs and scree. With seed 42 that tile is 93% soil, 2.6% cliffs and 4.4%
  scree in the Standard world, 97% soil, 0.3% cliffs and 2.5% scree at Grand, and nearly all soil at Earth 1:1. The
  highest tile near the origin, which the Standard world squeezes flat, is 3.8% cliffs and 5% scree at Grand and
  1.2% cliffs and 2.3% scree at Earth 1:1.
- `HollowsTest` checks that every kind appears, the share of open ground, the roof and the bedrock, the lava seas,
  the height of the Grand Hollows in the tall worlds, that the rock under their floors and over their ceilings counts
  as part of them, determinism and the sampling speed.
- `RiversTest` runs the river model over an analytic valley. It checks that water runs down to the sea, that the
  discharge is the runoff upstream, that neighbouring cells agree on every crossing, that pits are breached, that a
  river meets itself across tile borders, that the banks hold the water where it steps down, that lakes fill the
  hollows between the hills and their shores hold them, that a dry climate leaves the hollows dry, that every thread
  gets the same rivers, and that the width grows with the discharge.
- `CellLakesTest` fills bowls: to the rim in a wet climate, partly or not at all in a dry one. A bowl at the edge of
  the tile, at or below sea level, too shallow or too small holds no lake, and two bowls are two lakes.
- `RiverMapTest` needs the models. It surveys the rivers of a real world and draws maps of them and their lakes to
  `build/river-map`, to look at them without starting the game.
- `BiomeRefinerTest` checks each rule on real climates: arctic lowland, the Alps above the tree line, the Andes'
  cloud forest, the Pacific Northwest, the Mediterranean, the Gobi and the steppe, Dartmoor and the Pennines, the
  Lüneburg Heath, Bonneville and Uyuni, and tropical coasts.
- `WorldScaleTest`, `ClimateMappingTest` and `TerrainFieldTest` run with `./gradlew build` and need no models.
  `TerrainFieldTest` uses an analytic terrain to check:
  - interpolation;
  - continuity across tile borders;
  - eviction;
  - concurrent tile computation;
  - the coarse column.
- `WorldGenerationGameTest` is a client game test and needs the models. It creates a world with the default settings and
  checks that it uses Diffusicraft and that the heightmaps count all of Diffusicraft's full blocks. It then saves a
  biome map, a height map, in-game views around the spawn and views of the largest river near the spawn, the steepest
  cliff, each of Diffusicraft's biomes on the map, the tallest Grand Hollow and a lava sea nearby to
  `build/run/clientGameTest/screenshots`. The top of the cliff must be bare rock. It generates on the CPU unless
  `-Pdiffusicraft.device` names another device.
