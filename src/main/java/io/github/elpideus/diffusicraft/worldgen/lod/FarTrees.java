package io.github.elpideus.diffusicraft.worldgen.lod;

import com.mojang.logging.LogUtils;
import io.github.elpideus.diffusicraft.mixin.ChunkGeneratorAccessor;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The trees of a far view's nearest grids, grown where the world grows them, so that a tree seen from afar is still
 * there when its chunk loads. Each chunk's vegetation features that grow trees are placed again as the chunk generator
 * places them ({@code applyBiomeDecoration}): in the same order, from the same seeds, on a {@link FarSketch} of the far
 * view's ground, which gives them its soil, its water and its biomes. The crowns are read from the leaves and logs
 * they place.
 *
 * <p>A few things the world has are left out. Each chunk grows its trees on the bare ground, as if it were decorated
 * first: where a crown from a chunk decorated earlier would keep a tree from growing, the far view still shows it. The
 * features of the other steps, the structures, and the vegetation that grows no tree are not placed, nor the trees of
 * the biomes under the ground, such as the azaleas over the lush caves: a chunk grows the features of the biomes at the
 * surface of its columns and of those around it. The redwoods are {@link GreatTrees}'s.
 *
 * <p>Only the grids whose columns are at most {@value #MAX_STEP} blocks apart grow them, as a grid grows every tree in
 * the chunks it covers. Thread-safe.
 */
final class FarTrees {
	/** Widest step between a grid's columns at which it grows the world's trees. */
	static final int MAX_STEP = 8;
	private static final int VEGETATION = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
	private static final Logger LOGGER = LogUtils.getLogger();

	/** A vegetation feature that grows trees, and its index among the features of its step, which seeds its draws. */
	private record Planting(PlacedFeature placed, int index) {
	}

	private final @Nullable ServerLevel level;
	private final ChunkGenerator generator;
	private final LevelHeightAccessor heights;
	private final int seaLevel;
	private final long seed;
	/** In the order the chunk generator places them. */
	private final Planting[] plantings;
	/** The place of each feature in {@link #plantings}, or {@code -1}. */
	private final Reference2IntOpenHashMap<PlacedFeature> places = new Reference2IntOpenHashMap<>();
	/** The plantings each biome grows, by their places. */
	private final Map<Holder<Biome>, BitSet> held = new ConcurrentHashMap<>();
	private final Set<PlacedFeature> failed = ConcurrentHashMap.newKeySet();

	private FarTrees(@Nullable ServerLevel level, ChunkGenerator generator, LevelHeightAccessor heights, int seaLevel,
					 long seed, Planting[] plantings) {
		this.level = level;
		this.generator = generator;
		this.heights = heights;
		this.seaLevel = seaLevel;
		this.seed = seed;
		this.plantings = plantings;
		places.defaultReturnValue(-1);
		for (int p = 0; p < plantings.length; p++) {
			places.put(plantings[p].placed, p);
		}
	}

	/** The trees of a world of {@code scale}, or {@code null} if its biomes grow none. */
	static @Nullable FarTrees of(ServerLevel level, WorldScale scale) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		List<FeatureSorter.StepFeatureData> steps = ((ChunkGeneratorAccessor) generator).diffusicraft$featuresPerStep()
			.get();
		return of(level, generator, level, level.getSeaLevel(), level.getSeed(),
			steps.size() > VEGETATION ? steps.get(VEGETATION).features() : List.of(), scale);
	}

	/**
	 * The trees that the features of {@code vegetation}, the chunk generator's vegetation step in its order, grow in a
	 * world of {@code scale}, or {@code null} if they grow none. Without a {@code level}, a feature that needs the
	 * world's grows nothing.
	 */
	static @Nullable FarTrees of(@Nullable ServerLevel level, ChunkGenerator generator, LevelHeightAccessor heights,
								 int seaLevel, long seed, List<PlacedFeature> vegetation, WorldScale scale) {
		List<Planting> plantings = new ArrayList<>();
		for (int index = 0; index < vegetation.size(); index++) {
			PlacedFeature placed = vegetation.get(index);
			if (!GreatTrees.plants(placed) && TreeCover.growsTrees(placed, scale)) {
				plantings.add(new Planting(placed, index));
			}
		}
		return plantings.isEmpty() ? null
			: new FarTrees(level, generator, heights, seaLevel, seed, plantings.toArray(Planting[]::new));
	}

	/**
	 * Grows the trees over the grid of {@code width}² columns {@code step} blocks apart whose corner block is
	 * {@code (minX, minZ)}, on {@code ground} made of {@code rock} under {@code soil}, among {@code biomes}.
	 */
	Crowns grow(int minX, int minZ, int width, int step, FarSketch.Ground ground, FarSketch.Soil soil,
				BlockState rock, BiomeResolver biomes) {
		Crowns crowns = new Crowns(minX, minZ, width, step);
		FarSketch sketch = new FarSketch(level, heights, seaLevel, seed, ground, soil, rock, biomes);
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		FeaturePlacer placer = new FeaturePlacer(sketch, generator);
		Long2ObjectOpenHashMap<BitSet> grown = new Long2ObjectOpenHashMap<>();
		BitSet planted = new BitSet(plantings.length);
		int originY = SectionPos.sectionToBlockCoord(heights.getMinSectionY());
		// A feature places blocks in its chunk and the eight around it.
		int fromX = SectionPos.blockToSectionCoord(crowns.block(minX, 0)) - 1;
		int fromZ = SectionPos.blockToSectionCoord(crowns.block(minZ, 0)) - 1;
		int toX = SectionPos.blockToSectionCoord(crowns.block(minX, width - 1)) + 1;
		int toZ = SectionPos.blockToSectionCoord(crowns.block(minZ, width - 1)) + 1;
		for (int chunkZ = fromZ; chunkZ <= toZ; chunkZ++) {
			for (int chunkX = fromX; chunkX <= toX; chunkX++) {
				// The features of the biomes of the chunk and of those around it, as the chunk generator gathers them.
				planted.clear();
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						planted.or(grown(sketch, chunkX + dx, chunkZ + dz, grown));
					}
				}
				if (planted.isEmpty()) {
					continue;
				}
				BlockPos origin = new BlockPos(SectionPos.sectionToBlockCoord(chunkX), originY,
					SectionPos.sectionToBlockCoord(chunkZ));
				long decoration = random.setDecorationSeed(seed, origin.getX(), origin.getZ());
				for (int p = planted.nextSetBit(0); p >= 0; p = planted.nextSetBit(p + 1)) {
					Planting planting = plantings[p];
					random.setFeatureSeed(decoration, planting.index, VEGETATION);
					try {
						placer.placeWithBiomeCheck(planting.placed, random, origin);
					} catch (RuntimeException e) {
						warn(planting, e);
						// It keeps the positions it had left to place.
						placer = new FeaturePlacer(sketch, generator);
					}
				}
				crowns.take(sketch);
				sketch.clear();
			}
		}
		return crowns;
	}

	/** The plantings the biomes at the surface of chunk {@code (chunkX, chunkZ)} grow, kept in {@code grown}. */
	private BitSet grown(FarSketch sketch, int chunkX, int chunkZ, Long2ObjectMap<BitSet> grown) {
		long key = ChunkPos.pack(chunkX, chunkZ);
		BitSet bits = grown.get(key);
		if (bits == null) {
			bits = new BitSet(plantings.length);
			for (int quartZ = QuartPos.fromSection(chunkZ); quartZ < QuartPos.fromSection(chunkZ + 1); quartZ++) {
				for (int quartX = QuartPos.fromSection(chunkX); quartX < QuartPos.fromSection(chunkX + 1); quartX++) {
					// Around the middle of the quart's top block, where the trees take root.
					int quartY = QuartPos.fromBlock(sketch.land(QuartPos.toBlock(quartX) + 2,
						QuartPos.toBlock(quartZ) + 2).ground());
					for (int y = quartY - 1; y <= quartY + 1; y++) {
						bits.or(held(sketch.getNoiseBiome(quartX, y, quartZ)));
					}
				}
			}
			grown.put(key, bits);
		}
		return bits;
	}

	/** The plantings a biome grows, as the chunk generator finds its features. */
	private BitSet held(Holder<Biome> biome) {
		return held.computeIfAbsent(biome, holder -> {
			BitSet bits = new BitSet(plantings.length);
			List<HolderSet<PlacedFeature>> steps = generator.getBiomeGenerationSettings(holder).features();
			if (steps.size() > VEGETATION) {
				for (Holder<PlacedFeature> placed : steps.get(VEGETATION)) {
					int place = places.getInt(placed.value());
					if (place >= 0) {
						bits.set(place);
					}
				}
			}
			return bits;
		});
	}

	private void warn(Planting planting, RuntimeException e) {
		// The world closed under the grid, and its terrain with it: the far terrain drops the grid.
		if (level != null && !level.getServer().isRunning()) {
			throw e;
		}
		if (failed.add(planting.placed)) {
			Object name = level == null ? planting.placed
				: level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getKey(planting.placed);
			LOGGER.warn("The far terrain leaves out the trees of {}, which failed to grow", name, e);
		}
	}

	/**
	 * The trees grown over the columns of a grid, column by column: the lowest and highest leaf and log, as heights
	 * above the top block of the ground ({@code 1} right above it), and the width of the trunk that stands in the
	 * column, if one does.
	 */
	static final class Crowns {
		private final int minX;
		private final int minZ;
		private final int width;
		private final int step;
		private final int[] leafLow;
		private final int[] leafHigh;
		/** The highest leaf. */
		private final @Nullable BlockState[] leaves;
		private final int[] logLow;
		private final int[] logHigh;
		/** The lowest log. */
		private final @Nullable BlockState[] logs;
		/** Blocks across the trunk standing on the ground, or {@code 0}. */
		private final int[] trunks;

		Crowns(int minX, int minZ, int width, int step) {
			this.minX = minX;
			this.minZ = minZ;
			this.width = width;
			this.step = step;
			int columns = width * width;
			leafLow = new int[columns];
			leafHigh = new int[columns];
			logLow = new int[columns];
			logHigh = new int[columns];
			Arrays.fill(leafLow, Integer.MAX_VALUE);
			Arrays.fill(leafHigh, Integer.MIN_VALUE);
			Arrays.fill(logLow, Integer.MAX_VALUE);
			Arrays.fill(logHigh, Integer.MIN_VALUE);
			leaves = new BlockState[columns];
			logs = new BlockState[columns];
			trunks = new int[columns];
		}

		/** Block X or Z of column {@code index} of the grid, from its corner {@code min}. */
		int block(int min, int index) {
			return min + index * step + step / 2;
		}

		/** The column of the grid at {@code offset} blocks from its corner, or {@code -1} if none is. */
		private int index(int offset) {
			int fromFirst = offset - step / 2;
			if (fromFirst < 0 || fromFirst % step != 0) {
				return -1;
			}
			int index = fromFirst / step;
			return index < width ? index : -1;
		}

		/** Takes in the leaves and logs placed in {@code sketch} over the grid's columns. */
		void take(FarSketch sketch) {
			Long2ObjectMap<BlockState> blocks = sketch.blocks();
			for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
				BlockState state = entry.getValue();
				boolean leaf = state.is(BlockTags.LEAVES);
				if (!leaf && !state.is(BlockTags.LOGS)) {
					continue;
				}
				long pos = entry.getLongKey();
				int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
				int a = index(x - minX);
				int b = index(z - minZ);
				if (a < 0 || b < 0) {
					continue;
				}
				int height = y - sketch.land(x, z).ground();
				if (height <= 0) {
					continue;
				}
				int k = b * width + a;
				if (leaf) {
					leafLow[k] = Math.min(leafLow[k], height);
					if (height > leafHigh[k]) {
						leafHigh[k] = height;
						leaves[k] = state;
					}
				} else {
					logHigh[k] = Math.max(logHigh[k], height);
					if (height < logLow[k]) {
						logLow[k] = height;
						logs[k] = state;
					}
					if (height == 1) {
						trunks[k] = Math.max(trunks[k], trunkWidth(blocks, x, y, z));
					}
				}
			}
		}

		/** 2 if a square of four logs takes in block {@code (x, y, z)}, 1 if not. */
		private static int trunkWidth(Long2ObjectMap<BlockState> blocks, int x, int y, int z) {
			for (int dz = -1; dz <= 0; dz++) {
				for (int dx = -1; dx <= 0; dx++) {
					if (log(blocks, x + dx, y, z + dz) && log(blocks, x + dx + 1, y, z + dz)
						&& log(blocks, x + dx, y, z + dz + 1) && log(blocks, x + dx + 1, y, z + dz + 1)) {
						return 2;
					}
				}
			}
			return 1;
		}

		private static boolean log(Long2ObjectMap<BlockState> blocks, int x, int y, int z) {
			BlockState state = blocks.get(BlockPos.asLong(x, y, z));
			return state != null && state.is(BlockTags.LOGS);
		}

		/**
		 * Fills {@code out} with the crown over column {@code (a, b)} of the grid and tells whether there is one. A
		 * trunk shows only in columns no wider than it, and so do logs with no leaf over them, as a fallen tree's.
		 */
		boolean crown(int a, int b, TreeCover.Crown out) {
			int k = b * width + a;
			BlockState leaf = leaves[k];
			BlockState log = logs[k];
			if (leaf != null) {
				out.leaves = leaf;
				out.low = leafLow[k];
				out.high = leafHigh[k];
				out.holdsTrunk = log != null && trunks[k] >= step;
				if (log != null) {
					out.trunk = log;
				}
				return true;
			}
			if (log != null && step <= Math.max(1, trunks[k])) {
				out.leaves = log;
				out.trunk = log;
				out.low = logLow[k];
				out.high = logHigh[k];
				out.holdsTrunk = false;
				return true;
			}
			return false;
		}
	}
}
