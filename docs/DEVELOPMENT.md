# Development

## Setup

You need **JDK 25** and Git. The Gradle wrapper (9.7.1) downloads itself.

```bash
git clone https://github.com/elpideus/Diffusicraft.git
cd Diffusicraft
./gradlew build
```

The first build downloads Minecraft 26.3 and sets it up. Minecraft 26.3 ships unobfuscated, so the code uses Mojang's
own names; there are no mappings to learn. IntelliJ IDEA and VS Code (with the Java extensions) both import the Gradle
project directly, and Loom generates run configurations.

## Running the game

```bash
./gradlew runClient
```

- **Directories.** The client runs in `run/` and the server (`./gradlew runServer`) in `run-server/`. Both are
  gitignored.
- **Dev modpack.** The client starts with the dev modpack: Sodium, Iris, Distant Horizons and SeedGen, Biomes O'
  Plenty with TerraBlender, C2ME, Lithium, Mod Menu and others. These are the compatibility targets. The list is in
  [`gradle/dev-mods.gradle`](../gradle/dev-mods.gradle), pinned by Modrinth version id.
- **Licensing.** Dev mods are `localRuntime` only: never compiled against, never bundled, never a dependency. Some
  are All Rights Reserved and can only ever be used this way. The optional integrations, Mod Menu and the Distant
  Horizons API (LGPL), are separate `compileOnly` dependencies: compiled against, never bundled.
- **Without the modpack.** Add `-PnoDevMods` to start without it, for example when a dev mod has not caught up with a
  Minecraft update.
- **Part of the modpack.** `-PdevMods=slug,slug` starts with only those mods, for example
  `-PdevMods=biomes-o-plenty,glitchcore,terrablender` to test the biome mods alone. It works for the client game test
  too.
- **Adding or updating a mod.** Find the version id on the mod's Modrinth *Versions* page (the last part of the
  version URL) and add `'<slug>': '<version id>'`. If it fails to start, add the mod's required dependencies too.

Models and runtimes go to the shared storage directory described in [CONFIG.md](CONFIG.md#storagepath), so a dev
client reuses what your normal game downloaded.

**Seeing the first-launch screen again:** point `storagePath` in `run/config/diffusicraft.json` at an empty
directory. Or start the game with `XDG_DATA_HOME` set to an empty directory (Linux).

## Tests

```bash
./gradlew test
```

The fast tests need no downloads. Tests tagged `models` need the real models or download for real, and run only with
`-PwithModels`. Gradle passes `-Pdiffusicraft.*` and `-Ponnxruntime.*` properties on to them as system properties:

```bash
# Install a GPU runtime from the real manifest, like the in-game button
./gradlew test -PwithModels --tests '*RuntimeInstallTest' -Pdiffusicraft.installRuntime=cuda
```

```bash
# Benchmark the pipeline (see PERFORMANCE.md)
./gradlew test -PwithModels --tests '*WorldPipelineBenchmarkTest' -Pdiffusicraft.device=CUDA
```

### Client game tests

The tests start the real game and save screenshots to `build/run/clientGameTest/screenshots/`.

- [`ResourceScreensGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/ResourceScreensGameTest.java)
  goes through the launch screen, Create World > More and the resource screen, and opens and cancels an install.
  Nothing is downloaded.
- [`WorldGenerationGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/WorldGenerationGameTest.java)
  creates a world with the default settings and checks that it is a Diffusicraft world. It saves a biome map, a height
  map, three views around the spawn and views of the tallest Grand Hollow, a lava sea nearby, a Grand Hollow of each
  cave biome, whose own blocks it checks for, and the nearest lake in a Grand Hollow, whose water it checks. It checks
  the LODs
  that Distant Horizons draws against the chunks generated at the spawn, ground and tree crowns, logs how much of each
  biome's LOD is under crowns, checks that far LODs run no terrain inference and saves maps of them
  (`diffusicraft-lod-near.png`, `diffusicraft-lod-far.png`). It needs the models, and skips itself without them. It
  generates on the CPU, so the GPU stays free; add `-Pdiffusicraft.device=CUDA` to use the GPU. Each view waits until
  the chunks the server sends are rendered: Fabric's own wait expects a square of chunks, but the server sends a
  circle, so above a render distance of 6 it never returns.
- [`TallDimensionGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/TallDimensionGameTest.java)
  plays in a 32,736-block dimension: blocks, light and heightmaps at both ends, the network in both directions, a
  column full of blocks, and saving. It logs memory and save times. See [HEIGHT.md](HEIGHT.md#tests).
- [`EarthWorldGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/EarthWorldGameTest.java)
  creates an Earth 1:1 world and goes to the highest ground within 123 km of the spawn. It checks that the chunks
  there, which are filled only up to their ground, hold all of it, saves two views of the summit and logs how long
  their chunks took. It needs the models.

```bash
./gradlew runClientGameTest -PnoDevMods
```

This opens a game window. On Linux, run it on a virtual display instead, so nothing appears on your desktop:

```bash
env -u WAYLAND_DISPLAY SDL_VIDEO_DRIVER=x11 xvfb-run -a -s "-screen 0 1600x900x24" ./gradlew runClientGameTest -PnoDevMods
```

- **Rendering.** Under Xvfb, Minecraft falls back from Vulkan to OpenGL on Mesa's software renderer. That is fine for
  screens, and slow but usable for the world views. With a GPU, gamescope's headless backend renders on the real
  driver, also without a window:

  ```bash
  env -u WAYLAND_DISPLAY -u DISPLAY gamescope --backend headless -W 1600 -H 900 -- ./gradlew runClientGameTest -PnoDevMods -Pdiffusicraft.device=CUDA
  ```
- **Fresh install.** Prefix the command with `XDG_DATA_HOME=$(mktemp -d)` to see what a new player sees.
- **Not part of `build`.** Run them yourself after changing anything in `client/screen`, `worldgen` or the mixins.

## Project conventions

**Code style**

- Tabs for indentation.
- Javadoc on every class and on methods whose contract is not obvious. Comments explain *why*, not what.
- Nullability is explicit with JSpecify's `@Nullable` (`Resources.@Nullable Status` for nested types).
- `runtime` and `terrain` never import client classes; they also run on dedicated servers and in plain JUnit tests.

**Behaviour**

- **No work on the render thread.** Nothing touches the network, hashes files or waits for the hardware probe on the
  render thread. The screens read snapshots (`Resources.status()`, `hardware().getNow(null)`) and refresh on tick.
- **Text.** All player-facing text goes through `assets/diffusicraft/lang/en_us.json`, keyed `diffusicraft.*`.
- **Sizes.** Download and disk sizes are decimal (`Formats.bytes`: 1 GB = 10⁹ bytes). GPU memory is binary
  (`Formats.memory`), the way cards are labelled.

**Downloads**

- **Pinning.** Every download is pinned (URL, size, SHA-256) in a manifest under `src/main/resources/diffusicraft/`
  and comes from the vendor's official distribution. See [RUNTIMES.md](RUNTIMES.md#for-developers-the-manifest).
- **Licenses.** Anything under a license other than MIT/Apache needs the player to accept it first.

**Commits** are small and focused, with an imperative subject line (`Add MIGraphX execution provider`) and a body
that explains why.

## Updating Minecraft

1. Bump the versions in `gradle.properties` from [fabricmc.net/develop](https://fabricmc.net/develop), and update
   `modmenu_version` and `distanthorizons_version`.
2. Check that the mixin targets still exist:
   - `Gui.addInitialScreens(List<Function<Runnable, Screen>>)`;
   - the constructor of `CreateWorldScreen$MoreTab` and its local `GridLayout.RowHelper`;
   - the height mixins in [HEIGHT.md](HEIGHT.md#mixin-targets).
3. Run `./gradlew build` and the client game test, and look at the screenshots.
4. Update the dev modpack, or run with `-PnoDevMods` until the mods catch up.

## Useful log lines

| Log line | Meaning |
|---|---|
| `Diffusicraft storage: ...` | Where models and runtimes live |
| `Hardware: ... GPUs: [...]` | What the probe found, including compute capability and driver |
| `Loaded model 'base' on CUDA in ... ms` | Which execution provider each model uses |
| `Could not load model ... with CUDA: ...` | Why a provider was skipped; the CPU is tried next |
| `The CUDA runtime is installed but ...` | Why an installed runtime was not used |
| `Distant Horizons LODs for ... come from the terrain` | Distant Horizons draws that level with Diffusicraft's generator |
