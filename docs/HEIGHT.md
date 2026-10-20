# Build height beyond 4064

With Diffusicraft installed, a dimension type can be up to **32,736 blocks tall**: Y from −16,368 to 16,367. Vanilla
allows 4064. The price is horizontal range: worlds end at **±8,000,000 blocks** instead of ±30,000,000.

Earth's highest mountain rises 8,849 m above the sea. At 1 block per meter that fits easily, because the underground
and the oceans keep their game depth (see [Costs](#costs)).

Diffusicraft Grand runs from −256 to 2383 and Diffusicraft Earth 1:1 from −256 to 9087, so real mountains keep their
height (see [WORLDGEN.md](WORLDGEN.md#from-meters-to-blocks)). Their chunks fill only up to their ground (see
[Filling only to the ground](#filling-only-to-the-ground)). Any taller dimension type is plain data:

```json
{ "min_y": -16368, "height": 32736, "logical_height": 32736, ... }
```

Both `min_y` and `height` must still be multiples of 16. The test dimension in
`src/gametest/resources/data/diffusicraft-gametest/` is a complete example.

## Why vanilla stops at 4064

Minecraft packs a block position into one `long`: 26 bits each for X and Z and 12 for Y. Every height limit follows
from those 12 bits:

- `BlockPos` derives 26 bits from the world size of 30,000,000, and gives the rest of the `long` to Y.
- `DimensionType.BITS_FOR_Y` is `BlockPos.PACKED_Y_LENGTH`, so `Y_SIZE = 2^12 − 32 = 4064`,
  `MIN_Y = −2032` and `MAX_Y = 2031`. The dimension type codec validates against these.
- Noise settings, vertical anchors, flat layers, heightmaps and chunk coordinates all derive their limits from the
  same fields.

None of these are compile-time constants, so they all follow `BlockPos`.

## The new layout

| | Vanilla | Diffusicraft |
|---|---|---|
| Packed X / Y / Z bits in memory | 26 / 12 / 26 | 24 / 16 / 24 |
| Y bits for dimension types | 12 | 15 |
| Tallest dimension | 4064 (−2032 to 2031) | 32,736 (−16,368 to 16,367) |
| World limit | ±30,000,000 | ±8,000,000 |
| Largest world border | 29,999,984 | 7,999,984 |

All the numbers live in [`ExtendedHeight`](../src/main/java/io/github/elpideus/diffusicraft/height/ExtendedHeight.java).
The mixins in [`mixin/height`](../src/main/java/io/github/elpideus/diffusicraft/mixin/height) apply them:

- **`BlockPos`.** It derives its bits from 2^23 instead of 30,000,000, which gives 24 horizontal bits and 16 for Y.
  Everything derived from `BlockPos` follows.
- **`DimensionType`.** Y is capped at 15 bits, not 16. The network format below has room for 15. A 16-bit dimension
  would also double the memory per column (see [Costs](#costs)).
- **World limits.** The ±30,000,000 literals in `Level`, `Entity`, `ServerGamePacketListenerImpl`,
  `ForceLoadCommand` and `WorldBorder` become ±8,000,000. Without this, blocks past 2^23 would wrap around in packed
  positions.

### On the network

Positions keep vanilla's wire layout whenever Y fits in 12 bits, so dimensions of vanilla height look exactly like
vanilla on the wire.

Taller positions use a second layout in the same 64 bits:

```text
X field (26 bits):  [Y14] [¬Y14] [X: 24 bits]
Z field (26 bits):  [Y13 Y12]    [Z: 24 bits]
Y field (12 bits):  [Y11 … Y0]
```

Vanilla longs with |X| < 2^24 always have the top two bits of the X field equal. In the tall layout they differ, so
the reader can tell the two apart. `FriendlyByteBuf.writeBlockPos` and `readBlockPos` convert, and the rest of the
game keeps using in-memory packing.

**Chunk packets.** A chunk packet may carry up to 8 MiB of block data (vanilla: 2 MiB). A column of 2046 sections
needs more than 2 MiB once its sections hold varied blocks: the densest test column is 5.4 MiB. Over a real
connection, a dedicated server compresses each packet before it frames it. A frame holds up to 2 MiB compressed, and
the client inflates a packet to at most 8 MiB. The densest test column compresses to 64 KiB and reaches the client of a
dedicated server whole ([Tests](#tests)). A column reaches either limit only when about two thirds of its sections each
hold more than 16 kinds of block, or several thousand blocks of it hold blocks at random. Neither terrain nor builds
come near that. A column that did would disconnect the player, as vanilla does with any packet that is too large.

### In saves

Vanilla stores each section's Y as a byte, which holds sections −128 to 127 (Y −2048 to 2047). Sections outside that
range get an integer `Y` tag instead, and the reader accepts both.

Above the ground almost every section of a tall column is plain air with the biomes of the section below it, and
vanilla writes each of them with its two palettes. Diffusicraft leaves such sections out of the save when they also
have no light data, and loading fills each missing section with air and the biomes of the section below it
([`AirSections`](../src/main/java/io/github/elpideus/diffusicraft/height/AirSections.java)). The empty test column
writes 4 of its 2046 sections. In Earth 1:1 a chunk writes the sections up to its ground: about 20 of its 584 near
the sea, and 361 around the Earth test's summit at y = 5654. Light data is stored near blocks and in the shade under
them, so a block hanging high in the air keeps the sections below it in the save.

Sections are left out only in dimensions that cannot open without the mod: those taller than vanilla allows, and
those of Diffusicraft's own dimension types (Grand and Earth 1:1). Chunks of every other dimension are written exactly
as vanilla writes them. Tools that read region files with code of their own see the left-out sections as missing.

## Memory

Minecraft allocates every section of a loaded column, even empty ones, so a tall dimension would cost memory in
proportion to its height. Diffusicraft makes it follow what the dimension contains instead.

### Empty sections

A section holds two paletted containers, one for blocks and one for biomes. In vanilla an empty section costs about
450 bytes, because each container has its own data (a palette with its one value and a storage of zero bits) and its
own `ThreadingDetector`, which holds a semaphore and a lock.

- **Shared data.** Containers that hold a single value share one read-only copy of their data, one per value
  ([`SharedContainerData`](../src/main/java/io/github/elpideus/diffusicraft/height/SharedContainerData.java)). A
  container gets it when it is created, copied, loaded or read from a packet with one value, and a biome container
  also when world generation has filled it with one biome. Writing a different value resizes the container as in
  vanilla, which gives it data of its own: copy on write. A section that world generation fills with a single block,
  such as deep rock or the sea, gets shared data when its chunk is complete
  ([`LevelChunkMixin`](../src/main/java/io/github/elpideus/diffusicraft/mixin/height/LevelChunkMixin.java)). The
  check compares the packed storage a 64-bit word at a time, so it costs little even on the main thread.
- **No threading detector.** The detector never serialises anything: when two threads use a container at once, it
  turns the race into a crash report. Every container gets one shared detector that checks nothing
  ([`NoThreadingDetector`](../src/main/java/io/github/elpideus/diffusicraft/height/NoThreadingDetector.java)), as
  Lithium does by default. This applies to every dimension, tall or not.

What is left of an empty section is the section and its two containers: about 80 bytes. Sections themselves are not
shared, because the game and mods write to the sections of a chunk directly.

### Sections with a few blocks

Vanilla stores the blocks of every section that holds 2 to 16 different blocks at 4 bits per block, 2 KiB a section.
A tall world holds thousands of blocks of rock in each column, and most of its sections hold only a few kinds:
around the highest ground near the spawn of an Earth 1:1 world, half of the mixed sections hold two blocks and most
of the rest three or four. Diffusicraft stores sections of 2 blocks at 1 bit per block, 3 or 4 blocks at 2 bits and 5
to 8 blocks at 3 bits, as vanilla already does for biomes
([`NarrowLinearConfiguration`](../src/main/java/io/github/elpideus/diffusicraft/height/NarrowLinearConfiguration.java)).
A section that gains a block grows a bit at a time, as in vanilla.

- **Saves** keep vanilla's 4 bits per block, so a world opens the same without the mod; sections are narrowed again
  as they load.
- **Chunk packets** carry the narrower storage. Only a client with the mod reads them, which it needs anyway to join,
  since the mod adds blocks.
- **Lithium** swaps vanilla's 4-bit palette for a hash palette of its own and saves sections with its own code. Both
  pass through the same choice of configuration, so its sections are narrowed too and still saved at 4 bits.

### The client's render grid

The client keeps render data in a grid around the camera: a node for each section within the render distance
horizontally and, in vanilla, for every section row of the level. At render distance 12 in a 32,736-block dimension
that is 625 columns of 2046 rows, about 1.3 million nodes, of which the game only ever draws the 25 rows nearest the
camera.

Diffusicraft gives the grid a window of rows around the camera
([`RenderWindow`](../src/main/java/io/github/elpideus/diffusicraft/height/RenderWindow.java)): the camera's row and
`verticalRenderDistance` rows above and below it, the render distance by default (see
[CONFIG.md](CONFIG.md#verticalrenderdistance)). The window moves with the camera, as the grid already does
horizontally. Dimensions no taller than the window keep vanilla's grid.

**With Sodium** the window does nothing. Sodium replaces vanilla's grid with its own, which keeps render data for
every section of every loaded chunk, so its memory still grows with the height of the dimension. Sodium also counted
the heights of the sections it builds from y = −2048 in 10 bits, which never built anything above y = 14,335;
Diffusicraft counts them from below the camera instead (see [COMPATIBILITY.md](COMPATIBILITY.md#sodium)).

## Compatibility

- **Vanilla clients** should be able to join a Diffusicraft server whose dimension types all fit vanilla's limits.
  The server sends every dimension type when a client joins, so a single taller one locks vanilla clients out of
  the whole server. Neither case has been tried with a vanilla client yet.
- **A Diffusicraft client on a vanilla server** works as long as players stay within ±8,000,000 blocks. Beyond
  ±16,777,216, positions from the server are misread.
- **Existing worlds.** Adding or removing the mod does not move anything in dimensions of vanilla height: vanilla
  saves block positions as three integers, never as packed `long`s. Mods that save `BlockPos.asLong()` values do
  depend on the packing, and would read them wrongly after the mod is added or removed.
- **Mods that pack positions by hand** (shifts of 26, 12 and 38 bits) or assume ±30,000,000 break. Mods that use
  `BlockPos.asLong` and `BlockPos.of` keep working.
- **Mods that patch paletted containers.** Lithium and FerriteCore also remove the threading detector, and Moonrise
  replaces it with one of its own. The shared data and the detector change are written to work alongside all three:
  they never overwrite a method, and never assume the detector field is set.
- **Distant Horizons** stores heights in 12 bits, so its LODs hold 4096 blocks above the bottom of the level. Taller
  levels are cut off there in the LODs instead of coming out garbled: in Earth 1:1 they stop at y = 3838, and higher
  ground shows flat in the far terrain (see [COMPATIBILITY.md](COMPATIBILITY.md#distant-horizons)).
- **Voxy** packs the height of its sections into 8 bits, so it holds y = −4096 to 4095. Taller levels used to stop
  the game as soon as Voxy started in them; Diffusicraft now keeps Voxy's renderer and its store within those
  heights, and leaves out of it the sections beyond (see [COMPATIBILITY.md](COMPATIBILITY.md#voxy)). In Earth 1:1,
  ground above y = 4095 shows cut flat in Voxy's far terrain; the chunks themselves are whole. The far terrain
  Diffusicraft fills Voxy with sinks there, as it does for Distant Horizons.
- **Sodium** built no section above y = 14,335 or below y = −2048, so the tallest dimensions showed nothing at
  their top or bottom. Diffusicraft lets it build sections at every height (see
  [COMPATIBILITY.md](COMPATIBILITY.md#sodium)); the tall-dimension game test runs with it.
- **Untested:** Iris, Lithium, FerriteCore, Moonrise and C2ME in a tall dimension. Test each of them in one before
  relying on it.

## Costs

Minecraft allocates every section of a loaded column, even empty ones. A 32,736-block column has 2046 sections;
a vanilla overworld column has 24. Measured in a client game test (render distance 4, software rendering), before and
after the changes in [Memory](#memory):

| Memory and loading | Before | After |
|---|---|---|
| Memory per empty section | about 450 bytes | about 80 bytes |
| Memory per empty 32,736-block column | about 0.9 MiB | about 160 KiB |
| Heap in the vanilla overworld | 258 MiB | 247 MiB |
| Heap after entering the tall dimension at y = 16,000 | 2,132 MiB | 715 MiB |
| Heap after then visiting y = −16,000 | 2,260 MiB | 746 MiB |
| Heap after then visiting the dense column | 2,724 MiB | 848 MiB |
| Sections around the player whose data is shared, on the server and on the client | none | 165,634 of 165,726 |
| Nodes in the client's render grid | 165,726 (2046 rows) | 729 (9 rows) |
| Time to load and render the area at y = 16,000 | 1.9 s | 1.8 s |
| Time to load and render the dense column | 3.2 s | 2.4 s |

The heap is measured after a full garbage collection, and the times come from one run each, so take them as rough.
Saving, before and after leaving empty sections out (see [In saves](#in-saves)):

| Saving | Every section | Without empty sections |
|---|---|---|
| Sections written for the empty column | 2046 of 2046 | 4 of 2046 |
| Empty column saved: NBT / compressed on disk | 213 KiB / 6 KiB | 18 KiB / under 1 KiB |
| Encoding the empty column for saving | 7 ms | 1 ms |
| Densest column saved: NBT / compressed / encoding | 4.8 MiB / 46 KiB / 66 ms | the same |
| Stopping the test world's server | 12 s | 3 s |

What the rest of the tall dimension costs did not change:

| The rest | Value |
|---|---|
| Sections in memory after visiting the top, the bottom and the dense column (server and client) | 3.2 million |
| Densest column's block data in a chunk packet | 5.4 MiB |

- **Memory.** An empty section costs about 80 bytes instead of 450 (see [Empty sections](#empty-sections)), and the
  client keeps render data for the rows near the camera only (see [The client's render grid](#the-clients-render-grid)).
  The server also keeps the proto-chunks of its generation ring, so it holds many more columns than the player sees.
- **Saving.** Sections with blocks, light or a change of biome are written with their palettes, so a column takes
  time to save in proportion to what it holds, not to its height: 1 ms for the empty tall column, under 1 ms for a
  vanilla one. Stopping the server saves every loaded column, which took 3 seconds instead of 12 in the test world.

So a tall dimension still costs some memory in proportion to its height, whatever it contains. Make dimensions only
as tall as the terrain needs. Earth 1:1 keeps the underground and the oceans game-sized, because the fun is on the
surface: `min_y` stays −256 and the oceans stay compressed to at most 200 blocks deep. Only the top moves.
Its mountains keep their height up to 8 km and ease above that, so its highest ground is y = 9063 and the world runs
from −256 to 9087: 9,344 blocks, or 584 sections per column.

Real terrain is not empty. The Earth 1:1 game test stands over the highest ground near the spawn, at y = 5654, with
600 columns loaded around it at render distance 12: 350,400 sections, 161,000 of them mixed. The block storage is
counted on the server; the heap is the whole game, server and client, after a full garbage collection. One run each:

| Around the Earth 1:1 summit | Block storage | Heap |
|---|---|---|
| Single-block sections from world generation keeping data of their own | 425 MiB | 2,855 MiB |
| Shared once their chunk is complete ([Empty sections](#empty-sections)) | 317 MiB | 2,548 MiB |
| And 1 to 3 bits per block for sections with a few blocks ([Sections with a few blocks](#sections-with-a-few-blocks)) | 163 MiB | 1,942 MiB |

Of the mixed sections, 152,800 are stored narrower than 4 bits per block. The chunks took as long to be ready each
time (52 to 54 seconds).

## Filling only to the ground

Minecraft fills a new chunk from the bottom of the world to the top, working out the density of every block: 2.4
million blocks for a chunk of Earth 1:1, nearly all of them air. Diffusicraft fills it only up to its ground:

- **The ceiling.** `TerrainCeiling` works out the highest block a chunk can hold before it is filled. The density of
  the terrain is interpolated between the corners of cells 4 blocks wide and 8 tall, so it never rises above its
  highest corner; the ceiling takes the terrain at those corners, the banks and water of the chunk's rivers and
  lakes, and the sea, plus one cell. Above it everything is air: caves, the Hollows and the fade at the top of the
  world only take ground away.
- **Structures** that reshape the ground (villages, ancient cities) add density near their pieces, so the chunk fills
  up to their tops, plus the 24 blocks their boxes are widened by.
- **The fill.** `NoiseBasedChunkGeneratorMixin` gives `createNoiseChunk` that height, so the noise, the aquifer and
  the blocks stop there. The sections above stay empty and the surface rules skip them, as they skip any empty
  section. Carvers stop at the same height.
- **The ground of a column**, which structures look for (`getBaseHeight`), stops at the column's ceiling too.
- **Blended chunks**, next to chunks from an older version, keep the full fill, since the blending reads the old
  terrain.

The blocks come out the same either way: the cells and the aquifer's grid are aligned to absolute positions, not to
the part of the chunk that is filled. `TerrainCeilingTest` checks the ceiling over rivers and the sea, and the Earth
1:1 game test checks that the chunks around the highest ground near the spawn hold all of it.

It saves less time than the air it skips suggests. In the Earth 1:1 game test the chunks around the summit, at
render distance 12, were ready in 52 seconds against 57 with the full fill (one run each, on the CPU): most of a
chunk's time goes to the rest of its generation.

## Tests

- [`ExtendedHeightTest`](../src/test/java/io/github/elpideus/diffusicraft/height/ExtendedHeightTest.java) (part of
  `./gradlew build`) round-trips the wire format and checks it against vanilla's packing.
- [`SharedContainerDataTest`](../src/test/java/io/github/elpideus/diffusicraft/height/SharedContainerDataTest.java)
  checks the word-at-a-time uniformity check at every width a palette uses, with the odd entry anywhere.
- [`NarrowLinearConfigurationTest`](../src/test/java/io/github/elpideus/diffusicraft/height/NarrowLinearConfigurationTest.java)
  checks which configurations are narrowed, vanilla's and Lithium's, and that a narrow palette grows a bit at a time.
- [`TallDimensionGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/TallDimensionGameTest.java)
  runs the real game in a 32,736-block dimension. It checks:
  - blocks at y = ±16,000 and at the top of the world, on the server and the client;
  - changes in both directions over the network: the server places blocks, and the client breaks one;
  - block light, sky light and heightmaps near the top;
  - a column with all 2046 sections full of varied blocks;
  - that empty sections share their data, on the server and the client;
  - that the client's render grid holds only the rows around the camera;
  - saving and reloading the world, with the empty sections of a column left out of the save and their biomes back
    after loading;
  - the same on a dedicated server, whose packets go over a socket in compressed frames: blocks at both ends and at
    the top of the world, changes in both directions, light, heightmaps and the dense column.

  It saves screenshots of the top, the bottom and the dense column, and of the dense column on the dedicated server,
  each once the section of a block in view is built. It passes with Sodium and Voxy too (`-PdevMods=voxy,sodium`).
- [`TerrainCeilingTest`](../src/test/java/io/github/elpideus/diffusicraft/worldgen/river/TerrainCeilingTest.java)
  checks that the ceiling of each column is under its chunk's and above its rivers, lakes and the sea.
- [`EarthWorldGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/EarthWorldGameTest.java)
  creates an Earth 1:1 world and compares the chunks around the highest ground near the spawn with the terrain: their
  ground with the LODs, and their highest block with the summit. It saves and sends a few hundred of their narrow
  sections and reads them back, and logs what the chunks cost in memory.

## Mixin targets

These break when Mojang changes them. Check them on every Minecraft update:

| Mixin | Target | What it relies on |
|---|---|---|
| `BlockPosMixin` | `BlockPos.<clinit>` | the `30000000` literal |
| `DimensionTypeMixin` | `DimensionType.<clinit>` | the read of `BlockPos.PACKED_Y_LENGTH` |
| `FriendlyByteBufMixin` | `FriendlyByteBuf.writeBlockPos` / `readBlockPos` (static, `ByteBuf`) | the `asLong` and `readLong` calls |
| `SerializableChunkDataMixin` | `SerializableChunkData.parse` / `write` / `copyOf` | locals `y`, `sectionTag` and `sectionData`; `putByte("Y", …)`; the `new SerializableChunkData` at the end of `parse` and `copyOf` |
| `ClientboundLevelChunkPacketDataMixin` | `ClientboundLevelChunkPacketData.<clinit>` | the `2097152` literal |
| `LevelMixin` | `Level.isInWorldBoundsHorizontal`, `Level.getHeight` | the ±`30000000` literals |
| `EntityMixin` | `Entity.absSnapTo(DDD)V` | the ±`3.0E7` literals |
| `ServerGamePacketListenerImplMixin` | `ServerGamePacketListenerImpl.clampHorizontal` | the ±`3.0E7` literals |
| `ForceLoadCommandMixin` | `ForceLoadCommand.changeForceLoad` | the ±`30000000` literals |
| `WorldBorderMixin` | `WorldBorder.<init>(WorldBorder$Settings)`, `setAbsoluteMaxSize` | the `29999984` literal |
| `PalettedContainerMixin` | `PalettedContainer.<init>` (all three), `read`, `createOrReuseData` | `new ThreadingDetector` in the field initialiser; the `data` and `strategy` fields; `createOrReuseData` picking its configuration with `getConfigurationForBitCount(targetBits)`, and reusing the old data when the configuration stays the same |
| `StrategyMixin` | `Strategy`, `Strategy.getConfigurationForPaletteSize` | `getConfigurationForBitCount(0)` being the single-value configuration; `entryCount`; `getConfigurationForPaletteSize` calling `getConfigurationForBitCount`, which gives block states a 4-bit configuration for 1 to 3 bits; saves and packets reading `bitsInStorage` and `bitsInMemory` as they do |
| `SingleValuePaletteAccessor` | `SingleValuePalette` | the `value` field; `idFor` and `read` being the only methods that change it |
| `LevelChunkSectionMixin` | `LevelChunkSection.fillBiomesFromNoise` | the `biomes` field holding the new container at the end |
| `LevelChunkMixin` | `LevelChunk.<init>(ServerLevel, ProtoChunk, PostLoadProcessor)` | the new chunk keeping the proto-chunk's sections; no generation step writing blocks after it |
| `diffusicraft.classtweaker` | `PalettedContainer$Data` | the record and its constructor |
| `NoiseBasedChunkGeneratorMixin` | `NoiseBasedChunkGenerator.createNoiseChunk`, the `clampToHeightAccessor` call in `iterateNoiseColumn` | `createNoiseChunk` taking the `NoiseSettings` the chunk is filled with, and being called only from `fillFromNoise`'s worker |
| `dh.ChunkWrapperMixin` (Distant Horizons) | `ChunkWrapper.getExclusiveMaxBuildHeight()`, `getMaxNonEmptyHeight`, `getSolidHeightMapValue`, `getLightBlockingHeightMapValue` | Distant Horizons building LODs from these four; check it on every Distant Horizons update too |
| `voxy.VoxyRenderSystemMixin` (client, Voxy) | `VoxyRenderSystem.<init>` | its `new RenderDistanceTracker(…, minSection, maxSection, …)` taking the range of Voxy's top-level sections, at index 1 and 2; check it on every Voxy update too |
| `voxy.WorldUpdaterMixin` (client, Voxy) | `WorldUpdater.insertUpdate(WorldEngine, VoxelizedSection)` | every chunk section Voxy keeps passing through it; the `y` field of `VoxelizedSection` in chunk sections |
| `voxy.ActiveSectionTrackerMixin` (client, Voxy) | the call to `SectionLoader.load` in `ActiveSectionTracker.acquire(long, boolean)` | every section Voxy reads coming through it once; status 1 meaning the store never held it, after which Voxy fills it with air; the members declared in `src/voxyStubs` (not about height: the far terrain, see [COMPATIBILITY.md](COMPATIBILITY.md#voxy)) |
| `sodium.TaskCollectingTreeMixin` (client, Sodium) | `TaskCollectingTree.addPendingSection`, `getPendingTaskLists` | `SECTION_Y_MIN` (−128) inlined once in `addPendingSection`; `getPendingTaskLists` returning the `DeferredTaskList` its tasks go to; check it on every Sodium update too |
| `sodium.DeferredTaskListMixin` (client, Sodium) | `DeferredTaskList.dequeueNextSectionPos` | `SECTION_Y_MIN` (−128) inlined once, to unpack the height |
| `sodium.AbstractSectionVisitorMixin` (client, Sodium) | `AbstractSectionVisitor` | the `baseOffsetY` field, the lowest section of the search, which `TaskCollectingTree` inherits |
| `RenderGridMixin` (client) | `ViewArea.<init>`, `SectionUpdateTracker.<init>` | `new RotatingSectionStorage(radius, minSectionY, maxSectionY, …)`; the `RotatingSectionStorage` methods that `WindowedSectionStorage` overrides |

Also search the new version for other uses of `30000000`, `3.0E7` and `29999984`, and for code that packs positions
without `BlockPos.asLong`.

## Next steps

- **A switch.** A config option to keep vanilla's packing for players who need ±30,000,000 worlds.
- **Compatibility.** Distant Horizons and Voxy could hold all of Earth 1:1 by drawing their LODs from a raised
  bottom, at the cost of the deepest underground.
- **Only nearby heights on the wire (not for now).** The server would send each client only the sections near the
  player, as Cubic Chunks does, so a client's memory and bandwidth would follow what it sees instead of the height of
  the dimension. Every mod that expects whole columns on the client would break. If it is ever done it will be opt-in,
  and the player gets a warning about incompatible mods when they choose it.
