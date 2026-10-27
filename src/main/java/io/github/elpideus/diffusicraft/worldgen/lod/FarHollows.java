package io.github.elpideus.diffusicraft.worldgen.lod;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeClimate;
import io.github.elpideus.diffusicraft.worldgen.biome.BiomeWorld;
import io.github.elpideus.diffusicraft.worldgen.biome.HollowBiomes;
import io.github.elpideus.diffusicraft.worldgen.geology.Hollows;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.function.IntBinaryOperator;

/**
 * The Hollows of one grid of the far terrain, open where the chunks open them: the Grand Hollows, the smaller hollows
 * and the deep noodle caverns, with their lakes.
 *
 * <p>The chunks take the density of the Hollows at the corners of cells {@value #CELL_XZ} blocks wide and
 * {@value #CELL_Y} high, fade it to solid rock over the bottom {@value #FADE} blocks of the world, and run straight
 * between the corners (see {@code final_density} in {@code tools/worldgen_data.py}). So does this, computing each
 * corner once for the whole grid, so that a cavern's floor, walls and ceiling stand where the chunks put them, give or
 * take what vanilla's caves cut into them. Those, and the pools vanilla's aquifers leave, are left out.
 *
 * <p>Not thread-safe: one per grid.
 */
final class FarHollows {
	private static final int CELL_XZ = 4;
	private static final int CELL_Y = 8;
	/** The bottom of the world fades to this density over its lowest {@value #FADE} blocks. */
	private static final float BOTTOM = 0.1171875f;
	private static final int FADE = 24;
	private static final float[] NONE = new float[0];
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final BlockState ICE = Blocks.ICE.defaultBlockState();

	private final BiomeWorld world;
	private final Hollows hollows;
	private final int minY;
	private final IntBinaryOperator surface;
	private final Hollows.Column column = new Hollows.Column();
	/** The first corner of the grid and how many there are along X. */
	private final int cornerX;
	private final int cornerZ;
	private final int cornersWide;
	/** The density at each corner from cell {@code first} up, faded at the bottom, or {@code null} until computed. */
	private final float[][] densities;
	private final int[] firsts;
	/** Weights of the four corners around a column and their densities. */
	private final float[] weights = new float[4];
	private final float[][] around = new float[4][];
	private final int[] aroundFirst = new int[4];
	/** The open stretches found last, as pairs of the lowest block and the one over the highest. */
	private int[] stretches = new int[8];
	/** The heights the Hollows near the column found last can open, or an empty range. */
	private int reachBottom;
	private int reachTop;
	/** What fills the lake of each cavern met so far: water, ice, or air where it holds none. */
	private final Long2ObjectOpenHashMap<BlockState> lakes = new Long2ObjectOpenHashMap<>();
	private final TerrainField.Column scratch = new TerrainField.Column();
	private final BiomeClimate climate = new BiomeClimate();

	/**
	 * @param surface the terrain's surface in each column, which the Hollows keep their roof under: the world's own
	 *                where it is computed, or the coarse map's
	 * @param minX    the lowest block X of the columns this is asked about
	 * @param maxX    the highest block X of the columns this is asked about
	 */
	FarHollows(BiomeWorld world, int minY, IntBinaryOperator surface, int minX, int minZ, int maxX, int maxZ) {
		this.world = world;
		this.hollows = world.hollows();
		this.minY = minY;
		this.surface = surface;
		this.cornerX = Math.floorDiv(minX, CELL_XZ);
		this.cornerZ = Math.floorDiv(minZ, CELL_XZ);
		this.cornersWide = Math.floorDiv(maxX, CELL_XZ) + 2 - cornerX;
		int rows = Math.floorDiv(maxZ, CELL_XZ) + 2 - cornerZ;
		this.densities = new float[cornersWide * rows][];
		this.firsts = new int[cornersWide * rows];
	}

	/**
	 * Finds the open stretches of column {@code (x, z)} below block {@code limit}, bottom first, leaving out those
	 * fewer than {@code thinnest} blocks high. Read them with {@link #bottom} and {@link #top}.
	 *
	 * @return how many there are
	 */
	int open(int x, int z, int limit, int thinnest) {
		int cx = Math.floorDiv(x, CELL_XZ);
		int cz = Math.floorDiv(z, CELL_XZ);
		float fx = (x - cx * CELL_XZ) / (float) CELL_XZ;
		float fz = (z - cz * CELL_XZ) / (float) CELL_XZ;
		weights[0] = (1 - fx) * (1 - fz);
		weights[1] = fx * (1 - fz);
		weights[2] = (1 - fx) * fz;
		weights[3] = fx * fz;
		int low = Integer.MAX_VALUE;
		int high = Integer.MIN_VALUE;
		for (int i = 0; i < 4; i++) {
			// A column on a corner's line needs no corner past it.
			if (weights[i] == 0) {
				around[i] = NONE;
				continue;
			}
			int index = corner(cx + (i & 1), cz + (i >> 1));
			around[i] = densities[index];
			aroundFirst[i] = firsts[index];
			if (around[i].length > 0) {
				low = Math.min(low, firsts[index]);
				high = Math.max(high, firsts[index] + around[i].length - 1);
			}
		}
		if (low > high) {
			reachBottom = Integer.MAX_VALUE;
			reachTop = Integer.MIN_VALUE;
			return 0;
		}
		reachBottom = (low - 1) * CELL_Y;
		reachTop = (high + 1) * CELL_Y;
		// Every corner is solid outside its own range, so nothing opens more than a cell beyond them all.
		int count = 0;
		int openFrom = Integer.MIN_VALUE;
		float under = density(low - 1);
		for (int j = low - 1; j <= high; j++) {
			float over = density(j + 1);
			int cellBottom = j * CELL_Y;
			if (under > 0 && over > 0 || cellBottom >= limit) {
				if (openFrom != Integer.MIN_VALUE) {
					count = keep(count, openFrom, Math.min(cellBottom, limit), thinnest);
					openFrom = Integer.MIN_VALUE;
				}
				under = over;
				continue;
			}
			for (int y = Math.max(cellBottom, minY); y < cellBottom + CELL_Y && y < limit; y++) {
				float t = (y - cellBottom) / (float) CELL_Y;
				boolean open = under + t * (over - under) <= 0;
				if (open && openFrom == Integer.MIN_VALUE) {
					openFrom = y;
				} else if (!open && openFrom != Integer.MIN_VALUE) {
					count = keep(count, openFrom, y, thinnest);
					openFrom = Integer.MIN_VALUE;
				}
			}
			under = over;
		}
		if (openFrom != Integer.MIN_VALUE) {
			count = keep(count, openFrom, Math.min((high + 1) * CELL_Y, limit), thinnest);
		}
		return count;
	}

	/**
	 * Whether the Hollows come near the column found last, so that a cavern may open in the columns around it even
	 * where none opens in it. Its walls are seen between {@link #reachBottom} and {@link #reachTop}.
	 */
	boolean near() {
		return reachBottom < reachTop;
	}

	/** Lowest block the Hollows near the column found last can open. */
	int reachBottom() {
		return reachBottom;
	}

	/** Block over the highest the Hollows near the column found last can open. */
	int reachTop() {
		return reachTop;
	}

	/** Lowest block of open stretch {@code i}. */
	int bottom(int i) {
		return stretches[2 * i];
	}

	/** Block right above open stretch {@code i}. */
	int top(int i) {
		return stretches[2 * i + 1];
	}

	/**
	 * What fills the lake that open stretch {@code [bottom, top)} of column {@code (x, z)} starts in, and up to where,
	 * or {@code null} if it starts in none: water up to the brim, or ice where the lake is frozen solid, as
	 * {@code HollowLakes} fills it in the chunks.
	 *
	 * @param level receives the block over the lake's highest
	 */
	@Nullable BlockState lake(int x, int z, int bottom, int top, int[] level) {
		if (!column.isAt(x, z)) {
			hollows.column(x, z, surface, column);
		}
		// The chunks may open the floor of a basin a block or two deeper than the lake reaches.
		Hollows.Chamber chamber = null;
		for (int y = bottom; chamber == null && y < Math.min(top, bottom + CELL_Y); y++) {
			chamber = hollows.lakeAt(column, y);
		}
		if (chamber == null) {
			return null;
		}
		BlockState lake = lakes.get(chamber.id());
		if (lake == null) {
			HollowBiomes.climate(world, chamber, scratch, climate);
			String biome = HollowBiomes.pick(chamber.kind(), chamber.id(), climate);
			lake = !HollowBiomes.holdsLake(biome, climate) ? Blocks.AIR.defaultBlockState()
				: HollowBiomes.frozen(biome) ? ICE : WATER;
			lakes.put(chamber.id(), lake);
		}
		level[0] = Math.min(top, chamber.lakeLevel());
		return lake.isAir() || level[0] <= bottom ? null : lake;
	}

	private int keep(int count, int bottom, int top, int thinnest) {
		if (top - bottom < thinnest) {
			return count;
		}
		if (2 * count + 2 > stretches.length) {
			stretches = Arrays.copyOf(stretches, stretches.length * 2);
		}
		stretches[2 * count] = bottom;
		stretches[2 * count + 1] = top;
		return count + 1;
	}

	/** The density at height {@code 8j} of the column the corners around were last gathered for. */
	private float density(int j) {
		float value = 0;
		for (int i = 0; i < 4; i++) {
			if (weights[i] != 0) {
				int at = j - aroundFirst[i];
				value += weights[i] * (at >= 0 && at < around[i].length ? around[i][at] : faded(j, Hollows.SOLID));
			}
		}
		return value;
	}

	private float faded(int j, float density) {
		int y = j * CELL_Y;
		return y >= minY + FADE ? density : BOTTOM + Math.max(0, (y - minY) / (float) FADE) * (density - BOTTOM);
	}

	/** The index of corner {@code (cx, cz)}, its densities computed. */
	private int corner(int cx, int cz) {
		int index = (cz - cornerZ) * cornersWide + cx - cornerX;
		if (densities[index] != null) {
			return index;
		}
		hollows.column(cx * CELL_XZ, cz * CELL_XZ, surface, column);
		if (column.solid()) {
			densities[index] = NONE;
			return index;
		}
		int first = Math.max((int) Math.ceil(column.bottom() / CELL_Y), Math.floorDiv(minY, CELL_Y));
		int last = (int) Math.floor(column.top() / CELL_Y);
		float[] values = new float[Math.max(0, last - first + 1)];
		for (int j = first; j <= last; j++) {
			values[j - first] = faded(j, hollows.density(column, j * CELL_Y));
		}
		densities[index] = values;
		firsts[index] = first;
		return index;
	}
}
