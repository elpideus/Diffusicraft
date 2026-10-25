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
  Horizons API (LGPL), are separate `compileOnly` dependencies: compiled against, never bundled. Mixins into other
  dev mods name their classes and never compile against them. Where they need a type of the mod, as those into Voxy
  (All Rights Reserved) do, Diffusicraft declares the few members it uses itself, in the `voxyStubs` source set,
  which the client compiles against and the jar never holds.
- **Without the modpack.** Add `-PnoDevMods` to start without it, for example when a dev mod has not caught up with a
  Minecraft update.
- **Part of the modpack.** `-PdevMods=slug,slug` starts with only those mods, for example
  `-PdevMods=biomes-o-plenty,glitchcore,terrablender` to test the biome mods alone. It works for the client game test
  too.
- **Mods outside the modpack.** Some compatibility targets are not in the maintainer's pack and load only when named
  in `-PdevMods`, for example `-PdevMods=techreborn` to see the geology place the ores of a tech mod, or
  `-PdevMods=voxy,sodium` for Voxy, which needs Sodium. They are listed under `optionalDevMods` in the same file.
  Sulkan (`-PdevMods=sodium,sulkan`), shaders for Minecraft's Vulkan backend, is one of them.
- **Shader packs.** Named in `-PdevMods` as well, the packs under `devShaderPacks`, Bliss
  (`-PdevMods=sodium,iris,bliss-shader`) and Complementary Unbound (`complementary-unbound`), go into the client's
  `shaderpacks` folder as `<slug>.zip`. Like the mods, they are pinned by Modrinth version id and never leave the dev
  runtime.
- **On Vulkan.** The full modpack does not start on Vulkan. Iris is in it, so Distant Horizons switches itself to
  OpenGL and then stops the game (see [COMPATIBILITY.md](COMPATIBILITY.md#vulkan)). Pick mods without Iris, such as
  `-PdevMods=sodium,distanthorizons` or `-PdevMods=sodium,sulkan`. The game stops before its menus, so to go back to
  OpenGL set `preferredGraphicsBackend:"default"` in `run/options.txt`.
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

```bash
# Serve the relief map of a synthetic level, to work on the page in a browser (see MAP.md)
./gradlew test --tests '*MapPagePreview' -Pdiffusicraft.mapPreview=/tmp/map-url
```

### Client game tests

The tests start the real game and save screenshots to `build/run/clientGameTest/screenshots/`.

- [`ResourceScreensGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/ResourceScreensGameTest.java)
  goes through the launch screen, Create World > More and the resource screen, and opens and cancels an install.
  Nothing is downloaded.
- [`WorldSizeGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/WorldSizeGameTest.java) picks
  sizes with the slider in Create World's World tab, by mouse and by the arrow keys, and checks the world type follows.
  With Distant Horizons or Voxy installed and the models downloaded, it checks that Create on Ultra asks first. Nothing
  is created; it needs no models.
- [`WorldGenerationGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/WorldGenerationGameTest.java)
  creates a world with the default settings and checks that it is a Diffusicraft world. It saves a biome map, a height
  map, three views around the spawn, a view into a pit dug beside the deepest soil near the spawn, whose soil it checks
  goes deeper than vanilla's dirt with no sand or gravel over open space, and views of the tallest Grand Hollow, a lava
  sea nearby, a Grand Hollow of each cave biome, whose own blocks it checks for, and the nearest lake in a Grand Hollow,
  whose water it checks. It checks the LODs that Distant Horizons draws against the chunks generated at the spawn,
  ground and tree crowns, logs how much of each biome's LOD is under crowns, checks that far LODs run no terrain
  inference and saves maps of them (`diffusicraft-lod-near.png`, `diffusicraft-lod-far.png`). It needs the models, and
  skips itself without them. It generates on the CPU, so the GPU stays free; add `-Pdiffusicraft.device=CUDA` to use the
  GPU. Each view waits until the chunks the server sends are rendered: Fabric's own wait expects a square of chunks, but
  the server sends a circle, so above a render distance of 6 it never returns. Its world's seed is chosen for a coastal
  spawn with cliffs and Grand Hollows near it; `-Pdiffusicraft.seed=<seed>` picks another, to see the biomes of
  other climates.
- [`TallDimensionGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/TallDimensionGameTest.java)
  plays in a 32,736-block dimension: blocks, light and heightmaps at both ends, the network in both directions, a
  column full of blocks, and saving. Then it plays the same on a dedicated server over a real connection. It logs
  memory and save times. See [HEIGHT.md](HEIGHT.md#tests).
- [`UltraWorldGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/UltraWorldGameTest.java)
  creates an Ultra (1:1) world and goes to the highest ground within 123 km of the spawn. It checks that the chunks
  there, which are filled only up to their ground, hold all of it, saves two views of the summit and logs how long
  their chunks took. With Distant Horizons or Voxy in the run (`-PdevMods=distanthorizons`), it also checks that the
  chat warned once on arrival that they cannot hold the level whole, and saves a view of the chat. With Distant
  Horizons the game has hung as the test closed the world, after all its checks: the render thread waits for the
  integrated server to stop while the server waits at Fabric's test barrier. Stop the game then. It needs the models.
- [`WaterfallsGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/WaterfallsGameTest.java) creates
  an Ultra (1:1) world and checks the falls of its rivers, its only waterfalls, from the rivers' own columns: the
  highest step of their water within 4 km of the spawn, 3 blocks or more, must stand as a curtain over it, and around it
  and along the steepest straight reach without a fall of more than 8 blocks the river must keep its water within its
  banks while the chunks tick. It logs how far apart the water of the channels' neighbouring columns lies, and saves
  views of the falls (`diffusicraft-world-river-fall-front.png`, `-side.png`) and of the reach
  (`diffusicraft-world-river-reach-front.png`, `-side.png`). The spawn of the test's seed has no river within 100 km, so
  for that seed it goes to a steep river 100 km away; `-Pdiffusicraft.riverFallsAt=x,z` searches 256 blocks around
  another place. It needs the models.
- [`ReliefMapGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/ReliefMapGameTest.java)
  creates a Medium world and checks its relief map without opening a browser: the player's link, the page, the levels
  and the tiles around the player, coarse and in detail, whose colours it saves
  (`diffusicraft-map-coarse.png`, `-fine.png`). `-Pdiffusicraft.mapLink=<file>` keeps the world open for a browser.
  See [MAP.md](MAP.md#working-on-the-page). It needs the models.
- [`TreesGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/TreesGameTest.java) plants three of
  each of Diffusicraft's trees on a superflat world, the giants too, in the biome each grows in, and saves a view of
  each (`diffusicraft-world-tree-<name>.png`). The giants grow twice, at their full height and as in a Vanilla world
  (`tree-<name>-vanilla`), and the ancient oaks and kapoks a chunk at a time, as their structures' pieces are written
  in a world. It fails if a tree never grows on open grass. It needs no models.
- [`BiomesGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/BiomesGameTest.java) lays out a
  plot of each of Diffusicraft's surface biomes on a superflat world, grows its plants there as world generation
  would, and saves a view of each (`diffusicraft-world-biome-<name>.png`): how dense its woods are, how its trees mix
  and what colour its grass and leaves take. The ground stays superflat grass. It needs no models.
- [`VoxyGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/VoxyGameTest.java) runs only with
  Voxy installed (`-PdevMods=voxy,sodium`). It creates the world of `WorldGenerationGameTest` at a render distance of
  6 chunks, so that all but the nearest 100 blocks of its views are Voxy's far terrain, and saves three views from
  above the spawn (`diffusicraft-voxy-<view>.png`), each once Voxy has stopped asking for sections. It fails if no
  section was filled from the terrain or one could not be. It needs the models, and takes about 11 minutes on CUDA.
- [`VoxyServerGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/VoxyServerGameTest.java) does
  the same on a dedicated server, joined over a real connection, where Voxy's far terrain comes from the grids the
  server sends. It saves two views (`diffusicraft-voxy-server-<view>.png`), fails if the server offered no far terrain,
  if no section was filled from it or if one could not be, and logs how many bytes a grid takes on the wire. It needs
  the models. `-Pdiffusicraft.gametests=Voxy` runs both Voxy tests, `VoxyServer` this one alone.
- [`DistantHorizonsServerGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/DistantHorizonsServerGameTest.java)
  runs only when named and with Distant Horizons installed, against a dedicated server started apart from it (see
  below): Distant Horizons keeps one world per JVM, which a client joining a server in its own JVM would take over.
  It joins at a render distance of 8 chunks and waits until the client holds LODs, sent by the server, at 24 points
  on rings 320 to 1280 blocks round the spawn. It saves two views from above the spawn
  (`diffusicraft-dh-server-<view>.png`) before it fails on any point it does not hold, so a failed run still shows
  what the client drew, checks in the server's log that the server gave Distant Horizons Diffusicraft's generator,
  and stops the server. The server needs the models.
- [`ShadersGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/ShadersGameTest.java) runs only
  when named and with Iris installed (`-PdevMods=sodium,iris`). It turns on a small shader pack of its own
  (`src/gametest/resources/diffusicraft-gametest/shaderpack`), which casts shadows and draws Distant Horizons' far
  terrain, checks in a superflat world that Iris runs it and draws its shadow pass, and saves a view
  (`diffusicraft-shaders-flat.png`). The pack stays on for the tests after it, so
  `-Pdiffusicraft.gametests=Shaders,TallDimension` plays the tall dimension under shaders. It needs no models.
  `-Pdiffusicraft.shaderPack=bliss-shader.zip` turns on a real pack instead, from the run's `shaderpacks` folder,
  where `-PdevMods=sodium,iris,bliss-shader` puts Bliss (see [Running the game](#running-the-game)). Gradle also
  writes it into Iris's config before the game starts, so every test runs under it, `ShadersGameTest` or not. Use
  that for `DistantHorizonsServerGameTest` and leave `Shaders` out: with Distant Horizons installed, closing the
  superflat world once hung the game, Fabric's test threads waiting on the integrated server while it waited on them.

```bash
./gradlew runClientGameTest -PnoDevMods
```

This opens a game window. On Linux, run it on a virtual display instead, so nothing appears on your desktop:

```bash
env -u WAYLAND_DISPLAY SDL_VIDEO_DRIVER=x11 xvfb-run -a -s "-screen 0 1600x900x24" ./gradlew runClientGameTest -PnoDevMods
```

- **Rendering.** Under Xvfb, Minecraft draws through OpenGL on Mesa's software renderer. That is fine for
  screens, and slow but usable for the world views. With a GPU, gamescope's headless backend renders on the real
  driver, also without a window:

  ```bash
  env -u WAYLAND_DISPLAY -u DISPLAY gamescope --backend headless -W 1600 -H 900 -- ./gradlew runClientGameTest -PnoDevMods -Pdiffusicraft.device=CUDA
  ```

  SDL's offscreen driver (`SDL_VIDEO_DRIVER=offscreen`, no display at all) renders OpenGL on the real driver too.
- **Vulkan.** The game tests reset the options, whose default tries OpenGL first, so they run on OpenGL unless
  `-Pdiffusicraft.graphicsBackend=vulkan` forces Vulkan. Xvfb cannot show Vulkan at all: it has no DRI3. SDL's
  offscreen driver can, through a headless surface, but only on Mesa's drivers, not NVIDIA's, so pick Mesa's, here
  for an Intel integrated GPU:

  ```bash
  env -u WAYLAND_DISPLAY -u DISPLAY SDL_VIDEO_DRIVER=offscreen VK_LOADER_DRIVERS_SELECT='*intel_icd*' ./gradlew runClientGameTest -PnoDevMods -Pdiffusicraft.graphicsBackend=vulkan
  ```

  Under gamescope's headless backend NVIDIA's driver does start Vulkan, but Minecraft loses the device the first
  time it remakes its swapchain, on the title screen, before any world. So NVIDIA on Vulkan can be checked only
  by playing.
- **Fresh install.** Prefix the command with `XDG_DATA_HOME=$(mktemp -d)` to see what a new player sees.
- **Some of them.** `-Pdiffusicraft.gametests=TallDimension,Ultra` runs only the tests whose class names start with
  one of those. The tall dimension, the trees and the biomes each take under a minute.
- **Against a server of their own.** `DistantHorizonsServerGameTest` joins a server run by Gradle in a JVM of its
  own. Start it first; each start makes a new world on the test seed in `build/run/gameTestServer`, offline, with the
  test's player (`Player0`) as an operator. `-Pdiffusicraft.device` and `-Pdiffusicraft.gpuMemoryLimitMb` go into its
  Diffusicraft config, and the dev mods named go on it as on the client:

  ```bash
  ./gradlew runGameTestServer -PdevMods=distanthorizons -Pdiffusicraft.testServerPort=25570 -Pdiffusicraft.device=CUDA
  ```

  Once it logs `Done`, run the test with the same port, in another terminal:

  ```bash
  env -u WAYLAND_DISPLAY SDL_VIDEO_DRIVER=x11 xvfb-run -a -s "-screen 0 1600x900x24" ./gradlew runClientGameTest -PdevMods=distanthorizons -Pdiffusicraft.gametests=DistantHorizonsServer -Pdiffusicraft.testServerPort=25570
  ```

  The test stops the server when it is done. If it fails before it joins, stop the server yourself with Ctrl+C in
  its terminal. To run it with Distant Horizons SeedGen too (`-PdevMods=distanthorizons,distant-horizons-seedgen` on
  both), set `"shareSeedWithClients": false` in `build/run/gameTestServer/config/seedgen.json` after the server's
  first start, or the client draws the far terrain itself instead of taking the server's (see
  [COMPATIBILITY.md](COMPATIBILITY.md#distant-horizons)).
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
