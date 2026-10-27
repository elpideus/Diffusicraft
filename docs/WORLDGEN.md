# World generation

Diffusicraft generates the overworld from Terrain Diffusion's elevation and climate. The rest is Minecraft's own
`minecraft:noise` chunk generator, with Diffusicraft's noise settings and density functions. The overworld
therefore stays a vanilla `NoiseBasedChunkGenerator`, and its biome source is vanilla's multi-noise source with one
step added. Several things work unchanged because of this:

- structures and structure mods, though the villages and pillager outposts stand further apart
  ([below](#villages-and-outposts));
- biome mods that extend the overworld biome table (TerraBlender, Biomes O' Plenty, BYG);
- C2ME's parallel generation.

Distant Horizons gets a generator of its own that reads the terrain without building chunks; see
[COMPATIBILITY.md](COMPATIBILITY.md#distant-horizons).

## World types

Diffusicraft adds one world type, **Diffusicraft**, and makes it the default:

- **on the Create World screen**, `WorldCreationUiStateMixin` selects it;
- **on dedicated servers**, `DedicatedServerPropertiesMixin` makes it the default `level-type`.

Diffusicraft is inspired by real terrain, not a simulation of it. A world comes in six sizes, from the real Earth's
(1:1) down to 1:32, each half the one before, across and up alike. Heights are exaggerated, the more the smaller the
world, so mountains stay dramatic at every size. Each size is a world preset of its own; only the default, Ultra, is
listed among the world types. On a dedicated server, set `level-type` to the size's preset.

| Size | `level-type` | Horizontal | Heights exaggerated | Build height | Clouds |
|---|---|---|---|---|---|
| Vanilla (1:32) | `diffusicraft:vanilla` | 32 m per block | 3 times, eased above 1.3 km | −64 to 319 (vanilla's) | y = 192 |
| Medium (1:16) | `diffusicraft:medium` | 16 m per block | 2.5 times, eased above 4.8 km | −256 to 1279 | y = 192 |
| Large (1:8) | `diffusicraft:large` | 8 m per block | 2.3 times, eased above 5.6 km | −256 to 2303 | y = 234 |
| Grand (1:4) | `diffusicraft:grand` | 4 m per block | 2 times, eased above 6 km | −256 to 3839 | y = 363 |
| Extreme (1:2) | `diffusicraft:extreme` | 2 m per block | 1.7 times, eased above 6.3 km | −256 to 8191 | y = 585 |
| **Ultra (1:1)**, the default | `diffusicraft:ultra` | 1 m per block | 1.5 times, eased above 6.5 km | −256 to 16,367 | y = 986 |

**Picking a size.** While Diffusicraft is the world type, a size slider takes the place of "Customize" in the World tab
of the Create World screen (`client.mixin.CreateWorldScreenWorldTabMixin`, `client.worldgen.WorldSizeSlider`). Drag
it, or press the arrow keys to move one size at a time. Its tooltip gives the size's scale, its build height and how
high its mountains reach.

Grand is the tallest world that Distant Horizons and Voxy hold whole; in the two above it they cut the highest ground
flat in the far terrain (see [COMPATIBILITY.md](COMPATIBILITY.md#warnings)). With either mod installed, the slider turns
yellow on those sizes, its tooltip says what the mod will do, and Create asks before it makes the world. Ultra fills the
tallest dimension Minecraft can hold once Diffusicraft lifts its limits (see [HEIGHT.md](HEIGHT.md)). The clouds float
about 600 m above the sea, in each size's heights, and never below vanilla's.

A world type that needs the models cannot be created until they are downloaded. Pressing Create opens the resource
screen instead. A dedicated server downloads them at startup, and generation waits for the download.

Worlds made with the three world types before the sizes (`diffusicraft:standard`, `grand` and `earth`) do not load:
their presets are gone, and Grand changed its scale.

### From meters to blocks

`WorldScale` holds the numbers.

**Horizontally**, a block covers a fixed number of meters. The model's 30 m pixels are interpolated with Catmull-Rom
splines, so the surface stays smooth at any size. Finer detail is added on top: from 1:8 up the ground's
[roughness](#roughness) adds knolls and, from 1:4, small scarps; in every world the hard beds of the sedimentary cover
stand out of steep slopes as [ledges](#ledges); and at 1:4 and larger [gullies](#gullies) cut the slopes more finely
than the pixels.

**Vertically**, elevation maps linearly up to a knee, then eases towards a ceiling:

```text
blocks(e) = e / v                                            while e / v <= knee
blocks(e) = knee + (cap - knee) * tanh((e / v - knee) / (cap - knee))   above
```

Below the knee, hills and valleys keep their shape, exaggerated by the size's factor (its horizontal meters per block
over `v`). Above it, only the highest mountains are compressed to fit the build height. Oceans use the same curve with
their own numbers, so abyssal plains stay above the bottom of the world.

| Size | Land: m per block, knee, cap | Highest surface | Everest | Ocean: m per block, knee, cap | Lowest sea floor |
|---|---|---|---|---|---|
| vanilla | 10.7, 120, 240 | y = 303 | y = 303 | 20, 20, 45 | y = 18 |
| medium | 6.4, 750, 1100 | y = 1163 | y = 1160 | 16, 30, 80 | y = −17 |
| large | 3.5, 1600, 2150 | y = 2213 | y = 2209 | 12, 35, 120 | y = −57 |
| grand | 2, 3000, 3700 | y = 3763 | y = 3762 | 8, 40, 160 | y = −97 |
| extreme | 1.15, 5500, 7800 | y = 7863 | y = 7731 | 3, 50, 180 | y = −117 |
| ultra | 0.65, 10000, 15800 | y = 15,863 | y = 14,877 | 1, 60, 200 | y = −137 |

The sea is at y = 63 at every size. The sea floor lies at least one block under it, so a sea shallower than one
block's worth of meters still holds water instead of showing as a dry shelf flush with the water. The Everest column
is a peak of 8,849 m over valleys at 5 km, as [raised](#taller-mountains) to 10,978 m; unraised, it would stand at
y = 13,272 at Ultra. From Medium up the knee lies at 5 to 6.5 km, which the uplift takes peaks of about 4 to 4.5 km
over their valleys to, so only the highest ranges are eased. Each world above Vanilla ends
some 20 blocks or more above its highest surface, room for the tallest trees on the highest peak; Ultra has 500.

**Depth is sized for play.** The real crust is tens of kilometres thick, but the game happens mostly on the surface,
so the underground does not follow the vertical scale:

- the bottom of the world is y = −64 at Vanilla and y = −256 at every other size, the tall worlds;
- the seas are compressed, as in the table above;
- below the sea, the rock layers use at least 10 m per block (see [The ground](#the-ground)).

The build height goes to the surface instead. Real mountains rise some 9 km above the sea while the mantle lies
tens of kilometres down, and a world of both would be mostly rock nobody digs. So the tall worlds are tall at the top
only: Ultra is 16,624 blocks tall, all but 319 of them above the sea. Chunks fill only as high as their ground
reaches, so the empty height costs little (see [HEIGHT.md](HEIGHT.md#filling-only-to-the-ground)).

## How a column is generated

```text
Geography (per seed): continents, plates, hotspots and climate, the map the coarse model is conditioned on
   ▼
WorldPipeline (30 m pixels, per seed)
   │  PipelineSource: creates the pipeline on first use; caches windows under <world>/diffusicraft/
   ▼
Uplift: raises the mountains over their valley floors
   ▼
TerrainField: 256² pixel tiles, Catmull-Rom elevation, plus an 8-pixel grid of
   │          mean elevation, relief and sea-level climate, and the distance to the coastline
   ▼
TerrainSurface: the ground in blocks for one scale, eased down to the sea, with roughness from 1:8 up, ledges,
   │            sea cliffs and islets, and gullies at 1:4 and up
   ▼
density functions: diffusicraft:surface, diffusicraft:climate,
   │                diffusicraft:hollows (the Hollows model), diffusicraft:river (the Rivers model),
   │                diffusicraft:coast (the sea cliffs and islets, block by block)
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

### Taller mountains

The model's mountains rise as gently over their valleys as real ones do. Diffusicraft is a fantasy inspired by real
terrain, not a simulation of it, so `Uplift` raises them. It wraps the pipeline before anything else reads it and
lifts the ground `h` meters above its valley floor by

```text
lift(h) = (K − 1) (h − F (1 − e^(−h / F)))          K = 1.6, F = 300 m
```

Ground on the floor keeps its level, a hill 100 m high rises by 9 m, and the mountains by three fifths of their height
over their valleys, less 180 m. The lift never grows faster than three fifths of the height, so where the floor is
level, slopes keep their direction and only steepen.

| Peak, over a valley floor at | Raised to | y at Ultra, before and after |
|---|---|---|
| 800 m, 200 m | 1,004 m | 1,294 → 1,608 |
| 1,500 m, 300 m | 2,043 m | 2,371 → 3,207 |
| 3,000 m, 1,500 m | 3,721 m | 4,678 → 5,788 |
| 4,808 m (Mont Blanc), 1,000 m | 6,913 m | 7,460 → 10,696 |
| 8,849 m (Everest), 5,000 m | 10,978 m | 13,272 → 14,877 |

- **The valley floor** is the coarse map's 5th percentile of the elevation, or sea level where that is lower,
  interpolated bilinearly between the centres of the coarse pixels, 7.7 km apart. Each pixel's floor depends on where
  it is alone, so overlapping windows agree to the bit. The floors, and the rivers and lakes on them, keep their
  levels, and the coarse map keeps its 5th percentile, so the rivers, which route over it, run where they ran.
- **Plateaus.** The floor changes slowly, but where it falls away beside high, level ground, as beside a gorge cut
  through a plateau, the lift tilts that ground up towards the drop. A river there keeps its course if it falls at
  least three-eighths as fast as the floor; where it does not, a lake the rain fills can gather behind the tilt.
- **The climate.** Raised ground is colder by the local lapse rate, so the tree line and the snow stay at their
  heights and more of each range stands above them. On the coarse map, each pixel's mean rises as ground that high
  above the pixel's own 5th percentile would, and its temperature falls by 6.5 °C a kilometre.
- **Every size.** The field is shared by all the sizes of a seed, so the lift is in meters and the same in each. In
  the smaller sizes, whose knee lies low, the raised ranges crowd under the cap: their summits flatten a little more.
  The glaciers come after it and read the mountains as the world has them.

Ground below the sea is never raised.

### `TerrainField`

`TerrainField` holds one seed's terrain. It reads the pipeline in tiles of 256 × 256 pixels (7.7 km), with a
32-pixel margin, through `Uplift`, which [raises the mountains](#taller-mountains), and `Glaciation`, which drowns the
valleys of cold mountain coasts into [fjords](#coasts). It samples 13 pixels more around the margin, the reach of the
distance to the coastline and a cell, so that every pixel of the padded tile knows that distance and the land can be
[eased down to the sea](#coasts) there, the same in every tile that holds the pixel. For each tile it keeps three
things:

- **The padded elevation.** Columns sample it with a Catmull-Rom spline.
- **A grid every 8 pixels (240 m).** It holds slowly varying values, sampled bilinearly:
  - the mean elevation and relief (standard deviation) over a 1.4 km box, from summed-area tables;
  - the climate reduced to sea level, plus its lapse rate;
  - how strongly glaciers lowered the ground;
  - the coarse map's 5th percentile of the elevation. A column's height above it (`aboveLow`) tells a basin's floor,
    below zero, from the flat ground above it.

  The temperature at a column is then the sea-level temperature plus the lapse rate times the column's own
  elevation, so summits are cold even between grid points.
- **The distance to the coastline**, where there is a coast within 360 m of the tile. See [Coasts](#coasts).

Tiles are computed once, by the first thread that needs them; other threads wait for that result. The 160 most recently
used tiles stay in memory, about 66 MB, plus 134 KB for each tile with a coast: up to 21 MB more along the sea. The
terrain seed is derived from the world seed, so worlds with the same seed match.

### Roughness

Between the model's pixels the spline is smooth, so at 1:1 level ground would lie as smooth as a sheet for dozens of
blocks, and the gullies cut only the slopes. `Roughness` adds relief finer than the pixels to all land:

- **Knolls and hollows.** Octaves of gradient noise 240, 120, 60 and 30 m across. The widest rises and falls by up to
  4% of its width, and each finer one is two-thirds as high as the last, so the finer ones still show. At 1:1 they
  rise and fall by 2 blocks (standard deviation) on plains and 3 on rugged land, 19 at most. Each octave is turned by
  37° from the last, so their lattices do not line up.
- **Ridges.** On bare ground, dry or frozen, 70% of each octave is ridged: sharp crests along the noise's zero
  contours with rounded hollows between them. Under soil and plants, the knolls are round.
- **Scarps.** Small cliffs along the contours of a noise 800 m across, about every 230 m: 3.5 m high (5 blocks at 1:1,
  2 at least), their faces some 3 blocks wide. Behind each crest the ground falls gently away to the foot of the
  next, as on a cuesta, so the scarps lower the ground as much as they lift it. Under a full cover of soil and plants
  they are 60% as high.
- **Where.** Noise 4 km across makes some country rougher than the rest, from 30% of the roughest to all of it, and
  puts the scarps on about two fifths of the land, at full height on a tenth. Rugged land, with a relief of 150 m or
  more, is 1.3 times as rough as usual, and plains, 10 m or less, 0.8 times.
- **Near the sea.** The roughness grows in over the 8 blocks above the sea and over the 60 m inland of the strip of
  [sea cliffs](#coasts), so the beaches stay smooth. It never lowers the ground to less than a block above the sea,
  so no pond opens behind a beach.

The heights are in blocks, as shares of the widest wavelength in blocks, so the knolls keep their shape at every
size. Octaves narrower than 16 blocks are left out, since the density is interpolated over cells 4 blocks wide: all
four at Ultra, three at Extreme, two at Grand and one at Large, and none at Medium or Vanilla, whose blocks are 16 m
wide or more and show the model's own detail. The scarps need their noise 200 blocks across or more: from Grand up.
The roughness costs about 0.1 µs a column, and the rivers, the lakes and the shores read the ground with it, so the
banks hold the water where a hollow comes near (see [Rivers](#rivers)).

### Gullies

Nothing narrower than two of the model's 30 m pixels survives, so at 1:1 a mountainside would be a smooth sheet
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

Octaves narrower than 12 blocks are left out: the density is interpolated over cells 4 blocks wide and would turn them
into zigzags. That keeps all three at Ultra (1:1), two at Extreme and one at Grand, and none in the smaller sizes, whose
blocks are 8 m or more wide.

`TerrainSurface` puts the [roughness](#roughness) on the terrain, then the gullies, then the [ledges](#ledges), so
the ledges follow the beds through the knolls and the gullies. Everything that needs the ground
reads it there: the density, the ceiling of each chunk, the rivers and lakes, the Hollows, the materials (so the sides
of the gullies can bare the rock, see [The ground](#the-ground)) and the LODs whose columns are at most 4 blocks apart.
The biomes and the climate keep the smooth elevation; the roughness, the gullies and the ledges are too small to change
them. The gullies and the ledges take the slope from the spline's own derivative, so gentle ground costs one sample
and the roughness. Steep ground costs about 0.2 µs more for the rock,
the climate and the ledges, and 0.6 µs at 1:1 with the gullies. The rocks come from the geology seed, which
`TerrainFields` gives each field next to the terrain seed, so the gullies and the ledges follow the same rocks the
world shows.

### Ledges

Where a slope cuts through the sedimentary cover, its hard beds stand out and the soft beds between them recede, as on
the walls of the Grand Canyon: cliffs of stone, limestone and sandstone, with gentler slopes and benches of shale,
chalk, salt, coal or stone between them. `Geology.ledge` moves the ground to make them, in every world:

- **Which beds.** Three fifths of the thick beds are hard, the rest soft. A thin bed (1 or 2 blocks) goes with the
  beds around it: between two hard beds it is a parting or a coal seam in their cliff, and next to a soft bed it
  recedes with it. The rock follows: limestone, sandstone and red sandstone lie only in hard beds; shale, chalk and
  rock salt only in soft ones; stone in both.
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

The beds are 1 to 8 blocks thick at every size, so a cliff of 15 blocks stands for 10 m of rock at Ultra and 160 m
at Vanilla, as tall as the Grand Canyon's. The density's 4-block cells turn the narrowest cliffs into steep ramps,
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
| weirdness | Whether the column is a local peak or a hollow. The sign comes from smooth noise with a wavelength of 12 km of real ground, but at most 2000 blocks, and only picks variants: in patches a few hundred blocks across at 1:32 and about a thousand from 1:4 up, where 12 km would have made them several thousand. |
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
  - in dry climates the woods keep to the shaded slopes and junipers or grass take the sunny ones, as in the foothills
    of the Rockies;
  - Mediterranean scrub spreads up the sunny slopes.
- <a id="summer-rain"></a>**Summer rain.** The aridity threshold is Köppen's: `20 T + 140` mm, where T is the annual
  mean in °C, for rain that falls through the year, and `20 T + 280` mm where at least 70% of it falls in the summer
  half, when the heat takes more of it back. The climate says how unevenly the rain falls (BIO15) but not when. Where
  the coldest month freezes, uneven rain falls in summer, since cold air holds little water, as in Mongolia and on the
  Great Plains; where it stays above 15 °C, it comes with the monsoon. There the summer threshold applies, as far as the
  rain is uneven: the wetter half of a sinusoidal year holds one half plus BIO15 × √2/π of the rain. Between the two,
  uneven rain may be Mediterranean and fall in winter, so the year-round threshold stands. The threshold never falls
  below 140 mm, so tundra counts as moderately dry rather than as wet as a rainforest. The season only decides near the
  threshold: it counts in full up to the year-round one and fades out by two and a half times it, so the wettest taigas,
  and the peat of the West Siberian Plain and of northern Alberta, which also get most of their rain in summer, stay as
  wet as they are. With the year-round threshold, a plain with 340 mm of summer rain at 6.5 °C, January at −10 °C, came
  out as one forest 64 km across; with the summer threshold 92% of it was steppe, as on the plains of Inner Mongolia,
  and the woods kept to the higher, wetter hills on its edge, where 380 mm falls. The soils and the ground cover read
  the same ratio.
- <a id="summer-moisture"></a>**Summer moisture.** Köppen's threshold follows the year's mean, which hides a continental
  summer. Astana averages 3.5 °C over the year but 21 °C in July, and its 330 mm pass the threshold, so Köppen calls it
  humid; it is steppe. So the [refiner](#refined-biomes) also takes the threshold at the warmest month's temperature,
  `20 T_warm + 140` mm (`BiomeClimate.summerMoisture`). Where the warmest month reaches 17 °C and the year averages −2
  to 18 °C, land humid by the year's ratio but with less rain than that is forest-steppe, and with less than 0.7 of it
  steppe. Where the warmest month reaches 22 °C, Köppen's hot summer, land with less than 1.5 of it is prairie instead
  of forest-steppe or woods, wherever the ground is unbroken enough for the fires to run. Cooler summers take little
  water back. Colder years keep the taiga: the ground is frozen a little way down and holds the snowmelt, so that larches
  and spruces grow on 250 mm at Yakutsk and Fairbanks. Of real places, this puts Astana, Orenburg, Volgograd and
  Saskatoon in the steppe; Kharkiv, Omsk, Novosibirsk, Krasnoyarsk, Edmonton, Winnipeg and Budapest in the
  forest-steppe; Des Moines, Wichita and Harbin in the prairie; and Moscow, Kyiv, Warsaw, Berlin, Paris, Ottawa,
  Minneapolis and Saint Louis in the forest. It errs by a few hundred kilometres at the edges: the grassland around
  Odessa and Regina comes out as forest-steppe, and so do the hills around Beijing, while Voronezh, Kursk and Kazan, on
  the forest-steppe's northern edge, come out as forest. Vanilla's table still reads the year's ratio.
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
swap it for one of twenty-six new biomes, or for another of vanilla's. The first rule that matches wins:

| Biome | Climate | Replaces |
|---|---|---|
| **Fjord** | Sea water that was land before the glaciers lowered it, where they lowered ground at sea level by at least 120 m (see [Coasts](#coasts)); the lakes behind the fjords' steps take the land's biomes | Oceans and cold oceans, deep or not |
| *(wetlands)* | Vanilla's swamp band: mangroves on tropical coasts (coldest month at least 15 °C, below 10 m), páramo on tropical mountains, bogs where the warmest month stays below 20 °C and the summers are not dry, tundra beyond the tree line, swamps elsewhere | Swamps and mangroves |
| **Cloud forest** | Tropical mountainsides: 1000–3500 m, 10–20 °C, little seasonality, at least 1500 mm of rain | Any land but peaks, deserts and badlands |
| *(snow and ice)* | Warmest month below 3 °C, or a year below −10 °C with summers too cool for trees (warmest month below 10 °C), where the ground is white most of the year. Vanilla's snowy plains, ice spikes, snowy slopes and peaks stay; its frozen band has snowy taigas there too, and taigas where it is wettest, which become snowy plains, and its other land does the same, its slopes and hills snowy slopes | Any land but deserts, badlands and jungles |
| **Salt flat** | Desert (aridity below 0.5) with less than 4 m of relief on a basin's floor, below the 5th percentile of the ground within 7.7 km: the dry beds of old lakes, up on the cold Altiplano too. About 3% of the driest deserts, where flat ground anywhere made 57% | Plains, forests, taigas, savannas, deserts |
| **Páramo**, **puna** | Tropical mountains (little seasonality) from 2500 m where the year averages below 10 °C. Every month is a growing season there, so the tree line follows the year rather than the summer, and the cloud forest gives way to them. Páramo with at least three times the rain of the aridity threshold, as in the northern Andes or on Mount Kenya; puna where it is drier, as on the Altiplano or Mauna Kea | Any land, peaks included |
| **Tundra**, **alpine tundra** | Beyond the tree line: warmest month below 10 °C. Alpine on mountains | Any land, peaks included |
| *(montane forest)* | Humid mountainsides (aridity at least 1) below the subalpine belt, where the warmest month passes 14 °C, some 600 m below the tree line. Vanilla's plateau row puts meadows there between the woods of its lowland row, in strips along every valley side; they become those woods: forest-steppe where the summers are too dry for closed woods, or prairie where they are hot and the ground unbroken (the meadow stays in the steppe's climate), mixed forest in its climate; elsewhere forest, and with at least twice the rain of the aridity threshold birch forest, or taiga below 5 °C. Semi-arid mountainsides get juniper woodland (see below). Meadows stay in the subalpine belt and on ground drier still | Meadows |
| **Monsoon forest** | Frost-free lowland (coldest month at least 15 °C) with at least the rain of the aridity threshold. Vanilla only has temperate woods for it between its savannas and its jungles; jungles count too where the rain is strongly seasonal (BIO15 at least 80 %) and below 2000 mm, as in India and Thailand. Wetter jungles stay, as in the Amazon | Forests and taigas, jungles |
| *(savanna)* | The same frost-free land (coldest month at least 15 °C) with at least the rain of the aridity threshold. Vanilla puts its plains there wherever its humidity is low, about half of the wet-and-dry tropics in the worlds surveyed, but grassland that warm, with a dry season, is savanna, as in the Cerrado around Brasília, the Llanos and around Darwin | Plains, sunflower plains |
| **Laurel forest** | Jungle where the coldest month falls below 15 °C, so frost can come. Vanilla's jungles start at a yearly mean of 15 °C, which takes in the humid subtropics, as around Shanghai or Atlanta | Jungles |
| **Moorland** | Rolling uplands (300–1500 m, less than 90 m of relief) with cool summers (warmest month below 15.5 °C), coldest month above −6 °C, at least 800 mm of rain | Plains, forests, taigas, savannas, hills and meadows |
| **Bog** | The flattest land (less than 6 m of relief) below 1500 m, with cool summers (warmest month below 20 °C) and at least twice the rain of the aridity threshold, as in Finland, the West Siberian Plain and the Hudson Bay Lowlands. Not where maquis grows: peat needs ground wet all summer, and a Mediterranean summer is dry. Vanilla puts swamps only on low ground; bogs grow on flat land at any height below the mountains | Plains, forests, taigas, savannas |
| **Larch taiga** | Coldest month below −25 °C, with at least the rain of the aridity threshold, as in eastern Siberia and the Greater Khingan. The ground stays frozen all year a metre down, and on the layer that thaws in summer larches, which shed their needles for the winter, take over from the evergreen spruces. Vanilla puts snowy plains, snowy taigas and ice spikes there below a yearly −5 °C, and taigas above it. Larches grow in the coldest winters of all, as at Verkhoyansk and Oymyakon, wherever the summers pass 10 °C. Milder winters keep vanilla's spruce, or its forest-tundra where the summers stay cool. Ground too dry for them is steppe or cold desert (see below) | Snowy plains, snowy taigas, ice spikes, plains, forests, taigas, savannas |
| **Temperate rainforest** | 4–14 °C, coldest month above −3 °C, at least 1600 mm of rain | Plains, forests, taigas, savannas, slopes and hills |
| **Maquis** | 12–21 °C, coldest month 2–15 °C, 250–1000 mm of strongly seasonal rain, below 1200 m. Where frost never comes, the rain comes with the summer instead, as the aridity threshold has it, and grows savanna and dry woods, as on the uplands of Malawi; before, maquis took a quarter of seed 42's hot deserts, most of them frost-free | The same, deserts and meadows too, but no other slopes |
| **Heath** | Oceanic lowland below 300 m: 6–12 °C, coldest month above −4 °C, 550–1100 mm of rain. Only where the variant noise is below −0.3, so it comes in patches among the woods | Plains, forests, taigas, savannas |
| **Forest-tundra** | From the tree line to where the warmest month reaches 13 °C, the northern edge of the closed forest, with at least the rain of the aridity threshold. The taiga thins out there over a hundred kilometres or more into clumps of stunted black spruce and crooked birch on lichen and moss, as along Hudson Bay, in northern Quebec and Labrador, on the Kola Peninsula and in Iceland. Vanilla puts its taigas, forests and snowy plains there. On a mountainside it is the belt of scattered, wind-bent trees just below the tree line | Snowy plains, snowy taigas, ice spikes, plains, forests, taigas, savannas |
| **Forest-steppe**, **steppe** | Humid by the year's rain (aridity at least 1), but not by the summer's: the warmest month reaches 17 °C, the year averages −2 to 18 °C, and less rain falls than the threshold at the warmest month's temperature (see [Summer moisture](#summer-moisture)). Groves of birch and oak among the meadow-steppe, as from Ukraine to the Altai and in the aspen parkland of the Canadian prairies; with less than 0.7 of that rain, steppe, as around Astana and Orenburg | Plains, forests, taigas, savannas; on humid mountainsides, meadows (see the montane forest) |
| **Prairie** | The forest-steppe's climates where the warmest month reaches 22 °C, Köppen's hot summer, with less than 1.5 of the summer's rain: where the woods would give way to the forest-steppe in cooler summers, and on ground their climate would wood. Hot summers dry the grass for the fires, and the fires and the grazers keep the trees off, as from Illinois to Kansas, on the Songnen plain round Harbin and on the western pampas. The fires die out on broken ground: from 60 m of relief the hills stand wooded over the grass, as the Ozarks do. Saint Louis, with more rain, stays forest. Seed 2024 had three-quarters of its land with hot summers and cold winters (Dfa) there, and its swamps the rest | Plains, forests, taigas, savannas; flat meadows |
| **Mixed forest** | 3.5–9 °C, warmest month at least 16 °C, coldest month below −1 °C, with at least the rain of the aridity threshold, as in southern Finland and Sweden, around Moscow, by the Great Lakes and in Manchuria. Oaks and birches grow among the spruces and pines there. Vanilla draws its line between taiga and forest at 5 °C, through the middle of that belt, and grows one or the other on either side of it. The oak needs a summer warm enough to ripen its acorns; milder winters favour the beech, and the forest is broadleaf, as in Denmark and around Berlin, which vanilla's forest already is | Forests, birch forests and taigas; on humid mountainsides, meadows (see the montane forest) |
| *(taiga)* | Below 3.5 °C, with at least the rain of the aridity threshold. Vanilla grows its forest of oaks and birches down to −5 °C wherever the rain is moderate, and its taiga only where it is wetter, but only spruces, pines, firs, larches and birches stand those winters: the boreal forest, as around Kirov and at Fort Smith. Before, vanilla's forest made a sixth of the boreal land of seed 42 | Forests |
| **Cold desert** | Desert (aridity below 0.5) cooler than 12 °C | Plains, forests, taigas, savannas, deserts; snowy plains, snowy taigas and ice spikes beyond the snow and ice |
| **Desert scrub** | Desert from 12 °C with at least 0.15 of the rain of the aridity threshold, as in the Mojave, the Sonoran Desert and the Karoo, where shrubs grow over stony ground. Only drier deserts are hyperarid, getting less than a twentieth of the water the sun could take back, as in the heart of the Sahara; they are bare: stony desert, or vanilla's sand | Deserts |
| **Stony desert** | Hyperarid desert (below 0.15 of the rain of the aridity threshold, from 12 °C) that rises 20 m or more above the low ground around, the 5th percentile of the ground within 7.7 km. The wind sweeps the sand off the higher ground and leaves gravel plains and bare rock, the reg and hamada that make most of the Sahara, as in the Tanezrouft and on the Hamada du Draa. The sand gathers below, in the ergs, and stays vanilla's desert. Of the driest deserts, 49% are stony, 44% sand and 3% salt flats | Deserts |
| **Juniper woodland** | Semi-arid (aridity 0.6 to 1), 3–18 °C, on rugged ground: at least 60 m of relief, or a mountainside's meadow below the subalpine belt. Junipers and pinyon pines root in the cracks of the rock, where the water gathers that the grass of the plains takes first. So the ranges of the Great Basin, the mesas of the Colorado Plateau, the foothills of the Tien Shan and the hills of Anatolia and Iran are open woods above the steppe of their valleys | Plains, forests, taigas, savannas; on semi-arid mountainsides, meadows |
| **Shrub steppe** | Semi-arid and cooler than 18 °C where the ground is too flat, too dry or too cold for junipers, and either nearly desert (aridity below 0.65) or watered in the cool season (the coldest month above 2 °C, from which the aridity threshold takes the rain to fall in winter). Winter's rain soaks deep while little of it evaporates and waters the long roots of shrubs; summer's wets only the top of the soil, which the grass takes first. So sagebrush and saltbush cover the Great Basin and the Columbia Plateau, the steppe of Patagonia and the wormwood steppe of Kazakhstan. In the worlds surveyed, a third of the semi-arid steppe, and three-quarters in a drier one | Plains, forests, taigas, savannas; snowy plains, snowy taigas and ice spikes beyond the snow and ice |
| **Steppe** | The rest of the semi-arid land cooler than 18 °C: summer rain, and more of it, as on the Great Plains and in Mongolia; and where the summers are drier still (see the forest-steppe). Vanilla's frozen band puts snowy plains on such ground, as it does on the wetter ground of the larches, but so little snow falls there that it lies thin and blows away: the cold steppe of the Chuya basin below the Altai and of Mongolia's basins, which freezes deep in winter. Seed 7 had 2% of its land there | Plains, forests, taigas, savannas; snowy plains, snowy taigas and ice spikes beyond the snow and ice |
| **Thorn scrub** | Semi-arid land from 18 °C with less than 0.7 of the rain of the aridity threshold: the driest savannas, toward the deserts, as in the Sahel, the Caatinga, the Chaco, the rain shadow of the Deccan, Tamaulipas and the bush of Kenya. Thorn bushes, low mesquites and acacias grow there among grass too thin to carry the savanna's fires, under the odd baobab. Vanilla's savanna stays from 0.7 to the threshold, and the maquis comes first. Seed 1 had two-fifths of its hot semi-arid land there, as much as the savanna kept | Plains, forests, taigas, savannas |

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

**Sulfur caves.** Vanilla puts its sulfur caves where the weirdness is below −0.85, which Diffusicraft's terrain never
reaches (its weirdness stays within ±0.67), so they would never appear. They go where water rich in hydrogen sulfide
rises through the ground instead, as at Frasassi in Italy and Movile in Romania: `Geology.sulfurous` marks patches a few
hundred blocks across over the volcanic side of the basement (its lava fields and the schist belts around them), about
one part of the ground in thirty at every scale. There every vanilla pick between vanilla's cave depths (0.2 to 0.9: 26
to 115 blocks under the surface, or under y = 123 where the land stands higher) becomes sulfur caves, and so do most of
the Grand Hollows (see [Life in the Hollows](#life-in-the-hollows)). Vanilla's material rule then bands all their rock
with sulfur and cinnabar, and its springs, pools and sulfur spikes grow in their caves.

**Plumbing.** A biome source is shared by every world that uses a preset, so it learns which world it serves from the
climate sampler. `RandomStateMixin` attaches a `BiomeWorld` (the terrain, scale, Hollows and rocks) to the random state
of Diffusicraft's noise settings and to every `Climate.Sampler` it creates (`ClimateSamplerMixin`). Every way the game
asks for a biome goes through such a sampler: chunk generation, structure placement, `/locate`, `/place` and the debug
screen. All of them see the refined biomes. In other worlds the sampler carries nothing and the source behaves exactly
like vanilla's. It still extends `MultiNoiseBiomeSource` (its constructor is opened by `diffusicraft.classtweaker`), so
mods looking for a multi-noise source find one.

**The new biomes.** Each starts from a vanilla template. It keeps the template's carvers, underground features and
monsters, so caves and ores look the same under every biome, and replaces the vegetation, the ground, the animals and
the colours:

| Biome | Template | Vegetation | Ground |
|---|---|---|---|
| Tundra | Snowy plains | Dwarf birch scrub, moss mats, berry bushes, boulders | Grass with coarse dirt and podzol over peat |
| Alpine tundra | Meadow | Knee-high krummholz spruce, alpine flowers, moss, boulders | Grass broken by gravel scree and bare rock |
| Cloud forest | Jungle | Canopy trees and gnarled oaks hung with [lianas](#lianas), over an [undergrowth](#undergrowth) of shrubs of oak leaves, ferns, moss; dense fog (128 blocks) | Grass and podzol over laterite |
| Temperate rainforest | Old-growth spruce taiga | Giant and mega spruces, now and then a redwood, ferns, moss, mushrooms; light fog | Grass and podzol |
| Redwood forest | Old-growth spruce taiga | [Redwoods](#giant-trees) 110 to 230 blocks tall at 1:1, one or two in most chunks, over spruces, mega spruces and fallen trunks, large ferns, moss, mushrooms, berries; fog (256 blocks) | Grass with deep needle litter (podzol) and coarse dirt |
| Ancient woodland | Forest | [Ancient oaks](#giant-trees) 48 to 80 blocks tall at 1:1, one to every 3×3 chunks, over oaks, big oaks and birches far apart, as in a wood pasture, and fallen trunks; flowers, bushes, mushrooms | Grass with leaf mould (podzol) |
| Old-growth jungle | Jungle | [Kapoks](#giant-trees) 80 to 130 blocks tall at 1:1, one to most 3×3 chunks, rising out of a canopy of jungle trees, mega jungle trees and jungle bushes, some 20 to a chunk, hung with [lianas](#lianas), over a nearly closed [undergrowth](#undergrowth) of shrubs and large ferns; melons and moss | Grass, red laterite below |
| Maquis | Plains | Evergreen scrub, olives, stone pines, cypresses, bushes, alliums | Thin grass and terra rossa on limestone |
| Cold desert | Desert | Sagebrush, dry grass, scattered boulders; rain and snow | Sand with gravel and coarse dirt, sandstone below |
| Desert scrub | Desert | Creosote and saltbush mounds, low mesquites, columnar cacti and vanilla's on the sand, dry grass, dead bushes, desert marigolds and sand verbena, boulders; rabbits, armadillos and the odd camel | Coarse dirt with gravel pavement and drifts of sand, sandstone below |
| Stony desert | Desert | Dead bushes and the odd boulder; a few rabbits and camels | Gravel with bare sandstone and drifts of sand, sandstone where it is steep and below |
| Steppe | Plains | Grass, dry grass, tulips and poppies, a lone oak or birch | Grass with patches of coarse dirt over loess |
| Shrub steppe | Plains | Sagebrush a stride or two apart, as waist-high mounds of silvery leaves and low bushes, over dry bunchgrass, paintbrush and globemallow (tulips), wild onion (alliums), dead bushes and the odd boulder; wild horses and burros, sheep, jackrabbits (rabbits), foxes and coyotes (wolves) | Grass with bare coarse dirt and gravel over loess, rock where it is steep |
| Juniper woodland | Plains | Junipers, pinyon pines and young junipers a few strides apart over bunchgrass, sagebrush (bushes), dead bushes, paintbrush and globemallow (tulips), wild onion (alliums) and the odd cornflower, boulders; wild horses and burros, bighorns (goats), rabbits, foxes and coyotes (wolves). Its villages are the desert's, which look like adobe pueblos | Grass with coarse dirt and gravel, rock where it is steep |
| Prairie | Plains | Tall grass waist-high and thick, with short and dry grass, wild onion (alliums), sunflowers, ox-eye daisies, dandelions (coneflowers), pink tulips (blazing star) and cornflowers (lupins); a lone oak or big oak in one chunk in twenty, sugar cane and fireflies by the water; herds of bison (cows) and horses, sheep, chickens, pigs, rabbits, foxes and wolves | Grass over loess |
| Forest-steppe | Plains | Groves of birches (aspens), oaks and the odd big oak or pine, with fallen leaves and now and then a fallen trunk, among tall grass, cornflowers (sage), ox-eye daisies, poppies, dandelions (buttercups) and azure bluets; a lone tree here and there, bushes; the plains' farm animals, with horses, rabbits and foxes | Grass |
| Thorn scrub | Savanna | Thorn bushes, low creosote, mesquites and the odd acacia, alone and in thickets, and a baobab in one chunk in eight; bushes, dry grass, dandelions and alliums, columnar cacti on the sand, dead bushes, fireflies by the water; armadillos, goats, rabbits, donkeys, chickens and horses | Grass with coarse dirt, red laterite and drifts of red sand, laterite below |
| Moorland | Plains | Heather, moor grass and cottongrass, granite tors, the odd birch or spruce, berries | Grass with bare peat and sphagnum (moss), peat below |
| Heath | Plains | Heather and grass under scattered birches with bees, Scots pines and junipers | Grass with sandy coarse dirt and podzol |
| Salt flat | Desert | Nothing | Salt crust over rock salt |
| Páramo | Meadow | Frailejones, the odd queñua, tussock grass, gentians, groundsels and lupins, moss cushions, dark pools, boulders; fog (192 blocks) under a grey sky | Grass with cushion bogs (moss) and coarse dirt over peat, rock where it is steep |
| Puna | Meadow | Golden ichu grass, tola scrub, yareta cushions among boulders, a rare queñua; herds of llamas, under a deep blue sky. Its villages are the desert's, whose sandstone looks like adobe | Grass with gravel and coarse dirt, rock where it is steep |
| Fjord | Cold ocean | Kelp and seagrass in deep teal water; the cold ocean's fish and squid, wrecks and ruins, but no monuments, which keep to the open deep sea | The cold ocean's |
| Monsoon forest | Jungle | Teak and sal, acacias, jungle bushes, bamboo, dry grass, drifts of fallen leaves, warm flowers; junglefowl (chickens), wild boar (pigs), parrots and ocelots | Grass with red laterite and coarse dirt showing, laterite below |
| Laurel forest | Forest | Laurels with dense, glossy crowns, live oaks hung with moss, azalea trees and bushes (camellias and rhododendrons), bamboo, ferns, fallen leaves, fireflies; wild boar (pigs), foxes, rabbits, armadillos and, rarely, pandas | Grass with leaf mould (podzol) and coarse dirt, red laterite below |
| Forest-tundra | Taiga | Clumps of stunted black spruce, crooked mountain birches and spruce bent flat by the wind, with the odd taller white spruce, on dwarf birch scrub, grass, Labrador tea (bushes), lingonberries (sweet berries), reindeer lichen and moss, mushrooms, boulders; rabbits, foxes, wolves and reindeer (sheep) | Grass with coarse dirt and podzol over peat |
| Larch taiga | Taiga | Open stands of larches, light green, with young and stunted ones, birches and Scots pines where the ground thaws deeper, grass, Labrador tea (bushes), lingonberries (sweet berries), mats of reindeer lichen and moss, mushrooms; Yakutian horses, reindeer (sheep), cattle, foxes, wolves and rabbits | Grass with needle litter (podzol) and frost-churned coarse dirt |
| Mixed forest | Taiga | Norway spruces and Scots pines, with birches, oaks and the odd big oak, which drop their leaves on the needles; now and then a fallen trunk, large ferns, grass, lilies of the valley and azure bluets (wood anemones), sweet berries, mushrooms, fireflies by the water; the taiga's animals | Grass with needle litter (podzol) under the conifers |
| Bog | Swamp | Dark pools of peaty water with lily pads, cottongrass, moss carpets, heather, a few spindly black spruces, Scots pines and birches, berries, fireflies; frogs, and the swamp's slimes, witches and bogged | Sphagnum (moss) with bare peat and grass, peat all the way down |

The alpine tundra flowers with edelweiss and gentians, the maquis with lavender, and cottongrass grows on the tundra and
the bogs. The frailejones of the páramo are pale oak: silver-green rosettes on trunks one to three blocks tall. The
queñuas (Polylepis), which grow higher than any other tree, are mangrove wood for their red bark, on gnarled, forking
trunks. The yareta of the puna is a cushion plant so dense that it looks like a green boulder: here it is one, of moss.
The teak stands on a tall, clear trunk under a round crown, and the laurels under dense domes. The live oaks of the
laurel forest are dark oak wood under oak leaves, hung with pale hanging moss for Spanish moss. The bog's pools are
vanilla's `waterlogged_vegetation_patch`, the lush caves' clay pools: it sinks a patch of peat into the ground and fills
its top with water wherever the water is held on every side. The larches are spruce wood under oak leaves, which take
the biome's light green, in narrow, open cones; their reindeer lichen is pale moss. The junipers are acacia wood, grey
like their weathered bark, under oak leaves that take the biome's grey-green, on short, forking stems; the pinyons are
round crowns of spruce. The mountain birches of the forest-tundra are birch on a short, bending trunk, and its black
spruces the bog's. The thorn bushes of the thorn scrub are wide, low mounds of acacia leaves on a short stem of acacia
log, and its baobabs acacia wood, a 2×2 trunk a dozen blocks tall under a flat, spreading acacia crown.

The redwood forest, the ancient woodland and the old-growth jungle are no climate's own: they come only as
[patches](#sub-biomes) of the woods around them, on level land, and carry the groves of [giant trees](#giant-trees).

Their ground is the material rule `diffusicraft:biome_surface`, which runs ahead of vanilla's overworld surface rule
and leaves every other biome to it. They join vanilla's structure tags (villages, outposts, ruins) and the `c:`
convention tags where they fit. With so much open land, the villages and outposts are spread wider than vanilla's (see
[Villages and outposts](#villages-and-outposts)).

The game requires one order of features that every biome agrees with, and other mods' biomes take part in it. So a
new biome names a vanilla feature only where its template lists it too, in the template's order. Any other vanilla
feature it uses is a copy under Diffusicraft's name, and Diffusicraft's own features run after vanilla's. No order
these biomes impose can then clash with another mod's.

`tools/biomes.py` writes all of this: the biomes, their features, the surface rule and the tags. It reads the
templates from the Minecraft jar Loom unpacked.

### Sub-biomes

The climate changes over tens of kilometres of real ground, so the biome it picks can cover an area that size: at 1:1
one forest could run on for days of walking. Real woods break up within one climate over a few hundred meters, into
birch stands, glades, bogs and old growth, with the soil, the water and the last fire. So after the refiner, the biome
source breaks each biome into patches of its siblings (`SubBiomes`).

**Patches.** The world is cut into cells 320 blocks wide, at every size. Each holds one patch around a point placed at
random in the middle 80% of the cell, with a radius of 90 to 160 blocks, its edge frayed by noise with a wavelength of
96 blocks that moves it by up to 60% of the radius. A column belongs to the patch it lies deepest in, so patches that
meet do so along a curve. Patches cover 47% of the land, and a straight line runs through one for 180 blocks on
average. Each patch draws a number, which the family of the column's biome turns into one of its siblings, or into
nothing, which keeps the biome. On level ground a quarter of a forest is other woods and glades.

**Ground.** Some siblings keep to their ground, and their patches shrink back from ground that does not suit them. The
ground is the column's height over the mean of the land around (over 1.4 km of real ground), in units of its relief
(at least 15 m), as vanilla's peaks and valleys have it. A patch loses 0.8 of its depth, which is 1 at its centre and
0 at its edge, for each unit of height on the wrong side:

- **low**, the lower half of the land, where the water gathers: dark forest and old spruce, the groves of the plains
  and the steppe, the bamboo of the jungle, the gallery forests of the savanna and the prairie, the savanna of the thorn
  scrub;
- **high**, the upper half, which dries out first: pines, light woods, open jungle, shrub steppe and junipers, thorn
  scrub, stony desert and desert scrub, tundra on the exposed rises of the forest-tundra;
- **wet**, flat hollows, where the water stands: bogs, which also shrink from 10 m of relief and are gone by 25 m;
- **flat**, level land at any height, where the giants stand: the groves of giant trees, which shrink from 25 m of
  relief and are gone by 60 m, so they keep to the plains and the broad valleys. The old-growth jungle of the sparse
  jungle keeps to its low ground instead.

**Climate.** Siblings that need a climate of their own come only where it holds. Bogs need peat's cool, wet summers,
as the refiner's bogs do (warmest month below 20 °C, at least the rain of the aridity threshold, below 1500 m, not in
the maquis). Trees in the snowy plains and the meadows need summers at least 1 °C past the tree line, ice spikes summers
short of it. The dry forests of the savanna need frost-free land with at least the rain of the aridity threshold,
jungle in the monsoon forest 1500 mm, thorn scrub in the savanna less than the rain of the aridity threshold, desert
scrub in the thorn scrub less than 0.65 of it, and junipers in the shrub steppe their own climate (see the refiner); the
juniper woodland's patches are steppe or shrub steppe, whichever its climate would have. Redwoods need mild winters
and rain, a coldest month of −12 °C or more and at least 700 mm, as on the coasts where real redwoods grow: the
redwood groves of the boreal forest keep to its milder, wetter edges, and the temperate rainforest has them anywhere.

The families, with the share of the patches each sibling takes; the other patches keep the biome:

| Biome | Its patches |
|---|---|
| Forest | Birch forest 16%, flower forest 8%, plains 8%, dark forest 8% (low), dappled forest 6% (high), old-growth birch forest 4%, ancient woodland 5% (flat) |
| Birch forest | Forest 14%, old-growth birch forest 10%, flower forest 6%, plains 6%, ancient woodland 3% (flat) |
| Old-growth birch forest | Birch forest 16%, flower forest 6% |
| Dark forest | Forest 14% (high), birch forest 6% (high), pale garden 4% (low), ancient woodland 6% (flat) |
| Pale garden | Dark forest 16% |
| Flower forest | Forest 14%, plains 8%, birch forest 6%, ancient woodland 4% (flat) |
| Dappled forest | Forest 10% (low), plains 10%, birch forest 5% |
| Plains | Sunflower plains 12%, forest 12% (low), flower forest 5%, dappled forest 5%, birch forest 4% (low), ancient woodland 6% (flat) |
| Sunflower plains | Plains 16%, forest 8% (low), ancient woodland 4% (flat) |
| Prairie | Forest 10% (low), sunflower plains 8%, ancient woodland 3% (flat) |
| Ancient woodland | Forest 14%, flower forest 6%, plains 6% |
| Taiga | Old-growth spruce taiga 10% (low), old-growth pine taiga 10% (high), bog 10% (wet), birch forest 8%, redwood forest 4% (flat, mild) |
| Old-growth spruce taiga | Taiga 14% (high), bog 6% (wet), redwood forest 8% (flat, mild) |
| Old-growth pine taiga | Taiga 14%, old-growth spruce taiga 6% (low), redwood forest 6% (flat, mild) |
| Redwood forest | Old-growth spruce taiga 12%, taiga 6% (high) |
| Snowy taiga | Snowy plains 14% (high) |
| Snowy plains | Snowy taiga 10% (low), ice spikes 3% |
| Ice spikes | Snowy plains 20% |
| Meadow | Taiga 8% (low) |
| Jungle | Sparse jungle 10% (high), bamboo jungle 10% (low), old-growth jungle 10% (flat) |
| Sparse jungle | Jungle 14% (low), bamboo jungle 4% (low), old-growth jungle 4% (low) |
| Bamboo jungle | Jungle 18%, old-growth jungle 6% (flat) |
| Old-growth jungle | Jungle 12%, bamboo jungle 6% (low) |
| Savanna | Monsoon forest 8% (low), sparse jungle 4% (low), thorn scrub 8% (high, semi-arid) |
| Thorn scrub | Savanna 12% (low), desert scrub 6% (high, nearly desert) |
| Desert | Stony desert 6% (high) |
| Desert scrub | Desert 10% (low), stony desert 6% (high) |
| Mixed forest | Taiga 10% (high), birch forest 10%, forest 10% (low), bog 5% (wet), flower forest 3%, redwood forest 4% (flat, mild) |
| Larch taiga | Bog 8% (wet), taiga 6% (low), birch forest 5% |
| Forest-tundra | Tundra 14% (high), taiga 8% (low), bog 6% (wet) |
| Temperate rainforest | Bog 6% (wet), redwood forest 14% (flat) |
| Steppe | Forest-steppe 8% (low), shrub steppe 8% (high) |
| Forest-steppe | Steppe 12% (high), forest 6% (low), birch forest 6% |
| Shrub steppe | Steppe 10% (low), juniper woodland 6% (high) |
| Juniper woodland | Steppe or shrub steppe 10% (low) |
| Moorland | Bog 14% (wet) |
| Bog | Taiga 10% (high) |
| Monsoon forest | Bamboo jungle 8% (low), savanna 8% (high), jungle 5% (low) |
| Laurel forest | Bamboo jungle 10%, cherry grove 4% (high) |

Rivers, shores, seas, slopes, peaks, caves and the Grand Hollows keep their biomes, and so do the biomes of other mods
and the rest of Diffusicraft's: only the biomes in the table vary. A sibling a datapack removed leaves its patches to
the biome. The patches are drawn from the seed alone and need nothing but the column's climate, so the chunks, the
LOD mods and the [relief map](MAP.md) all see the same ones.

### Giant trees

`diffusicraft:giant_tree`, a feature (`GiantTreeFeature`) and a structure (`GiantTreeStructure`), grows trees far
taller than any of vanilla's, as a fantasy of real giants, in three forms. Each is a trunk that tapers from a flared foot, and limbs that carry the leaves in clumps
(`GiantTree`, which draws the shape before it meets the world):

- **Redwoods**, 110 to 230 blocks tall, of mangrove wood for its red-brown bark under spruce needles: a straight
  column, bare for half its height or more, under a narrow cone of short, drooping branches and a spire, its foot
  flaring into ribs. Coast redwoods reach 116 m; these are twice that.
- **Ancient oaks**, 48 to 80 blocks tall, of oak, as one grows in the open: a short, massive trunk that forks low into
  five to seven great limbs and a leader. The limbs rise steeply, then reach out nearly level and fork into branches
  under a broad, lumpy dome of clumps, wider than it is tall and open inside, so that the limbs show from under it.
  On the larger ones, a long limb or two sweeps out low under the dome.
- **Kapoks**, 80 to 130 blocks tall, of jungle wood: the emergents of the rainforest, a pale, unbranched column on
  plank buttresses that rises over the canopy to a flat umbrella, on limbs that leave the trunk nearly level and
  climb toward their ends.

These are their heights at 1:1. The smaller the world, the smaller the giants, though far less than the land, which
is some sixteen times lower at 1:32 than at 1:1: each halving of the world takes about a sixth off their height
(`GiantTree.size`), so that they stand enormous over a 1:1 world and are merely big in a Vanilla one. The
shapes follow the height, so a smaller giant is a smaller tree of the same build:

| Size | Of the height at 1:1 | Redwoods | Ancient oaks | Kapoks |
|---|---|---|---|---|
| Ultra (1:1) | 100% | 110 to 230 | 48 to 80 | 80 to 130 |
| Extreme (1:2) | 84% | 92 to 193 | 40 to 67 | 67 to 109 |
| Grand (1:4) | 71% | 78 to 163 | 34 to 57 | 57 to 92 |
| Large (1:8) | 59% | 65 to 137 | 29 to 48 | 48 to 77 |
| Medium (1:16) | 50% | 55 to 115 | 24 to 40 | 40 to 65 |
| Vanilla (1:32) | 42% | 46 to 97 | 20 to 34 | 34 to 55 |

The crowns spread with the height (`GiantTree.reach`): an oak's to six tenths of its height on every side, 58 to 96
blocks across at 1:1 and 24 to 40 at 1:32, and a kapok's to a third, 52 to 84 blocks across at 1:1 and 22 to 36 at
1:32. A broad crown is clumps spread evenly over the shell of a spheroid, each sunk a little into it, so that the
dome is lumpy; each limb takes the clumps on its side, and forks into a branch to each group of a few of them.

Outside a Diffusicraft world, as on the superflat world of `TreesGameTest`, they grow to their height at 1:1.
`TreesGameTest` plants the ancient oaks and kapoks itself (`GiantTreeStructure.plant`), since their structures grow
only in Diffusicraft worlds.

The clumps keep within six blocks of the wood, as leaves must to live, and large ones hold twigs of their own; the few
leaves still farther out are placed persistent so that they do not decay. A giant grows after the smaller trees of its
biome and through their crowns, as an emergent does. It needs ground within 6 blocks of level around its foot and no
water: its roots fill down to the ground where it falls away a little, but not off a cliff. The height is cut to fit
under the top of the world.

A feature writes only in the 3×3 chunks around its own, so a redwood, a feature, reaches at most 21 blocks out from its
trunk, about 42 blocks across, and each is placed near the middle of its chunk, where it has the most room. Its crown
is narrow, so it takes the height. It looks down through up to 32 blocks of leaves and plants for its ground, needs
soil there, and does not grow under another giant's crown, nor against another's foot.

The ancient oaks and the kapoks spread broad crowns, so they grow as structures, whose crowns are not held to those
chunks. The structure's one piece (`GiantTreePiece`) keeps the draws of the tree rather than its blocks; as each chunk
it reaches is decorated, it plans the tree again and lays the blocks of that chunk and of the six blocks around it,
which tell how far each leaf lies from the wood, and writes that chunk's share. A structure is drawn before its chunk
has any blocks, so it reads the land from the generator: the structure's biome at the foot, dry ground there and on a
ring around the foot, and that ring within 6 blocks of level. It grows in the step `top_layer_modification`, after
every smaller tree, and only in Diffusicraft worlds. The jungles' [undergrowth](#undergrowth) and [lianas](#lianas)
run in that step too, after it, so that its roots stand in the undergrowth and the kapoks' umbrellas hang with lianas.

**Where.** The groves are biomes of their own, which come as [patches](#sub-biomes) of the woods around them on level
land: redwood forests in the mild, wet taiga and the temperate rainforest, ancient woodlands in the forests and on the
plains, old-growth jungle in the jungle. Redwoods stand in two chunks of three there. The oaks and kapoks come from one
structure set, `diffusicraft:giant_trees`, which gives one chunk in each cell of 3×3 chunks, none within 6 chunks of
where a village may stand. Each of its structures grows in its own biomes (the biome tag
`diffusicraft:has_structure/<structure>`), so each chunk grows the giant of its biome: an ancient oak in every such
chunk of an ancient woodland, a kapok in 7 of 10 of the old-growth jungle's. Outside the groves, lone giants grow here
and there, and only in Diffusicraft worlds: a redwood in one in 6 chunks of the temperate rainforest and in one in 16
of the old-growth spruce and pine taigas (the placement filter `diffusicraft:diffusicraft_world`), an ancient oak in
one in 24 of vanilla's forests (flower, birch and dark forests too) and one in 48 of its plains and sunflower plains,
and a kapok in one in 10 of its jungles. So the plains, the largest open land, show a lone giant oak a hundred blocks
or so apart where the ground is level, and their ancient woodlands a stand of them.

The LOD mods and the far terrain draw the giants too, where the world grows them (see
[COMPATIBILITY.md](COMPATIBILITY.md#distant-horizons)).

### Lianas

Vanilla's jungle trees hang their vines four blocks or so below a leaf, so that its jungles show open air under their
crowns. `diffusicraft:lianas` (`LianaFeature`) lets those vines grow down towards the floor, as they would over the
years. It runs once per chunk, after the trees and the giants, in the last step (`top_layer_modification`), over every
column of the chunk. Each vine with a face on a side that hangs over air grows, with the feature's chance, at least
halfway down to whatever is below it, and to the floor at most. The vines it adds keep the faces on the sides of the
vine they hang from, which is what holds them: as in vanilla, a vine with a face on a side stays wherever the vine over
it has that face. A vine under a leaf, with only its face on top, holds nothing below it and stays as it is.

The chance is 60% in the old-growth jungle, the cloud forest and vanilla's jungle, and 30% in vanilla's sparse and
bamboo jungles. Vanilla's jungles have them only in Diffusicraft worlds.

Vanilla's jungles scatter free vines between y = 64 and 100 as well, which misses the crowns of most jungles in a
Diffusicraft world, since they stand higher. The old-growth jungle and the cloud forest place theirs within 16 blocks
under the top of the canopy instead, 64 tries a chunk (`canopy_vines`), about as thick as vanilla's, and on the
umbrellas of the kapoks too, whose lianas fall forty blocks and more.

### Undergrowth

Vanilla's jungles leave open grass under their trees, where a rainforest's floor grows thick. `diffusicraft:undergrowth`
(`UndergrowthFeature`) fills it, after the trees, the giants and the lianas. A smooth noise of the world's seed
(`Thickets`), broad thickets broken up by finer ones, sets how thick it grows at each column, from 0 in a clearing to
1 in a thicket. In the thickets shrubs grow: mounds of persistent leaves that follow the ground, highest over the middle
and a block high at the ragged rim, up to 4 blocks high and 9 across, the ones 3 blocks high or more around a stem of
jungle log. Between them and in the clearings, large ferns, ferns, grass, bushes and lone leaves cover the ground,
thickest in the thickets. The feature's `cover` sets how thick it all grows: 0.8 in the old-growth jungle, whose floor
is nearly closed, 0.7 in vanilla's jungle, 0.6 in the cloud forest, whose shrubs are of oak leaves, and 0.35 in
vanilla's sparse and bamboo jungles. Nothing grows where a structure on the surface reaches into the chunk, and
vanilla's jungles have it only in Diffusicraft worlds.

### Waterfalls

Every fall in the world is a river's. The water of a river follows the ground in blocks (see Falls under
[Rivers](#rivers)), so where the ground drops away under it, over a ledge, the step of a valley, a scarp or a sea cliff,
the river falls with it into the water below, and a tributary that comes out over the side of a deeper valley falls
into the river there. No stream is laid out for a fall: a cliff that no river runs over has none, and the water at the
foot of a fall is the river's own, which runs on. `diffusicraft:river_falls` (`RiverFallFeature`) hangs the curtains
and digs the pools under them.

**Where.** Only in Diffusicraft worlds: `ChunkGeneratorMixin` places the falls of the rivers right after vanilla's
water springs (`spring_water`), which nearly every land biome has, vanilla's, Diffusicraft's and other mods'
(`DiffusicraftWorldgen.AFTER_SPRINGS`). Added to the biomes instead, they would need a place in the order of features
that every mod's biomes agree with. A biome without vanilla's springs has no curtains, nor pools. The LOD mods do not
draw them.

**Vanilla's springs.** A water spring that vanilla sets in the face of a cliff, under the open sky, hangs a thread of
water down the face that stops in a puddle at its foot; on the cliffs of a large world it falls hundreds of blocks.
Diffusicraft worlds leave out every water spring whose water would fall more than 8 blocks onto anything but water
(`Springs`, which `SpringFeatureMixin` asks once vanilla has found the spot fit for one), so that every tall fall ends
in water and runs on. Springs in caves and under overhangs stay, as do those that fall into water or only a few blocks.

### Boulders

Two kinds of big boulder lie on the land, besides the small mossy ones of vanilla and of Diffusicraft's biomes
(vanilla's `forest_rock` blobs). Both have the same shape (`Boulder`): a block of rock bounded by its joints, not a dome
or a ball. It is a superellipsoid of power 6 to 10, nearly a box, 0.95 to 1.4 times its radius long, 0.6 to 1 times it
wide and as high as its flatness says, turned to a heading and tipped up to 25° about its long and its short axis, as it
came to rest. Five to nine planes break off its corners and edges, each 55% to 85% of the way out to its farthest point
that way, which leaves a block of flat faces and sharp edges, and few of them face down, so that it does not stand on a
point. Three waves wear its faces a little uneven, by up to a twelfth of its size, and frost has split one in four of
those at least 2.5 blocks from their centre to their top along an upright joint, from the top down to their middle. A
lone block left on its top goes, and so does any crumb that no longer holds on to the rest.

- **Erratics** (`diffusicraft:erratic`, `ErraticFeature`) are the lone boulders that the ice sheets left on open ground
  in the cold lands, of rock carried from far away rather than the ground's own: granite, gneiss or diorite. Most are 3
  to 5 blocks across, one in five 6 or more, one in twenty 13 or more, and the biggest some 25, as big as a house.
  They used to be rounder and bigger, one in ten 14 blocks or more, and read as balls strewn over the land. One chunk
  in forty has one where the year's mean temperature is 8 °C or less, none from 13 °C, and in proportion between.
- **Rockfall** (`diffusicraft:rockfall`, `RockfallFeature`): the boulders fallen from a rock face, at its foot. The
  feature tries 24 columns of the chunk for one: gentle ground on one side, a block up or down at most, where the
  rocks come to rest, and on the other a face that rises 2 blocks or more right beside the column and keeps rising at
  45° or more, at least 8 blocks within 12. One to three boulders (four under a face 24 blocks high or more) lie out
  from the foot and to either side, of the most common rock at the top of the ground up the face; a face mostly of
  soil sheds none. They are as big as the face is high, a tenth of its height across from their centres (1.5 to 4.5
  blocks), half to 1.1 times that, and the bigger the boulder, the farther out it rolled. A chunk in three of those
  with such a face has them.

Both shrink with the world, by the square root of the giant trees' shrinking: to 71% at 1:16 and 65% at 1:32. A
boulder's lowest block is sunk a quarter of its height into the ground at its centre, and where the ground falls away
under it, its rock fills the gap up to the ground at the centre; the bulge of its sides stands over the ground as on
level land. It is not set down where the ground under it is under water, falls away from the centre by more than a
quarter of its height or rises by more than half of it (an erratic, which lies on level ground only) or all of it (a
fallen rock, which may lean on its face), nor where a structure on the surface reaches into a chunk it would change. It
replaces no fluid, nothing built, no tree and nothing in `features_cannot_replace`. Moss carpets grow on the tops of
boulders where more than 700 mm of rain fall a year, on half of them from 2200 mm, and snow settles on them in the cold.

**Where.** Only in Diffusicraft worlds, whose climate the features read: `ChunkGeneratorMixin` places them right after
the falls (`DiffusicraftWorldgen.AFTER_SPRINGS`), so they reach every land biome too, and the falls are laid out on
bare ground while the boulders stay out of their water. The plants and trees grow after them, around them.

### Villages and outposts

Vanilla places a village in each cell of 34 by 34 chunks (544 blocks) whose chosen chunk lies in a village biome, and a
pillager outpost in one in five cells of 32 by 32 chunks. In a vanilla world two fifths of the land is village country
(plains, meadows, savannas, deserts, taigas, snowy plains), in patches a few hundred blocks across, so the villages
stand some 530 blocks apart.

A Diffusicraft world has far more of it. The [refined biomes](#refined-biomes) turn the open land of every climate into
prairies, steppes, heaths, scrub, tundra and open taigas, all village country, which take from a fifth to nearly nine
tenths of the land, most often more than half, and run on for thousands of blocks. There every cell holds a village:
with vanilla's spreads one stood every 400 blocks across the plains, and the world held up to twice vanilla's villages
and outposts for the same land.

So a Diffusicraft world spreads them three times as wide, rather than taking biomes out of the tags, which would leave
whole climates without a village: the villages one to a cell of 102 by 102 chunks, at least 24 chunks apart, the
outposts one in five cells of 96 by 96, at least 24 apart and still 10 chunks clear of the villages. In village country
the next village is now 1.2 to 1.7 km away, a short ride, and outside it, as in vanilla, there is none. The sets
`diffusicraft:villages` and `diffusicraft:pillager_outposts` hold these placements, with vanilla's salts, and no
structures. When a Diffusicraft world's structure state is made, `ChunkGeneratorStructureStateMixin` gives
`minecraft:villages` and `minecraft:pillager_outposts` their placements. Every village that vanilla, a data pack or a
mod lists in those sets still generates, only further apart, and other world types keep vanilla's spreads. The giant
trees keep their 6 chunks from the wider villages. A mod's set that keeps clear of `minecraft:villages` keeps clear of
vanilla's places, where no village now stands, which costs it a few places and nothing else.

Measured on the coarse climate's biomes, sampled every 250 blocks, for seeds 42, 7 and 20261052: squares of 60 km at 1:1
and 40 km at 1:32 around the origin, against vanilla worlds of the same seeds. Village to village is the median distance
from a village to the nearest other; the nearest village is the walk from village country, the nearest outpost the walk
from any land, as the median and the distance within which 9 in 10 places have one.

| World | Villages per 10,000² blocks of land | Village to village | Nearest village | Outposts per 10,000² blocks of land | Nearest outpost |
|---|---|---|---|---|---|
| Vanilla | 134 to 139 | 530 to 540 | 320 to 360, 670 to 840 | 19 to 25 | 1.0 to 1.4 km, 2.0 to 2.8 km |
| Ultra (1:1), vanilla's spreads | 76 to 243 | 390 to 450 | 230 to 260, 360 to 550 | 11 to 48 | 0.6 to 1.6 km, 1.2 to 3.9 km |
| Ultra (1:1) | 8 to 27 | 1.2 to 1.7 km | 0.7 to 1.2 km, 1.1 to 2.6 km | 1.8 to 7.9 | 1.6 to 3.6 km, 2.9 to 8.6 km |
| Vanilla size (1:32), vanilla's spreads | 175 to 298 | 400 to 410 | 230 to 250, 370 to 430 | 28 to 48 | 0.65 to 1.1 km, 1.2 to 3.2 km |
| Vanilla size (1:32) | 19 to 33 | 1.2 to 1.3 km | 0.7 to 0.8 km, 1.2 to 1.6 km | 4.6 to 8.3 | 1.6 to 2.4 km, 2.9 to 5.9 km |

The widest gaps are seed 20261052's at 1:1, where village country is a fifth of the land in scattered patches.
`WorldGenerationGameTest` checks that the villages and outposts of a Diffusicraft world take these spreads.

### Density and caves

The settings set `final_density` to vanilla's overworld density, with these changes:

- vanilla's `sloped_cheese` terrain is replaced by `diffusicraft:<scale>/terrain`, which is `(surface − y) / 16`.
  Its `y` is a gradient over the whole world, not vanilla's `minecraft:y`, which stops at y = 4062 and would leave
  the mountains of the tallest sizes solid up to the top of the world;
- the caves are vanilla's, but fewer, with more of them noodles (below);
- deeper than 25 blocks below the surface, the density is the minimum of the caves and
  [the Hollows](#the-hollows);
- [the sea cliffs and islets](#coasts) are set block by block: `diffusicraft:<scale>/coast_fill` and `coast_carve`;
- [the rivers](#rivers) come last: `diffusicraft:<scale>/river_fill` builds the ground up under them and
  `river_carve` cuts it down to their water, so their beds seal any cave below.

Everything else is vanilla, so worlds keep cave entrances, pillars, aquifers, the deep dark and structure blending
(the beardifier).

**Caves.** Vanilla's caves open about 8 % of the ground under the surface, most of it in cheese caverns, so a
Diffusicraft world's ground was as full of holes as vanilla's, and more with the Hollows. Its caves now open a little
over a third as much: 3.1 % rather than 8.5 % of the ground more than 6 blocks down, around the spawn of seed 20261029.
Noodles are the largest part of that, two fifths, where they were a twentieth; cheese caverns, a third before, are
now a few hundredths. The caves are vanilla's own noises, in `diffusicraft:<scale>/caves/…`, which
`tools/worldgen_data.py` writes:

| Cave | Change from vanilla |
|---|---|
| Cheese caverns (`cheese`) | The cheese noise must fall to −0.55 rather than −0.27 to open, so they are rare and smaller |
| 3D spaghetti and entrances (`entrances`) | Only where the noise `diffusicraft:entrance_gate` is above 0.2: whole tunnel networks in about a quarter of the ground, none elsewhere |
| 2D spaghetti (`spaghetti_2d`) | Only where `diffusicraft:spaghetti_2d_gate` is above 0.3, in under a fifth of the ground |
| Noodles (`noodle`) | In more of the ground (the noodle noise above −0.25 rather than 0), a little wider, and from the bottom of the world to the top rather than from −60 to 320 |
| Carved caves (`minecraft:cave`, `cave_extra_underground`) | One in six start (`NoiseBasedChunkGeneratorMixin`); ravines all start |

The gates are noises of their own with a wavelength of 256 blocks, and a tunnel closes within a quarter of a unit of
its gate's threshold, so the tunnels that are left are whole networks and the ground between them is solid.

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

- **Network.** `RiverNetwork` routes water over the coarse map, one cell per tile (7.7 km). Each land cell drains to its
  steepest lower neighbour, judged by its 5th-percentile elevation, which follows the valley floors. Where that leaves a
  pit, a priority flood within 16 cells finds the level at which it spills. The depression holds a lake when the cells
  joined to the pit below that level are at most 64, within 16 cells (123 km) either way, at least 2 m deep, and the
  runoff of the cells that drain into them outweighs what the lake's surface evaporates: 1.3 times the
  evapotranspiration, less the rain. Of nested depressions the outermost that holds a lake wins. The lake's cells take
  the level at which it spills, and the water crosses them to the cell it spills from and on into the cell it spills
  into. That cell and the ones after it on the flood's path are lowered evenly until the water reaches lower ground, the
  sea or another lake. Any other pit is breached: a priority flood within 8 cells finds the cheapest way out, and the
  cells on it are lowered just enough to drain. Water that reaches the sea is at sea level, so these ways out fall to
  the sea rather than to its floor, and a land cell whose 5th percentile lies below the sea is never a pit: a twentieth
  of it is sea, on a coast or in a fjord the ice drowned, and the rivers that reach it end there, unless its steepest
  descent leads straight into the ocean. Breached through to the coast, a fjord far up a valley would carry its rivers
  on at sea level under the mountains between. A lake's shores hold the flood of a pit higher than it; a lower lake is a
  way out. Pits with no way out keep their rivers in a pond. The discharge of a cell is the runoff of everything
  upstream, the precipitation less an evapotranspiration that grows with temperature. A river starts at 4 m³/s. The
  network is computed in regions of 32 by 32 cells, each from the map 82 cells around it, which holds every lake and
  breach its cells depend on, so the regions agree on every lake without looking at each other: a map 48 cells wider
  changes no cell of the 64 regions around the origin of seed 42. The first region of a world takes some 43 s on the CPU
  and 9 s on the GPU, most of it the coarse stage over the 1,700 km around it, 7 and 2 s more than the 50 cells that
  lakes of up to 16 cells needed. The network of a region itself takes some 20 ms.
- **Paths.** `CellRivers` draws the rivers inside a tile. A river crosses a side of the tile at that side's lowest
  point, running straight across, so the tiles on both sides agree without looking at each other. Inside, it follows the
  cheapest path, where low ground is cheap, and tributaries merge into a tree. On level ground the cost carries noise,
  so rivers meander instead of following the pixel grid; on steep ground they stay in their valleys. Paths are smoothed,
  and the water level only ever goes down along them. A basin that can hold a lake counts as the surface of its water
  rather than its floor, so a river crosses it at the height of its lowest point and leaves over that point. The paths
  follow the tile alone: the lakes of the blocks around and the great lakes (see below), which a river crosses in the
  same way, set only the river's level, and under their water the path follows the floor. The blocks can then draw the
  rivers of their tiles without one another's lakes. A river bound for the sea runs on to water that drains into it,
  past the valleys the ice drowned behind a step. Paths keep 32 pixels (960 m) from the sides but where they cross one,
  so the sea of a cell that ends its rivers can lie by a side of its tile, out of their reach. They then leave across
  that side at sea level, into the tile beyond, if that tile has sea within the reach of its own rivers: across the
  lowest such side, if more than one would do. The tiles on both sides decide this alike, from the terrain of both.
- **Width and depth.** About 4√Q meters wide for a discharge of Q m³/s, like real rivers. The smaller sizes widen them
  by the square root of their meters per block over 1.6, so they stay visible: 1.1 times at Extreme, 1.6 at Grand, 2.2
  at Large, 3.2 at Medium and 4.5 at Vanilla. Nothing gets wider than 480 m. The depth grows with the width, up to 10
  blocks at Ultra and to 10 over the cube root of the meters per block at the other sizes: 8, 6, 5, 4 and 3 blocks
  down to Vanilla. It shelves towards the banks.
- **Columns.** `Rivers.column` says what a river does to a column. The channel is cut down to the water, or built up on
  a footing 4 blocks deep where the river runs above the ground. Banks cut into higher ground blend into it over 1.5
  blocks across per block of height, out to 24 pixels (720 m) from the water at most, which keeps every column inside
  the tile whose river shapes it. A valley cut deeper than that reach can span at 1.5 blocks per block, from some 160 m
  at Vanilla to 310 m at Ultra, gets steeper walls: a gorge. Where the ground lies below the water, as on the downhill
  side of a river along a hillside, the bank stays as high as the water for 3 blocks and then falls 2 blocks per block
  to the ground, so the river rests on the slope rather than on an embankment over it. Where the water steps down, the
  banks within 2 blocks of the step rise above
  the higher water, which would otherwise spill over them and run beside the river as a second stream, and the water
  falls over the step (see Falls).
- **Falls.** The paths and their levels follow the terrain in pixels 30 m across, whose slopes are smooth, but the
  ground in blocks has ledges and cliffs, knolls and gullies. A river held to its smooth level would cut a trench
  through every rise of that ground, run on a dyke over every dip and fall nowhere, or, held level and then dropped,
  come down a slope in a staircase of falls. So at each size of world `RiverCourses` sets the water by the ground
  instead, every block along each path: the river's depth below the lowest ground across its channel and the 2 blocks
  of bank on each side, there and for the next 2 blocks downstream, whose banks rise above its water where it steps
  down, so that no bank stands out of the slope; and never rising. Where the ground drops away, the river falls with it, as far as the ground drops; where it slopes, the river
  runs down it a block at a time. A rise in its way it cuts through, at most 3 m (2 blocks) deeper than it usually
  lies, or down to its smooth level where that lies deeper, as through a ridge the smooth level breaches. Only over a
  hollow before a lesser rise does the water stand above the ground, never above its smooth level, on banks built up
  to hold it. Some levels are fixed. Within 4 blocks of a side of its tile a river keeps the level the coarse map
  gives the crossing, which the tile beyond has too, and comes down to it at most half a block per block, in rapids,
  or as steeply as its smooth level where that is steeper. It stands no lower than a lake it crosses, nor than the
  sea, and ends no lower than the river it joins or the pond it ends in, so a tributary that comes out above its
  river falls into it; the river it joins stays below the level the tributary came into the tile at, so the
  tributary never has to rise to reach it. The courses of a tile are worked out once per size of world, when its
  first column is asked for, and kept with the tile's rivers. The ground's rules would leave a wall of still water at
  each step, so `diffusicraft:river_falls`
  (`RiverFallFeature`, laid out by `RiverFalls` from the rivers' columns) hangs a curtain there: every column of the
  lower water beside the higher takes water flowing over the edge at the top of the step and falling water below it
  down to its own, as the game's water settles where it runs off a ledge, so nothing flows once the chunk is loaded. A
  step of a block takes only the water at its edge. Under a fall 2 blocks high or more the bed sinks into a pool, by a
  third of the fall's height (1 to 4 blocks), out to a block for every four of its height past the first (3 at most),
  shallower farther out, wherever the ground holds the water beside and below; the bed's sediment lines its floor.
  The pools come out the same whichever chunk digs them. A lake takes a curtain where a river falls into it, but no
  pool. The feature runs in every chunk of a Diffusicraft world, right after vanilla's springs
  (`DiffusicraftWorldgen.AFTER_SPRINGS`), and not where a structure on the surface reaches into the chunk.
- **Water and beds.** The material rule `diffusicraft:river` (`RiverRule`) fills the channel with water on a bed of
  sand and clay in the lowlands, and mostly gravel higher up. The valley, the channel and 3 blocks on each side, takes
  a river biome.
- **Lakes.** `CellBasins` finds the closed basins of a tile with a priority flood, which gives the height at which each
  would spill over. The sea is where the flood starts, but not the valleys the ice drowned (see [Coasts](#coasts)): a
  drowned valley cut off from the sea by a step above it is a basin whose floor lies below the sea. A basin can hold a
  lake when it is at least 1 m deep, two pixels across and spills above the sea, and lies at least 4 pixels inside its
  tile, so the tile alone decides its columns. `CellLakes` fills them. A river that crosses a basin fills it to the
  river's level, 1.5 m below its lowest point, whatever the climate, and flows on out over that point; where two rivers
  cross it, the lower sets the level. The rain fills the other basins: where the precipitation reaches the
  evapotranspiration, a basin fills to the brim. Drier basins fill less, down to nothing where the precipitation is 60%
  of the evapotranspiration, so deserts keep their dry basins. These lakes lie beyond the reach of any river, which
  would drain them. Below the sea the sea seeps in, so the rain fills a basin whose floor lies below it from sea level:
  the lakes behind the steps of drowned valleys stand above the sea though their floors lie far below it, as Mjøsa and
  Hornindalsvatnet do behind the sills the ice left. A lake whose water would stand no higher than the sea is the sea's,
  and does not show.
- **Lakes across tiles.** `BlockLakes` takes the basins that reach past the side of a tile. A block is two by two or
  three by three tiles; blocks of a size overlap, so every tile lies in four blocks of two by two and nine of three by
  three, and every side and corner lies inside one of each. One priority flood over a block's padded tiles finds its
  basins; it keeps those that stay 4 pixels inside the block but not inside a single tile, which the tile already fills.
  Both sizes are needed: the flood of a larger block can join a basin to a hollow beside it that reaches past the
  block's sides, where the smaller block keeps it apart. A river that runs into such a basin fills it to the level at
  which it enters, at most 1.5 m below the spill, where it flows on out; where several do, the lowest sets the level,
  and the rivers of the tiles around run over the lake's surface. A river enters it where it crosses an inner side of
  the block through it, near the point where it crosses, or inside a tile. To find where, the block draws the rivers of
  its tiles as the tiles alone would, without the lakes of the blocks around: their paths need nothing but the tile, and
  the lakes only raise the ground a river sees, so a river drawn without them reaches the basin no higher than the river
  drawn with them, which then runs on through the lake at its level. The rain fills the other basins as it fills a
  tile's, unless a river passes within reach of the water and would drain it. A tile takes the lakes of its thirteen
  blocks; where they overlap, the highest water wins. They are all real hollows of the terrain, so lakes that touch meet
  at one level, and the tiles on both sides of a side, which share the blocks that reach it, agree on the water there. A
  tile and its rivers therefore read the two rings of tiles around it, 24 tiles.
- **Great lakes.** `GreatLake` puts the water of a lake of the coarse map on the fine terrain. One priority flood over
  the padded tiles of all its cells, the water escaping into the sea, but not into the valleys the ice drowned, and past
  the edge of their padding, finds the hollows the terrain holds. The water stands at one level for the whole lake,
  between the level of the cell it spills into and the level at which it spills on the coarse map: the level at which
  its hollows cover the most of the lake's tiles. It fills every hollow of the padded tiles that holds water at that
  level, and a tile takes it where it is higher than its own lakes. The rivers of the coarse map run across the lake at
  the level of its water and leave it level with it, so the lake is fed and its outlet is cut down from its surface. The
  rivers see the lake as they see the lakes of a block. The lake is found once, for the first tile in it or next to it,
  which waits for the terrain of all its tiles: 23 to 27 s on the GPU for the three largest within 128 cells of the
  origin of seed 42, of 52 to 59 cells, and at 5 s a tile some five minutes on the CPU. The flood itself takes under a
  second. The prefetcher computes the tiles as soon as one of them is in its plan, then the water from them.
- **Lake columns.** A column is in a lake where one of the four pixels around it is and its ground is below the water.
  The lake decides such a column even within a river's reach, so the river flows into the lake and out again. The water
  of a lake the rain fills stops at the last whole block below the lake's level, so it never stands above the rim of its
  basin, and a lake shallower than a block does not show. A lake a river fills is level with the river, block for block,
  and the river's banks and the lake's shore hold it. The lake takes the rivers' water rule, on a bed mostly of clay and
  sand, but no river biome. A lake above the sea over ground the ice drowned takes the climate of land at the level of
  its water and away from the coast, so it gets the biome of the land around it, not the fjord. Its shore, two pixels
  wide, keeps its ground but is solid from below the water up, so no cave entrance drains the lake. Where the shore
  still lies below the water, it rises into a bank as the rivers' banks do.
- **Limits.** Where the fine terrain has no valley along the coarse drainage, the river runs on a built-up ridge like an
  embankment. Where a ridge of the fine terrain lies across it, the river cuts a gorge through: the coarse map, at
  7.7 km a cell, cannot see the passes between the fine valleys, and its breaches drain basins too large to be lakes
  through the ranges around them. In three windows of 12 by 12 cells in the mountains of seed 42, the lowest way across
  a river cell, from the side where its river enters to the side where it leaves, rises a median 220 m above the water,
  and more than 250 m in nearly half of them. One breach there runs 60 km through a range, as much as 650 m below the
  ground it cuts. Such a river runs through the basins in its way below their floors, in the gorge it cut, so they stay
  dry, and a basin beside a river, within its reach but not crossed by it, stays dry too. Many of the widest basins stay
  dry this way: in three windows of 12 by 12 tiles of seed 42, the blocks of three by three tiles hold nearly all the
  water of the basins that only they see in one, two thirds in another, and under a tenth in the third, where rivers run
  in the tiles of all of them. Two of its rivers run 20 and 73 m below the floors of the basins they cross, and a third
  passes beside its basin, lower. Lakes cover 4 to 12% of the land there, up to 3 points more than with blocks of two by
  two tiles alone. A basin that reaches past the edge of a block of three by three tiles, 23 km, stays dry unless it
  lies in a lake of the coarse map, and a river crosses it as it crosses any other ground. Where a basin that stays dry
  for one of these reasons lies in a valley the ice drowned, its floor below the sea, the sea's water stays in it at sea
  level, as a fjord. A depression of the coarse map larger than 64 cells, or wider than 16 cells (123 km), is breached
  rather than filled, so the largest lakes of the real world, like the Caspian or Lake Superior, stay dry: a lake waits
  for the terrain of all its tiles. Within 256 cells of the origin of seed 42, at least six depressions that would hold
  a lake are larger than 64 cells, one of them 153 cells, which would take more than a minute on the GPU and some 13
  minutes on the CPU; within 128 cells, 31 of the 570 lakes are larger than 16 cells. Filling such a depression only as
  far as a lake of 16 cells, and cutting its way out through the rest, was tried and dropped: in the mountains of seed
  42 the way out ran as a canyon through the rim and the ranges past it, rivers beside the lake ran up to 300 m above
  the ground, and where the lake's level met the spill of a lake beside it the two drained into each other, a loop the
  discharge never leaves. In three other windows of 12 by 12 tiles it changed nothing. The hollows of a great lake's
  tiles that do not hold water at its level stay dry, and so do those whose water spills past the padding of its tiles.
  The coarse map cannot tell a fjord open to the sea from a valley the ice drowned behind a step: a cell whose 5th
  percentile lies below the sea ends its rivers either way. Where its tile has sea only by a side, they cross into the
  sea beyond (see Paths), but where neither its tile nor the tiles beside it have sea within the reach of their rivers,
  only such a trough, the river ends in the trough's lake, which has no way out. Of the 26 cells that end rivers in a
  window of 40 by 80 tiles of seed 42 at (−50, 0), four did so before their rivers crossed into the sea beyond, and one,
  with 170 m³/s, still does.

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
  coastal plains of the smaller worlds, far inland. Low, wet, flat land past the beach is still a swamp, or a bog.
- **The land's slope.** The model's coasts are steps. It learnt them from maps whose sea floor was sampled far more
  coarsely than their land, so where hills or mountains meet the sea the ground climbs a hundred meters within a pixel
  or two of the coastline, and with the heights exaggerated it stood in the world as a wall: on the fjord coast of one
  spawn at Ultra, the ground 60 blocks inland stood a median 77 blocks above the sea, and 306 on a tenth of the coast.
  Real coasts mostly ease down to the water, over a beach, a strip of lowland or the foot of a slope. So `CoastSlope`
  holds the land within the coast's reach (360 m) under an envelope that climbs from the waterline at the coast's
  slope, from 0.3 to 0.6 m per meter (17° to 31°, which the heights make 25° to 43° at Ultra) by noise 2.5 km across,
  and bends up by 360 m more over the reach, so that by the reach any ground gets through: the first slope above the
  water is gentle, the rise the coast took is pushed inland, and a mountain still stands behind. The envelope is a
  soft minimum, `t / (1 + t⁴)^¼` of its height for ground `t` times as high, which leaves ground well under it alone,
  and the easing fades out over the last 30% of the reach. In the fjords, where the ice cut the land at sea level by
  40 m or more, the envelope steepens to 0.7 m per meter (35°, 47° at Ultra): their sides stay steep, the rock
  showing where the ledges and gullies cut them, but are no longer sheer for hundreds of blocks. The envelope never
  falls under 2 m, so a steep shore keeps a bank at the waterline rather than a flat flush with the water.
  `TerrainField` eases the pixels of each tile as it reads them, before anything else sees them, so the rivers and
  lakes, the biomes, the levels of detail, the cliffs and the ground in blocks all read the eased land: a lake that
  stands above the sea in a valley the ice drowned, whose coastline runs under its water, keeps its shores, because
  its basin is a basin of the eased land. For that, every pixel of the padded tile needs its distance to the
  coastline, so a tile samples 13 pixels past its margin (see [`TerrainField`](#terrainfield)). Everything is in real
  meters, so the coasts have the same shape at every size and are exaggerated as the rest of the land is.
- **Sea cliffs.** On stretches of coast here and there, `TerrainSurface` gathers the first of the rise of the eased
  ground into a sea cliff. Across a strip 60 m wide (at least 6 blocks: 6 at Vanilla and Medium, 7.5 at Large, 15 at
  Grand, 30 at Extreme and 60 at Ultra), the ground becomes a shelf a block above the water, then a face up to the
  cliff's crest, then a bench at the crest until the eased ground climbs past it, or a slope at half the face's pace
  where the ground stands higher. The face takes at most 6 to 16 m of the rise, by noise 1.5 km across (9 to 25 blocks
  at Ultra, at least 4 to 8 blocks in the smaller sizes); a lower coast's whole rise. Its crest rises and falls along
  the coast with noise 400 m across, down to 40% of that. A face is never sheer: it climbs 1.2 to 3 blocks a block on
  average (50° to 72°), by noise 300 m across, a quarter steeper halfway up, and half as steep at its rounded foot and
  crest; it runs across at most 35% of the strip, so the narrow strips of the smaller sizes keep it steeper, up to 3.8
  blocks a block on average. Its foot wanders across the strip, from the waterline to halfway inland, with noise 3
  strips long, and in and out by 8% of the strip round buttresses and coves 0.3 strips across. The noises are read at
  the coastline, down the coast's normal, so a cliff's shape changes along the coast and never across it. The cliff
  fades into the ground's own details over the last 30% of the strip. A cliff needs:
  - land at least 8 m above the sea at the end of the strip, and 20 m for a full cliff (1.5 and 3 blocks at least);
  - a straight coast, not a spit or a narrow inlet, where the gradient of the distance shrinks;
  - a stretch of coast where noise 1.5 km across allows cliffs: a tenth of the high coast has some cliff, and one
    part in 30 the whole face. They fade out over a few hundred meters between stretches.

  Before, the noise allowed cliffs on most of the coast, whole on a fifth, wherever the land stood 4 m high at the end
  of the strip, and each took the whole rise of the ground within the strip into one face, which at mountain coasts and
  in the fjords stood 100 to 470 blocks tall. Then the faces were sheer, 8 to 30 m (up to 46 blocks at Ultra), on a
  fifth of the high coast and whole on a twentieth: seen from the beach, a wall a kilometer long. Across 30 km of one
  world's coast, the tallest step from one column to the next was 26 blocks or more on one transect in a hundred; now it
  is 7, and the tallest anywhere 9. The faces are too steep for the density's interpolation, so it takes the ground of
  the cliff columns, and of the sea within 5 blocks of them, block by block (`CoastFunction`): `coast_fill` is solid
  from the lowest corner of the column's 4-block cell up to the exact ground, and `coast_carve` is air above it. So the
  face keeps its shape, and the rivers, which come after, still cut through it. The fill starts that low because deep in
  the rock a corner on the cliff is only as solid as its caves let it be, a fraction of how empty a corner out at sea
  is: between them, the face would be undercut from the sea floor up to the interpolated ground. Only the cells across a
  face reach down to the sea floor, so the caves under the cliff stay. The top of a cliff is too high for the beach band
  and keeps the biome inland; the shelf at its foot is beach. Like other cliffs, the face shows the rock (see [the
  ground](#the-ground)).
- **The sea floor.** The terrain's seas are flats near the shore: half of the sea 120 m from land is less than 0.3 m
  deep, and 480 m from land less than 3 m. A real sea floor deepens along a beach's equilibrium profile, `0.1 d^(2/3)`
  meters at `d` meters out (Dean's profile for fine sand): 2 m deep 100 m out and 5 m deep 360 m out. So the floor
  lies at least that deep out to `COAST_REACH`, and at least 5 m deep beyond it. Only Ultra and Extreme show it: 5
  blocks deep 360 blocks out at Ultra, under 2 at Extreme. In the smaller sizes the profile stays within the block of
  water the shallowest sea already has.
- **Islets.** Off rocky coasts lie islets, the skerries of a drowned rocky shore. `TerrainSurface` puts them on a
  jittered grid 70 m apart, past at least 20 m of open water and within 240 m beyond it, off coasts whose land stands
  8 to 20 m or more above the sea at the end of the strip, on the stretches of coast that noise 4 km across favours
  and in the clusters that noise 350 m across gathers along them: at most 55% of the places, in the thick of a cluster
  off the rockiest coast, and about a twentieth over all such coasts. Hashes alone rule out most places before the
  terrain is read, so the open sea, which asks about every column, costs a few hashes a column. Each islet is a mound
  8 to 30 m in radius, drawn out along the coast up to twice as long as wide, that rises from the sea floor to a
  rounded top 2 to 12 m above the sea, no higher than three fifths of its radius or four fifths of the land behind
  it, its sides steepest halfway up and taking the outer three quarters of the radius, inside an outline that noise
  moves in and out by up to 40% of the radius; islets that meet merge. The density takes them and the 5 blocks
  around them block by block, as it does cliffs. Only an islet's centre needs the distance to the coastline, so the
  smaller worlds keep islets 4 to 8 blocks across, 2 blocks of water off the coast, within the 11 blocks of sea the
  coastline's distance reaches at Vanilla. They took the place of the sea stacks and arches, columns with sheer sides
  from half as high as the cliff behind them to as high, 70 to 340 blocks on the fjord coast above, which stood in
  the water as smooth ovals.
- **Fjords.** In the ice ages, glaciers flowing off cold mountain coasts cut their valleys far below the sea, which
  flooded them when the ice melted: the fjords of Norway, Patagonia, Alaska and New Zealand. The model draws those
  coasts smooth, so `Glaciation` wraps the terrain source and cuts the valleys after the fact. The ice cut each valley's
  floor down to a trough whose floor lies 400 m under the sea at the coast and climbs to sea level 12 coarse pixels (92
  km) inland. The valley floor is the coarse map's 5th percentile, or sea level if that is lower, and counts only as far
  as the coarse pixel is mountainous, rising 150 to 450 m above it, or lies in a broad valley, whose neighbours on two
  opposite sides rise 150 to 450 m above its floor: the lowest ground of a plateau is no glacier's floor. The cut is the
  floor's height above the trough, in full up to 700 m and fading out by 1000 m, so high valleys keep their floors. In
  the mountains, ground up to 100 m above the valley floor is lowered by the full cut, less higher up and none from
  400 m above it. On plains, which the ice spread over rather than cut into, only the floor itself is lowered in full,
  fading out 30 m above it, and in between as far as the coarse pixel is mountainous: the coastal lowland and the plains
  at the foot of the mountains keep their ground, and of a broad valley only the flat floor drowns. Under the sea the
  lowering fades out at three times the cut down. It keeps every height in order, so it makes no hollow for the rivers
  to fill. The valleys below the trough flood up to about 20 km inland, the slopes above them steepen into walls, and
  the rivers reach the sea at the heads of the fjords. How far the ice reached a place, from 0 to 1, is the product of:
  - mountains, from the coarse map: within a coarse pixel of one that rises 150 to 450 m above its 5th percentile or
    the sea;
  - the sea, within 8 coarse pixels (61 km), fading out at 11 (84 km);
  - the cold: an annual mean at sea level below 8 °C, fading out at 12 °C. The samples, the coarse map and the long
    fjords all reduce the temperature to sea level by the lapse rate regressed over the land around, so they find the
    same coasts cold.

  The first two are interpolated between the centres of the coarse pixels, with the valley floor and the cut, so they
  are smooth. Lowered ground is warmer by its lapse rate. The coarse map lowers each pixel's mean and valley floor by
  the mean of what the samples do at 4 × 4 points across it, so a valley the ice cut through a strip of the pixel sinks
  the pixel's floor as far as the samples' (within 30–40 m on average in the tests, where the pixel's centre alone was
  off by 400–500 m in broad and branching valleys), and the rivers, the farthest LODs and the coarse searches see the
  same coasts. Water that was land before the ice is the fjord biome (see [Refined biomes](#refined-biomes)): the
  lowering keeps heights in order, so that is the water no deeper than the ice lowered ground at sea level, where it
  lowered it by at least 120 m. On two cold coasts of seed 42, each 92 km square, the ice drowned 486 and 118 km² of
  land, as deep as 450 m. Half the drowned water lies within a kilometre of the old coast, and the longest arms run 20
  and 7 km from it by water.
- **Long fjords.** Those valleys drown only as far as the coarse map's floors reach under the trough, some 20 km
  inland. The longest real fjords, which the great outlet glaciers cut along a coast's main valleys, run 100 km and
  more inland, over a shallow sill at the mouth and a basin up to 1,300 m deep behind it, between walls a kilometre
  high. `Fjords` traces such troughs on the coarse map and `Glaciation` cuts them, each as a whole, so their water
  always reaches the sea:
  - A mouth is a coastal coarse pixel, fully cold, mountainous itself or straight inland, whose valley floor, give or
    take 400 m of noise, is the lowest within 7 coarse pixels (54 km): mouths lie at least that far apart.
  - From there the fjord walks inland to the neighbouring pixel with the lowest floor, with a cost for turning. Each
    step takes it farther from the mouth and, within 8 pixels of the sea, from the sea, so it heads inland rather than
    along the coast. How far its ice reached is drawn for each mouth, from 3 to 16 coarse pixels (23 to 123 km) in a
    straight line, most of them short. The walk ends where no neighbour is still mountainous or 400 m high, cold, away
    from the sea and with its floor under 2,500 m.
  - The path, from two pixels out at sea through the walk to a pixel and a half past the head, is jittered and
    smoothed. It does not meander: ice cuts straight reaches.
  - The trough's floor lies 100 m under the sea at the sill, sinks within 11 km to the basin, 200 m deep and 35 m
    deeper per coarse pixel of the fjord's length, at most 1,200 m, and rises over the last third to 40 m under the sea
    at the head. Beyond it the valley climbs 400 m per coarse pixel. The floor is 2.4 km wide at the mouth and 800 m at
    the head. Between sill and head, the width varies by up to 45% and the depth by up to 20%, into narrows and
    basins. The walls rise from the floor in a U, 750 m within a kilometre of it and ever steeper. Ground sinks into the
    trough where it is lower, with a crease smoothed over 40 m. Most side valleys are not cut: they hang above the
    trough, as real ones do.
  - A smooth U makes a shore of round bays, one after the other. Along each shore the walls stand out and back by up
    to 800 m, into spurs and coves from 3 km long down to a few hundred meters: folded fractal noise, so the spurs end
    in points and the coves are round. The noise runs along the shore, not across it, so the walls still climb all
    the way from the water, with no pits or ponds in them. It grows from nothing at the edge of the floor to its full
    size a kilometre out, faster than the wall can turn back, so the floor stays clear and the water runs through to
    the head.
  - Arms branch off the trunk up its side valleys, where the glaciers that fed the great one cut troughs of their
    own, as the Lustrafjord, the Aurlandsfjord and the Fjærlandsfjord branch off the Sognefjord. Each point of the
    trunk between the mouth and the head may grow one, by an even chance, at least two steps after the last, up to six
    arms. The first step leaves the trunk at 37° to 90° from its way inland, never back towards the sea. From there
    the arm walks like the trunk, to the neighbour with the lowest floor farther from where it branched, never beside
    the trunk, an earlier arm or the sea, while its floor climbs at most 300 m a step above the trunk's where it
    branched: the ice filled the side valleys about as low as the main one. Its reach is drawn like the trunk's, from
    2 coarse pixels (15 km) up to half the trunk's steps and at most 6 (46 km). Its trough starts on the trunk's
    floor, 1.4 km wide, and sinks or rises from there, within 11 km as the trunk's does from its sill, to a basin as
    deep as its own length makes it, before it climbs to its head like the trunk's.
  - The ice cut there is the depth of the trough's floor and 40 m more, on its walls too, so the fjord biome follows
    the water to the head. In the coarse map, the pixels a fjord crosses keep 80% of their height above its floor and
    their 5th percentile sinks to it, so the rivers, the farthest LODs and the coarse searches see it.

  Fjords are traced in blocks of 16 coarse pixels, each from the coarse map around it, so neighbouring blocks agree to
  the bit. The trough is worked out every 4 pixels, on a lattice fixed in the world, and interpolated: about 1 ms per
  tile it reaches. Within 400 coarse pixels of the origin of seed 42, a square of 6,100 km, there are 280 fjords: half
  are longer than 47 km, a tenth longer than 121 km, and the longest runs 189 km; 39 are longer than 100 km. 152 of
  them branch, into 297 arms: half longer than 23 km, a tenth longer than 40 km, the longest 71 km. On two cold coasts
  of seed 42, 138 and 123 km square, the fjords and valleys drowned 1,492 and 939 km² of land joined to the sea, where
  without the arms they drowned 1,436 and 674. Half of that water lies more than 11 and 75 km from the old coast by
  water, and the farthest 152 and 169 km.
- **Limits.** Distant Horizons' LODs show the eased coasts, the cliffs, the sea floor and the islets as the chunks
  do, except the farthest, which read the coarse map: it has no coastline, so no beaches, easing, cliffs, profile or
  islets there. The profile has no bars or troughs. The islets take the biome of the sea around them, so they have no
  beaches of their own. The easing reads the distance to the coastline, which reaches 360 m: a mountain that climbs
  faster than the envelope lets through still stands steep beyond it, and the rise it took is pushed inland rather
  than spread. The drowned valleys have no sills: their troughs are deepest at the mouth,
  where real ones are shallowest, and climb steadily inland; only the long fjords have sills. Those valleys end where
  they climb over a step or a saddle between coarse pixels. The trough beyond is a basin like any other, which a river
  or the rain fills above the sea (see [Rivers](#rivers)), as the ice left Mjøsa and Hornindalsvatnet behind their
  sills. On the coasts of 138 and 123 km square above, 62 and 38 km² of drowned ground are cut off from the sea, besides
  what drains past the edges of the squares, and lakes stand above 48 and 8 km² of it, the largest up to 551 m above the
  sea over ground down to 339 m below it. The rest keeps the sea's level and counts as fjord: 12 km² on the first coast
  in basins of a block that rivers drain (see the limits of [Rivers](#rivers)), and 30 km² on the second in a basin
  wider than a block. Under the lakes above the sea the ground still takes the shape of a coast at sea level, its
  shelves, cliffs and islets under the water. The long fjords follow the coarse map's valleys, 7.7 km to a pixel, not
  the fine terrain's: they cut straight through the spurs and saddles between. Arms branch only off the trunks, never
  off each other, so a fjord system is a trunk and its side valleys, without the further branches of the largest real
  ones.

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

The slopes are those the player sees, so the size matters. Every size raises the land more than it widens it, from 1.5
times at Ultra to 3 times at Vanilla, so the smaller sizes have steeper flanks and more cliffs; above the knee the
height is squeezed, and the highest ground is gentler. At Ultra the slopes are the real ones over 30 m, steepened by
half: most of its cliffs are the [ledges](#ledges) and the sides of the [gullies](#gullies), and they keep to the
mountains (see `ErosionSurveyTest` under [Tests](#tests)).

`Geology` is a pure function of the geology seed, the scale and the column's terrain. It holds no Minecraft classes and
is tested on its own. As in vanilla, most of the ground is stone above y = 0 and deepslate below it; the other rocks
come in beds, bands and bodies in them. From the top down:

| Layer | Where | Rocks |
|---|---|---|
| Sedimentary cover | From the surface down to a depth set by the basin: up to about 2 km of rock under lowlands and shelves, but never more than about 210 blocks below the sea; a thin skin on rugged uplands | Beds 1–8 blocks thick that follow the height, not the surface, so valley walls cut through them and their hard beds stand out as [ledges](#ledges). Most of the beds, three in five, are stone; the basin decides the others: limestone, shale and chalk where there was a sea; shale with a little limestone and sandstone on land; coal seams in swampy basins; limestone, shale, rock salt and a little red sandstone where it is hot and dry. Only in deserts (Köppen's: less rain in mm than 10 times the mean temperature in °C, plus 70) are most beds sandstone and red sandstone |
| Fold belts | Rugged land over thick sediment | The beds fold steeply; deep shale turns to slate, then schist, and limestone to marble |
| Basement | Below the cover, down to about y = 4 | Stone, with its region's rock in it: granite plutons, bands of gneiss or schist, or flows of andesite with tuff beds between them; and lenses of diorite, granite, andesite or marble |
| Lower crust | From about y = 4 | Deepslate, then a few blocks of gabbro |
| Mantle | Above the magma layer | A few blocks of peridotite, pierced by rare kimberlite pipes; a quarter of the pipes reach the surface |
| Magma layer | The last blocks above the bedrock | Magma blocks, blackstone and basalt |

The deep layers share the room between y = 0 and the bottom of the world, so they are thicker in the tall worlds. In
vanilla's height the gabbro is about 5 blocks thick and the peridotite 3, leaving some 50 blocks of deepslate.
Thicknesses in meters follow the world's vertical scale down to the sea, so mountains keep the full height of their
strata. Below the sea, they use at least 10 m per block. A lowland's cover therefore ends within about 210 blocks of
the sea at every size: without that, Ultra's 1.7 km of sediment would run past the bottom of the world and
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
calcrete (calcite) under the subsoil, where the rain stops sinking in. The depths are in blocks, the same at every
size. Sand and gravel never hang over open space: there they stay sandstone and rock.

Some biomes lay a soil of their own on top, to vanilla's depth: peat under the moors, the tundra, the páramo and the
bogs, whose pools it lines too, terra rossa under the maquis, loess under the steppes and laterite under the jungles, the
monsoon and laurel forests, the savannas and the cloud forest. Peat, terra rossa and laterite also lie bare on top in
patches. The profile goes on under them. Vanilla's plain dirt stays only as topsoil: below it lies the subsoil. Only the
column's top run of ground gets a profile, so the floors of caves and the ground under rivers keep what the
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

| Kind | Depth | Size (Vanilla) | How often |
|---|---|---|---|
| Hollow | In the basement, centred between y = 6 and y = 36 (117 in the tall worlds) | 14–30 blocks in radius, 10–22 high | One in seven 112-block cells |
| Deep Hollow | In the deepslate, from halfway down to the lava level up to y = −8 | 20–42 in radius, 14–28 high | One in seven 144-block cells |
| Grand Hollow | 40–60 % of the way up from the lava level to y = 0 | 70–160 in radius, 22–30 high | A tenth of 448-block cells |
| Ancient Grand Hollow | 30–45 % of the way up | 150–320 in radius, 26–38 high | One in fourteen 896-block cells |
| Deep Grand Hollow | Just above the magma layer, its floor under the lava level | 100–230 in radius, 18–26 high | One in twelve 640-block cells |
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
  Ancient ones, through whatever ceiling the cave's biome lays, except in the glowworm grottoes, which their
  glowworms light.
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
| Sulfur caves | Seven in ten Grand Hollows and half the Ancient ones in sulfurous ground (see [Refined biomes](#refined-biomes)), whatever the climate, like Frasassi's | Sulfur spikes and thickets of them on floors and ceilings, pools ringed with sulfur | Bands of sulfur and cinnabar through all the rock |
| Lush caves | Seven in ten Grand Hollows under the wet tropics (at least 20 °C and 1500 mm of rain) | Moss on floors and ceilings, with azaleas, clay pools, cave vines, spore blossoms | The lush caves' own |
| **Glowworm grotto** | Seven in ten Grand Hollows under mild, wet land (4–20 °C, at least 1000 mm), like the Waitomo caves | Glowworm silk hanging in curtains from the ceiling, pools in clay, stalactites and stalagmites. No glowstone: the silk gives the only light | Patches of clay |
| **Selenite caverns** | Three in four Ancient Grand Hollows under dry land (aridity below 1), one in five under wet land. Like the Cave of the Crystals at Naica | Beams of selenite, from the floors and the ceilings, that cross the cavern at every angle. No dripstone, nothing alive | Calcite |
| **Mushroom forest** | More than half the Ancient Grand Hollows under wet land: the forest that Verne set by the Lidenbrock Sea | Huge brown and red mushrooms, patches of small ones, glow lichen, spores drifting in the air | Mycelium over dirt |
| **Salt caverns** | Seven in ten Grand Hollows, and of the Ancient ones the selenite leaves, under the beds of rock salt of hot, dry basins (`Geology.evaporite`, see [The ground](#the-ground)). Salt flows under the weight of the rock and rises in domes, as under Mount Sodom, where the Malham cave runs 10 km through it | Cubes of halite grown into one another on floors and ceilings, straws of salt hanging from the ceiling, pools of brine ringed with salt. No dripstone | Salt crust over rock salt, rock salt overhead |
| **Ice caves** | Seven in ten Grand Hollows, and of the Ancient ones the selenite and the mushrooms leave, under land below 0 °C, like the Eisriesenwelt and Scărișoara | Spires of ice from the floors and the ceilings, icicles, a lake frozen solid. No dripstone | Packed ice with drifts of snow, blue ice overhead |
| Dripstone caves | Every other Grand and Ancient Grand Hollow | Stalactites and stalagmites on every floor and ceiling | The dripstone caves' own |

The Deep Grand Hollows keep vanilla's biomes (see [Refined biomes](#refined-biomes)).

**Placement.** Vanilla finds the floor of a cave by scanning a dozen blocks up or down from a random height. That
finds the floors of its own caves, but nearly never those of a cavern a hundred blocks tall. The placement
`diffusicraft:hollow_surface` (`HollowSurfacePlacement`) knows from the model where a column's Grand Hollows lie, scans
just that band, and moves the position to one of its floors or ceilings at random: the floor itself, a ledge or a
pillar's top. It drops columns where no Grand Hollow opens, and places nothing outside a Diffusicraft world. The
caves' features use it. Fabric's biome API also adds copies of vanilla's moss, clay, cave vines, spore blossoms,
dripstone, sulfur spikes and sulfur pools, placed this way, to vanilla's lush, dripstone and sulfur caves, so that
they reach the floors of the Grand Hollows too. Like every Diffusicraft feature they run after vanilla's.

**Blocks and features.**

- **Glowworm silk** hangs from the ceiling in pale threads up to six blocks long, beaded with droplets that glow
  blue at full brightness. It gives light 4 and breaks at a touch.
- **Selenite** grows in beams (`diffusicraft:selenite_beam`, `SeleniteBeamFeature`) up to 22 blocks long and one to
  three blocks thick. Each beam is rooted two blocks deep in the rock and tapers to a point. Its blocks lie along
  whichever axis the beam runs closest to. A beam fills only open space and water beyond its root, and reaches at most
  15 blocks sideways, so it stays in the chunks a feature may write to. Selenite is translucent, like ice, and
  needs a pickaxe.
- **Huge mushrooms** are vanilla's, from the mushroom fields.
- **Halite** grows in cubes of rock salt (`diffusicraft:halite`, `HaliteFeature`): one three to seven blocks on a
  side, and two to six smaller ones within five blocks of it, each on the floor or under the ceiling it finds there.
  Each is sunk up to half its size into the rock and fills the open space and the rock, so the cubes grow out of the
  ground and into one another, as in the Crystal Grottoes of Wieliczka, but larger.
- **Spires** (`diffusicraft:spire`, `SpireFeature`) rise from a floor or hang from a ceiling, lean a few degrees at
  most, and taper from a base a fraction of their length across to a point a block wide. Every layer keeps the block
  its axis runs through, so a thin one never breaks. A spire fills only open space and water and stops at whatever
  it meets, so one that crosses a low cavern becomes a pillar. The ice caves grow spires of packed ice up to 24
  blocks tall, like the ice stalagmites of the Eisriesenwelt, and the salt caverns hang straws of salt crust up to 9
  blocks long, as the brine leaves them under Mount Sodom. Vanilla's ice spikes cannot serve: below y = 50 their
  pillars never reach the ground.
- **Icicles** of packed ice tipped with ice hang from the ice caves' ceilings (vanilla's `block_column`).
- **Brine pools** are vanilla's lakes (`minecraft:lake`) of pale green water in a rim of salt crust.

**Lakes.** Under wet land (aridity at least 1, and a mean temperature of at least 0 °C) the water that soaks down fills
the basin of a Grand or Ancient Grand Hollow to 0.92 of the floor's depth under the centre: just under the plain floor
at its rim, so the rock holds it. At Vanilla the lakes are 7 to 11 blocks deep in the middle, deeper in the tall worlds.
Under dry or frozen land the basins stay dry, and so do those of the selenite caverns, drained like the Cave of the
Crystals, and the salt caverns. In the ice caves, however dry the land, the lake is ice (`HollowBiomes.frozen`), as the
block of ice in Scărișoara has lain for thousands of years. `HollowLakes` fills them: a mixin at the end of
`NoiseChunk`'s constructor puts it in front of the chunk's aquifer, which the noise fill and the carvers both ask what
fills open space, so a carver's tunnel through a lake bed floods too. It finds the lake with `Hollows.lakeAt` and asks
`HollowBiomes.holdsLake` once per cavern. The cavern over a lake drains into it, so `HollowLakes` keeps the aquifer out
of it (`Hollows.overLake`): the aquifer's pools would stand on the lake, a block or more over its brim. Where another
cave opens into the cavern, its water may spill in. Like vanilla's lakes, the water is still: it schedules no fluid
updates. The beds are clay in the glowworm grottoes and lush caves, sand by the mushroom forest's lake, and gravel among
the dripstone. Every floor under water in those biomes above the lava level gets them, so vanilla's aquifer pools there
do too.

The ground is the material rule `diffusicraft:biome_caves`, the last of the rules `StrataRule` runs over its rock.
The erosion that bares rock on steep ground and lays scree under cliffs keeps to the land's surface, so the soils of
cave floors stay.

### Finding the Hollows

`/diffusicraft find <sight>` gives the nearest Hollow of a kind, Grand Hollow of a cave biome, or lake in a Grand
Hollow's basin; `/diffusicraft find` alone lists the sights. The command is `map.FindCommand` and the search is
`worldgen.Sights`. The model places every chamber from the seed alone, so the search loads no chunks. It walks the
cells of that kind of hollow out from where it is run, in squares twice as wide each time, nearest first. For each
Grand and Ancient Grand Hollow it asks `HollowBiomes` for the biome and the lake from the coarse climate over it, as
the world does. This part takes a few milliseconds.

The place it gives is then checked against the terrain itself. The model's floor is not quite the world's: the world
interpolates the density between the corners of 4 by 8 by 4 cells, vanilla's caves cut into it and its aquifers flood
it. The search tries columns along the cavern's two axes, first three quarters of the way out, then farther out and then
in, wherever the model leaves a floor. For each it asks the chunk generator for the column's blocks as the noise of its
chunk lays them out (`getBaseColumn`), under the model's roof there: the rock over the lower edges of a dome holds
vanilla's caves, which are not the cavern. It takes the first floor with 4 blocks of open air over it, without water or
lava, and gives the block one over that floor's top. Where no column has one, the place is just over the first water or
lava with as much air over it, and the command says so. A cavern with neither is passed over for the next. The features
come later, so a place can still land in a pool that they dig in the floor, or by a giant mushroom. The check needs that
terrain, and the rivers around it, at full detail. Where no one has been, the models take a while to compute it: two to
five minutes for each of `FindGameTest`'s far places, on four CPU threads at the lowest priority. So the command
searches on a background thread and answers when it is done.

| Looked for | Within |
|---|---|
| A kind of hollow | 65,536 blocks |
| A cave biome or a lake | Two of the coarse map's blocks: 65,536 blocks at 1:1 and 1:2, 61,440 at 1:4, 30,720 at 1:8, 15,360 at 1:16 and 7,680 at 1:32 |

The biomes and lakes need the coarse climate over each cavern, and the coarse map keeps only 64 of its blocks in
memory, so their search stops sooner.

The answer gives the coordinates, which suggest a `/tp` when clicked as `/locate`'s do, and the distance. While the
[relief map](MAP.md) is on, it also gives a link to the place on the map. The same players can run it as
`/diffusicraft map`: operators, and the owner of a singleplayer world even without cheats, since it changes nothing.
`FindGameTest` looks for every sight from the spawn, generates the chunks at each place found and checks that a player
can stand there, in the biome looked for.

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

Plates lie as far apart as on Earth, so most coasts and most land inland lie hundreds of kilometres from a range. A
coast, or land inland off the plains, is better with mountains in view. For these, `Geography` tries up to 16,384
places and keeps the first with ground at least 1,500 m high within 30 km (before `Uplift` raises it), or else the
first that suits. Over 300 seeds, this puts 81% of coastal spawns within 40 km of such ground, up from 8%. The plains
stay flat but for the knolls and ridges of [Roughness](#roughness). The search takes up to 0.2 s once per world.

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
- the height above the low ground is that gap, so the coarse map has no salt flats;
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
| `worldgen/` | `WorldScale`, `TerrainSource`, `Glaciation` (the fjords), `Fjords` (the long fjords), `TerrainField`, `SpawnSearch` and `WorldSpawn` (the world spawn), `Coastline` (the distance to the coast), `CoastSlope` (the land eased down to the sea), `Uplift` (taller mountains), `TerrainSurface` (the ground, with its roughness, ledges, sea cliffs, islets and gullies), `ClimateMapping`, `CoarseClimate`, `TerrainFields` (one field per seed, model lifetime), `PipelineSource`, `CarverRoof`, `Sights` (what `/diffusicraft find` finds), `DiffusicraftWorldgen` (registration) |
| `mixin/`, `client/mixin/` | The default world type, the world spawn and the respawn in a Hollow, the check for missing models on Create, the coarse stronghold search, the wider spreads of the villages and pillager outposts, the lava level, the carver roof, the vanilla features Diffusicraft worlds leave out and the falls they add after vanilla's water springs, the water springs they leave off the cliffs, the world attached to climate samplers, the lakes of the Hollows in front of each noise chunk's aquifer, the lapse rate, the material rules run outside chunks and the settings' own material rule, which tells a Diffusicraft world when TerraBlender wraps it |
| `worldgen/density/` | The density function types: `SurfaceFunction`, `ClimateFunction`, `HollowsFunction`, `RiverFunction`, `CoastFunction` |
| `worldgen/river/` | `Rivers` (what a river or lake does to a column), `RiverNetwork` (the coarse drainage), `CellRivers` (the paths inside a tile), `RiverCourses` (the water of those paths in blocks), `CellBasins` (the closed basins of a tile), `CellLakes` (the lakes inside a tile), `BlockLakes` (the lakes across tiles), `GreatLake` (the lakes of the coarse map), `CellFlows`, `RiverColumn` |
| `worldgen/biome/` | `ClimateBiomeSource` (`diffusicraft:climate`), `BiomeRefiner` (the rules), `SubBiomes` (the patches of siblings), `HollowBiomes` (the biomes of the Grand Hollows), `HollowLakes` (their lakes), `BiomeClimate`, `BiomeWorld`, `HeightTemperature` |
| `worldgen/feature/` | `HollowSurfacePlacement` (`diffusicraft:hollow_surface`), which finds the floors and ceilings of the Grand Hollows, `SeleniteBeamFeature` (`diffusicraft:selenite_beam`), `GiantTreeFeature` and `GiantTreeStructure` (both `diffusicraft:giant_tree`), `GiantTreePiece` (a giant written a chunk at a time) and `GiantTree` (the giant trees' shapes), `RiverFallFeature` (`diffusicraft:river_falls`) and `RiverFalls` (the curtains and pools of the rivers' falls), `Springs` (vanilla's water springs left off the cliffs), `LianaFeature` (`diffusicraft:lianas`), `UndergrowthFeature` (`diffusicraft:undergrowth`) and `Thickets` (where it grows thick), `ErraticFeature` (`diffusicraft:erratic`), `RockfallFeature` (`diffusicraft:rockfall`), `Boulder` (their shapes, and where a cliff's foot is) and `Boulders` (setting them down), and `DiffusicraftWorldFilter` (`diffusicraft:diffusicraft_world`), which keeps a feature to Diffusicraft worlds |
| `worldgen/geology/` | `Geology` (the rock and ore model), `Rock`, `Ore`, `Soils` (the soil model), `Hollows` (the cavern model), `Erosion` (cliffs and scree), `Roughness` (knolls and scarps), `Gullies`, `GeoNoise` |
| `worldgen/material/` | `StrataRule`, the material rule type that applies the geology and lights the Hollows; `ModOres`, the ores of tech mods; `RiverRule`, the rivers' water and beds |
| `worldgen/lod/` | `LodSampler`, `LodColumn`, `TreeCover` and `GreatTrees`: columns of the finished surface and its trees without chunks, for LOD mods; `FarStructures` and `StructureSketch`: the structures on the surface in those columns, placed without chunks; `LodGrid`: those columns cut into voxels, for Voxy; `LodFeed` and `LodPayloads`: grids sent from a server to its players' clients |
| `compat/dh/` | The Distant Horizons generator; loaded only with Distant Horizons. See [COMPATIBILITY.md](COMPATIBILITY.md) |
| `block/` | `DiffusicraftBlocks`, the new rocks, soils, plants and salt crust, glowworm silk and selenite |
| `data/diffusicraft/worldgen/` | Noise settings, material rules, density functions and world presets for each scale |
| `data/diffusicraft/dimension_type/` | The tall overworld dimension types, one for each size but Vanilla |
| `data/diffusicraft/worldgen/{biome,feature,placed_feature,structure,structure_set}/`, `material_rule/biome_surface.json`, `material_rule/biome_caves.json`, biome tags | The new biomes, their trees and plants, their ground and their tags, the giant trees' structures, and the spreads of the villages and pillager outposts |
| `tools/worldgen_data.py` | Writes the noise settings, material rules, density functions, presets and dimension types. Edit it, run `python3 tools/worldgen_data.py`, and commit the output. |
| `tools/biomes.py` | Writes the new biomes, the cave biomes of the Grand Hollows, their features, ground rules and tags, the giant trees' structures and the spreads of the villages and pillager outposts. Edit it, run `python3 tools/biomes.py`, and commit the output. |
| `tools/block_assets.py` | Draws the textures of the rocks, soils, plants, salt crust, glowworm silk and selenite, and writes the block states, models, loot tables, dye recipes, peat's burn time and tags of the blocks. `--preview sheet.png` writes a sheet of the textures for review. |

## Lifetime and storage

- **Loading.** The models load when the first chunk needs terrain, not when the world opens.
- **Unloading.** They are freed when the server stops, which gives the GPU memory back to the game.
- **Disk cache.** Pipeline windows are cached in
  `<world>/diffusicraft/<model revision>/map<Geography.VERSION>/<terrain seed>/`. Reopening a world is fast, and the
  terrain stays the same even if newer models are installed later.

## Tests

- `GeologyTest` checks the layer order, that lowlands are covered in sediment, that stone makes up most of the ground
  above y = 0 and deepslate most below it, that sandstone keeps to the deserts, that a world of 1 m per block keeps
  10 m per block below the sea and its own scale above it, determinism, ore rarity and diamond depth, that the
  basement is harder than its cover and granite harder than schist, that the hardness never jumps, and the sampling
  speed. For the ledges, it checks that the ground climbs steeply through hard beds and gently through soft ones,
  that limestone and sandstone lie only in hard beds and shale, chalk and salt only in soft ones, with the cliffs in
  hard rock or stone and the benches mostly on soft rock or stone at full and at weak strength, that the ledges
  average out, stay within 10 blocks and change smoothly, and that there are none in the basement or in fold belts.
  For the ores of tech mods, it checks that their minerals take only some of the bodies, only in their host rocks and
  only where a mod adds them, and that the bodies stay vanilla's without the mods. `ModOresTest` checks how a block
  is picked for each mineral: one in stone and one in deepslate, the first mod by id, either standing in for the
  other, and never a Nether or End ore.
- `GeologySurveyTest` needs the models. It runs the geology over the same real land at every scale, checks that the
  tall worlds keep about as much basement under the land as the Vanilla size, and draws a cross-section of each to
  `build/geology`. `-Pdiffusicraft.geologyStore=<dir>` keeps the terrain on disk for later runs, in a folder per seed.
- `UpliftTest` checks that the lift grows in from the valley floor and never faster than its gain, that the floors and
  the sea keep their levels and raised ground cools by the lapse rate, that windows agree to the bit, and that the
  coarse map's mean rises while its 5th percentile stays. `RoughnessTest` checks that the sizes below 1:8 have none
  and those below 1:4 no scarps, that the roughness averages out and stays within 30 blocks, that rugged land is
  rougher than plains, that bare ground has small cliffs that a cover of soil lowers, and that it is set by the seed.
- `GulliesTest` checks that only the sizes from 1:4 up are fine enough for gullies, that gentle ground stays smooth,
  that steeper slopes are cut deeper but never past the deepest a gully can go for any rock and climate, that the ground
  changes about three times as much across a slope as down it, that cover rounds the ground off and hard rock is cut
  deeper and sharper, that bare soft rock has the finest rills, and that the ground is continuous in space, rock and
  cover, and set by the seed. `TerrainFieldTest` checks the spline's gradient and that the ground keeps within a few
  blocks of the terrain in every world.
- `ErosionTest` checks that gentle ground keeps its soil, steep ground shows the rock in any direction, and scree
  lies at the foot of a cliff, whichever way it faces, and not on the flat beyond.
- `SoilsTest` checks that the soil follows the rock (sand on sandstone, clay on shale, grus on granite, terra rossa
  on limestone in the warm), that laterite, loess and gley take over from any rock where the climate or the water
  makes them, that only semi-arid land has a calcrete pan, that the horizons come in order down to the rock, that
  warm, wet, flat land weathers deepest, that the weathered rock turns to solid rock downwards, and that bauxite lies
  in patches of the lower subsoil under the flat land of the wet tropics, not on slopes or in drier or cooler land.
- `ErosionSurveyTest` needs the models. At every scale it finds the tile near the origin whose ground rises the most in
  blocks and sorts its land into soil, cliffs and scree. These numbers were measured before the six sizes, with the
  three worlds of the time: Standard (1:15, heights raised 1.5 times up to 1 km), Grand (1:5) and Earth (1:1, heights
  unexaggerated). With seed 42 that tile was 87% soil, 8.3% cliffs and 4.8% scree in the Standard world, 90% soil, 6%
  cliffs and 4.2% scree at Grand, and 93% soil, 4.3% cliffs and 2.5% scree at Earth. The highest tile near the origin,
  which the Standard world squeezed flat, was 10.4% cliffs and 7.5% scree at Grand and 6.5% cliffs and 4.4% scree at
  Earth. Before the ledges, the steepest tiles were 2.6% cliffs in the Standard world, 0.3% at Grand and nearly none
  at Earth.
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
  fjord up the valley ends its rivers instead of being breached to the coast, that the way out of a lake by the coast
  stays above the sea, that a river meets itself across tile borders, that the banks hold the water where it steps down,
  in the river and in the lakes it fills along its valley, that a river down a steep slope runs down it with the
  ground, 2 blocks at most a block, never far above or below the ground and never rising downstream, that the banks of
  a steeper one stand at most 2 blocks over the ground, that a river falls over a ledge of 80 m in its way and runs on in the ground above and below it, that a ridge the coarse map
  misses gets a gorge whose walls rise less than 3 blocks per block, that lakes fill the hollows between the hills and their shores
  hold them, that a dry climate leaves the hollows dry, that every thread gets the same rivers, that a thread asked
  about another chunk while it computes a column keeps that column's chunk, and that the width grows with the discharge.
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
  forest, Ecuador's páramo, the Altiplano and Mauna Kea, central India, Thailand and the Amazon, the Cerrado around
  Brasília and Darwin, Shanghai, Atlanta and Hong Kong, the Pacific Northwest, the Mediterranean and Los Angeles against
  Lilongwe, the Gobi and the wormwood steppe of Kazakhstan, the Mojave, the Sonoran Desert, the Karoo, Mesopotamia,
  Kufra's sand sea against the Tanezrouft and the hamada above Cairo, Dartmoor and the Pennines, the Lüneburg Heath,
  Bonneville and Uyuni, Finland's mires, the West Siberian Plain, the Bog of Allen, the Hudson Bay Lowlands, Yakutsk,
  Verkhoyansk, Oymyakon and the Greater Khingan against the Chuya steppe, southern Finland, Moscow, Stockholm, Ottawa
  and Manchuria's hills against Copenhagen, Berlin, Kirov, the Vosges and the Black Forest, the taiga of Kirov and Fort
  Smith, Astana, Orenburg and Volgograd, Kharkiv, Omsk, Edmonton, Budapest and Harbin against Fairbanks, the Greenland
  ice sheet, Utqiaġvik and the South Shetlands, tropical coasts and the Sognefjord, and Kuujjuarapik, Nain, Murmansk,
  Ungava, Akureyri and Iceland's other coasts against Rovaniemi, and Santa Fe, the hills above Cedar City and of
  Anatolia against the valley below Cedar City, Mongolia's hills and the Sahel's, and the sagebrush of Elko and the
  valley below Cedar City and Patagonia's shrub steppe at Comodoro Rivadavia against the grass of Río Gallegos, Denver
  and Scottsbluff. On slopes, the tree line of the Alps is higher on the sunny side, with forest-tundra just below it,
  and the woods of the Rockies' foothills keep to the shaded side, where it is cool enough for mixed forest; flat ground
  there is open forest-steppe, and junipers grow on the sunny side.
- `SubBiomesTest` checks that every sibling is land the source knows and that no family gives its siblings more than
  60% of its patches, that patches cover about half the land and run 100 to 320 blocks across, that a forest on level
  ground is a fifth to two-fifths other woods with every sibling present, that bogs keep to flat hollows and need cool
  summers, that pines keep to the rises and old spruce to the valleys, that no trees grow in patches beyond the tree
  line, that other biomes and columns outside the patches stay, and that the seed sets the patches. The groves of
  giants keep to level land, and redwoods to mild, wet climates.
- `GiantTreeTest` grows every form of giant tree at its least and most height, at 1:1 and at 1:32, and checks that it
  keeps within its reach and height, as a structure and as a feature, that the same draw grows the same tree, that a
  tree laid chunk by chunk is the whole tree, that all its wood hangs together from the foot, that at least 97% of its
  leaves live on the wood, that redwoods stand bare under their crowns, that ancient oaks spread domes nearly as wide
  as they are tall, open inside, over a bare trunk, that kapoks stand on buttresses under an umbrella half as wide as
  they are tall, and that a 230-block redwood takes milliseconds to grow. `TreeCoverTest` checks
  that the LODs draw a biome of kapoks as tall crowns over a good share of the ground, with their trunks a level of
  detail farther out, and as tall as the kapoks grow in a world of each size. `GreatTreesTest` checks that the LODs
  stand the structures' giants at the feet their chunks draw, with their trunks there and nowhere else, only the
  giant of the biome at the foot, none under water, on steep ground or in chunks the set does not place in, crowns
  wider than a feature's at 1:1 that shrink at 1:32, and leaves that stay put as the ground rises under them.
- `RiverFallsTest` lays out the falls of a river over a step: a curtain from the higher water in the columns below the
  step alone, a pool deepest at its foot and shallower downstream, none above the step, under a step of a block or in a
  lake the river falls into, none deeper than allowed, and a pool across the side of a chunk dug alike on both sides.
- `SpringsTest` leaves out a water spring high in a cliff over dry ground, and keeps one over water, one a few blocks
  up, one in a cave's wall and one with no hole beside it.
- `ThicketsTest` checks that the thickets and the clearings of the undergrowth both cover a good share of the floor,
  that a thicket thins out gradually, and that they follow the world's seed.
- `BoulderTest` checks that a boulder is one piece around its centre and stays within its reach, that the foot of
  a cliff is found beside a face that rises at 45° or more, and none on a gentler slope or under a face too low.
- `ClimateColumnTest` checks that the south face of a ridge is warmer and drier than its north face, by as much as
  its slope calls for, and that its east and west faces are alike. `ClimateMappingTest` checks how much a slope
  faces the sun, and that the shaded side of a mountain is snowy where the sunny side is not.
- `CoastlineTest` checks the distance to straight and round coasts, at sea and on land, that it is continuous across
  tile borders with its gradient, that a coast just outside a tile counts and that saddles follow the mean of their
  corners. `CoastSlopeTest` checks the envelope the land is eased under: ground well under it keeps its height, steep
  ground is held near it, it never falls under its least, the easing fades out by the reach and only ever climbs
  inland, higher ground stays higher, and the fjords keep steeper sides.
  `SeaCliffTest` builds straight coasts: some stretches of a high one get cliffs and fewer whole ones, no face is
  taller than its cap, a full cliff's foot is a shelf a block above the water, the cliff meets the ground at the end
  of its strip, the land eases down to the sea while a gentle coast keeps its shape, a low coast gets no cliffs and no
  islets, the far levels of detail show the cliffs where the chunks have them, islets lie off a rocky coast past open
  water and are mounds rather than columns, and the density takes every cliff and islet column block by block.
- `GlaciationTest` checks that the lowering keeps heights in order and leaves high ground and the deep sea alone, that
  the trough climbs inland and spares valleys too high above it, that a cold mountain coast drowns its valleys under the
  ridges while a mild one keeps them, that gentle valleys drown six tiles inland but not beyond the ice's reach, that
  the flat floor of a broad valley drowns while a plain beside the mountains keeps its ground, that the coarse map is
  lowered alike, its valley floors within 45 m of the samples' 5th percentile on average, and that overlapping samples
  agree to the bit.
- `GeographyTest` checks, on a table of heights shaped like the real one, that a seed always maps the same world, that
  land takes about its real share, that the origin is the kind of place asked for (a coast by the open sea on a
  continent, land far inland, a lone island, land over a Hollow or a high mountain), that most coasts have mountains in
  view and that seeds draw the kinds by their chances, coasts first. `SpawnSearchTest` checks on analytic terrain that a
  coastal spawn is on the beach nearest the origin, that a spawn by a peak is on its summit, that without a mountain or
  at sea the spawn moves to the nearest land, and that land at the origin keeps it.
- `WorldScaleTest`, `ClimateMappingTest` and `TerrainFieldTest` run with `./gradlew build` and need no models.
  `TerrainFieldTest` uses an analytic terrain to check:
  - interpolation;
  - continuity across tile borders;
  - eviction;
  - concurrent tile computation;
  - the coarse column;
  - the hillside's slope, across tile corners too;
  - the variant noise's wavelength, at most 2000 blocks from 1:4 up.
- `WorldGenerationGameTest` is a client game test and needs the models. It checks that Diffusicraft is the default world
  type, creates a world at the Medium size (1:16), which its checks were made for, and checks that a player can stand at
  the world spawn, that the heightmaps count all of Diffusicraft's full blocks, and that the villages and pillager
  outposts take Diffusicraft's wider spreads. It then saves a biome map, a height
  map, in-game views around the spawn and views of the largest river near the spawn if there is one, the steepest cliff,
  the tallest sea cliff, each of Diffusicraft's biomes within 24 km or, for those of other latitudes, along the meridian
  towards the equator, the tallest Grand Hollow, a lava sea nearby and the tallest Grand Hollow of each cave biome
  within 4 km, all at noon, to `build/run/clientGameTest/screenshots`. The top of the cliff must be bare rock, the face
  of the sea cliff sheer above the water, though a cave may open into it there
  and caves and ravines below it, and each cave biome must hold its own blocks: silk, selenite, mushrooms, moss or
  dripstone. Around the spawn no sand or gravel may hang over open space, the rock under the soil may hold no dirt or
  gravel down to the first cave, and on gentle ground outside limestone, chalk and marble the soil must go deeper than
  vanilla's dirt on average. Run with Tech Reborn (`-PdevMods=techreborn`), it checks that the geology places Tech
  Reborn's tin, lead, silver, uranium, rubies, sapphires and bauxite, and not its Nether sphalerite. It generates on the
  CPU unless `-Pdiffusicraft.device` names another device.
- `TreesGameTest`, a client game test that needs no models, plants three of each of Diffusicraft's trees on a
  superflat world, the giants too, each in its own biome for the colours of its leaves, and saves a view of each. The
  giants grow twice, at their full height and as in a Vanilla world, and the ancient oaks and kapoks a chunk at a
  time, as their structures' pieces are written in a world. A tree that never grows on open grass fails it.
- `BiomesGameTest`, a client game test that needs no models, lays out a plot of each of Diffusicraft's surface biomes
  on a superflat world, grows its plants and trees there as world generation would, and saves a view of each. The
  ground stays superflat grass, so the biomes' own ground and the plants that need sand are missing.
- `WaterfallsGameTest`, a client game test that needs the models, creates an Ultra world and checks the falls of its
  rivers from their columns, near the spawn or near another place (`-Pdiffusicraft.riverFallsAt=x,z`; the test world's
  nearest rivers lie 100 km away, and it goes to them): the highest step of their water must stand as a curtain over it,
  and around it and along the steepest reach without a fall of more than 8 blocks the river must keep its water within
  its banks once the chunks have ticked. It saves views of both from the front and the side.
- `BouldersGameTest`, a client game test that needs the models, creates an Ultra world and finds boulders near the
  spawn: erratics on the nearest cold, level land, and rocks at the feet of the steepest land. It logs how many there
  are and how big, and saves a view of the biggest of each area.
