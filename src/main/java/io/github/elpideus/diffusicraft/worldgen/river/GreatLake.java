package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;

import java.util.Arrays;

/**
 * The water of a lake of the coarse map ({@link RiverNetwork.Lake}) on the fine terrain, which no single tile or block
 * of tiles sees whole.
 *
 * <p>The padded tiles of the lake's cells are flooded together, the water escaping into the sea and past the edge of
 * their padding, so every hollow the flood fills is held by the terrain itself. The water stands at one level for the
 * whole lake, between the cell it spills into on the coarse map and the level at which it spills there: the level at
 * which the hollows that hold it cover the most of the lake's tiles. It fills every hollow of the padded tiles that
 * holds it to that level; a lake whose tiles hold water nowhere in that range stays dry.
 *
 * <p>The rivers of the coarse map run across the lake at the level of its water, so a lake is fed: its water rises
 * to the next whole block. They see its surface, and the ground up to {@link CellRivers#DROP} above it around it, as
 * the ground they run on, as they do around the lakes of a block.
 */
final class GreatLake {
	private static final int TILE = TerrainField.TILE;
	private static final int PAD = TerrainField.PAD;
	private static final int SPAN = TerrainField.SPAN;
	private static final int[] DI = RiverNetwork.DI;
	private static final int[] DJ = RiverNetwork.DJ;
	/** Kinds of run: the water, and the ground a river sees around it. */
	private static final byte WATER = 0;
	private static final byte GROUND = 1;

	/** Field pixel of the first row and column of the window: the lake's tiles and their padding. */
	private final int i0;
	private final int j0;
	private final int rows;
	/** Runs along the rows of the window, as in {@link BlockLakes}; null if the lake is dry. */
	private final int[] rowStart;
	private final short[] runFrom;
	private final short[] runTo;
	private final byte[] runKind;
	private final float water;

	private GreatLake(int i0, int j0, int rows, int[] rowStart, short[] runFrom, short[] runTo, byte[] runKind,
					  float water) {
		this.i0 = i0;
		this.j0 = j0;
		this.rows = rows;
		this.rowStart = rowStart;
		this.runFrom = runFrom;
		this.runTo = runTo;
		this.runKind = runKind;
		this.water = water;
	}

	/** The level of the water, meters; the lowest it may be where the lake is dry. */
	float water() {
		return water;
	}

	boolean isEmpty() {
		return rowStart == null;
	}

	/**
	 * Finds the water of {@code lake} from the terrain of its tiles. It stands at least at {@code lowest}, the level of
	 * the cell it spills into.
	 */
	static GreatLake build(TerrainField field, RiverNetwork.Lake lake, float lowest) {
		int[] cells = lake.cells();
		int ci0 = Integer.MAX_VALUE, ci1 = Integer.MIN_VALUE, cj0 = Integer.MAX_VALUE, cj1 = Integer.MIN_VALUE;
		for (int c = 0; c < cells.length; c += 2) {
			ci0 = Math.min(ci0, cells[c]);
			ci1 = Math.max(ci1, cells[c]);
			cj0 = Math.min(cj0, cells[c + 1]);
			cj1 = Math.max(cj1, cells[c + 1]);
		}
		int h = (ci1 - ci0 + 1) * TILE + 2 * PAD, w = (cj1 - cj0 + 1) * TILE + 2 * PAD;
		// The padded tiles of the lake's cells, which agree to the bit where they overlap; NaN around them.
		float[] e = new float[h * w];
		Arrays.fill(e, Float.NaN);
		boolean[] own = new boolean[h * w];
		boolean[] drowned = new boolean[h * w];
		for (int c = 0; c < cells.length; c += 2) {
			float[] tile = field.paddedTile(cells[c], cells[c + 1]);
			long[] valleys = field.paddedDrowned(cells[c], cells[c + 1]);
			int oi = (cells[c] - ci0) * TILE, oj = (cells[c + 1] - cj0) * TILE;
			for (int r = 0; r < SPAN; r++) {
				System.arraycopy(tile, r * SPAN, e, (oi + r) * w + oj, SPAN);
				for (int col = 0; valleys != null && col < SPAN; col++) {
					drowned[(oi + r) * w + oj + col] |= TerrainField.isDrowned(valleys, r * SPAN + col);
				}
			}
			for (int r = PAD; r < PAD + TILE; r++) {
				Arrays.fill(own, (oi + r) * w + oj + PAD, (oi + r) * w + oj + PAD + TILE, true);
			}
		}
		float[] fill = fill(e, drowned, h, w);
		float water = level(e, fill, own, lowest, lake.level());
		int i0 = ci0 * TILE - PAD, j0 = cj0 * TILE - PAD;

		// The water: every pixel below it in a hollow that holds it. The ground: the water, and the shore up to a drop
		// above it joined to the water, but not the ground below it outside the hollows, where the water leaves.
		byte[] kind = new byte[h * w];
		int[] queue = new int[64];
		int length = 0;
		for (int k = 0; k < e.length; k++) {
			if (e[k] < water && fill[k] >= water) {
				kind[k] = 1 + WATER;
				if (length == queue.length) {
					queue = Arrays.copyOf(queue, 2 * length);
				}
				queue[length++] = k;
			}
		}
		if (length == 0) {
			return new GreatLake(i0, j0, h, null, null, null, null, water);
		}
		float shore = water + CellRivers.DROP;
		for (int head = 0; head < length; head++) {
			int k = queue[head], i = k / w, j = k % w;
			for (int d = 0; d < 8; d++) {
				int ni = i + DI[d], nj = j + DJ[d];
				if (ni < 0 || nj < 0 || ni >= h || nj >= w) {
					continue;
				}
				int m = ni * w + nj;
				if (kind[m] == 0 && e[m] >= water && e[m] < shore) {
					kind[m] = 1 + GROUND;
					if (length == queue.length) {
						queue = Arrays.copyOf(queue, 2 * length);
					}
					queue[length++] = m;
				}
			}
		}
		return runs(kind, h, w, i0, j0, water);
	}

	/**
	 * Floods the window from the sea, the ground below it that the ice did not drown, and from its edge, where it meets
	 * the pixels outside the lake's padded tiles: the level each pixel's water would stand at before it found a way
	 * out, as in {@link CellBasins#fill}.
	 */
	private static float[] fill(float[] e, boolean[] drowned, int h, int w) {
		float[] fill = new float[e.length];
		Arrays.fill(fill, Float.NaN);
		LongHeap heap = new LongHeap(4 * (h + w));
		for (int k = 0; k < e.length; k++) {
			if (Float.isNaN(e[k])) {
				continue;
			}
			int i = k / w, j = k % w;
			boolean edge = e[k] < 0f && !drowned[k];
			for (int d = 0; d < 8 && !edge; d++) {
				int ni = i + DI[d], nj = j + DJ[d];
				edge = ni < 0 || nj < 0 || ni >= h || nj >= w || Float.isNaN(e[ni * w + nj]);
			}
			if (edge) {
				fill[k] = e[k];
				heap.push(LongHeap.key(e[k], k));
			}
		}
		while (!heap.isEmpty()) {
			long key = heap.pop();
			int k = (int) key, i = k / w, j = k % w;
			float level = LongHeap.cost(key);
			for (int d = 0; d < 8; d++) {
				int ni = i + DI[d], nj = j + DJ[d];
				if (ni < 0 || nj < 0 || ni >= h || nj >= w) {
					continue;
				}
				int m = ni * w + nj;
				if (!Float.isNaN(e[m]) && Float.isNaN(fill[m])) {
					fill[m] = Math.max(e[m], level);
					heap.push(LongHeap.key(fill[m], m));
				}
			}
		}
		return fill;
	}

	/**
	 * The level from {@code lowest} to {@code highest} at which the most of the lake's own tiles lie under water that
	 * stays: below the level, in a hollow that holds it. The higher of equal ones; {@code lowest} if none holds any.
	 */
	static float level(float[] e, float[] fill, boolean[] own, float lowest, float highest) {
		if (!(highest > lowest)) {
			return lowest;
		}
		int n = 0;
		for (boolean o : own) {
			n += o ? 1 : 0;
		}
		float[] ground = new float[n], held = new float[n];
		for (int k = 0, c = 0; k < e.length; k++) {
			if (own[k]) {
				ground[c] = e[k];
				held[c++] = fill[k];
			}
		}
		Arrays.sort(ground);
		Arrays.sort(held);
		// The pixels under water at a level: those below it, less those whose hollows spill lower. The most lie under
		// water at the level a hollow spills at, or at the highest.
		float best = lowest;
		int most = below(ground, lowest) - below(held, lowest);
		int g = below(ground, lowest);
		for (int f = below(held, lowest); f < n && held[f] <= highest; ) {
			float level = held[f];
			while (g < n && ground[g] < level) {
				g++;
			}
			if (g - f >= most) {
				most = g - f;
				best = level;
			}
			while (f < n && held[f] == level) {
				f++;
			}
		}
		int top = below(ground, highest) - below(held, highest);
		if (top >= most) {
			most = top;
			best = highest;
		}
		return most == 0 ? lowest : best;
	}

	/** How many of the sorted {@code values} lie below {@code level}. */
	private static int below(float[] values, float level) {
		int lo = 0, hi = values.length;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (values[mid] < level) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	/** The runs of water and ground along each row, from the kind of every pixel, 0 for none. */
	private static GreatLake runs(byte[] kind, int h, int w, int i0, int j0, float water) {
		int[] rowStart = new int[h + 1];
		short[] from = new short[64], to = new short[64];
		byte[] runKind = new byte[64];
		int r = 0;
		for (int i = 0; i < h; i++) {
			rowStart[i] = r;
			for (byte k = 1 + WATER; k <= 1 + GROUND; k++) {
				for (int j = 0; j < w; ) {
					if (kind[i * w + j] != k) {
						j++;
						continue;
					}
					int start = j;
					while (j < w && kind[i * w + j] == k) {
						j++;
					}
					if (r == from.length) {
						from = Arrays.copyOf(from, 2 * r);
						to = Arrays.copyOf(to, 2 * r);
						runKind = Arrays.copyOf(runKind, 2 * r);
					}
					from[r] = (short) start;
					to[r] = (short) j;
					runKind[r] = (byte) (k - 1);
					r++;
				}
			}
		}
		rowStart[h] = r;
		return new GreatLake(i0, j0, h, rowStart, Arrays.copyOf(from, r), Arrays.copyOf(to, r),
			Arrays.copyOf(runKind, r), water);
	}

	/**
	 * Paints the lake into tile {@code (ti, tj)}, as {@link BlockLakes#paint} does: {@code level} and {@code fed}
	 * cover tile-local rows and columns {@code -margin} to {@code TILE + margin}, where a pixel takes the highest water
	 * over it, fed where any lake at that level is; {@code ground}, in the frame of the padded tile, takes the ground
	 * the rivers see where that is higher. Either may be null.
	 */
	void paint(int ti, int tj, int margin, float[] level, boolean[] fed, float[] ground) {
		if (rowStart == null) {
			return;
		}
		int side = TILE + 2 * margin;
		int r0 = ti * TILE - i0, c0 = tj * TILE - j0;
		float high = water + CellRivers.DROP;
		for (int r = Math.max(0, r0 - PAD); r < Math.min(rows, r0 + TILE + PAD); r++) {
			int li = r - r0;
			for (int run = rowStart[r], end = rowStart[r + 1]; run < end; run++) {
				int from = runFrom[run] - c0, to = runTo[run] - c0;
				if (ground != null) {
					for (int j = Math.max(from, -PAD); j < Math.min(to, TILE + PAD); j++) {
						int k = (li + PAD) * SPAN + j + PAD;
						if (!(ground[k] >= high)) {
							ground[k] = high;
						}
					}
				}
				if (runKind[run] == WATER && level != null && li >= -margin && li < TILE + margin) {
					for (int j = Math.max(from, -margin); j < Math.min(to, TILE + margin); j++) {
						int p = (li + margin) * side + j + margin;
						if (!(level[p] >= water)) {
							level[p] = water;
							fed[p] = true;
						} else if (level[p] == water) {
							fed[p] = true;
						}
					}
				}
			}
		}
	}
}
