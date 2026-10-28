package io.github.elpideus.diffusicraft.worldgen.lod;

import io.github.elpideus.diffusicraft.mixin.MaterialRuleContextAccessor;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import io.github.elpideus.diffusicraft.worldgen.DiffusicraftWorldgen;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainSurface;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.river.RiverColumn;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Levels of detail of a Diffusicraft world, straight from the terrain: for each column of a square grid, the blocks
 * at its top, the water over it and its biome, without generating a chunk. Distant Horizons draws its far terrain
 * from it (see {@code compat.dh}).
 *
 * <p>The surface is the one the chunk generator builds: the terrain's height, cut by the rivers, with the sea up to
 * its level. The blocks come from the world's own material rules (soils, sand, gravel, snow on the peaks and the rock
 * strata in the cliffs), run on each column as the chunk generator runs them, with the neighbouring columns giving
 * the slope. Snow and ice follow the biome's temperature, as they do in the world. In the grids whose columns are
 * close enough to tell one tree from another, the trees stand where the world grows them (see {@link FarTrees});
 * further out they stand on the soils as crowns of leaves, as many and as large as the biome's own trees, scattered at
 * random (see {@link TreeCover}). The giants, those that grow as structures and the redwoods the biomes plant, stand
 * where the world grows them at every distance (see {@link GreatTrees}), their crowns over water too. The structures
 * on the surface, villages, temples, shipwrecks and the like, stand where the world starts them in the grids whose
 * columns are close enough to show them (see {@link FarStructures}).
 *
 * <p>In the grids whose columns are at most {@value #CAVE_STEP} blocks apart, the caverns of the Hollows open under the
 * ground where the chunks open them (see {@link FarHollows}), so that a far view across a Grand Hollow shows its far
 * walls, its floor and its ceiling rather than the void. They hold {@link Blocks#CAVE_AIR}, which the far terrain
 * keeps dark, their lakes and the lava at the bottom of the world; their floors and ceilings take the blocks the
 * material rules lay there, the glowing patches of the lit ceilings among them, and the rock around them shows its
 * strata. What the column leaves out is what a far view cannot show: vanilla's caves, plants and the structures
 * underground.
 *
 * <p>A grid wider than {@value #FULL_DETAIL_TILES} terrain tiles is sampled from the coarse map alone, inside
 * {@link CoarseClimate#call}: it costs no model inference beyond the coarse stage, but leaves out the rivers and
 * everything finer than a coarse pixel. Narrower grids read the full-resolution terrain, which runs the models over
 * every tile they touch. The ledges and the gullies show only in grids whose columns are at most {@value #CELL}
 * blocks apart: sampled further apart than that they would be noise. There the ground runs straight between the
 * corners of the cells the chunks interpolate their density over, as it does in the chunks. The sea cliffs and the
 * shape of the sea floor show in every grid that reads the full-resolution terrain.
 *
 * <p>A narrower grid can also be sampled at less than its full detail ({@link Detail}), so that what draws it need not
 * wait for the models: without the rivers, which need the tiles {@value Rivers#RING} rings around it, once its own
 * tiles are computed, and from the coarse map before then. {@link #available} says how finely the terrain computed so
 * far samples a grid, and {@link #prepare} computes what the grid lacks, a tile at a time, while the models are idle.
 *
 * <p>Thread-safe: each call to {@link #sample} works on its own state.
 */
public final class LodSampler {
	/** Grids wider than this many terrain tiles read the coarse map. */
	static final int FULL_DETAIL_TILES = 4;
	/**
	 * Width of the cells the chunks interpolate their density over, in blocks, and the widest step between columns at
	 * which the ground keeps its ledges and gullies.
	 */
	static final int CELL = 4;
	/** Blocks at the top of each column that the material rules decide one by one. */
	private static final int SURFACE_DEPTH = 4;
	/** Depth whose block stands for all the rock below the surface blocks. */
	private static final int ROCK_DEPTH = 12;
	/** Grids whose columns are further apart than this show no caves. */
	static final int CAVE_STEP = 8;
	/** Height of the bands of rock around the caves, each one block. */
	private static final int BAND = 16;
	/** Where in its band the block of a band is taken, and where again if that is an ore. */
	private static final int[] BAND_SAMPLES = {8, 11, 5, 14, 2};
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final BlockState ICE = Blocks.ICE.defaultBlockState();
	private static final BlockState SNOW = Blocks.SNOW.defaultBlockState();
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final BlockState CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();
	private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

	private final NoiseBasedChunkGenerator generator;
	private final RandomState randomState;
	private final LevelHeightAccessor heights;
	private final BiomeWorld world;
	private final TerrainField field;
	private final WorldScale scale;
	private final MaterialRule rule;
	private final BlockState defaultBlock;
	private final int seaLevel;
	private final int minY;
	private final int maxY;
	/** Open space below this fills with lava. */
	private final int lavaLevel;
	private final long seed;
	private final Map<Holder<Biome>, TreeCover> trees = new ConcurrentHashMap<>();
	private final @Nullable GreatTrees greatTrees;
	private final @Nullable FarTrees farTrees;
	private final @Nullable FarStructures structures;
	private final @Nullable LodStore store;

	/** How finely a grid is sampled. */
	public enum Detail {
		/** The full-resolution terrain with its rivers, or the coarse map for a grid too wide for it. */
		FULL,
		/** The full-resolution terrain without the rivers, their lakes and their valleys. */
		RIVERLESS,
		/** The coarse map alone. */
		COARSE
	}

	/** Receives the columns of a grid, row by row. */
	@FunctionalInterface
	public interface Sink {
		/** Column {@code (x, z)} of the grid, counted from its corner. The column is reused afterwards. */
		void accept(int x, int z, LodColumn column);
	}

	private LodSampler(NoiseBasedChunkGenerator generator, RandomState randomState, LevelHeightAccessor heights,
					   BiomeWorld world, long seed, @Nullable GreatTrees greatTrees, @Nullable FarTrees farTrees,
					   @Nullable FarStructures structures, @Nullable LodStore store) {
		this.generator = generator;
		this.randomState = randomState;
		this.heights = heights;
		this.world = world;
		this.field = world.field();
		this.scale = world.scale();
		NoiseGeneratorSettings settings = generator.generatorSettings().value();
		this.rule = DiffusicraftWorldgen.materialRule(settings, settings.materialRule().value());
		this.defaultBlock = settings.defaultBlock();
		this.seaLevel = settings.seaLevel();
		WorldGenerationContext context = new WorldGenerationContext(generator, heights);
		this.minY = context.getMinGenY();
		this.maxY = context.getMinGenY() + context.getGenDepth() - 1;
		// As the chunks' fluid picker has it (see NoiseBasedChunkGeneratorMixin).
		this.lavaLevel = Math.min(settings.noiseSettings().minY() + 10, seaLevel);
		this.seed = seed;
		this.greatTrees = greatTrees;
		this.farTrees = farTrees;
		this.structures = structures;
		this.store = store;
	}

	/** The sampler of a world, or {@code null} if it is not generated by Diffusicraft. */
	public static @Nullable LodSampler of(ServerLevel level) {
		BiomeWorld world = world(level);
		return world == null ? null : new LodSampler((NoiseBasedChunkGenerator) level.getChunkSource().getGenerator(),
			level.getChunkSource().randomState(), level, world, level.getSeed(),
			GreatTrees.of(level.getChunkSource().getGenerator(), level.getChunkSource().getGeneratorState(), world.scale(),
				level.getMaxY()), FarTrees.of(level, world.scale()), FarStructures.of(level), LodStore.of(level));
	}

	/** Whether Diffusicraft generates a world. */
	static boolean generates(ServerLevel level) {
		return world(level) != null;
	}

	private static @Nullable BiomeWorld world(ServerLevel level) {
		if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) {
			return null;
		}
		RandomState state = level.getChunkSource().randomState();
		return ((BiomeWorld.Carrier) (Object) state).diffusicraft$biomeWorld();
	}

	/** The columns of the places players have been, or {@code null} for a world of no running server. */
	public @Nullable LodStore store() {
		return store;
	}

	public TerrainField field() {
		return field;
	}

	public int minY() {
		return minY;
	}

	/** Highest block the terrain reaches. */
	public int maxY() {
		return maxY;
	}

	public int seaLevel() {
		return seaLevel;
	}

	public WorldScale scale() {
		return scale;
	}

	/** The biomes the world's biome source can place. */
	public Set<Holder<Biome>> possibleBiomes() {
		return generator.getBiomeSource().possibleBiomes();
	}

	/** Whether a grid of {@code width} columns {@code step} blocks apart is sampled from the coarse map. */
	public boolean coarse(int width, int step) {
		double tileBlocks = TerrainField.TILE / scale.pixelsPerBlock();
		return (double) width * step > FULL_DETAIL_TILES * tileBlocks;
	}

	/**
	 * Samples the grid of {@code width}² columns whose corner block is {@code (minX, minZ)}, one column every
	 * {@code step} blocks, each taken at the middle of its square.
	 */
	public void sample(int minX, int minZ, int width, int step, Sink sink) {
		sample(minX, minZ, width, step, Detail.FULL, sink);
	}

	/** Samples a grid like {@link #sample(int, int, int, int, Sink)}, but no finer than {@code detail}. */
	public void sample(int minX, int minZ, int width, int step, Detail detail, Sink sink) {
		switch (coarse(width, step) ? Detail.COARSE : detail) {
			case FULL -> new Grid(minX, minZ, width, step).sample(false, sink);
			case RIVERLESS -> CoarseClimate.callWithoutRivers(() -> {
				new Grid(minX, minZ, width, step).sample(false, sink);
				return null;
			});
			case COARSE -> sampleCoarse(minX, minZ, width, step, sink);
		}
	}

	/**
	 * The finest detail a grid can be sampled at from the terrain the models have computed, without running them: in
	 * full once the tiles it and its rivers need are in memory, without the rivers once its own are, and from the
	 * coarse map until then.
	 */
	public Detail available(int minX, int minZ, int width, int step) {
		if (coarse(width, step)) {
			return Detail.FULL;
		}
		int[] tiles = tiles(minX, minZ, width, step);
		int[] needed = Rivers.of(field).tilesNeeded(tiles[0], tiles[1], tiles[2], tiles[3]);
		// The grid's own tiles come first.
		int own = 2 * (tiles[2] - tiles[0] + 1) * (tiles[3] - tiles[1] + 1);
		for (int t = 0; t < needed.length; t += 2) {
			if (!field.tileReady(needed[t], needed[t + 1])) {
				return t < own ? Detail.COARSE : Detail.RIVERLESS;
			}
		}
		return Detail.FULL;
	}

	/**
	 * Gets the terrain ready for a grid to be sampled in full without running the models: runs them over the first
	 * tile it needs that is not in memory, but only while they are idle ({@link TerrainField#prefetchWhenIdle}), and
	 * one tile a call, so that chunk generation never waits for more than one of the grid's.
	 *
	 * @return whether the grid is ready to sample in full
	 */
	public boolean prepare(int minX, int minZ, int width, int step) {
		if (coarse(width, step)) {
			return true;
		}
		int[] tiles = tiles(minX, minZ, width, step);
		int[] needed = Rivers.of(field).tilesNeeded(tiles[0], tiles[1], tiles[2], tiles[3]);
		boolean computed = false;
		for (int t = 0; t < needed.length; t += 2) {
			if (field.tileReady(needed[t], needed[t + 1])) {
				continue;
			}
			if (computed || !field.prefetchWhenIdle(needed[t], needed[t + 1])) {
				return false;
			}
			computed = true;
		}
		return true;
	}

	/**
	 * The tiles of the terrain a grid reads, as {@code {ti0, tj0, ti1, tj1}}: those of its columns and their border,
	 * and of the corners of the cells around them.
	 */
	private int[] tiles(int minX, int minZ, int width, int step) {
		int low = -step + step / 2 - CELL;
		int high = width * step + step / 2 + CELL;
		// Pixel i grows with Z and j with X.
		return new int[]{TerrainField.tileOf(scale.toPixel(minZ + low)), TerrainField.tileOf(scale.toPixel(minX + low)),
			TerrainField.tileOf(scale.toPixel(minZ + high)), TerrainField.tileOf(scale.toPixel(minX + high))};
	}

	/**
	 * Samples a grid like {@link #sample}, from the coarse map alone however narrow the grid is: no model inference
	 * beyond the coarse stage, but no rivers and nothing finer than a coarse pixel either. For overviews, such as the
	 * relief map's, which show more land than anyone will ever load.
	 */
	public void sampleCoarse(int minX, int minZ, int width, int step, Sink sink) {
		Grid grid = new Grid(minX, minZ, width, step);
		CoarseClimate.call(() -> {
			grid.sample(true, sink);
			return null;
		});
	}

	/**
	 * The biomes at the surface of the coarse map, as {@link #sampleCoarse} finds them, for searches over many
	 * kilometres. Call it from inside {@link CoarseClimate#call}; each lookup belongs to one thread.
	 */
	public CoarseBiomes coarseBiomes() {
		BiomeResolver biomes = generator.getBiomeSource()
			.createResolver(randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		return (x, z) -> {
			float surface = scale.surfaceY(field.coarseElevation(scale.toPixel(z), scale.toPixel(x)));
			int top = Mth.clamp(Mth.ceil(surface) - 1, minY, maxY - 1);
			return biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(top), QuartPos.fromBlock(z));
		};
	}

	/** The biome at the surface of a column of the coarse map. */
	@FunctionalInterface
	public interface CoarseBiomes {
		Holder<Biome> at(int x, int z);
	}

	private final class Grid {
		private final int minX;
		private final int minZ;
		private final int width;
		private final int step;
		/** The grid with a border of one column, for the slopes at its edges. */
		private final int span;
		/** Highest solid block before the material rules run, as the noise leaves it. */
		private final int[] ground;
		/** Highest block that is not air when the material rules run: the ground or the sea over it. */
		private final int[] heightmap;
		/** Highest block of a river's bed, or {@link Integer#MIN_VALUE} away from the rivers' channels. */
		private final int[] bed;
		/** Highest river water block. */
		private final int[] riverTop;
		private final BlockState[] surface = new BlockState[SURFACE_DEPTH];
		/** The rivers and the full-resolution ground, or {@code null} in a grid of the coarse map. */
		private @Nullable Rivers rivers;
		private @Nullable TerrainSurface terrain;
		private BiomeResolver biomes;
		/** The biome the material rules see. */
		private Holder<Biome> biome;
		private MaterialRuleContextAccessor rules;
		private RuleEvaluator evaluator;
		/**
		 * The column whose rock {@link #underground} lays: where the bands of rock around its caves start and end, the
		 * block of each band, once known, and the rock over them.
		 */
		private int columnX;
		private int columnZ;
		private int columnGround;
		private int bandLow;
		private int bandHigh;
		private BlockState[] bands = new BlockState[8];
		private BlockState upper;
		private final int[] level = new int[1];
		/** The ground at the corners of the cells the chunks interpolate it over, as {@link #fineLand} finds it. */
		private final Long2FloatOpenHashMap corners = new Long2FloatOpenHashMap();

		Grid(int minX, int minZ, int width, int step) {
			this.minX = minX;
			this.minZ = minZ;
			this.width = width;
			this.step = step;
			this.span = width + 2;
			this.ground = new int[span * span];
			this.heightmap = new int[span * span];
			this.bed = new int[span * span];
			this.riverTop = new int[span * span];
			corners.defaultReturnValue(Float.NaN);
		}

		private int block(int min, int index) {
			return min + index * step + step / 2;
		}

		void sample(boolean coarse, Sink sink) {
			sampleHeights(coarse);

			biomes = generator.getBiomeSource()
				.createResolver(randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
			MaterialRuleContext context = MaterialRuleContextAccessor.diffusicraft$create(randomState.surfaceSystem(),
				randomState, new DensityVolume(1, 1, 1, minX, minY, minZ),
				randomState.samplersWithContext(SamplerContext.EMPTY_UNCACHED), pos -> biome,
				new WorldGenerationContext(generator, heights), null);
			rules = (MaterialRuleContextAccessor) (Object) context;
			evaluator = rule.compile(context);
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			LodColumn column = new LodColumn();
			TreeCover.Crown crown = new TreeCover.Crown();
			GreatTrees.Stand giants = greatTrees == null ? null : greatTrees.stand(this::dryGround, biomes);
			FarTrees.Crowns crowns = farTrees == null || terrain == null || step > FarTrees.MAX_STEP ? null
				: farTrees.grow(minX, minZ, width, step, this::fineLand, this::soil, defaultBlock, biomes);
			// Only over the full terrain with its rivers, which the starts kept for later grids are made on.
			FarStructures.Cell[] built = structures == null || rivers == null || terrain == null
				|| step > FarStructures.MAX_STEP ? null
				: structures.sketch(minX, minZ, width, step, this::land, biomes, defaultBlock);
			FarHollows caves = step > CAVE_STEP ? null : new FarHollows(world, minY, this::hollowsSurface,
				block(minX, 0), block(minZ, 0), block(minX, width - 1), block(minZ, width - 1));

			for (int b = 0; b < width; b++) {
				int z = block(minZ, b);
				for (int a = 0; a < width; a++) {
					int x = block(minX, a);
					int k = (b + 1) * span + a + 1;
					int groundTop = ground[k];
					int top = groundTop;
					int water = Integer.MIN_VALUE;
					if (bed[k] != Integer.MIN_VALUE) {
						top = bed[k];
						water = riverTop[k];
					}
					boolean sea = groundTop < seaLevel - 1;
					if (sea) {
						water = Math.max(water, seaLevel - 1);
					}

					biome = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(top), QuartPos.fromBlock(z));
					column.reset(biome);
					// The chunk generator measures slopes between neighbouring blocks; these columns are a step apart.
					int gradientX = Math.round((heightmap[k + 1] - heightmap[k - 1]) / (float) step);
					int gradientZ = Math.round((heightmap[k + span] - heightmap[k - span]) / (float) step);
					rules.diffusicraft$updateXZ(x, z, gradientX, gradientZ);
					// The surface building sees the sea, but not the rivers, which the rules pour themselves.
					int waterHeight = sea ? seaLevel : Integer.MIN_VALUE;
					int surfaceBottom = Math.max(minY, top - SURFACE_DEPTH + 1);
					for (int y = top; y >= surfaceBottom; y--) {
						surface[top - y] = apply(x, y, z, groundTop - y + 1, y - minY + 1, waterHeight);
					}
					FarStructures.Cell cell = built == null ? null : built[b * width + a];
					if (cell != null && cell.paving() != null) {
						surface[0] = cell.paving();
					}
					// The rules see the surface first and then the rock under it, as they do in the chunks.
					int rockY = Math.max(minY, top - ROCK_DEPTH);
					BlockState rock = apply(x, rockY, z, groundTop - rockY + 1, rockY - minY + 1, waterHeight);
					int caverns = caves == null ? 0 : caves.open(x, z, surfaceBottom, step);
					if (caves != null && caves.near()) {
						underground(column, caves, caverns, x, z, groundTop, surfaceBottom, waterHeight, rock);
					} else {
						column.add(rock, minY, surfaceBottom);
					}
					for (int y = surfaceBottom; y <= top; y++) {
						column.add(surface[top - y], y, y + 1);
					}
					// A structure's roof is the ground, as it is in the chunks (see ChunkColumns). The water fills what it
					// leaves open under the water's surface, as in a sunken ship.
					int floor = top;
					if (cell != null) {
						int[] ys = cell.ys();
						for (int i = 0; i < ys.length; i++) {
							if (ys[i] > floor) {
								column.add(WATER, floor + 1, Math.min(ys[i], water + 1));
								column.add(cell.states()[i], ys[i], ys[i] + 1);
								floor = ys[i];
							}
						}
					}
					column.markGround();

					Biome climate = biome.value();
					boolean dry = water <= floor;
					// Right over the ground or the water.
					int open = floor + 1;
					if (!dry) {
						// Water freezes where it is cold enough to snow, as the chunk's features freeze it.
						boolean frozen = climate.coldEnoughToSnow(pos.set(x, water, z), seaLevel);
						column.add(WATER, floor + 1, frozen ? water : water + 1);
						if (frozen) {
							column.add(ICE, water, water + 1);
						}
						column.waterDepth(water - floor);
						open = water + 1;
					}
					// No tree grows on a structure.
					boolean grown = cell == null && (crowns != null ? crowns.crown(a, b, crown)
						: dry && surface[0].is(BlockTags.SUPPORTS_VEGETATION)
						&& trees.computeIfAbsent(biome, holder -> TreeCover.of(holder, scale))
							.crown(seed, x, z, step, crown));
					// A giant's crown reaches out over the water too, and over the smaller trees.
					if (giants != null && cell == null) {
						grown = giants.crown(x, z, top, step, grown, crown);
					}
					int sky = open;
					if (grown) {
						int crownTop = Math.min(maxY - 1, top + crown.high);
						int crownBottom = Math.max(open, Math.min(crownTop, top + crown.low));
						if (crownBottom <= crownTop) {
							column.add(crown.holdsTrunk && dry ? crown.trunk : AIR, open, crownBottom);
							column.add(crown.leaves, crownBottom, crownTop + 1);
							sky = crownTop + 1;
						}
					}
					// Snow settles on whatever is highest, the leaves included, but the water.
					if ((dry || sky > open) && sky <= maxY && climate.hasPrecipitation()
						&& climate.coldEnoughToSnow(pos.set(x, sky, z), seaLevel)) {
						column.add(SNOW, sky, sky + 1);
					}
					sink.accept(a, b, column);
				}
			}
		}

		/** The ground, the rivers and the sea over the grid and its border. */
		private void sampleHeights(boolean coarse) {
			Rivers rivers = coarse || CoarseClimate.withoutRivers() ? null : Rivers.of(field);
			TerrainSurface surface = coarse ? null : field.surface(scale);
			TerrainSurface detailed = step > CELL ? null : surface;
			this.rivers = rivers;
			this.terrain = surface;
			// The chunks take the ground at the corners of their cells and run straight between them, which turns the
			// narrowest cliffs of the ledges and gullies into ramps. Do the same, or the steepest ground would stray.
			int cellX = 0, cellZ = 0, cells = 0;
			float[] corners = null;
			if (detailed != null) {
				cellX = Math.floorDiv(block(minX, -1), CELL);
				cellZ = Math.floorDiv(block(minZ, -1), CELL);
				cells = Math.floorDiv(block(minX, span - 2), CELL) + 2 - cellX;
				int rows = Math.floorDiv(block(minZ, span - 2), CELL) + 2 - cellZ;
				corners = new float[rows * cells];
				for (int r = 0; r < rows; r++) {
					for (int c = 0; c < cells; c++) {
						corners[r * cells + c] = (float) detailed.y((cellX + c) * CELL, (cellZ + r) * CELL);
					}
				}
			}
			for (int b = 0; b < span; b++) {
				int z = block(minZ, b - 1);
				double i = scale.toPixel(z);
				for (int a = 0; a < span; a++) {
					int x = block(minX, a - 1);
					double j = scale.toPixel(x);
					int k = b * span + a;
					float surfaceY;
					if (corners != null) {
						int dx = x - cellX * CELL, dz = z - cellZ * CELL;
						int c = (dz / CELL) * cells + dx / CELL;
						// Except at the sea cliffs, which the chunks take block by block (diffusicraft:coast).
						surfaceY = detailed.cliff(x, z);
						if (Float.isNaN(surfaceY)) {
							surfaceY = Mth.lerp2((dx % CELL) / (float) CELL, (dz % CELL) / (float) CELL, corners[c],
								corners[c + 1], corners[c + cells], corners[c + cells + 1]);
						}
					} else {
						surfaceY = coarse ? scale.surfaceY(field.coarseElevation(i, j)) : surface.shore(x, z);
					}
					int top = Mth.ceil(surfaceY) - 1;
					bed[k] = Integer.MIN_VALUE;
					if (rivers != null) {
						RiverColumn river = rivers.column(scale, x, z);
						if (river.river) {
							top = Mth.ceil(river.surface) - 1;
						}
						// The rules turn the channel's solid blocks above the bed to water.
						int waterTop = Math.min(river.waterTop, top);
						if (river.channel && river.bed < waterTop) {
							bed[k] = river.bed;
							riverTop[k] = waterTop;
						}
					}
					top = Mth.clamp(top, minY, maxY - 1);
					ground[k] = top;
					heightmap[k] = Math.max(top, seaLevel - 1);
				}
			}
		}

		/**
		 * The first block over the ground of column {@code (x, z)}, as {@link #land} finds the ground, or
		 * {@link Integer#MIN_VALUE} if the sea or a river covers it: where a giant may stand.
		 */
		private int dryGround(int x, int z) {
			FarSketch.Land land = land(x, z);
			return land.water() > land.ground() ? Integer.MIN_VALUE : land.ground() + 1;
		}

		/**
		 * The ground of column {@code (x, z)} and the water over it, as {@link #sampleHeights} and {@link #sample} find
		 * them, but without the ledges and the gullies.
		 */
		private FarSketch.Land land(int x, int z) {
			return land(x, z, terrain != null ? terrain.shore(x, z)
				: scale.surfaceY(field.coarseElevation(scale.toPixel(z), scale.toPixel(x))));
		}

		/**
		 * The ground of column {@code (x, z)} and the water over it as the chunks find them, with the ledges and the
		 * gullies however far apart the grid's columns are: where the trees take root. Only over the full-resolution
		 * terrain.
		 */
		private FarSketch.Land fineLand(int x, int z) {
			float surfaceY = terrain.cliff(x, z);
			if (Float.isNaN(surfaceY)) {
				int cellX = Math.floorDiv(x, CELL), cellZ = Math.floorDiv(z, CELL);
				surfaceY = Mth.lerp2((x - cellX * CELL) / (float) CELL, (z - cellZ * CELL) / (float) CELL,
					corner(cellX, cellZ), corner(cellX + 1, cellZ), corner(cellX, cellZ + 1),
					corner(cellX + 1, cellZ + 1));
			}
			return land(x, z, surfaceY);
		}

		/** The ground at the corner of cell {@code (cellX, cellZ)}, as {@link #sampleHeights} takes it. */
		private float corner(int cellX, int cellZ) {
			long key = ChunkPos.pack(cellX, cellZ);
			float y = corners.get(key);
			if (Float.isNaN(y)) {
				y = (float) terrain.y(cellX * CELL, cellZ * CELL);
				corners.put(key, y);
			}
			return y;
		}

		/**
		 * The block the material rules lay on top of the ground of column {@code (x, z)}, which is {@code land}'s, with
		 * the slope to its neighbours as {@link #fineLand} finds them. A river's bed takes the soil of dry ground.
		 */
		private BlockState soil(int x, int z, FarSketch.Land land) {
			int top = land.ground();
			biome = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(top), QuartPos.fromBlock(z));
			rules.diffusicraft$updateXZ(x, z, surfaceHeight(x + 1, z) - surfaceHeight(x - 1, z),
				surfaceHeight(x, z + 1) - surfaceHeight(x, z - 1));
			return apply(x, top, z, 1, top - minY + 1, top < seaLevel - 1 ? seaLevel : Integer.MIN_VALUE);
		}

		/** The highest block of column {@code (x, z)} that is not air, the ground or the sea. */
		private int surfaceHeight(int x, int z) {
			return Math.max(fineLand(x, z).ground(), seaLevel - 1);
		}

		/** The ground of column {@code (x, z)} and the water over it, under {@code surfaceY}, the terrain's surface. */
		private FarSketch.Land land(int x, int z, float surfaceY) {
			int top = Mth.ceil(surfaceY) - 1;
			int bed = Integer.MIN_VALUE;
			int water = Integer.MIN_VALUE;
			if (rivers != null) {
				RiverColumn river = rivers.column(scale, x, z);
				if (river.river) {
					top = Mth.ceil(river.surface) - 1;
				}
				int waterTop = Math.min(river.waterTop, top);
				if (river.channel && river.bed < waterTop) {
					bed = river.bed;
					water = waterTop;
				}
			}
			top = Mth.clamp(top, minY, maxY - 1);
			if (top < seaLevel - 1) {
				water = Math.max(water, seaLevel - 1);
			}
			return new FarSketch.Land(bed != Integer.MIN_VALUE ? bed : top, water);
		}

		/**
		 * The rock under the surface blocks of a column the Hollows come near, from the bottom of the world up to
		 * {@code surfaceBottom}, with their caverns open in it: the lava at the bottom of the world, their lakes, and
		 * their dark air. Each floor and ceiling takes the block the material rules lay there, in the biome of its
		 * cavern. Around the caverns, where their walls show, the rock comes in bands of {@value #BAND} blocks, each
		 * the block the rules put in its middle, so that the walls show the strata; below and above, where no wall
		 * shows, it is one run, the upper one {@code rock}, the block under the surface.
		 */
		private void underground(LodColumn column, FarHollows caves, int caverns, int x, int z, int groundTop,
								 int surfaceBottom, int waterHeight, BlockState rock) {
			columnX = x;
			columnZ = z;
			columnGround = groundTop;
			bandLow = Math.max(minY, Math.floorDiv(caves.reachBottom(), BAND) * BAND);
			bandHigh = Math.max(bandLow, Math.min(surfaceBottom, Math.ceilDiv(caves.reachTop(), BAND) * BAND));
			int count = (bandHigh - bandLow) / BAND + 1;
			if (bands.length < count) {
				bands = new BlockState[count];
			}
			Arrays.fill(bands, 0, count, null);
			upper = rock;
			Holder<Biome> ground = biome;
			Holder<Biome> under = ground;
			// Rock stretch i lies under cavern i, and over cavern i - 1.
			for (int i = 0; i <= caverns; i++) {
				int bottom = i == 0 ? minY : caves.top(i - 1);
				int top = i < caverns ? caves.bottom(i) : surfaceBottom;
				// The block over the stretch's highest and the surface of the water the rules see over it, which for
				// the highest stretch are the ground's.
				int over = i < caverns ? top : groundTop + 1;
				int water = i < caverns ? Integer.MIN_VALUE : waterHeight;
				BlockState fluid = null;
				int fluidTop = top;
				Holder<Biome> cavern = ground;
				if (i < caverns) {
					cavern = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(top), QuartPos.fromBlock(z));
					if (top < lavaLevel) {
						fluid = LAVA;
						fluidTop = Math.min(caves.top(i), lavaLevel);
					} else {
						fluid = caves.lake(x, z, top, caves.top(i), level);
						fluidTop = fluid == null ? top : level[0];
					}
				}
				int from = bottom;
				int to = top;
				if (i > 0) {
					// The ceiling of the cavern under the stretch, which may glow.
					biome = under;
					column.add(rock(bottom, over - bottom, 1, water), bottom, bottom + 1);
					from++;
				}
				BlockState floor = null;
				if (i < caverns && to > from) {
					// Ice is solid: the rules count it into the depth. Water and lava stand over the floor.
					biome = cavern;
					floor = fluid == ICE ? rock(top - 1, fluidTop - top + 1, top - bottom, Integer.MIN_VALUE)
						: rock(top - 1, 1, top - bottom, fluid == null ? Integer.MIN_VALUE : fluidTop);
					to--;
				}
				biome = ground;
				for (int y = from; y < to; ) {
					int end = y >= bandHigh ? to : y < bandLow ? bandLow : Math.floorDiv(y, BAND) * BAND + BAND;
					column.add(band(y), y, Math.min(end, to));
					y = Math.min(end, to);
				}
				if (floor != null) {
					column.add(floor, top - 1, top);
				}
				if (i < caverns) {
					if (fluid != null) {
						column.add(fluid, top, fluidTop);
					}
					column.add(CAVE_AIR, fluidTop, caves.top(i));
				}
				under = cavern;
			}
			biome = ground;
		}

		/**
		 * The block the material rules lay at height {@code y} of the column {@link #underground} lays, with
		 * {@code above} blocks of rock over it down to the block itself, {@code below} under it up to it, and the
		 * surface of the water over it, if any. An ore found there would stand for a whole floor or ceiling: the band's
		 * rock does instead.
		 */
		private BlockState rock(int y, int above, int below, int waterHeight) {
			BlockState state = apply(columnX, y, columnZ, above, below, waterHeight);
			return state.is(ConventionalBlockTags.ORES) ? band(y) : state;
		}

		/**
		 * The rock of the band of the column {@link #underground} lays that takes in height {@code y}: the block the
		 * rules put in the middle of the band, or near it if that is an ore. The rock below the bands is the lowest's,
		 * and that above them the block under the surface.
		 */
		private BlockState band(int y) {
			if (y >= bandHigh) {
				return upper;
			}
			int index = y < bandLow ? 0 : (Math.floorDiv(y, BAND) * BAND - bandLow) / BAND;
			BlockState state = bands[index];
			if (state == null) {
				int bandBottom = bandLow + index * BAND;
				for (int offset : BAND_SAMPLES) {
					int at = Math.max(minY, bandBottom + offset);
					state = apply(columnX, at, columnZ, columnGround - at + 1, at - minY + 1, Integer.MIN_VALUE);
					if (!state.is(ConventionalBlockTags.ORES)) {
						break;
					}
				}
				bands[index] = state;
			}
			return state;
		}

		/** The surface the Hollows keep their roof under, as the chunks find it, or the coarse map's. */
		private int hollowsSurface(int x, int z) {
			return (int) Math.floor(terrain != null ? terrain.y(x, z)
				: scale.surfaceY(field.coarseElevation(scale.toPixel(z), scale.toPixel(x))));
		}

		/**
		 * The block the material rules lay at height {@code y} of column {@code (x, z)}, with {@code above} blocks of
		 * rock over it down to the block itself, {@code below} under it up to it, and the surface of the water over it.
		 */
		private BlockState apply(int x, int y, int z, int above, int below, int waterHeight) {
			rules.diffusicraft$updateY(above, below, waterHeight, y);
			BlockState state = evaluator.tryApply(x, y, z);
			return state != null ? state : defaultBlock;
		}
	}
}
