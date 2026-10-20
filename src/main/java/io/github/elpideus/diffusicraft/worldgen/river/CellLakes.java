package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;

import java.util.Arrays;

/**
 * The lakes of one tile: the {@link CellBasins} that can hold one, filled with water, and the {@link BlockLakes} that
 * reach into it from the blocks around.
 *
 * <p>A basin that a river crosses fills to the river's level, just below its lowest point, where the river flows on
 * out of it. Any other basin is filled by the rain, up to its lowest point, or less where the climate is too dry to
 * fill it: a basin whose precipitation falls short of the evaporation holds a shallower lake, and none in a desert.
 * Such a basin stays dry within the reach of a river and its banks, as wide as a river gets at any scale. A basin
 * whose floor lies below the sea, a valley the ice drowned behind a step, holds the sea's water up to sea level
 * whatever the climate, and the rain fills it from there.
 *
 * <p>The lakes cover the tile and {@value #MARGIN} pixels around it, where the lakes of the blocks it shares with its
 * neighbours reach: the shores of the lakes beside it.
 */
final class CellLakes {
	static final CellLakes EMPTY = new CellLakes(null, null, null, null, null, 0);

	private static final int TILE = TerrainField.TILE;
	private static final int PAD = TerrainField.PAD;
	private static final int SPAN = TerrainField.SPAN;
	/** Pixels around the tile that its lakes cover. */
	static final int MARGIN = 2;
	/** Side of the square the lakes cover, pixels. */
	static final int SIDE = TILE + 2 * MARGIN;
	/** Ratio of precipitation to evaporation below which a basin holds no water, and above which it fills up. */
	static final float DRY = 0.6f;
	static final float WET = 1.0f;

	/**
	 * The lakes' pixels as runs along each row, which take far less memory than a level per pixel: the runs of
	 * tile-local row {@code i} are {@code rowStart[i + MARGIN]} to {@code rowStart[i + MARGIN + 1]}, in order, each from
	 * tile-local column {@code runFrom} to {@code runTo} (exclusive) at water level {@code runLevel} in meters, fed by a
	 * river where {@code runFed} is set. Null if the tile has no lake.
	 */
	private final int[] rowStart;
	private final short[] runFrom;
	private final short[] runTo;
	private final float[] runLevel;
	private final boolean[] runFed;
	private final int lakes;

	private CellLakes(int[] rowStart, short[] runFrom, short[] runTo, float[] runLevel, boolean[] runFed, int lakes) {
		this.rowStart = rowStart;
		this.runFrom = runFrom;
		this.runTo = runTo;
		this.runLevel = runLevel;
		this.runFed = runFed;
		this.lakes = lakes;
	}

	boolean isEmpty() {
		return rowStart == null;
	}

	/** Lakes in the tile's own basins. */
	int lakeCount() {
		return lakes;
	}

	/**
	 * The run holding tile-local pixel {@code (i, j)}, or -1 if it is not under a lake. Pixels up to {@value #MARGIN}
	 * outside the tile are known too.
	 */
	int run(int i, int j) {
		if (rowStart == null || i < -MARGIN || i >= TILE + MARGIN) {
			return -1;
		}
		for (int r = rowStart[i + MARGIN], end = rowStart[i + MARGIN + 1]; r < end && j >= runFrom[r]; r++) {
			if (j < runTo[r]) {
				return r;
			}
		}
		return -1;
	}

	/** Water level of a run in meters. */
	float runLevel(int run) {
		return runLevel[run];
	}

	/** Whether a run's lake is fed by a river, which sets its level. */
	boolean runFed(int run) {
		return runFed[run];
	}

	/** Water level of tile-local pixel {@code (i, j)} in meters, or NaN if it is not under a lake. */
	float level(int i, int j) {
		int r = run(i, j);
		return r < 0 ? Float.NaN : runLevel[r];
	}

	/** Climate that decides how far a basin fills. */
	interface Climate {
		/** Annual precipitation over evaporation at native pixel {@code (i, j)}. */
		float wetness(int i, int j);
	}

	/**
	 * Fills the {@code basins} of tile {@code (ti, tj)}, whose padded elevation is {@code tile} (see
	 * TerrainField#paddedTile), from its {@code rivers} and the levels along their paths (see {@link CellRivers#build};
	 * null if it has none) and from the climate.
	 */
	static CellLakes build(float[] tile, CellBasins basins, float[] pathLevel, CellRivers rivers, Climate climate,
						   int ti, int tj) {
		return build(tile, basins, pathLevel, rivers, climate, ti, tj, null, null);
	}

	/**
	 * Fills the {@code basins} of a tile as {@link #build(float[], CellBasins, float[], CellRivers, Climate, int, int)}
	 * does, over the lakes of the blocks around it: {@code level} and {@code fed} hold their water over the tile and
	 * {@value #MARGIN} pixels around, {@value #SIDE} pixels across (see BlockLakes#paint), NaN where there is none.
	 * They may be null, and are filled in.
	 */
	static CellLakes build(float[] tile, CellBasins basins, float[] pathLevel, CellRivers rivers, Climate climate,
						   int ti, int tj, float[] level, boolean[] fed) {
		int lakes = 0;
		CellRivers.Hit hit = new CellRivers.Hit();
		CellRivers.Width widest = discharge -> Rivers.MAX_HALF_WIDTH;
		for (int b = 1; b <= basins.count(); b++) {
			if (!basins.holdsLake(b)) {
				continue;
			}
			int first = basins.firstPixel(b), end = basins.endPixel(b), lowest = basins.lowest(b);
			float spill = basins.spill(b), river = Float.NaN;
			if (pathLevel != null) {
				for (int n = first; n < end; n++) {
					// The lowest river in the basin, which the others flow down into.
					float l = pathLevel[basins.pixel(n)];
					if (!Float.isNaN(l) && !(river <= l)) {
						river = l;
					}
				}
			}
			float water;
			if (!Float.isNaN(river)) {
				if (river > spill) {
					continue;
				}
				water = river;
			} else {
				int li = lowest / SPAN - PAD, lj = lowest % SPAN - PAD;
				water = rain(tile[lowest], spill, climate.wetness(ti * TILE + li, tj * TILE + lj));
			}
			// Water no higher than the sea is the sea's.
			if (water - tile[lowest] < CellBasins.MIN_DEPTH || !(water > 0f)
				|| Float.isNaN(river) && nearRiver(basins, first, end, tile, water, rivers, widest, hit)) {
				continue;
			}
			if (level == null) {
				level = new float[SIDE * SIDE];
				fed = new boolean[SIDE * SIDE];
				Arrays.fill(level, Float.NaN);
			}
			int pixels = 0;
			for (int n = first; n < end; n++) {
				int k = basins.pixel(n);
				if (tile[k] < water) {
					// Where a lake of the blocks around lies over this one, the higher water wins.
					int p = (k / SPAN - PAD + MARGIN) * SIDE + k % SPAN - PAD + MARGIN;
					boolean byRiver = !Float.isNaN(river);
					if (!(level[p] >= water)) {
						level[p] = water;
						fed[p] = byRiver;
					} else if (level[p] == water) {
						fed[p] |= byRiver;
					}
					pixels++;
				}
			}
			lakes += pixels > 0 ? 1 : 0;
		}
		return level == null ? EMPTY : runs(level, fed, lakes);
	}

	/**
	 * The level the rain fills a basin to, from its {@code floor} up to its {@code spill}, both in meters, where the
	 * precipitation over the evaporation is {@code wetness}. Below the sea the sea seeps in, so the rain fills a basin
	 * whose floor lies below it from sea level.
	 */
	static float rain(float floor, float spill, float wetness) {
		float bottom = Math.max(floor, 0f), share = Math.clamp((wetness - DRY) / (WET - DRY), 0f, 1f);
		return bottom + share * (spill - bottom);
	}

	/** Packs a level per pixel into runs along the rows. */
	private static CellLakes runs(float[] level, boolean[] fed, int lakes) {
		int[] rowStart = new int[SIDE + 1];
		short[] from = new short[64], to = new short[64];
		float[] levels = new float[64];
		boolean[] fedRuns = new boolean[64];
		int r = 0;
		for (int i = 0; i < SIDE; i++) {
			rowStart[i] = r;
			int j = 0;
			while (j < SIDE) {
				float water = level[i * SIDE + j];
				if (Float.isNaN(water)) {
					j++;
					continue;
				}
				int start = j;
				boolean river = fed[i * SIDE + j];
				while (j < SIDE && level[i * SIDE + j] == water && fed[i * SIDE + j] == river) {
					j++;
				}
				if (r == from.length) {
					from = Arrays.copyOf(from, 2 * r);
					to = Arrays.copyOf(to, 2 * r);
					levels = Arrays.copyOf(levels, 2 * r);
					fedRuns = Arrays.copyOf(fedRuns, 2 * r);
				}
				from[r] = (short) (start - MARGIN);
				to[r] = (short) (j - MARGIN);
				levels[r] = water;
				fedRuns[r] = river;
				r++;
			}
		}
		rowStart[SIDE] = r;
		if (r == 0) {
			return EMPTY;
		}
		return new CellLakes(rowStart, Arrays.copyOf(from, r), Arrays.copyOf(to, r), Arrays.copyOf(levels, r),
			Arrays.copyOf(fedRuns, r), lakes);
	}

	/** Whether a river or its banks reach any pixel of the basin that lies under {@code water}. */
	private static boolean nearRiver(CellBasins basins, int first, int end, float[] tile, float water,
									  CellRivers rivers, CellRivers.Width widest, CellRivers.Hit hit) {
		if (rivers.isEmpty()) {
			return false;
		}
		for (int n = first; n < end; n++) {
			int k = basins.pixel(n);
			if (tile[k] < water && rivers.nearest(k / SPAN - PAD + 0.5, k % SPAN - PAD + 0.5, widest, hit)) {
				return true;
			}
		}
		return false;
	}
}
