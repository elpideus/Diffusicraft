# LOD engines: Distant Horizons, Voxy, and what Diffusicraft should borrow

Research for Diffusicraft's own level-of-detail (LOD) system, written in October 2026. It describes how Distant
Horizons (DH) and Voxy build, store, update and draw their far terrain, especially for land that was never generated
as chunks, surveys other projects and papers, and ends with a recommendation ([section 6](#6-recommendation)).

The sources were read, not built or run. Facts come with file paths, class names, commits and URLs. Anything marked
**(estimate)** is our arithmetic; **[U]** marks a user's report or a third party's claim rather than a project's own
statement. Voxy is All Rights Reserved: it is described in our own words, and none of its code is reproduced here.

## Contents

1. [Sources](#1-sources)
2. [Distant Horizons](#2-distant-horizons)
3. [Voxy](#3-voxy)
4. [Side by side](#4-side-by-side)
5. [Other projects and papers](#5-other-projects-and-papers)
6. [Recommendation](#6-recommendation)
7. [Not found](#7-not-found)

## Summary

- **Neither engine can hold Diffusicraft's heights.** DH packs the bottom and height of each run of blocks into
  12 bits, so it describes 4,095 blocks above the level's floor and silently wraps taller columns in release builds.
  Voxy packs a section's y into 8 bits, so it covers y = −4096 to 4095. Both are confirmed in source, and both
  match what Diffusicraft already works around ([HEIGHT.md](../HEIGHT.md)).
- **Two opposite data models.** DH is a 2.5D column store: sections of 64 × 64 columns, each a list of vertical runs,
  in one SQLite file per dimension with Zstd. Voxy is a 3D voxel store: sections of 32³ 64-bit voxels at five levels,
  in RocksDB with Zstd level 1.
- **Land never generated.** Since 3.3, DH has a rough surface generator that point-samples vanilla's density for
  1,024 columns per section at any level, plus real chunk generation (dense, 4 × 4 chunks per task) for the finest
  level, plus an API for "N-sized" generators like Diffusicraft's. Voxy generates nothing. It imports region files and
  DH databases, takes chunks Chunky generates, and leaves the rest to third-party feeders that reach into its
  internals.
- **Real chunks.** DH builds LODs on the server side, when chunks load and save, and has no block-change hook. Voxy
  builds them on the client, as Sodium adds and removes chunks, and has no server side at all.
- **Drawing.**
  - DH: one GPU buffer and one draw call per section, OpenGL 3.3 or Mojang's API, CPU frustum culling only, and a
    dithered near discard plus a cross-fade with vanilla.
  - Voxy: GPU-driven, with 8-byte quads pulled in the shader, HiZ occlusion, three multi-draw-indirect-count calls a
    frame and OpenGL 4.6. It keeps clear of Sodium with a stencil and a depth buffer of the sections Sodium drew.
- **Recommendation.**
  - **Data:** keep DH's column model with 16-bit heights and 2D keys. Generate far tiles straight from
    `LodSampler` and keep them only in a cache that may be dropped; store only real-chunk tiles. The server streams
    both.
  - **Drawing:** go through Mojang's renderer API, like Sodium on 26.3, with Voxy's compact quads and pooled buffers.
    Take Voxy's seam technique, and add a heightfield clipmap for the far field.
  - **Shader packs:** Iris 26.3's new pipeline API can shade such a renderer with a pack's own terrain programs
    without the pack opting in; the separate shader research should test it first (section 6.7).
  - **Effort:** a first complete version is about 18 to 30 weeks for one developer (section 6.10).

## 1. Sources

Read at these commits (full hashes so the line numbers below can be found again):

- **DH core**, LGPL-3.0: gitlab.com/distant-horizons-team/distant-horizons-core at
  `c45c13f67daf139a6d163dbc09bcffa207fb54e0` (2026-10-24; DH 3.3.5-dev, API 7.3.0, network protocol 16).
- **DH, Minecraft side**, LGPL-3.0: gitlab.com/distant-horizons-team/distant-horizons at
  `909282fff23da5c5aa29b14db936f739549cd358` (2026-10-24).
- **Voxy**, All Rights Reserved: github.com/MCRcortex/voxy, branch `dev`, at
  `5bf33350a1cc7bb7e2b5d947b236c56d878b8a7d` (2026-10-19; 0.2.20-beta).
- **Nvidium**, LGPL-3.0: github.com/MCRcortex/nvidium, `dev` at `f2028b2ba9a5dc95d69e73a9eaeadcb447367d7d`
  (2024-08-20) and `26.3` at `27ceb7299e8a8de9ccca69bb4700909e600ea10f` (2026-09-23).
- **Bobby**, LGPL-3.0: github.com/Johni0702/bobby at `241f973106ee4e5552a5950d0a12433c07921c93` (2026-10-06; 5.2.16).
- **Sodium**, PolyForm Shield 1.0.0: github.com/CaffeineMC/sodium, `dev`, at
  `148151cf25c56f8b670986b348dcc589278c867e` (2026-10-16; 0.9.3-beta.1).
- **FarPlaneTwo**, MIT with an attribution clause: github.com/PorkStudios/FarPlaneTwo, `dev/abstract-everything` at
  `58308ae30f19b532b5ab4a5dcbde92c1d4c5d0cd` (2026-02-19) and `master` at `10156e5f8a434fcaa0e6e8561828a9144d5cd698`.
- **Iris**, LGPL-3.0: github.com/IrisShaders/Iris, `26.3` at `671a5c87747d33983d7ed659461a64f46dfcb1f0` (its API only).
- **The web**: the DH wiki, javadoc and release notes, Modrinth changelogs, GitLab and GitHub issues, and third-party
  mod pages, with URLs where they are used.

Not reachable: Reddit, YouTube transcripts, and the public mirror of the DH Discord (AnswerOverflow, behind a bot
check); only titles and search snippets were seen. No talks or design write-ups by DH's author (James Seibel) or
Voxy's (MCRcortex) were found; their reasoning comes from issue comments and release notes.

## 2. Distant Horizons

DH 3.3.5-dev (API 7.3.0, network protocol 16). Core paths below are relative to
`core/src/main/java/com/seibel/distanthorizons/core/` in distant-horizons-core and API paths to
`api/src/main/java/com/seibel/distanthorizons/api/`; Minecraft-side paths (`common/…`, `fabric/…`) are in the
distant-horizons repository. DH is LGPL-3.0, so short identifiers and constants are quoted freely.

### 2.1 Data model

DH is a **2.5D column store**. A *section* is always 64 × 64 columns; each column is a top-down list of vertical runs.

**Full datapoint** (`util/FullDataPointUtil.java:50-69`), one `long` per run:

| Bits | Width | Field |
|---|---|---|
| 0–30 | 31 | id into the section's palette of (biome, block state) pairs |
| 32–43 | 12 | height of the run in blocks |
| 44–55 | 12 | bottom of the run, relative to the level's minimum y |
| 56–59 | 4 | sky light |
| 60–63 | 4 | block light |

- Runs split only on a block or biome change, never on light; the light stored is that of the block above the run's
  top (`dataObjects/transformers/LodDataBuilder.java:200-201`). Air runs are stored explicitly, so a column has no
  gaps. There is no cap on runs per column in the full data.
- **Height limit.** 12 bits each for bottom and height: 4,095 blocks above the level's floor. Validation runs only in
  dev builds (`RUN_VALIDATION = ModInfo.IS_DEV_BUILD`) and its bound, `RenderDataPointUtil.MAX_WORLD_Y_SIZE = 6095`, is
  larger than the field anyway, so release builds wrap taller values silently. This is the cap Diffusicraft already
  works around (`mixin.dh.ChunkWrapperMixin`, `compat.dh`, [HEIGHT.md](../HEIGHT.md)).
- **Palette** (`dataObjects/fullData/FullDataPointIdMap.java`). Each section has its own list of
  `BlockBiomeWrapperPair`s, serialised as strings `"<biome>_DH-BSW_<block>_STATE_{props}"`. Merging two sections
  remaps ids; after every merge unused ids are pruned and renumbered (a migration, `sqlScripts/0100`, once deleted
  every coarse level to undo palette bloat).
- **Per column**: one byte of *generation step* (`EDhApiWorldGenerationStep`: `DOWN_SAMPLED` −1, `EMPTY` 0, …,
  `SURFACE` 5, …, `FEATURES` 8, `LIGHT` 9) and one byte of compression mode. Per section: timestamps and three
  propagation flags (`applyToParent`, `applyToChildren`, `regenerateLeaf`).

**Render datapoint** (`util/RenderDataPointUtil.java:79-105`), built from full data when a section is loaded for
drawing: 4-bit material class (`EDhApiBlockMaterial`: leaves, stone, water, …, used by shader packs), 4-bit alpha,
24-bit RGB colour (the block's colour already tinted by biome), 12-bit top, 12-bit bottom, 4-bit block and sky light.
Columns are stored with a fixed stride of `maxVerticalSliceCount` runs. *Vertical quality* caps runs per column by
detail level (`EDhApiVerticalQuality`; the default `MEDIUM` allows 6, 6, 6, then 4 for detail 3–9 and 1 beyond);
longer columns are reduced by merging their smallest runs (`util/RenderDataPointReducingList`). The conversion
(`dataObjects/transformers/FullDataToRenderDataTransformer`) also drops underground runs with no sky light below
`caveCullingHeight` (60), turns water under ice into ice, and lets snow layers and flowers tint the block below.

**Detail levels** (`pos/DhSectionPos.java`, `file/fullDatafile/V2/FullDataSourceProviderV2.java:73-86`). Data detail
*d* means a column 2^d blocks wide, so a section is 64 · 2^d blocks:

| d | Column | Section | Note |
|---|---|---|---|
| 0 | 1 | 64 (4 × 4 chunks) | leaf; chunks are written only here |
| 3 | 8 | 512 (one region) | |
| 6 | 64 | 4,096 | top of downward filling (`HIGHEST_DOWNSAMPLE_DETAIL_LEVEL`) |
| 8 | 256 | 16,384 | root (`ROOT_SECTION_DETAIL_LEVEL = 6 + 8`); "at 10+ the LOD application process breaks down" |

`DhSectionPos` packs a section into a `long`: an 8-bit section detail (*d* + 6), then 28-bit signed x and z.

**Downsampling** (`dataObjects/fullData/sources/FullDataSourceV2.updateFromOneBelowDetailLevel` and
`mergeInputTwoByTwoDataColumn`, lines 591–950). Each parent column comes from 2 × 2 child columns:

1. collect every run boundary of the four children and cut the column into slices between them;
2. in each slice, sample each child at the slice's middle y;
3. **the most common non-air id wins** (`determineMostCommonValueInColumnSlice`, "always overwrite air to prevent holes
   in hollow structures"), so one solid child beats three air children; ties go to the first child; the biome comes
   with the id;
4. light is the average over the children that hold the winning id only, so the light of outvoted water does not bleed
   onto the sand under it;
5. equal neighbouring slices are joined into runs;
6. the parent's generation step is the **minimum** of its children's, "because we don't want to accidentally record
   that something was generated when it wasn't".

With the default world compression `VISUALLY_EQUAL`, runs hidden on all four sides by opaque neighbours inside the same
section are then removed (`FullDataOcclusionCuller`, "expected compression ratio 0.7"). Sky light is baked only on leaf
sections, by DH's own column flood fill (`generation/DhLightingEngine.bakeDataSourceSkyLight`, which stops at the
section border); coarser levels inherit it through the averaging above.

### 2.2 Distant generation

Generation is driven by two settings (`config/Config.java:1440-1500`):

- the **generator plan** (`EDhApiGeneratorPlan`, API 7.1): `SURFACE_THEN_CHUNKS` (default), `SURFACE_ONLY`,
  `CHUNKS_ONLY` or `DISABLED`;
- the **chunk generator mode** (`EDhApiDistantGeneratorMode`), which matters only where real chunks are generated:
  `PRE_EXISTING_ONLY`, `SURFACE`, `FEATURES` (default) or `INTERNAL_SERVER`, "in order of fastest to slowest". The
  older `BIOME_ONLY` and `BIOME_ONLY_SIMULATE_HEIGHT` modes survive only as translation strings in `en_us.json`; the
  rough surface generator now plays their part.

The built-in generator, `generation/DhWorldGenerator`, can produce any level from 0 to 12 when the plan includes
surface generation. It generates real chunks only for leaves (detail 0) and uses the rough surface generator for
everything coarser.

**Rough surface generator** (`common/wrappers/worldGeneration/DhRoughSurfaceGenerator.java`), per 64 × 64 section at any
level:

1. find the surface of **1 column in 4** (32 × 32 = 1,024 searches) at a single point per cell: march down from the
   world top 8 blocks at a time testing vanilla's final density (`RoughSurfaceDensitySampler` compiles
   `randomState.getSampler` with DH caches on 26.3), then binary-search the last interval. Superflat worlds and worlds
   with a ceiling use one averaged height;
2. raise underwater spikes deeper than 8 blocks below their neighbours' median;
3. interpolate the other three columns bilinearly;
4. per column: water up to sea level (ice in cold biomes), the biome from the biome source at the surface, and the
   biome's most common top block, learned the first time each biome is seen by generating a real 4 × 4-chunk batch
   around it in `SURFACE` mode; snow on cold surfaces;
5. light computed directly (sky 15 minus water depth), and the column recorded as `SURFACE`. One solid run per column:
   no caves, overhangs, trees or structures.

Its cost is the same for a 64-block leaf as for a 16,384-block root, which is what makes far levels cheap. Its own
comments time three search strategies on 24 cores at a 128-chunk radius in a Terralith world: marching "15.9 million //
37 sec" (in use; "the slowest (especially for extended height worlds)"), binary search "3.3 million // 23 sec", and
binary search with a marching fallback "5.3 million // 27 sec" (lines 772-801; the unit of "million" is not stated).

**Chunk generation** (`common/wrappers/worldGeneration/DhChunkGenerator.java`), one leaf section (4 × 4 chunks) per
task:

| Mode | Steps run | Skipped | Light |
|---|---|---|---|
| `PRE_EXISTING_ONLY` | read region files; `BIOMES` for missing chunks | all generation | from NBT, else DH's |
| `SURFACE` | structure starts and references, `BIOMES`, `TERRAIN` (noise + surface before 26.3) | features | DH's |
| `FEATURES` | as above plus `FEATURES` (`applyBiomeDecoration`) | carvers on ≤ 26.2 | DH's |
| `INTERNAL_SERVER` | the server's own chunk system to `FULL`, with tickets; chunks are saved | nothing | DH's |

- DH avoids vanilla's chunk system: it makes its own `ProtoChunk`s (never added to `ChunkMap`, never saved), reads
  region files directly (`RegionFileStorageExternalCache`, or `IOWorker` when C2ME is present), runs the steps in a
  fake `DhLitWorldGenRegion` (ticks to a black hole, no entities, block entities only for spawners), allocates empty
  proto chunks 8 chunks around the batch for structure references (400 per 16-chunk batch), and replaces vanilla's
  light engine with a no-op (`DummyLightEngine`). Block light comes from DH's own BFS, "roughly based on Starlight".
  A comment records that a wider border "caused extreme server lag".
- Every column built from a chunk is recorded as `LIGHT`, whatever the mode (`LodDataBuilder.java:263`).
- Structure starts are serialised by one global lock; feature placement swallows `ConcurrentModificationException`s.

**Batching, threads and priority.**

- Leaves are generated **densely**: every chunk of every leaf in range. Coarse levels in `CHUNKS_ONLY` exist only by
  merging leaves upward; with surface generation they are generated directly at their own level.
- `SURFACE_THEN_CHUNKS` works in two passes: rough sections are pushed down to children as `DOWN_SAMPLED` columns
  (`FullDataUpdatePropagatorV2`, only from parents below section detail 12), leaves touched that way are flagged
  `Regenerate`, and a regeneration queue later generates them as real chunks, nearest first, once no coarse request
  waits. Real data then replaces rough data by the merge rule in section 2.3.
- One "World Gen Queue" thread per level starts tasks while fewer than `numberOfThreads + 1` run, choosing the one with
  the least Chebyshev distance to the player divided by its section detail level, a mild preference for coarse
  sections (`generation/queues/WorldGenerationQueue.java:288-368`). The render tree asks for each section at the detail
  it will draw, nearest first (`render/QuadTree/LodQuadTree.java:478-552`).
- One shared pool (`util/threading/PriorityTaskPicker`) runs at most `numberOfThreads` tasks across all of DH's
  executors (IO, render loading, LOD building, propagation, world generation, network compression). The default is
  half the logical cores; `threadRunTimeRatio` (default 1.0) can make threads sleep between tasks. World generation
  pauses while the camera moves faster than 20 blocks/s, while more than 1,000 chunk updates wait, and while the
  integrated server averages 25 ms per tick or more (or 50 ms at the 99th percentile).

**Costs.** DH states no cost per chunk or per section for any mode; it measures them at run time (a rolling average of
milliseconds per chunk, shown as chunks per second on F3). Section 2.7 has the figures users and the DH team publish.

**What makes each fast or slow.** `PRE_EXISTING_ONLY` costs file reads and NBT parsing. `SURFACE` and `FEATURES` cost
vanilla's full density evaluation for every block of every leaf chunk, plus 400 proto chunks per batch, and `FEATURES`
adds decoration. `INTERNAL_SERVER` adds the server's chunk system, ticket handling on the main thread and saving ("less
efficient unless a mod like C2ME is present"). The rough surface generator is fast because it point-samples 1,024
columns per section regardless of the section's size and never builds a chunk; it is slow only where marching from a
tall world's top costs many density samples.

**World-generator override API** (`api/interfaces/override/worldGenerator/IDhApiWorldGenerator.java`):

- registered per level with `DhApi.worldGenOverrides.registerWorldGeneratorOverride`, one per level, chosen by priority
  (DH's own has −1, the default for others is 10; Diffusicraft uses 20);
- the generator declares its finest and coarsest data detail (`getSmallestDataDetailLevel`,
  `getLargestDataDetailLevel`); coarser requests are split into quadrants until they fit;
- `getReturnType()` picks one of three methods: `generateChunks` (`VANILLA_CHUNKS`: hand back `ChunkAccess`es, which DH
  turns into LODs as above), `generateApiChunks` (`API_CHUNKS`: `DhApiChunk`s of `DhApiTerrainDataPoint` columns), or
  `generateLod` (`API_DATA_SOURCES`, API 4.0: fill a pooled 64 × 64 `IDhApiFullDataSource` at any detail level);
- it is called from the single queue thread with the section's corner chunk, its position and data detail, the
  configured mode and DH's world-generation executor, and must return a future; it effectively gets up to
  `numberOfThreads` workers. The pooled data source must not be kept after the future completes;
- results go through the same merge as everything else (section 2.3). `generateLod` columns take the generation step
  the caller passes; the deprecated three-argument `setApiDataPointColumn` defaults to `SURFACE`
  (`api/objects/data/IDhApiFullDataSource.java:20-28`). Sky light supplied at detail 0 is replaced by DH's own bake;
- heights are packed into the same 12-bit fields, with no clamp on the API path.

> **Aside for the current integration** (not part of the new design): `compat.dh.DiffusicraftLodGenerator` calls the
> deprecated three-argument setter, so its columns are recorded as `SURFACE`. At this DH commit, under the default
> `SURFACE_THEN_CHUNKS` plan, a leaf needs `FEATURES`
> (`file/fullDatafile/GeneratedFullDataSourceProvider.java:368-382`), so Diffusicraft's leaves count as unfinished and
> may be queued again. DH 3.3.4, which Diffusicraft compiles against, ships API 7.2.0 (the four-argument setter came in
> 7.1.0 with DH 3.3.0, per the release notes), so passing `FEATURES` or `LIGHT` is possible; whether 3.3.4 really
> requeues the leaves was not tested.

### 2.3 Real chunks

- **Hooks.** On the server side (which includes singleplayer): Fabric's `ServerChunkEvents.CHUNK_LOAD` and a mixin at
  the return of `ChunkMap.save` (`fabric/mixins/server/MixinChunkMap`), which skips unsaved, upgrading, unlit and proto
  chunks. There is **no block-change or light hook** on the server, so in singleplayer an edit reaches the LODs when the
  chunk is next saved. A client connected to a dedicated server also listens to `ClientChunkEvents.CHUNK_LOAD`, block
  break and place callbacks, and the chunk-light packet, and drops those updates where the server supplies LODs
  (`level/DhClientLevel.shouldProcessChunkUpdate`).
- **Queue** (`api/internal/chunkUpdating/ChunkUpdateQueueManager.java`): one thread per level, two stages of
  nearest-first priority queues capped at 1,000 × threads × players, evicting the farthest when full. The first stage
  skips chunks whose block/biome hash (every second block plus the surface) matches the `ChunkHash` table; the second
  waits 250 ms so neighbours can arrive, then hands the chunk to the "LOD Builder" executor.
- **Building** (`LodDataBuilder.createFromChunk`): DH bakes block light with its own engine over the 3 × 3 chunks, then
  walks each column down from the higher of its heightmaps to the lowest non-empty section, closing a run at each block
  or biome change. Flowers, snow layers and similar blocks become one-block runs so they tint the top of the column.
  Water, leaves and glass are ordinary runs here; the render transform treats them specially.
- **Write path.** The new leaf data merges into a 1-second write-coalescing cache per section ("merging writes
  together in memory significantly improves throughput"). On flush, `FullDataUpdaterV2` locks the section, **reads the
  whole row from SQLite**, merges, re-bakes the section's sky light, compresses and upserts it, then tells the renderer,
  which rebuilds the section and its four neighbours.
- **Up the levels.** The saved row carries `ApplyToParent = 1`. A propagation thread per level wakes every 250 ms,
  takes up to 10 × threads flagged rows nearest the player and merges each into its parent, stopping at the first level
  that does not change.
- **Generated versus real.** At the same level an incoming column replaces the stored one only if its generation step is
  at least the stored one's, and `DOWN_SAMPLED` input only fills `EMPTY` or `DOWN_SAMPLED` columns
  (`FullDataSourceV2.updateFromSameDetailLevel`). Real chunks are always `LIGHT` (9), the highest step, so they replace
  rough (`SURFACE`) and down-sampled data and refresh older real data. Nothing distinguishes DH's own generated chunks
  from chunks a player visited: both are `LIGHT`.

### 2.4 Storage

- **SQLite**, one `DistantHorizons.sqlite` per dimension (WAL, `synchronous = NORMAL`, one JDBC connection per thread).
  Table `FullData` has the primary key `(DetailLevel, PosX, PosZ)` (`sqlScripts/0020`) and holds per row: the
  interior columns' data, four one-column border strips (`NorthAdjData` …, added so a neighbour can read just the edge
  it needs, `sqlScripts/0090`), the generation-step and compression-mode bytes, the palette, a format version, the
  compression mode, the three propagation flags and timestamps. `ChunkHash` and `BeaconBeam` tables sit beside it.
- **Row format V2** (`sql/dto/FullDataSourceV2DTO.java:379-667`): column-oriented varints: run counts per column, then
  `(id << 2 | hasLight << 1 | hasGap)` per run, then heights, then bottom y only where it does not follow from the run
  above, then one light byte per lit run. Each of the eight blobs is compressed separately.
- **Compression** (`EDhApiDataCompressionMode` javadoc, measured per row in 2024): **Zstd level 3 is the default** (read
  2.1 ms, write 4.9 ms, ratio 0.26), LZ4 (3.25 / 5.99 ms, 0.45), LZMA2 (13.3 / 71 ms, 0.21, "often causes whole
  computer stuttering"). The config comment says there is "no reason to use any compressor except ZStandard".
- **Render data is not cached**: the `DhRenderData` table was dropped because "the render cache was discovered to not
  speed up LOD loading" (`sqlScripts/0040`); every load rebuilds render data from full data plus the neighbours' strips.
- **Memory.** No LRU of full data: every read queries SQLite (relying on SQLite's own page cache). Arrays are pooled
  through soft references (`util/objects/pooling/PhantomArrayList`); write caches coalesce for 1 s.
- **Throughput.** The `synchronous` tooltip gives "~2k saves per sec" for FULL, "~14k" for NORMAL and "~16k" for OFF.
- **Size.** DH publishes no bytes-per-area figure. From the V2 format we estimate 40–60 KB per leaf section in a
  vanilla-like overworld, about **13–20 MB per km² of fully generated terrain** over all levels (estimate; section 2.7
  has user reports). A rough-surface section is far smaller: one run per column.

### 2.5 Rendering

- **Two backends.** `OPEN_GL` (raw LWJGL, GLSL 330, so OpenGL 3.3; buffer storage and vertex attribute binding used when
  present) and `BLAZE_3D` (Mojang's `GpuDevice`/`RenderPass` API, `com.mojang.renderpearl.api` on 26.3), which is the
  default from 26.1 and the only one that runs on Vulkan. Iris supports only `OPEN_GL`: with Iris installed DH forces
  it, and choosing Blaze3D crashes with "Iris doesn't support DH when using the [BLAZE_3D] rendering engine"
  (`common/AbstractModInitializer.java:475-503`).
- **Meshing** (`dataObjects/render/bufferBuilding/ColumnRenderBufferBuilder`, `ColumnBox`, `LodQuadBuilder`), on DH's
  pool: each run is a box; its top and bottom are culled against the runs above and below, and each side is split by
  height against the neighbour column's runs so only exposed parts become quads (a cliff gives one quad as tall as its
  exposed face). Tops and bottoms are merged greedily in two passes, sides horizontally plus a vertical pre-merge, up
  to 2,048 blocks wide (16 with earth curvature on). Instead of skirts, sides on a section border are kept even when
  an opaque, lit neighbour covers them, "to prevent holes when low detail LODs are replaced by higher-detail ones".
- **Vertex** (`LodQuadBuilder.java:470-519`): 16 bytes: x, y, z as `uint16` relative to the section's corner (y from
  the level floor), a `uint16` of light plus a 0.01-block outward nudge against cracks, RGBA8 colour with face shade
  baked in, a material byte and a normal byte for shader packs, and a `uint16` texture tile. 64 bytes per quad plus
  24 bytes of `uint32` indices from one shared index buffer. Light comes from Minecraft's lightmap texture.
- **Buffers and draws.** One or more buffers of at most 2 MB per section ("relatively small (10 MB was the previous
  max) to reduce stuttering"), recreated on every rebuild with immutable `glBufferStorage` and no mapping; uploads run
  on the render thread for at most half a frame. Then **one `glDrawElements` per buffer**, near to far: no multi-draw,
  instancing, indirect draws or compute.
- **Detail selection** (`render/QuadTree/LodQuadTree.java:1244-1271`): `detail = floor(log_base(d / (unit · 16)))`
  with the 2D distance *d* only; with the default quality the base is 2 and the unit 12 chunks, so detail 0 reaches
  384 blocks, detail 1 768 and so on. The tree updates every 100 ms on its own thread. Default LOD radius 256 chunks
  (up to 4,096). By our re-implementation that is about 430 live sections at the default radius and 780 at the maximum.
- **Culling.** Frustum only, on the CPU, against a box that spans the **full world height** for every section
  (`render/renderer/cullingFrustum/DhFrustumBounds`). No GPU or occlusion culling; hidden geometry is removed at build
  time (cave culling, covered faces).
- **Transparency.** Separate buffers; whole sections sorted far to near, no per-quad sort, depth writes left on.
- **The seam.** DH draws into its own RGBA8 and `DEPTH32F` targets before vanilla terrain, then copies its colour over
  Minecraft's with no depth test; vanilla draws on top using its own depth, so vanilla always wins where it has a pixel
  (`common/render/openGl/GlDhMetaRenderer`, `postProcessing/copy`). No LOD section is culled for lying inside the
  vanilla render distance. Instead the terrain shader discards fragments nearer than `nearClip + 16` blocks with a 4 × 4
  Bayer-dithered band out to 1.5 times that (`shaders/terrain/gl/frag.frag:135-161`), and a "vanilla fade" pass
  cross-fades vanilla's pixels into DH's image between 1.5 and 1.9 times that distance using Minecraft's depth
  (`shaders/fade/gl/vanilla_fade.frag`). The clip distance is a fraction of the vanilla render distance (0.9 above 10
  chunks, 0.2 with shader packs), reduced while moving fast. DH's projection keeps Minecraft's with a near plane of at
  most 7.5 blocks and a far plane of (LOD radius + 512) × 2 blocks (× √2 with Iris).
- **Fog and effects.** Its own screen-space fog over DH depth (default exponential squared from 40 % to 100 % of the LOD
  distance), SSAO, TAA and a far fade into the sky (these last three are off under shader packs).
- **Shader packs.** DH exposes program and framebuffer overrides (`IDhApiShaderProgram`, `IDhApiFramebuffer`), render
  events, its depth and colour texture ids and a "defer transparent rendering" switch (`IDhApiRenderProxy`); Iris uses
  them to render DH's buffers with the pack's `dh_terrain`, `dh_water` and `dh_shadow` programs and `dhDepthTex*`
  samplers. Those names live in Iris, not DH. With shaders DH draws from a mixin into Sodium's `renderGroup`.
- **Numbers in the source**: none beyond the 2 MB buffer cap and a warning that vanilla render distances above 12 add
  little ("A vanilla render distance of 8 is recommended").

### 2.6 Multiplayer

- One plugin channel, `distant_horizons:msg`, with versioned messages (`network/messages`). A client asks for a section
  (`FullDataSourceRequestMessage`, with its local timestamp when it has the section); the server answers with the
  **same compressed row it stores**, split into messages of at most 1,048,000 bytes, or with "not changed", or with an
  exception such as "rate limited" or "requires splitting" (coarser than the server's generator can make).
- The server **generates on demand**: requests for the same section from several players share one task, which joins
  the server's own world-generation queue; a dedicated server has no render tree, so client requests, the leaf
  regeneration pass and `/pregen` feed it.
- Limits (server config, `Config.java:1947-2081`): 20 generation requests per second and 20 in flight per player,
  `playerBandwidthLimit` 500 KB/s (sent at 20 Hz), an optional global limit, request radius up to 4,096 chunks,
  "sync on load" at 50 per second. Client and server negotiate the minimum of their settings.
- **Freshness** by timestamp only: the client sends its section's timestamp plus the server clock offset, and the
  server sends the section only if its own is newer. The DTO's checksum is "not used for anything".
- **Real-chunk changes** are pushed when `enableRealTimeUpdates` (default on) to players within 256 chunks, as the
  whole changed leaf section, not a diff (`level/AbstractDhServerLevel.java:229-265`).

### 2.7 What DH and its users publish

DH has no documentation site; the official sources are the GitLab wiki (its FAQ covers 3.2.0-b), the API javadoc at
https://distant-horizons-team.gitlab.io/distant-horizons/ and the release notes
(https://gitlab.com/distant-horizons-team/distant-horizons/-/releases,
https://modrinth.com/mod/distanthorizons/changelog).

**Generation.**

- No chunks-per-second figure per mode is published. DH's author: "Chunk generation time is generally on the order of
  several seconds" (https://gitlab.com/distant-horizons-team/distant-horizons/-/work_items/1208), and "chunk to LOD
  converting is less than 10% of the time" (work item 945).
- The rough surface generator came from work item 1294 (2026-08-24): "The current SURFACE world gen mode isn't much
  faster than FEATURES and looks significantly worse", while N-sized generation allows "a massive world gen distance to
  be generated significantly faster than FEATURES, which requires each chunk be generated individually". It shipped in
  3.3.0 as "a high-speed surface world generator", with "significantly improve world gen task queuing for fast custom
  world generators". On generating less than whole chunks: "Minecraft's world gen can only handle full chunks at a
  time ... The best we can do is generate the surface and guess the block based on the biome" (work item 1303); and
  "Generating noise at a lower resolution would be incredibly powerful and DH already supports that sort of generation,
  unfortunately MC's generator doesn't" (work item 1123).
- The API example `NSizedWorldGenerator` "generates LODs directly at whatever detail level is requested. This allows
  for **extremely** fast world generation if properly implemented"
  (https://gitlab.com/distant-horizons-team/distant-horizons-api-example).
- [U] Third-party generators through that API: DH SeedGen (All Rights Reserved) claims to be 5.6 times faster than
  DH's rough generator (https://modrinth.com/mod/distant-horizons-seedgen); "Turbo for Distant Horizons" claims 17 to 19
  times faster than the rough surface search (search snippet only); Tellus, an Earth-scale generator (LGPL-3.0,
  https://github.com/Yucareux/Tellus), samples elevation, land cover and climate directly through the same API.

**Storage history** (release notes): 2.0.0 moved "from a loose file system to a Sqlite database" and from colours to
block ids; 2.1.0 made LZMA the default ("roughly 3x the compression"), introduced the lossy `VISUALLY_EQUAL` mode and
WAL; 2.3.5 switched to Zstd "to reduce stuttering"; 2.4.0 moved to Zstd block mode ("6x speed improvement for
reading, 3x ... for writing"), var-ints and the separate border columns ("Decreased new database size by about 40%",
"Loading pre-existing LODs improved by about 5x"); 3.3.0 set SQLite's `synchronous` to NORMAL ("Reduce disk usage by
about 50%" in writes). DH's author on one file per dimension: "Having a single large database file doesn't cause any
noticeable performance loss" (work item 981); on disks: "DH is designed with SSD's in mind" (work item 1199).

**Sizes and speed** [U unless stated]:

- disk: 12 GB for an All The Mods 10 overworld (work item 981); 8 to 18 GB in modpacks and "hundreds of gigabytes if
  you're doing 4096" (AnswerOverflow snippets);
- memory: [S] 2.1.0, "1024 at medium can be loaded with 4 GB of memory"; 2.3.0, "DH can now run with as little as 2-4
  GB of memory"; [S] the Modrinth gallery, "anything above 512 may become difficult due to high VRAM/GPU usage";
- frame rate: work item 1281 (RTX 3060, Ryzen 5 3600, 1080p) measured 144 → 105 FPS on OpenGL and 167 → 119 on Vulkan
  from 3.1.2 to 3.2.0, which the author put down to the new textured LODs.

**Height.** No official limit is published. Tellus's fork of DH widens the height field to 14 bits for its range of
y = −640 to 10447 and says "Stock DH's vertical packing still cannot represent the full Tellus extended-height range"
(https://github.com/Yucareux/Tellus/blob/main/docs/distant-horizons-3.3.1.md).

**Seams.** The FAQ: holes at the edge of the vanilla render distance come from "vanilla terrain not loading in fast
enough, or your shader having an incorrect overdraw prevention implementation"; low-quality blocks behind fences and
ladders are "intended behavior, to prevent holes in the world when moving". 2.0.1 replaced "the near clip plane with a
fragment shader"; 2.1.0 dropped a "Seamless Overdraw Test" that "ultimately failed".

**Shader packs.** Iris's ShaderDoc documents the DH contract
(https://github.com/IrisShaders/ShaderDoc/blob/master/dh-support.md): programs `dh_terrain`, `dh_water` and `dh_shadow`,
samplers `dhDepthTex0` and `dhDepthTex1`, uniforms such as `dhNearPlane`, `dhFarPlane`, `dhRenderDistance` and
`dhProjection`, the `DISTANT_HORIZONS` define and `dhMaterialId`. [U] A community list names Bliss, BSL, Complementary
Reimagined, Photon and about 25 other packs as supporting it
(https://gist.github.com/Steveplays28/52db568f297ded527da56dbe6deeec0e). DH's own FAQ: DH "only works for Iris 1.7+ and
only with shaders built with DH support in mind".

## 3. Voxy

Voxy 0.2.20-beta, `dev` at `5bf33350`, built for Minecraft 26.3 with Sodium 0.9.3-beta.1 and Iris 1.11.7. It needs
Sodium (`fabric.mod.json`: `depends.sodium`). Paths are relative to `src/main/java/me/cortex/voxy/` unless they start
with `src/`. Everything below is described in our own words; no Voxy code is reproduced.

### 3.1 Data model

**Voxel.** One 64-bit `long` per voxel (static helpers in `common/world/other/Mapper.java`, `isAir` to
`composeMappingId`):

| Bits | Width | Field |
|---|---|---|
| 60–63 | 4 | block light |
| 56–59 | 4 | sky light |
| 47–55 | 9 | biome id (so at most 512 biomes per world) |
| 27–46 | 20 | block-state id (0 is air of every kind) |
| 0–26 | 27 | unused |

Air keeps only its light; it never stores a biome. Light is kept because a face takes the light of the voxel in front of
it when it is meshed.

**Ids.** Each world has an append-only `Mapper` with two id spaces, block states and biomes, handed out in the order
they are first seen. Each new id is written to the store at once as gzip-compressed NBT under the key
`(type << 30) | id`: the block state through `BlockState.CODEC` with the game's data version, so it can be upgraded
with DataFixers on load, and the biome by its registry name. There is no overflow check, so a world with more than 512
biomes would spill into the light bits. The `Mapper` also keeps each state's light dampening as its *opacity*, with
leaves forced to 15.

**Sections and levels.** A `WorldSection` is 32 × 32 × 32 voxels held in a `long[32768]`, which is 256 KiB in memory
whatever it contains (`common/world/WorldSection.java`). `WorldEngine.MAX_LOD_LAYER = 4` gives five levels:

| Level | Voxel (blocks) | Section (blocks) | Voxels one 16³ chunk section fills |
|---|---|---|---|
| 0 | 1 | 32 | 16³ |
| 1 | 2 | 64 | 8³ |
| 2 | 4 | 128 | 4³ |
| 3 | 8 | 256 | 2³ |
| 4 | 16 | 512 | 1 |

**Section key** (`WorldEngine.getWorldSectionId`, `POS_FORMAT_VERSION = 1`): level in bits 60–63, y in 52–59 (8 bits,
signed), z in 28–51 (24 bits), x in 4–27 (24 bits), and 4 spare bits. A TODO next to it says the format could shrink
with the level. Because every position must also be valid at level 0 (`NodeManager.assertPosValid`) and ingestion
writes level 0 first, y covers 256 level-0 sections: **y = −4096 to 4095**. Out-of-range y is masked, not checked, so
it silently lands on a section 8,192 blocks away; this is why Diffusicraft clamps Voxy (`client.compat.voxy`). x and z
reach ±268 million blocks.

**Per-section metadata.** `nonEmptyChildren`, an 8-bit child-existence mask (level 0 holds `0xFF` when it has any
non-air voxel), and at level 0 a count of non-air voxels. The renderer's hierarchy walks these masks. There is no hash,
checksum or per-section format version (`SaveLoadSystem3` has a "do hash" TODO).

**Downsampling.** Voxy never builds a coarse section from its eight children. Each 16³ chunk section is reduced inside
itself when it is ingested, 16 → 8 → 4 → 2 → 1, into one `long[4681]` pyramid (`common/voxelization/VoxelizedSection`,
`WorldVoxilizedSectionMipper`), and each level of the pyramid is copied into the section of that level
(`WorldUpdater.insertUpdate`). This works because 2^L ≤ 16 for every level. When eight voxels collapse into one
(`common/world/other/Mipper.mip`):

- every non-air voxel scores its opacity × 16 plus a fixed corner rank, and the highest score wins whole, with its own
  block, biome and light: the most opaque block wins, and any block beats air;
- ties go to the +x corner, then +y, then +z;
- if all eight are air, the result is air whose block light is the floored mean and whose sky light is the rounded-up
  mean of the eight.

There is no majority vote and no surface rule. Diffusicraft's own `LodGrid` javadoc records the visible cost: at 16
blocks a voxel every sea shallower than that becomes dry ground at sea level, because the sea floor is more opaque than
the water over it. A coarse level is only as fresh as the last ingest of each chunk section under it, and it cannot be
rebuilt from stored finer data (a TODO in `SectionSerializationStorage.loadSection` mentions it).

### 3.2 Land that was never generated

Voxy has **no world generator and no generator API**. It knows only what it is given:

- **chunks the client loads** (section 3.3);
- **importers** (`commonImpl/importers`), started with `/voxy import world|raw|zip|bobby|current` or
  `/voxy import distant_horizons <db>`:
  - `WorldImporter` parses the region-file header itself (the 8 KiB location table), hands each chunk's payload to
    Minecraft's decompressor, `NbtIo` and the `PalettedContainer` codecs, keeps only chunks whose status is FULL or
    EMPTY, takes light from the stored `BlockLight`/`SkyLight`, and voxelises them on the shared pool (service weight
    3). One reader thread walks the files and sleeps while more than 10,000 chunks wait;
  - `DHImporter` reads a Distant Horizons `DistantHorizons.sqlite`, detail level 0 only, decodes DH's 64-bit datapoints
    into columns, expands them into 16³ sections and inserts them like any other (weight 10). It is the model for a
    feeder that has columns rather than chunks;
  - both pause while more than 1,200 sections wait to be saved. The throughput is printed at the end of an import and
    written nowhere;
- **Chunky.** In singleplayer a mixin on Chunky's `FabricWorld.getChunkAtAsync` passes every chunk Chunky generates to
  the ingest service (`commonImpl/mixin/chunky/MixinFabricWorld`), so pre-generating with Chunky fills Voxy as long as
  each chunk's light is ready. The cost is the full vanilla chunk pipeline per chunk;
- **third-party feeders.** `fabric.mod.json` declares `"breaks": {"voxyworldgenv2": "=2.2.2"}`, which shows that a
  "world gen for Voxy" mod exists (section 3.7). Without an API such mods call Voxy's public statics or mix into it: a
  whole `LevelChunk` through `VoxelIngestService.tryAutoIngestChunk`, one section through `rawIngest`, prepared voxels
  through `VoxelizedSection` plus `WorldVoxilizedSectionMipper.mipSection` plus `WorldUpdater.insertUpdate` (what
  `DHImporter` does), or raw writes into a `WorldSection` at any level through `WorldEngine.acquire`, which leaves the
  child masks to the caller. Diffusicraft's current Voxy integration fills sections the store misses by mixing into
  the call to `SectionLoader.load` in `ActiveSectionTracker.acquire`.

What makes these fast or slow: an importer costs only decompression, NBT parsing and voxelisation per chunk, because the
chunks already exist; anything that needs land nobody has visited pays for vanilla chunk generation (Chunky, or a
feeder that runs the chunk pipeline). Voxy's normal path builds even its coarsest voxel from all 4,096 blocks of a
chunk section; only raw writes into coarse sections avoid that.

### 3.3 Real chunks

All ingestion runs **on the client**; only the client entrypoint creates a Voxy instance (`client/VoxyClient`), so a
dedicated server does nothing. The triggers:

| Trigger | Where |
|---|---|
| Sodium adds a chunk to its render tracker | `client/mixin/sodium/MixinRenderSectionManager` (`onChunkAdded`) |
| Sodium removes a chunk (`ClientChunkCache.drop` with Bobby) | `onChunkRemoved`; `MixinClientChunkCache` |
| A visible Sodium section becomes empty | redirect in `updateSectionInfo` |
| A block on a section border turns to air | `client/mixin/minecraft/MixinClientLevel` (`setBlocksDirty`) |
| Chunky generates a chunk (singleplayer) | section 3.2 |

Most edits therefore reach Voxy when the chunk is next added or removed. The calling thread checks that the chunk's
light is ready (`LIGHT_AND_DATA`) and skips it otherwise, copies the light layers, and queues one job per section on the
ingest service (weight 5000, the highest). A worker then:

1. **voxelises** the section (`common/voxelization/WorldConversionFactory`), reading the vanilla `PalettedContainer`'s
   palette and packed storage directly (opened by `voxy.accesswidener`), mapping each palette entry to a `Mapper` id
   once, and taking each voxel's biome from its 4³ biome cell;
2. **mips** it into the 16 → 1 pyramid;
3. **inserts** it (`WorldUpdater.insertUpdate`): for level 0 to 4 it overwrites the matching sub-cube of each level's
   section, air included, updates the non-air count and child masks, and calls `WorldEngine.markDirty` with a 6-bit
   mask of the neighbouring sections that share a changed face, which the renderer listens to. It stops at the first
   level where nothing changed.

There is no merge with what was there: the whole 16³ cube and its mips are replaced. Saving is lazy: a dirty section is
queued for saving only when nothing holds it any more (`ActiveSectionTracker.tryUnload`).

### 3.4 Storage

`SectionSerializationStorage` turns a section into bytes (`common/world/SaveLoadSystem3`) and hands them to a stack of
configurable layers described by a JSON file. The default (`common/StorageConfigUtil.createDefaultSerializer`) is
**serialiser → ZSTD level 1 → RocksDB**.

- **Section format.** A 16-byte header (key; LUT size and child mask), then 32,768 16-bit indices in the section's
  linear order, then the LUT of distinct 64-bit voxel values. Raw size is 65,552 + 8 × LUT bytes; there is no run-length
  or variable-width coding, and ZSTD finds the redundancy. A JMH benchmark (`src/jmh`) measures exactly this decode.
- **RocksDB** (rocksdbjni 10.2.1, `common/config/storage/rocksdb`): column families for sections and id mappings; the
  section family turns RocksDB's own compression off, optimises for point lookups, has a 128 MiB HyperClockCache block
  cache and 10-bit Bloom filters. Keys are the 8-byte section id in big-endian order, so rows sort by level, then y, z,
  x (a Morton swizzle exists but is disabled).
- **Alternatives**: LMDB (LWJGL, map grows 32 MiB at a time), Redis, in-memory, an LZ4 compressor, sharding over several
  backends, and a read-through cache layer.
- **Files**: `<world>/voxy` in singleplayer, otherwise `.minecraft/.voxy/saves/<server address>`.
- **Size.** Voxy publishes no bytes-per-area figure. From the format we estimate a few tens of bytes for a uniform
  section and about 2–10 KB for a surface section after ZSTD-1, which is roughly **10–60 MB per km² over all levels**
  for vanilla-height worlds (estimate; section 3.7 has what users report). Sections that equal the default fill (open
  sky) are never written.
- **Memory.** 64 lock-striped maps of live sections plus an LRU of 1,024 unreferenced sections (2,048 with a heap of
  about 4 GiB or more), so 256–512 MiB of voxel arrays; a pool of up to 400 spare arrays (about 100 MiB); RocksDB's
  128 MiB block cache; a soft limit of 5,000 queued saves.
- **Threads** (`common/thread`): one pool of `max(cores / 1.5, 1)` workers at thread priority 3, which also borrows
  Sodium's idle chunk-builder threads by replacing the semaphore of Sodium's job queue
  (`client/mixin/sodium/MixinChunkJobQueue`). A worker picks a service at random, weighted by queue length × weight:
  ingest 5000, saving 100, meshing 10, DH import 10, world import 3.

### 3.5 Rendering

Voxy's renderer is GPU-driven and needs OpenGL 4.6 (compute, `glDispatchComputeIndirect`,
`glMultiDrawElementsIndirectCountARB`, `gl_BaseInstance`, DSA, persistent mapping).

**Meshing** (`client/core/rendering/building/RenderDataFactory`, `client/core/util/ScanMesher2D`), on the shared pool:

- a 32³ section is meshed against the facing slices of its six neighbours at the same level;
- opaque faces exist where an opaque voxel meets a non-opaque one; a face is dropped when the neighbour has the same
  self-culling model or an occluding face;
- faces are merged greedily in 2D slices on all three axes, but only between voxels with the same model, light and
  biome, and at most **16 × 16 voxels per quad**;
- fluids are meshed in a separate pass; waterlogged blocks give both their own and fluid quads;
- quads go into eight buckets: translucent, double-sided, and one per face direction.

**Block models** (`client/core/model`). On a dedicated thread Voxy reads the vanilla block atlas back once and
rasterises each block state's model in software, orthographically from each of the six directions, into 16 × 16 colour
and depth images. From those it derives the render layer (leaves forced solid), per-face inset depth and bounds (so a
slab or fence becomes one inset textured rectangle per face), occlusion flags and tinting, and it shares one model id
between states that look the same. The faces live in one RGBA8 atlas of 12,288 × 8,192 texels with mips (about 510 MB
by our arithmetic from the constants); each model also has a 64-byte GPU record.

**Quad format.** 8 bytes per quad: face (3 bits), two sizes (4 + 4 bits), a 5-bit position per axis inside the section,
a 16-bit model id, a 9-bit biome and the 8-bit light. There is no vertex buffer: the vertex shader reads quad
`gl_VertexID / 4` from one large SSBO and uses a shared 16-bit index buffer. Positions are integers relative to the
camera's 32-block section, so there is no float precision problem at any x/z the key allows.

**GPU memory and uploads.** All geometry lives in one SSBO of up to 4 GiB minus 1 KiB (smaller on some drivers, sparse
buffers as a fallback), carved by an arena allocator in 1 KiB steps on an async thread. Section metadata (32 bytes each,
2^20 sections) and the node tree (16 bytes per node, 2^21 nodes) are SSBOs too. Uploads go through a 64 MiB persistent
mapped staging buffer, then compute "memcpy" and "scatter" shaders write them into place. A 4 GiB CPU-side LRU keeps
built meshes so an evicted one need not be rebuilt.

**Traversal and culling** (`client/core/rendering/hierachical`, `shaders/lod/hierarchical`):

1. The CPU keeps a ring of level-4 top nodes (512 blocks) around the camera, one per 512-block slab of height.
2. Each frame a compute shader walks the tree breadth first, one indirect dispatch per level. For each node it checks a
   cylindrical render distance, the frustum (without the far plane) and a **HiZ** depth pyramid (built from Voxy's own
   depth after drawing last frame's visible list).
3. A node is refined when its projected screen area exceeds `subDivisionSize²` pixels (64² by default): a pixel-area
   rule, not a geometric error. A parent is swapped for its children only when all child meshes exist, so the switch
   pops but never leaves a hole.
4. Nodes whose data or mesh is missing are written to a feedback buffer of at most 50 requests per frame, fewer as the
   mesh backlog grows; the CPU loads or meshes them and uploads the result.
5. The candidate sections' boxes are rasterised against the depth buffer as a second occlusion test, then a compute
   shader writes one indirect draw per non-empty face bucket, skipping the face directions that point away from the
   camera.
6. When geometry memory runs low, a "cleaner" compute pass finds the 256 least recently drawn nodes for eviction.

**Draw submission.** Three `glMultiDrawElementsIndirectCount` calls per frame (opaque, newly visible, translucent) plus
one indirect draw for the occlusion boxes. No bindless. Translucent sections are bucket-sorted far to near by a GPU
prefix sum; quads inside a section are not sorted. NVIDIA extensions (representative fragment test, int64, subgroups)
are optional speed-ups.

**The seam with Sodium.** Voxy draws at the end of Sodium's cutout terrain pass
(`client/mixin/sodium/MixinDefaultChunkRenderer`), with near and far planes of 16 blocks (8 at a vanilla render distance
of 2 chunks or less) and 48,000 blocks (`client/core/VoxyRenderSystem`):

- a full-screen pass writes stencil 0 wherever vanilla drew, and LODs draw only where the stencil is 1;
- each frame it rasterises the back faces of every built section that Sodium's visibility search reached into a "chunk
  bound" depth buffer (`client/core/rendering/bounding/BoundRenderer`), and the LOD fragment shader discards anything
  nearer than it, so LOD terrain never shows inside the loaded volume even where vanilla shows sky or caves;
- LODs render into Voxy's own colour and D24S8 depth; a composite pass reprojects that depth into vanilla's and blends
  the colour in;
- Voxy pushes vanilla's render-distance fog out of the way and applies its own fog and a linear fade over the last 10 %
  of its radius. There is no dithering or cross-fade, either with vanilla or between levels.

**Shader packs.** A pack must opt in with a `voxy.json` (plus optional `voxy_*.glsl`): Voxy then writes straight into
the pack's gbuffer targets, appends the pack's fragment code (a `voxy_emitFragment` function), and exposes `vx*`
matrices, `vxRenderDistance`, near/far planes and its depth samplers, with the `VOXY` define set. LODs are never drawn
into the shadow map. Without `voxy.json`, Voxy uses its own unlit pipeline.

**Numbers.** Default render distance 16 top nodes (8,192 blocks), up to 64 (32,768 blocks); `subDivisionSize` 64 px;
caps of 2^20 sections, 2^21 nodes, 200,000 traversal entries and 400,000 opaque draws. No performance figures are in the
repository (README is one line); section 3.7 has published ones.

### 3.6 Multiplayer

Voxy has **no server side**: Modrinth lists the server as unsupported, and a collaborator says "voxy doesnt have any
server side support" (https://github.com/MCRcortex/voxy/issues/521). Another collaborator gives the reason: streaming
full LOD data could overload a server with about ten players, "That's why its not implemented yet"
(https://github.com/MCRcortex/voxy/issues/188). In multiplayer a client therefore has only the chunks the server sent
it. The storage folder is keyed by the server's address, so a changed address "resets" the LODs (issue 292).

### 3.7 What Voxy and its users publish, and third-party feeders

**Official statements.**

- OpenGL 4.6 is required: "Mac only supports OpenGL 4.1, Voxy requires OpenGL 4.6"
  (https://github.com/MCRcortex/voxy/issues/480). It runs only on Minecraft's OpenGL backend (0.2.17: "opengl only,
  vulkan unsupport"; a Vulkan rewrite was closed as not planned, issue 369).
- Licence and API: "Feel free to write your own LoD mod ... no you cannot use any code from this repo"
  (https://github.com/MCRcortex/voxy/issues/164). There is no API; Big Globe "temporarily disabled worldgen integration
  for Voxy 0.2.x ... Worldgen integration will be re-added if/when Voxy gets an API", and in 5.0.0 shipped a "New LOD
  renderer. This lets you see farther without needing mods like Distant Horizons or Voxy"
  (https://modrinth.com/mod/big-globe/changelog).
- The Modrinth gallery shows an "Intel Iris Xe iGPU rendering the entire hermicraft season 9 world at 1080p with 30
  fps". The README is one line and there is no wiki; no design write-up was found.
- Shader support "must add explicit support for voxy" (Modrinth); the integration format was finalised in 0.2.5 and is
  at version 3 in 0.2.20.

**Reports** [U]:

- disk: "LODs take about 190 GiB with ZSTD preset 8 ... but the save totals about 185 GiB"; the author rejected the
  reporter's run-length proposal as "many orders of magnitudes worse then the current serialization system"
  (https://github.com/MCRcortex/voxy/issues/423);
- a corrupt RocksDB `.sst` file crashes the world on load, and the fix is deleting the Voxy database (issue 503);
- RocksDB's natives for other architectures are stripped by default (issue 394), and Windows on ARM does not work
  (issues 538 and 742);
- seams: "inter lod level issue still remains" (issue 13); Voxy Seam Fix exists because "Voxy renders distant water at a
  slightly different height on every LoD level" (https://modrinth.com/mod/voxy-seam-fix); gaps at LOD seams (issue 663);
- height: no published limit; the 0.2.13 changelog fixes a "crash with tall worlds at max render distance", and Tellus's
  Voxy mixin hard-codes an 8-bit top-level y, consistent with section 3.1.

**Shader packs** [U, community list at https://gist.github.com/appleneko2001/51dcf842c934db4c6201146f2e594953]: BSL,
Solas and I Like Vanilla support Voxy; Photon (merged to its main branch in 2026-02), Complementary Reimagined (since
r5.8) and Bliss's unstable branch are in progress; Voxy Speed Gen's wiki says Bliss 2.1.2 has none.

**Third-party feeders for land Voxy has not seen.** Because Voxy has no generator, no server side and no API, a small
ecosystem fills the gap, mostly by reflection or mixins into Voxy's internals:

- **Voxygen** (formerly Voxy WorldGen; github.com/iSeeEthan/voxy_worldgen_v2; a restrictive custom licence): up to
  2.x it generated real chunks with forced tickets and let Voxy ingest them; 3.0.0 (2026-10-24) builds voxels without
  chunks and claims "around 300-600 chunks a second".
- **Voxy Speed Gen** (nayanct.github.io/vsg-wiki; MIT per its wiki, no public source found): runs vanilla biomes, noise
  and surface on throwaway chunks, only near the surface. Its own benchmark: about 270 chunks/s (Fast, no trees),
  205/s with trees, 94/s for Chunky and 24/s for Voxy WorldGen 2.4.3.
- **LOD Server Support** (github.com/VoX/lod-server-support; MIT): the server reads or generates chunks and streams
  columns that the client feeds to Voxy; about 650 columns/s into Voxy. A critic in Voxy's issue 188: it "sends full
  chunk sections, which scales poorly in bandwidth".
- **VoxyServer** (github.com/poosmacker/voxyserver; GPL-3.0): runs Voxy on the server, stores its sections per world
  and streams them; users build Voxy themselves because it cannot be bundled.
- **Chunksmith** (modrinth.com/mod/chunksmith; GPL-3.0): pregeneration that feeds Voxy and DH and throws the chunks
  away; 31.6 to 43.9 chunks/s on 8 cores.
- **Tellus** (github.com/Yucareux/Tellus; LGPL-3.0): Earth data, ingested into Voxy in real time.

Voxy itself declares `"breaks": {"voxyworldgenv2": "=2.2.2"}` in `fabric.mod.json` (0.2.13: "due to memory leaks").
The fast ones (Voxygen 3.0, Voxy Speed Gen, Tellus), like DH's rough generator, sample the terrain near its surface
instead of generating whole chunks; the others pay for vanilla chunks.

## 4. Side by side

| | Distant Horizons 3.3.5-dev | Voxy 0.2.20-beta |
|---|---|---|
| Model | 2.5D: 64 × 64 columns of vertical runs | 3D: 32³ voxels |
| Element | 64-bit run: 31-bit id, 12-bit height and bottom, light | 64-bit voxel: 20-bit block, 9-bit biome, light |
| Ids | per-section palette of (biome, block) strings | per-world append-only palette, NBT |
| Levels | 0–8: columns 1–256 blocks, sections 64–16,384 | 0–4: voxels 1–16 blocks, sections 32–512 |
| Height limit | 4,095 blocks above the floor (12 bits) | y = −4096 to 4095 (8-bit section y) |
| Downsampling | per slice, most common non-air of 2 × 2 | most opaque of 2 × 2 × 2, inside each chunk section |
| Ungenerated land | rough surface (any level) + dense chunk generation + API | none; importers, Chunky, third parties |
| Real chunks | server side: load and save | client side: Sodium add/remove, a few block hooks |
| Storage | SQLite per dimension, Zstd 3, varint columns | RocksDB, Zstd 1, flat u16 palette per section |
| Render data | rebuilt from full data on every load | meshes kept in a 4 GiB CPU LRU and on the GPU |
| Vertex | 16 B per vertex, 64 B + 24 B of indices per quad | 8 B per quad, vertex pulling |
| Draws | one per buffer (≥ 1 per section) | 3 MDI-count calls per frame |
| Culling | CPU frustum against full-height boxes | GPU traversal, frustum, HiZ, box raster test |
| Detail rule | log₂ of 2D distance | projected area above `subDivisionSize²` px |
| API floor | OpenGL 3.3, or Mojang's API (Vulkan) | OpenGL 4.6 |
| Seam | under vanilla; dithered near discard; cross-fade | stencil; depth of the sections Sodium drew |
| Shader packs | Iris's `dh_*` programs (pack opts in) | `voxy.json` (pack opts in) |
| Multiplayer | server generates on demand, 500 KB/s per player, timestamps, pushes | none |
| Licence | LGPL-3.0 | All Rights Reserved |

## 5. Other projects and papers

### 5.1 Nvidium (MCRcortex, LGPL-3.0)

An alternative Sodium backend for NVIDIA GPUs from Turing on. It needs `GL_NV_mesh_shader`, bindless multi-draw and
sparse buffers (`src/main/java/me/cortex/nvidium/Nvidium.java:29-52`), and turns itself off when an Iris shader pack
is active (`sodiumCompat/IrisCheck.java:13-15`). On 26.3 it hooks Mojang's `GlDevice`, so it exists only on the
OpenGL backend. Described in our own words:

- Sodium's regions of 8 × 4 × 8 sections; per section, quads grouped by face direction, in one 80 GB sparse buffer
  whose 1 MiB pages are committed on demand.
- 16 bytes per vertex and no index buffer. The mesh shader reads quad *n*'s four vertices and emits two triangles; the
  fragment shader fetches attributes itself with `gl_BaryCoordNV`, so little passes between stages.
- A task-shader workgroup per section drops face directions that cannot face the camera and launches one mesh
  workgroup per 16 quads (its "meshlet", up to 64 vertices and 32 triangles, the measured sweet spot); the mesh shader
  drops triangles that cover no pixel centre.
- **Two-phase occlusion culling without a CPU round trip** (`RenderPipeline.renderFrame`): draw last frame's visible
  set, rasterise region and section boxes against that depth with colour and depth writes off (fragments set a
  visibility flag; the representative fragment test is a speed-up), then draw what became visible this frame.
- A "keep distance" that keeps unloaded sections' geometry in VRAM, evicting the least recently seen region.
- Reusable without NVIDIA hardware: the two-phase box test (plain SSBOs and early fragment tests, or compute and
  indirect draws, as in Haar and Aaltonen 2015), face-direction ranges, sub-pixel triangle culling, least-recently-seen
  eviction, and its `hideSection` API idea. Not reusable: mesh shaders, bindless pointers and sparse buffers.

### 5.2 Bobby (Johni0702, LGPL-3.0)

A client cache of real chunks that lets the render distance exceed the server's view distance.

- **Storage**: vanilla Anvil `.mca` files with its own NBT per chunk (palettes, light, block entities, heightmaps) under
  `.minecraft/.bobby/<server>/<seed hash>/<dimension>`, through Mojang's `IOWorker`; a per-region last-access file
  lets old regions be deleted (`FakeChunkStorage.java`, `ChunkSerializer.java`).
- **Hooks**: saves each chunk as `ClientChunkCache.drop`, `updateViewCenter` or `updateViewRadius` lets it go, keeping
  the light packet that arrived before the chunk's light was applied (`mixin/ClientPacketListenerMixin.java:35-58`);
  loads stored chunks back on eight threads, nearest first.
- **Rendering**: a `FakeChunk` is an ordinary `LevelChunk`, so Sodium meshes and draws it like any other (Bobby tells
  Sodium's chunk tracker that it has block and light data).
- **Cost**: the same as a real chunk in memory, meshing and GPU geometry, growing with the square of the radius; only
  what this client has seen. Section y is stored as a byte (`ChunkSerializer.java:95`), so it cannot store a
  2,046-section dimension.
- Worth borrowing: the capture points, the light-packet trick and the delayed unload. The real-chunk overlay should
  reduce a chunk to LOD columns at capture time rather than keep it whole.

### 5.3 Sodium's region renderer (CaffeineMC, PolyForm Shield: re-implement, do not copy)

- **On 26.3, Sodium draws through Mojang's API** (`com.mojang.renderpearl.api`: `GpuBuffer`, `RenderPass`,
  `RenderPipeline`) and picks a backend at start (`client/gpu/device/backend/DrawBackend.java`): OpenGL multi-draw,
  Vulkan with `VK_EXT_multi_draw`, or Vulkan indirect draws, all through `RenderPass.multiDrawIndexed` and
  `drawIndexedIndirect`.
- **Regions** of 8 × 4 × 8 sections (128 × 64 × 128 blocks); one cached multi-draw batch per region and pass, rebuilt
  only when invalidated; push constants carry the region's offset.
- **Compact vertex**: 20 bytes, with 20 bits per axis in a 32-block window around the section (1/32768-block steps),
  colour × AO, 15-bit UVs with a sign bit for a bias toward the sprite centre, light, material and section index
  (`render/chunk/vertex/format/impl/CompactChunkVertex.java`). The region offset is computed on the CPU as
  (region origin − integer camera position) − camera fraction, so floats stay small anywhere in the world.
- **One shared quad index buffer** (`SharedQuadIndexBuffer`) and `baseVertex` commands; faces grouped by direction and
  culled by the camera's side with a 3-block slack; shared arenas with defragmentation of up to 32 copies per frame.
- **Culling**: a breadth-first search through each section's face-to-face visibility (`occlusion/OcclusionCuller`,
  `VisibilityEncoding`), with angle and ray tests, **on its own thread** since 0.9.1.
- **What it draws**: a chunk only once it and all eight neighbours have block and light data (`map/ChunkTracker`), only
  built sections, and only inside a cylinder of radius and half-height equal to the render distance. There is no public
  API for "which sections were drawn": vanilla's `LevelRenderer.isSectionCompiledAndVisible`, which Sodium overrides,
  answers "built"; `SodiumWorldRenderer` and `RenderSectionManager.getRenderLists()` are internal.
- Newly built sections fade in from the fog colour, timed through a texel buffer of creation times.

### 5.4 FarPlaneTwo (DaPorkchop_, MIT with attribution: code reusable with credit and a link)

The closest prior art for tall worlds and generator-driven LODs, for Forge 1.12.2 with Cubic Chunks; its stated goal
is render distances "of *at least* 100 thousand blocks" (`README.md`).

- **Cubic tiles of 16³ voxels**, each level doubling the voxel size, keyed by (level, x, y, z). The server tracks a cube
  of tiles per level around each player, bounded vertically too, so a tall world costs no more than a short one: a 3D
  clipmap (`core/.../engine/server/tracking/Tracker.java`).
- **Rough tiles straight from the generator's density** at the tile's spacing, meshed by dual contouring
  (`engine/server/gen/rough/AbstractDualContouringRoughVoxelGenerator.java`); exact tiles from real chunks. The older
  `master` branch had a heightmap mode with up to four layers per column and a min/max scaler that keeps the most
  deviating height.
- **GPU frustum and HiZ culling** in compute shaders writing indirect draws (`shaders/comp/tile_occlusion_test.comp`,
  with reversed-Z and min/max-sampler variants).
- **The seam**: each frame it builds a bitmask of vanilla sections that are built and whose neighbours are built, and
  its finest tiles are skipped there, on the CPU and the GPU
  (`core/.../client/render/TerrainRenderingBlockedTracker.java`, `shaders/util/vanilla_renderability.glsl`).
- The server generates and stores tiles in RocksDB and streams them; a config tooltip gives about 10.8 KiB of VRAM per
  tile (65,247 tiles, 689 MiB). No shader-pack support.

### 5.5 Other Minecraft far-terrain mods

- **Tellus** (github.com/Yucareux/Tellus; LGPL-3.0): Earth-scale terrain from elevation data, a fast DH generator
  through DH's API, real-time Voxy ingestion, and its own DH fork with 14-bit heights. The closest project to
  Diffusicraft's goals, and proof that Earth scale needs wider height fields.
- **Big Globe** (modrinth.com/mod/big-globe; All Rights Reserved): a world generator that dropped its Voxy integration
  for want of an API and built its own LOD renderer in 5.0.0 (50 million quads by default, 100 million at most). The
  precedent for this plan.
- **WorldFarView** (github.com/Gord1402/WorldFarView; licence unclear, `gradle.properties` says All Rights Reserved): a
  quadtree of heightfield quads sampled from the generator, refined by projected area times a terrain-complexity
  measure, generated on the server or, given the seed and settings, on the client. Worth taking: its split rule.
- **Ecstatic** (modrinth.com/mod/ecstatic; CC BY-NC-SA 4.0): "samples the seed in a grid" instead of loading chunks,
  the same idea as `LodSampler`; with shaders, fading, trees and transparency break.
- **DH SeedGen** (All Rights Reserved): a fast DH generator from the seed that fakes trees (section 2.7).
- **Lumeon, Visual Far, Farsight, Lodistance, HorizonLOD**: client caches of seen terrain at lower detail; All Rights
  Reserved or no source found.
- **Sodium LOD Fixer** (MIT): moved Sodium's culling search to a thread before Sodium itself did (0.9.1); its lesson is
  to keep Sodium's own render distance small and let the LOD take the rest.

Not found: "Simple LOD", "Far Horizons", "Voxel Distant Terrain", and any Distant Horizons for Bedrock (Bedrock has no
LOD mod; a feedback post asks for one). Sodium has no far-render work of its own.

### 5.6 Outside Minecraft: Veloren (GPL-3.0, ideas only)

Veloren draws everything beyond its voxel terrain as one static grid mesh displaced from a whole-world altitude
texture (`voxygen/src/scene/lod.rs`, `assets/voxygen/shaders/lod-terrain-vert.glsl`). The grid is "splayed" so it is
dense near the camera and sparse far away, re-centred every frame; normals come from finite differences; horizon maps
give terrain shadows without a shadow map; far trees and houses are instanced low-poly models. At the seam, the LOD
surface is pulled far down near the camera and not at all at the view distance, so real voxels always win the depth
test. It is the cheapest far field for a heightfield world: one draw and one texture.

### 5.7 Papers and techniques

- **Geometry clipmaps.** F. Losasso, H. Hoppe, SIGGRAPH 2004, https://hhoppe.com/proj/geomclipmap/. Nested rings of
  one regular grid, refilled toroidally as the camera moves, with a blend band between rings. With 255² vertices,
  11 to 12 levels reach about 500 km from 1-block spacing (estimate): the far-field structure.
- **GPU geometry clipmaps.** A. Asirvatham, H. Hoppe, GPU Gems 2, ch. 2, 2005. Heights in textures and a few reusable
  footprint meshes, so the CPU only writes new texels.
- **CDLOD.** F. Strugar, Journal of Graphics, GPU, and Game Tools 14(4), 2009, https://github.com/fstrugar/CDLOD. A
  quadtree of identical patches chosen by true 3D distance, each vertex morphing toward its parent: no cracks, and the
  right selection for a camera high above the ground in a 32k-tall world.
- **Chunked LOD.** T. Ulrich, SIGGRAPH 2002 course, https://tulrich.com/geekstuff/chunklod.html. Screen-space error
  selection and skirts.
- **Transvoxel.** E. Lengyel, JGGT 15(2), 2010, https://transvoxel.org/. Transition cells between voxel levels; only if
  the LOD ever becomes volumetric.
- **Sparse voxel octrees and DAGs.** S. Laine, T. Karras, I3D 2010; V. Kämpe, E. Sintorn, U. Assarsson, SIGGRAPH 2013;
  Aokana (arXiv:2505.02017, 2025) composites a DAG far field with meshes. Too heavy for a heightfield world; the
  deduplication idea suits storing real chunks.
- **Greedy meshing.** M. Lysenko, "Meshing in a Minecraft Game", 0fps.net, 2012; binary greedy meshing,
  https://github.com/cgerikj/binary-greedy-meshing (face masks with 64-bit shifts, so 64-wide tiles fit Java `long`s).
  Texture wrapping with `fract()` inside merged quads.
- **GPU-driven rendering.** U. Haar, S. Aaltonen, SIGGRAPH 2015 course. GPU culling into indirect draws and two-phase
  occlusion; Nvidium and FarPlaneTwo implement it in Minecraft.
- **Vertex pulling.** D. Rákos, *OpenGL Insights*, ch. 21, 2012. Quads of 8 bytes expanded in the shader.
- **Hierarchical Z.** N. Greene, M. Kass, G. Miller, SIGGRAPH 1993; D. Rákos, RasterGrid blog, 2010. HiZ tests of tile
  boxes; vanilla terrain, drawn first, is a good occluder.
- **Nanite.** B. Karis, R. Stubbe, G. Wihlidal, SIGGRAPH 2021 course. Lock shared borders when simplifying tiles.
- **Impostors.** R. Brucks, "Octahedral Impostors", 2018; E. Risser, "True Impostors", GPU Gems 3, ch. 21. One
  impostor per biome's tree for the middle distance.
- **Ray-marched heightfields.** A. Tevs, I. Ihrke, H.-P. Seidel, "Maximum Mipmaps", I3D 2008; C. Dick, J. Krüger,
  R. Westermann, Eurographics 2009. A far field in one pass; harder to fit under shader packs than a mesh.
- **Concurrent binary trees.** J. Dupuy, 2020; A. Benyoub, J. Dupuy, arXiv:2407.02215, 2024. GPU-adaptive tessellation
  for very uneven relief.
- **Precision.** K. Cozzi, K. Ring, *3D Engine Design for Virtual Globes*, 2011, ch. 5; N. Reed, "Depth Precision
  Visualized", 2015. Camera-relative integers and reversed-Z float depth.

## 6. Recommendation

### 6.1 What Diffusicraft has, and what it needs

Diffusicraft already owns the hard half of a far-terrain system: a generator that answers "what is at the top of this
column" without building a chunk. `worldgen.lod.LodSampler` fills `LodColumn`s (runs of blocks from the bottom up,
water depth, biome, tree crowns) for any grid of 64 × 64 columns at any spacing; `LodGrid` holds them and its
`STREAM_CODEC` sends a grid in 1.6 to 2.5 KB ([COMPATIBILITY.md](../COMPATIBILITY.md#voxy)); `LodFeed` serves grids
on two threads with a 128-grid cache. Inference dominates the cost (99 % of the pipeline's time on the CPU, 97 % on
CUDA, [PERFORMANCE.md](../PERFORMANCE.md#where-the-time-goes)), and grids wider than four terrain tiles already read
only the 7.7 km coarse map.

What is missing is everything DH and Voxy do after the data: storing it, overlaying real chunks, meshing, drawing,
culling, the seam with vanilla, and shader packs. The design below takes from each project what fits a
**heightfield-first, 32k-tall, Earth-scale** world, and leaves the rest.

### 6.2 The shape of the system

```text
             server (or the integrated server)                          client
 ┌──────────────────────────────────────────────────────┐   ┌──────────────────────────────────────────┐
 │ LodSampler ── generated tiles (pure function of seed) │   │ tile cache (disk, per server/dimension)  │
 │ chunk save/unload ── real tiles (level 0) ── merge ↑  │──▶│ mesher threads ── quad pool (one buffer) │
 │ tile store (real tiles + bounded generated cache)     │   │ near field: column tiles, levels 0..5    │
 │ far-field rasters (height + colour, 64² cells)        │──▶│ far field: heightfield clipmap           │
 │ LodFeed: requests, revisions, pushes, bandwidth caps  │   │ seam: stencil + loaded-volume depth      │
 └──────────────────────────────────────────────────────┘   └──────────────────────────────────────────┘
```

Two representations, one source:

- a **near field** of column tiles, blocky like the game, from the chunks' edge out to a few kilometres;
- a **far field** drawn as a smooth heightfield from rasters of height and colour, out to the horizon (tens of
  kilometres at 1:1).

Both come from `LodSampler`; real chunks overlay the near field.

### 6.3 Data model: copy DH's columns, not Voxy's cubes

**Unit.** A *tile* is 64 × 64 columns at level *L*; a column is 2^L blocks wide, so a tile is 64 · 2^L blocks, exactly
DH's section and Diffusicraft's `LodGrid.MAX_WIDTH`. Key: `(dimension, L, x, z)`. There is **no y in the key**, so
height never limits addressing.

**Run** (one `long`, replacing DH's 12-bit layout):

| Bits | Field | Why |
|---|---|---|
| 16 | bottom, blocks above the level's floor | 65,536 blocks; 32,736 needs 15 |
| 16 | height of the run | same |
| 20 | id into a per-world palette of (block state, biome) | as Voxy's 20-bit block id; biome folded in as DH does |
| 4 | sky light | real data only; generated data has analytic light |
| 4 | block light | torches, lava, villages at night |
| 4 | flags | e.g. "run is a tree crown", "under water" |

Per column: one byte of **source** (`GENERATED_COARSE`, `GENERATED_FINE`, `REAL`, `MIXED`), like DH's generation
step but naming the producer rather than a vanilla `ChunkStatus`. Per tile: a revision number and the generator
version.

Why columns:

- **Height costs nothing.** A 15,000-block mountain is still three to six runs. In Voxy's model the same column is up
  to 470 level-0 sections, each a database row and a 256 KiB array when loaded, and its key format cannot address it.
- **It is what Diffusicraft already makes.** `LodColumn` is runs; `LodGrid` is 64 × 64 columns; the codec exists.
  Nothing has to be voxelised and then re-merged.
- **Overhangs, floors and caves still fit**, as more runs per column. DH keeps the full list in storage and caps it
  only when drawing (`EDhApiVerticalQuality`); do the same.
- **Real chunks become columns cheaply** (section 6.5).

What to leave out of DH's model: the per-section string palette that needs pruning and renumbering after every merge
(DH once deleted every coarse level to undo palette bloat, `sqlScripts/0100`); use a **per-world append-only palette**
as Voxy does, persisted with each entry's data version, and a per-tile palette only on the wire (as `LodGrid` already
does).

### 6.4 Generated data: keep it a pure function

Every fast LOD path in the survey samples a surface function directly at the tile's resolution instead of building
chunks: DH 3.3's rough surface generator (1,024 searches per section at any level), the third-party DH SeedGen and
Turbo, Tellus's DH and Voxy paths, Voxygen 3.0's far terrain and Voxy Speed Gen's Fast mode (section 3.7). Chunk
generation is "on the order of several seconds" per chunk according to DH's author, and turning a chunk into an LOD is
under 10 % of that (section 2.7). Diffusicraft already does the fast thing; the recommendations are about scheduling
and caching it.

- **Generated tiles are never authoritative.** A tile is a function of (seed, level, x, z, generator version). Do not
  write them into the same store as real data, as DH does (everything there is `LIGHT` once a chunk was built, and a
  generated chunk cannot be told from a visited one). Keep generated tiles in a bounded cache that may be dropped at
  any time: the windows the models produced are already on disk (`DiskWindowStore`), so a miss over land computed
  before costs sampling, not inference.
- **Sample at the level's resolution; never downsample generated data.** DH builds coarse levels from children and has
  to guess (most common non-air block per slice); Voxy keeps the most opaque voxel, which turns shallow seas into
  land. A generated coarse tile comes straight from the sampler at its own spacing, which is already how
  `LodSampler` and the current DH and Voxy adapters work.
- **Fill coarse first, then refine**, as both projects do (DH divides distance by detail level; Voxy builds coarse
  meshes first and swaps in children only when all of them exist). The horizon then appears in seconds and detail
  follows.
- **Let the renderer drive requests.** Voxy's feedback (the traversal writes up to 50 missing nodes a frame, fewer as
  the backlog grows) asks only for what is on screen at the detail it needs. DH asks for every section of its quadtree
  in the radius, seen or not. A CPU-side version of Voxy's rule is enough at first (section 6.7).
- **Give inference to chunks first.** One model run at a time already fills the GPU or every core. LOD requests should
  wait behind chunk generation near players, as DH pauses world generation while the server is behind or the player
  moves faster than 20 blocks/s.
- **Measure a middle path.** Between full-resolution tiles (the decoder runs over every tile they touch) and the coarse
  map (7.7 km pixels) lies the latent stage's low-frequency elevation at 240 m per pixel. At 1:1 that is about one
  sample per level-8 column; reading it without running the decoder may halve the cost of the mid-distance tiles. This
  is untested.

### 6.5 Real chunks: DH's hooks on the server, Voxy's tricks in the walk

**Where.** On the server side, which includes singleplayer's integrated server, as DH does: the server sees every chunk
any player touched, not just the ones one client loaded, and it is where multiplayer data has to come from. Voxy's
client-only ingestion is why it has no multiplayer story and why third parties stream whole chunk sections to feed it
(section 3.7).

**When.**

- **chunk load from disk** and **chunk save** (DH's `ChunkMap.save` mixin), skipping unlit or proto chunks;
- **unload**, so the last edits are never lost;
- **a dirty flag per chunk on block change**: DH has no block hook and waits for the next save, Voxy catches only some
  edits. Set the flag on `setBlock`, rebuild at most every few seconds per chunk, and always on save;
- skip chunks whose content hash is unchanged (DH's `ChunkHash` table) and debounce 250 ms so neighbours arrive (DH);
- coalesce writes per tile for about a second before writing (DH: "merging writes together in memory significantly
  improves throughput").

**How.** Walk each column down from the higher of the heightmaps, as DH does, but read the `PalettedContainer`'s
palette and packed storage directly, as Voxy does, so that a uniform 16³ section (a `SingleValuePalette`, which is most
of a tall world's rock and all of its air) costs one step instead of 4,096 block reads. Close a run on a block or
biome change; store light only where it is not the analytic default. Drop runs that are hidden on every side
(DH's `VISUALLY_EQUAL`, which DH expects to cut the data by about 30 %), and cap nothing in storage.

**Overlay and propagation.** Real data is stored at level 0 and wins over generated data column by column (DH's merge
rule, without its rank of vanilla steps). Coarser tiles over real land are rebuilt bottom-up, DH style, from children
that are each either real or generated at that child's level. Because real land is a tiny fraction of an Earth-sized
world, only a few coarse tiles ever need this, and each stores a per-column `MIXED` mark so a renderer can tell. For
the merge, use the visible-from-above rule Diffusicraft already wrote for `LodGrid` (water over ground hides it),
then DH's slice merge below the top; not Voxy's opacity rule.

### 6.6 Storage: small, boring, no new natives

- **Store real tiles; cache generated ones.** Disk then grows with what players built and visited, not with view
  distance. DH stores everything it generated (user reports of 12 to 18 GB per modpack world, "hundreds of gigabytes"
  at 4,096 chunks) and Voxy stores every voxel it ever saw (one report of 190 GiB of LODs for a 185 GiB save).
- **Use Minecraft's own `RegionFile`** for the tile store: one directory per dimension and level, a tile's (x, z) as the
  "chunk" position, 4 KiB sectors, the compression schemes vanilla region files support (zlib by default, LZ4 since
  1.20.5), and the `LodGrid` codec as the payload. This adds no dependency. DH's SQLite and Voxy's RocksDB both ship
  native libraries (Voxy strips RocksDB's non-x86 natives by default and does not run on Windows on ARM); Diffusicraft
  already has enough native weight in ONNX Runtime.
- **Size.** At 1.6 to 2.5 KB per 64 × 64 grid, level 0 is about 0.4 to 0.6 MB per km² for terrain like the sampler's
  and perhaps 1 to 3 MB per km² for built-up real land (estimate), with all coarser levels adding a third. That is an
  order of magnitude under DH's and Voxy's estimates in sections 2.4 and 3.4, before counting that generated land is
  not stored at all.
- **Memory.** Do not keep decoded tiles: DH found that caching render data "did not speed up LOD loading" and keeps no
  LRU of full data. Keep meshes on the GPU, an LRU of compressed tiles on the heap, and Voxy's idea of a CPU-side mesh
  cache only if remeshing shows up in profiles.

### 6.7 Rendering: Mojang's API, Voxy's buffers, a heightfield for the far field

**Graphics API.** Draw through Minecraft's own renderer API (`com.mojang.renderpearl.api`: `GpuDevice`,
`RenderPipeline`, `RenderPass`), as Sodium does on 26.3 and as DH's `BLAZE_3D` backend does. Three facts decide it:

- **It runs on both of Minecraft 26.3's backends.** Sodium issues the same calls and gets multi-draw on OpenGL and
  `VK_EXT_multi_draw` or indirect draws on Vulkan (`gpu/device/backend/DrawBackend.java`, section 5.3). Voxy and
  Nvidium, written against raw OpenGL, do not run on Vulkan at all.
- **It has what the design needs.** `RenderPass.multiDrawIndexed`, `drawIndexedIndirect`, push constants and
  texel-buffer uniforms (`UniformType.TEXEL_BUFFER`, which Sodium uses for its section fade times): enough to draw
  every tile from one pooled buffer in one call and to pull vertices from a buffer.
- **Iris 26.3 can put a mod's pipeline under a shader pack.** Its public API has `assignPipeline(RenderPipeline,
  IrisProgram)` (API v0.3, with keys such as `TERRAIN_SOLID`), `assignPipelineShadow` and
  `registerShadowRenderCallback` (v0.4, a callback in the shadow pass after opaque terrain) (`IrisShaders/Iris` at
  `671a5c8`, `common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java:118-140`). DH's Iris support predates this
  and goes through OpenGL-only overrides, which is why DH refuses Blaze3D with Iris.

Raw OpenGL stays a fallback for anything the API lacks; whether it exposes compute shaders or storage buffers, which a
GPU-driven traversal would need, was not checked. Minecraft's OpenGL backend also covers macOS (OpenGL 4.1), which
Voxy's 4.6 requirement locks out.

**Meshing**, on worker threads, from runs (DH's `ColumnBox` logic): top and bottom faces culled against the runs above
and below, sides split by height against the neighbour column, so a cliff is one quad however tall. Greedy-merge tops
and sides (DH merges up to 2,048 blocks; Voxy caps at 16 voxels). Mesh against the neighbouring tiles' border columns
(DH stores them as separate strips for exactly this); keep DH's border overdraw instead of skirts.

**Quads.** Pack each quad into **8 to 12 bytes** and pull vertices in the shader from one big buffer, as Voxy does
(8 bytes per quad, no vertex buffer, a shared index buffer), instead of DH's 64 bytes of vertices plus 24 of indices
or Sodium's 80 bytes per quad. Positions are integers relative to the tile, and the tile's origin minus the camera's
integer position is added before anything becomes a float (DH's Blaze path, Voxy's section offset, Sodium's region
offset): at ±8,000,000 a float world coordinate has half-block steps. Under a shader pack mapped through
`assignPipeline`, the pack's vertex shader replaces ours and will read ordinary terrain attributes, so that path needs a
vanilla-style vertex layout, several times larger. Keep both layouts behind one mesher; which attributes Iris feeds
terrain programs is for the shader session to confirm.

**Colour.** One colour per (block state, biome) palette entry in a small lookup buffer, plus optional textures from the
vanilla atlas for the nearest levels (DH's textured LODs, on to detail 2 by default). Do not copy Voxy's model bakery:
its 12,288 × 8,192 atlas costs about half a gigabyte of VRAM, which a GPU already running Terrain Diffusion and a shader
pack cannot spare.

**Buffers and draws.** One pooled geometry buffer with an arena allocator (Sodium's shared arenas defragment a few
copies per frame), filled through a staging buffer with a per-frame budget (Voxy: a 64 MiB persistently mapped ring; DH:
at most half a frame of uploads); one multi-draw per pass (all tiles in a single call), with per-tile,
per-face-direction ranges so faces pointing away from the camera are never submitted (Voxy's per-section back-face
trick). DH's one-buffer-per-section, one-draw-per-buffer design, recreated on every rebuild, is what to avoid.

**Selection and culling.** With the far field taking the horizon, the near field has a few thousand tiles at most, so
start on the CPU: pick each tile's level from its projected size, Voxy's screen-space rule (refine above about 64²
pixels), with hysteresis; DH's distance-only rule ignores altitude, which matters when a player flies over 15 km
peaks. Frustum-cull with the tile's **real** height range, not the world's (DH tests every section against the full
world height, which in a 32k-tall world culls nothing vertically). Mountains hide most of the land at Earth scale, so
add occlusion culling next: a small depth pyramid of the previous frame, tested per tile (Voxy builds its pyramid with
fragment passes; FarPlaneTwo's MIT-licensed `tile_occlusion_test.comp` is a ready compute version, section 5.4).
Voxy's fully GPU-driven traversal is a later, optional tier.

**Far field.** Beyond the near field, draw a **heightfield clipmap** (Losasso and Hoppe 2004; CDLOD, Strugar 2009;
section 5.7): a few nested rings of a regular grid, displaced in the vertex shader from a toroidally updated height
texture, coloured from a matching colour texture, normals from the heights. Six bytes per cell (16-bit height, RGBA8
colour that bakes the dominant surface block, water, snow and canopy) and eight rings of 256² cells cost about 3 MB of
VRAM and a handful of draw calls for any view distance. The data is the top of each `LodColumn`, so it comes from the
same sampler, and on a server it is a stream of small raster tiles. Columns 256 blocks wide drawn as boxes, as DH and
Voxy do at that range, show 256-block stair steps on mountainsides; a heightfield does not. Blend near and far with a
dithered band, as DH blends with vanilla.

**The seam with vanilla and Sodium: Voxy's mechanism, DH's dither.**

- Draw LODs only where vanilla drew nothing (Voxy: a stencil written from vanilla's depth) and never inside the volume
  Sodium drew this frame (Voxy: a depth buffer of the back faces of the sections in Sodium's visible lists; FarPlaneTwo:
  a per-frame mask of vanilla sections that are built and whose neighbours are built). Holes where chunks have not yet
  loaded fill with LOD terrain, and LOD terrain never overlaps drawn chunks. If Mojang's pipeline state has no stencil
  (not checked), the same test runs in the fragment shader against vanilla's depth texture.
- Sodium never draws a chunk until it and all eight neighbours have block and light data, and draws nothing more than
  one render distance above or below the camera (`render/chunk/map/ChunkTracker`, `occlusion/OcclusionCuller`). The LOD
  must therefore cover the outer ring of received chunks and, in a 32k-tall world, all the ground under a camera flying
  high above it; a cut based on what Sodium actually drew does both.
- DH's alternative, a spherical discard distance with a Bayer-dithered band and a screen-space cross-fade, needs no
  knowledge of Sodium, but its FAQ explains holes at the edge of the vanilla render distance (chunks loading late) and
  low-detail blocks behind fences and ladders ("intended behavior, to prevent holes"). Use its dither only where the two
  fields meet.
- Render into a 32-bit float depth target with reverse-Z (Voxy detects reverse-Z from Blaze3D's default depth state;
  DH 3.3 uses it by default). Voxy's 24-bit fixed-point depth, with its 16-block near plane, resolves only about
  9 blocks at 48 km and worse beyond; float reverse-Z keeps about seven significant digits at any distance.

**Fog and haze.** At 1:1 the far field reaches distances where the atmosphere dominates. Replace vanilla's
render-distance fog in the LOD pass with an aerial-perspective term (cheap analytic scattering) that matches vanilla's
fog colour at the seam; DH's exponential fog over its own depth is the minimum. Earth curvature (a drop of d²/2R in
the vertex shader, 785 m at 100 km) is optional; DH clamps its curvature to 1:50 Earth.

**Shader packs.** A separate session covers this. The options this research found:

- **Iris's pipeline API** (above): no opt-in by packs, and a shadow callback. Open questions: the vertex layout terrain
  programs expect, and depth: packs project with vanilla's matrices, so LOD terrain tens of kilometres away must fit
  inside the projection's far plane and the packs' own fog and composite passes.
- **DH's contract** (`dh_terrain`, `dh_water`, `dh_shadow`, `dhDepthTex0/1`, `dhProjection`, the `DISTANT_HORIZONS`
  define): supported by Bliss, Complementary, BSL, Photon and about 25 more packs, but implemented inside Iris
  against DH's API classes.
- **Voxy's contract** (`voxy.json`, `voxy_emitFragment`, `vx*` uniforms, the `VOXY` define): Voxy writes straight into
  the pack's gbuffers; supported by BSL and Solas, in progress in Photon, Complementary and Bliss's unstable branch;
  Bliss 2.1.2, which the game tests use, has none (section 3.7). Its format is defined by All Rights Reserved code.

Whatever is chosen, keep material classes in the vertex data (DH's 4-bit `EDhApiBlockMaterial` lets packs treat water
and leaves differently) and decide on shadows early (Voxy draws none into the shadow map; Iris draws DH's).

### 6.8 Multiplayer: DH's protocol ideas over `LodFeed`

`LodFeed` already does the core of DH's on-demand path: the client asks, the server samples, answers, and refuses
requests beyond a radius or above an in-flight cap. Add:

- **real tiles**: served from the server's tile store, with generated tiles filling the rest exactly as today;
- **revisions instead of timestamps**: a per-tile revision (or content hash) lets a client revalidate its cache with
  "I have revision N" and get "unchanged" back, as DH's sync-on-load does with timestamps (DH has to correct for clock
  offset; its checksum field is "not used for anything");
- **pushes**: when a real tile changes, send it to players within a radius (DH's real-time updates, default 256 chunks),
  debounced and coalesced;
- **far-field rasters**: a separate, much cheaper request type (a 64² raster of 16-bit heights and colour indices is
  about 12 KB raw and a few KB compressed, by estimate);
- **limits**: DH defaults to 500 KB/s and 20 requests a second per player; Voxy's team cites bandwidth as the reason
  it has no server side. At about 2 KB per grid, 500 KB/s is 250 grids a second, which is plenty;
- **a client cache** keyed by server and dimension (DH's `Distant_Horizons_server_data/<server>`; Voxy's server
  address folder, which "resets" when the address changes, so prefer a server-provided id).

Clients never run the models in multiplayer; the server answers everything, as today.

### 6.9 Copy, adapt, avoid

| Choice | From | Verdict | Why |
|---|---|---|---|
| 64 × 64-column tiles, runs per column | DH | **copy** | height-independent, matches `LodColumn`/`LodGrid` |
| 12-bit run heights | DH | **avoid** | caps at 4,096 blocks; use 16 bits |
| 8-bit section y in a 3D key | Voxy | **avoid** | caps at ±4,096; 2D keys need no y |
| per-section string palette with pruning | DH | avoid | use a per-world append-only palette (Voxy) |
| per-column generation step, real beats generated | DH | **adapt** | name the source, not vanilla steps |
| N-sized generation from a surface function | DH 3.3, SeedGen, Tellus | **copy** | Diffusicraft already does it |
| coarse levels from children, most common non-air | DH | adapt | only for real land, with a visible-from-above top |
| most opaque voxel wins | Voxy | **avoid** | shallow seas become land |
| server-side ingestion on load/save | DH | **copy** | needed for multiplayer and all players' builds |
| client-only ingestion | Voxy | avoid | no multiplayer |
| reading `PalettedContainer` storage directly | Voxy | **copy** | uniform sections cost one step |
| block-change dirty flag | neither | add | DH waits for saves, Voxy catches some edits |
| SQLite / RocksDB + native libraries | DH / Voxy | avoid | vanilla `RegionFile` is enough for 2D tiles |
| store generated data | both | **avoid** | it is reproducible; store real data only |
| Zstd per blob | DH | adapt | LZ4 or deflate from vanilla first; Zstd if size matters |
| one buffer and one draw per section | DH | **avoid** | pooled buffer and multi-draw |
| 8-byte quads, vertex pulling | Voxy | **copy** | 7–11 times less than DH's 88 bytes per quad |
| software-baked model atlas | Voxy | avoid | about 0.5 GB of VRAM |
| per-vertex baked colour, material class | DH | adapt | palette colours plus material bits |
| screen-space refinement | Voxy | **copy** | accounts for altitude |
| distance-only detail, full-height frustum boxes | DH | avoid | wrong for 32k-tall worlds |
| GPU traversal, HiZ, MDI count | Voxy | later | HiZ first; GPU traversal optional |
| Mojang's renderpearl API (Blaze3D) | DH, Sodium 26.3 | **copy** | OpenGL and Vulkan; Iris `assignPipeline` |
| raw OpenGL 4.6 only | Voxy | avoid | no Vulkan, no macOS |
| stencil + drawn-section depth seam | Voxy | **copy** | no holes, no overlap |
| vanilla-renderability mask | FarPlaneTwo | **copy** | the same cut as data the culler can use |
| dithered discard and cross-fade | DH | adapt | only between near and far fields |
| own colour/depth, D24S8 | Voxy | adapt | 32F reverse-Z depth |
| `voxy.json` / `dh_*` pack contracts | both | decide with the shader session | both need packs to opt in |
| timestamps for sync, whole-section pushes | DH | adapt | revisions; whole tiles are fine at 2 KB |

### 6.10 Effort

Estimates for one developer who knows the code base, in weeks, assuming the shader-pack work is scoped separately.

| Part | Weeks | Depends on |
|---|---|---|
| Tile format: 16-bit runs, world palette, source marks, codec (extending `LodColumn`/`LodGrid`) | 1–2 | |
| Tile store on vanilla `RegionFile`, revisions, generated-tile cache | 1–2 | format |
| Request scheduling: coarse first, renderer-driven, yielding to chunk generation | 1–2 | format |
| Mesher: runs to quads, greedy merge, neighbour strips, border overdraw | 2–3 | format |
| Renderer on Mojang's API: pipelines, pooled buffer, staging, multi-draw, levels, frustum | 3–5 | mesher |
| Seam with vanilla and Sodium (stencil, built-section depth, mixins), fog | 1–2 | renderer |
| Far field: rasters from the sampler, clipmap, blend band, haze | 2–3 | renderer |
| Real chunks: hooks, palette walk, dirty flags, merge, upward propagation | 2–3 | format, store |
| Multiplayer: real tiles, revisions, pushes, rasters, client cache, limits | 2–3 | store, real chunks |
| HiZ occlusion culling | 1–2 | renderer |
| Game tests (CPU and CUDA), benchmarks, docs | 2–3 | all |
| **Total, first complete version** | **18–30** | |
| Optional: GPU-driven traversal (compute, MDI count), in raw OpenGL if Mojang's API lacks compute | 3–4 | renderer |
| Shader-pack integration | see the shader research | renderer, seam |

A useful order: format, store and scheduling; mesher and renderer with generated tiles only (this already replaces
the DH and Voxy integrations in singleplayer); the seam; real chunks; multiplayer; the far field; occlusion culling.

### 6.11 Risks and open questions

- **Sodium hooks.** The seam needs the sections Sodium drew and a draw call at the right point of its frame, as
  Voxy's does; those mixins break on Sodium updates, like the ones Diffusicraft already keeps (`client/mixin/sodium`).
- **Mojang's renderer API is young.** `RenderPipeline` moved from `com.mojang.blaze3d.pipeline` to
  `com.mojang.renderpearl.api.pipeline` between 26.1 and 26.3 (Iris's API on its two branches), and DH and Sodium both
  carry version-specific code for it; expect work on every Minecraft update.
- **Iris.** DH and Voxy both need packs to opt in; Iris's pipeline API does not, but its vertex and depth assumptions
  are untested for terrain tens of kilometres away. Which route to take (that API, DH's `dh_*` programs, which Bliss
  and Complementary support, or Voxy's `voxy.json`) is the shader session's question, and it decides how far "works
  under Bliss" is from "works".
- **macOS** caps OpenGL at 4.1, so anything beyond what Mojang's OpenGL backend offers there must stay optional.
- **Inference contention.** The far field over tens of kilometres wants decoder tiles that chunk generation also
  wants. The scheduling rule in section 6.4 and the 240 m middle path need measuring before promising view distances.
- **Real data at coarse levels** is lossy by nature; a city 20 km away will be a few coloured columns. That matches DH
  and Voxy and is acceptable.
- **The AMD ROCm path** has never run on AMD hardware; nothing here changes that.

## 7. Not found

Searched for and not found, or deliberately left to other work:

- **DH**: a measured cost per chunk or chunks per second for any generation mode (DH only measures them at run time);
  official bytes per section or per km² (section 2.4 gives an estimate); the behaviour of the removed `BIOME_ONLY` and
  `BIOME_ONLY_SIMULATE_HEIGHT` modes (only their translation strings remain, and the clones are shallow); a server-side
  block-change or light hook; GPU occlusion culling or multi-draw; the pre-2.3 file format; talks or write-ups by its
  author.
- **Voxy**: any official size, VRAM or frame-rate figure; importer throughput (printed at run time only); why it chose
  RocksDB and LMDB; any design write-up; an official height limit; dithering or cross-fading anywhere; a per-quad
  translucency sort; an API.
- **Shader packs** (for the separate shader research): Iris's own DH compatibility code was not read (the `dh_*` names
  come from Iris's ShaderDoc); which Iris terrain attributes a pipeline mapped with `assignPipeline` must supply;
  whether Bliss or Complementary ship a `voxy.json` (only a community list).
- **Mojang's 26.3 renderer API**: whether it exposes compute shaders, storage buffers or stencil state was not checked;
  multi-draw, indirect draws, push constants and texel buffers were seen in Sodium's use of it.
- **Other mods**: "Simple LOD", "Far Horizons", "Voxel Distant Terrain" and Distant Horizons for Bedrock.
- **Web**: Reddit, YouTube transcripts and the DH Discord mirror could not be read; only titles and search snippets.
