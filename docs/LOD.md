# Far terrain

Diffusicraft is getting a far terrain of its own, so that it no longer needs Distant Horizons or Voxy. It is meant
to show what players built, to work with shader packs, and to cost as little as possible on top of 1:1 world
generation, which already keeps a computer busy. This page describes the design and how far along it is. The parts
used with Distant Horizons and Voxy today are described in [COMPATIBILITY.md](COMPATIBILITY.md#voxy).

## What it borrows

The ideas below come from how Distant Horizons, its SeedGen add-on and Voxy work. They are written anew: no code is
taken from them. Voxy is All Rights Reserved and Distant Horizons is LGPL.

- **Terrain without chunks**, as SeedGen does. `LodSampler` takes the finished surface of a column straight from the
  terrain field, the climate and the material rules. Far terrain then costs one column per point, not a generated
  chunk, and costs nothing for land that nobody has visited.
- **Columns of runs**, as Distant Horizons stores them. Each column is a list of runs, each one block state from one
  Y to another, plus the column's biome. A column of stone, dirt and grass is three runs whatever its height, which
  matters in 32,736-block worlds.
- **Halving levels of detail**, as both mods use. A level's columns are twice as far apart as the level below's:
  level 0 has a column every block, level 9 one every 512 blocks.
- **Chunks taken as they are saved**, as both mods take what is loaded. Places players have been show as they are,
  not as they were generated.
- **Caves filled in**, as Distant Horizons fills them. A far view never sees inside a closed space, so a column
  keeps only what the sky can reach.

## Built so far

### Reading saved chunks: `ChunkColumns`

`ChunkMap.save` copies a chunk's sections and light before writing them to disk on a background thread. A mixin
(`ChunkMapMixin`) hands that same copy to the level's store, so the server thread does nothing more than queue it.
On the store's thread, `ChunkColumns` reads each column of the copy:

- **What counts.** A column keeps the blocks that stop a player, plus water, lava and snow. Plants, torches, rails
  and other blocks one walks through count as air, as they do from afar.
- **The ground.** The ground is the highest block that is neither leaves nor ice: a roof, or the sea floor under a
  frozen sea, as `LodSampler` has it.
- **How deep it keeps blocks.** A column keeps its blocks one by one down to four blocks under the lowest ground
  around it, so that a wall or a cliff shows its face. Below that, one run of the rock there reaches the bottom of
  the world.
- **Open space under things.** Where the sky lights the space under the ground, as under a bridge, a pier, an
  overhang or a floating build, the column keeps that space and the water in it, down to the next ground. Closed
  rooms and caves fill in.

### Keeping them: `LodStore`

Each Diffusicraft level has one store, opened on its first use and closed once the server has saved its chunks for
good (`SERVER_STOPPED`).

- **Columns at the sampler's points.** A level's columns are `step` blocks apart, at `step / 2` past a multiple of
  the step, which are the points `LodSampler` takes for a grid of that step. A saved chunk adds its 256 columns to
  level 0, 64 to level 1 and so on, down to one at level 4. Beyond that, one chunk in every 2 × 2 adds a column to
  level 5, and one in every 32 × 32 adds one to level 9.
- **Pages.** A level's columns come in pages of 64 × 64 columns. Each page is a `LodGrid` plus a bitset of which of
  its columns are stored. A chunk always lies within one page of each level.
- **On disk.** Pages live in region files under `<world>/diffusicraft/lod/<namespace>/<path>/L<level>`, written
  through Minecraft's own `IOWorker`, so there are no new dependencies. Each page has its own list of the names of
  its block states and biomes, so it reads the same after mods change their numbering. A block or biome that is gone
  reads as air, or as plains.
- **Threads.** Everything runs on one low-priority thread per store. Saved chunks are read in batches and each page
  they touch is rewritten once. The last 192 pages stay in memory.

### Using them: `LodGrid.sample`

Every grid the far terrain asks for goes through `LodGrid.sample`: Voxy's in singleplayer, and the feed to
multiplayer clients. When the grid's step is a level's and its corner is a multiple of that step, the stored columns
replace the sampled ones. A grid whose columns are all stored never runs the terrain at all. `LodFeed` drops the
grids it cached under a page that changes.

## Next

1. **Drawing it.** Tiles of these columns, coarser the further they lie, meshed on worker threads and drawn past the
   chunks by Diffusicraft itself.
2. **Shader packs.** Bliss and Complementary Unbound shade Distant Horizons' terrain through Iris's support for it.
   Our renderer has to be shaded the same way.
3. **Coarser levels from finer ones.** Far levels now keep one column out of each square of 2ᴸ × 2ᴸ, as the sampler
   does. Building them from the level below, as Voxy builds its coarser sections, would keep thin builds and roads
   visible from further away.
4. **Telling clients.** A page that changes should reach the clients that hold it. For now they keep their copy
   until they ask again.
