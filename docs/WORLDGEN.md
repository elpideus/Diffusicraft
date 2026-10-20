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
splines, so the surface stays smooth at any scale. Finer detail comes from the rock: in every world the hard beds of
the sedimentary cover stand out of steep slopes as [ledges](#ledges), and at Earth 1:1 [gullies](#gullies) cut the
slopes more finely than the pixels.

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
Geography (per seed): continents, plates, hotspots and climate, the map the coarse model is conditioned on
   ▼
WorldPipeline (30 m pixels, per seed)
   │  PipelineSource: creates the pipeline on first use; caches windows under <world>/diffusicraft/
   ▼
TerrainField: 256² pixel tiles, Catmull-Rom elevation, plus an 8-pixel grid of
   │          mean elevation, relief and sea-level climate, and the distance to the coastline
   ▼
TerrainSurface: the ground in blocks for one scale, with ledges, sea cliffs and stacks, and gullies at Earth 1:1
   ▼
density functions: diffusicraft:surface, diffusicraft:climate,
   │                diffusicraft:hollows (the Hollows model), diffusicraft:river (the Rivers model),
   │                diffusicraft:coast (the sea cliffs and stacks, block by block)
   ▼
noise settings diffusicraft:<scale>: noise router, aquifers, material rule diffusicraft:<scale>
   ▼                                  (vanilla's surface rules + diffusicraft:strata, the Geology model,
   ▼                                   + diffusicraft:river, the rivers' water and beds)
vanilla NoiseBasedChunkGenerator: caves, aquifers, ores, surface blocks, features and structures
```

### The geography

The coarse model draws the terrain and the climate to fit a conditioning map, at 7.7 km per pixel:

- the elevation;
- the temperature and its seasonality;
- the annual precipitation and its seasonality.

The reference implementation conditions it on noise, which scatters landmasses of one size across the sea. Diffusicraft
draws the map from `terrain.geography.Geography` instead. It is a model of a planet's geography, cheap enough to
evaluate anywhere:

- **`Continents`.** Octaves of gradient noise run from 12,000 km down to 24 km and follow the spectrum of real
  topography, so one world holds supercontinents, continents, subcontinents and islands of every size. Domain warping
  bends them into arms, peninsulas and gulfs. A slowly varying ruggedness keeps some coasts long and smooth, as around
  Africa, and breaks others into archipelagos and shelf seas, as in the Aegean.
- **`Hypsometry`.** Heights are matched quantile for quantile to the real distribution the coarse model was trained
  on: a little over two fifths land, broad platforms, narrow shelves and wide abyssal plains. The land rises from the
  coast over a coastal plain.
- **`Tectonics`.** Plates are the cells of a warped Voronoi diagram, their sites about 2,400 km apart. Where they
  meet, they form the following:
  - a trench and a cordillera inland where an ocean sinks under a continent, as the Andes;
  - an arc of volcanic islands where two oceans meet, as the Aleutians;
  - a wide belt of mountains and plateaus where continents collide, as the Himalaya and Tibet;
  - a mid-ocean ridge or a rift valley where plates part.

  Old boundaries are worn down, like the Appalachians.
- **`Hotspots`.** Chains of volcanic islands sink into seamounts as they age. On a continent, a hotspot leaves a broad
  swell.
- **`ClimateModel`.** Winds blow in the real belts: the trades, the westerlies and the polar easterlies, with
  monsoons. They carry moisture inland from the sea and rain it out over the land and the mountains, which leaves rain
  shadows behind the ranges. Cold currents dry subtropical west coasts and warm ones wet east coasts. The temperature
  is milder by the sea and more extreme deep inside a continent.

The latitude runs along the z axis at 111 km per degree, at every world scale. The origin lies between 35° and 50°
north, and the continents are shifted so that the place the seed drew for the spawn lies there (see
[The world spawn](#the-world-spawn)).

The windows cached on disk go under `map<Geography.VERSION>`. Bump the version whenever the map of a seed changes.
A world started before a change keeps the chunks it has already generated and generates new ones from the new map, so
the two meet at a seam. Nothing has been released yet, so no world is migrated.

### `TerrainField`

`TerrainField` holds one seed's terrain. It reads the pipeline in tiles of 256 × 256 pixels (7.7 km), with a
32-pixel margin, through `Glaciation`, which drowns the valleys of cold mountain coasts into [fjords](#coasts). For
each tile it keeps three things:

- **The padded elevation.** Columns sample it with a Catmull-Rom spline.
- **A grid every 8 pixels (240 m).** It holds slowly varying values, sampled bilinearly:
  - the mean elevation and relief (standard deviation) over a 1.4 km box, from summed-area tables;
  - the climate reduced to sea level, plus its lapse rate;
  - how strongly glaciers lowered the ground.

  The temperature at a column is then the sea-level temperature plus the lapse rate times the column's own
  elevation, so summits are cold even between grid points.
- **The distance to the coastline**, where there is a coast within 360 m of the tile. See [Coasts](#coasts).

Tiles are computed once, by the first thread that needs them; other threads wait for that result. The 96 most recently
used tiles stay in memory, about 40 MB, plus 134 KB for each tile with a coast: up to 13 MB more along the sea. The
terrain seed is derived from the world seed, so worlds with the same seed match.

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
  the widest rise and fall some 3 blocks; all three together never pass 9.5.
- **Rock and climate.** They decide the gullies' character:

  | | Bare (dry or frozen) | Covered (wet and mild) |
  |---|---|---|
  | **Soft rock** | Dense, sharp rills, as in badlands | Rounded hills |
  | **Hard rock** | Deep, sharp crags | Deep gullies, less sharp |

  Hard rock is cut deeper: the widest octave by 1.15 times its usual depth in granite and 0.85 in flat beds. Soil and
  plants creep down the slopes and round them off, and hide the finer octaves: these are 1.4 times as deep on bare
  soft rock and 0.4 under a full cover (1 and 0.6 on hard rock). The sharpness takes each wave from a cosine to a
  triangle, with sharp ridges and V-shaped troughs: bare ground is fully sharp, hard rock under cover 0.6 and soft
  rock under cover round.

  The hardness is the rock's under the slope, from `Geology.hardness`: 0 for flat sedimentary beds, 0.35 for folded
  beds and schist, 0.6 for volcanic rock, 0.65 for plain stone, 0.85 for gneiss and 1 for granite. It spreads over
  8 blocks across the contact between the cover and the basement, so a slope that cuts down into the basement
  sharpens without a seam. The cover is the climate's: it takes rain, by the aridity ratio the biomes use (none below
  0.5, desert, and full above 1.5), and warmth (none below −4 °C and full above 4 °C). Deserts, tundra and the high
  summits are bare.

Octaves narrower than 12 blocks are left out: the density is interpolated over cells 4 blocks wide and would turn
them into zigzags. That keeps all three at Earth 1:1 and none in the other worlds, whose blocks are 5 and 15 m wide.

`TerrainSurface` puts the gullies on the terrain, then the [ledges](#ledges), and everything that needs the ground
reads it there: the density, the ceiling of each chunk, the rivers and lakes, the Hollows, the materials (so the sides
of the gullies can bare the rock, see [The ground](#the-ground)) and the LODs whose columns are at most 4 blocks apart.
The biomes and the climate keep the smooth elevation; gullies and ledges are too small to change them. The slope comes
from the spline's own derivative, so gentle ground costs one sample. Steep ground costs about 0.2 µs more for the rock,
the climate and the ledges, and 0.6 µs at Earth 1:1 with the gullies. The rocks come from the geology seed, which
`TerrainFields` gives each field next to the terrain seed, so the gullies and the ledges follow the same rocks the
world shows.

### Ledges

Where a slope cuts through the sedimentary cover, its hard beds stand out and the soft beds between them recede, as on
the walls of the Grand Canyon: cliffs of sandstone and limestone, with gentler slopes and benches of shale, chalk, salt
or coal between them. `Geology.ledge` moves the ground to make them, in every world:

- **Which beds.** Thick beds of sandstone, red sandstone and limestone are hard; shale, chalk, rock salt and coal are
  soft. A thin bed (1 or 2 blocks) goes with the beds around it: between two hard beds it is a parting or a coal seam in
  their cliff, and next to a soft bed it recedes with it.
- **Profile.** Each run of hard beds pairs with the run of soft beds above it. The top of the hard run stands as a
  cliff three times as steep as the slope, up to 15 blocks tall; a thicker run keeps the slope's pace below that, as
  under a cliff whose foot is buried in its own debris. The soft run brings the ground back to the slope's line by its
  top, at most five times gentler than the slope, so a cliff stands out no further than the beds above it can recede.
  The mean is taken off, so the ledges never raise or lower a hillside as a whole; they move the ground by at most 10
  blocks.
- **On the beds.** The beds wander up and down with the warp that tilts and folds them, and each block takes the bed at
  its bottom. The ledges follow the blocks' beds, so a cliff is made of the rock it stands in. Where the beds step a
  block, the ledges move across over a quarter of the step, so the ground has no seams.
- **Where.** On slopes steeper than 0.15 blocks per block, in full from 0.5 (27°). Soil and plants creep down the
  slope and round the ledges off: under a full [cover](#gullies) they keep 40% of their relief. Weaker ledges stand on
  the same beds, because the profile is scaled along the beds rather than in height. The ledges fade out over 8
  blocks at the base of the cover and over the deepslate, and in fold belts, whose beds tilt too steeply for them.

The beds are 1 to 8 blocks thick in every world, so a cliff of 15 blocks is 15 m at Earth 1:1 and 150 m in the
Standard world, as tall as the Grand Canyon's. The density's 4-block cells turn the narrowest cliffs into steep ramps,
but those still rise through the same beds, and [Erosion](#the-ground) bares the rock on them.

### Biome parameters

The biome source starts from vanilla's `multi_noise` overworld preset, which reads six parameters. `ClimateMapping`
gives each one a physical meaning, with breakpoints at the edges of vanilla's own bands:

| Parameter | Computed from |
|---|---|
| temperature | Annual mean at the column's elevation, on its slope. The hot band (desert, badlands) also requires dry air. |
| humidity (vegetation) | Precipitation over the Köppen aridity threshold (see [Summer rain](#summer-rain) below), less what the sun dries out of a sunny slope. |
| continentalness | Offshore: the water depth. On the [coast](#coasts): the height above the sea. Inland: the mean elevation. |
| erosion | Local relief: rugged land is low, flat land is high. Flat, low and wet land is put in the swamp band. |
| weirdness | Whether the column is a local peak or a hollow. The sign comes from smooth noise with a wavelength of 12 km of real ground and only picks variants, in patches kilometres across. |
| depth | Blocks below the surface, capped at 60 blocks above the sea. |

Some adjustments move biomes away from vanilla's defaults:

- **Sun and shade.** The climate is the one the ground feels on its slope (`ClimateMapping.expose`). A slope that
  faces the sun is warmer, and the sun dries out its ground; one that faces away stays cool and moist. The world lies
  in the northern hemisphere, so the sunny slopes face south. The effect grows with the sine of the slope times the
  cosine of the angle between the way it faces and the south. At most, for a cliff facing straight south or north, it
  adds or takes off 3 °C and 40% of the rain; a 30° slope gets half that. The slope is the hillside's
  (`TerrainField.slope`, over 960 m), not every gully's, so the warm and cool sides follow the valleys in belts
  kilometres long; measured over 120 m, a third of the dark forest came in patches under a square kilometre. The
  coarse map has none. It feeds both vanilla's table and the [refiner](#refined-biomes) below. Some effects:
  - the tree line and the snow climb a few hundred meters higher on the sunny side of a mountain;
  - in dry climates the woods keep to the shaded slopes and grass or steppe takes the sunny ones, as in the foothills
    of the Rockies;
  - Mediterranean scrub spreads up the sunny slopes.
- <a id="summer-rain"></a>**Summer rain.** The aridity threshold is Köppen's: `20 T + 140` mm, where T is the annual
  mean in °C, for rain that falls through the year, and `20 T + 280` mm where at least 70% of it falls in the summer
  half, when the heat takes more of it back. The climate says how unevenly the rain falls (BIO15) but not when. Where
  the coldest month freezes, uneven rain falls in summer, since cold air holds little water, as in Mongolia and on the
  Great Plains; where it stays above 15 °C, it comes with the monsoon. There the summer threshold applies, as far as
  the rain is uneven: the wetter half of a sinusoidal year holds one half plus BIO15 × √2/π of the rain. Between the
  two, uneven rain may be Mediterranean and fall in winter, so the year-round threshold stands. The threshold never
  falls below 140 mm, so tundra counts as moderately dry rather than as wet as a rainforest. The season only decides
  near the threshold: it counts in full up to the year-round one and fades out by two and a half times it, so the
  wettest taigas, and the peat of the West Siberian Plain and of northern Alberta, which also get most of their rain
  in summer, stay as wet as they are. With the year-round threshold, a plain with 340 mm of summer rain at 6.5 °C,
  January at −10 °C, came out as one forest 64 km across; now 92% of it is steppe, as on the plains of Inner
  Mongolia, and the woods keep to the higher, wetter hills on its edge, where 380 mm falls. The soils and the ground
  cover read the same ratio.
- **Snow.** Snowy slopes and peaks need temperatures below 1 °C. A warmer mountain is moved into the plateau and
  forest bands. Between 1 and 8 °C, a summit more than 250 m above the land around it becomes stony peaks; a lower
  one keeps the woods of its own band. Vanilla gives stony peaks only to its warm band, which gives jungle wherever
  vanilla picks no peak biome, so a summit is moved there only where vanilla does.
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
swap it for one of sixteen new biomes. The first rule that matches wins:

| Biome | Climate | Replaces |
|---|---|---|
| **Fjord** | Water that was land before the glaciers lowered it, where they lowered ground at sea level by at least 120 m (see [Coasts](#coasts)) | Oceans and cold oceans, deep or not |
| *(wetlands)* | Vanilla's swamp band: mangroves on tropical coasts (coldest month at least 15 °C, below 10 m), páramo on tropical mountains, bogs where the warmest month stays below 20 °C and the summers are not dry, tundra beyond the tree line, swamps elsewhere | Swamps and mangroves |
| **Cloud forest** | Tropical mountainsides: 1000–3500 m, 10–20 °C, little seasonality, at least 1500 mm of rain | Any land but peaks, deserts and badlands |
| *(vanilla snow and ice)* | Warmest month below 3 °C, or a year below −10 °C | Kept as vanilla picked them |
| **Salt flat** | Desert (aridity below 0.5) with less than 4 m of relief: the dry beds of old lakes, up on the cold Altiplano too | Plains, forests, taigas, savannas, deserts |
| **Páramo**, **puna** | Tropical mountains (little seasonality) from 2500 m where the year averages below 10 °C. Every month is a growing season there, so the tree line follows the year rather than the summer, and the cloud forest gives way to them. Páramo with at least three times the rain of the aridity threshold, as in the northern Andes or on Mount Kenya; puna where it is drier, as on the Altiplano or Mauna Kea | Any land, peaks included |
| **Tundra**, **alpine tundra** | Beyond the tree line: warmest month below 10 °C. Alpine on mountains | Any land, peaks included |
| *(montane forest)* | Humid mountainsides (aridity at least 1) below the subalpine belt, where the warmest month passes 14 °C, some 600 m below the tree line. Vanilla's plateau row puts meadows there between the woods of its lowland row, in strips along every valley side; they become those woods: forest, and with at least twice the rain of the aridity threshold birch forest, or taiga below 5 °C. Meadows stay in the subalpine belt and on drier ground | Meadows |
| **Monsoon forest** | Frost-free lowland (coldest month at least 15 °C) with at least the rain of the aridity threshold. Vanilla only has temperate woods for it between its savannas and its jungles; jungles count too where the rain is strongly seasonal (BIO15 at least 80 %) and below 2000 mm, as in India and Thailand. Wetter jungles stay, as in the Amazon | Forests and taigas, jungles |
| **Laurel forest** | Jungle where the coldest month falls below 15 °C, so frost can come. Vanilla's jungles start at a yearly mean of 15 °C, which takes in the humid subtropics, as around Shanghai or Atlanta | Jungles |
| **Moorland** | Rolling uplands (300–1500 m, less than 90 m of relief) with cool summers (warmest month below 15.5 °C), coldest month above −6 °C, at least 800 mm of rain | Plains, forests, taigas, savannas, hills and meadows |
| **Bog** | The flattest land (less than 6 m of relief) below 1500 m, with cool summers (warmest month below 20 °C) and at least twice the rain of the aridity threshold, as in Finland, the West Siberian Plain and the Hudson Bay Lowlands. Not where maquis grows: peat needs ground wet all summer, and a Mediterranean summer is dry. Vanilla puts swamps only on low ground; bogs grow on flat land at any height below the mountains | Plains, forests, taigas, savannas |
| **Temperate rainforest** | 4–14 °C, coldest month above −3 °C, at least 1600 mm of rain | Plains, forests, taigas, savannas, slopes and hills |
| **Maquis** | 12–21 °C, coldest month at least 2 °C, 250–1000 mm of strongly seasonal rain, below 1200 m | The same, deserts and meadows too, but no other slopes |
| **Heath** | Oceanic lowland below 300 m: 6–12 °C, coldest month above −4 °C, 550–1100 mm of rain. Only where the variant noise is below −0.3, so it comes in patches among the woods | Plains, forests, taigas, savannas |
| **Cold desert** | Desert (aridity below 0.5) cooler than 12 °C | Plains, forests, taigas, savannas, deserts |
| **Steppe** | Semi-arid (aridity below 1) and cooler than 18 °C | Plains, forests, taigas, savannas |

Frozen and warm oceans, shores, rivers, caves and the mushroom fields are never touched, and neither is any biome a mod adds to the
table: only vanilla's own biomes are refined. `BiomeRefiner` is pure and unit-tested.

**Snow line.** Vanilla cools every biome by 0.05 per 40 blocks above y = 80 (`Biome.getHeightAdjustedTemperature`),
so snow falls on any high ground. Diffusicraft's biomes already come from the climate at the real elevation, so that
would count the cold twice: `BiomeMixin` turns the lapse rate off while a Diffusicraft world runs
(`HeightTemperature`). Snow therefore lies where the climate is cold, and the tundra puts the tree line below the snow
line, as on real mountains. On a dedicated server the clients keep vanilla's lapse rate, so high ground can show snow
falling where the server has rain; only the weather on screen differs.

**Grand Hollows.** Each Grand and Ancient Grand Hollow is one cave biome of its own from floor to ceiling, picked
from the climate over it (see [Life in the Hollows](#life-in-the-hollows)), in place of whatever vanilla would pick
there. So are their pillars, the smaller caves that cut into them and the rock around them
(`Hollows.grandHollowNear`): a few dozen blocks of their walls, and at least a dozen blocks under their floors and
over their ceilings. Biomes that other mods add to the parameter list stay where vanilla puts them. The Deep Grand
Hollows over the lava seas keep vanilla's picks, but not the deep dark, which would carpet their floors in sculk:
there the source picks what vanilla would pick halfway down to the deep dark, lush or dripstone caves where the climate
calls for them and the biome above otherwise. The game looks a biome up as much as a quart off, so deep dark left a
few blocks under a floor would put sculk on it, and sculk veins spread up to twenty blocks from wherever the deep dark
starts them.

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
| Páramo | Meadow | Frailejones, the odd queñua, tussock grass, gentians, groundsels and lupins, moss cushions, dark pools, boulders; fog (192 blocks) under a grey sky | Grass with cushion bogs (moss) and coarse dirt over peat, rock where it is steep |
| Puna | Meadow | Golden ichu grass, tola scrub, yareta cushions among boulders, a rare queñua; herds of llamas, under a deep blue sky. Its villages are the desert's, whose sandstone looks like adobe | Grass with gravel and coarse dirt, rock where it is steep |
| Fjord | Cold ocean | Kelp and seagrass in deep teal water; the cold ocean's fish and squid, wrecks and ruins, but no monuments, which keep to the open deep sea | The cold ocean's |
| Monsoon forest | Jungle | Teak and sal, acacias, jungle bushes, bamboo, dry grass, drifts of fallen leaves, warm flowers; junglefowl (chickens), wild boar (pigs), parrots and ocelots | Grass with red laterite and coarse dirt showing, laterite below |
| Laurel forest | Forest | Laurels with dense, glossy crowns, live oaks hung with moss, azalea trees and bushes (camellias and rhododendrons), bamboo, ferns, fallen leaves, fireflies; wild boar (pigs), foxes, rabbits, armadillos and, rarely, pandas | Grass with leaf mould (podzol) and coarse dirt, red laterite below |
| Bog | Swamp | Dark pools of peaty water with lily pads, cottongrass, moss carpets, heather, a few spindly black spruces, Scots pines and birches, berries, fireflies; frogs, and the swamp's slimes, witches and bogged | Sphagnum (moss) with bare peat and grass, peat all the way down |

The alpine tundra flowers with edelweiss and gentians, the maquis with lavender, and cottongrass grows on the tundra and
the bogs. The frailejones of the páramo are pale oak: silver-green rosettes on trunks one to three blocks tall. The
queñuas (Polylepis), which grow higher than any other tree, are mangrove wood for their red bark, on gnarled, forking
trunks. The yareta of the puna is a cushion plant so dense that it looks like a green boulder: here it is one, of moss.
The teak stands on a tall, clear trunk under a round crown, and the laurels under dense domes. The live oaks of the
laurel forest are dark oak wood under oak leaves, hung with pale hanging moss for Spanish moss. The bog's pools are
vanilla's `waterlogged_vegetation_patch`, the lush caves' clay pools: it sinks a patch of peat into the ground and fills
its top with water wherever the water is held on every side.

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
- [the sea cliffs and stacks](#coasts) are set block by block: `diffusicraft:<scale>/coast_fill` and `coast_carve`;
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
A ravine there would become a canal, or drain a river. Everywhere, they leave a block of roof under sand and gravel,
which would otherwise hang over their holes: they carve after the surface rules, which keep the soil over the noise
caves.

`chunk_surface_level` is the exact surface rather than vanilla's estimate. Surface rules therefore place grass, sand
and snow at the right height.

The aquifers get `diffusicraft:<scale>/aquifer_surface` instead. On land it is the same surface. Under the sea it is
at most 12 blocks under sea level. Vanilla's aquifers fill a column with the sea only where its surface plus 8 is
below sea level, and leave anything shallower mostly dry. With the exact surface, every sea less than 9 blocks deep
became a dry plain dotted with dry pits.

### Rivers

Rivers come from the drainage of the coarse map and follow the fine terrain inside each tile (`worldgen/river/`).
Lakes fill the closed basins of the fine terrain: the rivers fill the basins they cross, the rain the others. The
depressions of the coarse map hold the great lakes. Everything is computed on demand, on any thread, and comes out
the same. Each thread keeps the columns of the chunk it is working on. A thread waiting for terrain can be handed other
generation work, which asks about another chunk; that work gets columns of its own, so the ones being computed stay in
place.

- **Network.** `RiverNetwork` routes water over the coarse map, one cell per tile (7.7 km). Each land cell drains to
  its steepest lower neighbour, judged by its 5th-percentile elevation, which follows the valley floors. Where that
  leaves a pit, a priority flood within 8 cells finds the level at which it spills. The depression holds a lake when
  the cells joined to the pit below that level are at most 16, within 8 cells either way, at least 2 m deep, and the
  runoff of the cells that drain into them outweighs what the lake's surface evaporates: 1.3 times the
  evapotranspiration, less the rain. Of nested depressions the outermost that holds a lake wins. The lake's cells take
  the level at which it spills, and the water crosses them to the cell it spills from and on into the cell it spills
  into. That cell and the ones after it on the flood's path are lowered evenly until the water reaches lower ground,
  the sea or another lake. Any other pit is breached: a priority flood within 8 cells finds the cheapest way out, and
  the cells on it are lowered just enough to drain. A lake's shores hold the flood of a pit higher than it; a lower
  lake is a way out. Pits with no way out keep their rivers in a pond. The discharge of a cell is the runoff of
  everything upstream, the precipitation less an evapotranspiration that grows with temperature. A river starts at
  4 m³/s. The network is computed in regions of 32 by 32 cells, each from the map 50 cells around it, which holds
  every lake and breach its cells depend on, so the regions agree on every lake without looking at each other. The
  first region of a world takes some 8 s on the CPU, most of it the coarse stage over the 1,200 km around it, and
  each further one about 2 s.
- **Paths.** `CellRivers` draws the rivers inside a tile. A river crosses a side of the tile at that side's lowest
  point, running straight across, so the tiles on both sides agree without looking at each other. Inside, it follows
  the cheapest path, where low ground is cheap, and tributaries merge into a tree. On level ground the cost carries
  noise, so rivers meander instead of following the pixel grid; on steep ground they stay in their valleys. Paths are
  smoothed, and the water level only ever goes down along them. A basin that can hold a lake counts as the surface of
  its water rather than its floor, so a river crosses it at the height of its lowest point and leaves over that point.
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
- **Lakes.** `CellBasins` finds the closed basins of a tile with a priority flood, which gives the height at which each
  would spill over. A basin can hold a lake when it is at least 1 m deep, two pixels across and above the sea, and
  lies at least 4 pixels inside its tile, so the tile alone decides its columns. `CellLakes` fills them. A river that
  crosses a basin fills it to the river's level, 1.5 m below its lowest point, whatever the climate, and flows on out
  over that point; where two rivers cross it, the lower sets the level. The rain fills the other basins: where the
  precipitation reaches the evapotranspiration, a basin fills to the brim. Drier basins fill less, down to nothing
  where the precipitation is 60% of the evapotranspiration, so deserts keep their dry basins. These lakes lie beyond
  the reach of any river, which would drain them.
- **Lakes across tiles.** `BlockLakes` takes the basins that reach past the side of a tile. A block is two by two
  tiles; blocks overlap by a tile, so every tile lies in four of them, and every side and corner lies inside one. One
  priority flood over a block's four padded tiles finds its basins; it keeps those that stay 4 pixels inside the block
  but not inside a single tile, which the tile already fills. A river that crosses an inner side of the block through
  such a basin, near the point where it crosses, fills it to its own level, at most 1.5 m below the spill, and the
  rivers of the tiles around run over the lake's surface. The rain fills the others as it fills a tile's basins, but
  only where no river runs in any of the tiles the lake reaches: the block does not draw the rivers of its tiles, and
  cannot tell whether one would pass beside the lake, below its water, and drain it. A tile takes the lakes of its four
  blocks; where they overlap, the highest water wins. They are all real hollows of the terrain, so lakes that touch meet
  at one level, and the tiles on both sides of a side, which share the blocks that reach it, agree on the water there.
  A tile and its rivers therefore read the eight tiles around it.
- **Great lakes.** `GreatLake` puts the water of a lake of the coarse map on the fine terrain. One priority flood over
  the padded tiles of all its cells, the water escaping into the sea and past the edge of their padding, finds the
  hollows the terrain holds. The water stands at one level for the whole lake, between the level of the cell it spills
  into and the level at which it spills on the coarse map: the level at which its hollows cover the most of the lake's
  tiles. It fills every hollow of the padded tiles that holds water at that level, and a tile takes it where it is
  higher than its own lakes. The rivers of the coarse map run across the lake at the level of its water and leave it
  level with it, so the lake is fed and its outlet is cut down from its surface. The rivers see the lake as they see
  the lakes of a block. The lake is found once, for the first tile in it or next to it, which waits for the terrain of
  all its tiles; the prefetcher computes them as soon as one of them is in its plan.
- **Lake columns.** A column is in a lake where one of the four pixels around it is and its ground is below the water.
  The lake decides such a column even within a river's reach, so the river flows into the lake and out again. The
  water of a lake the rain fills stops at the last whole block below the lake's level, so it never stands above the
  rim of its basin, and a lake shallower than a block does not show. A lake a river fills is level with the river,
  block for block, and the river's banks and the lake's shore hold it. The lake takes the rivers' water rule, on a bed
  mostly of clay and sand, but no river biome. Its shore, two pixels wide, keeps its ground but is solid from below the
  water up, so no cave entrance drains the lake. Where the shore still lies below the water, it rises into a bank as
  the rivers' banks do.
- **Limits.** Where the fine terrain has no valley along the coarse drainage, the river runs on a built-up ridge like
  an embankment. A basin beside a river, within its reach but not crossed by it, stays dry, and so does a basin across
  tiles that the rain would fill where a river runs in one of those tiles. A basin that reaches past a block's edge,
  15 km, stays dry unless it lies in a lake of the coarse map, and a river crosses it as it crosses any other ground.
  A depression of the coarse map larger than 16 cells, or wider than 8 cells (60 km), is breached rather than filled,
  so the largest lakes of the real world stay dry. The hollows of a great lake's tiles that do not hold water at its
  level stay dry, and so do those whose water spills past the padding of its tiles.

### Coasts

The sea lies at y = 63 in every world, and the coastline is where the terrain's elevation crosses zero. `Coastline`
traces it through each tile with marching squares, between the centres of the pixels, and keeps the distance from every
pixel to it: positive on land, negative at sea, up to 12 pixels (360 m). `TerrainField.coast` reads that distance
bilinearly, and its gradient, which points inland. Neighbouring tiles agree on it to the bit. Tracing a tile's coastline
takes about 1 ms on one core for a straight coast and 14 ms for a tile full of islands, against seconds of inference.

- **Beaches.** Vanilla's beach band (continentalness from −0.19 to −0.11) goes to the land within a beach's width of the
  coastline and lower than 5 m above the sea or 2.5 blocks, whichever is higher. The width wanders with slow noise from
  45 to 120 m, at least 5 blocks. Inside the band, continentalness is the height above the sea, so vanilla picks its
  beach, snowy beach or stony shore as it would. Before, the band went by height alone, and it covered most of the low
  coastal plains of the Standard world, far inland. Low, wet, flat land past the beach is still a swamp, or a bog.
- **Sea cliffs.** At 30 m per pixel the terrain's coasts are gentle: the steepest is a slope, which the density's
  4-block cells would smear further. So `TerrainSurface` builds cliffs where high land meets the sea. Across a strip 60
  m wide (at least 6 blocks: 6 in the Standard world, 12 at Grand, 60 at Earth 1:1), it gathers the rise of the ground
  into one face. Seaward of the face is a shelf a block above the water; landward, the ground at the end of the strip.
  The face wanders across the strip with noise 3 strips long, and the cliff fades into the ground's own details over the
  last 30% of the strip. A cliff needs:
  - land at least 4 m above the sea at the end of the strip, and 15 m for a full cliff (1.5 and 3 blocks at least);
  - a straight coast, not a spit or a narrow inlet, where the gradient of the distance shrinks;
  - a stretch of coast where noise 1.5 km across allows cliffs. They fade out over a few hundred meters between
    stretches.

  The density takes the ground of the cliff columns, and of the sea within 5 blocks of them, block by block
  (`CoastFunction`): `coast_fill` is solid from the lowest corner of the column's 4-block cell up to the exact ground,
  and `coast_carve` is air above it. So the face is sheer, and the rivers, which come after, still cut through it. The
  fill starts that low because deep in the rock a corner on the cliff is only as solid as its caves let it be, a
  fraction of how empty a corner out at sea is: between them, the face would be undercut from the sea floor up to the
  interpolated ground. Only the cells across a face reach down to the sea floor, so the caves under the cliff stay. The
  top of a cliff is too high for the beach band and keeps the biome inland; the shelf at its foot is beach. Like other
  cliffs, the face shows the rock (see [the ground](#the-ground)).
- **The sea floor.** The terrain's seas are flats near the shore: half of the sea 120 m from land is less than 0.3 m
  deep, and 480 m from land less than 3 m. A real sea floor deepens along a beach's equilibrium profile, `0.1 d^(2/3)`
  meters at `d` meters out (Dean's profile for fine sand): 2 m deep 100 m out and 5 m deep 360 m out. So the floor
  lies at least that deep out to `COAST_REACH`, and at least 5 m deep beyond it. Only the Earth 1:1 world shows it,
  5 blocks deep 360 blocks out; at the other scales the profile stays within the block of water the shallowest sea
  already has.
- **Sea stacks and arches.** The sea cuts cliffs back and leaves the harder parts standing offshore. `TerrainSurface`
  puts stacks off the cliffs: columns 6 to 25 m in radius, with sheer sides and a gently domed top, from half as high
  as the cliff behind them to as high. A stack stands past at least 20 m of open water and at most 150 m beyond that.
  The places for them lie on a jittered grid 150 m apart; each can hold a stack where it falls in that band, off a
  cliff at least half whole, on a stretch of coast that noise 5 km across favours. Hashes alone keep places from
  touching, before the terrain is read, so most places cost a few hashes. One stack in five is an arch: two to three
  times longer along the coast than wide, with an opening through it across the coast, rounded at the top, from the sea
  floor up to 40–60% of its height above the sea, under a roof at least 2 blocks thick. The density takes stacks and
  the 5 blocks around them block by block, as it does cliffs, and leaves the opening empty: the sea fills it below its
  level, the air above. The smaller worlds keep stacks 3 to 5 blocks across and 2 blocks off the coast, and arches
  with an opening at least 3 blocks high.
- **Fjords.** In the ice ages, glaciers flowing off cold mountain coasts cut their valleys far below the sea, which
  flooded them when the ice melted: the fjords of Norway, Patagonia, Alaska and New Zealand. The model draws those
  coasts smooth, so `Glaciation` wraps the terrain source and cuts the valleys after the fact. The ice cut each valley's
  floor down to a trough whose floor lies 400 m under the sea at the coast and climbs to sea level 12 coarse pixels (92
  km) inland. The valley floor is the coarse map's 5th percentile, or sea level if that is lower, and counts only as far
  as the coarse pixel is mountainous, rising 150 to 450 m above it: the lowest ground of a plateau or a broad valley is
  no glacier's floor. The cut is the floor's height above the trough, in full up to 1000 m and fading out by 1400 m, so
  high valleys keep their floors. In the mountains, ground up to 100 m above the valley floor is lowered by the full
  cut, less higher up and none from 400 m above it. On plains, which the ice spread over rather than cut into, only the
  floor itself is lowered in full, fading out 30 m above it, and in between as far as the coarse pixel is mountainous:
  the coastal lowland and the plains at the foot of the mountains keep their ground. Under the sea the lowering fades
  out at three times the cut down. It keeps every height in order, so it makes no hollow for the rivers to fill. The
  valleys below the trough flood up to about 20 km inland, the slopes above them steepen into walls, and the rivers
  reach the sea at the heads of the fjords. How far the ice reached a place, from 0 to 1, is the product of:
  - mountains, from the coarse map: within a coarse pixel of one that rises 150 to 450 m above its 5th percentile or
    the sea;
  - the sea, within 8 coarse pixels (61 km), fading out at 11 (84 km);
  - the cold: an annual mean at sea level below 8 °C, fading out at 12 °C.

  The first two are interpolated between the centres of the coarse pixels, with the valley floor and the cut, so they
  are smooth. Lowered ground is warmer by its lapse rate. The coarse map is lowered alike, so the rivers, the farthest
  LODs and the coarse searches see the same coasts. Water that was land before the ice is the fjord biome (see [Refined
  biomes](#refined-biomes)): the lowering keeps heights in order, so that is the water no deeper than the ice lowered
  ground at sea level, where it lowered it by at least 120 m. On two cold coasts of seed 42, each 92 km square, the ice
  drowned 476 and 119 km² of land, as deep as 450 m. Half the drowned water lies within a kilometre of the old coast,
  and the longest arms run 19 and 8 km from it by water.
- **Limits.** Distant Horizons' LODs show the cliffs, the sea floor and the stacks as the chunks do, except the
  farthest, which read the coarse map: it has no coastline, so no beaches, cliffs, profile or stacks there. The LODs
  hold one height per column, so arches show solid. The profile has no bars or troughs. Stacks have no notch at the
  waterline, and no boulders lie around them. Fjords have no sills: their troughs are deepest at the mouth, where real
  ones are shallowest, and climb steadily inland. They reach about 20 km inland, not the 100 km and more of the
  longest real ones: the floor the ice cut down from is the coarse map's, so the water ends where a valley climbs over
  a step or a saddle between coarse pixels. The trough beyond it holds a lake at sea level that counts as fjord, 15%
  and 5% of the drowned water on those two coasts, where a real lake behind a sill stands above the sea.

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
ground is gentle. At Earth 1:1 the slopes are the real ones over 30 m, which are seldom steeper than 50°: most of
its cliffs are the [ledges](#ledges) and the sides of the [gullies](#gullies), and they keep to the mountains (see
`ErosionSurveyTest` under [Tests](#tests)).

`Geology` is a pure function of the geology seed, the scale and the column's terrain. It holds no Minecraft classes and
is tested on its own. From the top down:

| Layer | Where | Rocks |
|---|---|---|
| Sedimentary cover | From the surface down to a depth set by the basin: up to about 2 km of rock under lowlands and shelves, but never more than about 210 blocks below the sea; a thin skin on rugged uplands | Beds 1–8 blocks thick that follow the height, not the surface, so valley walls cut through them and their hard beds stand out as [ledges](#ledges). The basin decides the rocks: limestone, chalk, shale and sandstone where there was a sea; sandstone and shale on land; coal seams in swampy basins; red sandstone and rock salt where it is hot and dry |
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

**Ores of tech mods.** Tech mods add the metals vanilla lacks: Create's zinc, and the tin, lead, silver, nickel, uranium
and platinum metals of Tech Reborn, Mekanism and others. Diffusicraft adds no blocks for them. When the tags load,
`ModOres` looks up each mineral's ores under the common tag `c:ores/<metal>` or, where no mod tags the metal, under the
mineral's own name (sphalerite, galena, bauxite, Tech Reborn's sheldonite). It keeps one block that sits in stone and
one that sits in deepslate, which it tells apart by the tags `c:ores_in_ground/stone` and `c:ores_in_ground/deepslate`.
For a block in neither, it asks the mods' own ore features what ground they put it in, by testing their targets against
stone and deepslate, and then looks for a "deepslate" in its id and at its map colour. Where several mods add the same
mineral, the first by id wins. Nether and End ores are never placed. Some of the bodies in a mineral's host rocks then
hold it instead of vanilla's ore:

| Host rock | Minerals of tech mods |
|---|---|
| Limestone | Zinc and lead, fluorite |
| Shale and slate | Zinc, lead and silver; uranium in black shale |
| Sandstone, red sandstone | Uranium, where groundwater left it |
| Marble | Rubies |
| Granite | Tin and tungsten in the veins at its top, uranium, fluorite |
| Andesite, tuff | Silver, zinc, lead |
| Stone | Zinc, tin, lead, silver, nickel |
| Gneiss | Tin, sapphires, uranium |
| Schist | Sapphires, tungsten |
| Deepslate | Nickel, silver, uranium, tungsten |
| Gabbro | Nickel, platinum, osmium, iridium |
| Peridotite | Nickel, the platinum metals, peridot |

With every mineral present, about one ore block in five of the lowland rock, outside the coal seams, is a tech mod's.
Without the mods the bodies stay vanilla's, so the rock is as rich either way, and the mods' own scattered ores still
generate as vanilla's do. Bauxite, the aluminium ore, is no body in the rock: where the wettest tropics have
weathered flat land (weathering at least three quarters of its fastest, about 1,100 mm of rain at 26 °C and more where
it is cooler, and slopes under 0.12), patches of the laterite some 80 blocks across hold it: almost half the blocks
of their lower subsoil are bauxite. The log names the blocks chosen: "The geology places the ores of other mods: …".

**New rocks.** Limestone, chalk, shale, rock salt, slate, marble, schist, gneiss, gabbro, peridotite and kimberlite
(`DiffusicraftBlocks`). They drop themselves and are mined with any pickaxe. Most count as stone for tools and
furnaces, and so do granite, diorite, andesite and tuff, because a basement can be granite for hundreds of blocks. They
join `#minecraft:base_stone_overworld`, so moss, sculk, dripstone and vanilla's ore blobs spread through them. They and
salt crust also join `#minecraft:blocks_motion_no_leaves`. The heightmaps that features, snow and rain read count only
the blocks listed there, and would otherwise take the ground to lie under the rock.

**Soils.** A soil is the rock beneath it, weathered by the climate where it lies. `Soils`, a pure model like
`Geology`, works out each column's soil from the rock four blocks under its top, the climate and the slope, and
`StrataRule` lays it under the biome's top block: a block or two of topsoil, then the subsoil, then weathered rock,
broken rock with soil between it that gives way downwards to the solid rock.

| Where | Subsoil |
|---|---|
| The wet tropics and the savannas: 18 °C or more, and at least 1.2 times the Köppen aridity threshold | Laterite, from any rock |
| Cool, dry plains: −3 to 14 °C, 0.5 to 1.4 times the threshold, slopes under 0.25 | Loess, over any rock |
| Under peat and mud | Clay: the waterlogged gley |
| Limestone, chalk and marble | Terra rossa where it is 12 °C or warmer and not too wet. Elsewhere a block of dirt at most: limestone dissolves rather than crumbles |
| Sandstone and red sandstone | Sand and red sand |
| Shale, slate and coal seams | Clay mottled with dirt where it is wet enough, dirt where it is dry |
| Granite, gneiss and diorite | Grus, the coarse sand and gravel of their crystals: coarse dirt, over gravel in the weathered rock |
| Other rocks | Dirt, over coarse dirt in the weathered rock |

Weathering is a chemical reaction that warmth and rain drive, so the profiles go about 4 blocks deep in cold or dry
land and on slopes, 6 in temperate land, 8 or 9 under loess, 12 in the savannas and 15 in the wet tropics, varying
by a few blocks over some 30. Semi-arid land, from 0.5 to 1.1 times the threshold and 6 °C or warmer, has a pan of
calcrete (calcite) under the subsoil, where the rain stops sinking in. The depths are in blocks, the same in every
world type. Sand and gravel never hang over open space: there they stay sandstone and rock.

Some biomes lay a soil of their own on top, to vanilla's depth: peat under the moors, the tundra, the páramo and the
bogs, whose pools it lines too, terra rossa under the maquis, loess under the steppe and laterite under the jungles, the
monsoon and laurel forests, the savannas and the cloud forest. Peat, terra rossa and laterite also lie bare on top in
patches. The profile goes on under them. Vanilla's plain dirt stays only as topsoil: below it lies the subsoil. Only the
column's top run of ground gets a profile, so the floors of caves and the ground under arches and rivers keep what the
biome gives them. Soils count as dirt (`#minecraft:dirt`), so saplings and plants grow on them as on dirt, and they dig
with a shovel. Peat burns in a furnace for half as long as coal (`context_int_provider/cooking/time_peat.json`).

Soil and loose gravel lie only at the top of the ground, in scree, in river beds and on beaches. So Diffusicraft worlds
leave out the blobs of dirt and gravel that vanilla scatters through the rock (`ore_dirt` and `ore_gravel`), which
showed through the faces of cliffs, and the lava lakes on the surface (`lake_lava_surface`), which no plain, steppe or
forest has; those underground stay. Biomes are shared by every world, so their feature lists keep them:
`ChunkGeneratorMixin` skips them while it decorates a Diffusicraft world's chunks (`DiffusicraftWorldgen.leavesOut`).
The other features keep their seeds.

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
- **Basins.** The middle of the floor of a Grand or Ancient Grand Hollow sinks into a basin, up to 0.8 times the
  floor's depth under the centre deeper, out to 0.6 of the way to the wall, and at least two blocks over the lava
  level. Below the level of its lake (see [Life in the Hollows](#life-in-the-hollows)) no other hollow opens into the
  basin or the 3 blocks of rock under it, so nothing drains it.
- **Light.** `StrataRule` puts patches of glowstone in the ceilings of Grand Hollows and shroomlight in those of
  Ancient ones, except in the glowworm grottoes, which their glowworms light.
- **Lava seas.** Deep Grand Hollows reach below the floor limit, which cuts them flat well under the lava level, so
  most of their floor lies under a lava sea. A floor that only just dipped under the lava would stay dry: the world
  interpolates the density over 8 blocks of height and fades it to solid towards the bottom.
- **Roof and floor.** At least 28 blocks of rock stay between a hollow and the surface, so they never open into the
  sky or the sea. Nothing is carved within 6 blocks of the bottom, so the bedrock is whole. Only the lava seas sink
  lower: their lava rests on the bedrock, 3 blocks above the bottom.

Vanilla's aquifers can flood parts of a hollow with water, as they do vanilla's caves, but not the cavern over a lake
(see [Life in the Hollows](#life-in-the-hollows)). The density is sampled at the corners of the 4 × 8 × 4 cells that
vanilla interpolates. Each sample takes a few tens of nanoseconds.

### Life in the Hollows

Each Grand and Ancient Grand Hollow is one cave biome, which `HollowBiomes` picks from the climate over the cavern's
centre, read on the coarse map, and a roll of the cavern's own. A cave keeps the mean temperature of the land above it
all year round, and is as wet as the rain that soaks down into it.

| Biome | Where | Life | Ground |
|---|---|---|---|
| Lush caves | Seven in ten Grand Hollows under the wet tropics (at least 20 °C and 1500 mm of rain) | Moss on floors and ceilings, with azaleas, clay pools, cave vines, spore blossoms | The lush caves' own |
| **Glowworm grotto** | Seven in ten Grand Hollows under mild, wet land (4–20 °C, at least 1000 mm), like the Waitomo caves | Glowworm silk hanging in curtains from the ceiling, pools in clay, stalactites and stalagmites. No glowstone: the silk gives the only light | Patches of clay |
| **Selenite caverns** | Three in four Ancient Grand Hollows under dry land (aridity below 1), one in five under wet land. Like the Cave of the Crystals at Naica | Beams of selenite, from the floors and the ceilings, that cross the cavern at every angle. No dripstone, nothing alive | Calcite |
| **Mushroom forest** | More than half the Ancient Grand Hollows under wet land: the forest that Verne set by the Lidenbrock Sea | Huge brown and red mushrooms, patches of small ones, glow lichen, spores drifting in the air | Mycelium over dirt |
| Dripstone caves | Every other Grand and Ancient Grand Hollow | Stalactites and stalagmites on every floor and ceiling | The dripstone caves' own |

The Deep Grand Hollows keep vanilla's biomes (see [Refined biomes](#refined-biomes)).

**Placement.** Vanilla finds the floor of a cave by scanning a dozen blocks up or down from a random height. That
finds the floors of its own caves, but nearly never those of a cavern a hundred blocks tall. The placement
`diffusicraft:hollow_surface` (`HollowSurfacePlacement`) knows from the model where a column's Grand Hollows lie, scans
just that band, and moves the position to one of its floors or ceilings at random: the floor itself, a ledge or a
pillar's top. It drops columns where no Grand Hollow opens, and places nothing outside a Diffusicraft world. The
caves' features use it. Fabric's biome API also adds copies of vanilla's moss, clay, cave vines, spore blossoms and
dripstone, placed this way, to vanilla's lush and dripstone caves, so that they reach the floors of the Grand Hollows
too. Like every Diffusicraft feature they run after vanilla's.

**Blocks and features.**

- **Glowworm silk** hangs from the ceiling in pale threads up to six blocks long, beaded with droplets that glow
  blue at full brightness. It gives light 4 and breaks at a touch.
- **Selenite** grows in beams (`diffusicraft:selenite_beam`, `SeleniteBeamFeature`) up to 22 blocks long and one to
  three blocks thick. Each beam is rooted two blocks deep in the rock and tapers to a point. Its blocks lie along
  whichever axis the beam runs closest to. A beam fills only open space and water beyond its root, and reaches at most
  15 blocks sideways, so it stays in the chunks a feature may write to. Selenite is translucent, like ice, and
  needs a pickaxe.
- **Huge mushrooms** are vanilla's, from the mushroom fields.

**Lakes.** Under wet land (aridity at least 1, and a mean temperature of at least 0 °C) the water that soaks down
fills the basin of a Grand or Ancient Grand Hollow to 0.92 of the floor's depth under the centre: just under the plain
floor at its rim, so the rock holds it. In the standard world the lakes are 7 to 11 blocks deep in the middle, deeper
in the tall worlds. Under dry or frozen land the basins stay dry, and so do those of the selenite caverns, drained
like the Cave of the Crystals. `HollowLakes` fills them: a mixin at the end of `NoiseChunk`'s constructor puts it in
front of the chunk's aquifer, which the noise fill and the carvers both ask what fills open space, so a carver's
tunnel through a lake bed floods too. It finds the lake with `Hollows.lakeAt` and asks `HollowBiomes.holdsLake` once
per cavern. The cavern over a lake drains into it, so `HollowLakes` keeps the aquifer out of it (`Hollows.overLake`):
the aquifer's pools would stand on the lake, a block or more over its brim. Where another cave opens into the cavern,
its water may spill in. Like vanilla's lakes, the water is still: it schedules no fluid updates. The beds are clay in
the glowworm grottoes and lush caves, sand by the mushroom forest's lake, and gravel among the dripstone. Every floor
under water in those biomes above the lava level gets them, so vanilla's aquifer pools there do too.

The ground is the material rule `diffusicraft:biome_caves`, the last of the rules `StrataRule` runs over its rock.
The erosion that bares rock on steep ground and lays scree under cliffs keeps to the land's surface, so the soils of
cave floors stay.

### The world spawn

Each seed draws the kind of place where the world spawn lies (`SpawnKind`):

| Kind | Chance | Where |
|---|---|---|
| Coast | 55% | On dry land by the open sea, a beach where there is one, on a landmass more than 150 km across |
| Inland | 33% | At least 100 km from the sea; three seeds in four put it on a plain under 500 m |
| Island | 8% | On an island at least 25 km across, with no other land within 120 km and little within 300 km |
| Hollow | 2% | On the floor of an Ancient Grand Hollow, under the land |
| Peak | 2% | Near the summit of a mountain over 3,000 m |

`Geography` shifts the continents so that such a place lies at the origin. It tries up to 4,096 places within
30,000 km, or 65,536 for an island, and keeps the first that suits. If none does, the origin lies inland. The sea by a
coast must be more than 100 m deep 30 and 60 km out, because the coarse model fills a small or shallow inlet with
land.

The map is only what the coarse model is conditioned on, so the shore or the summit the model draws lies a little
away from where the map puts it. `SpawnSearch` reads the coarse map within 6 coarse pixels (46 km) of the origin.
Then it reads the full terrain of at most six coarse pixels:

- **Coasts and islands.** It takes the shore nearest the origin: dry land within 180 m of the water, 1 to 40 blocks
  above the sea, or a beach no higher than 4 blocks if there is one.
- **Peaks.** It takes the highest coarse pixel around, then its highest point. If that point lies on the pixel's
  edge, it follows the ground into the next pixel.
- **Inland.** It keeps the origin. If the origin is at sea, it takes the nearest dry, gentle coarse pixel.

`WorldSpawn` replaces vanilla's `setInitialSpawn` (through `MinecraftServerMixin`). It puts the spawn on the nearest
column within 48 blocks where vanilla would let a player respawn, or else in the first chunk of vanilla's spiral that
has one.

For a Hollow, it takes the nearest Ancient Grand Hollow within four cells (3,584 blocks) of the origin that has at
least 50 m of land over its middle. It stands the player on the floor three quarters of the way out from the middle,
clear of the lake. The bonus chest goes beside the player, with torches around it. If no Hollow has a floor to stand
on, the spawn goes inland on the surface. A player without a bed respawns on that floor too
(`PlayerSpawnFinderMixin`), where vanilla would look for open sky over it.

The search runs the full model over the pixels it reads, which takes a few seconds the first time a world loads. The
noise settings have an empty `spawn_target`, so vanilla's own search does not run, and the structures take (0, 0) as
their origin.

### Far-away searches

Before the first chunk generates, each of the 128 strongholds searches the biomes within 112 blocks of its ring
position, up to about 20 000 blocks out. Run on the full terrain, that would take far longer than the spawn.
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
| `terrain/geography/` | `Geography` (the conditioning map), `Continents`, `Hypsometry`, `Tectonics`, `Hotspots`, `ClimateModel`, `ReliefLattice`, `GradientNoise`, `SpawnKind` |
| `worldgen/` | `WorldScale`, `TerrainSource`, `Glaciation` (the fjords), `TerrainField`, `SpawnSearch` and `WorldSpawn` (the world spawn), `Coastline` (the distance to the coast), `TerrainSurface` (the ground, with its ledges, sea cliffs, stacks and gullies), `ClimateMapping`, `CoarseClimate`, `TerrainFields` (one field per seed, model lifetime), `PipelineSource`, `CarverRoof`, `DiffusicraftWorldgen` (registration) |
| `mixin/`, `client/mixin/` | The default world type, the world spawn and the respawn in a Hollow, the check for missing models on Create, the coarse stronghold search, the lava level, the carver roof, the world attached to climate samplers, the lakes of the Hollows in front of each noise chunk's aquifer, the lapse rate, the material rules run outside chunks and the settings' own material rule, which tells a Diffusicraft world when TerraBlender wraps it |
| `worldgen/density/` | The density function types: `SurfaceFunction`, `ClimateFunction`, `HollowsFunction`, `RiverFunction`, `CoastFunction` |
| `worldgen/river/` | `Rivers` (what a river or lake does to a column), `RiverNetwork` (the coarse drainage), `CellRivers` (the paths inside a tile), `CellBasins` (the closed basins of a tile), `CellLakes` (the lakes inside a tile), `BlockLakes` (the lakes across tiles), `GreatLake` (the lakes of the coarse map), `CellFlows`, `RiverColumn` |
| `worldgen/biome/` | `ClimateBiomeSource` (`diffusicraft:climate`), `BiomeRefiner` (the rules), `HollowBiomes` (the biomes of the Grand Hollows), `HollowLakes` (their lakes), `BiomeClimate`, `BiomeWorld`, `HeightTemperature` |
| `worldgen/feature/` | `HollowSurfacePlacement` (`diffusicraft:hollow_surface`), which finds the floors and ceilings of the Grand Hollows, and `SeleniteBeamFeature` (`diffusicraft:selenite_beam`) |
| `worldgen/geology/` | `Geology` (the rock and ore model), `Rock`, `Ore`, `Soils` (the soil model), `Hollows` (the cavern model), `Erosion` (cliffs and scree), `Gullies`, `GeoNoise` |
| `worldgen/material/` | `StrataRule`, the material rule type that applies the geology and lights the Hollows; `ModOres`, the ores of tech mods; `RiverRule`, the rivers' water and beds |
| `worldgen/lod/` | `LodSampler`, `LodColumn` and `TreeCover`: columns of the finished surface and its trees without chunks, for LOD mods |
| `compat/dh/` | The Distant Horizons generator; loaded only with Distant Horizons. See [COMPATIBILITY.md](COMPATIBILITY.md) |
| `block/` | `DiffusicraftBlocks`, the new rocks, soils, plants and salt crust, glowworm silk and selenite |
| `data/diffusicraft/worldgen/` | Noise settings, material rules, density functions and world presets for each scale |
| `data/diffusicraft/dimension_type/` | The tall overworld dimension types (grand and earth) |
| `data/diffusicraft/worldgen/{biome,feature,placed_feature}/`, `material_rule/biome_surface.json`, `material_rule/biome_caves.json`, biome tags | The new biomes, their trees and plants, their ground and their tags |
| `tools/worldgen_data.py` | Writes the noise settings, material rules, density functions, presets and dimension types. Edit it, run `python3 tools/worldgen_data.py`, and commit the output. |
| `tools/biomes.py` | Writes the new biomes, the cave biomes of the Grand Hollows, their features, ground rules and tags. Edit it, run `python3 tools/biomes.py`, and commit the output. |
| `tools/block_assets.py` | Draws the textures of the rocks, soils, plants, salt crust, glowworm silk and selenite, and writes the block states, models, loot tables, dye recipes, peat's burn time and tags of the blocks. `--preview sheet.png` writes a sheet of the textures for review. |

## Lifetime and storage

- **Loading.** The models load when the first chunk needs terrain, not when the world opens.
- **Unloading.** They are freed when the server stops, which gives the GPU memory back to the game.
- **Disk cache.** Pipeline windows are cached in
  `<world>/diffusicraft/<model revision>/map<Geography.VERSION>/<terrain seed>/`. Reopening a world is fast, and the
  terrain stays the same even if newer models are installed later.

## Tests

- `GeologyTest` checks the layer order, that lowlands are covered in sediment, that Earth 1:1 keeps the standard
  world's layers below the sea and its own scale above it, determinism, ore rarity and diamond depth, that the
  basement is harder than its cover and granite harder than schist, that the hardness never jumps, and the sampling
  speed. For the ledges, it checks that the ground climbs steeply through hard beds and gently through soft ones,
  with the cliffs in hard beds and the benches mostly on soft ones at full and at weak strength, that the ledges
  average out, stay within 10 blocks and change smoothly, and that there are none in the basement or in fold belts.
  For the ores of tech mods, it checks that their minerals take only some of the bodies, only in their host rocks and
  only where a mod adds them, and that the bodies stay vanilla's without the mods. `ModOresTest` checks how a block
  is picked for each mineral: one in stone and one in deepslate, the first mod by id, either standing in for the
  other, and never a Nether or End ore.
- `GeologySurveyTest` needs the models. It runs the geology over the same real land at every scale, checks that the
  tall worlds keep about as much basement under the land as the standard world, and draws a cross-section of each to
  `build/geology`. `-Pdiffusicraft.geologyStore=<dir>` keeps the terrain on disk for later runs, in a folder per seed.
- `GulliesTest` checks that only Earth 1:1 is fine enough for gullies, that gentle ground stays smooth, that steeper
  slopes are cut deeper but never past the deepest a gully can go for any rock and climate, that the ground changes
  about three times as much across a slope as down it, that cover rounds the ground off and hard rock is cut deeper
  and sharper, that bare soft rock has the finest rills, and that the ground is continuous in space, rock and cover,
  and set by the seed. `TerrainFieldTest` checks the spline's gradient and that the ground keeps within a few blocks
  of the terrain in every world.
- `ErosionTest` checks that gentle ground keeps its soil, steep ground shows the rock in any direction, and scree
  lies at the foot of a cliff, whichever way it faces, and not on the flat beyond.
- `SoilsTest` checks that the soil follows the rock (sand on sandstone, clay on shale, grus on granite, terra rossa
  on limestone in the warm), that laterite, loess and gley take over from any rock where the climate or the water
  makes them, that only semi-arid land has a calcrete pan, that the horizons come in order down to the rock, that
  warm, wet, flat land weathers deepest, that the weathered rock turns to solid rock downwards, and that bauxite lies
  in patches of the lower subsoil under the flat land of the wet tropics, not on slopes or in drier or cooler land.
- `ErosionSurveyTest` needs the models. At every scale it finds the tile near the origin whose ground rises the most in
  blocks and sorts its land into soil, cliffs and scree. With seed 42 that tile is 87% soil, 8.3% cliffs and 4.8%
  scree in the Standard world, 90% soil, 6% cliffs and 4.2% scree at Grand, and 93% soil, 4.3% cliffs and 2.5% scree
  at Earth 1:1. The highest tile near the origin, which the Standard world squeezes flat, is 10.4% cliffs and 7.5%
  scree at Grand and 6.5% cliffs and 4.4% scree at Earth 1:1. Before the ledges, the steepest tiles were 2.6% cliffs
  in the Standard world, 0.3% at Grand and nearly none at Earth 1:1.
- `HollowsTest` checks that every kind appears, the share of open ground, the roof and the bedrock, the lava seas,
  the height of the Grand Hollows in the tall worlds, that the rock under their floors and over their ceilings counts
  as part of them, that every open block of a Grand Hollow belongs to one cavern from floor to ceiling, within the band
  of the column the placements scan, that the basins hold their lakes, with no open block outside a lake beside or
  under one, mostly 5 blocks deep or more in the middle, determinism, the sampling speed, and that the chambers around
  a place come nearest first. `HollowBiomesTest` checks that the Deep Grand Hollows keep vanilla's biomes, that the
  Grand Hollows follow the climate, that the Ancient ones grow crystals under dry land and mushrooms under wet land,
  that a cavern always picks the same biome, and that only wet, unfrozen land fills a basin, never in the selenite
  caverns.
- `RiversTest` runs the river model over an analytic valley. It checks that water runs down to the sea, that the
  discharge is the runoff upstream, that neighbouring cells agree on every crossing, that pits are breached, that a
  river meets itself across tile borders, that the banks hold the water where it steps down, in the river and in the
  lakes it fills along its valley, that lakes fill the hollows between the hills and their shores hold them, that a
  dry climate leaves the hollows dry, that every thread gets the same rivers, that a thread asked about another
  chunk while it computes a column keeps that column's chunk, and that the width grows with the discharge.
- `GreatLakeTest` sinks a bowl 500 m deep into the slope of the analytic valley, on the coarse map and the fine
  terrain alike, astride the border of two regions. It checks that both regions see the same lake, that its cells drain
  through its outlet, that no pit remains, that the rivers cross it at the level of its water and never climb, that
  the water fills the bowl on every tile and the tiles agree on it along their sides, that every scale has water at
  its bottom, that a dry climate leaves no lake, and that every thread gets the same lake.
- `CellLakesTest` fills bowls: to the rim in a wet climate, partly or not at all in a dry one. A bowl at the edge of
  the tile, at or below sea level, too shallow or too small holds no lake, and two bowls are two lakes. A river
  running down a valley fills the bowl in it, even in a desert, just below its rim, and flows on out of it.
- `RiverMapTest` needs the models. It surveys the rivers of a real world and draws maps of them and their lakes to
  `build/river-map`, to look at them without starting the game.
- `BiomeRefinerTest` checks each rule on real climates: arctic lowland, the Alps above the tree line, the Andes' cloud
  forest, Ecuador's páramo, the Altiplano and Mauna Kea, central India, Thailand and the Amazon, Shanghai, Atlanta and
  Hong Kong, the Pacific Northwest, the Mediterranean, the Gobi and the steppe, Dartmoor and the Pennines, the Lüneburg
  Heath, Bonneville and Uyuni, Finland's mires, the West Siberian Plain, the Bog of Allen, the Hudson Bay Lowlands,
  tropical coasts and the Sognefjord. On slopes, the tree line of the Alps is higher on the sunny side, and the woods of
  the Rockies' foothills keep to the shaded side.
- `ClimateColumnTest` checks that the south face of a ridge is warmer and drier than its north face, by as much as
  its slope calls for, and that its east and west faces are alike. `ClimateMappingTest` checks how much a slope
  faces the sun, and that the shaded side of a mountain is snowy where the sunny side is not.
- `CoastlineTest` checks the distance to straight and round coasts, at sea and on land, that it is continuous across
  tile borders with its gradient, that a coast just outside a tile counts and that saddles follow the mean of their
  corners. `SeaCliffTest` builds straight coasts: some stretches of a high one get cliffs, a full cliff's foot is a
  shelf a block above the water, the cliff meets the ground at the end of its strip, a low coast gets none, and the
  density takes every cliff column block by block.
- `GlaciationTest` checks that the lowering keeps heights in order and leaves high ground and the deep sea alone, that
  the trough climbs inland and spares valleys too high above it, that a cold mountain coast drowns its valleys under the
  ridges while a mild one keeps them, that gentle valleys drown six tiles inland but not beyond the ice's reach, that
  a plain beside the mountains keeps its ground, that the coarse map is lowered alike, and that overlapping samples
  agree to the bit.
- `GeographyTest` checks, on a table of heights shaped like the real one, that a seed always maps the same world,
  that land takes about its real share, that the origin is the kind of place asked for (a coast by the open sea on a
  continent, land far inland, a lone island, land over a Hollow or a high mountain) and that seeds draw the kinds by
  their chances, coasts first. `SpawnSearchTest` checks on analytic terrain that a coastal spawn is on the beach
  nearest the origin, that a spawn by a peak is on its summit, that without a mountain or at sea the spawn moves to the
  nearest land, and that land at the origin keeps it.
- `WorldScaleTest`, `ClimateMappingTest` and `TerrainFieldTest` run with `./gradlew build` and need no models.
  `TerrainFieldTest` uses an analytic terrain to check:
  - interpolation;
  - continuity across tile borders;
  - eviction;
  - concurrent tile computation;
  - the coarse column;
  - the hillside's slope, across tile corners too.
- `WorldGenerationGameTest` is a client game test and needs the models. It creates a world with the default settings and
  checks that it uses Diffusicraft, that a player can stand at the world spawn, and that the heightmaps count all of
  Diffusicraft's full blocks. It then saves a biome map, a height map, in-game views around the spawn and views of the
  largest river near the spawn, the steepest cliff, the tallest sea cliff, each of Diffusicraft's biomes within 24 km
  or, for those of other latitudes, along the meridian towards the equator, the tallest Grand Hollow, a lava sea nearby
  and the tallest Grand Hollow of each cave biome within 4 km, all at noon, to `build/run/clientGameTest/screenshots`.
  The top of the cliff must be bare rock, the face of the sea cliff (not an arch, which is open halfway up) sheer above
  the water, though a cave may open into it there and caves and ravines below it, and each cave biome must hold its own
  blocks: silk, selenite, mushrooms, moss or dripstone. Around the spawn no sand or gravel may hang over open space, the
  rock under the soil may hold no dirt or gravel down to the first cave, and on gentle ground outside limestone, chalk
  and marble the soil must go deeper than vanilla's dirt on average. Run with Tech Reborn (`-PdevMods=techreborn`), it
  checks that the geology places Tech Reborn's tin, lead, silver, uranium, rubies, sapphires and bauxite, and not its
  Nether sphalerite. It generates on the CPU unless `-Pdiffusicraft.device` names another device.
- `TreesGameTest`, a client game test that needs no models, plants three of each of Diffusicraft's trees on a
  superflat world, each in its own biome for the colours of its leaves, and saves a view of each. A tree that never
  grows on open grass fails it.
