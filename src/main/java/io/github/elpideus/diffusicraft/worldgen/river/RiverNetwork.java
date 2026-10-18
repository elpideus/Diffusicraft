package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.TerrainSource;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The drainage network of a world on the coarse map, one cell per coarse pixel (7.68 km).
 *
 * <p>Water leaves every land cell towards its steepest lower neighbour (D8), going by the cell's 5th-percentile
 * elevation, which follows the valley floors. Cells with no lower neighbour are breached: a priority flood within
 * {@value #BREACH_RADIUS} cells finds the cheapest way to a lower cell, and the cells on that path are lowered just
 * enough to drain through it. What a pit's path does depends only on the map within that radius, so every part of
 * the network can be computed on its own, in any order, on any thread, and comes out the same. Pits with no way out
 * stay: their rivers end in a pond.
 *
 * <p>The discharge of a cell is the runoff of every cell upstream of it, summed exactly. Runoff is the precipitation
 * less a simple evapotranspiration that grows with temperature. A cell carries a river when its discharge reaches
 * {@value #MIN_DISCHARGE} m³/s. Rivers cross from cell to cell through the four sides only: a diagonal step goes
 * through the lower of the two cells beside it.
 */
final class RiverNetwork {
	/** Cells per side of a region, the unit in which the network is computed and cached. */
	static final int REGION = 32;
	/** How far, in cells, a pit may look for a way out. */
	static final int BREACH_RADIUS = 8;
	/** The smallest discharge that makes a river, m³/s. */
	static final float MIN_DISCHARGE = 4f;
	/** Flow direction of an ocean cell, where rivers end. */
	static final int OCEAN = -2;
	/** Flow direction of a pit that could not be breached. */
	static final int PIT = -1;
	/** Neighbour offsets: the four sides (north, south, west, east, as {@link CellFlows} numbers them), then corners. */
	static final int[] DI = {-1, 1, 0, 0, -1, -1, 1, 1};
	static final int[] DJ = {0, 0, -1, 1, -1, 1, -1, 1};

	/** Window around a region holding every pit that can lower a cell next to it, and everything those pits see. */
	private static final int MARGIN = 2 * BREACH_RADIUS + 2;
	private static final double CELL_METERS = TerrainField.TILE * WorldScale.METERS_PER_PIXEL;
	private static final double SECONDS_PER_YEAR = 3.15576e7;

	private final TerrainField field;
	private final Map<Long, CompletableFuture<float[]>> data = new ConcurrentHashMap<>();
	private final Map<Long, CompletableFuture<Region>> regions = new ConcurrentHashMap<>();

	RiverNetwork(TerrainField field) {
		if (field.coarseScale() != TerrainField.TILE) {
			throw new IllegalArgumentException("Rivers need one coarse pixel per tile, not " + field.coarseScale());
		}
		this.field = field;
	}

	/** Annual evapotranspiration in mm at an annual mean temperature in °C. */
	static float evapotranspiration(float temperature) {
		return Math.max(0f, 300f + 25f * temperature);
	}

	/** Runoff of a cell in m³/s, from its annual mean temperature (°C) and precipitation (mm). */
	static float runoff(float temperature, float precipitation) {
		double meters = Math.max(0.0, precipitation - evapotranspiration(temperature)) / 1000.0;
		return (float) (meters * CELL_METERS * CELL_METERS / SECONDS_PER_YEAR);
	}

	/** Flow direction of a cell: an index into {@link #DI}, {@link #OCEAN} or {@link #PIT}. */
	int direction(int ci, int cj) {
		Region r = region(ci, cj);
		return r.direction[r.index(ci, cj)];
	}

	/** The cell's 5th-percentile elevation after breaching, in meters. */
	float level(int ci, int cj) {
		Region r = region(ci, cj);
		return r.level[r.index(ci, cj)];
	}

	boolean ocean(int ci, int cj) {
		return direction(ci, cj) == OCEAN;
	}

	/** Discharge out of a cell in m³/s: its runoff and everything upstream. */
	float discharge(int ci, int cj) {
		Region r = region(ci, cj);
		float known = r.discharge[r.index(ci, cj)];
		if (!Float.isNaN(known)) {
			return known;
		}
		// Depth-first over the upstream cells, without recursion: basins can hold thousands of cells. Values are
		// memoised in the regions; two threads may compute the same cell, and they agree.
		long[] stack = new long[64];
		int top = 0;
		stack[top++] = key(ci, cj);
		while (top > 0) {
			long k = stack[top - 1];
			int i = (int) (k >> 32), j = (int) k;
			Region cell = region(i, j);
			int index = cell.index(i, j);
			if (!Float.isNaN(cell.discharge[index])) {
				top--;
				continue;
			}
			double sum = cell.runoff[index];
			boolean ready = true;
			for (int n = 0; n < 8; n++) {
				int ui = i + DI[n], uj = j + DJ[n];
				if (flowsInto(ui, uj, i, j)) {
					Region up = region(ui, uj);
					float q = up.discharge[up.index(ui, uj)];
					if (Float.isNaN(q)) {
						if (top == stack.length) {
							stack = Arrays.copyOf(stack, top * 2);
						}
						stack[top++] = key(ui, uj);
						ready = false;
					} else {
						sum += q;
					}
				}
			}
			if (ready) {
				cell.discharge[index] = (float) sum;
				top--;
			}
		}
		return r.discharge[r.index(ci, cj)];
	}

	/** Whether cell {@code (ui, uj)} drains straight into its neighbour {@code (i, j)}. */
	private boolean flowsInto(int ui, int uj, int i, int j) {
		int d = direction(ui, uj);
		return d >= 0 && ui + DI[d] == i && uj + DJ[d] == j;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Crossings

	/** One crossing of a river from a cell into its side neighbour. */
	private record Leg(int fromI, int fromJ, int toI, int toJ, float level, float discharge, int continueSide) {
	}

	/**
	 * Where rivers enter and leave cell {@code (ci, cj)}, with their levels and discharges. Both cells next to a side
	 * see the same crossings on it, so the rivers meet there.
	 */
	CellFlows flows(int ci, int cj) {
		List<Leg> legs = new ArrayList<>();
		for (int di = -1; di <= 1; di++) {
			for (int dj = -1; dj <= 1; dj++) {
				legsOf(ci + di, cj + dj, ci, cj, legs);
			}
		}
		if (legs.isEmpty()) {
			return CellFlows.NONE;
		}
		CellFlows flows = new CellFlows();
		int direction = direction(ci, cj);
		int ownSide = direction >= 0 ? firstSide(ci, cj, direction) : -1;
		boolean finalInflow = false;
		for (Leg leg : legs) {
			if (leg.fromI == ci && leg.fromJ == cj) {
				int side = side(leg.toI - ci, leg.toJ - cj);
				flows.addOut(side, leg.level, leg.discharge);
			} else if (leg.toI == ci && leg.toJ == cj) {
				int side = side(leg.fromI - ci, leg.fromJ - cj);
				int target;
				if (direction == OCEAN) {
					target = CellFlows.SEA;
				} else if (leg.continueSide >= 0) {
					target = leg.continueSide;
				} else {
					finalInflow = true;
					target = direction == PIT ? CellFlows.SINK : ownSide;
				}
				flows.addIn(side, target, leg.level, leg.discharge);
			}
		}
		if (ownSide >= 0 && !finalInflow && flows.outDischarge[ownSide] > 0f) {
			// A river starts here: towards the largest stream that feeds the cell, or in the middle.
			float best = 0f;
			for (int n = 0; n < 8; n++) {
				int ui = ci + DI[n], uj = cj + DJ[n];
				if (flowsInto(ui, uj, ci, cj)) {
					float q = discharge(ui, uj);
					if (q > best) {
						best = q;
						flows.sourceDi = DI[n];
						flows.sourceDj = DJ[n];
					}
				}
			}
			flows.source = ownSide;
		}
		return flows;
	}

	/** The side through which a cell's flow leaves it: its direction, or the lower cell beside a diagonal. */
	private int firstSide(int ci, int cj, int direction) {
		if (direction < 4) {
			return direction;
		}
		return lowerIntermediateIsVertical(ci, cj, direction) ? side(DI[direction], 0) : side(0, DJ[direction]);
	}

	private boolean lowerIntermediateIsVertical(int ci, int cj, int direction) {
		return level(ci + DI[direction], cj) <= level(ci, cj + DJ[direction]);
	}

	/** Adds the legs of cell {@code (ci, cj)}'s river that touch cell {@code (xi, xj)}. */
	private void legsOf(int ci, int cj, int xi, int xj, List<Leg> out) {
		int direction = direction(ci, cj);
		if (direction < 0) {
			return;
		}
		float q = discharge(ci, cj);
		if (q < MIN_DISCHARGE) {
			return;
		}
		int di = ci + DI[direction], dj = cj + DJ[direction];
		float from = level(ci, cj), to = level(di, dj);
		if (direction < 4) {
			add(out, xi, xj, new Leg(ci, cj, di, dj, Math.max(0f, 0.5f * (from + to)), q, -1));
			return;
		}
		boolean vertical = lowerIntermediateIsVertical(ci, cj, direction);
		int mi = vertical ? di : ci, mj = vertical ? cj : dj;
		float drop = from - to;
		int onward = side(di - mi, dj - mj);
		add(out, xi, xj, new Leg(ci, cj, mi, mj, Math.max(0f, from - drop / 3f), q, ocean(mi, mj) ? -1 : onward));
		if (!ocean(mi, mj)) {
			add(out, xi, xj, new Leg(mi, mj, di, dj, Math.max(0f, from - 2f * drop / 3f), q, -1));
		}
	}

	private static void add(List<Leg> out, int xi, int xj, Leg leg) {
		if (leg.fromI == xi && leg.fromJ == xj || leg.toI == xi && leg.toJ == xj) {
			out.add(leg);
		}
	}

	/** The side number of a unit step. */
	static int side(int di, int dj) {
		return di < 0 ? 0 : di > 0 ? 1 : dj < 0 ? 2 : 3;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Regions

	private Region region(int ci, int cj) {
		int ri = Math.floorDiv(ci, REGION), rj = Math.floorDiv(cj, REGION);
		return once(regions, key(ri, rj), () -> computeRegion(ri, rj));
	}

	/** Mean elevation, 5th percentile and runoff of a block of {@value #REGION}² cells. */
	private float[] data(int bi, int bj) {
		return once(data, key(bi, bj), () -> {
			int n = REGION * REGION, ci0 = bi * REGION, cj0 = bj * REGION;
			float[] coarse = field.coarse(ci0, cj0, ci0 + REGION, cj0 + REGION);
			float[] out = new float[3 * n];
			for (int k = 0; k < n; k++) {
				out[k] = coarse[TerrainSource.COARSE_ELEVATION * n + k];
				out[n + k] = coarse[TerrainSource.COARSE_LOW_ELEVATION * n + k];
				out[2 * n + k] = runoff(coarse[TerrainSource.COARSE_TEMPERATURE * n + k],
					coarse[TerrainSource.COARSE_PRECIPITATION * n + k]);
			}
			return out;
		});
	}

	private Region computeRegion(int ri, int rj) {
		int size = REGION + 2 * MARGIN, n = REGION * REGION;
		int wi0 = ri * REGION - MARGIN, wj0 = rj * REGION - MARGIN;
		float[] mean = new float[size * size], low = new float[size * size];
		for (int bi = Math.floorDiv(wi0, REGION); bi <= Math.floorDiv(wi0 + size - 1, REGION); bi++) {
			for (int bj = Math.floorDiv(wj0, REGION); bj <= Math.floorDiv(wj0 + size - 1, REGION); bj++) {
				float[] block = data(bi, bj);
				int a0 = Math.max(0, bi * REGION - wi0), a1 = Math.min(size, (bi + 1) * REGION - wi0);
				int b0 = Math.max(0, bj * REGION - wj0), b1 = Math.min(size, (bj + 1) * REGION - wj0);
				for (int a = a0; a < a1; a++) {
					int src = (a + wi0 - bi * REGION) * REGION + (b0 + wj0 - bj * REGION);
					System.arraycopy(block, src, mean, a * size + b0, b1 - b0);
					System.arraycopy(block, n + src, low, a * size + b0, b1 - b0);
				}
			}
		}

		double[] z = new double[size * size];
		for (int k = 0; k < z.length; k++) {
			z[k] = low[k];
		}
		// Every pit that can lower a cell of the region or one next to it, in a fixed order.
		int lo = MARGIN - BREACH_RADIUS - 1, hi = MARGIN + REGION + BREACH_RADIUS + 1;
		for (int a = lo; a < hi; a++) {
			for (int b = lo; b < hi; b++) {
				if (mean[a * size + b] > 0f && isPit(low, size, a, b)) {
					breach(mean, low, z, size, a, b);
				}
			}
		}

		Region region = new Region(ri, rj);
		float[] own = data(ri, rj);
		for (int a = 0; a < REGION; a++) {
			for (int b = 0; b < REGION; b++) {
				int w = (a + MARGIN) * size + b + MARGIN, k = a * REGION + b;
				region.level[k] = (float) z[w];
				region.runoff[k] = own[2 * n + k];
				if (mean[w] <= 0f) {
					region.direction[k] = OCEAN;
					continue;
				}
				int best = PIT;
				double bestDrop = 0.0;
				for (int d = 0; d < 8; d++) {
					double drop = (z[w] - z[w + DI[d] * size + DJ[d]]) / (d < 4 ? 1.0 : Math.sqrt(2.0));
					if (drop > bestDrop) {
						bestDrop = drop;
						best = d;
					}
				}
				region.direction[k] = (byte) best;
			}
		}
		return region;
	}

	private static boolean isPit(float[] low, int size, int a, int b) {
		float h = low[a * size + b];
		for (int d = 0; d < 8; d++) {
			if (low[(a + DI[d]) * size + b + DJ[d]] < h) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Floods out from the pit at window cell {@code (pa, pb)} by 5th-percentile elevation until it reaches a lower
	 * cell or the sea, then lowers the cells on the way so that they drain the pit. Gives up past the breach radius.
	 */
	private static void breach(float[] mean, float[] low, double[] z, int size, int pa, int pb) {
		int r = BREACH_RADIUS, side = 2 * r + 1, centre = r * side + r;
		float pit = low[pa * size + pb];
		int[] parent = new int[side * side];
		Arrays.fill(parent, -2);
		parent[centre] = -1;
		LongHeap heap = new LongHeap(64);
		heap.push(LongHeap.key(pit, centre));
		int end = -1;
		while (!heap.isEmpty()) {
			int k = (int) heap.pop();
			int u = k / side, v = k % side;
			int w = (pa - r + u) * size + pb - r + v;
			if (k != centre && (low[w] < pit || mean[w] <= 0f)) {
				end = k;
				break;
			}
			for (int d = 0; d < 8; d++) {
				int nu = u + DI[d], nv = v + DJ[d];
				if (nu >= 0 && nu < side && nv >= 0 && nv < side && parent[nu * side + nv] == -2) {
					parent[nu * side + nv] = k;
					heap.push(LongHeap.key(low[(pa - r + nu) * size + pb - r + nv], nu * side + nv));
				}
			}
		}
		if (end < 0) {
			return;
		}
		int steps = 0;
		for (int k = parent[end]; k != centre; k = parent[k]) {
			steps++;
		}
		int endW = (pa - r + end / side) * size + pb - r + end % side;
		double target = low[endW];
		if (mean[endW] <= 0f) {
			target = Math.min(target, pit - 1.0);
			z[endW] = Math.min(z[endW], target);
		}
		// Cells on the path, from the one before the end back to the one after the pit.
		int n = steps + 1, step = steps;
		for (int k = parent[end]; k != centre; k = parent[k], step--) {
			int w = (pa - r + k / side) * size + pb - r + k % side;
			z[w] = Math.min(z[w], pit - (pit - target) * step / n);
		}
	}

	private static final class Region {
		final int i0;
		final int j0;
		final byte[] direction = new byte[REGION * REGION];
		final float[] level = new float[REGION * REGION];
		final float[] runoff = new float[REGION * REGION];
		final float[] discharge = new float[REGION * REGION];

		Region(int ri, int rj) {
			this.i0 = ri * REGION;
			this.j0 = rj * REGION;
			Arrays.fill(discharge, Float.NaN);
		}

		int index(int ci, int cj) {
			return (ci - i0) * REGION + (cj - j0);
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Helpers

	static long key(int i, int j) {
		return ((long) i << 32) | (j & 0xffffffffL);
	}

	/** The value under {@code key}, computed once by the first thread that asks; the others wait for it. */
	static <T> T once(Map<Long, CompletableFuture<T>> map, long key, Supplier<T> compute) {
		CompletableFuture<T> future = map.get(key);
		if (future == null) {
			CompletableFuture<T> mine = new CompletableFuture<>();
			future = map.putIfAbsent(key, mine);
			if (future == null) {
				future = mine;
				try {
					mine.complete(compute.get());
				} catch (Throwable t) {
					map.remove(key, mine);
					mine.completeExceptionally(t);
				}
			}
		}
		try {
			return future.join();
		} catch (CompletionException e) {
			if (e.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (e.getCause() instanceof Error error) {
				throw error;
			}
			throw e;
		}
	}
}
