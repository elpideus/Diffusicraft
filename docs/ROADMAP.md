# Roadmap

**The goal.** A fantasy world in the spirit of Skyrim or The Witcher, but grounded in reality. Its mountains, rivers,
climates, forests and rock layers should look and behave like real ones. It is aimed at survival, realism and RPG
modpacks built around Create.

The pieces in place generate the world's shape and climate. The pieces planned turn that into Minecraft blocks.

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

## Next: the world generator

### World type and scales

Diffusicraft becomes the default world type on the Create World screen, with three scales:

| Mode | Vertical | Horizontal | Build height |
|---|---|---|---|
| **Vanilla** | Real relief compressed to fit | Compressed so slopes stay walkable | Vanilla (−64 to 320) |
| **Intermediate** | Moderate exaggeration of hills and ranges | 1 block ≈ several meters | Up to the vanilla data-pack limit (4064) |
| **Extreme / Godlike** | 1 block = 1 m | 1 block = 1 m | Beyond 4064: Earth's relief spans about −11 km to +8.8 km |

The exact scale factors will be tuned in game, so that each mode keeps recognisable valleys, ridges and coasts at
its own scale.

### Height beyond 4064 blocks

The 1:1 mode needs build heights beyond the limits `DimensionType` enforces (height ≤ 4064, multiple of 16). This
needs research into:

- raising the codec limits and the section storage behind them;
- lighting and heightmaps at that scale;
- compatibility with Sodium, Distant Horizons and Voxy, which make their own assumptions about world height.

### Terrain detail

- **Interpolation.** Interpolate the 30 m grid to blocks with slope-preserving interpolation.
- **Sub-30 m detail.** Add procedural detail below the model's resolution, driven by the local slope, rock type and
  climate.
- **Water and erosion.** Carve rivers and lakes from the elevation field (flow accumulation), with valleys, cliffs,
  scree and talus.
- **Coasts.** Coastlines, beaches and cliffs follow the real sea level (y = 0 at 1:1).

### Biomes

- **Placement from climate.** Biomes are placed from the model's climate: annual temperature, seasonality,
  precipitation and its seasonality. They also depend on elevation, slope and aspect, rather than on vanilla's noise
  parameters.
- **Real sizes.** Biomes are as large as their real counterparts: a forest can be tens of kilometres across at 1:1.
- **Snow.** Snowy peaks appear only where it is both high and cold.
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
