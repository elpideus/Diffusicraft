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
# add -Pdiffusicraft.gametests=TallDimension,Ultra to run only the game tests whose class names start with those
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
  - client: `Gui.addInitialScreens`, `CreateWorldScreen$MoreTab.<init>`, `CreateWorldScreen$WorldTab.<init>` and its
    `customizeTypeButton`, `CreateWorldScreen.onCreate`, `WorldCreationUiState.<init>`, and for the far terrain
    (`client/mixin/lod`) the head of `LevelRenderer.addMainPass`, the tail of `LevelRenderer.executeSolid`, the first
    `Math.max` in `Camera.update` (its far plane) and the return of `FogRenderer.setupFog`, and for the clouds
    (`client/mixin/clouds`) the drawing `CloudRenderer.prepare` (wrapped, named by its descriptor beside the one that
    reloads the clouds) and its calls to `Std140Builder.putVec3`, the first write of `FogData.cloudEnd` in
    `AtmosphericFogEnvironment.setupFog` and the same `Math.max` in `Camera.update`;
  - common: `DedicatedServerProperties.<init>` (its read of `WorldPresets.NORMAL`), the synthetic
    `ChunkGeneratorStructureState.lambda$generateRingPositions$0`, whose name changes if Mojang adds a lambda before it,
    the call to `Stream.collect` in `ChunkGeneratorStructureState.createForNormal` (where a Diffusicraft world's villages
    and pillager outposts take Diffusicraft's spreads),
    `NoiseBasedChunkGenerator.createFluidPicker` and `createNoiseChunk`, the call to `clampToHeightAccessor` in
    `NoiseBasedChunkGenerator.iterateNoiseColumn`, the calls to `WorldCarver.isStartChunk`, `applyCarvingMask` and
    `Holder.value` (vanilla's carvers swapped for Diffusicraft's copies) in `NoiseBasedChunkGenerator.generateCarvers`,
    the material rule at the heads of `generateCarvers` and `buildSurface` (where other mods' rules are split from
    Diffusicraft's), the `cache` field of `StructureTemplate.Palette` (replaced at the end
    of its constructor, so that the far terrain's threads can fill it beside the chunks'), the call to
    `FeaturePlacer.placeWithBiomeCheck` in
    `ChunkGenerator.applyBiomeDecoration` (which also places the river falls and boulders after `spring_water`), the
    call to `WorldGenLevel.setBlock` in `SpringFeature.place`, the call to `SerializableChunkData.copyOf` in
    `ChunkMap.save(ChunkAccess)` (which hands each saved chunk to the far terrain's store),
    `NoiseChunk.<init>` (its `aquifer` field, which every caller reads through `aquifer()`), `RandomState.create` (its
    head too, where vanilla's noises are swapped for Diffusicraft's copies and the loaded noise router and aquifers
    are put back) and `createClimateSampler`, the interface added to `Climate.Sampler`, the `0.05F` in
    `Biome.getHeightAdjustedTemperature`, the accessors for `ChunkGenerator.featuresPerStep` (where the far terrain
    finds the redwoods' places among the trees), `MaterialRuleContext.randomState`, `RandomState.random`, the
    `NoiseGeneratorSettings` fields `materialRule`, `noiseRouter` and `aquifers` (kept as loaded at the end of the
    constructor, because TerraBlender and FrozenLib change what the rule's accessor returns, Biolith replaces the rule
    and Lithostitched the router and aquifers), and the invokers of `MaterialRuleContext`'s
    constructor, `updateXZ` and `updateY`, `MinecraftServer.setInitialSpawn` (cancelled at its head),
    `PlayerSpawnFinder.findSpawn` (wrapped) and the invokers of its `getLevelRespawnPos` and `fixupSpawnHeight`. The
    class tweaker `diffusicraft.classtweaker` opens `MultiNoiseBiomeSource`'s constructor. Not a mixin, but as
    fragile: `worldgen.biome.VanillaBiomeTable` defines a copy of `OverworldBiomeBuilder` from the game's jar and calls
    its constructor and `addBiomes` by reflection, which breaks if the builder gains a nested class or reaches
    anything package-private (`VanillaBiomeTableTest` checks it);
  - height (`mixin/height`): the `30000000`, `3.0E7`, `29999984` and `2097152` literals in vanilla, the locals of
    `SerializableChunkData.parse` and `copyOf` and of `FriendlyByteBuf.read/writeBlockPos`, and the internals of
    `PalettedContainer`, its `Strategy` and `SingleValuePalette` (opened by `diffusicraft.classtweaker`); on the client
    (`client/mixin/height`), `new RotatingSectionStorage` in `ViewArea.<init>` and `SectionUpdateTracker.<init>`.
    The full table is in [docs/HEIGHT.md](docs/HEIGHT.md#mixin-targets);
  - Distant Horizons (`mixin/dh`, applied only when it is installed): the height getters of its `ChunkWrapper`
    (`getExclusiveMaxBuildHeight()`, `getMaxNonEmptyHeight`, `getSolidHeightMapValue` and
    `getLightBlockingHeightMapValue`). Check them on every Distant Horizons update.
  - Tectonic (`mixin/tectonic`, applied only when it is installed): the head of `SerializableChunkData.parse`, at a
    priority that runs before Tectonic's own, and its `Tectonic.BLENDING_KEY` and `BLENDING_VERSION`, read by
    reflection. Biolith (`mixin/biolith`): the first two `Holder.is` calls in `MaterialSystem.buildSurface`, which
    Biolith wraps to run its surface builders, the call to `Holder.unwrapKey` in `BiomeCoordinator.handleWorldStarting`,
    its `SurfaceRuleCollector.OVERWORLD.getAllBootstrapped`, read by reflection in `compat.biolith.BiolithRules`, and
    `biolith$getDimensionType`, the method Biolith adds to every biome source, which `ClimateBiomeSource` overrides.
    Check them on every update of either mod.
  - Voxy (`client/mixin/voxy`, applied only when it is installed): the `new RenderDistanceTracker` in
    `VoxyRenderSystem.<init>`, `WorldUpdater.insertUpdate`, the call to `SectionLoader.load` in
    `ActiveSectionTracker.acquire(long, boolean)`, the private `VoxyInstance.createWorld`, the interface added to
    `WorldEngine`, and every member declared in `src/voxyStubs`. Voxy is All Rights Reserved, so Diffusicraft compiles
    against its own declarations of the Voxy members it uses, never against Voxy. Check them all on every Voxy
    update.
  - Sodium (`client/mixin/sodium`, applied only when it is installed): the `-128` inlined once in
    `TaskCollectingTree.addPendingSection` and in `DeferredTaskList.dequeueNextSectionPos`, `getPendingTaskLists`
    returning the `DeferredTaskList`, and `AbstractSectionVisitor.baseOffsetY`, plus
    `SodiumWorldRenderer.instanceNullable()`, `isSectionReady(int, int, int)` and `isBoxVisible` (six doubles),
    which `client.compat.sodium.SodiumSections` calls through method handles for the far terrain. `ChunkMask` and
    `far_terrain.fsh` repeat the distance test of `TraversableTree` (a cylinder from the camera itself) and the
    distance `RenderSectionManager.getSearchDistance` picks from the fog, which Sodium reads at the return of
    `FogRenderer.setupFog`, after our `FogRendererMixin` (priority 900). Check them on every Sodium update.
  - Iris (`client/mixin/iris`, applied only when it is installed and Distant Horizons is not): the static
    `StandardMacros.createStandardEnvironmentDefines` (where `DISTANT_HORIZONS` is defined), the `DHCompat` methods
    `checkFrame`, `hasRenderingEnabled`, `getNearPlane`, `getFarPlane`, `getRenderDistance`, `getProjection()`,
    `getDepthTex` and `getDepthTexNoTranslucent`, and the heads of `LevelRenderer.executeSolid` and
    `executeClassicTransparency`, which rely on Iris starting its deferred passes and a new pass just before the
    latter. `client.compat.iris` builds the pack's `dh_terrain` and `dh_water` from Iris's own classes
    (`TransformPatcher.patchDHTerrain`, `ProgramUniforms`, `ProgramSamplers`, `IrisRenderingPipeline`'s DH getters and
    `createDHFramebuffer`), and its `ChunkMaskPatch` renames the patched `void main()` and reads `modelOffset` and the
    `_vert_position` and `_vert_normal` that Iris's `DHTerrainTransformer` declares. `OverdrawTests` reads the source
    as Iris prints it: preprocessed, with no comments, and the first output as `layout(location = 0) out vec4`. Check
    them all on every Iris update.
- **Positions.** `BlockPos` packs 24/16/24 bits, not 26/12/26, and worlds end at ±8,000,000. Never pack positions by
  hand or assume ±30,000,000: use `BlockPos.asLong`/`of`, and the numbers in `ExtendedHeight`.
- **World generation** is described in [docs/WORLDGEN.md](docs/WORLDGEN.md). The data under
  `src/main/resources/data` and the block assets are generated: edit `tools/worldgen_data.py`,
  `tools/block_assets.py` (blocks) or `tools/biomes.py` (biomes, their features and tags) and rerun it, never the
  JSON or the textures.
- **Optional mods.** Mod Menu, Iris and the Distant Horizons API are `compileOnly`, never bundled. Code that touches
  them (`client.compat`, `compat.dh`) loads only when the mod is installed; keep their classes out of everything else.
- **Untested paths.** The AMD ROCm/MIGraphX path has never run on AMD hardware. Say so when touching it.
- **Commits.** Small and logical, with an imperative subject and a body saying why. Docs change in the same commit as
  the behaviour.
