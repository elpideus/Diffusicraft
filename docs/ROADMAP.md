# Roadmap

**The goal.** A fantasy world in the spirit of Skyrim or The Witcher, but grounded in reality. Its mountains, rivers,
climates, forests and rock layers should look and behave like real ones. It is aimed at survival, realism and RPG
modpacks built around Create.

The pieces in place generate the world's shape, climate, rivers, lakes, rock, soils and deep caverns, and a world
generator turns them into blocks. The pieces planned add the details: finer terrain and new biomes.

## Done

- **Terrain Diffusion in Java.** A concurrent, deterministic port of the Terrain Diffusion pipeline: infinite
  elevation and climate at 30 m per pixel, interpolated to blocks with Catmull-Rom splines. See
  [MODELS.md](MODELS.md) and [ARCHITECTURE.md](ARCHITECTURE.md).
- **Inference everywhere.**
  - CPU, bundled.
  - NVIDIA CUDA, a one-click download from official sources, with license acceptance.
  - AMD ROCm/MIGraphX, bring your own build (experimental).

  See [RUNTIMES.md](RUNTIMES.md).
- **Resource manager.**
  - Opens at first launch, and later from Create World > More and Mod Menu.
  - Downloads in the background, then reports with a toast.
  - Detects the hardware and picks a sensible GPU memory cap.
  - Stores everything once per user.
- **World sizes.** Diffusicraft is the default world type on the Create World screen and on dedicated servers. It is
  inspired by real terrain, not a simulation of it, and comes in six sizes, each half the one before, across and up,
  with heights exaggerated the more the smaller the world:

  | Size | Horizontal | Heights exaggerated | Build height |
  |---|---|---|---|
  | **Vanilla** (1:32) | 32 m per block | 3 times, eased above 1.3 km | Vanilla (−64 to 319) |
  | **Medium** (1:16) | 16 m per block | 2.5 times, eased above 4.8 km | −256 to 1279 |
  | **Large** (1:8) | 8 m per block | 2.3 times, eased above 5.6 km | −256 to 2303 |
  | **Grand** (1:4) | 4 m per block | 2 times, eased above 6 km | −256 to 3839 |
  | **Extreme** (1:2) | 2 m per block | 1.7 times, eased above 6.3 km | −256 to 8191 |
  | **Ultra** (1:1), the default | 1 m per block | 1.5 times, eased above 6.5 km | −256 to 16,367 |

  Pick the size with the slider beside the world type. Distant Horizons and Voxy cut the highest ground of Extreme and
  Ultra flat in their far terrain; with either installed, the slider, Create and the chat say so. See
  [WORLDGEN.md](WORLDGEN.md#world-types).
- **A real geography.** The coarse model is conditioned on a map of continents, plates and climate rather than on
  noise. One world holds supercontinents and islands of every size, with smooth coasts in places and archipelagos in
  others. It also has cordilleras, collision belts, island arcs, rifts and hotspot chains, and winds, rain shadows and
  ocean currents. See [WORLDGEN.md](WORLDGEN.md#the-geography).
- **Where the world begins.** Most worlds start on a coast, then inland on a continent, mostly on a plain. A few
  start on a lone island far out at sea, and a rare few on the floor of an Ancient Grand Hollow or near a mountain's
  summit. See [WORLDGEN.md](WORLDGEN.md#the-world-spawn).
- **Biomes from the climate.** The six vanilla biome parameters come from the real temperature, aridity, elevation
  and relief. Snowy peaks appear only where it is cold, and deserts only where it is hot and dry. Slopes facing the
  sun are warmer and drier than those facing away, so the tree line climbs higher on them and, in dry climates, the
  woods keep to the shaded slopes. Biome mods that extend the overworld table get the same parameters; checked in
  game with Biomes O' Plenty and TerraBlender. See [COMPATIBILITY.md](COMPATIBILITY.md#biome-mods).
- **Real-world biomes.** The seasons refine vanilla's picks into tundra, alpine tundra, cloud forest, temperate
  rainforest, Mediterranean maquis, moorland, heath, salt flats, bogs, Siberian larch taiga, forest-tundra, cold desert,
  desert scrub, stony desert, steppe, shrub steppe, forest-steppe, juniper woodland, fjords, monsoon, laurel and mixed
  forests, and the páramo and puna of tropical mountains, with their own trees (krummholz, giant spruces, olives, stone
  pines, cypresses, black spruces, mountain birches, larches, junipers, pinyons, mesquites, teak, live oaks,
  frailejones, queñuas), plants and ground; vanilla's own biomes where its table picks the wrong ones, taiga in its
  boreal forests, savanna on its tropical plains and steppe on its dry frozen ground; the wetlands follow the climate,
  from mangroves to bogs. New plants: heather, cottongrass, lavender, edelweiss and gentian, and salt crust. Soils of
  their own under some biomes: peat, which burns, terra rossa, laterite and loess. Snow follows the climate rather than
  a fixed height, above a real tree line, with no forest on it, and the Grand Hollows are free of sculk. See
  [WORLDGEN.md](WORLDGEN.md#refined-biomes).
- **Real biome sizes.** Biomes come in patches as large as their real counterparts. At 1:1 the main forests
  and taigas average 10 to 20 km across, a few hundred square kilometres at most, and steppe or maquis can cover a
  whole plain. Sun and shade are measured over a whole valley side, vanilla's variants are picked by noise with a
  12 km wavelength, summer rain counts as Köppen counts it, so continental plains are steppe, and meadows keep to the
  subalpine belt instead of lining every valley side. Pale gardens, from vanilla's plateau row, still come in belts a
  few kilometres long on the upper slopes. See [WORLDGEN.md](WORLDGEN.md#biome-parameters).
- **Geology.** Stone above y = 0 and deepslate below, as in vanilla, with sedimentary beds by basin, fold belts and
  basement rocks in them, gabbro, peridotite with kimberlite pipes, and a magma layer above the bedrock. Sandstone
  keeps to the deserts. Ore bodies by rock and depth on top of vanilla's ores. Eleven new rocks. See
  [WORLDGEN.md](WORLDGEN.md#the-ground).
- **Cliffs and scree.** Ground too steep for soil shows the rock beneath, and scree of gravel and broken rock lies
  under the cliffs. See [WORLDGEN.md](WORLDGEN.md#the-ground).
- **Soils.** Soil that follows the rock beneath and the climate: sand on sandstone, clay on shale, grus on granite,
  terra rossa on limestone in the warm, laterite in the tropics, loess on the cool, dry plains, gley under peat and
  calcrete pans in semi-arid land. Profiles of topsoil, subsoil and weathered rock run from about 4 blocks deep in
  the cold to 15 in the wet tropics. See [WORLDGEN.md](WORLDGEN.md#the-ground).
- **Minerals of tech mods.** Where Create, Tech Reborn, Mekanism or other mods add them, the geology's ore bodies
  hold their minerals too, in the rocks they form in: zinc and lead in limestone, tin in granite, nickel and the
  platinum metals in gabbro and peridotite, and bauxite in the laterite of the wet tropics. The ores come from the
  common tags, so any mod that tags them takes part. See [WORLDGEN.md](WORLDGEN.md#the-ground).
- **Gullies.** From 1:4 up the slopes are cut by gullies finer than the model's 30 m pixels, which run downhill and
  branch, deeper on steeper ground; gentle ground stays smooth. The rock and the climate shape them: crags in hard
  rock, badlands in bare soft rock, rounded hills where soil and plants cover soft rock. See
  [WORLDGEN.md](WORLDGEN.md#gullies).
- **Roughness.** From 1:8 up all land undulates in knolls and hollows finer than the model's pixels, sharp-crested on
  bare ground and round under soil, and from 1:4 up small scarps run across some country. See
  [WORLDGEN.md](WORLDGEN.md#roughness).
- **Ledges.** Where a slope cuts through the sedimentary cover, its hard beds of stone, limestone and sandstone stand
  out as cliffs and the soft beds between them recede into benches, as in the Grand Canyon, in every world. See
  [WORLDGEN.md](WORLDGEN.md#ledges).
- **Lakes on rivers.** Rivers fill the basins they cross and flow on out of them over their lowest point. See
  [WORLDGEN.md](WORLDGEN.md#rivers).
- **Lakes across tiles.** Basins that reach past the side of a tile hold lakes up to 23 km across, on which the
  tiles on both sides agree exactly. Rivers fill those they cross. See [WORLDGEN.md](WORLDGEN.md#rivers).
- **Great lakes.** The depressions of the coarse map hold lakes of up to 64 tiles and 123 km across, where the water
  flowing into them outweighs what they evaporate. Their water stands at one level over all their tiles, and the rivers
  cross them at that level and flow on out. See [WORLDGEN.md](WORLDGEN.md#rivers).
- **Caves.** Vanilla's caves, opening a little over a third as much of the ground, most of it in noodle tunnels: rare
  cheese caverns, spaghetti tunnels in networks with solid ground between them, and a sixth of the carved caves.
  See [WORLDGEN.md](WORLDGEN.md#density-and-caves).
- **The Hollows.** Caverns larger the deeper they lie: Hollows, Deep Hollows, Grand Hollows with pillars and lit
  ceilings, Ancient Grand Hollows, Deep Grand Hollows over lava seas, and deep noodle caverns. See
  [WORLDGEN.md](WORLDGEN.md#the-hollows).
- **Life in the Hollows.** Each Grand Hollow is one cave biome, from the climate over it: lush caves under the wet
  tropics, glowworm grottoes hung with glowing silk under mild, wet land, selenite caverns crossed by beams of crystal
  under dry land, forests of giant mushrooms, and dripstone caves elsewhere. Their plants and crystals reach the floors
  and ceilings of caverns a hundred blocks tall. Under wet land, a lake fills the basin in the middle of the floor.
  See [WORLDGEN.md](WORLDGEN.md#life-in-the-hollows).
- **Rivers and lakes.** Drainage of the whole world from its rain and terrain, with discharge, meanders,
  tributaries, widths that grow with the discharge, banks, beds and river biomes. Lakes fill the closed basins as far
  as the climate is wet enough. Shallow seas hold water, and coastal lowland keeps its cave entrances and ravines
  closed so they do not flood into canals. See [WORLDGEN.md](WORLDGEN.md#rivers).
- **Coasts.** Beaches a real beach's width along the coastline, and sheer sea cliffs along stretches where high land
  meets the sea, in every world, with sea stacks and arches off them; all but the farthest LODs show them. At Earth
  1:1 the sea floor deepens away from the shore along a beach's profile. On cold mountain coasts the glaciers' valleys
  drown into fjords, with a biome of their own, and the longest run 100 km and more inland, over a sill into basins
  up to 1,200 m deep, with arms up their side valleys. The valleys they drowned behind a step hold lakes above the
  sea, as Mjøsa does. See [WORLDGEN.md](WORLDGEN.md#coasts).
- **Distant Horizons.** Its far terrain comes straight from the terrain, without generating chunks: surface blocks
  from the world's own material rules, rivers, sea, ice and snow, tree crowns estimated from each biome's trees
  (biome mods' own trees included), and the coarse map alone for the far horizon. A dedicated server makes them and
  sends them to its players, with or without Distant Horizons SeedGen, checked in game. See
  [COMPATIBILITY.md](COMPATIBILITY.md#distant-horizons).
- **Build height up to 32,736 blocks.** Dimension types can be up to 32,736 blocks tall (Y −16,368 to 16,367);
  vanilla stops at 4064. Positions are repacked, so worlds end at ±8,000,000 blocks. Blocks, light, heightmaps, the
  network and saves work at both ends, in singleplayer and on a dedicated server; a client game test covers them.
  Empty sections cost about 80 bytes of memory
  instead of 450 and are left out of saves, sections of a few blocks take 1 to 3 bits per block instead of 4, and the
  client keeps render data for the rows near the camera only. Sodium builds sections at every height, and Voxy is
  kept within the 8192 blocks it holds. See [HEIGHT.md](HEIGHT.md).
- **Tall mountains.** Mountains stand up to three fifths taller over their valleys than real ones, and at Ultra (1:1)
  heights are half as tall again as the real ones up to 6.5 km: Mont Blanc reaches y = 10,700 and Everest
  y = 14,900, in a world from −256 to 16,367, with the underground and the oceans kept game-sized. Chunks are filled
  only up to their ground, so the air above it is never computed. See [WORLDGEN.md](WORLDGEN.md#taller-mountains) and
  [HEIGHT.md](HEIGHT.md#filling-only-to-the-ground).
- **Generating ahead.** Terrain is computed in the background where the players are heading, so chunks rarely
  wait for inference. See [PERFORMANCE.md](PERFORMANCE.md#generating-ahead).
- **A relief map.** A web page the mod serves for the world that is open, shaded by its relief and drawn from the
  terrain before any chunk is generated: the coarse map when zoomed out, the full terrain when zoomed in, kept on disk.
  Click a place for its coordinates and a `/tp`, or search for the nearest places of a biome. It opens from the pause
  menu or `/diffusicraft map`, and a server can share it with its players. See [MAP.md](MAP.md).
- **Sub-biomes.** Every forest, plain, taiga, jungle, savanna, steppe and desert breaks into patches of its siblings a
  few hundred blocks across, in the same climate: birch stands, glades and dark woods in the forest, bogs in the flat
  hollows of the taiga and pines on its rises. The variants vanilla's weirdness picks come in patches of at most a
  thousand blocks at every size. See [WORLDGEN.md](WORLDGEN.md#sub-biomes).
- **Giant trees.** Redwoods 110 to 230 blocks tall, ancient oaks 48 to 80 under crowns 58 to 96 blocks across, and
  kapoks 80 to 130 rising over the jungle canopy on buttresses to umbrellas 52 to 84 across, in groves of their own on
  level land (redwood forests, ancient woodlands, old-growth jungle) and alone here and there in forests, taigas,
  jungles and on the plains. The oaks and kapoks grow as structures, written a chunk at a time, so their crowns reach
  past the 3×3 chunks a feature may write to. All shrink with the world, to about two fifths at 1:32. The LOD mods
  draw them too. See [WORLDGEN.md](WORLDGEN.md#giant-trees).
- **Waterfalls.** The water of the rivers follows the ground in blocks, so a river falls wherever the ground drops
  away under it, over ledges, the steps of valleys, scarps and sea cliffs, into a pool of the river below, and runs
  down slopes a block at a time rather than in a staircase of level stretches and falls. Tributaries that come out
  over the side of a deeper valley fall into its river. No stream is made up for a fall, so every waterfall comes
  from a river and runs on as one. The curtains are placed whole, so that tall falls stand from the start. Vanilla's
  water springs no longer hang threads of water down the cliffs onto dry ground. See
  [WORLDGEN.md](WORLDGEN.md#waterfalls) and [Rivers](WORLDGEN.md#rivers).
- **Denser jungles.** Vines hang from the jungle crowns down towards the floor as lianas, the old-growth jungle grows
  some 20 trees a chunk with large ferns under them, the free vines of the jungles hang under their canopy at any
  height, and thickets of leafy shrubs and ferns cover their floors between clearings. See
  [WORLDGEN.md](WORLDGEN.md#lianas) and [Undergrowth](WORLDGEN.md#undergrowth).
- **Boulders.** Erratics of granite, gneiss and diorite lie alone on the cold lands, up to 25 blocks across, and
  boulders of a cliff's own rock lie fallen at its foot, the biggest the farthest out. See
  [WORLDGEN.md](WORLDGEN.md#boulders).
- **Half-precision decoder.** On NVIDIA GPUs the decoder runs in fp16, which makes terrain 1.2× faster and moves it
  by 5 cm RMS. See [PERFORMANCE.md](PERFORMANCE.md#half-precision-decoder).

## Next

### Height beyond 4064 blocks

The engine allows 32,736 blocks now. What is left (see [HEIGHT.md](HEIGHT.md#next-steps)):

- **Compatibility.** Distant Horizons holds 4095 blocks and Voxy 8192, so the far terrain of Extreme and Ultra is
  flat above y = 3838 with the one and y = 4095 with the other.

### Terrain detail

- **Lakes larger than a block.** The fine basins wider than a block of three by three tiles outside the lakes of the
  coarse map, which stay dry for now: in two of three windows of 12 by 12 tiles of seed 42, two such basins each, up to
  29 km across, would cover another 1.6 and 3.8% of the land. Blocks of four and five tiles across were measured on the
  GPU and dropped: a new world's first chunk would wait 31 and 45 s instead of 22 s, for the terrain of 49 and 81 tiles
  rather than 25, and in two windows of 12 by 12 tiles of seed 42 with land, they took the lakes from 6.7 to 7.1% of the
  land in one and left the other at 1.7%, the larger blocks adding nothing to the smaller. Also the depressions of more
  than 64 tiles, like those of the Caspian or the Great Lakes, which are breached: a lake waits for the terrain of all
  its tiles, and one of 153 tiles near the origin of seed 42 would take more than a minute on the GPU and some 13
  minutes on the CPU. Filling them only as far as a lake of 16 tiles, with a cut through the rest, was tried and dropped
  ([WORLDGEN.md](WORLDGEN.md#rivers)). Where such a basin lies in a valley the ice drowned behind a step, it keeps the
  sea's level instead of a lake above it. And a way out for the lakes of valleys the ice drowned behind a step in cells
  whose 5th percentile lies below the sea, which end their rivers as fjords do, since the coarse map cannot tell the two
  apart, where neither their tile nor the tiles beside it have sea that rivers reach: one of the 26 cells that end
  rivers in a window of 40 by 80 tiles of seed 42 still ends them in such a lake, with 170 m³/s. Rivers whose sea lies
  only by a side of their tile already cross into the sea beyond.

### Biomes

- **More real-world biomes.** A survey of the land of four worlds by rough Köppen class finds a fitting biome in every
  class now, with the prairie where hot summers burn the woods back and the thorn scrub of the driest savannas. What
  is left needs more than the yearly figures: whether the rain of a climate between the Mediterranean and the monsoon
  falls in summer or in winter, which the data has no month for.
- **Other biome mods.** Biomes O' Plenty, Oh The Biomes We've Gone (BYG) and TerraBlender regions are placed into the
  same climate space from the start, instead of being bolted on, with regions as large as the world's scale calls for.

### Compatibility

- **Voxy.** The same far terrain for Voxy, which SeedGen also targets, straight from the terrain instead of from
  chunks. Built: Diffusicraft fills each section Voxy's store misses, in singleplayer from the world and on a server
  from the grids the server sends (see [COMPATIBILITY.md](COMPATIBILITY.md#voxy)), both checked in game.
- **Rendering mods.** Sodium, Iris and shader packs, which share the GPU with inference. Checked in game: Iris with
  Bliss, Distant Horizons' far terrain included, and Minecraft's Vulkan renderer with Sodium and Sulkan (see
  [COMPATIBILITY.md](COMPATIBILITY.md#shaders)).
- **Structures.** Structure mods, placed on realistic slopes with sensible foundations.
- **Create.** Create and its addons.

### Performance

See [PERFORMANCE.md](PERFORMANCE.md#next-steps). TensorRT waits for a smaller download, and I/O binding for ONNX
Runtime's Java API to offer it.
