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
 * elevation, which follows the valley floors. Where that leaves a pit, there are two ways out.
 *
 * <p>A depression that the water flowing into it can fill becomes a lake: the cells joined to the pit below the level
 * at which it spills, if there are at most {@value #MAX_LAKE_CELLS} of them within {@value #MAX_LAKE_SPAN} cells
 * either way, it is at least {@value #MIN_LAKE_DEPTH} m deep and the runoff of the cells draining into it outweighs
 * what its surface loses to evaporation. A lake's cells take its level, the water crosses it towards its outlet, and
 * leaves over the cell it spills into, which is lowered with the cells after it until the water reaches lower ground,
 * the sea or another lake. Of nested depressions the outermost that holds a lake wins, so lakes never touch.
 *
 * <p>Any other pit is breached: a priority flood within {@value #BREACH_RADIUS} cells finds the cheapest way to a
 * lower cell, and the cells on that path are lowered just enough to drain through it. What a pit or a lake does
 * depends only on the map within a few radii, so every part of the network can be computed on its own, in any order,
 * on any thread, and comes out the same. Pits with no way out stay: their rivers end in a pond.
 *
 * <p>Water that reaches the sea is at sea level, so the way out of a lake or a pit falls to the sea, not to the floor
 * of the sea or of a fjord, and a land cell whose 5th percentile lies below the sea is never a pit: a twentieth of it
 * is sea, on a coast or in a fjord the ice drowned (see Glaciation), and the rivers that reach it end there
 * ({@link #MOUTH}), unless its steepest descent leads straight into the ocean. Otherwise a fjord far up a valley would
 * be breached to the coast, and the rivers would run on at sea level through the mountains between.
 *
 * <p>The discharge of a cell is the runoff of every cell upstream of it, summed exactly. Runoff is the precipitation
 * less a simple evapotranspiration that grows with temperature. A cell carries a river when its discharge reaches
 * {@value #MIN_DISCHARGE} m³/s. Rivers cross from cell to cell through the four sides only: a diagonal step goes
 * through the lower of the two cells beside it, or through the one in the lake it leaves or enters. Rivers in a lake
 * run at the level of its water ({@link GreatLake}).
 */
final class RiverNetwork {
	/** Cells per side of a region, the unit in which the network is computed and cached. */
	static final int REGION = 32;
	/** How far, in cells, a pit may look for a way out. */
	static final int BREACH_RADIUS = 8;
	/** The smallest discharge that makes a river, m³/s. */
	static final float MIN_DISCHARGE = 4f;
	/** Most cells a lake covers; larger depressions are breached. */
	static final int MAX_LAKE_CELLS = 16;
	/** Most cells a lake reaches across, along either axis. */
	static final int MAX_LAKE_SPAN = 8;
	/** Shallowest depression that holds a lake, from its pit to where it spills, in meters. */
	static final float MIN_LAKE_DEPTH = 2f;
	/** Flow direction of an ocean cell, where rivers end. */
	static final int OCEAN = -2;
	/** Flow direction of a pit that could not be breached. */
	static final int PIT = -1;
	/** Flow direction of a land cell whose 5th percentile lies below the sea, where rivers end in its sea. */
	static final int MOUTH = -3;
	/** Neighbour offsets: the four sides (north, south, west, east, as {@link CellFlows} numbers them), then corners. */
	static final int[] DI = {-1, 1, 0, 0, -1, -1, 1, 1};
	static final int[] DJ = {0, 0, -1, 1, -1, 1, -1, 1};
	/** The neighbour offset pointing back. */
	private static final int[] OPPOSITE = {1, 0, 3, 2, 7, 6, 5, 4};

	/**
	 * Window around a region holding everything its network depends on. The flow of the cells next to the region
	 * depends on the pits within a radius of them, which flood a radius further; their levels on the ways out of the
	 * lakes within a radius of those; and a way out on which cells, up to a radius from the lake's pit, lie in other
	 * lakes, found by the pits a radius around them, which look a radius and a cell further.
	 */
	private static final int MARGIN = 6 * BREACH_RADIUS + 2;
	/** How much more than the land around it open water evaporates. */
	private static final double OPEN_WATER = 1.3;
	private static final double CELL_METERS = TerrainField.TILE * WorldScale.METERS_PER_PIXEL;
	private static final double SECONDS_PER_YEAR = 3.15576e7;
	private static final double DIAGONAL = Math.sqrt(2.0);
	private static final Lake[] NO_LAKES = {};

	private final TerrainField field;
	private final Map<Long, CompletableFuture<float[]>> data = new ConcurrentHashMap<>();
	private final Map<Long, CompletableFuture<Region>> regions = new ConcurrentHashMap<>();
	private final Map<Long, CompletableFuture<GreatLake>> greatLakes = new ConcurrentHashMap<>();

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

	/** What a lake covering a cell loses to evaporation beyond the rain falling on it, in m³/s. */
	static float evaporation(float temperature, float precipitation) {
		double meters = Math.max(0.0, OPEN_WATER * evapotranspiration(temperature) - Math.max(0f, precipitation));
		return (float) (meters / 1000.0 * CELL_METERS * CELL_METERS / SECONDS_PER_YEAR);
	}

	/** Flow direction of a cell: an index into {@link #DI}, {@link #OCEAN}, {@link #MOUTH} or {@link #PIT}. */
	int direction(int ci, int cj) {
		Region r = region(ci, cj);
		return r.direction[r.index(ci, cj)];
	}

	/**
	 * The cell's 5th-percentile elevation after breaching, in meters: the level a lake spills at on its cells, and
	 * lower along the ways out of lakes and pits.
	 */
	float level(int ci, int cj) {
		Region r = region(ci, cj);
		return r.level[r.index(ci, cj)];
	}

	boolean ocean(int ci, int cj) {
		return direction(ci, cj) == OCEAN;
	}

	/** Whether rivers end in cell {@code (ci, cj)}: the ocean, or the sea of a {@link #MOUTH}. */
	private boolean endsRivers(int ci, int cj) {
		int direction = direction(ci, cj);
		return direction == OCEAN || direction == MOUTH;
	}

	/** The lake on cell {@code (ci, cj)}, or null. */
	Lake lakeAt(int ci, int cj) {
		Region r = region(ci, cj);
		int lake = r.lake[r.index(ci, cj)];
		return lake < 0 ? null : r.lakes[lake];
	}

	/** The water of a lake on the fine terrain, found once for every tile that asks. */
	GreatLake greatLake(Lake lake) {
		return once(greatLakes, lake.id(), () -> {
			float lowest = Math.max(0f, level(lake.exitI(), lake.exitJ()));
			return GreatLake.build(field, lake, lowest);
		});
	}

	/** The lakes with a cell in tile {@code (ti, tj)} or around it, which can reach into its padding. */
	List<GreatLake> greatLakesAround(int ti, int tj) {
		List<GreatLake> out = new ArrayList<>(0);
		List<Long> seen = new ArrayList<>(0);
		for (int di = -1; di <= 1; di++) {
			for (int dj = -1; dj <= 1; dj++) {
				Lake lake = lakeAt(ti + di, tj + dj);
				if (lake != null && !seen.contains(lake.id())) {
					seen.add(lake.id());
					out.add(greatLake(lake));
				}
			}
		}
		return out;
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

	/**
	 * A lake of the coarse map: the cells, given as pairs {@code (i, j)}, joined to a pit below the level at which it
	 * spills. Its water leaves through cell {@code out} into cell {@code exit}, over which it spills. {@code id} is the
	 * pit, the same for every region that sees the lake.
	 */
	record Lake(long id, float level, int[] cells, int outI, int outJ, int exitI, int exitJ) {
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
				if (direction == OCEAN || direction == MOUTH) {
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

	/** The side through which a cell's flow leaves it: its direction, or the cell beside a diagonal it crosses. */
	private int firstSide(int ci, int cj, int direction) {
		if (direction < 4) {
			return direction;
		}
		return crossesVertically(ci, cj, direction) ? side(DI[direction], 0) : side(0, DJ[direction]);
	}

	/**
	 * Whether a diagonal step crosses the cell beside it along {@code i} rather than along {@code j}: the one in the
	 * lake the step leaves or enters, else one in no lake, else the lower.
	 */
	private boolean crossesVertically(int ci, int cj, int direction) {
		int vi = ci + DI[direction], hj = cj + DJ[direction];
		Lake vertical = lakeAt(vi, cj), horizontal = lakeAt(ci, hj);
		if (vertical != null || horizontal != null) {
			for (Lake own : new Lake[]{lakeAt(ci, cj), lakeAt(vi, hj)}) {
				if (own != null && same(vertical, own) != same(horizontal, own)) {
					return same(vertical, own);
				}
			}
			if ((vertical == null) != (horizontal == null)) {
				return vertical == null;
			}
		}
		return level(vi, cj) <= level(ci, hj);
	}

	private static boolean same(Lake a, Lake b) {
		return a != null && b != null && a.id() == b.id();
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
		Lake source = lakeAt(ci, cj), target = lakeAt(di, dj);
		if (direction < 4) {
			if (touches(ci, cj, di, dj, xi, xj)) {
				float level = water(source, target, ci, cj, di, dj, Math.max(0f, 0.5f * (from + to)));
				out.add(new Leg(ci, cj, di, dj, level, q, -1));
			}
			return;
		}
		boolean vertical = crossesVertically(ci, cj, direction);
		int mi = vertical ? di : ci, mj = vertical ? cj : dj;
		float drop = from - to;
		boolean sea = endsRivers(mi, mj);
		if (touches(ci, cj, mi, mj, xi, xj)) {
			float level = water(source, target, ci, cj, mi, mj, Math.max(0f, from - drop / 3f));
			out.add(new Leg(ci, cj, mi, mj, level, q, sea ? -1 : side(di - mi, dj - mj)));
		}
		if (!sea && touches(mi, mj, di, dj, xi, xj)) {
			float level = water(source, target, mi, mj, di, dj, Math.max(0f, from - 2f * drop / 3f));
			out.add(new Leg(mi, mj, di, dj, level, q, -1));
		}
	}

	/**
	 * The level of a leg from cell {@code a} to cell {@code b} of a river stepping from a cell in lake {@code source}
	 * to one in lake {@code target}, either of which may be null: the level of the lake's water across a lake and out
	 * of it, so that the river leaves it level with its water, and no lower than the water into one. The water of a
	 * lake is never lower than the cell it spills into, and a cell flows into a lake only from above its level, so the
	 * river never climbs.
	 */
	private float water(Lake source, Lake target, int ai, int aj, int bi, int bj, float level) {
		if (source != null) {
			return greatLake(source).water();
		}
		Lake a = lakeAt(ai, aj);
		if (a != null && same(a, lakeAt(bi, bj))) {
			return greatLake(a).water();
		}
		return target == null ? level : Math.max(level, greatLake(target).water());
	}

	private static boolean touches(int ai, int aj, int bi, int bj, int xi, int xj) {
		return ai == xi && aj == xj || bi == xi && bj == xj;
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

	/** Mean elevation, 5th percentile, runoff and evaporation from a lake of a block of {@value #REGION}² cells. */
	private float[] data(int bi, int bj) {
		return once(data, key(bi, bj), () -> {
			int n = REGION * REGION, ci0 = bi * REGION, cj0 = bj * REGION;
			float[] coarse = field.coarse(ci0, cj0, ci0 + REGION, cj0 + REGION);
			float[] out = new float[Window.CHANNELS * n];
			for (int k = 0; k < n; k++) {
				float temperature = coarse[TerrainSource.COARSE_TEMPERATURE * n + k];
				float precipitation = coarse[TerrainSource.COARSE_PRECIPITATION * n + k];
				out[k] = coarse[TerrainSource.COARSE_ELEVATION * n + k];
				out[n + k] = coarse[TerrainSource.COARSE_LOW_ELEVATION * n + k];
				out[2 * n + k] = runoff(temperature, precipitation);
				out[3 * n + k] = evaporation(temperature, precipitation);
			}
			return out;
		});
	}

	private Region computeRegion(int ri, int rj) {
		Window window = new Window(ri * REGION - MARGIN, rj * REGION - MARGIN, REGION + 2 * MARGIN);
		int size = window.size, n = REGION * REGION;
		for (int bi = Math.floorDiv(window.i0, REGION); bi <= Math.floorDiv(window.i0 + size - 1, REGION); bi++) {
			for (int bj = Math.floorDiv(window.j0, REGION); bj <= Math.floorDiv(window.j0 + size - 1, REGION); bj++) {
				float[] block = data(bi, bj);
				int a0 = Math.max(0, bi * REGION - window.i0), a1 = Math.min(size, (bi + 1) * REGION - window.i0);
				int b0 = Math.max(0, bj * REGION - window.j0), b1 = Math.min(size, (bj + 1) * REGION - window.j0);
				for (int a = a0; a < a1; a++) {
					int src = (a + window.i0 - bi * REGION) * REGION + (b0 + window.j0 - bj * REGION);
					for (int c = 0; c < Window.CHANNELS; c++) {
						System.arraycopy(block, c * n + src, window.channel(c), a * size + b0, b1 - b0);
					}
				}
			}
		}
		window.findLakes();
		window.lowerOutlets();
		window.breachPits();
		return window.region(ri, rj);
	}

	private static final class Region {
		final int i0;
		final int j0;
		final byte[] direction = new byte[REGION * REGION];
		final float[] level = new float[REGION * REGION];
		final float[] runoff = new float[REGION * REGION];
		final float[] discharge = new float[REGION * REGION];
		/** The lake on each cell, as an index into {@link #lakes}, or -1. */
		final short[] lake = new short[REGION * REGION];
		Lake[] lakes = NO_LAKES;

		Region(int ri, int rj) {
			this.i0 = ri * REGION;
			this.j0 = rj * REGION;
			Arrays.fill(discharge, Float.NaN);
			Arrays.fill(lake, (short) -1);
		}

		int index(int ci, int cj) {
			return (ci - i0) * REGION + (cj - j0);
		}
	}

	/** A depression of the map: the cells joined to a pit below the level at which it spills, and its way out. */
	private static final class Depression {
		/** The pit, the cells and the path from the pit to where the flood found lower ground, as window indices. */
		final int pit;
		final int[] cells;
		final int[] path;
		final float level;
		/** The lowest 5th percentile of the cells. */
		final float floor;
		/** Where the water leaves a lake: its last cell and the one it spills into, as window indices. */
		int out = -1;
		int exit = -1;

		Depression(int pit, int[] cells, int[] path, float level, float floor) {
			this.pit = pit;
			this.cells = cells;
			this.path = path;
			this.level = level;
			this.floor = floor;
		}
	}

	/**
	 * The map around a region, {@link #MARGIN} cells each way, in which its lakes are found and its pits breached.
	 * Cells are numbered by rows, which orders them as the world does, so every region takes them in the same order.
	 */
	private static final class Window {
		static final int CHANNELS = 4;

		final int i0;
		final int j0;
		final int size;
		final float[] mean;
		final float[] low;
		final float[] runoff;
		final float[] evaporation;
		/** Level of each cell: the 5th percentile, raised on lakes and lowered along the ways out. */
		double[] z;
		/** The lake on each cell, as an index into {@link #lakes}, or -1. */
		final int[] lake;
		final List<Depression> lakes = new ArrayList<>();

		Window(int i0, int j0, int size) {
			this.i0 = i0;
			this.j0 = j0;
			this.size = size;
			int cells = size * size;
			mean = new float[cells];
			low = new float[cells];
			runoff = new float[cells];
			evaporation = new float[cells];
			lake = new int[cells];
			Arrays.fill(lake, -1);
		}

		float[] channel(int c) {
			return switch (c) {
				case 0 -> mean;
				case 1 -> low;
				case 2 -> runoff;
				default -> evaporation;
			};
		}

		/**
		 * Finds the depressions of every pit within five radii and a cell of the region that hold a lake, and keeps the
		 * outermost: those whose pit no larger one covers. Each lake is found from the first of its pits.
		 */
		void findLakes() {
			int r = BREACH_RADIUS, lo = MARGIN - 5 * r - 1, hi = MARGIN + REGION + 5 * r + 1;
			List<Depression> found = new ArrayList<>();
			float[] fill = new float[size * size];
			Arrays.fill(fill, Float.NaN);
			for (int a = lo; a < hi; a++) {
				for (int b = lo; b < hi; b++) {
					if (mean[a * size + b] > 0f && low[a * size + b] > 0f && isPit(low, size, a, b)) {
						Depression d = depression(a, b);
						if (d != null) {
							found.add(d);
							for (int c : d.cells) {
								if (!(fill[c] >= d.level)) {
									fill[c] = d.level;
								}
							}
						}
					}
				}
			}
			z = new double[size * size];
			for (int k = 0; k < z.length; k++) {
				z[k] = low[k];
			}
			for (Depression d : found) {
				if (fill[d.pit] == d.level && lake[d.pit] < 0) {
					for (int c : d.cells) {
						lake[c] = lakes.size();
						z[c] = d.level;
					}
					lakes.add(d);
				}
			}
		}

		/**
		 * The depression of the pit at window cell {@code (pa, pb)}, if it holds a lake, else null. A priority flood
		 * within the breach radius finds the level at which the pit spills to lower ground or the sea; the cells below
		 * that level joined to the pit must make a lake small enough, and the cells of the radius whose steepest
		 * descent leads into them must bring more water than it loses to evaporation.
		 */
		private Depression depression(int pa, int pb) {
			int r = BREACH_RADIUS, side = 2 * r + 1, centre = r * side + r, origin = (pa - r) * size + pb - r;
			float pit = low[pa * size + pb];
			int[] parent = new int[side * side];
			Arrays.fill(parent, -2);
			parent[centre] = -1;
			LongHeap heap = new LongHeap(64);
			heap.push(LongHeap.key(pit, centre));
			int end = -1;
			float spill = pit;
			while (!heap.isEmpty()) {
				int k = (int) heap.pop();
				int u = k / side, v = k % side, w = origin + u * size + v;
				if (k != centre && (low[w] < pit || mean[w] <= 0f)) {
					end = k;
					break;
				}
				spill = Math.max(spill, low[w]);
				for (int d = 0; d < 8; d++) {
					int nu = u + DI[d], nv = v + DJ[d];
					if (nu >= 0 && nu < side && nv >= 0 && nv < side && parent[nu * side + nv] == -2) {
						parent[nu * side + nv] = k;
						heap.push(LongHeap.key(low[origin + nu * size + nv], nu * side + nv));
					}
				}
			}
			if (end < 0 || !(spill > 0f) || spill - pit < MIN_LAKE_DEPTH) {
				return null;
			}
			// The cells under the spill joined to the pit, as long as they make a lake small enough.
			boolean[] in = new boolean[side * side];
			int[] queue = new int[MAX_LAKE_CELLS + 1];
			int length = 0, u0 = r, u1 = r, v0 = r, v1 = r;
			queue[length++] = centre;
			in[centre] = true;
			for (int head = 0; head < length; head++) {
				int k = queue[head];
				int u = k / side, v = k % side;
				for (int d = 0; d < 8; d++) {
					int nu = u + DI[d], nv = v + DJ[d], m = nu * side + nv;
					if (!in[m] && low[origin + nu * size + nv] < spill) {
						if (length == MAX_LAKE_CELLS) {
							return null;
						}
						u0 = Math.min(u0, nu);
						u1 = Math.max(u1, nu);
						v0 = Math.min(v0, nv);
						v1 = Math.max(v1, nv);
						if (u1 - u0 >= MAX_LAKE_SPAN || v1 - v0 >= MAX_LAKE_SPAN) {
							return null;
						}
						in[m] = true;
						queue[length++] = m;
					}
				}
			}
			// Water in and out. The lake keeps the span short of the edge of the radius, which so holds all of it.
			double lost = 0.0;
			float floor = pit;
			int[] cells = new int[length];
			for (int c = 0; c < length; c++) {
				cells[c] = origin + queue[c] / side * size + queue[c] % side;
				lost += evaporation[cells[c]];
				floor = Math.min(floor, low[cells[c]]);
			}
			if (lost > 0.0 && catchment(origin, side, in, lost) < lost) {
				return null;
			}
			int steps = 0;
			for (int k = end; k >= 0; k = parent[k]) {
				steps++;
			}
			int[] path = new int[steps];
			for (int k = end, s = steps - 1; k >= 0; k = parent[k], s--) {
				path[s] = origin + k / side * size + k % side;
			}
			return new Depression(pa * size + pb, cells, path, spill, floor);
		}

		/**
		 * The runoff of the cells of a radius whose steepest descent leads into the cells {@code in}, counted until
		 * it reaches {@code enough}.
		 */
		private double catchment(int origin, int side, boolean[] in, double enough) {
			double sum = 0.0;
			for (int u = 0; u < side && sum < enough; u++) {
				for (int v = 0; v < side; v++) {
					int cu = u, cv = v;
					for (int steps = 0; steps < 64; steps++) {
						if (in[cu * side + cv]) {
							sum += runoff[origin + u * size + v];
							break;
						}
						int w = origin + cu * size + cv;
						int best = mean[w] <= 0f ? -1 : steepest(w, low);
						if (best < 0) {
							break;
						}
						cu += DI[best];
						cv += DJ[best];
						if (cu < 0 || cv < 0 || cu >= side || cv >= side) {
							break;
						}
					}
				}
			}
			return sum;
		}

		/**
		 * Lowers the way out of every lake: the cells on the path of its first pit's flood after the lake, down to
		 * where the path reaches lower ground, the sea or another lake. They fall evenly from the floor of the lake, or
		 * from halfway down to a target above the floor, to the target, which is never below the sea. Lake cells are
		 * never lowered.
		 */
		void lowerOutlets() {
			for (int l = 0; l < lakes.size(); l++) {
				Depression d = lakes.get(l);
				int[] path = d.path;
				int o = 0;
				while (lake[path[o + 1]] == l) {
					o++;
				}
				d.out = path[o];
				d.exit = path[o + 1];
				int s = o + 1, last = path.length - 1;
				while (s < last && lake[path[s]] < 0) {
					s++;
				}
				int stop = path[s];
				// Water that reaches the sea, or a cell with sea in it, is at sea level.
				double target = lake[stop] >= 0 ? lakes.get(lake[stop]).level : Math.max(0.0, low[stop]);
				double top = target < d.floor ? d.floor : 0.5 * (d.level + target);
				int m = s - o - 1;
				for (int k = 1; k <= m; k++) {
					int w = path[o + k];
					z[w] = Math.min(z[w], top - (top - target) * k / (m + 1));
				}
			}
		}

		/**
		 * Breaches every land cell above the sea within a radius and a cell of the region that the lakes and their ways
		 * out still leave a pit. The shores of a lake higher than the pit hold its flood; a lower lake is a way out.
		 */
		void breachPits() {
			double[] before = z.clone();
			int lo = MARGIN - BREACH_RADIUS - 1, hi = MARGIN + REGION + BREACH_RADIUS + 1;
			for (int a = lo; a < hi; a++) {
				for (int b = lo; b < hi; b++) {
					int w = a * size + b;
					if (mean[w] > 0f && before[w] > 0.0 && lake[w] < 0 && isPit(before, a, b)) {
						breach(before, a, b);
					}
				}
			}
		}

		/**
		 * Floods out from the pit at window cell {@code (pa, pb)} by the levels {@code before} until it reaches a lower
		 * cell or the sea, then lowers the cells on the way so that they drain the pit, to no lower than the sea. Gives
		 * up past the breach radius.
		 */
		private void breach(double[] before, int pa, int pb) {
			int r = BREACH_RADIUS, side = 2 * r + 1, centre = r * side + r, origin = (pa - r) * size + pb - r;
			double pit = before[pa * size + pb];
			int[] parent = new int[side * side];
			Arrays.fill(parent, -2);
			parent[centre] = -1;
			LongHeap heap = new LongHeap(64);
			heap.push(LongHeap.key((float) pit, centre));
			int end = -1;
			while (!heap.isEmpty()) {
				int k = (int) heap.pop();
				int u = k / side, v = k % side, w = origin + u * size + v;
				if (k != centre && (before[w] < pit || mean[w] <= 0f)) {
					end = k;
					break;
				}
				for (int d = 0; d < 8; d++) {
					int nu = u + DI[d], nv = v + DJ[d], m = nu * side + nv;
					if (nu < 0 || nu >= side || nv < 0 || nv >= side || parent[m] != -2) {
						continue;
					}
					int nw = origin + nu * size + nv;
					if (lake[nw] < 0 || before[nw] < pit) {
						parent[m] = k;
						heap.push(LongHeap.key((float) before[nw], m));
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
			int endW = origin + end / side * size + end % side;
			double target = Math.max(0.0, before[endW]);
			// Cells on the path, from the one before the end back to the one after the pit.
			int n = steps + 1, step = steps;
			for (int k = parent[end]; k != centre; k = parent[k], step--) {
				int w = origin + k / side * size + k % side;
				z[w] = Math.min(z[w], pit - (pit - target) * step / n);
			}
		}

		/** The region's cells: their levels and flow directions, and the lakes on them. */
		Region region(int ri, int rj) {
			Region region = new Region(ri, rj);
			int[] index = new int[lakes.size()];
			Arrays.fill(index, -1);
			List<Lake> records = new ArrayList<>();
			List<int[]> trees = new ArrayList<>();
			for (int a = 0; a < REGION; a++) {
				for (int b = 0; b < REGION; b++) {
					int w = (a + MARGIN) * size + b + MARGIN, k = a * REGION + b;
					region.level[k] = (float) z[w];
					region.runoff[k] = runoff[w];
					int l = lake[w];
					if (mean[w] <= 0f) {
						region.direction[k] = OCEAN;
					} else if (l >= 0) {
						if (index[l] < 0) {
							index[l] = records.size();
							records.add(record(lakes.get(l)));
							trees.add(tree(l));
						}
						Depression d = lakes.get(l);
						int c = 0;
						while (d.cells[c] != w) {
							c++;
						}
						region.lake[k] = (short) index[l];
						region.direction[k] = (byte) trees.get(index[l])[c];
					} else {
						region.direction[k] = (byte) (low[w] <= 0f && !intoOcean(w) ? MOUTH : steepest(w, z));
					}
				}
			}
			region.lakes = records.toArray(NO_LAKES);
			return region;
		}

		private Lake record(Depression d) {
			int[] cells = new int[2 * d.cells.length];
			for (int c = 0; c < d.cells.length; c++) {
				cells[2 * c] = i0 + d.cells[c] / size;
				cells[2 * c + 1] = j0 + d.cells[c] % size;
			}
			return new Lake(key(i0 + d.pit / size, j0 + d.pit % size), d.level, cells, i0 + d.out / size,
				j0 + d.out % size, i0 + d.exit / size, j0 + d.exit % size);
		}

		/**
		 * The flow directions of lake {@code l}'s cells, in the order of its cells: towards its outlet along the
		 * cheapest steps through the lake, a diagonal costing more where neither cell beside it lies in the lake. The
		 * outlet flows into the cell it spills into.
		 */
		private int[] tree(int l) {
			Depression d = lakes.get(l);
			int n = d.cells.length;
			float[] cost = new float[n];
			int[] direction = new int[n];
			Arrays.fill(cost, Float.POSITIVE_INFINITY);
			int out = indexOf(d.cells, d.out);
			cost[out] = 0f;
			direction[out] = step(d.out, d.exit);
			LongHeap heap = new LongHeap(n);
			heap.push(LongHeap.key(0f, d.out));
			while (!heap.isEmpty()) {
				long key = heap.pop();
				int u = (int) key, cu = indexOf(d.cells, u);
				float here = LongHeap.cost(key);
				if (here > cost[cu]) {
					continue;
				}
				for (int s = 0; s < 8; s++) {
					int v = u + DI[s] * size + DJ[s];
					if (lake[v] != l) {
						continue;
					}
					float through = s < 4 ? 2f : lake[u + DI[s] * size] == l || lake[u + DJ[s]] == l ? 3f : 6f;
					int cv = indexOf(d.cells, v);
					if (here + through < cost[cv]) {
						cost[cv] = here + through;
						direction[cv] = OPPOSITE[s];
						heap.push(LongHeap.key(cost[cv], v));
					}
				}
			}
			return direction;
		}

		/** The neighbour offset from window cell {@code from} to its neighbour {@code to}. */
		private int step(int from, int to) {
			for (int s = 0; s < 8; s++) {
				if (from + DI[s] * size + DJ[s] == to) {
					return s;
				}
			}
			throw new IllegalStateException("not neighbours");
		}

		private static int indexOf(int[] cells, int w) {
			int c = 0;
			while (cells[c] != w) {
				c++;
			}
			return c;
		}

		/** The steepest descent from window cell {@code w} over {@code level}: an index into {@link #DI}, or {@link #PIT}. */
		private int steepest(int w, double[] level) {
			int best = PIT;
			double bestDrop = 0.0;
			for (int d = 0; d < 8; d++) {
				double drop = (level[w] - level[w + DI[d] * size + DJ[d]]) / (d < 4 ? 1.0 : DIAGONAL);
				if (drop > bestDrop) {
					bestDrop = drop;
					best = d;
				}
			}
			return best;
		}

		private int steepest(int w, float[] level) {
			int best = PIT;
			double bestDrop = 0.0;
			for (int d = 0; d < 8; d++) {
				double drop = (level[w] - level[w + DI[d] * size + DJ[d]]) / (d < 4 ? 1.0 : DIAGONAL);
				if (drop > bestDrop) {
					bestDrop = drop;
					best = d;
				}
			}
			return best;
		}

		/**
		 * Whether the steepest descent from window cell {@code w} leads straight into the ocean: into an ocean cell
		 * across a side, or across a corner with an ocean cell beside it, through which the river crosses.
		 */
		private boolean intoOcean(int w) {
			int d = steepest(w, z);
			if (d < 0 || mean[w + DI[d] * size + DJ[d]] > 0f) {
				return false;
			}
			return d < 4 || mean[w + DI[d] * size] <= 0f || mean[w + DJ[d]] <= 0f;
		}

		private boolean isPit(double[] level, int a, int b) {
			double h = level[a * size + b];
			for (int d = 0; d < 8; d++) {
				if (level[(a + DI[d]) * size + b + DJ[d]] < h) {
					return false;
				}
			}
			return true;
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
