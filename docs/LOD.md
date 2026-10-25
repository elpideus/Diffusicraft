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
- **A rough surface first**, as Distant Horizons' generator builds one before it generates any chunk. A tile whose
  terrain the models have not computed yet is built from what they have: without the rivers, or from the coarse map
  alone. The models compute what is missing later, one tile at a time and only while chunk generation leaves them
  idle, and the tile is then built again in full. The far terrain fills the view in seconds and never makes a chunk
  wait.

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

Every grid the far terrain asks for goes through `LodGrid.sample`: our renderer and Voxy in singleplayer, and the
feed to multiplayer clients where the server shares what players built. When the grid's step is a level's and its
corner is a multiple of that step, the stored columns replace the sampled ones. A grid whose columns are all stored
never runs the terrain at all. `LodGrid.generate` samples the terrain alone, as it was generated.

A grid that reads the full-resolution terrain needs its own tiles and, for its rivers, those two rings around it.
`LodGrid.Mode` says whether the grid may wait for the models to compute them:

- **`FULL`** runs the models over every tile the grid needs, as chunk generation does. Voxy asks for its grids so.
- **`ROUGH`** never runs them. It samples the grid as finely as the terrain computed so far allows
  (`LodSampler.available`): in full once every tile it needs is in memory, without the rivers once its own tiles
  are, and from the coarse map before then. The grid is marked rough unless it came out in full.
- **`REFINE`** runs the models over one missing tile at most, and only while they are idle
  (`TerrainField.prefetchWhenIdle`): no tile is being computed, none was for the last half second, and no other
  refinement is computing one. It returns the grid in full once nothing is missing, and no grid until then.

Chunk generation asks for its next tile as soon as one is done, so a refinement only ever slips in between, and it
holds chunk generation up by one tile at most. A grid reads the full-resolution terrain only while it spans four
tiles or fewer, so with its rings it needs 81 at most, within the 160 the terrain keeps in memory.

### On a server: `LodFeed`, `RemoteLods` and `SeenChunks`

A server with Diffusicraft sends the far terrain of its Diffusicraft worlds to the clients with Diffusicraft that ask
for it, for our renderer or for Voxy (see [COMPATIBILITY.md](COMPATIBILITY.md#voxy)). As a player joins, `LodFeed`
tells the client how far it sends, which levels it serves and whether it shares what players built. The client
(`RemoteLods`) then asks for grids, at most 32 at once, nearest first. Our renderer asks rough first and then
refines, as it does in singleplayer, and the server keeps the last 128 grids it sent but samples a rough one again
when it is asked for again, since the terrain may be ready by then. What the grids show depends on the server's
[`shareBuilds`](CONFIG.md#sharebuilds):

- **Off, the default: the terrain as generated.** The server sends `LodGrid.generate`, so a player cannot find from
  afar what others built, such as a base. Each client keeps what it saw itself instead. As it lets go of a chunk, or
  of a whole level as it leaves, `SeenChunks` hands the chunk's sections to a store of its own (`LodStore` again),
  from the highest down to two under the chunk's lowest ground, or on down to the floor of a sea. A chunk whose
  ground falls further than 64 sections is left out. Nothing changes the sections of a chunk the client has let go
  of, so they are read as they are, and only their light is copied. The stores live under
  `diffusicraft/seen/<server address>/<world>/<namespace>/<path>` in the game's folder, where `<world>` is the
  one-way hash of the world's seed that vanilla already sends every client. The far terrain lays the stored columns
  over the grids the server sent, and keeps the last 16 grids, so that a tile a seen chunk changes is built again
  without asking the server.
- **On: what players built.** The server sends `LodGrid.sample`, with the chunks as they were last saved. When a
  page of its store changes, it drops the grids it cached under it and tells the players within reach
  (`LodPayloads.Changed`), whose far terrain builds the tiles over it again. The client keeps nothing of its own.

### Drawing them: tiles, meshes and colours

These parts are unit-tested and feed the renderer below.

- **Tiles (`LodTiles`).** The far view is cut into tiles, and each tile is one page of the store: 64 × 64 columns of
  one level, `64 · 2ᴸ` blocks wide. The tiles form a quadtree, as Distant Horizons' do. A tile splits into the four
  of the level below while the viewer is closer to it than `detail` times its step, so columns lie about
  `distance / detail` blocks apart. Tiles that lie wholly within the chunks the game draws are left out. Out to
  16,384 blocks, a detail of 64 takes about 170 tiles, and a detail of 128 about 420. As the viewer moves, a tile
  that split stays split until the viewer is 1.25 times as far as it would split at, and one that did not must come
  as much closer to split (`LodTiles.Chooser`), as Distant Horizons' quadtree keeps its own. Otherwise a player
  going to and fro across the line where a tile splits would have the four finer tiles and the coarse one built
  again and again.
- **Meshes (`LodMesher`).** A tile becomes faces, solid and water apart:
  - **Tops.** The tops of the highest solid run and the highest water in each column merge into rectangles of one
    block, biome, height and light, as Voxy and Distant Horizons merge theirs. A plain or a sea then takes a few
    faces. The tops under a bridge or an overhang stay one per column.
  - **Sides.** A side shows only the part of a run that the neighbouring column leaves open.
  - **Skirts.** Along a tile's edges, the neighbour may be a tile of another level, so the solid runs show their
    sides down to two steps under the ground of the column and of its neighbours along the edge. Where the ground
    rises away from the edge, the skirt also reaches as far down as the slope goes three steps on past it, where the
    column of a tile two levels coarser may meet the edge. This skirt closes the cracks between tiles, even on steep
    mountainsides. Water has no skirt, which would show as walls under the sea.
  - **Bottoms.** The underside of a bridge or overhang shows.
  - **Light.** The ground under something takes a little less sky light. Faces under water darken with depth, as
    the world's do.
- **Colours (`LodColors`, client).** Each block has a colour for its top, bottom and sides: the average of the
  textures its model shows there, weighted by the area each covers, leaving out see-through pixels. The parts the
  world tints, such as grass, leaves and water, take the same tints for the column's biome, through the game's own
  tint sources. Textures are read from the resource packs the first time a block is asked for, off the render
  thread. When the resource packs change, the renderer starts over with the new colours.

### The renderer: `FarTerrain` (client)

`FarTerrain` draws the tiles in Diffusicraft worlds when neither Distant Horizons nor Voxy is installed and
`farTerrain` is on (see [CONFIG.md](CONFIG.md#farterrain)).

- **Where the grids come from.** In singleplayer, from the integrated server's own level through `LodGrid.sample`,
  so what players built shows. Its store tells the renderer which pages changed, and those tiles are built again.
  On a server, from the far terrain the server sends (`RemoteLods`), with the chunks this client saw laid over it
  where the server does not share what players built (see [above](#on-a-server-lodfeed-remotelods-and-seenchunks)).
- **Building.** Two low-priority worker threads sample a tile's grid, mesh it and write its vertices off the heap.
  At most six tiles are built at once, nearest first, and tiles whose terrain changed go before new ones. Each tile
  is built rough first (see [above](#using-them-lodgridsample)). Once every tile wanted is built, the nearest rough
  one is refined, one at a time; a tile whose terrain is not ready is asked for again a second later. The render
  thread only uploads finished meshes, at most 4 MB a frame, so a burst of tiles does not stall it. `LodView` keeps
  a coarse tile drawn until every finer tile wanted within it is built, and finer tiles until the coarse tile that
  replaces them is, so there are no holes while the player moves.
- **Vertices.** 16 bytes each: the position from the tile's corner as unsigned shorts, the sky and block light, the
  colour, the material and the side the face looks to. A tile's solid quads and water quads share one buffer, and
  its corner goes to the shader in a uniform of its own, so the far terrain is exact at ±8,000,000 blocks.
- **Drawing.** At the end of the world's solid pass, after the chunks, with one draw call per tile and per layer.
  The far terrain shares the chunks' depth buffer, fog, lightmap and face shading. Tiles outside the view are
  skipped. The land is drawn nearest first, as Distant Horizons draws its own, so that a near mountain hides the
  land behind it before the land is shaded; the water is drawn furthest first, so that it blends over what lies
  behind it.
- **Where the chunks take over.** The tiles reach all the way in to the camera, and `ChunkMask` keeps a texture of
  one texel per section around it, set where the world draws the section: its chunk is loaded and lit with the eight
  around it, it lies within the render distance and the rows of sections the world draws, and it is air or has its
  mesh, built by Sodium or else by vanilla, whose graph of the sections the camera can see must also have reached
  it. The fragment shader drops far terrain over those sections, a quarter of a block inside the face it draws so
  that the edges match. Everywhere else the far terrain shows: while a chunk loads, while a section waits for its
  mesh as the player flies, and above and below the rows the world draws. Tiles that began near the edge of the
  render distance, and a mask of one texel per chunk set from its surface section alone, left holes where the world
  had not built a section yet, most of all on steep slopes and while flying. The texture's slots rotate as the
  camera moves, as the world's own grid of sections does, and a few lines of chunks are looked up each frame, so a
  large render distance spreads the work. A tile that lies wholly within sections the world draws is not drawn at
  all, since the shader would drop every fragment of it: the mask's bytes are checked on the CPU as well.
- **Debug screen.** The `diffusicraft:far_terrain` entry, turned on among the others with F3 + F6, shows the tiles
  with a mesh and those drawn in the last frame, those left rough and those still to build, and the memory their
  meshes take on the GPU.
- **Reach.** The camera's far plane moves out to twice the far terrain's distance, and the fog closes in at its
  end instead of at the end of the chunks. The air's haze, which vanilla ends at 1024 blocks, stretches as far.
  Fog under water, in lava and from blindness or darkness stays as it is.

### Under a shader pack: `IrisFarTerrain` (client, with Iris)

Packs that support Distant Horizons, such as Bliss and Complementary Unbound, shade its terrain with programs of
their own, `dh_terrain` and `dh_water`, which Iris builds and feeds while Distant Horizons is installed. The far
terrain's vertices already follow Distant Horizons' layout, so where Distant Horizons is not installed, Diffusicraft
plays its part for Iris:

- **The pack's programs.** `DISTANT_HORIZONS` is defined for the pack while `farTerrain` is on, as Iris defines it
  for Distant Horizons, so the pack builds its programs for it and merges its depth in its later passes. Turning
  `farTerrain` on or off loads the pack again. `FarProgram` builds `dh_terrain` and `dh_water` as Iris patches them
  for Distant Horizons, with the pack's uniforms, samplers and blending.
- **What Iris asks.** Iris asks Distant Horizons for its near and far planes, its projection, how far it reaches and
  its depth textures. The far terrain answers in its place.
- **Drawing.** The land is drawn as the world's solid pass begins, before the chunks, into the pack's buffers and a
  depth buffer of its own, which is then copied to the two textures the pack reads. The water is drawn after the
  pack's deferred passes, as the translucent pass begins, as Distant Horizons draws it. The pack itself leaves out
  the far terrain within the chunks' reach, so the tiles start at its edge, `ChunkMask` is not used, and the pack's
  own fog applies.
- **Not yet.** The far terrain casts no shadows.

## Next

1. **Shadows.** Under a shader pack, the far terrain should cast shadows, as Distant Horizons' does, through the
   pack's `dh_shadow` program.
2. **Coarser levels from finer ones.** Far levels now keep one column out of each square of 2ᴸ × 2ᴸ, as the sampler
   does. Building them from the level below, as Voxy builds its coarser sections, would keep thin builds and roads
   visible from further away.
3. **Workers that yield.** The far terrain's two workers run at the lowest thread priority, which Linux ignores. A
   duty cycle, as Distant Horizons gives its own threads, sleeping after each tile about as long as it took, would
   leave the chunks and the game more of the CPU while tiles build. Pausing new builds while the player flies fast,
   when the tiles chosen are stale before they are done, would help as well.
