package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;

import java.util.Arrays;

/**
 * The lakes inside one tile: closed basins of the fine terrain, filled with water up to the lowest point of their rim,
 * or less where the climate is too dry to fill them.
 *
 * <p>A priority flood over the tile and its margin fills every basin to the height at which it would spill over. A
 * filled basin becomes a lake when it is at least {@value #MIN_DEPTH} m deep and {@value #MIN_PIXELS} pixels across,
 * lies wholly inside the tile and at least {@value #EDGE} pixels from its sides, so that the tile alone decides its
 * columns, and lies beyond the reach of every river and its banks, as wide as a river gets at any scale: rivers drain
 * the basins they cross. A basin whose precipitation falls short of the evaporation holds a shallower lake, and none
 * in a desert.
 */
final class CellLakes {
	static final CellLakes EMPTY = new CellLakes(null, null, null, null, 0);

	private static final int TILE = TerrainField.TILE;
	private static final int PAD = TerrainField.PAD;
	private static final int SPAN = TerrainField.SPAN;
	/** Lakes keep this many pixels from the sides of the tile. */
	static final int EDGE = 4;
	/** Shallowest basin that holds a lake, meters. */
	static final float MIN_DEPTH = 1f;
	/** Smallest basin that holds a lake, pixels. */
	static final int MIN_PIXELS = 2;
	/** Ratio of precipitation to evaporation below which a basin holds no water, and above which it fills up. */
	private static final float DRY = 0.6f;
	private static final float WET = 1.0f;
	private static final int[] DI = {-1, -1, -1, 0, 0, 1, 1, 1};
	private static final int[] DJ = {-1, 0, 1, -1, 1, -1, 0, 1};

	/**
	 * The lakes' pixels as runs along each row, which take far less memory than a level per pixel: the runs of row
	 * {@code i} are {@code rowStart[i]} to {@code rowStart[i + 1]}, in order, each from column {@code runFrom} to
	 * {@code runTo} (exclusive) at water level {@code runLevel} in meters. Null if the tile has no lake.
	 */
	private final int[] rowStart;
	private final short[] runFrom;
	private final short[] runTo;
	private final float[] runLevel;
	private final int lakes;

	private CellLakes(int[] rowStart, short[] runFrom, short[] runTo, float[] runLevel, int lakes) {
		this.rowStart = rowStart;
		this.runFrom = runFrom;
		this.runTo = runTo;
		this.runLevel = runLevel;
		this.lakes = lakes;
	}

	boolean isEmpty() {
		return rowStart == null;
	}

	int lakeCount() {
		return lakes;
	}

	/** Water level of tile-local pixel {@code (i, j)} in meters, or NaN if it is not under a lake. */
	float level(int i, int j) {
		if (rowStart == null || i < 0 || i >= TILE) {
			return Float.NaN;
		}
		for (int r = rowStart[i], end = rowStart[i + 1]; r < end && j >= runFrom[r]; r++) {
			if (j < runTo[r]) {
				return runLevel[r];
			}
		}
		return Float.NaN;
	}

	/** Climate that decides how far a basin fills. */
	interface Climate {
		/** Annual precipitation over evaporation at native pixel {@code (i, j)}. */
		float wetness(int i, int j);
	}

	/**
	 * Finds the lakes of tile {@code (ti, tj)} in its padded elevation {@code tile} (see TerrainField#paddedTile),
	 * away from its {@code rivers}.
	 */
	static CellLakes build(float[] tile, CellRivers rivers, Climate climate, int ti, int tj) {
		float[] fill = fill(tile);
		int[] label = new int[SPAN * SPAN];
		int[] queue = new int[SPAN * SPAN];
		float[] level = null;
		int lakes = 0;
		CellRivers.Hit hit = new CellRivers.Hit();
		CellRivers.Width widest = discharge -> Rivers.MAX_HALF_WIDTH;
		int next = 0;
		for (int start = 0; start < fill.length; start++) {
			if (label[start] != 0 || !(fill[start] > tile[start])) {
				continue;
			}
			// Gather the basin: every pixel under the same water, which lies at one level.
			int id = ++next, size = 0, lowest = start;
			boolean inside = true;
			queue[size++] = start;
			label[start] = id;
			for (int head = 0; head < size; head++) {
				int k = queue[head];
				int pi = k / SPAN - PAD, pj = k % SPAN - PAD;
				if (pi < EDGE || pj < EDGE || pi >= TILE - EDGE || pj >= TILE - EDGE) {
					inside = false;
				}
				if (tile[k] < tile[lowest]) {
					lowest = k;
				}
				for (int d = 0; d < 8; d++) {
					int ni = k / SPAN + DI[d], nj = k % SPAN + DJ[d];
					if (ni < 0 || nj < 0 || ni >= SPAN || nj >= SPAN) {
						continue;
					}
					int n = ni * SPAN + nj;
					if (label[n] == 0 && fill[n] > tile[n]) {
						label[n] = id;
						queue[size++] = n;
					}
				}
			}
			float spill = fill[start];
			if (!inside || size < MIN_PIXELS || spill - tile[lowest] < MIN_DEPTH || spill <= 0f) {
				continue;
			}
			int li = lowest / SPAN - PAD, lj = lowest % SPAN - PAD;
			float wetness = climate.wetness(ti * TILE + li, tj * TILE + lj);
			float share = Math.clamp((wetness - DRY) / (WET - DRY), 0f, 1f);
			float water = tile[lowest] + share * (spill - tile[lowest]);
			if (water - tile[lowest] < MIN_DEPTH || nearRiver(queue, size, tile, water, rivers, widest, hit)) {
				continue;
			}
			if (level == null) {
				level = new float[TILE * TILE];
				Arrays.fill(level, Float.NaN);
			}
			int pixels = 0;
			for (int q = 0; q < size; q++) {
				int k = queue[q];
				if (tile[k] < water) {
					level[(k / SPAN - PAD) * TILE + k % SPAN - PAD] = water;
					pixels++;
				}
			}
			lakes += pixels > 0 ? 1 : 0;
		}
		return level == null ? EMPTY : runs(level, lakes);
	}

	/** Packs a level per pixel into runs along the rows. */
	private static CellLakes runs(float[] level, int lakes) {
		int[] rowStart = new int[TILE + 1];
		short[] from = new short[64], to = new short[64];
		float[] levels = new float[64];
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
				while (j < TILE && level[i * TILE + j] == water) {
					j++;
				}
				if (r == from.length) {
					from = Arrays.copyOf(from, 2 * r);
					to = Arrays.copyOf(to, 2 * r);
					levels = Arrays.copyOf(levels, 2 * r);
				}
				from[r] = (short) start;
				to[r] = (short) j;
				levels[r] = water;
				r++;
			}
		}
		rowStart[TILE] = r;
		return new CellLakes(rowStart, Arrays.copyOf(from, r), Arrays.copyOf(to, r), Arrays.copyOf(levels, r), lakes);
	}

	/** Whether a river or its banks reach any pixel of the basin that lies under {@code water}. */
	private static boolean nearRiver(int[] queue, int size, float[] tile, float water, CellRivers rivers,
									  CellRivers.Width widest, CellRivers.Hit hit) {
		if (rivers.isEmpty()) {
			return false;
		}
		for (int q = 0; q < size; q++) {
			int k = queue[q];
			if (tile[k] < water && rivers.nearest(k / SPAN - PAD + 0.5, k % SPAN - PAD + 0.5, widest, hit)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The priority flood: the height to which water would fill each pixel of the padded tile before it could run off
	 * the edge or into the sea. Pixels that would not hold water keep their own height.
	 */
	private static float[] fill(float[] tile) {
		float[] fill = new float[SPAN * SPAN];
		Arrays.fill(fill, Float.NaN);
		LongHeap heap = new LongHeap(4 * SPAN);
		for (int k = 0; k < fill.length; k++) {
			int i = k / SPAN, j = k % SPAN;
			if (i == 0 || j == 0 || i == SPAN - 1 || j == SPAN - 1 || tile[k] < 0f) {
				fill[k] = tile[k];
				heap.push(LongHeap.key(tile[k], k));
			}
		}
		while (!heap.isEmpty()) {
			long key = heap.pop();
			int k = (int) key;
			float surface = LongHeap.cost(key);
			int i = k / SPAN, j = k % SPAN;
			for (int d = 0; d < 8; d++) {
				int ni = i + DI[d], nj = j + DJ[d];
				if (ni < 0 || nj < 0 || ni >= SPAN || nj >= SPAN) {
					continue;
				}
				int n = ni * SPAN + nj;
				if (Float.isNaN(fill[n])) {
					fill[n] = Math.max(tile[n], surface);
					heap.push(LongHeap.key(fill[n], n));
				}
			}
		}
		return fill;
	}
}
