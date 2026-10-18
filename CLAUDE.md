# Diffusicraft: notes for coding agents

Fabric mod for Minecraft 26.3+ (Java 25, Mojang's unobfuscated names, Loom split source sets). It does AI world
generation with Terrain Diffusion through ONNX Runtime 1.31. Start with [docs/README.md](docs/README.md). The
architecture is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the plan in [docs/ROADMAP.md](docs/ROADMAP.md).

## Commands

```bash
./gradlew build                      # compile everything + fast unit tests (no downloads)
./gradlew runClient -PnoDevMods      # game without the dev modpack
./gradlew test -PwithModels --tests '*WorldPipelineBenchmarkTest' -Pdiffusicraft.device=CPU   # needs models
env -u WAYLAND_DISPLAY SDL_VIDEO_DRIVER=x11 xvfb-run -a -s "-screen 0 1600x900x24" \
    ./gradlew runClientGameTest -PnoDevMods   # headless UI test, screenshots in build/run/clientGameTest/screenshots
```

## Things to know

- **Testing the UI.** Use the client game test under Xvfb (above). Never take screenshots of the user's desktop, and
  never open windows on it. Prefix with `XDG_DATA_HOME=$(mktemp -d)` to simulate a fresh install.
- **Native runtime selection.** `NativeRuntime.activate()` must run before any `ai.onnxruntime` class initialises.
  Always go through `OnnxModel.environment()`. A runtime change needs a game restart.
- **Downloads.** Every download is pinned in `src/main/resources/diffusicraft/*-manifest.json`. Re-pin sizes and
  SHA-256s whenever a URL changes. Keep `onnxruntime_version` in `gradle.properties` in sync with the manifest.
- **Package boundaries.** `runtime` and `terrain` must not import client classes. They run on servers and in plain
  JUnit tests.
- **Render thread.** No I/O, hashing or blocking on it. Screens read snapshots (`Resources.status()`) and refresh on
  tick.
- **Mixin targets** that break on Minecraft updates:
  - client: `Gui.addInitialScreens`, `CreateWorldScreen$MoreTab.<init>`, `CreateWorldScreen.onCreate` and
    `WorldCreationUiState.<init>`;
  - common: `DedicatedServerProperties.<init>` (its read of `WorldPresets.NORMAL`), the synthetic
    `ChunkGeneratorStructureState.lambda$generateRingPositions$0`, whose name changes if Mojang adds a lambda before it,
    `NoiseBasedChunkGenerator.createFluidPicker`, the call to `applyCarvingMask` in
    `NoiseBasedChunkGenerator.generateCarvers`, `RandomState.create` and `createClimateSampler`, the interface added
    to `Climate.Sampler`, and the accessors for `MaterialRuleContext.randomState` and `RandomState.random`. The class
    tweaker `diffusicraft.classtweaker` opens `MultiNoiseBiomeSource`'s constructor.
- **World generation** is described in [docs/WORLDGEN.md](docs/WORLDGEN.md). The data under
  `src/main/resources/data` and the block assets are generated: edit `tools/worldgen_data.py`,
  `tools/block_assets.py` (blocks) or `tools/biomes.py` (biomes, their features and tags) and rerun it, never the
  JSON or the textures.
- **Untested paths.** The AMD ROCm/MIGraphX path has never run on AMD hardware. Say so when touching it.
- **Commits.** Small and logical, with an imperative subject and a body saying why. Docs change in the same commit as
  the behaviour.
