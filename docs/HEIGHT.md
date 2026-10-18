# Build height beyond 4064

With Diffusicraft installed, a dimension type can be up to **32,736 blocks tall**: Y from −16,368 to 16,367. Vanilla
allows 4064. The price is horizontal range: worlds end at **±8,000,000 blocks** instead of ±30,000,000.

Earth's highest mountain rises 8,849 m above the sea. At 1 block per meter that fits easily, because the underground
and the oceans keep their game depth (see [Costs](#costs)).

This is the engine side only. The Diffusicraft world types still use −256 to 2031 (see [WORLDGEN.md](WORLDGEN.md)).
A taller dimension type is plain data:

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
needs more than 2 MiB once its sections hold varied blocks: the densest test column is 5.4 MiB. On a dedicated server,
packets are compressed before they are framed, and a frame holds up to 2 MiB compressed. Real terrain compresses far
below that, but the worst case has not been tested over a real connection.

### In saves

Vanilla stores each section's Y as a byte, which holds sections −128 to 127 (Y −2048 to 2047). Sections outside that
range get an integer `Y` tag instead, and the reader accepts both. Chunks of vanilla-height dimensions are written
exactly as vanilla writes them.

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
  such as deep stone, keeps its own data until it is saved and loaded again.
- **No threading detector.** The detector never serialises anything: when two threads use a container at once, it
  turns the race into a crash report. Every container gets one shared detector that checks nothing
  ([`NoThreadingDetector`](../src/main/java/io/github/elpideus/diffusicraft/height/NoThreadingDetector.java)), as
  Lithium does by default. This applies to every dimension, tall or not.

What is left of an empty section is the section and its two containers: about 80 bytes. Sections themselves are not
shared, because the game and mods write to the sections of a chunk directly.

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
every section of every loaded chunk, so its memory still grows with the height of the dimension.

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
- **Untested:** Sodium, Iris, Distant Horizons, Voxy, Lithium, FerriteCore, Moonrise and C2ME. Sodium and the LOD
  mods keep their own per-section data and may assume a 4064-block world. Test each of them in a tall dimension
  before relying on it.

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
What the rest of the tall dimension costs did not change:

| Saving and the network | Value |
|---|---|
| Sections in memory after visiting the top, the bottom and the dense column (server and client) | 3.2 million |
| Empty column saved: NBT / compressed on disk | 253 KiB / 7 KiB |
| Densest column saved: NBT / compressed on disk | 4.8 MiB / 40 KiB |
| Encoding one column for saving: empty / densest | 14 ms / 100 ms |
| Densest column's block data in a chunk packet | 5.4 MiB |

- **Memory.** An empty section costs about 80 bytes instead of 450 (see [Empty sections](#empty-sections)), and the
  client keeps render data for the rows near the camera only (see [The client's render grid](#the-clients-render-grid)).
  The server also keeps the proto-chunks of its generation ring, so it holds many more columns than the player sees.
- **Saving.** Every section is written with its palettes, so encoding a column takes time in proportion to its
  height: 14 ms for an empty tall column, under 1 ms for a vanilla one. Leaving the test world took 28 seconds: 20 to
  finish pending chunk work and 8 to save the tall dimension.
- **Light and heightmaps** add little. Light data is stored only near blocks: the empty test column saved 13 sky
  light and 5 block light layers for its 2046 sections.

So a tall dimension still costs save time, and some memory, in proportion to its height, whatever it contains. Make
dimensions only as tall as the terrain needs. Earth 1:1 keeps the underground and the oceans game-sized, because the fun
is on the surface: `min_y` stays −256 and the oceans stay compressed to at most 200 blocks deep. Only the top moves.
Everest's 8,849 m above sea level 63 puts the highest block near y = 8,912, so with headroom the world runs from −256 to
about 9,200: some 9,500 blocks, or 592 sections per column.

## Tests

- [`ExtendedHeightTest`](../src/test/java/io/github/elpideus/diffusicraft/height/ExtendedHeightTest.java) (part of
  `./gradlew build`) round-trips the wire format and checks it against vanilla's packing.
- [`TallDimensionGameTest`](../src/gametest/java/io/github/elpideus/diffusicraft/gametest/TallDimensionGameTest.java)
  runs the real game in a 32,736-block dimension. It checks:
  - blocks at y = ±16,000 and at the top of the world, on the server and the client;
  - changes in both directions over the network: the server places blocks, and the client breaks one;
  - block light, sky light and heightmaps near the top;
  - a column with all 2046 sections full of varied blocks;
  - that empty sections share their data, on the server and the client;
  - that the client's render grid holds only the rows around the camera;
  - saving and reloading the world.

  It saves screenshots of the top, the bottom and the dense column.

## Mixin targets

These break when Mojang changes them. Check them on every Minecraft update:

| Mixin | Target | What it relies on |
|---|---|---|
| `BlockPosMixin` | `BlockPos.<clinit>` | the `30000000` literal |
| `DimensionTypeMixin` | `DimensionType.<clinit>` | the read of `BlockPos.PACKED_Y_LENGTH` |
| `FriendlyByteBufMixin` | `FriendlyByteBuf.writeBlockPos` / `readBlockPos` (static, `ByteBuf`) | the `asLong` and `readLong` calls |
| `SerializableChunkDataMixin` | `SerializableChunkData.parse` / `write` | locals `y` and `sectionTag`; `putByte("Y", …)` |
| `ClientboundLevelChunkPacketDataMixin` | `ClientboundLevelChunkPacketData.<clinit>` | the `2097152` literal |
| `LevelMixin` | `Level.isInWorldBoundsHorizontal`, `Level.getHeight` | the ±`30000000` literals |
| `EntityMixin` | `Entity.absSnapTo(DDD)V` | the ±`3.0E7` literals |
| `ServerGamePacketListenerImplMixin` | `ServerGamePacketListenerImpl.clampHorizontal` | the ±`3.0E7` literals |
| `ForceLoadCommandMixin` | `ForceLoadCommand.changeForceLoad` | the ±`30000000` literals |
| `WorldBorderMixin` | `WorldBorder.<init>(WorldBorder$Settings)`, `setAbsoluteMaxSize` | the `29999984` literal |
| `PalettedContainerMixin` | `PalettedContainer.<init>` (all three), `read`, `createOrReuseData` | `new ThreadingDetector` in the field initialiser; the `data` and `strategy` fields; `createOrReuseData` reusing the old data when the configuration stays the same |
| `StrategyMixin` | `Strategy` | `getConfigurationForBitCount(0)` being the single-value configuration; `entryCount` |
| `SingleValuePaletteAccessor` | `SingleValuePalette` | the `value` field; `idFor` and `read` being the only methods that change it |
| `LevelChunkSectionMixin` | `LevelChunkSection.fillBiomesFromNoise` | the `biomes` field holding the new container at the end |
| `diffusicraft.classtweaker` | `PalettedContainer$Data` | the record and its constructor |
| `RenderGridMixin` (client) | `ViewArea.<init>`, `SectionUpdateTracker.<init>` | `new RotatingSectionStorage(radius, minSectionY, maxSectionY, …)`; the `RotatingSectionStorage` methods that `WindowedSectionStorage` overrides |

Also search the new version for other uses of `30000000`, `3.0E7` and `29999984`, and for code that packs positions
without `BlockPos.asLong`.

## Next steps

- **Uniform sections from world generation.** Give sections that world generation fills with one block shared data
  when their chunk is complete, instead of when they are next loaded.
- **Leaner saves.** Skip sections that are all air with a single biome, and fill them back in on load.
- **A switch.** A config option to keep vanilla's packing for players who need ±30,000,000 worlds.
- **Taller Diffusicraft world types.** Earth 1:1 can then keep real mountains instead of compressing them above
  1.2 km. Only its top moves: from −256 to about 9,200, some 9,500 blocks (see [Costs](#costs)).
- **Compatibility.** Test Sodium, Distant Horizons, Voxy and a dedicated server with a real network connection.
- **Only nearby heights on the wire (not for now).** The server would send each client only the sections near the
  player, as Cubic Chunks does, so a client's memory and bandwidth would follow what it sees instead of the height of
  the dimension. Every mod that expects whole columns on the client would break. If it is ever done it will be opt-in,
  and the player gets a warning about incompatible mods when they choose it.
