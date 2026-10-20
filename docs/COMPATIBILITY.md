# Compatibility

Diffusicraft generates the overworld with Minecraft's own `NoiseBasedChunkGenerator` and multi-noise biome source,
fed by Terrain Diffusion (see [WORLDGEN.md](WORLDGEN.md)). Anything that works with those works with Diffusicraft
unless it replaces the overworld's generator itself. This page lists what has been checked, what Diffusicraft does
for each mod, and what is still open.

| Mod | Status | What Diffusicraft does |
|---|---|---|
| [Distant Horizons](#distant-horizons) 3.3.4 | Integrated | Draws the far terrain straight from the terrain, without chunks |
| [Voxy](#voxy) 0.2.20-beta | Works, checked in game | Keeps it within the heights it can hold; draws its far terrain straight from the terrain in singleplayer (not yet checked in game) |
| Distant Horizons SeedGen, Voxy SeedGen | Superseded in Diffusicraft worlds | Both mods' far terrain comes from Diffusicraft's terrain instead, Voxy's in singleplayer |
| [Biomes O' Plenty](#biome-mods) 26.3.0.0.15, TerraBlender 26.3.0.0.9 | Works, checked in game | Their biomes get the same climate parameters as vanilla's; their trees show in the LODs |
| Oh The Biomes We've Gone (BYG) | Not checked: no release for 26.3 yet | Should work as Biomes O' Plenty does, through TerraBlender |
| C2ME 0.4.2-alpha.0.106+26.3 | Works, checked in game | Nothing: the terrain pipeline is thread-safe. The client game test passes with it (`-PdevMods=c2me-fabric`), LODs, rivers and lakes included, in well under half the time |
| [Tech Reborn](#tech-mods) 6.2.0 | Works, checked in game | The geology's ore bodies hold its tin, lead, silver, uranium, rubies, sapphires and the rest in their host rocks, and its bauxite lies in tropical laterite |
| Create, Mekanism, other tech mods | Should work: not checked | The same for any ore under the common tags `c:ores/<metal>` |
| [Sodium](#sodium) 0.9.3-beta.1 | Works, checked in game | Lets it build sections at every height of a tall dimension; inference leaves it GPU memory |
| Iris, shader packs | Works | Inference leaves GPU memory for them; see [CONFIG.md](CONFIG.md#gpumemorylimitmb) |
| Structure mods | Works | Structures are placed by the vanilla generator |

## Distant Horizons

Distant Horizons draws terrain far beyond the render distance from levels of detail (LODs). By default it builds them
from generated chunks, which in a Diffusicraft world means running the whole chunk pipeline for every chunk in view.
With Diffusicraft installed it instead gets each LOD straight from the terrain: `compat.dh.DiffusicraftLodGenerator`
is registered for every Diffusicraft level when the level loads. Other levels, and Diffusicraft levels with
[`distantHorizonsLods`](CONFIG.md#distanthorizonslods) off, keep Distant Horizons' own generator or another mod's.
Its priority (20) is above the default, so it wins over SeedGen in Diffusicraft levels.

**What an LOD holds.** `worldgen.lod.LodSampler` fills each column of an LOD the way the chunk generator would:

- the terrain's height, cut by the rivers, with water in their channels and lakes, and the sea up to its level; the
  nearest LODs, whose columns are at most 4 blocks apart, carry the ledges too, and the gullies at Earth 1:1, smoothed
  over the 4-block cells the chunks interpolate over, as the chunks smooth them; all but the farthest, which read the
  coarse map, carry the sea cliffs and stacks and the shape of the sea floor;
- the blocks at the top from the world's own material rules (soils, sand, gravel, snow, rock strata in cliffs), run on
  each column with the slope taken from its neighbours;
- one rock sample for everything below, which shows in cliff faces;
- the trees, as crowns of leaves over the soils where the biome grows them, with snow on them where it snows:
  as many, as tall and as wide as the biome's own trees (see below);
- ice on cold water and a snow layer where the biome snows, as the chunk's features would put them;
- the biome, which Distant Horizons uses to tint grass, leaves and water.

Caves, plants, fallen trees and structures are left out. Lighting follows Distant Horizons' own conventions: full sky
light above the ground, the sky dimmed by the water over it, and none below the surface.

**Trees.** `worldgen.lod.TreeCover` works out each biome's trees once from its vegetation features, without placing
any in the world:

- **How many.** The expected number per chunk comes from their placement (counts, rarity and chance), split between
  the branches of the selectors by their odds.
- **What shape.** Each tree's own trunk and foliage placers grow it 64 times on an empty stand-in for the world, which
  records where the leaves and logs go. The crowns keep the highest and lowest leaf of each column, so dark oaks,
  mega spruces and fancy oaks are as wide as in the world. Placers that need more of the world than the stand-in gives
  are drawn as discs of their foliage radius instead.
- **Where.** The trunks are scattered at random, as in the world: a grid of cells, each holding a Poisson-distributed
  number of trees around one. Like the world, a tree does not grow where an earlier tree's crown already shades its
  spot. Without that, or with trees spread evenly, the crowns would overlap less than the world's and the LODs would
  show up to a third more canopy. A column is under the highest crown that reaches it, with the trunk drawn only in
  the nearest LODs. The wider columns of farther LODs are under a canopy as often as the ground is.

**Trees of biome mods.** Biome mods often grow trees with features of their own rather than Minecraft's tree feature;
Biomes O' Plenty does for nearly all of its trees. Those features are placed whole on the same stand-in, standing on
grass, and count as trees if they place leaves; their crowns take the leaves and logs they place most. A feature that
needs more of the world than the stand-in gives, or ground other than grass, is not drawn.

**Near and far.** An LOD of 64 columns covers `64 × 2^detail` blocks. Up to four terrain tiles across, the sampler
reads the full-resolution terrain, which runs the models over every tile it touches, as chunks do. Wider LODs read the
coarse map alone: no inference beyond the coarse stage, but no rivers or lakes and no detail finer than a coarse
pixel (7.7 km). That keeps the far horizon cheap.

| World type | Terrain tile | Full resolution up to | Coarse from |
|---|---|---|---|
| Diffusicraft | 512 blocks | detail 5 (2048 blocks per LOD) | detail 6 |
| Diffusicraft Grand | 1536 blocks | detail 6 (4096 blocks per LOD) | detail 7 |
| Diffusicraft Earth 1:1 | 7680 blocks | detail 8 (16384 blocks per LOD) | detail 9 |

**Height.** Distant Horizons packs the bottom and the height of each run of blocks into 12 bits, so it holds 4095
blocks above the bottom of a level and garbles anything higher. Diffusicraft Earth 1:1 is 9,344 blocks tall, so its
LODs stop at y = 3838:

- the generator's columns that reach higher sink into the rock under them by the difference, keeping their snow and
  soils on top;
- the chunks Distant Horizons reads are cut off at that height by `mixin.dh.ChunkWrapperMixin`, which applies only
  when Distant Horizons is installed.

Ground above y = 3838 shows flat in the far terrain; the chunks themselves are whole. Grand and the standard world
fit.

**Checking it.** The client game test samples the LODs around the spawn, block by block, and compares them with the
chunks generated there: the ground and its top block, and the share of the ground under tree crowns. It also checks
that a far LOD computes no terrain tile, and saves maps of both; see
[DEVELOPMENT.md](DEVELOPMENT.md#tests).

**Licensing.** Distant Horizons is LGPL-3.0. Diffusicraft compiles against its API (`compileOnly`) and never bundles
it; the integration loads only when Distant Horizons is installed.

## Voxy

Voxy draws terrain far beyond the render distance, as Distant Horizons does, from levels of detail it builds out of
the chunks the client loads and out of the worlds its importers read. It has no API for a world generator to supply
them; Diffusicraft fills them in from the terrain itself.

**Far terrain.** Voxy keeps its world in sections of 32 × 32 × 32 voxels at five levels of detail, a voxel being 1 to
16 blocks on a side, and reads each from its store when it first needs it, to draw it or to write a chunk into it.
Where the store has never held a section, Voxy would leave it empty. In a Diffusicraft world, in singleplayer,
`client.compat.voxy.VoxyTerrain` fills it from the terrain instead, through `worldgen.lod.LodGrid`, which cuts
voxels out of the same columns Distant Horizons gets (`LodSampler`): surface blocks from the material rules, rivers,
sea, ice, snow and tree crowns. A voxel shows the highest block in it that is neither air nor water, else water, else
air, as Voxy's coarser levels keep its finer ones; the sky lights the air, and water darkens one level per block
below its surface. Ground above y = 4095 sinks into the rock under it and stands flat there, as it does for
Distant Horizons.

- The sections filled are not saved. Voxy saves a section once the chunks it loads change it, filled blocks and
  all, and the chunks replace the filled blocks where they lie: trees, buildings and whatever players built show as
  they are.
- Voxy finds what to draw by walking down from its coarsest sections through the children each says hold blocks. A
  filled section claims the children under its blocks and one voxel round them, since the finer columns of a child
  can stand a little higher or wider. When a child turns out empty, its parents are put right as it is filled. A
  child that holds blocks its parent did not claim, a spike thinner than the parent's voxels, is not drawn until the
  chunks there are loaded.
- Three mixins, applied only when Voxy is installed, do this: `VoxyInstanceMixin` tells Diffusicraft which level
  each of Voxy's worlds holds as Voxy opens it, `WorldEngineMixin` keeps the terrain with it, and
  `ActiveSectionTrackerMixin` fills each section the store misses.
- On a server, Voxy's far terrain is still built from the chunks the client sees: the client has no terrain of its
  own to fill it from. Turn [`voxyLods`](CONFIG.md#voxylods) off to have the same in singleplayer.
- Not yet checked in game; the voxel rule has unit tests (`LodGridTest`).

**Heights.** Voxy packs the height of its sections into 8 bits, so it holds y = −4096 to 4095. Grand fits. In a taller
level, Earth 1:1 or a dimension of up to 32,736 blocks (see [HEIGHT.md](HEIGHT.md)), its renderer walked the sections
past that, which wrap round to the other end of the level, and stopped the game with "Position not valid at all
levels" as soon as the player entered. Two mixins, applied only when Voxy is installed, keep it within its heights:

- `client.mixin.voxy.VoxyRenderSystemMixin` gives the renderer the range of its coarsest sections, 512 blocks tall,
  cut to the heights Voxy holds;
- `client.mixin.voxy.WorldUpdaterMixin` leaves out of Voxy's store the chunk sections above or below them.

In Earth 1:1, ground above y = 4095 shows cut flat in Voxy's far terrain; the chunks themselves are whole.

**Checking it.** The client game tests for tall dimensions and for Earth 1:1 pass with Voxy
(`-PdevMods=voxy,sodium`: Voxy needs Sodium) and save screenshots.

**Licensing.** Voxy is All Rights Reserved. It is a dev mod outside the pack (`localRuntime`, named in `-PdevMods`),
never a dependency and never bundled. Diffusicraft compiles its mixins against its own declarations of the few Voxy
members they use, in the `voxyStubs` source set, never against Voxy; those declarations are not shipped. Each has
been checked against Voxy 0.2.20-beta's classes: names, types and constants.

## Sodium

Sodium replaces the client's chunk renderer. In a dimension taller than vanilla's it differs from vanilla twice:

- **Building sections.** Sodium packs the position of each section waiting to be built into 10 bits a coordinate and
  counted heights from y = −2048, so it reached only up to y = 14,335. Sections outside that range came back at the
  wrong height and were never built: at y = ±16,000 it drew nothing. Grand and Earth 1:1 fit in that range; the
  tallest dimensions do not. The mixins in `client.mixin.sodium`, applied only when Sodium is installed, count the
  heights from below the camera's search area instead (`client.compat.sodium.SodiumTaskHeights`), so every section
  Sodium looks at is built, at any height. In levels within Sodium's range the sections built are the same.
- **Memory.** Sodium keeps render data for every section of every loaded chunk, so Diffusicraft's window of rows
  around the camera does nothing with it (see [HEIGHT.md](HEIGHT.md#the-clients-render-grid)).

**Checking it.** The client game test for tall dimensions, run with Sodium (`-PdevMods=sodium`), draws the platforms
at y = ±16,000 and a chunk of mixed blocks at y = 16,340. Under Sodium, Fabric's `waitForChunksRender` returns at
once, before Sodium has queued anything, so the game tests first wait for the section of a block they look at to be
built (`GameTests.waitForRender`).

**Licensing.** Sodium is under the PolyForm Shield license. It is a dev mod (`localRuntime`); Diffusicraft's mixins
name its classes and never compile against it.

## Biome mods

Biome mods that add to the overworld's biome table, such as TerraBlender and the mods built on it (Biomes O' Plenty,
BYG), place their biomes by the six climate parameters. Diffusicraft computes those parameters from the real climate
(see [WORLDGEN.md](WORLDGEN.md#biome-parameters)), so their biomes land where the climate matches what they asked for.

**Checked with Biomes O' Plenty.** The client game test, run with Biomes O' Plenty and TerraBlender
(`-PdevMods=biomes-o-plenty,glitchcore,terrablender`), finds them sharing the land with vanilla's biomes and
Diffusicraft's own: around a temperate spawn, redwood forest, old-growth woodland, woodland, orchard, highland and
field next to vanilla's forest and plains, and within 24 km the same nine of Diffusicraft's refined biomes as without
them.

- **Regions.** TerraBlender divides the world into regions, each with the biome table of one mod, by a noise of its
  own. A Biomes O' Plenty region takes its biomes from Biomes O' Plenty's table, with Diffusicraft's parameters.
  Diffusicraft refines only vanilla's picks (see [WORLDGEN.md](WORLDGEN.md#refined-biomes)), so the mod's biomes stay
  as they are. The regions keep TerraBlender's size in blocks at every scale, which is small next to the terrain of
  Diffusicraft Earth 1:1.
- **Surfaces.** TerraBlender tries the mods' material rules first, in the mods' own biomes, and the world's wherever
  they place nothing. Everything else, the rivers, the rock and Diffusicraft's soils, comes from Diffusicraft's rules.
  TerraBlender does this by wrapping the world's rule in a new one, so Diffusicraft tells its worlds by the rule the
  noise settings were loaded with, not by the one they hand out.
- **Distant Horizons.** The LODs run the same combined rules, and draw the mods' trees (see
  [Trees of biome mods](#distant-horizons)). In the test the LOD shows crowns over 83% of the redwood forest, 70% of
  the old-growth woodland and 43% of the woodland.

## Tech mods

Tech mods add metals and gems vanilla lacks, each with its own scattered ores. Diffusicraft leaves those as they are
and also puts the minerals in the geology's ore bodies, in the rocks where they form: tin in granite, zinc and lead in
limestone, nickel and the platinum metals in gabbro and peridotite (see [WORLDGEN.md](WORLDGEN.md#the-ground)). It
adds no blocks of its own and does not depend on any tech mod: `worldgen.material.ModOres` finds the ores through the
common tags `c:ores/<metal>` when the tags load, and the bodies stay vanilla's for any mineral no mod adds.

**Checked with Tech Reborn.** The client game test, run with Tech Reborn (`-PdevMods=techreborn`), checks that the
geology places its tin and lead ores (`lead_ore`, not `galena_ore`, because the metal's tag comes first), silver,
uranium, rubies, sapphires and bauxite, and not its sphalerite, which Tech Reborn puts in the Nether. Tech Reborn's
ores carry neither `c:ores_in_ground` tags nor a map colour, so `ModOres` reads the ground from Tech Reborn's own ore
features. Its tungsten, peridot and sheldonite (platinum) grow only in the End. Tech Reborn also has deepslate forms of
them that it never generates, and the geology places those, so the three turn up in the overworld too, in their
deepslate form even in shallow rock.
