# Roadmap

**The goal.** A fantasy world in the spirit of Skyrim or The Witcher, but grounded in reality. Its mountains, rivers,
climates, forests and rock layers should look and behave like real ones. It is aimed at survival, realism and RPG
modpacks built around Create.

The pieces in place generate the world's shape and climate, and a first world generator turns them into blocks.
The pieces planned add the details: geology, caves, rivers and new biomes.

## Done

- **Terrain Diffusion in Java.** A concurrent, deterministic port of the Terrain Diffusion pipeline: infinite
  elevation and climate at 30 m per pixel. See [MODELS.md](MODELS.md) and [ARCHITECTURE.md](ARCHITECTURE.md).
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
  | **Diffusicraft Grand** | 5 m per block | 4 m per block, eased above 4.8 km | −256 to 2031 |
  | **Diffusicraft Earth 1:1** | 1 m per block | 1 m per block, eased above 1.2 km | −256 to 2031 |

  The scale factors will be tuned in game. See [WORLDGEN.md](WORLDGEN.md).
- **Biomes from the climate.** The six vanilla biome parameters come from the real temperature, aridity, elevation
  and relief. Snowy peaks appear only where it is cold, and deserts only where it is hot and dry. Biome mods that
  extend the overworld table get the same parameters.
- **Build height up to 32,736 blocks.** Dimension types can be up to 32,736 blocks tall (Y −16,368 to 16,367);
  vanilla stops at 4064. Positions are repacked, so worlds end at ±8,000,000 blocks. Blocks, light, heightmaps, the
  network and saves work at both ends; a client game test covers them. See [HEIGHT.md](HEIGHT.md).

## Next

### Height beyond 4064 blocks

The engine allows 32,736 blocks now. What is left (see [HEIGHT.md](HEIGHT.md#next-steps)):

- **Earth 1:1 at full height.** It still compresses mountains above 1.2 km to fit 2288 blocks. Earth's relief needs
  about 20,000.
- **Memory and saves.** Empty sections cost about 80 bytes now instead of 450. Still to do: share uniform sections
  as soon as world generation makes them, and skip empty sections when saving.
- **Compatibility.** Sodium, Distant Horizons and Voxy, which make their own assumptions about world height, and a
  dedicated server over a real network.

### Terrain detail

- **Interpolation.** Interpolate the 30 m grid to blocks with slope-preserving interpolation.
- **Sub-30 m detail.** Add procedural detail below the model's resolution, driven by the local slope, rock type and
  climate.
- **Water and erosion.** Carve rivers and lakes from the elevation field (flow accumulation), with valleys, cliffs,
  scree and talus.
- **Coasts.** Coastlines, beaches and cliffs follow the real sea level (y = 0 at 1:1).

### Biomes

- **More of the climate.** Use the seasonality of temperature and precipitation, and the slope and aspect, which the
  biome parameters ignore so far.
- **Real sizes.** Biomes are as large as their real counterparts: a forest can be tens of kilometres across at 1:1.
- **New real-world biomes.** For example:
  - temperate rainforest, Mediterranean maquis, steppe, moorland and heath;
  - alpine tundra, cloud forest, salt flats, fjords and mangroves.
- **New flora.** New trees, flowers and grasses that match those biomes.
- **Other biome mods.** Biomes O' Plenty, Oh The Biomes We've Gone (BYG) and TerraBlender regions are placed into the
  same climate space from the start, instead of being bolted on.

### Underground

- **Strata.** Earth-like layers: soil and subsoil, sedimentary rock, metamorphic and igneous basement, then a magma
  layer above bedrock.
- **Ores.** Ore and mineral rarity depends on depth and rock type.
- **New materials.** New minerals, rocks and soils.
- **The Hollows.** Cave systems at increasing depth and scale, in the style of *Journey to the Center of the Earth*:
  - **Hollow** and **Deep Hollow**;
  - **Grand Hollow**: cavern worlds with their own ceilings, lakes and light;
  - **Ancient Grand Hollow** and **Deep Grand Hollow**;
  - deep noodle caverns linking them.

### Compatibility

- **Distant Horizons and Voxy SeedGen.** The terrain is a pure function of seed and position, so LODs can be
  generated straight from the pipeline.
- **C2ME.** Parallel chunk generation: the pipeline is already thread-safe.
- **Rendering mods.** Sodium, Iris and shader packs, which share the GPU with inference.
- **Structures.** Structure mods, placed on realistic slopes with sensible foundations.
- **Create.** Create and its addons.

### Performance

See [PERFORMANCE.md](PERFORMANCE.md#next-steps): generating ahead of the player, FP16/TensorRT, I/O binding.
