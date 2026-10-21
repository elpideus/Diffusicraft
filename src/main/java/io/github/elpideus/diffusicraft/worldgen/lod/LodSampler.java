package io.github.elpideus.diffusicraft.worldgen.lod;

import io.github.elpideus.diffusicraft.mixin.MaterialRuleContextAccessor;
import io.github.elpideus.diffusicraft.worldgen.CoarseClimate;
import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainSurface;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.river.RiverColumn;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
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
 * the slope. Snow and ice follow the biome's temperature, as they do in the world. Trees stand on the soils as
 * crowns of leaves, as many and as large as the biome's own trees (see {@link TreeCover}). What the column leaves out
 * is what a far view cannot show: caves, plants and structures.
 *
 * <p>A grid wider than {@value #FULL_DETAIL_TILES} terrain tiles is sampled from the coarse map alone, inside
 * {@link CoarseClimate#call}: it costs no model inference beyond the coarse stage, but leaves out the rivers and
 * everything finer than a coarse pixel. Narrower grids read the full-resolution terrain, which runs the models over
 * every tile they touch. The ledges and the gullies show only in grids whose columns are at most {@value #CELL}
 * blocks apart: sampled further apart than that they would be noise. There the ground runs straight between the
 * corners of the cells the chunks interpolate their density over, as it does in the chunks. The sea cliffs and the
 * shape of the sea floor show in every grid that reads the full-resolution terrain.
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
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final BlockState ICE = Blocks.ICE.defaultBlockState();
	private static final BlockState SNOW = Blocks.SNOW.defaultBlockState();
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private final NoiseBasedChunkGenerator generator;
	private final RandomState randomState;
	private final LevelHeightAccessor heights;
	private final TerrainField field;
	private final WorldScale scale;
	private final MaterialRule rule;
	private final BlockState defaultBlock;
	private final int seaLevel;
	private final int minY;
	private final int maxY;
	private final long seed;
	private final Map<Holder<Biome>, TreeCover> trees = new ConcurrentHashMap<>();

	/** Receives the columns of a grid, row by row. */
	@FunctionalInterface
	public interface Sink {
		/** Column {@code (x, z)} of the grid, counted from its corner. The column is reused afterwards. */
		void accept(int x, int z, LodColumn column);
	}

	private LodSampler(NoiseBasedChunkGenerator generator, RandomState randomState, LevelHeightAccessor heights,
					   BiomeWorld world, long seed) {
		this.generator = generator;
		this.randomState = randomState;
		this.heights = heights;
		this.field = world.field();
		this.scale = world.scale();
		NoiseGeneratorSettings settings = generator.generatorSettings().value();
		this.rule = settings.materialRule().value();
		this.defaultBlock = settings.defaultBlock();
		this.seaLevel = settings.seaLevel();
		WorldGenerationContext context = new WorldGenerationContext(generator, heights);
		this.minY = context.getMinGenY();
		this.maxY = context.getMinGenY() + context.getGenDepth() - 1;
		this.seed = seed;
	}

	/** The sampler of a world, or {@code null} if it is not generated by Diffusicraft. */
	public static @Nullable LodSampler of(ServerLevel level) {
		if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
			return null;
		}
		RandomState state = level.getChunkSource().randomState();
		BiomeWorld world = ((BiomeWorld.Carrier) (Object) state).diffusicraft$biomeWorld();
		return world == null ? null : new LodSampler(generator, state, level, world, level.getSeed());
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
		if (coarse(width, step)) {
			sampleCoarse(minX, minZ, width, step, sink);
		} else {
			new Grid(minX, minZ, width, step).sample(false, sink);
		}
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
		}

		private int block(int min, int index) {
			return min + index * step + step / 2;
		}

		void sample(boolean coarse, Sink sink) {
			sampleHeights(coarse);

			BiomeResolver biomes = generator.getBiomeSource()
				.createResolver(randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
			@SuppressWarnings("unchecked")
			Holder<Biome>[] biome = new Holder[1];
			MaterialRuleContext context = MaterialRuleContextAccessor.diffusicraft$create(randomState.surfaceSystem(),
				randomState, new DensityVolume(1, 1, 1, minX, minY, minZ),
				randomState.samplersWithContext(SamplerContext.EMPTY_UNCACHED), pos -> biome[0],
				new WorldGenerationContext(generator, heights), null);
			MaterialRuleContextAccessor rules = (MaterialRuleContextAccessor) (Object) context;
			RuleEvaluator evaluator = rule.compile(context);
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			LodColumn column = new LodColumn();
			TreeCover.Crown crown = new TreeCover.Crown();

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

					biome[0] = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(top), QuartPos.fromBlock(z));
					column.reset(biome[0]);
					// The chunk generator measures slopes between neighbouring blocks; these columns are a step apart.
					int gradientX = Math.round((heightmap[k + 1] - heightmap[k - 1]) / (float) step);
					int gradientZ = Math.round((heightmap[k + span] - heightmap[k - span]) / (float) step);
					rules.diffusicraft$updateXZ(x, z, gradientX, gradientZ);
					// The surface building sees the sea, but not the rivers, which the rules pour themselves.
					int waterHeight = sea ? seaLevel : Integer.MIN_VALUE;
					int surfaceBottom = Math.max(minY, top - SURFACE_DEPTH + 1);
					for (int y = top; y >= surfaceBottom; y--) {
						surface[top - y] = apply(evaluator, rules, x, y, z, groundTop, waterHeight);
					}
					int rockY = Math.max(minY, top - ROCK_DEPTH);
					column.add(apply(evaluator, rules, x, rockY, z, groundTop, waterHeight), minY, surfaceBottom);
					for (int y = surfaceBottom; y <= top; y++) {
						column.add(surface[top - y], y, y + 1);
					}
					column.markGround();

					Biome climate = biome[0].value();
					int sky = top + 1;
					if (water > top) {
						// Water freezes where it is cold enough to snow, as the chunk's features freeze it.
						boolean frozen = climate.coldEnoughToSnow(pos.set(x, water, z), seaLevel);
						column.add(WATER, top + 1, frozen ? water : water + 1);
						if (frozen) {
							column.add(ICE, water, water + 1);
						}
						column.waterDepth(water - top);
					} else {
						if (surface[0].is(BlockTags.SUPPORTS_VEGETATION)
							&& trees.computeIfAbsent(biome[0], TreeCover::of).crown(seed, x, z, step, crown)) {
							int crownTop = Math.min(maxY - 1, top + crown.high);
							int crownBottom = Math.max(top + 1, Math.min(crownTop, top + crown.low));
							column.add(crown.holdsTrunk ? crown.trunk : AIR, top + 1, crownBottom);
							column.add(crown.leaves, crownBottom, crownTop + 1);
							sky = crownTop + 1;
						}
						// Snow settles on whatever is highest, the leaves included.
						if (sky <= maxY && climate.hasPrecipitation()
							&& climate.coldEnoughToSnow(pos.set(x, sky, z), seaLevel)) {
							column.add(SNOW, sky, sky + 1);
						}
					}
					sink.accept(a, b, column);
				}
			}
		}

		/** The ground, the rivers and the sea over the grid and its border. */
		private void sampleHeights(boolean coarse) {
			Rivers rivers = coarse ? null : Rivers.of(field);
			TerrainSurface surface = coarse ? null : field.surface(scale);
			TerrainSurface detailed = step > CELL ? null : surface;
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

		private BlockState apply(RuleEvaluator evaluator, MaterialRuleContextAccessor rules, int x, int y, int z,
								 int groundTop, int waterHeight) {
			rules.diffusicraft$updateY(groundTop - y + 1, y - minY + 1, waterHeight, y);
			BlockState state = evaluator.tryApply(x, y, z);
			return state != null ? state : defaultBlock;
		}
	}
}
