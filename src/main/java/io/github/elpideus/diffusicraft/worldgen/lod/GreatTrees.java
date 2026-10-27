package io.github.elpideus.diffusicraft.worldgen.lod;

import io.github.elpideus.diffusicraft.mixin.ChunkGeneratorAccessor;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.feature.DiffusicraftWorldFilter;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeFeature;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeStructure;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.OffsetPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomChancePlacement;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.SurfaceWaterDepthFilter;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The giant trees as a far view sees them: those that grow as structures ({@link GiantTreeStructure}), the ancient oaks
 * and the kapoks, and those that the biomes' features plant ({@link GiantTreeFeature}), the redwoods. {@link TreeCover}
 * scatters a biome's trees at random, which does for a canopy; but a giant stands out over it, and one drawn elsewhere
 * than the world grows it would vanish as the chunks load, and another stand up beside it. These stand where the world
 * grows them.
 *
 * <p>A structure set of giants places them in the chunks the world gives it, clear of the villages as the set keeps
 * them, and each chunk draws its sapling as the structure draws it: where in the chunk, how tall, and whether it grows
 * at all. The giant of the structure whose biomes hold the foot grows there if the far view's ground is dry under its
 * foot and level around it, as the structure asks of the world's ground.
 *
 * <p>A feature that plants a giant at most once in a chunk, by placement modifiers whose draws are known (see {@link
 * #plants}), is followed draw by draw, as the world's decoration draws it in each chunk: from the chunk's seed and the
 * feature's place among the features of its step, through the counts, the chances and the offsets of its placement, to
 * the height of the tree. The modifiers that look at the world look at the far view's ground and biomes instead: the
 * giant grows if the ground is dry under its foot and level around it, and its biome plants it. The world plants the
 * chunks in the order they load, and a giant does not grow under one planted before it; a far view cannot know that
 * order, so it lets the giant of the chunk that comes first in an order of its own keep the other from growing.
 *
 * <p>A far view's ground is not quite the world's, so now and then a giant is drawn that the world does not grow, or
 * the other way round. The crowns are those of a few trees grown of each kind, at heights spread over its range: each
 * giant has the crown of the one nearest its own height. Growing every giant a far view shows would take far too long.
 * So the giants stand in their places at their heights, but their crowns are not quite their own.
 *
 * <p>Thread-safe, as {@link LodSampler} needs: the trees are grown once, and each grid has a {@link Stand} of its own.
 */
final class GreatTrees {
	/** Trees grown of each structure, at heights spread over its range. */
	private static final int SHAPES = 16;
	/** Mixed into the seeds of the trees grown. */
	private static final long SHAPE_SEED = 0x3C6EF372FE94F82BL;
	/** The step of decoration whose features plant the trees. */
	private static final int VEGETATION = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();

	private final Spread[] spreads;
	private final Planting[] plantings;
	/** How near to and how far from its chunk's corner, along either axis, a planted giant's foot may lie. */
	private final int nearestFoot;
	private final int farthestFoot;
	private final long seed;
	private final WorldScale scale;
	/** The highest block of the world. */
	private final int maxY;

	/** Whether a structure set places a structure in a chunk. */
	@FunctionalInterface
	interface Chunks {
		boolean placed(int chunkX, int chunkZ);
	}

	/** A structure set of giants, and the farthest any of their crowns reaches. */
	private record Spread(RandomSpreadStructurePlacement placement, Chunks chunks, Kind[] kinds, int reach) {
	}

	/** A tree grown of a structure: its crown, and how far its foot reaches from its axis. */
	private record Grown(TreeCover.Shape shape, int footReach) {
	}

	/** A giant that grows, with its foot, the block over the ground, at {@code (x, base, z)}. */
	private record Giant(int x, int z, int base, TreeCover.Shape shape, BlockState leaves, BlockState wood) {
		/** Whether its crown or its trunk stands over the column at block {@code (x, z)}. */
		boolean covers(int x, int z) {
			int index = shape.index(x - this.x, z - this.z);
			return index >= 0 && shape.high()[index] != TreeCover.Shape.NO_LEAVES;
		}
	}

	/** One structure of a set, and the trees grown of it. */
	private static final class Kind {
		final GiantTreeStructure structure;
		/** The lowest and the highest it grows. */
		final int low;
		final int high;
		private final AtomicReferenceArray<Grown> grown = new AtomicReferenceArray<>(SHAPES);

		Kind(GiantTreeStructure structure, WorldScale scale) {
			this.structure = structure;
			this.low = structure.minHeight(scale);
			this.high = Math.max(low, structure.maxHeight(scale));
		}

		/** The tree grown of it nearest in height to {@code height}. */
		Grown nearest(int height) {
			int slot = slot(height, low, high);
			Grown tree = grown.get(slot);
			if (tree == null) {
				// Two grids may grow the same tree at once, and grow the same one.
				int grownHeight = slotHeight(slot, low, high);
				tree = grow(structure.reach(grownHeight), sink -> structure.grow(grownHeight, SHAPE_SEED + slot, sink));
				grown.compareAndSet(slot, null, tree);
			}
			return tree;
		}
	}

	/** A feature of the biomes that plants giants, and the trees grown of it. */
	private static final class Grove {
		final GiantTreeFeature feature;
		/** The lowest and the highest it grows. */
		final int low;
		final int high;
		/** The trees grown, by height and by how far they may reach. */
		private final AtomicReferenceArray<Grown> grown = new AtomicReferenceArray<>(
			SHAPES * (GiantTreeFeature.MAX_ROOM + 1));

		Grove(GiantTreeFeature feature, WorldScale scale) {
			this.feature = feature;
			this.low = feature.minHeight(scale);
			this.high = Math.max(low, feature.maxHeight(scale));
		}

		/** The tree grown of it nearest in height to {@code height}, and reaching at most {@code room} out. */
		Grown nearest(int height, int room) {
			int slot = slot(height, low, high);
			int key = slot * (GiantTreeFeature.MAX_ROOM + 1) + room;
			Grown tree = grown.get(key);
			if (tree == null) {
				int grownHeight = slotHeight(slot, low, high);
				tree = grow(room, sink -> feature.grow(grownHeight, room, SHAPE_SEED + slot, sink));
				grown.compareAndSet(key, null, tree);
			}
			return tree;
		}
	}

	/** A placed feature that plants giants of a grove, at most one in a chunk. */
	private static final class Planting {
		final PlacedFeature placed;
		/** Its place among the features of its step, which seeds its draws in each chunk. */
		final int index;
		final Grove grove;
		/** How near to and how far from its chunk's corner, along either axis, its foot may lie. */
		final int nearestFoot;
		final int farthestFoot;

		Planting(PlacedFeature placed, int index, Grove grove) {
			this.placed = placed;
			this.index = index;
			this.grove = grove;
			int nearest = 0, farthest = 0;
			for (PlacementModifier modifier : placed.placement()) {
				if (modifier instanceof InSquarePlacement) {
					farthest += 15;
				} else if (modifier instanceof OffsetPlacement offset) {
					nearest += Math.min(offset.x().minInclusive(), offset.z().minInclusive());
					farthest += Math.max(offset.x().maxInclusive(), offset.z().maxInclusive());
				}
			}
			this.nearestFoot = nearest;
			this.farthestFoot = farthest;
		}

		/**
		 * Draws where it plants a giant in the chunk whose corner is {@code corner}, as the placement modifiers draw it,
		 * or {@code null} if it plants none there. Those that look at the world draw nothing, and are left to the caller.
		 */
		@Nullable BlockPos draw(RandomSource random, BlockPos corner) {
			BlockPos[] at = {corner};
			for (PlacementModifier modifier : placed.placement()) {
				// None of these reads the context.
				if (modifier instanceof CountPlacement count) {
					if (count.count(random, at[0]) == 0) {
						return null;
					}
				} else if (modifier instanceof RarityFilter || modifier instanceof RandomChancePlacement) {
					if (!((PlacementFilter) modifier).shouldPlace(null, random, at[0])) {
						return null;
					}
				} else if (modifier instanceof OffsetPlacement || modifier instanceof InSquarePlacement) {
					modifier.modify(null, random, at[0], pos -> at[0] = pos);
				}
			}
			return at[0];
		}
	}

	/** The one of {@value #SHAPES} heights spread from {@code low} to {@code high} nearest {@code height}. */
	private static int slot(int height, int low, int high) {
		return high == low ? 0
			: (int) Math.round((Math.clamp(height, low, high) - low) * (SHAPES - 1.0) / (high - low));
	}

	private static int slotHeight(int slot, int low, int high) {
		return low + (int) Math.round((high - low) * slot / (SHAPES - 1.0));
	}

	private GreatTrees(Spread[] spreads, Planting[] plantings, long seed, WorldScale scale, int maxY) {
		this.spreads = spreads;
		this.plantings = plantings;
		this.nearestFoot = Arrays.stream(plantings).mapToInt(planting -> planting.nearestFoot).min().orElse(0);
		this.farthestFoot = Arrays.stream(plantings).mapToInt(planting -> planting.farthestFoot).max().orElse(0);
		this.seed = seed;
		this.scale = scale;
		this.maxY = maxY;
	}

	/**
	 * The giants of a world whose highest block is {@code maxY}, or {@code null} if neither its sets nor its biomes
	 * grow any.
	 */
	static @Nullable GreatTrees of(ChunkGenerator generator, ChunkGeneratorStructureState state, WorldScale scale,
								   int maxY) {
		List<StructureSet> sets = state.possibleStructureSets().stream().map(Holder::value).toList();
		List<FeatureSorter.StepFeatureData> steps = ((ChunkGeneratorAccessor) generator).diffusicraft$featuresPerStep()
			.get();
		return of(sets, set -> (x, z) -> set.placement().isStructureChunk(state, x, z),
			steps.size() > VEGETATION ? steps.get(VEGETATION).features() : List.of(), state.getLevelSeed(), scale, maxY);
	}

	/**
	 * The giants of the structure sets {@code sets} that spread giants alone, which place them in the chunks that
	 * {@code chunks} gives each, and of the features of {@code vegetation}, the step of decoration that plants trees, in
	 * the order the world's generator sorted them; or {@code null} if there are none.
	 */
	static @Nullable GreatTrees of(List<StructureSet> sets, Function<StructureSet, Chunks> chunks,
								   List<PlacedFeature> vegetation, long seed, WorldScale scale, int maxY) {
		List<Spread> spreads = new ArrayList<>();
		for (StructureSet set : sets) {
			if (!(set.placement() instanceof RandomSpreadStructurePlacement placement) || set.structures().isEmpty()) {
				continue;
			}
			List<Kind> kinds = new ArrayList<>();
			int reach = 0;
			for (StructureSet.StructureSelectionEntry entry : set.structures()) {
				if (entry.structure().value() instanceof GiantTreeStructure structure) {
					Kind kind = new Kind(structure, scale);
					kinds.add(kind);
					reach = Math.max(reach, structure.reach(kind.high));
				}
			}
			// A set that places other structures as well may place one of those where a giant would grow.
			if (kinds.size() == set.structures().size()) {
				spreads.add(new Spread(placement, chunks.apply(set), kinds.toArray(Kind[]::new), reach));
			}
		}
		List<Planting> plantings = new ArrayList<>();
		// Placed features of one feature share the trees grown of it.
		Map<GiantTreeFeature, Grove> groves = new HashMap<>();
		for (int index = 0; index < vegetation.size(); index++) {
			PlacedFeature placed = vegetation.get(index);
			if (plants(placed)) {
				plantings.add(new Planting(placed, index, groves.computeIfAbsent(
					(GiantTreeFeature) placed.feature().value(), feature -> new Grove(feature, scale))));
			}
		}
		return spreads.isEmpty() && plantings.isEmpty() ? null : new GreatTrees(spreads.toArray(Spread[]::new),
			plantings.toArray(Planting[]::new), seed, scale, maxY);
	}

	/**
	 * Whether a far view follows a placed feature to the giants it plants, as {@link GreatTrees} does, rather than
	 * scatter them as {@link TreeCover} does: whether it plants a {@link GiantTreeFeature} at most once in a chunk,
	 * where its biome holds it, by modifiers that draw what a far view can draw too.
	 */
	static boolean plants(PlacedFeature placed) {
		if (!(placed.feature().value() instanceof GiantTreeFeature)) {
			return false;
		}
		boolean biome = false;
		for (PlacementModifier modifier : placed.placement()) {
			if (modifier instanceof CountPlacement count ? count.count().maxInclusive() > 1
				: !(modifier instanceof RarityFilter || modifier instanceof RandomChancePlacement
					|| modifier instanceof OffsetPlacement || modifier instanceof InSquarePlacement
					|| modifier instanceof HeightmapPlacement || modifier instanceof SurfaceWaterDepthFilter
					|| modifier instanceof BiomeFilter || modifier instanceof BlockPredicateFilter
					|| modifier instanceof DiffusicraftWorldFilter)) {
				return false;
			}
			biome |= modifier instanceof BiomeFilter;
		}
		// Without, the world plants it wherever a biome of the chunks around holds it.
		return biome;
	}

	/**
	 * Grows a tree, none of whose blocks lies farther than {@code reach} from its trunk, and takes the crown over each
	 * column around its trunk, as {@link TreeCover.Shape} has it. A column of the trunk that no leaves cover, as where a
	 * kapok's crown opens over it, is capped with one over its wood, or the far view would not draw the trunk at all.
	 */
	private static Grown grow(int reach, Consumer<GiantTreeStructure.BlockSink> growth) {
		int side = 2 * reach + 1;
		short[] low = new short[side * side];
		short[] high = new short[side * side];
		short[] woodTop = new short[side * side];
		boolean[] trunk = new boolean[side * side];
		Arrays.fill(low, TreeCover.Shape.NO_LEAVES);
		Arrays.fill(high, TreeCover.Shape.NO_LEAVES);
		// The farthest the foot reaches from the axis, and the columns of the trunk.
		int[] foot = new int[2];
		growth.accept((x, y, z, wood) -> {
			int k = (z + reach) * side + x + reach;
			// Over the block the tree stands on, the one under its foot.
			short above = (short) (y + 1);
			if (!wood) {
				low[k] = low[k] == TreeCover.Shape.NO_LEAVES ? above : (short) Math.min(low[k], above);
				high[k] = (short) Math.max(high[k], above);
				return;
			}
			woodTop[k] = (short) Math.max(woodTop[k], above);
			if (y == 0) {
				trunk[k] = true;
				foot[0] = Math.max(foot[0], Math.max(Math.abs(x), Math.abs(z)));
				foot[1]++;
			}
		});
		for (int k = 0; k < trunk.length; k++) {
			if (trunk[k] && high[k] == TreeCover.Shape.NO_LEAVES) {
				low[k] = high[k] = (short) (woodTop[k] + 1);
			}
		}
		int trunkWidth = Math.max(1, (int) Math.round(Math.sqrt(foot[1])));
		return new Grown(new TreeCover.Shape(reach, low, high, trunk, trunkWidth), foot[0]);
	}

	/**
	 * The giants over one grid, which stand on {@code ground} among {@code biomes}: the grid's own, so that they grow
	 * where its land lets them.
	 */
	Stand stand(GiantTreeStructure.Ground ground, BiomeResolver biomes) {
		return new Stand(ground, biomes);
	}

	/**
	 * Fills {@code out} with a giant's crown over the column at block {@code (x, z)}, as {@link Stand#crown} does, and
	 * tells whether {@code out} holds a crown.
	 */
	private static boolean crown(Giant giant, int x, int z, int top, int step, boolean found, TreeCover.Crown out) {
		TreeCover.Shape shape = giant.shape;
		int index = shape.index(x - giant.x, z - giant.z);
		if (index < 0 || shape.high()[index] == TreeCover.Shape.NO_LEAVES) {
			return found;
		}
		// From over the block the giant stands on to over the column's ground.
		int lift = giant.base - 1 - top;
		int high = shape.high()[index] + lift;
		if (high < 1 || found && high <= out.high) {
			return found;
		}
		out.leaves = giant.leaves;
		out.trunk = giant.wood;
		out.low = shape.low()[index] + lift;
		out.high = high;
		out.holdsTrunk = shape.trunk()[index] && step <= shape.trunkWidth();
		return true;
	}

	/**
	 * Where a planting draws a giant in a chunk, before the far view looks at the ground there, and once it has, the
	 * giant that grows.
	 */
	private static final class Seedling {
		final Planting planting;
		final int x;
		final int z;
		/** How tall it is drawn, before the top of the world cuts it. */
		final int height;
		/** It is planted before the seedlings of a greater order. */
		final long order;
		/** Whether the ground under it was looked at, and the giant that grows there if no other keeps it from growing. */
		boolean looked;
		@Nullable Giant giant;
		/**
		 * Whether it grows: {@code 0} until known, {@code 1} if it does, {@code -1} if one planted before keeps it from
		 * growing.
		 */
		byte grows;

		Seedling(Planting planting, int x, int z, int height, long order) {
			this.planting = planting;
			this.x = x;
			this.z = z;
			this.height = height;
			this.order = order;
		}

		/** Whether its giant's crown, wherever it grows, may reach the column at block {@code (x, z)}. */
		boolean reaches(int x, int z) {
			return Math.max(Math.abs(x - this.x), Math.abs(z - this.z)) <= GiantTreeFeature.MAX_ROOM;
		}
	}

	/** The giants of one grid, found as its columns ask for them. Not thread-safe. */
	final class Stand {
		private static final Seedling[] NONE = {};

		private final GiantTreeStructure.Ground ground;
		private final BiomeResolver biomes;
		/** For each set, the giant of each of its regions where one grows, and {@code null} where none does. */
		private final List<Long2ObjectOpenHashMap<@Nullable Giant>> regions = new ArrayList<>();
		/** The seedlings the plantings draw in each chunk. */
		private final Long2ObjectOpenHashMap<Seedling[]> seedlings = new Long2ObjectOpenHashMap<>();
		/** Draws as the world's decoration does. */
		private final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));

		private Stand(GiantTreeStructure.Ground ground, BiomeResolver biomes) {
			this.ground = ground;
			this.biomes = biomes;
			for (int s = 0; s < spreads.length; s++) {
				regions.add(new Long2ObjectOpenHashMap<>());
			}
		}

		/**
		 * Fills {@code out} with the highest giant's crown over the column at block {@code (x, z)}, whose ground's top
		 * block is {@code top}, if it is higher than the crown {@code out} holds already, if {@code found}. Tells
		 * whether {@code out} holds a crown. The column stands for a square of {@code step} blocks; a trunk shows only
		 * in columns no wider than it.
		 */
		boolean crown(int x, int z, int top, int step, boolean found, TreeCover.Crown out) {
			for (int s = 0; s < spreads.length; s++) {
				Spread spread = spreads[s];
				int spacing = spread.placement.spacing();
				int lastX = Math.floorDiv(SectionPos.blockToSectionCoord(x + spread.reach), spacing);
				int lastZ = Math.floorDiv(SectionPos.blockToSectionCoord(z + spread.reach), spacing);
				for (int regionZ = Math.floorDiv(SectionPos.blockToSectionCoord(z - spread.reach), spacing);
					 regionZ <= lastZ; regionZ++) {
					for (int regionX = Math.floorDiv(SectionPos.blockToSectionCoord(x - spread.reach), spacing);
						 regionX <= lastX; regionX++) {
						Giant giant = giant(s, regionX, regionZ);
						if (giant != null) {
							found = GreatTrees.crown(giant, x, z, top, step, found, out);
						}
					}
				}
			}
			if (plantings.length == 0) {
				return found;
			}
			for (int chunkZ = firstChunk(z); chunkZ <= lastChunk(z); chunkZ++) {
				for (int chunkX = firstChunk(x); chunkX <= lastChunk(x); chunkX++) {
					for (Seedling seedling : seedlings(chunkX, chunkZ)) {
						if (seedling.reaches(x, z) && grows(seedling)) {
							found = GreatTrees.crown(seedling.giant, x, z, top, step, found, out);
						}
					}
				}
			}
			return found;
		}

		private @Nullable Giant giant(int s, int regionX, int regionZ) {
			Long2ObjectOpenHashMap<@Nullable Giant> giants = regions.get(s);
			long key = ChunkPos.pack(regionX, regionZ);
			if (giants.containsKey(key)) {
				return giants.get(key);
			}
			Giant giant = find(spreads[s], regionX, regionZ);
			giants.put(key, giant);
			return giant;
		}

		/** The giant that grows in a region of a set, or {@code null} if none does. */
		private @Nullable Giant find(Spread spread, int regionX, int regionZ) {
			int spacing = spread.placement.spacing();
			ChunkPos chunk = spread.placement.getPotentialStructureChunk(seed, regionX * spacing, regionZ * spacing);
			if (!spread.chunks.placed(chunk.x(), chunk.z())) {
				return null;
			}
			boolean looked = false;
			int footX = 0, footZ = 0, foot = Integer.MIN_VALUE;
			Holder<Biome> biome = null;
			// The world tries the set's structures in an order of its own, but only one has the biome of the foot.
			for (Kind kind : spread.kinds) {
				GiantTreeStructure.Sapling sapling = kind.structure.sapling(seed, chunk.x(), chunk.z(), scale);
				if (sapling == null) {
					continue;
				}
				if (!looked || sapling.x() != footX || sapling.z() != footZ) {
					looked = true;
					footX = sapling.x();
					footZ = sapling.z();
					foot = ground.at(footX, footZ);
					biome = foot == Integer.MIN_VALUE ? null : biomes.getNoiseBiome(QuartPos.fromBlock(footX),
						QuartPos.fromBlock(foot), QuartPos.fromBlock(footZ));
				}
				if (biome == null || !kind.structure.biomes().contains(biome)) {
					continue;
				}
				int height = kind.structure.height(sapling, foot, maxY, scale);
				if (height == 0) {
					continue;
				}
				Grown tree = kind.nearest(height);
				if (GiantTreeStructure.levelAround(ground, footX, foot, footZ, tree.footReach + 2)) {
					return new Giant(footX, footZ, foot, tree.shape, kind.structure.leaves(), kind.structure.wood());
				}
			}
			return null;
		}

		/** The first chunk along an axis whose planted giants may reach block {@code at} along it. */
		private int firstChunk(int at) {
			return Math.ceilDiv(at - farthestFoot - GiantTreeFeature.MAX_ROOM, 16);
		}

		/** The last chunk along an axis whose planted giants may reach block {@code at} along it. */
		private int lastChunk(int at) {
			return Math.floorDiv(at - nearestFoot + GiantTreeFeature.MAX_ROOM, 16);
		}

		/**
		 * The seedlings the plantings draw in a chunk, from the chunk's seed and each one's place among the features of
		 * its step, through its placement to the height of its tree, as the world's decoration draws them.
		 */
		private Seedling[] seedlings(int chunkX, int chunkZ) {
			long key = ChunkPos.pack(chunkX, chunkZ);
			Seedling[] drawn = seedlings.get(key);
			if (drawn != null) {
				return drawn;
			}
			drawn = NONE;
			BlockPos corner = new BlockPos(SectionPos.sectionToBlockCoord(chunkX), 0,
				SectionPos.sectionToBlockCoord(chunkZ));
			long decoration = random.setDecorationSeed(seed, corner.getX(), corner.getZ());
			// The world plants the chunks in the order they load; this one comes before or after another at random.
			long order = HashCommon.mix(key ^ seed) & ~0xFFFFL;
			for (Planting planting : plantings) {
				random.setFeatureSeed(decoration, planting.index, VEGETATION);
				BlockPos foot = planting.draw(random, corner);
				if (foot != null) {
					drawn = Arrays.copyOf(drawn, drawn.length + 1);
					drawn[drawn.length - 1] = new Seedling(planting, foot.getX(), foot.getZ(),
						planting.grove.feature.height(random, scale), order | planting.index);
				}
			}
			seedlings.put(key, drawn);
			return drawn;
		}

		/**
		 * The giant that grows of a seedling where nothing planted before it stands, or {@code null} if none does: if
		 * the ground is dry under its foot and level around it, and its biome plants it, as the placement and the
		 * feature ask of the world.
		 */
		private @Nullable Giant giant(Seedling seedling) {
			if (seedling.looked) {
				return seedling.giant;
			}
			seedling.looked = true;
			int base = ground.at(seedling.x, seedling.z);
			if (base == Integer.MIN_VALUE) {
				return null;
			}
			Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(seedling.x), QuartPos.fromBlock(base),
				QuartPos.fromBlock(seedling.z));
			Planting planting = seedling.planting;
			if (!biome.value().getGenerationSettings().hasFeature(planting.placed)) {
				return null;
			}
			int height = GiantTreeFeature.fit(seedling.height, base, maxY, scale);
			if (height == 0) {
				return null;
			}
			Grove grove = planting.grove;
			Grown tree = grove.nearest(height, GiantTreeFeature.room(seedling.x, seedling.z));
			if (GiantTreeStructure.levelAround(ground, seedling.x, base, seedling.z, tree.footReach + 2)) {
				seedling.giant = new Giant(seedling.x, seedling.z, base, tree.shape, grove.feature.leaves(),
					grove.feature.wood());
			}
			return seedling.giant;
		}

		/** Whether a seedling grows a giant: one grows there, and none planted before it stands over its foot. */
		private boolean grows(Seedling seedling) {
			if (seedling.grows == 0) {
				seedling.grows = giant(seedling) != null && !shaded(seedling, true) ? (byte) 1 : (byte) -1;
			}
			return seedling.grows > 0;
		}

		/**
		 * Whether the crown or the trunk of a giant planted before a seedling's stands over its foot: the feature then
		 * finds no ground under it. With {@code deep}, only of a giant that grows itself, without looking further back:
		 * one kept from growing by a third does not keep the seedling from growing.
		 */
		private boolean shaded(Seedling seedling, boolean deep) {
			for (int chunkZ = firstChunk(seedling.z); chunkZ <= lastChunk(seedling.z); chunkZ++) {
				for (int chunkX = firstChunk(seedling.x); chunkX <= lastChunk(seedling.x); chunkX++) {
					for (Seedling before : seedlings(chunkX, chunkZ)) {
						if (before.order >= seedling.order || !before.reaches(seedling.x, seedling.z)) {
							continue;
						}
						Giant giant = giant(before);
						if (giant != null && giant.covers(seedling.x, seedling.z) && !(deep && shaded(before, false))) {
							return true;
						}
					}
				}
			}
			return false;
		}
	}
}
