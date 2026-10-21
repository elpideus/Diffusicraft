package io.github.elpideus.diffusicraft.worldgen.lod;

import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.feature.GiantTreeStructure;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;

/**
 * The giant trees that grow as structures ({@link GiantTreeStructure}), the ancient oaks and the kapoks, as a far view
 * sees them. {@link TreeCover} scatters a biome's trees at random, which does for a canopy; but a giant stands out
 * over it, and one drawn elsewhere than the world grows it would move as the chunks load. These stand where the world
 * grows them.
 *
 * <p>A structure set of giants places them in the chunks the world gives it, clear of the villages as the set keeps
 * them, and each chunk draws its sapling as the structure draws it: where in the chunk, how tall, and whether it grows
 * at all. The giant of the structure whose biomes hold the foot grows there if the far view's ground is dry under its
 * foot and level around it, as the structure asks of the world's ground. A far view's ground is not quite the world's,
 * so now and then a giant is drawn that the world does not grow, or the other way round.
 *
 * <p>The crowns are those of a few trees grown of each structure, at heights spread over its range: each giant has
 * the crown of the one nearest its own height. Growing every giant a far view shows would take far too long. So the
 * giants stand in their places at about their heights, but their crowns are not quite their own.
 *
 * <p>Thread-safe, as {@link LodSampler} needs: the trees are grown once, and each grid has a {@link Stand} of its own.
 */
final class GreatTrees {
	/** Trees grown of each structure, at heights spread over its range. */
	private static final int SHAPES = 16;
	/** Mixed into the seeds of the trees grown. */
	private static final long SHAPE_SEED = 0x3C6EF372FE94F82BL;

	private final Spread[] spreads;
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
			int slot = high == low ? 0
				: (int) Math.round((Math.clamp(height, low, high) - low) * (SHAPES - 1.0) / (high - low));
			Grown tree = grown.get(slot);
			if (tree == null) {
				// Two grids may grow the same tree at once, and grow the same one.
				tree = grow(structure, low + (int) Math.round((high - low) * slot / (SHAPES - 1.0)), SHAPE_SEED + slot);
				grown.compareAndSet(slot, null, tree);
			}
			return tree;
		}
	}

	private GreatTrees(Spread[] spreads, long seed, WorldScale scale, int maxY) {
		this.spreads = spreads;
		this.seed = seed;
		this.scale = scale;
		this.maxY = maxY;
	}

	/** The giants of a world whose highest block is {@code maxY}, or {@code null} if none of its sets places any. */
	static @Nullable GreatTrees of(ChunkGeneratorStructureState state, WorldScale scale, int maxY) {
		List<StructureSet> sets = state.possibleStructureSets().stream().map(Holder::value).toList();
		return of(sets, set -> (x, z) -> set.placement().isStructureChunk(state, x, z), state.getLevelSeed(), scale,
			maxY);
	}

	/**
	 * The giants of the structure sets {@code sets} that spread giants alone, which place them in the chunks that
	 * {@code chunks} gives each, or {@code null} if there are none.
	 */
	static @Nullable GreatTrees of(List<StructureSet> sets, Function<StructureSet, Chunks> chunks, long seed,
								   WorldScale scale, int maxY) {
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
		return spreads.isEmpty() ? null : new GreatTrees(spreads.toArray(Spread[]::new), seed, scale, maxY);
	}

	/**
	 * Grows a tree of a structure, {@code height} blocks tall, and takes the crown over each column around its trunk,
	 * as {@link TreeCover.Shape} has it. A column of the trunk that no leaves cover, as where a kapok's crown opens over
	 * it, is capped with one over its wood, or the far view would not draw the trunk at all.
	 */
	private static Grown grow(GiantTreeStructure structure, int height, long seed) {
		int reach = structure.reach(height);
		int side = 2 * reach + 1;
		short[] low = new short[side * side];
		short[] high = new short[side * side];
		short[] woodTop = new short[side * side];
		boolean[] trunk = new boolean[side * side];
		Arrays.fill(low, TreeCover.Shape.NO_LEAVES);
		Arrays.fill(high, TreeCover.Shape.NO_LEAVES);
		// The farthest the foot reaches from the axis, and the columns of the trunk.
		int[] foot = new int[2];
		structure.grow(height, seed, (x, y, z, wood) -> {
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

	/** The giants of one grid, found as its columns ask for them. Not thread-safe. */
	final class Stand {
		private final GiantTreeStructure.Ground ground;
		private final BiomeResolver biomes;
		/** For each set, the giant of each of its regions where one grows, and {@code null} where none does. */
		private final List<Long2ObjectOpenHashMap<@Nullable Giant>> regions = new ArrayList<>();

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
						if (giant == null) {
							continue;
						}
						TreeCover.Shape shape = giant.shape;
						int index = shape.index(x - giant.x, z - giant.z);
						if (index < 0 || shape.high()[index] == TreeCover.Shape.NO_LEAVES) {
							continue;
						}
						// From over the block the giant stands on to over the column's ground.
						int lift = giant.base - 1 - top;
						int high = shape.high()[index] + lift;
						if (high < 1 || found && high <= out.high) {
							continue;
						}
						found = true;
						out.leaves = giant.leaves;
						out.trunk = giant.wood;
						out.low = shape.low()[index] + lift;
						out.high = high;
						out.holdsTrunk = shape.trunk()[index] && step <= shape.trunkWidth();
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
	}
}
