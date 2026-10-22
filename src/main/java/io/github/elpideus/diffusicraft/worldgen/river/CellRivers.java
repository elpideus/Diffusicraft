package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rivers inside one tile, which is one coarse cell: smooth polylines with a water level, a discharge and the
 * ground height along them, and the ponds where rivers end in a pit.
 *
 * <p>A river crosses a side of the cell at a node, the lowest point of that side away from the corners. Near the
 * side it runs straight across it, so the two cells that share the side draw the same river there without looking
 * at each other. Inside, it follows the cheapest path through the terrain, where low ground is cheap, from the node
 * where it enters to where it leaves; paths to the same exit merge into a tree, so tributaries join. Over level ground
 * a little noise in the cost makes rivers wander instead of running in the straight lines of the pixel grid. The water
 * level goes down along the path, never rises, and never sinks below the level of the exit.
 *
 * <p>Rivers see a basin that can hold a lake ({@link CellBasins}) as the surface of its water, not its floor: they
 * cross it at the height of its lowest point, less the depth of their channel, and leave it over that point. The
 * {@link CellLakes} fill it to their level. They see a lake of the blocks around that a river feeds
 * ({@link BlockLakes}) the same way, but only for their level: their paths follow the tile alone, so that a block can
 * draw the rivers of its tiles, which decide its lakes, without the lakes of the other blocks.
 *
 * <p>Each river is a path, from where it enters the tile or starts to where it leaves it, joins another river, reaches
 * the sea or ends in a pond. Its level is as smooth as the terrain in pixels; at each world size, {@link RiverCourses}
 * sets its water by the ground in blocks instead, which has the ledges and cliffs that the rivers fall over.
 *
 * <p>Paths stay in the interior band, {@value #LO} pixels from the sides, and nothing reaches farther than
 * {@value #REACH} pixels from a river's water, which lies at most {@link Rivers#MAX_HALF_WIDTH} pixels from its path,
 * so every column of the tile is decided by this tile alone.
 */
final class CellRivers {
	static final CellRivers EMPTY = new CellRivers(new float[0], 0, new int[0][], new int[0]);

	private static final int TILE = TerrainField.TILE;
	private static final int PAD = TerrainField.PAD;
	private static final int SPAN = TerrainField.SPAN;
	/** First and last-plus-one row (and column) of the band where rivers are routed. */
	static final int LO = 32;
	static final int HI = TILE - LO;
	private static final int BAND = HI - LO;
	/** Nodes keep this far from the corners, so stubs on two sides never meet. */
	static final int NODE_MARGIN = 48;
	/** Half-width of the box that smooths a side before its lowest point is taken. */
	private static final int SMOOTH = 4;
	/**
	 * Farthest the banks of a river reach from its water, in pixels (720 m). With the widest river, this is as far as the
	 * band lies inside the sides, so a river decides no column past its tile. A valley cut deeper than its banks can
	 * span at their usual slope gets steeper walls: a gorge.
	 */
	static final float REACH = 24f;
	/** Rivers run straight across a side from this far outside it to this far inside. */
	static final float STRAIGHT = 16f;
	private static final int BUCKET = 8;
	private static final int BUCKETS = TILE / BUCKET;
	/** How much more a step over the highest ground of the tile costs than one over the lowest. */
	private static final float COST_SLOPE = 40f;
	/** Relief below which a tile counts as flat when weighing paths, meters. */
	private static final float MIN_RANGE = 30f;
	/**
	 * Most the meander noise multiplies the cost of a step by, less one, and its wavelength in pixels. The noise is the
	 * absolute value of gradient noise, which is zero along winding lines that the rivers follow.
	 */
	private static final float MEANDER = 2.5f;
	private static final double MEANDER_WAVE = 32.0;
	/**
	 * Local slopes, meters per pixel, below which the meander noise is at full strength and above which it is gone:
	 * rivers wander on plains, but in valleys the ground alone decides.
	 */
	private static final float FLAT = 2f;
	private static final float STEEP = 6f;
	private static final long MEANDER_SALT = 0x6D65616E646572L;
	/** How far the water lies below the ground it flows over, meters. */
	static final float DROP = 1.5f;
	private static final int CHAIKIN_PASSES = 3;
	/** A path keeps every this many pixels before it is smoothed, which hides the steps of the grid. */
	private static final int PATH_STEP = 3;
	/** Pond radius per square root of discharge, and the largest, meters. */
	private static final float POND_METERS = 25f;
	private static final float MAX_POND_METERS = 300f;
	private static final float SQRT2 = (float) Math.sqrt(2.0);
	/** How a path ends: leaving the tile across a side, joining another river, in the sea or in a pond. */
	static final int EXIT = 0, JOIN = 1, SEA = 2, POND = 3;

	// Per segment: ai, aj, bi, bj, level a, level b, discharge a, discharge b, ground a, ground b, pond radius (px),
	// the path it belongs to (-1 for a pond), and how far along the path a lies (px).
	private static final int STRIDE = 13;
	/**
	 * Where {@link #segment} finds the ends of a segment, their water levels and discharges, and how far along its path
	 * the first end lies.
	 */
	static final int AI = 0, AJ = 1, BI = 2, BJ = 3, LEVEL_A = 4, LEVEL_B = 5, DISCHARGE_A = 6, DISCHARGE_B = 7;
	static final int ALONG = 12;
	// Per path: its first segment and the one past its last, whether it starts at a side (1) or a source (0), how it
	// ends, and the path it joins, or -1.
	private static final int PATH_STRIDE = 5;

	private final float[] segments;
	private final int count;
	private final int[][] buckets;
	private final int[] paths;

	/** How wide a river of some discharge is, in pixels, at the world scale being generated. */
	interface Width {
		float halfWidthPixels(float discharge);
	}

	/** The river nearest a point, filled by {@link #nearest}. */
	static final class Hit {
		/** Distance from the centre line, pixels. */
		float distance;
		/** Half the river's width there (or the pond's radius), pixels. */
		float halfWidth;
		/** Water level, meters. */
		float level;
		float discharge;
		/** Ground height under the centre line before any carving, meters. */
		float ground;
		/** The path of the river, or -1 for a pond, and how far along it the point nearest lies, pixels. */
		int path;
		float along;
	}

	private CellRivers(float[] segments, int count, int[][] buckets, int[] paths) {
		this.segments = segments;
		this.count = count;
		this.buckets = buckets;
		this.paths = paths;
	}

	boolean isEmpty() {
		return count == 0;
	}

	int segmentCount() {
		return count;
	}

	/** Attribute {@code attribute} of segment {@code s}: {@link #AI} and the others. */
	float segment(int s, int attribute) {
		return segments[s * STRIDE + attribute];
	}

	/**
	 * Rivers as paths: one per river that enters the tile or starts in it, in the order they were drawn, so a path
	 * joins only one drawn before it.
	 */
	int pathCount() {
		return paths.length / PATH_STRIDE;
	}

	/** The first segment of path {@code p} and the one past its last. */
	int pathFirst(int p) {
		return paths[p * PATH_STRIDE];
	}

	int pathEnd(int p) {
		return paths[p * PATH_STRIDE + 1];
	}

	/** Whether path {@code p} enters across a side, at the level the tile beyond it has there, or starts at a source. */
	boolean startsAtSide(int p) {
		return paths[p * PATH_STRIDE + 2] != 0;
	}

	/** How path {@code p} ends: {@link #EXIT}, {@link #JOIN}, {@link #SEA} or {@link #POND}. */
	int ending(int p) {
		return paths[p * PATH_STRIDE + 3];
	}

	/** The path that path {@code p} joins, at the end of its last segment, or -1. */
	int joins(int p) {
		return paths[p * PATH_STRIDE + 4];
	}

	/**
	 * Finds the river whose water is nearest tile-local pixel coordinate {@code (u, v)}, measured from its bank, if
	 * any lies within {@link #REACH}. Returns whether one did.
	 */
	boolean nearest(double u, double v, Width width, Hit hit) {
		if (count == 0 || u < 0 || v < 0 || u >= TILE || v >= TILE) {
			return false;
		}
		int[] list = buckets[(int) (u / BUCKET) * BUCKETS + (int) (v / BUCKET)];
		if (list == null) {
			return false;
		}
		float[] s = segments;
		double best = Double.POSITIVE_INFINITY;
		for (int index : list) {
			int o = index * STRIDE;
			double ai = s[o], aj = s[o + 1], di = s[o + 2] - ai, dj = s[o + 3] - aj;
			double length2 = di * di + dj * dj;
			double t = length2 > 0 ? Math.clamp(((u - ai) * di + (v - aj) * dj) / length2, 0.0, 1.0) : 0.0;
			double ei = u - (ai + t * di), ej = v - (aj + t * dj);
			double distance = Math.sqrt(ei * ei + ej * ej);
			if (distance >= REACH + s[o + 10] + 8f) {
				continue;
			}
			float ft = (float) t;
			float discharge = s[o + 6] + (s[o + 7] - s[o + 6]) * ft;
			float halfWidth = Math.max(width.halfWidthPixels(discharge), s[o + 10]);
			double edge = distance - halfWidth;
			if (edge < best) {
				best = edge;
				hit.distance = (float) distance;
				hit.halfWidth = halfWidth;
				hit.level = s[o + 4] + (s[o + 5] - s[o + 4]) * ft;
				hit.discharge = discharge;
				hit.ground = s[o + 8] + (s[o + 9] - s[o + 8]) * ft;
				hit.path = (int) s[o + 11];
				hit.along = (float) (s[o + ALONG] + t * Math.sqrt(length2));
			}
		}
		return best < REACH;
	}

	/**
	 * Routes the rivers of {@code flows} through the padded elevation {@code tile} (see TerrainField#paddedTile) of
	 * tile {@code (ti, tj)} in a world with {@code seed}, over its {@code basins}. Where {@code pathLevel} is given,
	 * with a NaN for every pixel of the padded tile, it gets the water level of every pixel a river runs through.
	 */
	static CellRivers build(float[] tile, CellBasins basins, CellFlows flows, long seed, int ti, int tj,
							float[] pathLevel) {
		if (flows.isEmpty()) {
			return EMPTY;
		}
		return new Builder(tile, basins, flows, seed, ti, tj, pathLevel).build();
	}

	/**
	 * The node of a side of the padded elevation {@code tile} (see TerrainField#paddedTile), where the rivers that
	 * cross that side cross it: the lowest point of the smoothed side, away from the corners, as a tile-local position
	 * along the side. The two tiles that share a side find the same node.
	 */
	static int node(float[] tile, int side) {
		int lo = NODE_MARGIN - SMOOTH, hi = TILE - NODE_MARGIN + SMOOTH;
		float[] raw = new float[hi - lo];
		for (int t = lo; t < hi; t++) {
			raw[t - lo] = sideHeight(tile, side, t);
		}
		float best = Float.POSITIVE_INFINITY;
		int bestT = TILE / 2;
		for (int t = NODE_MARGIN; t < TILE - NODE_MARGIN; t++) {
			float sum = 0f;
			for (int k = -SMOOTH; k <= SMOOTH; k++) {
				sum += raw[t + k - lo];
			}
			if (sum < best) {
				best = sum;
				bestT = t;
			}
		}
		return bestT;
	}

	/** The lower of the two pixels on either side of a side, at position {@code t} along it. */
	private static float sideHeight(float[] tile, int side, int t) {
		int inside, outside;
		switch (side) {
			case 0 -> {
				inside = pad(0, t);
				outside = pad(-1, t);
			}
			case 1 -> {
				inside = pad(TILE - 1, t);
				outside = pad(TILE, t);
			}
			case 2 -> {
				inside = pad(t, 0);
				outside = pad(t, -1);
			}
			default -> {
				inside = pad(t, TILE - 1);
				outside = pad(t, TILE);
			}
		}
		return Math.min(tile[inside], tile[outside]);
	}

	/** Index in the padded tile of tile-local pixel {@code (i, j)}. */
	private static int pad(int i, int j) {
		return (i + PAD) * SPAN + j + PAD;
	}

	// ---------------------------------------------------------------------------------------------------------

	private static final class Builder {
		private final float[] tile;
		private final CellBasins basins;
		private final CellFlows flows;
		private final long seed;
		private final int ti;
		private final int tj;
		private final int[] node = new int[4];
		private final float[] nodeGround = new float[4];
		private final float[] cost = new float[BAND * BAND];
		private final float[] dist = new float[BAND * BAND];
		private final LongHeap heap = new LongHeap(4096);
		private final float[] level = new float[BAND * BAND];
		private final float[] discharge = new float[BAND * BAND];
		private final float[] extra = new float[BAND * BAND];
		/** The path drawn through each pixel, plus one, or 0. */
		private final int[] drawnBy = new int[BAND * BAND];
		private final float[] pathLevel;
		private float[] out = new float[STRIDE * 64];
		private int count;
		private int[] pathsOut = new int[PATH_STRIDE * 8];
		private int pathCount;

		Builder(float[] tile, CellBasins basins, CellFlows flows, long seed, int ti, int tj, float[] pathLevel) {
			this.tile = tile;
			this.basins = basins;
			this.flows = flows;
			this.seed = seed;
			this.ti = ti;
			this.tj = tj;
			this.pathLevel = pathLevel;
		}

		/** Elevation of tile-local pixel {@code (i, j)}; the margin allows -32 to 287. */
		float h(int i, int j) {
			return tile[(i + PAD) * SPAN + j + PAD];
		}

		float h(int k) {
			return h(LO + k / BAND, LO + k % BAND);
		}

		/**
		 * The ground the river sees at tile-local pixel {@code (i, j)}, which sets its level: the surface of a lake, or
		 * the terrain.
		 */
		float g(int i, int j) {
			return basins.ground((i + PAD) * SPAN + j + PAD);
		}

		float g(int k) {
			return g(LO + k / BAND, LO + k % BAND);
		}

		/**
		 * The ground the river's path follows at tile-local pixel {@code (i, j)}: as {@link #g}, but over the lakes of
		 * the tile alone, so that the path needs nothing else.
		 */
		float s(int i, int j) {
			return basins.surface((i + PAD) * SPAN + j + PAD);
		}

		float s(int k) {
			return s(LO + k / BAND, LO + k % BAND);
		}

		/** Height difference between pixel (i, j) and {@code e}, with the sea counted as level with the coast. */
		private float drop(int i, int j, float e) {
			return Math.abs(Math.max(0f, s(i, j)) - e);
		}

		CellRivers build() {
			for (int side = 0; side < 4; side++) {
				if (!Float.isNaN(flows.inLevel[side]) || !Float.isNaN(flows.outLevel[side])) {
					findNode(side);
				}
			}
			float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
			for (int k = 0; k < cost.length; k++) {
				float e = Math.max(0f, s(k));
				min = Math.min(min, e);
				max = Math.max(max, e);
			}
			float scale = COST_SLOPE / Math.max(max - min, MIN_RANGE);
			long noiseSeed = seed ^ MEANDER_SALT;
			for (int k = 0; k < cost.length; k++) {
				int i = LO + k / BAND, j = LO + k % BAND;
				float e = Math.max(0f, s(i, j));
				cost[k] = 1f + scale * (e - min);
				float slope = Math.max(Math.max(drop(i - 1, j, e), drop(i + 1, j, e)),
					Math.max(drop(i, j - 1, e), drop(i, j + 1, e)));
				float flat = Math.clamp((STEEP - slope) / (STEEP - FLAT), 0f, 1f);
				if (flat > 0f) {
					double wi = ((double) ti * TILE + i) / MEANDER_WAVE, wj = ((double) tj * TILE + j) / MEANDER_WAVE;
					cost[k] *= 1f + flat * MEANDER * (float) Math.abs(GeoNoise.fractal(noiseSeed, wi, wj, 1));
				}
			}
			Arrays.fill(level, Float.POSITIVE_INFINITY);
			for (int target = 3; target >= CellFlows.SINK; target--) {
				route(target);
			}
			return new CellRivers(Arrays.copyOf(out, count * STRIDE), count, index(),
				Arrays.copyOf(pathsOut, pathCount * PATH_STRIDE));
		}

		private void findNode(int side) {
			node[side] = node(tile, side);
			nodeGround[side] = sideHeight(tile, side, node[side]);
		}

		/** Band index of the pixel where the river of a side's node enters the band. */
		private int innerPixel(int side) {
			int t = node[side] - LO;
			return switch (side) {
				case 0 -> t;
				case 1 -> (BAND - 1) * BAND + t;
				case 2 -> t * BAND;
				default -> t * BAND + BAND - 1;
			};
		}

		/** Tile-local coordinates of the point on a side's stub {@code depth} pixels inside the side. */
		private float[] stubPoint(int side, float depth) {
			float t = node[side] + 0.5f;
			return switch (side) {
				case 0 -> new float[]{depth, t};
				case 1 -> new float[]{TILE - depth, t};
				case 2 -> new float[]{t, depth};
				default -> new float[]{t, TILE - depth};
			};
		}

		/** Draws every river bound for one target: a side, the sea or a pond. */
		private void route(int target) {
			List<int[]> starts = new ArrayList<>();
			List<Float> startDischarge = new ArrayList<>();
			List<Float> startLevel = new ArrayList<>();
			for (CellFlows.Inflow inflow : flows.inflows) {
				if (inflow.target() == target) {
					starts.add(new int[]{innerPixel(inflow.side()), inflow.side()});
					startDischarge.add(inflow.discharge());
					startLevel.add(flows.inLevel[inflow.side()]);
				}
			}
			if (flows.source >= 0 && flows.source == target) {
				int start = sourcePixel();
				starts.add(new int[]{start, -1});
				startDischarge.add(Math.min(0.5f * RiverNetwork.MIN_DISCHARGE, flows.outDischarge[target]));
				startLevel.add(g(start) - DROP);
			}
			if (starts.isEmpty()) {
				return;
			}

			int[] roots;
			float rootLevel;
			if (target >= 0) {
				roots = new int[]{innerPixel(target)};
				rootLevel = flows.outLevel[target];
			} else {
				roots = target == CellFlows.SEA ? seaPixels() : new int[0];
				if (roots.length == 0) {
					roots = new int[]{lowestPixel()};
					rootLevel = Math.max(0f, h(roots[0]));
					target = CellFlows.SINK;
				} else {
					rootLevel = 0f;
				}
			}
			int[] parent = search(roots);

			float inTotal = 0f;
			for (float q : startDischarge) {
				inTotal += q;
			}
			float outTotal = target >= 0 ? flows.outDischarge[target] : inTotal;
			float gain = Math.max(0f, outTotal - inTotal);

			List<int[]> paths = new ArrayList<>();
			List<Integer> touched = new ArrayList<>();
			for (int s = 0; s < starts.size(); s++) {
				int[] path = path(parent, starts.get(s)[0]);
				paths.add(path);
				float run = startLevel.get(s), q = startDischarge.get(s);
				for (int t = 0; t < path.length; t++) {
					int p = path[t];
					run = Math.min(run, g(p) - DROP);
					if (level[p] == Float.POSITIVE_INFINITY) {
						touched.add(p);
						discharge[p] = 0f;
						extra[p] = 0f;
					}
					level[p] = Math.min(level[p], run);
					discharge[p] += q;
					extra[p] = Math.max(extra[p], path.length > 1 ? gain * t / (path.length - 1) : gain);
				}
			}
			for (int p : touched) {
				level[p] = Math.max(Math.max(level[p], rootLevel), 0f);
				if (pathLevel != null) {
					int k = (LO + p / BAND + PAD) * SPAN + LO + p % BAND + PAD;
					pathLevel[k] = Float.isNaN(pathLevel[k]) ? level[p] : Math.min(pathLevel[k], level[p]);
				}
			}

			for (int s = 0; s < paths.size(); s++) {
				draw(paths.get(s), starts.get(s)[1], target);
			}
			if (target == CellFlows.SINK) {
				int root = roots[0];
				float radius = Math.min(POND_METERS * (float) Math.sqrt(inTotal), MAX_POND_METERS) / 30f;
				float i = LO + root / BAND + 0.5f, j = LO + root % BAND + 0.5f;
				segment(i, j, i, j, level[root], level[root], inTotal, inTotal, h(root), h(root), radius, -1, 0f);
			}
			for (int p : touched) {
				level[p] = Float.POSITIVE_INFINITY;
				drawnBy[p] = 0;
			}
		}

		/** Turns one path into a smoothed polyline, stopping where it joins a river already drawn. */
		private void draw(int[] path, int inSide, int target) {
			int index = pathCount;
			List<float[]> line = new ArrayList<>();
			if (inSide >= 0) {
				line.add(vertex(stubPoint(inSide, STRAIGHT), flows.inLevel[inSide], flows.inDischarge[inSide],
					nodeGround[inSide]));
			}
			int joins = -1;
			for (int t = 0; t < path.length; t++) {
				int p = path[t];
				joins = drawnBy[p] - 1;
				if (t % PATH_STEP == 0 || joins >= 0 || t == path.length - 1) {
					line.add(vertex(new float[]{LO + p / BAND + 0.5f, LO + p % BAND + 0.5f}, level[p],
						discharge[p] + extra[p], h(p)));
				}
				if (joins >= 0) {
					break;
				}
				drawnBy[p] = index + 1;
			}
			boolean exits = joins < 0 && target >= 0;
			if (exits) {
				line.add(vertex(stubPoint(target, STRAIGHT), flows.outLevel[target], flows.outDischarge[target],
					nodeGround[target]));
			}
			List<float[]> smooth = chaikin(line);
			if (inSide >= 0) {
				smooth.addFirst(vertex(stubPoint(inSide, -LO - 0.5f), flows.inLevel[inSide],
					flows.inDischarge[inSide], nodeGround[inSide]));
			}
			if (exits) {
				smooth.add(vertex(stubPoint(target, -LO - 0.5f), flows.outLevel[target], flows.outDischarge[target],
					nodeGround[target]));
			}
			int first = count;
			float along = 0f;
			for (int k = 0; k + 1 < smooth.size(); k++) {
				float[] a = smooth.get(k), b = smooth.get(k + 1);
				segment(a[0], a[1], b[0], b[1], a[2], b[2], a[3], b[3], a[4], b[4], 0f, index, along);
				along += (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
			}
			if (pathsOut.length < (pathCount + 1) * PATH_STRIDE) {
				pathsOut = Arrays.copyOf(pathsOut, pathsOut.length * 2);
			}
			int o = pathCount++ * PATH_STRIDE;
			pathsOut[o] = first;
			pathsOut[o + 1] = count;
			pathsOut[o + 2] = inSide >= 0 ? 1 : 0;
			pathsOut[o + 3] = joins >= 0 ? JOIN : exits ? EXIT : target == CellFlows.SEA ? SEA : POND;
			pathsOut[o + 4] = joins;
		}

		/** A vertex: its place, level, discharge and ground. */
		private static float[] vertex(float[] at, float level, float discharge, float ground) {
			return new float[]{at[0], at[1], level, discharge, ground};
		}

		/** Chaikin's corner cutting, keeping both ends. Attributes are interpolated along with the position. */
		private static List<float[]> chaikin(List<float[]> line) {
			for (int pass = 0; pass < CHAIKIN_PASSES && line.size() > 2; pass++) {
				List<float[]> next = new ArrayList<>(line.size() * 2);
				next.add(line.getFirst());
				for (int k = 0; k + 1 < line.size(); k++) {
					float[] a = line.get(k), b = line.get(k + 1);
					if (k > 0) {
						next.add(mix(a, b, 0.25f));
					}
					if (k + 2 < line.size()) {
						next.add(mix(a, b, 0.75f));
					}
				}
				next.add(line.getLast());
				line = next;
			}
			return line;
		}

		private static float[] mix(float[] a, float[] b, float t) {
			float[] m = new float[a.length];
			for (int k = 0; k < a.length; k++) {
				m[k] = a[k] + (b[k] - a[k]) * t;
			}
			return m;
		}

		private void segment(float ai, float aj, float bi, float bj, float la, float lb, float qa, float qb, float ga,
							 float gb, float pond, int path, float along) {
			if (out.length < (count + 1) * STRIDE) {
				out = Arrays.copyOf(out, out.length * 2);
			}
			int o = count++ * STRIDE;
			out[o] = ai;
			out[o + 1] = aj;
			out[o + 2] = bi;
			out[o + 3] = bj;
			out[o + 4] = la;
			out[o + 5] = lb;
			out[o + 6] = qa;
			out[o + 7] = qb;
			out[o + 8] = ga;
			out[o + 9] = gb;
			out[o + 10] = pond;
			out[o + 11] = path;
			out[o + 12] = along;
		}

		/** Where a river with no inflow starts: off the middle, towards the stream that feeds the cell. */
		private int sourcePixel() {
			double di = flows.sourceDi, dj = flows.sourceDj, length = Math.sqrt(di * di + dj * dj);
			double reach = length > 0 ? 80.0 / length : 0.0;
			int i = (int) Math.round(TILE / 2.0 + di * reach), j = (int) Math.round(TILE / 2.0 + dj * reach);
			return (i - LO) * BAND + (j - LO);
		}

		private int[] seaPixels() {
			int n = 0;
			int[] sea = new int[BAND * BAND];
			for (int k = 0; k < sea.length; k++) {
				// Not a valley the ice drowned behind a step, which may hold a lake above the sea.
				if (basins.sea((LO + k / BAND + PAD) * SPAN + LO + k % BAND + PAD)) {
					sea[n++] = k;
				}
			}
			return Arrays.copyOf(sea, n);
		}

		/** The lowest pixel well inside the tile, where a pond collects what flows into a pit. */
		private int lowestPixel() {
			int best = -1;
			float lowest = Float.POSITIVE_INFINITY;
			for (int i = NODE_MARGIN; i < TILE - NODE_MARGIN; i++) {
				for (int j = NODE_MARGIN; j < TILE - NODE_MARGIN; j++) {
					if (h(i, j) < lowest) {
						lowest = h(i, j);
						best = (i - LO) * BAND + (j - LO);
					}
				}
			}
			return best;
		}

		/** Least-cost search from the roots over the band; returns each pixel's next step towards a root. */
		private int[] search(int[] roots) {
			int[] parent = new int[BAND * BAND];
			Arrays.fill(parent, -2);
			Arrays.fill(dist, Float.POSITIVE_INFINITY);
			heap.clear();
			for (int r : roots) {
				dist[r] = 0f;
				parent[r] = -1;
				heap.push(LongHeap.key(0f, r));
			}
			while (!heap.isEmpty()) {
				long key = heap.pop();
				int k = (int) key;
				float d = LongHeap.cost(key);
				if (d > dist[k]) {
					continue;
				}
				int a = k / BAND, b = k % BAND;
				for (int n = 0; n < 8; n++) {
					int na = a + RiverNetwork.DI[n], nb = b + RiverNetwork.DJ[n];
					if (na < 0 || na >= BAND || nb < 0 || nb >= BAND) {
						continue;
					}
					int nk = na * BAND + nb;
					float nd = d + cost[nk] * (n < 4 ? 1f : SQRT2);
					if (nd < dist[nk]) {
						dist[nk] = nd;
						parent[nk] = k;
						heap.push(LongHeap.key(nd, nk));
					}
				}
			}
			return parent;
		}

		private static int[] path(int[] parent, int start) {
			int length = 0;
			for (int k = start; k >= 0; k = parent[k]) {
				length++;
			}
			int[] path = new int[length];
			int n = 0;
			for (int k = start; k >= 0; k = parent[k]) {
				path[n++] = k;
			}
			return path;
		}

		/** Lists, for every bucket of the tile, the segments that can reach into it. */
		private int[][] index() {
			int[] counts = new int[BUCKETS * BUCKETS];
			int[][] lists = new int[BUCKETS * BUCKETS][];
			for (int pass = 0; pass < 2; pass++) {
				for (int s = 0; s < count; s++) {
					int o = s * STRIDE;
					float reach = REACH + out[o + 10] + 8f;
					int i0 = bucket(Math.min(out[o], out[o + 2]) - reach);
					int i1 = bucket(Math.max(out[o], out[o + 2]) + reach);
					int j0 = bucket(Math.min(out[o + 1], out[o + 3]) - reach);
					int j1 = bucket(Math.max(out[o + 1], out[o + 3]) + reach);
					for (int bi = i0; bi <= i1; bi++) {
						for (int bj = j0; bj <= j1; bj++) {
							int b = bi * BUCKETS + bj;
							if (pass == 0) {
								counts[b]++;
							} else {
								lists[b][--counts[b]] = s;
							}
						}
					}
				}
				if (pass == 0) {
					for (int b = 0; b < lists.length; b++) {
						if (counts[b] > 0) {
							lists[b] = new int[counts[b]];
						}
					}
				}
			}
			return lists;
		}

		private static int bucket(float coordinate) {
			return Math.clamp((int) Math.floor(coordinate / BUCKET), 0, BUCKETS - 1);
		}
	}
}
