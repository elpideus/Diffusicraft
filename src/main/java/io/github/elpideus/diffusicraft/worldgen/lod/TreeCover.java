package io.github.elpideus.diffusicraft.worldgen.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
import java.util.IdentityHashMap;
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
 * foliage radius instead. Trees that are not {@link TreeFeature}s, such as those some biome mods place with features
 * of their own, are not seen.
 *
 * <p>The trunks are scattered at random, as the world scatters them: each square cell of a grid holds a number of
 * trees drawn from the Poisson distribution of the biome's density, each somewhere in its cell. Spread more evenly,
 * the crowns would overlap less than the world's and cover more of the ground. A tree does not grow under an earlier
 * tree's crown, as in the world (see {@link #shaded}). A column is under the highest crown that reaches it, so the
 * nearest levels of detail draw whole crowns and the farther ones, which take one column from a wide square, are under
 * a canopy as often as the ground is.
 */
final class TreeCover {
	private static final Logger LOGGER = LoggerFactory.getLogger(TreeCover.class);
	private static final TreeCover NONE = new TreeCover(new Kind[0], new double[0], 1, new double[0], 0);
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
	private static final int MAX_REACH = 16;
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

	/** One kind of tree, with {@value #DRAWS} draws of its shape. */
	private record Kind(BlockState leaves, BlockState trunk, Shape[] shapes) {
	}

	/**
	 * One tree, column by column around its trunk. For each column of the square {@code 2 * reach + 1} blocks wide: the
	 * lowest and highest leaf, as heights above the block the tree stands on ({@code 1} right above it), or
	 * {@link #NO_LEAVES}, and whether the trunk rises there.
	 */
	private record Shape(int reach, short[] low, short[] high, boolean[] trunk) {
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

	private TreeCover(Kind[] kinds, double[] shares, int cell, double[] perCell, int reach) {
		this.kinds = kinds;
		this.shares = shares;
		this.cell = cell;
		this.perCell = perCell;
		this.reach = reach;
	}

	/**
	 * Fills {@code out} with the highest crown over the column at block {@code (x, z)} and tells whether there is one.
	 * The column stands for a square of {@code step} blocks; a trunk shows only in columns of one block.
	 */
	boolean crown(long seed, int x, int z, int step, Crown out) {
		if (kinds.length == 0) {
			return false;
		}
		boolean found = false;
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
					out.holdsTrunk = step == 1 && tree.shape.trunk[index];
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

	/** The tree cover of a biome, which is empty if it grows no trees that can be seen. */
	static TreeCover of(Holder<Biome> biome) {
		List<HolderSet<PlacedFeature>> steps = biome.value().getGenerationSettings().features();
		int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
		if (steps.size() <= step) {
			return NONE;
		}
		RandomSource random = RandomSource.create(0x7EE5L);
		Map<TreeFeature, Double> counts = new IdentityHashMap<>();
		for (Holder<PlacedFeature> placed : steps.get(step)) {
			try {
				collect(placed.value(), 1, counts, random, 0);
			} catch (RuntimeException e) {
				LOGGER.debug("Could not follow {} to its trees", placed, e);
			}
		}

		List<Kind> kinds = new ArrayList<>();
		double[] shares = new double[counts.size()];
		double total = 0;
		int reach = 0;
		for (Map.Entry<TreeFeature, Double> entry : counts.entrySet()) {
			Kind kind = kind(entry.getKey(), random);
			total += entry.getValue();
			shares[kinds.size()] = total;
			kinds.add(kind);
			for (Shape shape : kind.shapes) {
				reach = Math.max(reach, shape.reach);
			}
		}
		if (total < MIN_TREES) {
			return NONE;
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
			total, kinds.size(), cell);
		return new TreeCover(kinds.toArray(Kind[]::new), shares, cell, perCell, reach);
	}

	/** Adds the expected number per chunk of each tree that {@code placed} grows, times {@code weight}. */
	private static void collect(PlacedFeature placed, double weight, Map<TreeFeature, Double> counts,
								RandomSource random, int depth) {
		double number = weight * expectedCount(placed.placement(), random);
		if (number > 0 && depth < MAX_DEPTH) {
			collect(placed.feature().value(), number, counts, random, depth + 1);
		}
	}

	@SuppressWarnings("deprecation") // RandomSelectorFeature still takes WeightedPlacedFeatures.
	private static void collect(Feature feature, double weight, Map<TreeFeature, Double> counts, RandomSource random,
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
				// Another mod's feature: its trees, if it names any, share it with whatever else it may place.
				List<Holder<Feature>> inner = feature.getSubFeatures().toList();
				for (Holder<Feature> option : inner) {
					if (option.value() instanceof TreeFeature tree) {
						counts.merge(tree, weight / inner.size(), Double::sum);
					}
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

	/** Draws {@value #DRAWS} trees of a kind: by its placers if they grow on the stand-in, as discs if not. */
	private static Kind kind(TreeFeature tree, RandomSource random) {
		BlockState leaves = state(tree.foliageProvider(), random, Blocks.OAK_LEAVES);
		BlockState trunk = state(tree.trunkProvider(), random, Blocks.OAK_LOG);
		Shape[] shapes = new Shape[DRAWS];
		try {
			for (int i = 0; i < DRAWS; i++) {
				shapes[i] = new StandIn().grow(tree, random);
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
		return new Shape(radius, low, high, trunk);
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
	 * An empty world for a tree's placers to grow one tree in, which records what they place. It answers what the
	 * placers ask of the world, which block is where and how high it reaches, and throws on anything else.
	 */
	private static final class StandIn implements InvocationHandler, FoliagePlacer.FoliageSetter {
		private static final BlockState AIR = Blocks.AIR.defaultBlockState();
		private static final int MIN_Y = -64;
		private static final int HEIGHT = 512;

		private final Map<BlockPos, BlockState> blocks = new HashMap<>();
		private final Set<BlockPos> leaves = new HashSet<>();
		private final WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
			new Class<?>[]{WorldGenLevel.class}, this);

		/** Grows one tree with its trunk on the origin, as {@link TreeFeature} does, without its roots. */
		Shape grow(TreeFeature tree, RandomSource random) {
			int height = tree.trunkPlacer().getTreeHeight(random);
			int foliage = tree.foliagePlacer().foliageHeight(random, height, tree);
			int radius = tree.foliagePlacer().foliageRadius(random, height - foliage);
			List<FoliagePlacer.FoliageAttachment> attachments = tree.trunkPlacer().placeTrunk(level,
				(pos, state) -> blocks.put(pos.immutable(), state), random, height, BlockPos.ZERO, tree);
			for (FoliagePlacer.FoliageAttachment attachment : attachments) {
				tree.foliagePlacer().createFoliage(level, this, random, tree, height, attachment, foliage, radius);
			}
			return shape();
		}

		private Shape shape() {
			int reach = 0;
			for (BlockPos pos : leaves) {
				reach = Math.max(reach, Math.max(Math.abs(pos.getX()), Math.abs(pos.getZ())));
			}
			if (leaves.isEmpty() || reach > MAX_REACH) {
				throw new IllegalStateException("No crown, or one too wide: " + leaves.size() + " leaves");
			}
			int side = 2 * reach + 1;
			short[] low = new short[side * side];
			short[] high = new short[side * side];
			boolean[] trunk = new boolean[side * side];
			Arrays.fill(low, Shape.NO_LEAVES);
			Arrays.fill(high, Shape.NO_LEAVES);
			for (BlockPos pos : leaves) {
				int k = (pos.getZ() + reach) * side + pos.getX() + reach;
				// The tree stands on the block below the origin.
				short y = (short) (pos.getY() + 1);
				low[k] = low[k] == Shape.NO_LEAVES ? y : (short) Math.min(low[k], y);
				high[k] = high[k] == Shape.NO_LEAVES ? y : (short) Math.max(high[k], y);
			}
			for (BlockPos pos : blocks.keySet()) {
				if (pos.getY() == 0 && Math.abs(pos.getX()) <= reach && Math.abs(pos.getZ()) <= reach
					&& !leaves.contains(pos)) {
					trunk[(pos.getZ() + reach) * side + pos.getX() + reach] = true;
				}
			}
			return new Shape(reach, low, high, trunk);
		}

		private BlockState at(BlockPos pos) {
			return blocks.getOrDefault(pos, AIR);
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
					break;
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
