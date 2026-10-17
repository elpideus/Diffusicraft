# Architecture

Diffusicraft is a Fabric mod for Minecraft 26.3+. It runs on Java 25 and uses Mojang's unobfuscated names. It has
three layers:

```text
┌──────────────────────────────────────────────────────────────────────────┐
│ client: resource screen, launch hook, Create World button, Mod Menu, toasts │
├──────────────────────────────────────────────────────────────────────────┤
│ worldgen (planned): world preset, chunk generator, biome source, geology   │
├──────────────────────────────────────────────────────────────────────────┤
│ terrain.diffusion: Terrain Diffusion pipeline -> elevation + climate       │
├──────────────────────────────────────────────────────────────────────────┤
│ runtime: config, downloads, GPU runtimes, native library selection         │
└──────────────────────────────────────────────────────────────────────────┘
          ONNX Runtime 1.31 (CPU bundled; CUDA / ROCm installed at runtime)
```

The pipeline, the runtime management and the UI exist today. The world generator is next; see [ROADMAP.md](ROADMAP.md).

## Source layout

Loom's split source sets keep client-only code out of the server.

| Path | Contents |
|---|---|
| `src/main/java/.../diffusicraft` | Entrypoints: `Diffusicraft` (common), `DiffusicraftPreLaunch`, mixin plugin |
| `.../runtime` | Everything downloaded or native: `Resources` (facade), `DiffusicraftConfig`, `RuntimeManifest`, `RuntimeInstaller`, `Downloader`, `RemoteZip`, `SystemLibraries`, `NativeRuntime`, `Hardware`, `Platform`, `Job` |
| `.../terrain/diffusion` | The inference pipeline: `WorldPipeline`, `ModelBundle`, `OnnxModel`, `ModelAssets`, `EDMScheduler`, `LaplacianUtils`, `SyntheticMapFactory`, ... |
| `.../terrain/diffusion/tensor` | `WindowedTensor` (lazy, concurrent, cached tiling) and its stores |
| `src/client/java/.../client` | `DiffusicraftClient` (toasts); `screen/` (resource screen); `mixin/` (launch and Create World hooks); `compat/` (Mod Menu) |
| `src/main/resources/diffusicraft` | `model-manifest.json`, `runtime-manifest.json`: every download, pinned by SHA-256 |
| `src/test` | JUnit tests. Those tagged `models` need the real models and run only with `-PwithModels` |
| `src/gametest` | Fabric client game test that drives the real game through the screens |

## Startup

1. **`Diffusicraft.onInitialize`** (client and server) calls `Resources.init`. This:
   - loads `config/diffusicraft.json`;
   - resolves the shared storage directory;
   - scans for system CUDA libraries;
   - starts the hardware probe (`nvidia-smi`, `/sys/class/drm`) on a background thread;
   - registers `Resources::chooseRuntime` with `NativeRuntime`.

   Nothing here blocks on the network or on ONNX Runtime.
2. **Dedicated server:** `prepareServer` starts the model download if needed, and the CUDA download when the
   configuration asks for it. See [CONFIG.md](CONFIG.md#dedicated-servers).
3. **Client:** `GuiMixin` hooks `Gui.addInitialScreens`, the list vanilla uses for its onboarding screens. While
   `Resources.setupSuggestedAtLaunch()` is true, it appends `ResourceManagerScreen.atLaunch`. That is the case when
   the models are missing, or when a supported NVIDIA GPU has no runtime, unless the player chose the CPU or turned
   the screen off. *Later* or *Download recommended* continue to the title screen.
4. **Later access:**
   - `CreateWorldScreenMoreTabMixin` adds *Diffusicraft Resources...* under Game Rules, Experiments and Data Packs in
     the *More* tab.
   - `ModMenuIntegration` opens the same screen.

## Resource management (`runtime`)

`Resources` is the single entry point used by the screens, the server and, later, the world generator.

- **Status.** A snapshot of models and runtimes, recomputed on a background executor after every change. It uses
  metadata only: `.verified` and `.installed` markers, never re-hashing.
- **Jobs.** Downloads and installs run one at a time on a worker thread as `Job`s, with byte progress, cancel and
  error. They keep running when the screen closes. `DiffusicraftClient` shows a toast when they end.
- **Downloads.** `Downloader` handles resume, retries, SHA-256 checks and atomic moves. `RemoteZip` extracts single
  entries from remote ZIPs with range requests. Only the libraries one platform loads are fetched, not whole wheels
  and jars.
- **Licenses.** NVIDIA's libraries are fetched from NVIDIA's own PyPI packages, never redistributed. The player
  accepts their licenses in `LicenseConfirmScreen` before the download starts.

### Native library selection

ONNX Runtime's Java binding loads its native library once, from a directory chosen by the `onnxruntime.native.path`
system property. `NativeRuntime.activate()` runs before any ONNX Runtime class initialises (`OnnxModel.environment()`
calls it first):

1. `Resources.chooseRuntime()` picks the runtime. It uses the configured device, what is installed, whether the
   NVIDIA driver exists, and whether the GPU is new enough.
2. For a GPU runtime, every vendor library (cudart, cuBLAS, cuDNN, ...) is loaded with `System.load` by absolute
   path, dependencies first. When ONNX Runtime's CUDA provider later asks for `libcudnn.so.9` by name, the dynamic
   linker reuses the copy already loaded. This needs no `LD_LIBRARY_PATH` or `PATH` changes and works the same on
   Linux and Windows.
3. `onnxruntime.native.path` is set to the runtime's ONNX Runtime directory. For the CPU runtime it stays unset, so
   the bundled build loads.

The result is recorded as an `Activation`. If the configuration later asks for another runtime, the screen shows
*After restart*.

`OnnxModel` then tries execution providers in order: the requested one, any other GPU provider the library contains,
then the CPU. A provider that fails to create a session is skipped with a warning, so a broken GPU install degrades to
CPU instead of crashing world generation.

## Terrain pipeline

`WorldPipeline` turns a seed into an infinite map at 30 m per pixel. Three diffusion stages follow the Python
reference:

| Stage | Resolution | Model | Steps |
|---|---|---|---|
| Coarse | 1 px = 7.7 km | `coarse_model.onnx` | 20 DPM-Solver++ steps, conditioned on a synthetic climate map (`SyntheticMapFactory`) |
| Latent | 1 px = 240 m | `base_model.onnx` | 2 flow-matching steps, conditioned on the coarse map |
| Decoder | 1 px = 30 m | `decoder_model.onnx` | 1 flow-matching step producing an elevation residual |

**Elevation** is the Laplacian decode of the residual over the latent's low-frequency channel. **Climate** is the
coarse climate upsampled, with temperature corrected by a lapse rate regressed on the local elevation.

### Windowed tensors

Each stage is a `WindowedTensor`: an unbounded 2D tensor made of overlapping, weighted windows on a `TileGrid`.

- **Lazy evaluation.** Reading a rectangle computes only the windows that intersect it, recursively pulling the
  upstream windows they depend on. Overlaps are blended by accumulated weights, so window borders never show.
- **Concurrency.** Any number of world-generation threads can read at once. A reader claims missing windows under a
  short lock, computes them outside it, and waits on windows other threads claimed. Tensors form a DAG and claims are
  completed before waiting, so this cannot deadlock. The class javadoc has the full argument.
- **Batching.** Windows claimed together are batched into one inference call when the model has a dynamic batch
  dimension (coarse and latent: 4, decoder: 2).
- **Caching.** Each stage has a soft in-memory budget. Coarse, latent and residual windows can also persist through a
  `WindowStore` (`DiskWindowStore`: raw float files, atomic renames). Evicted windows are then reloaded instead of
  recomputed.
- **Determinism.** Diffusion noise is a function of world seed, stage and position (`GaussianNoisePatch`,
  `PortableRng`). The output therefore does not depend on request order or thread timing. Different providers can
  still round floating point differently.

### Running the models

- **Loading.** `ModelBundle` loads the three sessions once, straight from the model files, so the 2 GB base model is
  never copied onto the Java heap. Every world shares them; pipelines differ only by seed.
- **One run at a time.** A shared `Semaphore` allows one inference run at a time, because a run already fills every
  CPU core or the whole GPU. Running more at once would only add contention.
- **Leaving room for the game.** On CUDA, the memory arena is capped at 60% of video memory by default and grows only
  as needed. The game, LOD mods and shader packs keep the rest.

## World generation (planned)

The design is in [ROADMAP.md](ROADMAP.md). In short:

- **World preset.** A `diffusicraft:terrain_diffusion` world preset, made the default on the Create World screen, with
  three horizontal scales: Vanilla, Intermediate and 1:1 (Godlike).
- **Chunk generator.** It samples `WorldPipeline` per region and interpolates the 30 m grid to blocks. It adds
  procedural detail below the model's resolution, and carves rivers, cliffs and erosion features.
- **Biome source.** Biomes come from the climate (temperature, seasonality, precipitation) and from elevation and
  slope, not from vanilla's noise parameters. Snow caps appear only where it is both high and cold. Biomes from
  Biomes O' Plenty, BYG and TerraBlender are mapped into the same climate space.
- **Height.** Extreme modes need more than vanilla's 4064-block limit. This needs a dimension type and section
  storage that go beyond it, while staying compatible with Sodium, Distant Horizons and Voxy.
- **Geology.** Earth-like strata with depth-dependent ore and stone rarity, a magma layer above bedrock, and the
  Hollows cave systems.
- **SeedGen and LOD.** The terrain is a pure function of seed and position. Distant Horizons and Voxy SeedGen can
  therefore generate LODs straight from the pipeline without creating chunks.
