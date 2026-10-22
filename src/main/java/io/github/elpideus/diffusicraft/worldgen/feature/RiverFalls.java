package io.github.elpideus.diffusicraft.worldgen.feature;

import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import io.github.elpideus.diffusicraft.worldgen.river.RiverColumn;
import io.github.elpideus.diffusicraft.worldgen.river.Rivers;

import java.util.Arrays;

/**
 * The falls of the rivers over a chunk, laid out from the rivers' own columns ({@link Rivers#column}) around it. Where
 * a river's water stands lower than the water of a channel beside it, as below the steps the rivers fall in, the
 * ground's rules leave a wall of still water; here the lower column takes a curtain instead, from the higher water
 * down onto its own. Under each fall two blocks high or more, the river's bed sinks into a pool, deepest at the foot.
 * {@link RiverFallFeature} places them.
 *
 * <p>Everything follows from the columns alone, which are the same whichever chunk reads them, so that a pool across
 * the side of a chunk is dug alike on both sides.
 */
final class RiverFalls {
	/** No water in a column. */
	static final int NONE = Integer.MIN_VALUE;
	/** Farthest a pool reaches from the foot of its falls, in blocks. */
	static final int WIDEST = 3;
	/** How far out of the chunk the columns are read: as far as a pool reaches, and the columns beside its foot. */
	static final int BORDER = WIDEST + 1;
	private static final int SIDE = 16 + 2 * BORDER;
	private static final int[][] AROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	final int x0, z0;
	/** Highest water block of each column of the chunk and around it, or {@link #NONE}. */
	private final int[] water = new int[SIDE * SIDE];
	/** Whether the water of a column is a lake's. */
	private final boolean[] lake = new boolean[SIDE * SIDE];
	/** The highest block of each column of the chunk's curtain, which falls down to its water, or {@link #NONE}. */
	final int[] curtain = new int[256];
	/** How many blocks each column of the chunk's river bed sinks into a pool. */
	final int[] pool = new int[256];

	RiverFalls(int x0, int z0) {
		this.x0 = x0;
		this.z0 = z0;
		Arrays.fill(water, NONE);
	}

	/** The falls of the chunk at {@code (x0, z0)}, from the rivers' columns. */
	static RiverFalls read(Rivers rivers, WorldScale scale, int x0, int z0, int deepest) {
		RiverFalls falls = new RiverFalls(x0, z0);
		// Chunk by chunk, as the rivers keep the columns of one chunk at a time.
		for (int cz = -1; cz <= 1; cz++) {
			for (int cx = -1; cx <= 1; cx++) {
				for (int z = Math.max(-BORDER, 16 * cz); z < Math.min(16 + BORDER, 16 * cz + 16); z++) {
					for (int x = Math.max(-BORDER, 16 * cx); x < Math.min(16 + BORDER, 16 * cx + 16); x++) {
						RiverColumn column = rivers.column(scale, x0 + x, z0 + z);
						if (column.channel) {
							falls.set(x0 + x, z0 + z, column.waterTop, column.lake);
						}
					}
				}
			}
		}
		falls.plan(deepest);
		return falls;
	}

	/** Sets the water of a column within {@link #BORDER} blocks of the chunk. */
	void set(int x, int z, int top, boolean isLake) {
		int k = index(x - x0, z - z0);
		water[k] = top;
		lake[k] = isLake;
	}

	/**
	 * Lays out the curtains of the chunk's columns, and the pools, no deeper than {@code deepest} blocks. A fall
	 * {@code h} blocks high sinks the bed under it by a third of {@code h}, a block at least, and the river's bed
	 * around it, as far as a block for every four of {@code h} past the first and {@value #WIDEST} blocks at most, less
	 * the farther out.
	 */
	void plan(int deepest) {
		Arrays.fill(curtain, NONE);
		Arrays.fill(pool, 0);
		for (int z = 1 - BORDER; z < 15 + BORDER; z++) {
			for (int x = 1 - BORDER; x < 15 + BORDER; x++) {
				int top = fallsFrom(x, z);
				if (top == NONE) {
					continue;
				}
				int own = water[index(x, z)];
				if (inChunk(x, z)) {
					curtain[x + 16 * z] = top;
				}
				int height = top - own, depth = Math.min(deepest, Math.max(1, height / 3));
				if (height < 2 || depth <= 0) {
					continue;
				}
				int reach = Math.min(WIDEST, 1 + height / 4);
				for (int dz = -reach; dz <= reach; dz++) {
					for (int dx = -reach; dx <= reach; dx++) {
						int px = x + dx, pz = z + dz;
						float distance = (float) Math.sqrt(dx * dx + dz * dz);
						if (!inChunk(px, pz) || distance > reach || water[index(px, pz)] != own
							|| lake[index(px, pz)]) {
							continue;
						}
						int sink = Math.round(depth * (1f - distance / (reach + 1)));
						pool[px + 16 * pz] = Math.max(pool[px + 16 * pz], sink);
					}
				}
			}
		}
	}

	/** The highest water block of the column at {@code (x, z)} of the chunk, or {@link #NONE}. */
	int water(int x, int z) {
		return water[index(x, z)];
	}

	/** The highest water beside the column at {@code (x, z)} of the chunk if it stands above its own, or {@link #NONE}. */
	private int fallsFrom(int x, int z) {
		int own = water[index(x, z)];
		if (own == NONE) {
			return NONE;
		}
		int top = own;
		for (int[] d : AROUND) {
			top = Math.max(top, water[index(x + d[0], z + d[1])]);
		}
		return top > own ? top : NONE;
	}

	private static boolean inChunk(int x, int z) {
		return x >= 0 && x < 16 && z >= 0 && z < 16;
	}

	private static int index(int x, int z) {
		return x + BORDER + (z + BORDER) * SIDE;
	}
}
