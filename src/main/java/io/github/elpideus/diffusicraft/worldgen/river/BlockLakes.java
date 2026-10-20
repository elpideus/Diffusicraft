package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;

import java.util.Arrays;

/**
 * The lakes of one block of two by two tiles that reach past the sides of a single tile, which the {@link CellBasins}
 * of a tile cannot see whole.
 *
 * <p>Block {@code (bi, bj)} covers tiles {@code bi} and {@code bi + 1} down and {@code bj} and {@code bj + 1} across,
 * so blocks overlap by a tile and every tile lies in four of them. The basins of a block come from one priority
 * flood over its four padded tiles, which agree to the bit where they overlap; a basin it keeps stays
 * {@value CellBasins#EDGE} pixels inside the block, and is left to the tile's own basins when it also stays that far
 * inside a single tile. Every basin of every block is a real hollow of the terrain, so a pixel under several lakes
 * takes the highest: the lakes then meet at one level wherever they touch, and the tiles beside a side, which share
 * the blocks that reach it, agree on the water there.
 *
 * <p>Such a lake is fed by a river when a river crosses one of the block's inner sides through it, near the node
 * where it crosses ({@link CellRivers#node}): it fills to that river's level, and at most to {@link CellRivers#DROP}
 * below its spill, where the river flows on out of it. The rivers of its tiles see it as the surface of its water, as
 * they see the tile's own lakes. Any other basin is filled by the rain, as in {@link CellLakes}, but only where no
 * river runs in the tiles its water reaches: the rivers of each tile are its own, and the block cannot tell whether
 * one of them would pass beside the lake, lower than its water, and drain it. A river keeps {@link CellRivers#LO}
 * pixels inside its tile, farther than it and its banks reach, except where it crosses into the next tile, which then
 * carries it too, so the dry shores of a basin may reach a tile with rivers.
 *
 * <p>A lake larger than a block stays dry, unless it lies in a depression of the coarse map ({@link GreatLake}).
 */
final class BlockLakes {
	static final BlockLakes EMPTY = new BlockLakes(null, null, null, null, null, 0);

	private static final int TILE = TerrainField.TILE;
	private static final int PAD = TerrainField.PAD;
	private static final int SPAN = TerrainField.SPAN;
	private static final int EDGE = CellBasins.EDGE;
	/** Side of the block, pixels. */
	static final int SIZE = 2 * TILE;
	/** Side of the window the block floods: the block and the padding of its tiles. */
	private static final int WINDOW = SIZE + 2 * PAD;
	/**
	 * Pixels around a crossing node that its river reaches: the straight part of the river, which runs
	 * {@link CellRivers#STRAIGHT} pixels into each tile, and the widest a river gets on either side of it.
	 */
	private static final int REACH_ALONG = (int) CellRivers.STRAIGHT;
	private static final int REACH_ACROSS = (int) Rivers.MAX_HALF_WIDTH;
	private static final int[] DI = {-1, -1, -1, 0, 0, 1, 1, 1};
	private static final int[] DJ = {-1, 0, 1, -1, 1, -1, 0, 1};

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

	private BlockLakes(int[] rowStart, short[] runFrom, short[] runTo, float[] runLevel, byte[] runKind, int lakes) {
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
	 * Finds the lakes of block {@code (bi, bj)} from the padded elevation of its tiles and the rivers crossing them,
	 * in the order {@code (bi, bj)}, {@code (bi, bj + 1)}, {@code (bi + 1, bj)}, {@code (bi + 1, bj + 1)}.
	 */
	static BlockLakes build(float[][] tiles, CellFlows[] flows, CellLakes.Climate climate, int bi, int bj) {
		float[] e = window(tiles);
		float[] fill = CellBasins.fill(e, WINDOW);
		float[] crossing = crossings(tiles, flows);
		boolean[] flowing = new boolean[4];
		for (int t = 0; t < 4; t++) {
			flowing[t] = !flows[t].isEmpty();
		}
		Painter painter = new Painter();
		int[] label = new int[WINDOW * WINDOW];
		int[] pixels = new int[WINDOW * WINDOW];
		int lakes = 0;
		for (int first = 0; first < fill.length; first++) {
			if (label[first] != 0 || !(fill[first] > e[first])) {
				continue;
			}
			// Gather the basin, as in CellBasins.
			int size = 0, low = first, tileOf = -1;
			boolean inside = true, ownTile = true;
			float river = Float.NaN;
			pixels[size++] = first;
			label[first] = 1;
			for (int head = 0; head < size; head++) {
				int k = pixels[head];
				int i = k / WINDOW - PAD, j = k % WINDOW - PAD;
				if (i < EDGE || j < EDGE || i >= SIZE - EDGE || j >= SIZE - EDGE) {
					inside = false;
				}
				int t = (i < TILE ? 0 : 2) + (j < TILE ? 0 : 1);
				int li = i - (t >> 1) * TILE, lj = j - (t & 1) * TILE;
				if (tileOf < 0) {
					tileOf = t;
				}
				if (t != tileOf || li < EDGE || lj < EDGE || li >= TILE - EDGE || lj >= TILE - EDGE) {
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
					int ni = k / WINDOW + DI[d], nj = k % WINDOW + DJ[d];
					if (ni < 0 || nj < 0 || ni >= WINDOW || nj >= WINDOW) {
						continue;
					}
					int n = ni * WINDOW + nj;
					if (label[n] == 0 && fill[n] > e[n]) {
						label[n] = 1;
						pixels[size++] = n;
					}
				}
			}
			float spill = fill[first], floor = e[low];
			if (!inside || ownTile || size < CellBasins.MIN_PIXELS || !(spill > 0f)
				|| spill - floor < CellBasins.MIN_DEPTH) {
				continue;
			}
			float water;
			boolean fed = !Float.isNaN(river);
			if (fed) {
				water = Math.min(river, spill - CellRivers.DROP);
			} else {
				int li = low / WINDOW - PAD, lj = low % WINDOW - PAD;
				float wetness = climate.wetness(bi * TILE + li, bj * TILE + lj);
				float share = Math.clamp((wetness - CellLakes.DRY) / (CellLakes.WET - CellLakes.DRY), 0f, 1f);
				water = floor + share * (spill - floor);
				// The tiles that see the water, where a river of their own could drain it.
				boolean[] reaches = new boolean[4];
				for (int n = 0; n < size; n++) {
					int k = pixels[n];
					if (!(e[k] < water)) {
						continue;
					}
					int i = k / WINDOW - PAD, j = k % WINDOW - PAD;
					for (int t = 0; t < 4; t++) {
						int ti = (t >> 1) * TILE, tj = (t & 1) * TILE;
						reaches[t] |= i >= ti - CellLakes.MARGIN && i < ti + TILE + CellLakes.MARGIN
							&& j >= tj - CellLakes.MARGIN && j < tj + TILE + CellLakes.MARGIN;
					}
				}
				boolean dry = true;
				for (int t = 0; t < 4; t++) {
					dry &= !(reaches[t] && flowing[t]);
				}
				if (!dry) {
					continue;
				}
			}
			if (water - floor < CellBasins.MIN_DEPTH) {
				continue;
			}
			for (int n = 0; n < size; n++) {
				int k = pixels[n];
				int i = k / WINDOW - PAD, j = k % WINDOW - PAD;
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
	 * Paints the lakes into tile {@code (oi, oj)} of the block, 0 or 1 each way. {@code level} and {@code fed} cover
	 * tile-local rows and columns {@code -margin} to {@code TILE + margin}; a pixel takes the highest water over it,
	 * and is fed where any lake at that level is. {@code ground}, in the frame of the padded tile, takes the ground
	 * that a river sees around a fed lake, where that is higher. Either may be null.
	 */
	void paint(int oi, int oj, int margin, float[] level, boolean[] fed, float[] ground) {
		if (rowStart == null) {
			return;
		}
		int side = TILE + 2 * margin;
		int i0 = oi * TILE, j0 = oj * TILE;
		for (int i = Math.max(0, i0 - PAD); i < Math.min(SIZE, i0 + TILE + PAD); i++) {
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

	/** The padded elevation of the block's window, from its four padded tiles. */
	private static float[] window(float[][] tiles) {
		float[] e = new float[WINDOW * WINDOW];
		for (int wi = 0; wi < WINDOW; wi++) {
			int i = wi - PAD;
			int a = i < TILE ? 0 : 1;
			for (int b = 0; b < 2; b++) {
				// Columns from the left tile up to the middle of the block, and from the right one after.
				int from = b == 0 ? 0 : TILE + PAD, to = b == 0 ? TILE + PAD : WINDOW;
				float[] tile = tiles[2 * a + b];
				System.arraycopy(tile, (i - a * TILE + PAD) * SPAN + from - b * TILE, e, wi * WINDOW + from,
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
	private static float[] crossings(float[][] tiles, CellFlows[] flows) {
		float[] crossing = new float[WINDOW * WINDOW];
		Arrays.fill(crossing, Float.NaN);
		// The east sides of the two western tiles, and the south sides of the two northern ones.
		int[][] sides = {{0, 3}, {2, 3}, {0, 1}, {1, 1}};
		for (int[] s : sides) {
			int t = s[0], side = s[1];
			float in = flows[t].inLevel[side], out = flows[t].outLevel[side];
			if (Float.isNaN(in) && Float.isNaN(out)) {
				continue;
			}
			float level = Float.isNaN(in) ? out : Float.isNaN(out) ? in : Math.min(in, out);
			int node = CellRivers.node(tiles[t], side);
			int i0 = (t >> 1) * TILE, j0 = (t & 1) * TILE;
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
					int k = (i + PAD) * WINDOW + j + PAD;
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
		/** Per row: the pixels painted, as column, kind and level. */
		private final int[][] cols = new int[SIZE][];
		private final byte[][] kinds = new byte[SIZE][];
		private final float[][] levels = new float[SIZE][];
		private final int[] counts = new int[SIZE];

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
			int[] rowStart = new int[SIZE + 1];
			short[] from = new short[64], to = new short[64];
			float[] level = new float[64];
			byte[] kind = new byte[64];
			int r = 0;
			long[] order = new long[16];
			for (int i = 0; i < SIZE; i++) {
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
			rowStart[SIZE] = r;
			return new BlockLakes(rowStart, Arrays.copyOf(from, r), Arrays.copyOf(to, r), Arrays.copyOf(level, r),
				Arrays.copyOf(kind, r), lakes);
		}
	}
}
