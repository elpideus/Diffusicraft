package io.github.elpideus.diffusicraft.worldgen.lod;

import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.OverlayFeature;
import net.minecraft.world.level.levelgen.feature.RandomBooleanSelectorFeature;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.SequenceFeature;
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.WeightedRandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomChancePlacement;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The trees of one biome as a far view sees them: how many grow, where, and the shapes of their crowns, worked out
 * from the biome's vegetation features without placing any in the world. {@link LodSampler} draws the canopy of its
 * columns from it.
 *
 * <p>The number follows each placed feature of the vegetation step down to its trees. The placement modifiers give the
 * expected number per chunk: counts averaged over many draws, rarity and chance filters as probabilities, and the other
 * filters as always passing. Selectors split that number between their branches by their odds.
 *
 * <p>The shapes come from each tree's own trunk and foliage placers, run many times on an empty stand-in for the
 * world, which records the logs and leaves they place: a crown is the highest and lowest leaf of each column around
 * the trunk. A tree whose placers need more of the world than the stand-in gives is drawn as a disc as wide as its
 * foliage radius instead. Biome mods often grow trees with features of their own rather than {@link TreeFeature}s:
 * those are placed whole on the stand-in, standing on grass, and the ones that place leaves are trees, of their most
 * common leaves and logs. A mod's feature that fails on the stand-in is not drawn.
 *
 * <p>The trunks are scattered at random, as the world scatters them: each square cell of a grid holds a number of
 * trees drawn from the Poisson distribution of the biome's density, each somewhere in its cell. Spread more evenly,
 * the crowns would overlap less than the world's and cover more of the ground. A tree does not grow under an earlier
 * tree's crown, as in the world (see {@link #shaded}). A column is under the highest crown that reaches it, so the
 * nearest levels of detail draw whole crowns and the farther ones, which take one column from a wide square, are under
 * a canopy as often as the ground is.
 *
 * <p>Giant trees, Diffusicraft's and other mods' trees whose crowns reach farther than {@value #GIANT_REACH} blocks,
 * are scattered on a grid of their own, with cells as wide as their own share of the ground: on the small trees' grid
 * each column would look over hundreds of cells for them. As in the world, which places them after the small trees
 * and grows them through their crowns, the small trees do not shade them, nor they the small trees; only another giant
 * keeps one from growing. Their trunks show in the farther levels of detail too, as far as they are wide.
 * Diffusicraft's giants are drawn at the height they grow to in a world of the cover's size.
 */
final class TreeCover {
	private static final Logger LOGGER = LoggerFactory.getLogger(TreeCover.class);
	private static final TreeCover NONE = new TreeCover(new Kind[0], new double[0], 1, new double[0], 0, null);
	/** Draws that average a random count, and trees drawn of each kind. */
	private static final int DRAWS = 64;
	private static final double CHUNK_AREA = 16 * 16;
	/** Origins of the count draws spread over this many blocks, for counts that follow a noise. */
	private static final int ORIGIN_SPREAD = 1 << 16;
	/** Nesting of features followed at most, in case a pack makes a cycle. */
	private static final int MAX_DEPTH = 16;
	/** Expected trees per chunk below which a biome has none worth drawing. */
	private static final double MIN_TREES = 1e-3;
	/** Widest crown recorded, in blocks from the trunk. */
	private static final int MAX_REACH = 24;
	/** Reach of the widest crown of a small tree, in blocks: a kind with a wider one is a giant. */
	private static final int GIANT_REACH = 12;
	/** Mixed into the seed for the giants' grid, so that its cells fall elsewhere than the small trees'. */
	private static final long GIANTS = 0x6A09E667F3BCC909L;
	/** Most trees in a cell, which holds one on average. */
	private static final int MAX_PER_CELL = 8;

	private final Kind[] kinds;
	/** Cumulative share of the trees, by number. */
	private final double[] shares;
	/** Side of the square cells, which hold about one tree each, in blocks. */
	private final int cell;
	/** Cumulative chance that a cell holds at most 0, 1, 2... trees. */
	private final double[] perCell;
	/** Farthest any crown reaches from its trunk. */
	private final int reach;
	/** The giant trees, on a grid of their own, or {@code null} if the biome grows none. */
	private final @Nullable TreeCover giants;

	/** One kind of tree, with {@value #DRAWS} draws of its shape. */
	private record Kind(BlockState leaves, BlockState trunk, Shape[] shapes) {
	}

	/**
	 * One tree, column by column around its trunk. For each column of the square {@code 2 * reach + 1} blocks wide: the
	 * lowest and highest leaf, as heights above the block the tree stands on ({@code 1} right above it), or
	 * {@link #NO_LEAVES}, and whether the trunk rises there. The trunk is about {@code trunkWidth} blocks across.
	 */
	private record Shape(int reach, short[] low, short[] high, boolean[] trunk, int trunkWidth) {
		static final short NO_LEAVES = Short.MIN_VALUE;

		/** Index of the column {@code (dx, dz)} from the trunk, or {@code -1} beyond the crown's reach. */
		int index(int dx, int dz) {
			return Math.abs(dx) > reach || Math.abs(dz) > reach ? -1 : (dz + reach) * (2 * reach + 1) + dx + reach;
		}
	}

	/** The crown over one column, as {@link #crown} finds it. */
	static final class Crown {
		BlockState leaves;
		BlockState trunk;
		/** Height of the lowest leaf above the ground's top block: 1 right above it. */
		int low;
		/** Height of the highest leaf above the ground's top block. */
		int high;
		/** Whether the trunk rises in the column, under the leaves. */
		boolean holdsTrunk;
		private final Tree tree = new Tree();
		private final Tree earlier = new Tree();
		private final Tree before = new Tree();
	}

	/** One tree of a cell, as {@link #draw} fills it. */
	private static final class Tree {
		Kind kind;
		Shape shape;
		/** The trunk's block. */
		int x;
		int z;
		/** When the world would place it, as a share of the trees around it: earlier trees come first. */
		double order;
	}

	private TreeCover(Kind[] kinds, double[] shares, int cell, double[] perCell, int reach,
					  @Nullable TreeCover giants) {
		this.kinds = kinds;
		this.shares = shares;
		this.cell = cell;
		this.perCell = perCell;
		this.reach = reach;
		this.giants = giants;
	}

	/**
	 * Fills {@code out} with the highest crown over the column at block {@code (x, z)} and tells whether there is one.
	 * The column stands for a square of {@code step} blocks; a trunk shows only in columns no wider than it.
	 */
	boolean crown(long seed, int x, int z, int step, Crown out) {
		boolean found = giants != null && giants.crown(seed ^ GIANTS, x, z, step, out);
		if (kinds.length == 0) {
			return found;
		}
		Tree tree = out.tree;
		int lastX = Math.floorDiv(x + reach, cell);
		int lastZ = Math.floorDiv(z + reach, cell);
		for (int cellZ = Math.floorDiv(z - reach, cell); cellZ <= lastZ; cellZ++) {
			for (int cellX = Math.floorDiv(x - reach, cell); cellX <= lastX; cellX++) {
				long hash = cellHash(seed, cellX, cellZ);
				int trees = count(unit(hash));
				for (int t = 1; t <= trees; t++) {
					draw(hash, t, cellX, cellZ, tree);
					int index = tree.shape.index(x - tree.x, z - tree.z);
					if (index < 0 || tree.shape.high[index] == Shape.NO_LEAVES
						|| found && tree.shape.high[index] <= out.high
						|| shaded(seed, tree, out.earlier, out.before)) {
						continue;
					}
					found = true;
					out.leaves = tree.kind.leaves;
					out.trunk = tree.kind.trunk;
					out.low = tree.shape.low[index];
					out.high = tree.shape.high[index];
					out.holdsTrunk = tree.shape.trunk[index] && step <= tree.shape.trunkWidth;
				}
			}
		}
		return found;
	}

	/**
	 * Whether an earlier tree stands over {@code tree}'s trunk, which keeps it from growing: the world places trees one
	 * after another on the highest block, and a sapling does not grow on leaves. With {@code before}, only an earlier
	 * tree that grew itself counts, which it does unless a tree before it stands over its own trunk. Following the
	 * chain that one step further gives a canopy within a few percent of the world's; further steps change it little.
	 */
	private boolean shaded(long seed, Tree tree, Tree earlier, @Nullable Tree before) {
		int lastX = Math.floorDiv(tree.x + reach, cell);
		int lastZ = Math.floorDiv(tree.z + reach, cell);
		for (int cellZ = Math.floorDiv(tree.z - reach, cell); cellZ <= lastZ; cellZ++) {
			for (int cellX = Math.floorDiv(tree.x - reach, cell); cellX <= lastX; cellX++) {
				long hash = cellHash(seed, cellX, cellZ);
				int trees = count(unit(hash));
				for (int t = 1; t <= trees; t++) {
					draw(hash, t, cellX, cellZ, earlier);
					if (earlier.order >= tree.order) {
						continue;
					}
					int index = earlier.shape.index(tree.x - earlier.x, tree.z - earlier.z);
					if (index >= 0 && (earlier.shape.high[index] != Shape.NO_LEAVES || earlier.shape.trunk[index])
						&& (before == null || !shaded(seed, earlier, before, null))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static long cellHash(long seed, int cellX, int cellZ) {
		return mix(seed ^ cellX * 0x9E3779B97F4A7C15L ^ cellZ * 0xC2B2AE3D27D4EB4FL);
	}

	/** Fills {@code out} with tree {@code t} of a cell, counted from 1. */
	private void draw(long cellHash, int t, int cellX, int cellZ, Tree out) {
		long tree = mix(cellHash + t * 0x632BE59BD9B4E019L);
		out.kind = kinds[pick(mix(tree + 1))];
		out.shape = out.kind.shapes[(int) Long.remainderUnsigned(mix(tree + 2), DRAWS)];
		out.x = cellX * cell + (int) Long.remainderUnsigned(mix(tree + 3), cell);
		out.z = cellZ * cell + (int) Long.remainderUnsigned(mix(tree + 4), cell);
		out.order = unit(mix(tree + 5));
	}

	/** Number of trees in a cell, for a uniform {@code u} in [0, 1). */
	private int count(double u) {
		for (int n = 0; n < perCell.length; n++) {
			if (u < perCell[n]) {
				return n;
			}
		}
		return perCell.length;
	}

	private int pick(long hash) {
		double u = unit(hash) * shares[shares.length - 1];
		for (int i = 0; i < shares.length - 1; i++) {
			if (u < shares[i]) {
				return i;
			}
		}
		return shares.length - 1;
	}

	private static double unit(long hash) {
		return (hash >>> 11) * 0x1.0p-53;
	}

	/** SplitMix64's finaliser. */
	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/**
	 * The tree cover of a biome in a world of {@code scale}, which is empty if it grows no trees that can be seen.
	 */
	static TreeCover of(Holder<Biome> biome, WorldScale scale) {
		List<HolderSet<PlacedFeature>> steps = biome.value().getGenerationSettings().features();
		int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
		if (steps.size() <= step) {
			return NONE;
		}
		RandomSource random = RandomSource.create(0x7EE5L);
		// In the order of the biome's features, so that each session draws the same trees.
		Map<Feature, Double> counts = new LinkedHashMap<>();
		for (Holder<PlacedFeature> placed : steps.get(step)) {
			try {
				collect(placed.value(), 1, counts, random, 0);
			} catch (RuntimeException e) {
				LOGGER.debug("Could not follow {} to its trees", placed, e);
			}
		}

		Map<Kind, Double> small = new LinkedHashMap<>();
		Map<Kind, Double> giant = new LinkedHashMap<>();
		for (Map.Entry<Feature, Double> entry : counts.entrySet()) {
			Kind kind = kind(entry.getKey(), random, scale);
			if (kind != null) {
				boolean wide = entry.getKey() instanceof GiantTreeFeature
					|| Arrays.stream(kind.shapes).anyMatch(shape -> shape.reach > GIANT_REACH);
				(wide ? giant : small).merge(kind, entry.getValue(), Double::sum);
			}
		}
		TreeCover giants = layer(biome, giant, null);
		TreeCover cover = layer(biome, small, giants);
		return cover != null ? cover : giants != null ? giants : NONE;
	}

	/**
	 * The trees of {@code counts}, by their number per chunk, scattered on one grid, or {@code null} if they are too few
	 * to draw.
	 */
	private static @Nullable TreeCover layer(Holder<Biome> biome, Map<Kind, Double> counts,
											 @Nullable TreeCover giants) {
		Kind[] kinds = counts.keySet().toArray(Kind[]::new);
		double[] shares = new double[kinds.length];
		double total = 0;
		int reach = 0;
		for (int i = 0; i < kinds.length; i++) {
			total += counts.get(kinds[i]);
			shares[i] = total;
			for (Shape shape : kinds[i].shapes) {
				reach = Math.max(reach, shape.reach);
			}
		}
		if (total < MIN_TREES) {
			return giants;
		}
		// Cells as wide as one tree's share of the ground, so that they hold one on average.
		double density = total / CHUNK_AREA;
		int cell = Math.max(1, (int) (1 / Math.sqrt(density)));
		double mean = density * cell * cell;
		double[] perCell = new double[MAX_PER_CELL];
		double chance = Math.exp(-mean);
		double cumulative = 0;
		for (int n = 0; n < MAX_PER_CELL; n++) {
			cumulative += chance;
			perCell[n] = cumulative;
			chance *= mean / (n + 1);
		}
		LOGGER.debug("{}: {} trees per chunk of {} kinds, one to a cell of {} blocks", biome.getRegisteredName(),
			total, kinds.length, cell);
		return new TreeCover(kinds, shares, cell, perCell, reach, giants);
	}

	/** Adds the expected number per chunk of each tree that {@code placed} grows, times {@code weight}. */
	private static void collect(PlacedFeature placed, double weight, Map<Feature, Double> counts,
								RandomSource random, int depth) {
		double number = weight * expectedCount(placed.placement(), random);
		if (number > 0 && depth < MAX_DEPTH) {
			collect(placed.feature().value(), number, counts, random, depth + 1);
		}
	}

	@SuppressWarnings("deprecation") // RandomSelectorFeature still takes WeightedPlacedFeatures.
	private static void collect(Feature feature, double weight, Map<Feature, Double> counts, RandomSource random,
								int depth) {
		switch (feature) {
			case TreeFeature tree -> counts.merge(tree, weight, Double::sum);
			case RandomSelectorFeature selector -> {
				// The first option whose chance comes up is placed, and the default if none does.
				double remaining = weight;
				for (var option : selector.features()) {
					collect(option.feature().value(), remaining * option.chance(), counts, random, depth);
					remaining *= 1 - option.chance();
				}
				collect(selector.defaultFeature().value(), remaining, counts, random, depth);
			}
			case SimpleRandomSelectorFeature(HolderSet<PlacedFeature> features) ->
				features.forEach(option -> collect(option.value(), weight / features.size(), counts, random, depth));
			case WeightedRandomSelectorFeature selector -> {
				List<Weighted<Holder<PlacedFeature>>> options = selector.features().unwrap();
				double total = options.stream().mapToInt(Weighted::weight).sum();
				for (Weighted<Holder<PlacedFeature>> option : options) {
					collect(option.value().value(), weight * option.weight() / total, counts, random, depth);
				}
			}
			case RandomBooleanSelectorFeature(Holder<PlacedFeature> first, Holder<PlacedFeature> second) -> {
				collect(first.value(), weight / 2, counts, random, depth);
				collect(second.value(), weight / 2, counts, random, depth);
			}
			case SequenceFeature(HolderSet<PlacedFeature> features) ->
				features.forEach(option -> collect(option.value(), weight, counts, random, depth));
			case OverlayFeature(HolderSet<PlacedFeature> features) ->
				features.forEach(option -> collect(option.value(), weight, counts, random, depth));
			default -> {
				// Another feature: its trees, if it names any, share it with whatever else it may place. A mod's own
				// feature that names none may grow trees itself, which kind() finds out.
				List<Holder<Feature>> inner = feature.getSubFeatures().toList();
				for (Holder<Feature> option : inner) {
					if (option.value() instanceof TreeFeature tree) {
						counts.merge(tree, weight / inner.size(), Double::sum);
					}
				}
				if (inner.isEmpty() && !feature.getClass().getName().startsWith("net.minecraft.")) {
					counts.merge(feature, weight, Double::sum);
				}
			}
		}
	}

	/** Expected number of placements per origin, for a feature placed once per chunk. */
	private static double expectedCount(List<PlacementModifier> placement, RandomSource random) {
		double expected = 1;
		for (PlacementModifier modifier : placement) {
			switch (modifier) {
				case RepeatingPlacement repeating -> {
					double sum = 0;
					for (int i = 0; i < DRAWS; i++) {
						BlockPos origin = new BlockPos(random.nextInt(ORIGIN_SPREAD) - ORIGIN_SPREAD / 2, 64,
							random.nextInt(ORIGIN_SPREAD) - ORIGIN_SPREAD / 2);
						sum += repeating.count(random, origin);
					}
					expected *= sum / DRAWS;
				}
				case CountOnEveryLayerPlacement(IntProvider count) -> expected *= mean(count, random);
				case RarityFilter(int chance) -> expected /= Math.max(1, chance);
				case RandomChancePlacement(float chance) -> expected *= chance;
				default -> {
				}
			}
		}
		return expected;
	}

	private static double mean(IntProvider provider, RandomSource random) {
		double sum = 0;
		for (int i = 0; i < DRAWS; i++) {
			sum += provider.sample(random);
		}
		return sum / DRAWS;
	}

	/**
	 * Draws {@value #DRAWS} trees of a feature in a world of {@code scale}, or returns {@code null} if it grows none
	 * that can be drawn.
	 */
	private static @Nullable Kind kind(Feature feature, RandomSource random, WorldScale scale) {
		return feature instanceof TreeFeature tree ? kind(tree, random) : placed(feature, random, scale);
	}

	/** Draws {@value #DRAWS} trees of a kind: by its placers if they grow on the stand-in, as discs if not. */
	private static Kind kind(TreeFeature tree, RandomSource random) {
		BlockState leaves = state(tree.foliageProvider(), random, Blocks.OAK_LEAVES);
		BlockState trunk = state(tree.trunkProvider(), random, Blocks.OAK_LOG);
		Shape[] shapes = new Shape[DRAWS];
		try {
			for (int i = 0; i < DRAWS; i++) {
				shapes[i] = new StandIn(StandIn.AIR).grow(tree, random);
			}
		} catch (RuntimeException e) {
			LOGGER.debug("Could not grow {} on a stand-in; drawing discs", tree, e);
			for (int i = 0; i < DRAWS; i++) {
				shapes[i] = disc(tree, random);
			}
		}
		return new Kind(leaves, trunk, shapes);
	}

	/**
	 * Places another mod's feature {@value #DRAWS} times on the stand-in, standing on grass, and draws its trees if it
	 * places leaves. A draw that places none, as a tree that fails now and then does, grows nothing. The leaves and logs
	 * are those it places most.
	 */
	private static @Nullable Kind placed(Feature feature, RandomSource random, WorldScale scale) {
		Shape[] shapes = new Shape[DRAWS];
		Map<Block, Integer> leaves = new HashMap<>();
		Map<Block, Integer> logs = new HashMap<>();
		try {
			for (int i = 0; i < DRAWS; i++) {
				StandIn standIn = new StandIn(Blocks.GRASS_BLOCK.defaultBlockState());
				shapes[i] = standIn.place(feature, random, scale);
				for (BlockState state : standIn.blocks.values()) {
					if (state.getBlock() instanceof LeavesBlock) {
						leaves.merge(state.getBlock(), 1, Integer::sum);
					} else if (state.is(BlockTags.LOGS)) {
						logs.merge(state.getBlock(), 1, Integer::sum);
					}
				}
			}
		} catch (RuntimeException e) {
			LOGGER.debug("Could not place {} on a stand-in; its trees are not drawn", feature, e);
			return null;
		}
		if (leaves.isEmpty()) {
			return null;
		}
		return new Kind(most(leaves).defaultBlockState(),
			logs.isEmpty() ? Blocks.OAK_LOG.defaultBlockState() : most(logs).defaultBlockState(), shapes);
	}

	private static Block most(Map<Block, Integer> counts) {
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
	}

	/**
	 * A tree as {@link TreeFeature} sizes it, without running its placers: a trunk and, on top, a stack of discs of
	 * leaves as wide as the foliage radius.
	 */
	private static Shape disc(TreeFeature tree, RandomSource random) {
		int height = tree.trunkPlacer().getTreeHeight(random);
		int foliage = tree.foliagePlacer().foliageHeight(random, height, tree);
		int radius = Math.clamp(tree.foliagePlacer().foliageRadius(random, height - foliage), 0, MAX_REACH);
		int side = 2 * radius + 1;
		short[] low = new short[side * side];
		short[] high = new short[side * side];
		boolean[] trunk = new boolean[side * side];
		double limit = (radius + 0.5) * (radius + 0.5);
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int k = (dz + radius) * side + dx + radius;
				boolean inside = dx * dx + dz * dz <= limit;
				// The trunk rises from the block above the ground, and the top leaf sits right above the trunk.
				low[k] = inside ? (short) Math.max(1, height + 1 - foliage) : Shape.NO_LEAVES;
				high[k] = inside ? (short) (height + 1) : Shape.NO_LEAVES;
			}
		}
		trunk[radius * side + radius] = true;
		return new Shape(radius, low, high, trunk, 1);
	}

	/** What a provider gives without a level, or {@code fallback} if it needs one. */
	private static BlockState state(Holder<BlockStateProvider> provider, RandomSource random, Block fallback) {
		try {
			BlockState state = provider.value().getState(null, random, BlockPos.ZERO);
			if (state != null) {
				return state;
			}
		} catch (RuntimeException ignored) {
			// Providers that read the level.
		}
		return fallback.defaultBlockState();
	}

	/**
	 * An empty world for a tree's placers, or a whole feature, to grow one tree in, which records what they place. It
	 * answers what trees ask of the world, which block is where and how high it reaches, and throws on anything else.
	 * Below the origin is {@code ground}, and air above.
	 */
	private static final class StandIn implements InvocationHandler, FoliagePlacer.FoliageSetter {
		private static final BlockState AIR = Blocks.AIR.defaultBlockState();
		private static final int MIN_Y = -64;
		private static final int HEIGHT = 512;
		private static final Shape EMPTY = new Shape(0, new short[]{Shape.NO_LEAVES}, new short[]{Shape.NO_LEAVES},
			new boolean[1], 1);
		/**
		 * Where a whole feature is placed: in the middle of its chunk, where a feature that keeps to the chunks around
		 * its own has the most room.
		 */
		private static final BlockPos ORIGIN = new BlockPos(8, 0, 8);

		private final BlockState ground;
		private final Map<BlockPos, BlockState> blocks = new HashMap<>();
		private final Set<BlockPos> leaves = new HashSet<>();
		private final WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
			new Class<?>[]{WorldGenLevel.class}, this);
		private @Nullable RandomSource random;
		/** Where the tree stands. */
		private BlockPos origin = BlockPos.ZERO;

		StandIn(BlockState ground) {
			this.ground = ground;
		}

		/** Grows one tree with its trunk on the origin, as {@link TreeFeature} does, without its roots. */
		Shape grow(TreeFeature tree, RandomSource random) {
			this.origin = BlockPos.ZERO;
			int height = tree.trunkPlacer().getTreeHeight(random);
			int foliage = tree.foliagePlacer().foliageHeight(random, height, tree);
			int radius = tree.foliagePlacer().foliageRadius(random, height - foliage);
			List<FoliagePlacer.FoliageAttachment> attachments = tree.trunkPlacer().placeTrunk(level,
				(pos, state) -> blocks.put(pos.immutable(), state), random, height, BlockPos.ZERO, tree);
			for (FoliagePlacer.FoliageAttachment attachment : attachments) {
				tree.foliagePlacer().createFoliage(level, this, random, tree, height, attachment, foliage, radius);
			}
			// Everything at the origin's height but the leaves is the trunk.
			return shape(state -> true);
		}

		/**
		 * Places a whole feature at the origin, on the ground, as the world places it on the highest block: a giant at
		 * its height in a world of {@code scale}, which the stand-in cannot tell it.
		 */
		Shape place(Feature feature, RandomSource random, WorldScale scale) {
			this.random = random;
			this.origin = ORIGIN;
			if (feature instanceof GiantTreeFeature giant) {
				giant.place(level, random, ORIGIN, scale);
			} else {
				feature.place(level, null, random, ORIGIN);
			}
			blocks.forEach((pos, state) -> {
				if (state.getBlock() instanceof LeavesBlock) {
					leaves.add(pos);
				}
			});
			return leaves.isEmpty() ? EMPTY : shape(state -> state.is(BlockTags.LOGS));
		}

		/** The crown of the leaves placed, with a trunk where {@code trunk} accepts a block at the origin's height. */
		private Shape shape(Predicate<BlockState> trunk) {
			int reach = 0;
			for (BlockPos pos : leaves) {
				reach = Math.max(reach, Math.max(Math.abs(pos.getX() - origin.getX()),
					Math.abs(pos.getZ() - origin.getZ())));
			}
			if (leaves.isEmpty() || reach > MAX_REACH) {
				throw new IllegalStateException("No crown, or one too wide: " + leaves.size() + " leaves");
			}
			int side = 2 * reach + 1;
			short[] low = new short[side * side];
			short[] high = new short[side * side];
			boolean[] trunks = new boolean[side * side];
			Arrays.fill(low, Shape.NO_LEAVES);
			Arrays.fill(high, Shape.NO_LEAVES);
			for (BlockPos pos : leaves) {
				int k = (pos.getZ() - origin.getZ() + reach) * side + pos.getX() - origin.getX() + reach;
				// The tree stands on the block below the origin.
				short y = (short) (pos.getY() + 1);
				low[k] = low[k] == Shape.NO_LEAVES ? y : (short) Math.min(low[k], y);
				high[k] = high[k] == Shape.NO_LEAVES ? y : (short) Math.max(high[k], y);
			}
			int columns = 0;
			for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
				BlockPos pos = entry.getKey();
				int dx = pos.getX() - origin.getX(), dz = pos.getZ() - origin.getZ();
				if (pos.getY() == 0 && Math.abs(dx) <= reach && Math.abs(dz) <= reach
					&& !leaves.contains(pos) && trunk.test(entry.getValue())) {
					trunks[(dz + reach) * side + dx + reach] = true;
					columns++;
				}
			}
			return new Shape(reach, low, high, trunks, Math.max(1, (int) Math.round(Math.sqrt(columns))));
		}

		private BlockState at(BlockPos pos) {
			BlockState state = blocks.get(pos);
			return state != null ? state : pos.getY() < 0 ? ground : AIR;
		}

		@Override
		public void set(BlockPos pos, BlockState state) {
			BlockPos key = pos.immutable();
			blocks.put(key, state);
			leaves.add(key);
		}

		@Override
		public boolean isSet(BlockPos pos) {
			return leaves.contains(pos);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			int arity = args == null ? 0 : args.length;
			switch (method.getName()) {
				case "isStateAtPosition":
					return ((Predicate<BlockState>) args[1]).test(at((BlockPos) args[0]));
				case "isFluidAtPosition":
					return ((Predicate<FluidState>) args[1]).test(at((BlockPos) args[0]).getFluidState());
				case "getBlockState":
					return at((BlockPos) args[0]);
				case "getFluidState":
					return at((BlockPos) args[0]).getFluidState();
				case "setBlock":
					blocks.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
					return true;
				case "getMinY":
					return MIN_Y;
				case "getMaxY":
					return MIN_Y + HEIGHT - 1;
				case "getHeight":
					if (arity == 0) {
						return HEIGHT;
					}
					if (arity == 3) {
						// The heightmaps of an empty world: the origin's height, on the ground.
						return 0;
					}
					break;
				case "getHeightmapPos":
					BlockPos pos = (BlockPos) args[1];
					return new BlockPos(pos.getX(), 0, pos.getZ());
				case "getRandom":
					if (random != null) {
						return random;
					}
					break;
				case "getBlockEntity":
					// Decorations such as beehives look for theirs; the stand-in keeps none.
					if (arity == 1) {
						return null;
					}
					break;
				case "scheduleTick":
					// Leaves and vines ask for a tick as their shapes settle; nothing runs in the stand-in.
					return null;
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				case "toString":
					return "TreeCover.StandIn";
				default:
					break;
			}
			if (method.isDefault()) {
				return InvocationHandler.invokeDefault(proxy, method, args);
			}
			throw new UnsupportedOperationException(method.getName());
		}
	}
}
