# Diffusicraft

Next-generation world generation for Minecraft (Fabric, 26.3+), driven by
[Terrain Diffusion](https://github.com/xandergos/terrain-diffusion), a diffusion model trained on real-world
elevation and climate data. The goal is a world that feels like Skyrim or The Witcher, but is grounded in real
geography, climate and geology.

> **Status: early development.** The terrain pipeline, the GPU runtimes, the resource manager and the world
> generator work, with geology, the Hollows, rivers and lakes, coasts, and real-world biomes. Shores and more biome
> mods are next. See the [roadmap](docs/ROADMAP.md).

## What works today

- **A world generator.** Diffusicraft is the default world type, at three scales: Diffusicraft (vanilla height),
  Grand (up to y = 2383) and Earth 1:1, whose mountains stand at their real height (up to y = 9087). Biomes follow the
  real climate: snow only where it is cold, deserts only where it is hot and dry, and the sunny side of a valley is
  warmer and drier than the shaded one. Vanilla caves, structures and biome mods keep working. See
  [WORLDGEN.md](docs/WORLDGEN.md).
- **Rivers, coasts, rock and caverns.** Rivers drain the whole world from its rain and terrain, and fill the lakes on
  their way, up to great lakes 123 km across. Beaches follow the coastline, and sea cliffs rise where high land meets
  the sea, with stacks and arches off them. On cold mountain coasts the valleys drown into fjords, the longest reaching
  100 km and more inland and branching into arms. The rock shapes the slopes: hard beds stand out of them as ledges and
  cliffs. Soils follow the rock under them and the climate, from thin, stony ground in the cold to deep laterite in the
  tropics. Underground, sedimentary beds, fold belts and basement rock hold ores by rock and depth, above a magma layer,
  and the minerals of tech mods where Create, Tech Reborn or Mekanism add them: zinc and lead in limestone, tin in
  granite, platinum in gabbro, bauxite under the tropics. Caverns grow larger the deeper they lie, down to the Grand
  Hollows over lava seas, each a cave biome of its own: glowworm grottoes, selenite caverns, mushroom forests, lush and
  dripstone caves, with lakes in their floors under wet land.
- **Real-world biomes.** Tundra, cloud forest, monsoon forest, laurel forest, páramo, puna, temperate rainforest,
  maquis, moorland, heath, bogs, fjords, salt flats, steppe and more, with their own trees, plants and soils.
- **The full Terrain Diffusion pipeline, in Java.** Infinite, seamless, deterministic elevation and climate at 30 m
  per pixel. It is thread-safe for parallel chunk generation and cached in memory and on disk.
- **CPU, NVIDIA and AMD.**
  - The CPU build is bundled.
  - The NVIDIA CUDA runtime is a one-click download from official sources. It is about 6× faster than the CPU.
  - AMD ROCm (MIGraphX) is experimental, with your own ONNX Runtime build.
- **Distant Horizons.** With Distant Horizons installed, its far terrain, trees included, comes straight from the
  terrain without generating chunks, and the far horizon costs no model inference. See
  [COMPATIBILITY.md](docs/COMPATIBILITY.md).
- **A resource screen.** It opens at first launch, and later from Create World > More or Mod Menu. It downloads the
  models (2.3 GB) and the GPU runtime in the background. Before anything downloads, it shows the licenses to accept.
  It also caps GPU memory so shaders keep theirs.

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API, Java 25.
- About 2.3 GB of disk space for the models. The CUDA runtime adds 1.1–2 GB, shared by every instance.
- For NVIDIA: a GeForce 16 / RTX 20 series GPU or newer, with driver 580+.

## Documentation

Everything is in [docs/](docs/README.md): runtimes, configuration, models, architecture, development and performance.
Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT. Terrain Diffusion and its weights are MIT-licensed by Alexander Goslin. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for everything else.
