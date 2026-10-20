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
- **World types and scales.** Diffusicraft is the default world type on the Create World screen and on dedicated
  servers. Three scales:

  | World type | Horizontal | Vertical | Build height |
  |---|---|---|---|
  | **Diffusicraft** | 15 m per block | 10 m per block, eased above 1 km | Vanilla (−64 to 319) |
  | **Diffusicraft Grand** | 5 m per block | 4 m per block, eased above 8 km | −256 to 2383 |
  | **Diffusicraft Earth 1:1** | 1 m per block | 1 m per block, eased above 8 km | −256 to 9087 |

  The scale factors will be tuned in game. See [WORLDGEN.md](WORLDGEN.md).
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
  rainforest, Mediterranean maquis, moorland, heath, salt flats, bogs, Siberian larch taiga, cold desert, desert scrub,
  steppe, fjords, monsoon and laurel forests, and the páramo and puna of tropical mountains, with their own trees
  (krummholz, giant spruces, olives, stone pines, cypresses, black spruces, larches, mesquites, teak, live oaks,
  frailejones, queñuas), plants and ground; the wetlands follow the climate, from mangroves to bogs. New plants:
  heather, cottongrass, lavender, edelweiss and gentian, and salt crust. Soils of their own under some biomes: peat,
  which burns, terra rossa, laterite and loess. Snow follows the climate rather than a fixed height, above a real tree
  line, with no forest on it, and the Grand Hollows are free of sculk. See [WORLDGEN.md](WORLDGEN.md#refined-biomes).
- **Real biome sizes.** Biomes come in patches as large as their real counterparts. At Earth 1:1 the main forests
  and taigas average 10 to 20 km across, a few hundred square kilometres at most, and steppe or maquis can cover a
  whole plain. Sun and shade are measured over a whole valley side, vanilla's variants are picked by noise with a
  12 km wavelength, summer rain counts as Köppen counts it, so continental plains are steppe, and meadows keep to the
  subalpine belt instead of lining every valley side. Pale gardens, from vanilla's plateau row, still come in belts a
  few kilometres long on the upper slopes. See [WORLDGEN.md](WORLDGEN.md#biome-parameters).
- **Geology.** Sedimentary beds by basin, fold belts, basement, deepslate, gabbro, peridotite with kimberlite pipes,
  and a magma layer above the bedrock. Ore bodies by rock and depth on top of vanilla's ores. Eleven new rocks. See
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
- **Gullies.** At Earth 1:1 the slopes are cut by gullies finer than the model's 30 m pixels, which run downhill and
  branch, deeper on steeper ground; gentle ground stays smooth. The rock and the climate shape them: crags in hard
  rock, badlands in bare soft rock, rounded hills where soil and plants cover soft rock. See
  [WORLDGEN.md](WORLDGEN.md#gullies).
- **Ledges.** Where a slope cuts through the sedimentary cover, its hard beds of sandstone and limestone stand out as
  cliffs and the soft beds between them recede into benches, as in the Grand Canyon, in every world. See
  [WORLDGEN.md](WORLDGEN.md#ledges).
- **Lakes on rivers.** Rivers fill the basins they cross and flow on out of them over their lowest point. See
  [WORLDGEN.md](WORLDGEN.md#rivers).
- **Lakes across tiles.** Basins that reach past the side of a tile hold lakes up to 15 km across, on which the
  tiles on both sides agree exactly. Rivers fill those they cross. See [WORLDGEN.md](WORLDGEN.md#rivers).
- **Great lakes.** The depressions of the coarse map hold lakes of up to 16 tiles and 60 km across, where the water
  flowing into them outweighs what they evaporate. Their water stands at one level over all their tiles, and the
  rivers cross them at that level and flow on out. See [WORLDGEN.md](WORLDGEN.md#rivers).
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
  drown into fjords, with a biome of their own. See [WORLDGEN.md](WORLDGEN.md#coasts).
- **Distant Horizons.** Its far terrain comes straight from the terrain, without generating chunks: surface blocks
  from the world's own material rules, rivers, sea, ice and snow, tree crowns estimated from each biome's trees
  (biome mods' own trees included), and the coarse map alone for the far horizon. See
  [COMPATIBILITY.md](COMPATIBILITY.md#distant-horizons).
- **Build height up to 32,736 blocks.** Dimension types can be up to 32,736 blocks tall (Y −16,368 to 16,367);
  vanilla stops at 4064. Positions are repacked, so worlds end at ±8,000,000 blocks. Blocks, light, heightmaps, the
  network and saves work at both ends, in singleplayer and on a dedicated server; a client game test covers them.
  Empty sections cost about 80 bytes of memory
  instead of 450 and are left out of saves, sections of a few blocks take 1 to 3 bits per block instead of 4, and the
  client keeps render data for the rows near the camera only. See [HEIGHT.md](HEIGHT.md).
- **Real mountain heights.** Mountains stand at their real height in Earth 1:1, a block per meter up to 8 km: from
  −256 to 9087, with the underground and the oceans kept game-sized. Chunks are filled only up to their ground, so
  the air above it is never computed. See [HEIGHT.md](HEIGHT.md#filling-only-to-the-ground).
- **Generating ahead.** Terrain is computed in the background where the players are heading, so chunks rarely
  wait for inference. See [PERFORMANCE.md](PERFORMANCE.md#generating-ahead).

## Next

### Height beyond 4064 blocks

The engine allows 32,736 blocks now. What is left (see [HEIGHT.md](HEIGHT.md#next-steps)):

- **Compatibility.** Sodium and Voxy, which make their own assumptions about world height. Distant Horizons holds
  4095 blocks, so the LODs of Earth 1:1 are flat above y = 3838.

### Terrain detail

- **Rain lakes near rivers.** Lakes across tiles where a river runs in one of the tiles their water reaches, at most
  0.5% of the land: telling whether the river passes beside one takes the rivers of the tiles around, so the lakes of a
  tile would read the terrain of 5 × 5 tiles rather than 3 × 3, which a new world and a fast flight wait for. Also the
  fine basins larger than a block outside the lakes of the coarse map, which stay dry for now; and depressions of more
  than 16 tiles, like those of the Caspian or the Great Lakes, which are breached.

### Biomes

- **More real-world biomes.** The other biomes the climate can tell apart.
- **Longer fjords.** Today's fjords reach about 20 km inland and end where their valleys climb over a step the coarse
  map does not see. The longest real ones run 100 km and more inland, over sills, between walls a kilometre high.
- **Other biome mods.** Biomes O' Plenty, Oh The Biomes We've Gone (BYG) and TerraBlender regions are placed into the
  same climate space from the start, instead of being bolted on, with regions as large as the world's scale calls for.

### Compatibility

- **Voxy.** The same LODs for Voxy, which SeedGen also targets. Voxy has no release for 26.3 yet. It has no generator
  API either: Diffusicraft would push sections of LOD columns into it, as Voxy's own importers do.
- **Rendering mods.** Sodium, Iris and shader packs, which share the GPU with inference.
- **Structures.** Structure mods, placed on realistic slopes with sensible foundations.
- **Create.** Create and its addons.

### Performance

See [PERFORMANCE.md](PERFORMANCE.md#next-steps): FP16/TensorRT, I/O binding, larger requests.
