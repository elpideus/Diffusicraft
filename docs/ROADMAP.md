# Roadmap

**The goal.** A fantasy world in the spirit of Skyrim or The Witcher, but grounded in reality. Its mountains, rivers,
climates, forests and rock layers should look and behave like real ones. It is aimed at survival, realism and RPG
modpacks built around Create.

The pieces in place generate the world's shape, climate, rivers, lakes, rock and deep caverns, and a world generator
turns them into blocks. The pieces planned add the details: erosion, soils and new biomes.

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
- **Biomes from the climate.** The six vanilla biome parameters come from the real temperature, aridity, elevation
  and relief. Snowy peaks appear only where it is cold, and deserts only where it is hot and dry. Biome mods that
  extend the overworld table get the same parameters; checked in game with Biomes O' Plenty and TerraBlender. See
  [COMPATIBILITY.md](COMPATIBILITY.md#biome-mods).
- **Real-world biomes.** The seasons refine vanilla's picks into tundra, alpine tundra, cloud forest, temperate
  rainforest, Mediterranean maquis, moorland, heath, salt flats, cold desert and steppe, with their own trees
  (krummholz, giant spruces, olives, stone pines, cypresses), plants and ground; swamps and mangroves follow the
  coast's climate. New plants: heather, cottongrass, lavender, edelweiss and gentian, and salt crust. Soils of their
  own under some biomes: peat, which burns, terra rossa, laterite and loess. Snow follows the
  climate rather than a fixed height, above a real tree line, and the Grand Hollows are free of sculk. See
  [WORLDGEN.md](WORLDGEN.md#refined-biomes).
- **Geology.** Sedimentary beds by basin, fold belts, basement, deepslate, gabbro, peridotite with kimberlite pipes,
  and a magma layer above the bedrock. Ore bodies by rock and depth on top of vanilla's ores. Eleven new rocks. See
  [WORLDGEN.md](WORLDGEN.md#the-ground).
- **The Hollows.** Caverns larger the deeper they lie: Hollows, Deep Hollows, Grand Hollows with pillars and lit
  ceilings, Ancient Grand Hollows, Deep Grand Hollows over lava seas, and deep noodle caverns. See
  [WORLDGEN.md](WORLDGEN.md#the-hollows).
- **Rivers and lakes.** Drainage of the whole world from its rain and terrain, with discharge, meanders,
  tributaries, widths that grow with the discharge, banks, beds and river biomes. Lakes fill the closed basins as far
  as the climate is wet enough. Shallow seas hold water, and coastal lowland keeps its cave entrances and ravines
  closed so they do not flood into canals. See [WORLDGEN.md](WORLDGEN.md#rivers).
- **Distant Horizons.** Its far terrain comes straight from the terrain, without generating chunks: surface blocks
  from the world's own material rules, rivers, sea, ice and snow, tree crowns estimated from each biome's trees
  (biome mods' own trees included), and the coarse map alone for the far horizon. See
  [COMPATIBILITY.md](COMPATIBILITY.md#distant-horizons).
- **Build height up to 32,736 blocks.** Dimension types can be up to 32,736 blocks tall (Y −16,368 to 16,367);
  vanilla stops at 4064. Positions are repacked, so worlds end at ±8,000,000 blocks. Blocks, light, heightmaps, the
  network and saves work at both ends; a client game test covers them. See [HEIGHT.md](HEIGHT.md).
- **Real mountain heights.** Mountains stand at their real height in Earth 1:1, a block per meter up to 8 km: from
  −256 to 9087, with the underground and the oceans kept game-sized. Chunks are filled only up to their ground, so
  the air above it is never computed. See [HEIGHT.md](HEIGHT.md#filling-only-to-the-ground).
- **Generating ahead.** Terrain is computed in the background where the players are heading, so chunks rarely
  wait for inference. See [PERFORMANCE.md](PERFORMANCE.md#generating-ahead).

## Next

### Height beyond 4064 blocks

The engine allows 32,736 blocks now. What is left (see [HEIGHT.md](HEIGHT.md#next-steps)):

- **Memory and saves.** Empty sections cost about 80 bytes now instead of 450, and the client keeps render data for
  the rows near the camera only. Still to do: share uniform sections as soon as world generation makes them, and skip
  empty sections when saving.
- **Compatibility.** Sodium, Distant Horizons and Voxy, which make their own assumptions about world height, and a
  dedicated server over a real network.

### Terrain detail

- **Sub-30 m detail.** Add procedural detail below the model's resolution, driven by the local slope, rock type and
  climate.
- **Erosion.** Cliffs, scree and talus.
- **Lakes on rivers.** Rivers that fill the basins they cross and flow on out of them, and lakes larger than a
  tile.
- **Coasts.** Coastlines, beaches and cliffs follow the real sea level (y = 0 at 1:1).

### Biomes

- **More of the climate.** Use the slope and aspect, which the biomes ignore so far.
- **Real sizes.** Biomes are as large as their real counterparts: a forest can be tens of kilometres across at 1:1.
- **More real-world biomes.** Fjords, bogs and the other biomes the climate can tell apart.
- **Other biome mods.** Biomes O' Plenty, Oh The Biomes We've Gone (BYG) and TerraBlender regions are placed into the
  same climate space from the start, instead of being bolted on, with regions as large as the world's scale calls for.

### Underground

- **Soils.** Soil that follows the rock beneath as well as the climate, and soil profiles deeper than a few blocks.
- **New minerals.** Minerals for Create and other tech mods, placed by the same geology.
- **Life in the Hollows.** Cave biomes, flora and lakes of their own for the Grand Hollows.

### Compatibility

- **Voxy.** The same LODs for Voxy, which SeedGen also targets. Voxy has no release for 26.3 yet. It has no generator
  API either: Diffusicraft would push sections of LOD columns into it, as Voxy's own importers do.
- **Rendering mods.** Sodium, Iris and shader packs, which share the GPU with inference.
- **Structures.** Structure mods, placed on realistic slopes with sensible foundations.
- **Create.** Create and its addons.

### Performance

See [PERFORMANCE.md](PERFORMANCE.md#next-steps): FP16/TensorRT, I/O binding, larger requests.
