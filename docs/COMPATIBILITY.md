# Compatibility

Diffusicraft generates the overworld with Minecraft's own `NoiseBasedChunkGenerator` and multi-noise biome source,
fed by Terrain Diffusion (see [WORLDGEN.md](WORLDGEN.md)). Anything that works with those works with Diffusicraft
unless it replaces the overworld's generator itself. This page lists what has been checked, what Diffusicraft does
for each mod, and what is still open.

| Mod | Status | What Diffusicraft does |
|---|---|---|
| [Distant Horizons](#distant-horizons) 3.3.4 | Integrated | Draws the far terrain straight from the terrain, without chunks |
| Distant Horizons SeedGen, Voxy SeedGen | Superseded in Diffusicraft worlds | Distant Horizons uses Diffusicraft's generator; Voxy is planned |
| [Biomes O' Plenty](#biome-mods) 26.3.0.0.15, TerraBlender 26.3.0.0.9 | Works, checked in game | Their biomes get the same climate parameters as vanilla's; their trees show in the LODs |
| Oh The Biomes We've Gone (BYG) | Not checked: no release for 26.3 yet | Should work as Biomes O' Plenty does, through TerraBlender |
| C2ME 0.4.2-alpha.0.106+26.3 | Works, checked in game | Nothing: the terrain pipeline is thread-safe. The client game test passes with it (`-PdevMods=c2me-fabric`), LODs, rivers and lakes included, in well under half the time |
| Sodium, Iris, shader packs | Works | Inference leaves GPU memory for them; see [CONFIG.md](CONFIG.md#gpumemorylimitmb) |
| Structure mods | Works | Structures are placed by the vanilla generator |

## Distant Horizons

Distant Horizons draws terrain far beyond the render distance from levels of detail (LODs). By default it builds them
from generated chunks, which in a Diffusicraft world means running the whole chunk pipeline for every chunk in view.
With Diffusicraft installed it instead gets each LOD straight from the terrain: `compat.dh.DiffusicraftLodGenerator`
is registered for every Diffusicraft level when the level loads. Other levels, and Diffusicraft levels with
[`distantHorizonsLods`](CONFIG.md#distanthorizonslods) off, keep Distant Horizons' own generator or another mod's.
Its priority (20) is above the default, so it wins over SeedGen in Diffusicraft levels.

**What an LOD holds.** `worldgen.lod.LodSampler` fills each column of an LOD the way the chunk generator would:

- the terrain's height, cut by the rivers, with water in their channels and lakes, and the sea up to its level; at
  Earth 1:1 the nearest LODs, whose columns are at most 4 blocks apart, carry the gullies too;
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
