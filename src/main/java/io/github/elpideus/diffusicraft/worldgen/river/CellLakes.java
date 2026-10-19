package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;

import java.util.Arrays;

/**
 * The lakes inside one tile: the {@link CellBasins} that can hold one, filled with water.
 *
 * <p>A basin that a river crosses fills to the river's level, just below its lowest point, where the river flows on
 * out of it. Any other basin is filled by the rain, up to its lowest point, or less where the climate is too dry to
 * fill it: a basin whose precipitation falls short of the evaporation holds a shallower lake, and none in a desert.
 * Such a basin stays dry within the reach of a river and its banks, as wide as a river gets at any scale.
 */
final class CellLakes {
	static final CellLakes EMPTY = new CellLakes(null, null, null, null, null, 0);

	private static final int TILE = TerrainField.TILE;
	private static final int PAD = TerrainField.PAD;
	private static final int SPAN = TerrainField.SPAN;
	/** Ratio of precipitation to evaporation below which a basin holds no water, and above which it fills up. */
	private static final float DRY = 0.6f;
	private static final float WET = 1.0f;

	/**
	 * The lakes' pixels as runs along each row, which take far less memory than a level per pixel: the runs of row
	 * {@code i} are {@code rowStart[i]} to {@code rowStart[i + 1]}, in order, each from column {@code runFrom} to
	 * {@code runTo} (exclusive) at water level {@code runLevel} in meters, fed by a river where {@code runFed} is set.
	 * Null if the tile has no lake.
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

	int lakeCount() {
		return lakes;
	}

	/** The run holding tile-local pixel {@code (i, j)}, or -1 if it is not under a lake. */
	int run(int i, int j) {
		if (rowStart == null || i < 0 || i >= TILE) {
			return -1;
		}
		for (int r = rowStart[i], end = rowStart[i + 1]; r < end && j >= runFrom[r]; r++) {
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
		float[] level = null;
		boolean[] fed = null;
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
				float wetness = climate.wetness(ti * TILE + li, tj * TILE + lj);
				float share = Math.clamp((wetness - DRY) / (WET - DRY), 0f, 1f);
				water = tile[lowest] + share * (spill - tile[lowest]);
			}
			if (water - tile[lowest] < CellBasins.MIN_DEPTH
				|| Float.isNaN(river) && nearRiver(basins, first, end, tile, water, rivers, widest, hit)) {
				continue;
			}
			if (level == null) {
				level = new float[TILE * TILE];
				fed = new boolean[TILE * TILE];
				Arrays.fill(level, Float.NaN);
			}
			int pixels = 0;
			for (int n = first; n < end; n++) {
				int k = basins.pixel(n);
				if (tile[k] < water) {
					int p = (k / SPAN - PAD) * TILE + k % SPAN - PAD;
					level[p] = water;
					fed[p] = !Float.isNaN(river);
					pixels++;
				}
			}
			lakes += pixels > 0 ? 1 : 0;
		}
		return level == null ? EMPTY : runs(level, fed, lakes);
	}

	/** Packs a level per pixel into runs along the rows. */
	private static CellLakes runs(float[] level, boolean[] fed, int lakes) {
		int[] rowStart = new int[TILE + 1];
		short[] from = new short[64], to = new short[64];
		float[] levels = new float[64];
		boolean[] fedRuns = new boolean[64];
		int r = 0;
		for (int i = 0; i < TILE; i++) {
			rowStart[i] = r;
			int j = 0;
			while (j < TILE) {
				float water = level[i * TILE + j];
				if (Float.isNaN(water)) {
					j++;
					continue;
				}
				int start = j;
				boolean river = fed[i * TILE + j];
				while (j < TILE && level[i * TILE + j] == water && fed[i * TILE + j] == river) {
					j++;
				}
				if (r == from.length) {
					from = Arrays.copyOf(from, 2 * r);
					to = Arrays.copyOf(to, 2 * r);
					levels = Arrays.copyOf(levels, 2 * r);
					fedRuns = Arrays.copyOf(fedRuns, 2 * r);
				}
				from[r] = (short) start;
				to[r] = (short) j;
				levels[r] = water;
				fedRuns[r] = river;
				r++;
			}
		}
		rowStart[TILE] = r;
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
