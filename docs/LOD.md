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
- **Caves filled in further out**, as Distant Horizons fills them. A view from a long way off never sees inside a
  closed space, so a column whose level has columns more than 8 blocks apart keeps only what the sky can reach.
  The finer levels keep the caves, which the sky does not light (see [below](#caves-farhollows)).
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
  around it, so that a wall or a cliff shows its face. Below that, the rock there reaches the bottom of the world,
  around the caves.
- **Open space under things.** Where the sky lights the space under the ground, as under a bridge, a pier, an
  overhang or a floating build, the column keeps that space and the water in it, down to the next ground.
- **Caves and closed rooms.** The air the sky leaves dark, in a cave or a room under its roof, is kept as cave air,
  with the water and lava in it and the blocks of its floor and ceiling. Around the heights the chunk's caves open
  at, the rock comes in bands of 16 blocks, each the block in its middle, so that their walls show the strata; below
  and above them it is one run.
- **Sections without light.** Vanilla keeps no sky light for a section that the sky reaches nowhere. Nor does it
  keep any for one that only repeats the bottom of the section above, and above every section it keeps, it reads
  full light. So a section without light is dark under a dark block, such as a tunnel under the floor of a deep sea,
  and in the open above them all. Under a lit block it may be either, and the column does not count it as a cave;
  nor does it anywhere in a chunk with no light kept at all.

### Keeping them: `LodStore`

Each Diffusicraft level has one store, opened on its first use and closed once the server has saved its chunks for
good (`SERVER_STOPPED`).

- **Columns at the sampler's points.** A level's columns are `step` blocks apart, at `step / 2` past a multiple of
  the step, which are the points `LodSampler` takes for a grid of that step. A saved chunk adds its 256 columns to
  level 0, 64 to level 1 and so on, down to one at level 4. Beyond that, one chunk in every 2 × 2 adds a column to
  level 5, and one in every 32 × 32 adds one to level 9.
- **Caves by level.** A level keeps a chunk's caves as high as its step at least, and only up to level 3, whose
  columns are 8 blocks apart, as the far terrain draws caves no further out. A cave too low for a level is filled with
  the block under it.
- **Pages.** A level's columns come in pages of 64 × 64 columns. Each page is a `LodGrid` plus a bitset of which of
  its columns are stored. A chunk always lies within one page of each level.
- **On disk.** Pages live in region files under `<world>/diffusicraft/lod/<namespace>/<path>/L<level>`, written
  through Minecraft's own `IOWorker`, so there are no new dependencies. Each page has its own list of the names of
  its block states and biomes, so it reads the same after mods change their numbering. A block or biome that is gone
  reads as air, or as plains. Each page also notes the version of what its columns hold, and a page of another
  version reads as missing: version 2 keeps the caves.
- **Reading the world again.** A server's store whose folder does not note the version in a `version` file, as when
  an older build of the mod or none at all played the world, reads every chunk saved in the level again as it opens,
  on its own thread, a slice at a time between the pages the far terrain asks for, and writes the file once it is
  done. A chunk that an older game saved is read once the server has brought it up to date and saved it again. A
  server stopped before the store is done starts over the next time.
- **The terrain as generated.** The store also keeps each page of the terrain as generated that the far terrain
  sampled in full, under `G<level>` beside `L<level>`, whatever players did to it since. A world closed and opened
  again then shows its far terrain at once, where the models would otherwise compute it all over again, rough first.
  Each page notes the build that generated it: `generation` in `diffusicraft-build.properties`, a digest the build
  works out of everything under `src/main`, the models' manifest among it. A page of another build is generated
  again, since other code or data may shape other terrain, and a build without the digest keeps none.
- **Threads.** Everything runs on one low-priority thread per store. Saved chunks are read 32 at a time, and each
  page they touch is rewritten once; between batches the thread serves the pages the far terrain asked for
  meanwhile, so a flight that saves a hundred chunks a second does not hold up its reads. A chunk saved again before
  it was read is read once, as it is now. Vanilla saves a chunk again whenever anything in it changed, as grass
  spreads or a crop grows, so a page whose columns come out as they were is neither written nor rebuilt. The last
  192 pages stay in memory, and the last 4096 found missing, so that asking for them again reads nothing.

### Using them: `LodGrid.sample`

Every grid the far terrain asks for goes through `LodGrid.sample`: our renderer and Voxy in singleplayer, and the
feed to multiplayer clients where the server shares what players built. When the grid's step is a level's and its
corner is a multiple of that step, the stored columns replace the sampled ones. A grid whose columns are all stored
never runs the terrain at all. `LodGrid.generate` samples the terrain alone, as it was generated, and reads a page
the store kept of it instead, in full whatever the mode.

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

### Structures: `FarStructures` and `FarSketch`

The villages, temples, outposts, shipwrecks and other structures on the surface show in the grids whose columns are
at most 8 blocks apart, so they no longer pop up out of nowhere as one flies towards them. `FarStructures` picks their
starts as the chunk generator does, from the same seeds and the same structure sets, and keeps the last 4,096 it made
in memory. Only the sets spread at random are read: strongholds and whatever is underground stay out. It places their
pieces chunk by chunk, with the chunk generator's own decoration seeds, into a `FarSketch`: a `WorldGenLevel`
without chunks, solid up to the far terrain's ground and water over it where the sea or a river covers it. The pieces
read it as they read a chunk being generated, so foundations reach down to that ground, paths follow it and turn into
bridges over water. The sketch keeps only the blocks; entities, block entities and ticks are dropped.

Each cell then sums up the blocks in its columns. It keeps a block at each height where the structures put one above
the ground in a quarter of its columns at least, the block they put there most, with air between them, or water under
the water's surface. So where a cell is a column, near the chunks, a house keeps its walls, windows, roof and eaves
block by block, and further out its outline, never a solid box. A cell is paved where the structures lie on the
ground, as a village's paths do. No tree grows on such a cell. A structure may sit a block or two off where it
generates: it stands on the far ground, which lacks the ledges and gullies, and the earth vanilla heaps up and cuts
away round some structures. Grids sampled without the rivers or from the coarse map show no structures, so the
starts kept are all made on the finished terrain. A structure that fails to place in a sketch is left out with one
warning in the log.
Vanilla caches a template's blocks by kind in a map that only one thread was meant to fill at a time. The far
terrain's workers place pieces beside the chunks' own, so `StructureTemplatePaletteMixin` makes that map a concurrent
one: before, two threads filling it at once threw, and could have thrown in the chunks' generation.

`FarTerrainStructuresGameTest` views the village nearest the spawn from beyond the chunks drawn, before its chunks
exist, then generates them and checks that the far columns over most of its buildings rise to within 3 blocks of
their roofs. On the test's seed, 870 of the 1,034 columns over a taiga village's buildings do, and the columns over
the whole village lie 0.8 blocks off on average.

### Trees: `FarTrees`

The grids whose columns are at most 8 blocks apart grow their trees where the world grows them, so that a tree seen
from afar is still there when the player comes close. Further out, `TreeCover` scatters each biome's trees at random,
as many as the world grows and of the same shapes ([COMPATIBILITY.md](COMPATIBILITY.md#distant-horizons)), and the
giants stand where the world grows them at every distance (`GreatTrees`).

`FarTrees` places each chunk's vegetation features that grow trees again, as the chunk generator decorates the chunk:
in the same order, from the same decoration and feature seeds, those of the biomes of the chunk and of the chunks
around it, each only where its own biome is. They grow in a `FarSketch` of the far terrain's ground, topped with the
block its surface rules put there, so they take root where a sapling would, keep out of the water and clear of each
other's crowns in their chunk, as in the world. Each column of the grid keeps the lowest and highest leaf over it,
and the lowest and highest log, and is drawn under that crown. A trunk shows where the columns are no further apart
than it is wide.

A few things differ from the world:

- **Each chunk grows on the bare ground**, as if it were decorated first. Where the crown of a tree in a chunk
  decorated before would keep a tree from growing, the far terrain still shows that tree, and the world's trees after
  it in its chunk may then stand elsewhere. Which of the two the world grows depends on the order its chunks generate
  in. In `FarTreesTest`'s oak woods, three trees to a chunk, 9 of 102 trees show that the world does not grow, and 3
  that it grows are missing.
- **Only trees.** The other vegetation, the features of the other steps and the structures are not placed, and no
  tree grows on a structure's cell.
- **The surface's biomes.** A chunk grows the features of the biomes at the surface of its columns, so the azaleas
  over lush caves, whose biome lies under the ground, are left out.
- **A point a column.** A column 8 blocks wide shows a crown when the block at its point is under one.

A grid grows every tree in the chunks it covers and in the ring around them: some 1,150 chunks for 64 × 64 columns
8 blocks apart, which takes 0.2 seconds for the oak woods of the test. A tree feature that throws is left out with one
warning in the log.

### Caves: `FarHollows`

In the grids whose columns are at most 8 blocks apart, the caverns of the Hollows open under the ground where the
chunks open them: the Hollows, the Deep Hollows, the Grand Hollows of every kind and the deep noodle caverns. A view
across a Grand Hollow then shows its far walls, its floor and its ceiling instead of the void, and a cave that runs
out of the chunks drawn goes on in the far terrain.

- **Where they open.** The chunks take the density of the Hollows at the corners of cells 4 blocks wide and 8 high,
  fade it to solid rock over the bottom 24 blocks of the world, and run straight between the corners. `FarHollows`
  does the same, working out each corner once for the whole grid, so a cavern's floor, walls and ceiling stand where
  the chunks put them. A cavern lower than the grid's step is left out, as the columns could not show it.
- **What fills them.** Cave air, which the far terrain keeps dark; each cavern's lake, water or ice or none, as the
  chunks fill it; and lava at the bottom of the world, below the chunks' lava level.
- **Their rock.** Each floor and ceiling takes the block the material rules lay there, in the biome of its cavern,
  the glowing ceilings among them. An ore there would stand for a whole floor, so the rock around it does instead.
  Around the heights the caverns open at, the rock comes in bands of 16 blocks, each the block the rules put in its
  middle, so that the walls show the strata.
- **What is left out.** Vanilla's own caves and the pools its aquifers leave. These show only where the chunks were
  saved, which the store keeps as they are (see [above](#reading-saved-chunks-chunkcolumns)).

`FarTerrainCavesGameTest` digs a sealed tunnel 40 blocks under the spawn and 120 blocks south, past the chunks drawn,
and checks that the far terrain keeps its far end open, closed and dark. On the test's seed the tunnel runs under the
floor of the sea, whose sections vanilla saves without light. The test then views the Ancient Grand Hollow nearest the
spawn, 445 by 385 blocks across, from its floor: 629 of the 646 far columns 8 blocks apart around its middle open into
it, none of them lit by the sky. A tile 512 blocks wide over it, at that step, takes 141 ms to sample once the models
have computed the terrain under it, and has 21,372 faces, 15,585 of them in the dark.

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
  without asking the server. The client that runs a singleplayer or LAN world keeps nothing, since its far terrain
  reads the world itself. A store still writing as the game quits is waited for, before the game stops the threads
  it writes through.
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
    faces. So do the highest floor of a cave in each column, the highest lake in one and the highest ceiling, so
    that the floor of a Grand Hollow takes few faces too. The other tops, under a bridge or an overhang, stay one per
    column.
  - **Sides.** A side shows only the part of a run that the neighbouring column leaves open.
  - **Skirts.** Along a tile's edges, the neighbour may be a tile of another level, so the solid runs show their
    sides down to two steps under the ground of the column and of its neighbours along the edge. Where the ground
    rises away from the edge, the skirt also reaches as far down as the slope goes three steps on past it, where the
    column of a tile two levels coarser may meet the edge. This skirt closes the cracks between tiles, even on steep
    mountainsides. It also runs through the depths where the tile has caves, so that a cave that runs a little
    differently in the tile beside it, sampled more or less finely, meets rock there and not a hole. Water has no
    skirt, which would show as walls under the sea.
  - **Bottoms.** The underside of a bridge, an overhang or the ceiling of a cave shows.
  - **Light.** The ground under something takes a little less sky light. Faces under water darken with depth, as
    the world's do. The faces in a cave, and those in or under the water of a lake in one, take no sky light at all,
    so that the caves stay as dark as the world's own and a shader pack does not light them as if the sun reached
    them. Water without sky light is drawn opaque, with the land (`FarMeshWriter`): Bliss's `dh_water` lights all
    water as open to the sky, reflecting it, whatever light it has, so a far cave lake glowed under it.
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
  it. Sodium must also hold the section in the tree of those it draws, which takes in a section it has just built
  only when it culls again, a few frames later. Sodium measures the render distance from the camera itself, not
  from its chunk, and stops nearer where the fog ends sooner, as in rain, so the fragment shader tests each
  section's distance from the camera the way Sodium does, with a sixteenth of a block to spare. The fragment shader
  drops far terrain over those sections, a quarter of a block inside the face it draws so that the edges match.
  Everywhere else the far terrain shows: while a chunk loads, while a section waits for its mesh as the player
  flies, and above and below the rows the world draws. Tiles that began near the edge of the render distance, and a
  mask of one texel per chunk set from its surface section alone, left holes where the world had not built a section
  yet, most of all on steep slopes and while flying. Under Sodium, squares of sky flickered at the edge of the
  chunks as the camera moved within its chunk, and in front of it as it flew. The texture's slots rotate as the
  camera moves, as the world's own grid of sections does, and a few lines of chunks are looked up each frame, so a
  large render distance spreads the work. A tile that lies wholly within sections the world draws is not drawn at
  all, since the shader would drop every fragment of it: the mask's bytes are checked on the CPU as well.
- **From a cave.** A cave that leads out of the chunks drawn goes on in the far terrain, which keeps the caves of
  the chunks saved and opens those of the Hollows (see [above](#caves-farhollows)). A chunk changed is saved, and so
  shows, within about ten seconds; one that only loaded waits for the autosave. When the far terrain was solid under
  the ground, the eye went on through it from a cave to the sky; flat walls of rock along the edge of the chunks
  then closed the view instead, which hid the far side of a Grand Hollow.
- **Debug screen.** The `diffusicraft:far_terrain` entry, turned on among the others with F3 + F6, shows the tiles
  with a mesh and those drawn in the last frame, those left rough and those still to build, the memory their meshes
  take on the GPU and the chunks saved that the store has still to read, which pile up if it falls behind. Under a
  shader pack, a second line counts the tiles drawn into its shadow map.
- **Reach.** The camera's far plane moves out to twice the far terrain's distance, and the fog closes in at its
  end instead of at the end of the chunks. The air's haze, which vanilla ends at 1024 blocks, stretches as far.
  Fog under water, in lava and from blindness or darkness stays as it is. Sodium reads the fog at the same point,
  for its chunks' own fog and for how far it draws them, and the far terrain stretches it first, so that Sodium's
  chunks fade as vanilla's do.

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
  the far terrain within the chunks' reach, and its `dh_water` the water wherever the chunks have drawn, which it
  would cover from a cave, as it is drawn after them; so the tiles start at its edge, `ChunkMask` is not used, and the
  pack's own fog applies. A pack without `dh_water` has its water drawn with `dh_terrain`, as Iris does.
- **Shadows.** Where the pack has a `dh_shadow` program, the land of the far terrain is drawn into the pack's shadow
  map, through Iris's public callback for the shadow pass, which runs after the chunks' solid terrain. Every tile the
  shadow's box sees casts, those out of view as well, since the sun throws shadows into view from behind the camera.
  The water does not: what is drawn there also lands in `shadowtex1`, the depth without the translucent blocks, which
  Iris copies after the callbacks. How far the shadows reach is the pack's `shadowDistance`. It casts even where the
  pack turns Distant Horizons' shadows off (`dhShadow.enabled`, which Bliss ties to its `DISTANT_HORIZONS_SHADOWMAP`
  option and leaves off). Iris then keeps the shadow's box to the pack's own reach, 128 blocks in Bliss, and with a
  render distance shorter than that the far terrain begins inside it. Bliss lights everything there from the shadow
  map and its sky light only beyond it, so a far cave with nothing drawn over it in the shadow map would be lit as if
  in the open. Complementary has no `dh_shadow` and lights Distant Horizons' terrain from its sky light alone, so its
  far caves stay dark without it.

## Next

1. **Coarser levels from finer ones.** Far levels now keep one column out of each square of 2ᴸ × 2ᴸ, as the sampler
   does. Building them from the level below, as Voxy builds its coarser sections, would keep thin builds and roads
   visible from further away.
2. **Workers that yield.** The far terrain's two workers run at the lowest thread priority, which Linux ignores. A
   duty cycle, as Distant Horizons gives its own threads, sleeping after each tile about as long as it took, would
   leave the chunks and the game more of the CPU while tiles build. Pausing new builds while the player flies fast,
   when the tiles chosen are stale before they are done, would help as well.
3. **Fewer, smaller quads.** The debug screen shows some 300 MiB of meshes for about 400 tiles at a render distance
   of 6: 64 bytes a quad, and about 12,000 quads a tile. Merging side faces along a row, as Distant Horizons does,
   and tops by their final colour rather than their biome would leave a third to a half fewer quads. Quads of 8 bytes
   read from a buffer, as Voxy draws its own, would take an eighth of the memory, outside shader packs.
4. **Vanilla's caves.** The far terrain as generated opens only the Hollows, so a vanilla cave that leads out of the
   chunks drawn into land nobody has saved yet ends in rock. Opening them too would mean working out the noise
   router's caves at the corners of each cell, as `FarHollows` does the Hollows'.
