# Diffusicraft documentation

**For players and server owners**

- [RUNTIMES.md](RUNTIMES.md): CPU, NVIDIA CUDA and AMD ROCm; what gets downloaded and why; setting up ROCm.
- [CONFIG.md](CONFIG.md): every setting in `config/diffusicraft.json`, where files are stored, and dedicated servers.
- [MODELS.md](MODELS.md): the Terrain Diffusion models: what they are, their size, their license, and how they are
  verified.
- [MAP.md](MAP.md): the relief map in the browser: opening it, finding places and their coordinates, and sharing it
  from a server.
- [COMPATIBILITY.md](COMPATIBILITY.md): other mods: Distant Horizons, biome mods, C2ME, shaders and structures.

**For contributors**

- [ARCHITECTURE.md](ARCHITECTURE.md): how the mod is put together: startup, native runtime selection, and the
  windowed diffusion pipeline.
- [WORLDGEN.md](WORLDGEN.md): how the terrain becomes chunks: world sizes, the terrain field, biome
  parameters, density and the spawn.
- [LOD.md](LOD.md): the far terrain of our own: what it takes from Distant Horizons and Voxy, how saved chunks are
  kept, how it is drawn, under shader packs too, and what comes next.
- [HEIGHT.md](HEIGHT.md): build heights up to 32,736 blocks: how positions are repacked, what it costs, and what it
  breaks.
- [DEVELOPMENT.md](DEVELOPMENT.md): building, running with the dev modpack, tests and the headless client game test.
- [MOD_SURVEY.md](MOD_SURVEY.md): other biome and terrain mods, and what each changes in the overworld.
- [PERFORMANCE.md](PERFORMANCE.md): benchmarks, where the time goes, and what to optimise next.
- [ROADMAP.md](ROADMAP.md): the full vision: world scales, height beyond 4064, climate biomes, geology and the
  Hollows.
- [../CONTRIBUTING.md](../CONTRIBUTING.md): how to send changes.
