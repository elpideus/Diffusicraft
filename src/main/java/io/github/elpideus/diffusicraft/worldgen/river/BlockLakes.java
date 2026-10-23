package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * The lakes of one block of two by two or three by three tiles that reach past the sides of a single tile, which the
 * {@link CellBasins} of a tile cannot see whole.
 *
 * <p>Block {@code (bi, bj)} of {@code n} by {@code n} tiles covers tiles {@code bi} to {@code bi + n - 1} down and
 * {@code bj} to {@code bj + n - 1} across, so blocks of a size overlap and every tile lies in {@code n²} of them. The
 * basins of a block come from one priority flood over its padded tiles, which agree to the bit where they overlap; a
 * basin it keeps stays {@value CellBasins#EDGE} pixels inside the block, and is left to the tile's own basins when it
 * also stays that far inside a single tile. Every basin of every block is a real hollow of the terrain, so a pixel
 * under several lakes takes the highest: the lakes then meet at one level wherever they touch, and the tiles beside a
 * side, which share the blocks that reach it, agree on the water there. Both sizes are needed: the flood of a larger
 * block can join a basin to a hollow beside it that reaches past its sides, where a smaller one keeps it apart.
 *
 * <p>Such a lake is fed by a river when a river crosses one of the block's inner sides through it, near the node
 * where it crosses ({@link CellRivers#node}): it fills to that river's level, and at most to {@link CellRivers#DROP}
 * below its spill, where the river flows on out of it. The rivers of its tiles see it as the surface of its water, as
 * they see the tile's own lakes, though only for their level. Any other basin is filled by the rain, as in
 * {@link CellLakes}, unless a river passes within reach of its water, lower than it, and would drain it. The block
 * draws the rivers of its tiles to tell: their paths follow each tile alone ({@link CellBasins#surface}), so the
 * block needs neither its own lakes nor those of the other blocks to draw them. A river that crosses such a basin
 * away from the nodes, inside one tile, leaves it dry: the river cannot feed it, as its level depends on the lakes of
 * the other blocks.
 *
 * <p>A lake larger than a block of three by three tiles stays dry, unless it lies in a depression of the coarse map
 * ({@link GreatLake}).
 */
final class BlockLakes {
	static final BlockLakes EMPTY = new BlockLakes(0, null, null, null, null, null, 0);

	private static final int TILE = TerrainField.TILE;
	private static final int PAD = TerrainField.PAD;
	private static final int SPAN = TerrainField.SPAN;
	private static final int EDGE = CellBasins.EDGE;
	/**
	 * Pixels around a crossing node that its river reaches: the straight part of the river, which runs
	 * {@link CellRivers#STRAIGHT} pixels into each tile, and the widest a river gets on either side of it.
	 */
	private static final int REACH_ALONG = (int) CellRivers.STRAIGHT;
	private static final int REACH_ACROSS = (int) Rivers.MAX_HALF_WIDTH;
	private static final int[] DI = {-1, -1, -1, 0, 0, 1, 1, 1};
	private static final int[] DJ = {-1, 0, 1, -1, 1, -1, 0, 1};

	/** Pixels across the block. */
	private final int size;
	/**
	 * The lakes as runs along the rows of the block, as in {@link CellLakes}: the runs of row {@code i} are
	 * {@code rowStart[i]} to {@code rowStart[i + 1]}, each from column {@code runFrom} to {@code runTo} (exclusive),
	 * of one {@code runKind} and at {@code runLevel} meters: the water of a lake, or the ground a river sees around a
	 * fed one. Null if the block has no lake.
	 */
	private final int[] rowStart;
	private final short[] runFrom;
	private final short[] runTo;
	private final float[] runLevel;
	private final byte[] runKind;
	private final int lakes;

	/** Kinds of run: water a river does not feed, water a river feeds, and ground a river sees. */
	private static final byte RAIN = 0;
	private static final byte FED = 1;
	private static final byte GROUND = 2;

	private BlockLakes(int size, int[] rowStart, short[] runFrom, short[] runTo, float[] runLevel, byte[] runKind,
					   int lakes) {
		this.size = size;
		this.rowStart = rowStart;
		this.runFrom = runFrom;
		this.runTo = runTo;
		this.runLevel = runLevel;
		this.runKind = runKind;
		this.lakes = lakes;
	}

	boolean isEmpty() {
		return rowStart == null;
	}

	int lakeCount() {
		return lakes;
	}

	/**
	 * Finds the lakes of block {@code (bi, bj)} of {@code n} by {@code n} tiles from the padded elevation of its
	 * tiles, their drowned valleys (see TerrainField#paddedDrowned; null where a tile has none) and the rivers crossing
	 * them, row by row: tile {@code (bi + a, bj + b)} is at index {@code a * n + b}. {@code rivers} draws the rivers of
	 * a tile from its index, as {@link CellRivers#build} does over the tile's basins with no lakes of the blocks
	 * around; it is asked only for tiles with rivers whose paths the block needs.
	 */
	static BlockLakes build(int n, float[][] tiles, long[][] drowned, CellFlows[] flows,
							IntFunction<CellRivers> rivers, CellLakes.Climate climate, int bi, int bj) {
		int size = n * TILE, window = size + 2 * PAD;
		float[] e = window(n, tiles);
		float[] fill = CellBasins.fill(e, window(n, drowned), window);
		float[] crossing = crossings(n, tiles, flows);
		CellRivers[] drawn = new CellRivers[n * n];
		CellRivers.Hit hit = new CellRivers.Hit();
		Painter painter = new Painter(size);
		int[] label = new int[window * window];
		int[] pixels = new int[window * window];
		int lakes = 0;
		for (int first = 0; first < fill.length; first++) {
			if (label[first] != 0 || !(fill[first] > e[first])) {
				continue;
			}
			// Gather the basin, as in CellBasins.
			int count = 0, low = first, tile = -1;
			boolean inside = true, ownTile = true;
			float river = Float.NaN;
			pixels[count++] = first;
			label[first] = 1;
			for (int head = 0; head < count; head++) {
				int k = pixels[head];
				int i = k / window - PAD, j = k % window - PAD;
				if (i < EDGE || j < EDGE || i >= size - EDGE || j >= size - EDGE) {
					inside = false;
				}
				int a = tileOf(n, i), b = tileOf(n, j), t = a * n + b;
				int li = i - a * TILE, lj = j - b * TILE;
				if (tile < 0) {
					tile = t;
				}
				if (t != tile || li < EDGE || lj < EDGE || li >= TILE - EDGE || lj >= TILE - EDGE) {
					ownTile = false;
				}
				if (e[k] < e[low]) {
					low = k;
				}
				float c = crossing[k];
				if (!Float.isNaN(c) && !(river <= c)) {
					river = c;
				}
				for (int d = 0; d < 8; d++) {
					int ni = k / window + DI[d], nj = k % window + DJ[d];
					if (ni < 0 || nj < 0 || ni >= window || nj >= window) {
						continue;
					}
					int m = ni * window + nj;
					if (label[m] == 0 && fill[m] > e[m]) {
						label[m] = 1;
						pixels[count++] = m;
					}
				}
			}
			float spill = fill[first], floor = e[low];
			if (!inside || ownTile || count < CellBasins.MIN_PIXELS || !(spill > 0f)
				|| spill - floor < CellBasins.MIN_DEPTH) {
				continue;
			}
			float water;
			boolean fed = !Float.isNaN(river);
			if (fed) {
				water = Math.min(river, spill - CellRivers.DROP);
			} else {
				int li = low / window - PAD, lj = low % window - PAD;
				water = CellLakes.rain(floor, spill, climate.wetness(bi * TILE + li, bj * TILE + lj));
				if (nearRiver(n, pixels, count, e, water, flows, rivers, drawn, hit)) {
					continue;
				}
			}
			if (water - floor < CellBasins.MIN_DEPTH || !(water > 0f)) {
				continue;
			}
			for (int q = 0; q < count; q++) {
				int k = pixels[q];
				int i = k / window - PAD, j = k % window - PAD;
				if (e[k] < water) {
					painter.add(i, j, water, fed ? FED : RAIN);
				}
				if (fed && e[k] < water + CellRivers.DROP) {
					painter.add(i, j, water + CellRivers.DROP, GROUND);
				}
			}
			lakes++;
		}
		return lakes == 0 ? EMPTY : painter.runs(lakes);
	}

	/**
	 * Whether a river of the block's tiles passes within reach of the water of a basin, its {@code count}
	 * {@code pixels} in the window below {@code water}, as {@link CellLakes} has it for the lakes of a tile. The basin
	 * lies inside the block, and a river of a tile around the block keeps farther from it than it reaches, except
	 * where it crosses into a tile of the block, which draws it too. {@code drawn} keeps the rivers drawn so far.
	 */
	private static boolean nearRiver(int n, int[] pixels, int count, float[] e, float water, CellFlows[] flows,
									 IntFunction<CellRivers> rivers, CellRivers[] drawn, CellRivers.Hit hit) {
		int window = n * TILE + 2 * PAD;
		for (int q = 0; q < count; q++) {
			int k = pixels[q];
			if (!(e[k] < water)) {
				continue;
			}
			int i = k / window - PAD, j = k % window - PAD;
			int a = tileOf(n, i), b = tileOf(n, j), t = a * n + b;
			if (flows[t].isEmpty()) {
				continue;
			}
			if (drawn[t] == null) {
				drawn[t] = rivers.apply(t);
			}
			if (drawn[t].nearest(i - a * TILE + 0.5, j - b * TILE + 0.5, CellLakes.WIDEST, hit)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Paints the lakes into tile {@code (oi, oj)} of the block, each from 0 to one less than its tiles across.
	 * {@code level} and {@code fed} cover tile-local rows and columns {@code -margin} to {@code TILE + margin}; a pixel
	 * takes the highest water over it, and is fed where any lake at that level is. {@code ground}, in the frame of the
	 * padded tile, takes the ground that a river sees around a fed lake, where that is higher. Either may be null.
	 */
	void paint(int oi, int oj, int margin, float[] level, boolean[] fed, float[] ground) {
		if (rowStart == null) {
			return;
		}
		int side = TILE + 2 * margin;
		int i0 = oi * TILE, j0 = oj * TILE;
		for (int i = Math.max(0, i0 - PAD); i < Math.min(size, i0 + TILE + PAD); i++) {
			int li = i - i0;
			for (int r = rowStart[i], end = rowStart[i + 1]; r < end; r++) {
				float value = runLevel[r];
				if (runKind[r] == GROUND) {
					if (ground == null) {
						continue;
					}
					int from = Math.max(runFrom[r], j0 - PAD), to = Math.min(runTo[r], j0 + TILE + PAD);
					for (int j = from; j < to; j++) {
						int k = (li + PAD) * SPAN + j - j0 + PAD;
						if (!(ground[k] >= value)) {
							ground[k] = value;
						}
					}
					continue;
				}
				if (level == null || li < -margin || li >= TILE + margin) {
					continue;
				}
				boolean river = runKind[r] == FED;
				int from = Math.max(runFrom[r], j0 - margin), to = Math.min(runTo[r], j0 + TILE + margin);
				for (int j = from; j < to; j++) {
					int p = (li + margin) * side + j - j0 + margin;
					if (!(level[p] >= value)) {
						level[p] = value;
						fed[p] = river;
					} else if (level[p] == value) {
						fed[p] |= river;
					}
				}
			}
		}
	}

	/** The tile of a block of {@code n} by {@code n} tiles that its row or column {@code i} lies in or beside. */
	private static int tileOf(int n, int i) {
		return Math.clamp(Math.floorDiv(i, TILE), 0, n - 1);
	}

	/**
	 * The drowned valleys of the block's window, as {@link CellBasins#fill} takes them, from those of its padded
	 * tiles; null if none has any.
	 */
	private static long[] window(int n, long[][] drowned) {
		if (Arrays.stream(drowned).allMatch(Objects::isNull)) {
			return null;
		}
		int window = n * TILE + 2 * PAD;
		long[] mask = new long[(window * window + 63) >> 6];
		for (int wi = 0; wi < window; wi++) {
			int i = wi - PAD;
			int a = tileOf(n, i);
			for (int b = 0; b < n; b++) {
				// As in window(int, float[][]).
				int from = b == 0 ? 0 : b * TILE + PAD, to = b == n - 1 ? window : (b + 1) * TILE + PAD;
				long[] tile = drowned[a * n + b];
				int row = (i - a * TILE + PAD) * SPAN - b * TILE;
				for (int wj = from; wj < to; wj++) {
					if (TerrainField.isDrowned(tile, row + wj)) {
						int k = wi * window + wj;
						mask[k >> 6] |= 1L << k;
					}
				}
			}
		}
		return mask;
	}

	/** The padded elevation of the block's window, from its padded tiles. */
	private static float[] window(int n, float[][] tiles) {
		int window = n * TILE + 2 * PAD;
		float[] e = new float[window * window];
		for (int wi = 0; wi < window; wi++) {
			int i = wi - PAD;
			int a = tileOf(n, i);
			for (int b = 0; b < n; b++) {
				// Each tile's own columns, and the padding of the first and last beyond the block's sides.
				int from = b == 0 ? 0 : b * TILE + PAD, to = b == n - 1 ? window : (b + 1) * TILE + PAD;
				float[] tile = tiles[a * n + b];
				System.arraycopy(tile, (i - a * TILE + PAD) * SPAN + from - b * TILE, e, wi * window + from,
					to - from);
			}
		}
		return e;
	}

	/**
	 * The lowest level of the rivers that cross the block's inner sides, over the pixels they reach near the nodes
	 * where they cross, in the frame of the window; NaN elsewhere. Both tiles beside a side find the same node and
	 * the same rivers.
	 */
	private static float[] crossings(int n, float[][] tiles, CellFlows[] flows) {
		int window = n * TILE + 2 * PAD;
		float[] crossing = new float[window * window];
		Arrays.fill(crossing, Float.NaN);
		// The east sides of all tiles but the eastern ones, and the south sides of all but the southern ones.
		for (int s = 0; s < 2 * n * n; s++) {
			int t = s >> 1, side = (s & 1) == 0 ? 3 : 1;
			if (side == 3 ? t % n == n - 1 : t / n == n - 1) {
				continue;
			}
			float in = flows[t].inLevel[side], out = flows[t].outLevel[side];
			if (Float.isNaN(in) && Float.isNaN(out)) {
				continue;
			}
			float level = Float.isNaN(in) ? out : Float.isNaN(out) ? in : Math.min(in, out);
			int node = CellRivers.node(tiles[t], side);
			int i0 = t / n * TILE, j0 = t % n * TILE;
			// Along the river and across it, around the node.
			for (int along = -REACH_ALONG; along < REACH_ALONG; along++) {
				for (int across = -REACH_ACROSS; across <= REACH_ACROSS; across++) {
					int i, j;
					if (side == 3) {
						i = i0 + node + across;
						j = j0 + TILE + along;
					} else {
						i = i0 + TILE + along;
						j = j0 + node + across;
					}
					int k = (i + PAD) * window + j + PAD;
					if (!(crossing[k] <= level)) {
						crossing[k] = level;
					}
				}
			}
		}
		return crossing;
	}

	/** Collects the runs of the lakes of a block, row by row. */
	private static final class Painter {
		private final int size;
		/** Per row: the pixels painted, as column, kind and level. */
		private final int[][] cols;
		private final byte[][] kinds;
		private final float[][] levels;
		private final int[] counts;

		Painter(int size) {
			this.size = size;
			cols = new int[size][];
			kinds = new byte[size][];
			levels = new float[size][];
			counts = new int[size];
		}

		void add(int i, int j, float level, byte kind) {
			int n = counts[i];
			if (cols[i] == null) {
				cols[i] = new int[16];
				kinds[i] = new byte[16];
				levels[i] = new float[16];
			} else if (n == cols[i].length) {
				cols[i] = Arrays.copyOf(cols[i], 2 * n);
				kinds[i] = Arrays.copyOf(kinds[i], 2 * n);
				levels[i] = Arrays.copyOf(levels[i], 2 * n);
			}
			cols[i][n] = j;
			kinds[i][n] = kind;
			levels[i][n] = level;
			counts[i] = n + 1;
		}

		BlockLakes runs(int lakes) {
			int[] rowStart = new int[size + 1];
			short[] from = new short[64], to = new short[64];
			float[] level = new float[64];
			byte[] kind = new byte[64];
			int r = 0;
			long[] order = new long[16];
			for (int i = 0; i < size; i++) {
				rowStart[i] = r;
				int n = counts[i];
				if (n == 0) {
					continue;
				}
				// Sort the row's pixels by kind, then column; a basin paints each pixel once per kind.
				if (order.length < n) {
					order = new long[Math.max(n, 2 * order.length)];
				}
				for (int m = 0; m < n; m++) {
					order[m] = (long) kinds[i][m] << 40 | (long) cols[i][m] << 20 | m;
				}
				Arrays.sort(order, 0, n);
				int m = 0;
				while (m < n) {
					int at = (int) (order[m] & 0xFFFFF);
					int start = cols[i][at];
					byte k = kinds[i][at];
					float value = levels[i][at];
					int end = start + 1;
					m++;
					while (m < n) {
						int next = (int) (order[m] & 0xFFFFF);
						if (kinds[i][next] != k || cols[i][next] != end || levels[i][next] != value) {
							break;
						}
						end++;
						m++;
					}
					if (r == from.length) {
						from = Arrays.copyOf(from, 2 * r);
						to = Arrays.copyOf(to, 2 * r);
						level = Arrays.copyOf(level, 2 * r);
						kind = Arrays.copyOf(kind, 2 * r);
					}
					from[r] = (short) start;
					to[r] = (short) end;
					level[r] = value;
					kind[r] = k;
					r++;
				}
			}
			rowStart[size] = r;
			return new BlockLakes(size, rowStart, Arrays.copyOf(from, r), Arrays.copyOf(to, r), Arrays.copyOf(level, r),
				Arrays.copyOf(kind, r), lakes);
		}
	}
}
