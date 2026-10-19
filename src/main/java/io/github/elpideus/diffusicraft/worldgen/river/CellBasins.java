package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;

import java.util.Arrays;

/**
 * The closed basins of one padded tile (see TerrainField#paddedTile): the hollows that water fills before it can run
 * off the edge of the tile or into the sea, each with the height at which it spills over.
 *
 * <p>A basin can hold a lake when it is at least {@value #MIN_DEPTH} m deep and {@value #MIN_PIXELS} pixels across,
 * lies above the sea, and keeps {@value #EDGE} pixels inside the tile, so that the tile alone decides its columns.
 * Rivers run over the surface of such a basin rather than its floor ({@link #ground}): they fill it and flow on out
 * over its lowest point. The {@link CellLakes} then follow from the rivers and the climate. Basins that reach past the
 * sides of the tile belong to the {@link BlockLakes}, and the rivers see the surface of those that a river feeds too.
 *
 * <p>Pixels are numbered as in the padded tile, row by row over {@link TerrainField#SPAN} columns.
 */
final class CellBasins {
	private static final int TILE = TerrainField.TILE;
	private static final int PAD = TerrainField.PAD;
	private static final int SPAN = TerrainField.SPAN;
	/** Basins that hold lakes keep this many pixels from the sides of the tile. */
	static final int EDGE = 4;
	/** Shallowest basin that holds a lake, meters. */
	static final float MIN_DEPTH = 1f;
	/** Smallest basin that holds a lake, pixels. */
	static final int MIN_PIXELS = 2;
	private static final int[] DI = {-1, -1, -1, 0, 0, 1, 1, 1};
	private static final int[] DJ = {-1, 0, 1, -1, 1, -1, 0, 1};

	private final float[] tile;
	/** The height water fills each pixel to; pixels that hold none keep their own height. */
	private final float[] fill;
	/** The basin of each pixel, from 1, or 0 for none. */
	private final int[] label;
	/** The pixels of basin {@code b}, from 1, are {@code pixels[start[b - 1]]} to {@code pixels[start[b] - 1]}. */
	private final int[] pixels;
	private final int[] start;
	/** Per basin, from 0: its lowest pixel and whether it can hold a lake. */
	private final int[] lowest;
	private final boolean[] holds;
	/** The ground the rivers see over the lakes of the blocks around, or NaN; null if there are none. */
	private final float[] raise;

	private CellBasins(float[] tile, float[] fill, int[] label, int[] pixels, int[] start, int[] lowest,
					   boolean[] holds, float[] raise) {
		this.tile = tile;
		this.fill = fill;
		this.label = label;
		this.pixels = pixels;
		this.start = start;
		this.lowest = lowest;
		this.holds = holds;
		this.raise = raise;
	}

	/** Finds the basins of a padded tile. */
	static CellBasins of(float[] tile) {
		return of(tile, null);
	}

	/**
	 * Finds the basins of a padded tile, where the rivers see the ground {@code raise} over the lakes of the blocks
	 * around (see BlockLakes#paint), in the frame of the padded tile: NaN where none lies. It may be null.
	 */
	static CellBasins of(float[] tile, float[] raise) {
		float[] fill = fill(tile, SPAN);
		int[] label = new int[SPAN * SPAN];
		int[] pixels = new int[SPAN * SPAN];
		int[] start = new int[64];
		int[] lowest = new int[64];
		boolean[] holds = new boolean[64];
		int count = 0, size = 0;
		for (int first = 0; first < fill.length; first++) {
			if (label[first] != 0 || !(fill[first] > tile[first])) {
				continue;
			}
			// Gather the basin: every pixel under the same water, which lies at one level.
			int id = count + 1, from = size, low = first;
			boolean inside = true;
			pixels[size++] = first;
			label[first] = id;
			for (int head = from; head < size; head++) {
				int k = pixels[head];
				int pi = k / SPAN - PAD, pj = k % SPAN - PAD;
				if (pi < EDGE || pj < EDGE || pi >= TILE - EDGE || pj >= TILE - EDGE) {
					inside = false;
				}
				if (tile[k] < tile[low]) {
					low = k;
				}
				for (int d = 0; d < 8; d++) {
					int ni = k / SPAN + DI[d], nj = k % SPAN + DJ[d];
					if (ni < 0 || nj < 0 || ni >= SPAN || nj >= SPAN) {
						continue;
					}
					int n = ni * SPAN + nj;
					if (label[n] == 0 && fill[n] > tile[n]) {
						label[n] = id;
						pixels[size++] = n;
					}
				}
			}
			if (count == start.length) {
				start = Arrays.copyOf(start, 2 * count);
				lowest = Arrays.copyOf(lowest, 2 * count);
				holds = Arrays.copyOf(holds, 2 * count);
			}
			float spill = fill[first];
			start[count] = size;
			lowest[count] = low;
			holds[count] = inside && size - from >= MIN_PIXELS && spill - tile[low] >= MIN_DEPTH && spill > 0f;
			count++;
		}
		return new CellBasins(tile, fill, label, pixels, Arrays.copyOf(start, count), lowest, holds, raise);
	}

	/** Number of basins; they are numbered from 1. */
	int count() {
		return start.length;
	}

	/** The basin of pixel {@code k}, or 0. */
	int basin(int k) {
		return label[k];
	}

	/** Whether basin {@code b} can hold a lake. */
	boolean holdsLake(int b) {
		return holds[b - 1];
	}

	/** The height at which basin {@code b} spills over, meters. */
	float spill(int b) {
		return fill[lowest[b - 1]];
	}

	/** The lowest pixel of basin {@code b}. */
	int lowest(int b) {
		return lowest[b - 1];
	}

	int firstPixel(int b) {
		return b == 1 ? 0 : start[b - 2];
	}

	int endPixel(int b) {
		return start[b - 1];
	}

	/** The {@code n}th pixel of all basins, in order of basin. */
	int pixel(int n) {
		return pixels[n];
	}

	/**
	 * The ground a river sees at pixel {@code k}: the surface of a basin that can hold a lake, which the river fills,
	 * or of a lake of the blocks around that a river feeds, and the terrain everywhere else.
	 */
	float ground(int k) {
		int b = label[k];
		float ground = b != 0 && holds[b - 1] ? fill[k] : tile[k];
		return raise != null && raise[k] > ground ? raise[k] : ground;
	}

	/**
	 * The priority flood: the height to which water would fill each pixel of a square of elevation {@code span}
	 * pixels across before it could run off the edge or into the sea. Pixels that would not hold water keep their own
	 * height.
	 */
	static float[] fill(float[] tile, int span) {
		float[] fill = new float[span * span];
		Arrays.fill(fill, Float.NaN);
		LongHeap heap = new LongHeap(4 * span);
		for (int k = 0; k < fill.length; k++) {
			int i = k / span, j = k % span;
			if (i == 0 || j == 0 || i == span - 1 || j == span - 1 || tile[k] < 0f) {
				fill[k] = tile[k];
				heap.push(LongHeap.key(tile[k], k));
			}
		}
		while (!heap.isEmpty()) {
			long key = heap.pop();
			int k = (int) key;
			float surface = LongHeap.cost(key);
			int i = k / span, j = k % span;
			for (int d = 0; d < 8; d++) {
				int ni = i + DI[d], nj = j + DJ[d];
				if (ni < 0 || nj < 0 || ni >= span || nj >= span) {
					continue;
				}
				int n = ni * span + nj;
				if (Float.isNaN(fill[n])) {
					fill[n] = Math.max(tile[n], surface);
					heap.push(LongHeap.key(fill[n], n));
				}
			}
		}
		return fill;
	}
}
