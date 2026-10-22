package io.github.elpideus.diffusicraft.worldgen.river;

import io.github.elpideus.diffusicraft.worldgen.TerrainField;
import io.github.elpideus.diffusicraft.worldgen.WorldScale;

/**
 * Where the water of the rivers of one tile stands at one world size: the top of the water, in blocks, every block
 * along each river.
 *
 * <p>The paths and levels of the rivers ({@link CellRivers}) follow the terrain in pixels 30 m wide, whose slopes are
 * smooth. The ground in blocks has ledges and cliffs, knolls and gullies. A river held to its smooth level would cut a
 * trench through every rise of that ground, run on a dyke over every dip, and fall nowhere. So its water follows the
 * ground instead, {@link CellRivers#DROP} below the lowest ground across its channel, and never rises. Where the ground
 * drops away, over a ledge or a cliff, the river falls with it to the ground below. Where the ground slopes, the water
 * runs down it in steps of a block. Where the ground rises in its way, the river cuts through, at most {@link #CUT}
 * meters deeper than it usually lies below the ground, or down to its smooth level where that lies deeper, as through
 * a ridge the smooth level breaches. Only over a hollow before a lesser rise does the water stand above the ground, on
 * banks built up to hold it, and never above its smooth level.
 *
 * <p>Some levels are fixed. Within {@link #EDGE} blocks of a side of the tile, a river keeps its smooth level, which
 * the tile beyond that side gives it too, so that the two tiles' water meets. A river that leaves across a side comes
 * down to that level at most {@link #RAMP} blocks per block, in rapids, or as steeply as its smooth level where that
 * is steeper, so that it never runs in a trench below it. A river never stands below a lake it crosses
 * or runs beside, nor below the sea. It ends no lower than the river it joins or the pond it ends in, so that a
 * tributary above the river where they meet falls into it. And the river it joins stays below the level the
 * tributary entered the tile at, so that the tributary never has to rise to reach it.
 */
final class RiverCourses {
	/** Blocks from a side of the tile within which a river keeps the smooth level the tile beyond it also has. */
	private static final double EDGE = 4;
	/** Steepest a river comes down to the level it leaves the tile at, blocks per block. */
	private static final double RAMP = 0.5;
	/** How much deeper than usual a river may cut into ground that rises in its way, meters, and blocks at least. */
	private static final double CUT = 3;
	private static final double CUT_BLOCKS = 2;

	/** The ground in blocks, as {@link io.github.elpideus.diffusicraft.worldgen.TerrainSurface} has it. */
	interface Ground {
		/** The height of the ground at a point, in blocks. */
		double y(double x, double z);
	}

	/** Per path, the top of the water every {@link #step} pixels along it: the block above its highest water block. */
	private final short[][] tops;
	private final double step;

	private RiverCourses(short[][] tops, double step) {
		this.tops = tops;
		this.step = step;
	}

	/**
	 * The water of {@code rivers} and {@code lakes}, of tile {@code (ti, tj)}, at {@code scale}, over {@code ground},
	 * for rivers as wide as {@code width} has them.
	 */
	static RiverCourses of(CellRivers rivers, CellLakes lakes, int ti, int tj, WorldScale scale, Ground ground,
						   CellRivers.Width width) {
		return new Builder(rivers, lakes, ti, tj, scale, ground, width).build();
	}

	/**
	 * The top of the water {@code along} pixels down path {@code p}, as {@link CellRivers.Hit} has them: the block
	 * above its highest water block.
	 */
	int top(int p, double along) {
		short[] path = tops[p];
		return path[Math.clamp(Math.round(along / step), 0, path.length - 1)];
	}

	// ---------------------------------------------------------------------------------------------------------

	private static final class Builder {
		private final CellRivers rivers;
		private final CellLakes lakes;
		private final WorldScale scale;
		private final Ground ground;
		private final CellRivers.Width width;
		/** Where the tile starts, in pixels. */
		private final double i0;
		private final double j0;
		private final double pixelsPerBlock;
		/** Pixels between samples: a block. */
		private final double step;
		private final double edge;
		/** {@link #RAMP} per pixel. */
		private final float ramp;
		private final float cut;
		private final float sea;
		private final Path[] paths;

		Builder(CellRivers rivers, CellLakes lakes, int ti, int tj, WorldScale scale, Ground ground,
				CellRivers.Width width) {
			this.rivers = rivers;
			this.lakes = lakes;
			this.scale = scale;
			this.ground = ground;
			this.width = width;
			this.i0 = (double) ti * TerrainField.TILE;
			this.j0 = (double) tj * TerrainField.TILE;
			this.pixelsPerBlock = scale.pixelsPerBlock();
			this.step = pixelsPerBlock;
			this.edge = EDGE * pixelsPerBlock;
			this.ramp = (float) (RAMP / pixelsPerBlock);
			this.cut = (float) Math.max(CUT / scale.verticalMetersPerBlock(), CUT_BLOCKS);
			this.sea = scale.surfaceY(0f);
			this.paths = new Path[rivers.pathCount()];
		}

		RiverCourses build() {
			for (int p = 0; p < paths.length; p++) {
				paths[p] = sample(p);
			}
			// Where each tributary joins its river, and the least level each path entered at, its tributaries' too.
			for (int p = paths.length - 1; p >= 0; p--) {
				Path path = paths[p];
				for (int k = 0; k < path.n; k++) {
					if (path.fixed[k]) {
						path.cap = Math.min(path.cap, scale.surfaceY(path.meters[k]));
					}
				}
				int main = rivers.joins(p);
				if (main >= 0 && path.n > 0) {
					int last = rivers.pathEnd(p) - 1;
					path.joinAlong = project(main, rivers.segment(last, CellRivers.BI), rivers.segment(last, CellRivers.BJ));
					paths[main].cap = Math.min(paths[main].cap, path.cap);
				}
			}
			short[][] tops = new short[paths.length][];
			for (int p = 0; p < paths.length; p++) {
				float[] water = water(p);
				short[] top = new short[water.length];
				for (int k = 0; k < water.length; k++) {
					top[k] = (short) Math.clamp((long) Math.ceil(water[k]), Short.MIN_VALUE, Short.MAX_VALUE);
				}
				tops[p] = top;
			}
			return new RiverCourses(tops, step);
		}

		/** The places, directions, levels and discharges every {@link #step} pixels along path {@code p}. */
		private Path sample(int p) {
			int first = rivers.pathFirst(p), end = rivers.pathEnd(p);
			if (first == end) {
				return new Path(0);
			}
			double length = rivers.segment(end - 1, CellRivers.ALONG) + length(end - 1);
			Path path = new Path((int) Math.ceil(length / step) + 1);
			for (int k = 0, s = first; k < path.n; k++) {
				double along = Math.min(k * step, length);
				while (s + 1 < end && rivers.segment(s + 1, CellRivers.ALONG) <= along) {
					s++;
				}
				double ai = rivers.segment(s, CellRivers.AI), aj = rivers.segment(s, CellRivers.AJ);
				double di = rivers.segment(s, CellRivers.BI) - ai, dj = rivers.segment(s, CellRivers.BJ) - aj;
				double segment = Math.hypot(di, dj);
				double t = segment > 0 ? Math.clamp((along - rivers.segment(s, CellRivers.ALONG)) / segment, 0, 1) : 0;
				float level = rivers.segment(s, CellRivers.LEVEL_A), discharge = rivers.segment(s, CellRivers.DISCHARGE_A);
				double u = ai + t * di, v = aj + t * dj;
				path.along[k] = (float) along;
				path.u[k] = (float) u;
				path.v[k] = (float) v;
				path.du[k] = segment > 0 ? (float) (di / segment) : 0f;
				path.dv[k] = segment > 0 ? (float) (dj / segment) : 0f;
				path.meters[k] = level + (rivers.segment(s, CellRivers.LEVEL_B) - level) * (float) t;
				path.discharge[k] = discharge + (rivers.segment(s, CellRivers.DISCHARGE_B) - discharge) * (float) t;
				path.fixed[k] = Math.min(Math.min(u, v), Math.min(TerrainField.TILE - u, TerrainField.TILE - v)) < edge;
			}
			return path;
		}

		private double length(int s) {
			return Math.hypot(rivers.segment(s, CellRivers.BI) - rivers.segment(s, CellRivers.AI),
				rivers.segment(s, CellRivers.BJ) - rivers.segment(s, CellRivers.AJ));
		}

		/** How far along path {@code p} the point nearest tile-local pixel {@code (u, v)} lies. */
		private float project(int p, double u, double v) {
			double best = Double.POSITIVE_INFINITY, along = 0;
			for (int s = rivers.pathFirst(p); s < rivers.pathEnd(p); s++) {
				double ai = rivers.segment(s, CellRivers.AI), aj = rivers.segment(s, CellRivers.AJ);
				double di = rivers.segment(s, CellRivers.BI) - ai, dj = rivers.segment(s, CellRivers.BJ) - aj;
				double length2 = di * di + dj * dj;
				double t = length2 > 0 ? Math.clamp(((u - ai) * di + (v - aj) * dj) / length2, 0, 1) : 0;
				double ei = u - (ai + t * di), ej = v - (aj + t * dj), distance = ei * ei + ej * ej;
				if (distance < best) {
					best = distance;
					along = rivers.segment(s, CellRivers.ALONG) + t * Math.sqrt(length2);
				}
			}
			return (float) along;
		}

		/**
		 * The height of the water along path {@code p}, in blocks, as the paths it joins have it already. It has
		 * bounds: a fixed level where it is fixed; above, the lowest fixed level before, a ceiling where a tributary
		 * joins, and the ramp down to a fixed level ahead; below, the highest floor ahead, as far as the bounds above
		 * allow. Between them it follows the ground and never rises.
		 */
		private float[] water(int p) {
			Path path = paths[p];
			int n = path.n;
			float[] floor = new float[n], ceiling = new float[n], low = new float[n], smooth = new float[n];
			for (int k = 0; k < n; k++) {
				float level = scale.surfaceY(path.meters[k]);
				smooth[k] = level;
				ceiling[k] = Float.POSITIVE_INFINITY;
				if (path.fixed[k]) {
					floor[k] = level;
					ceiling[k] = level;
					low[k] = Float.POSITIVE_INFINITY;
					continue;
				}
				double u = path.u[k], v = path.v[k], half = width.halfWidthPixels(path.discharge[k]);
				double across = Math.min(ground(u, v), Math.min(ground(u - half * path.dv[k], v + half * path.du[k]),
					ground(u + half * path.dv[k], v - half * path.du[k])));
				low[k] = (float) across - (scale.surfaceY(path.meters[k] + CellRivers.DROP) - level);
				float lake = lakes.level((int) Math.floor(u), (int) Math.floor(v));
				floor[k] = Float.isNaN(lake) ? Math.min(low[k] - cut, level) : scale.surfaceY(lake);
			}
			if (n > 0) {
				int main = rivers.joins(p);
				if (rivers.ending(p) == CellRivers.JOIN && main >= 0) {
					floor[n - 1] = Math.max(floor[n - 1], at(paths[main], path.joinAlong));
				} else if (rivers.ending(p) == CellRivers.POND) {
					floor[n - 1] = Math.max(floor[n - 1], scale.surfaceY(path.meters[n - 1]));
				}
			}
			for (int t = p + 1; t < paths.length; t++) {
				if (rivers.joins(t) == p && n > 0) {
					int k = (int) Math.clamp(Math.round(paths[t].joinAlong / step), 0, n - 1);
					ceiling[k] = Math.min(ceiling[k], paths[t].cap);
				}
			}

			float[] upper = new float[n], lower = new float[n];
			float before = Float.POSITIVE_INFINITY;
			for (int k = 0; k < n; k++) {
				before = Math.min(before, ceiling[k]);
				upper[k] = before;
			}
			float ahead = Float.POSITIVE_INFINITY, highest = Float.NEGATIVE_INFINITY;
			for (int k = n - 1; k >= 0; k--) {
				if (k + 1 < n) {
					ahead += ramp * (path.along[k + 1] - path.along[k]);
				}
				// The ramp never holds the water below its smooth level, which comes down as steeply as it must.
				ahead = path.fixed[k] ? ceiling[k] : Math.min(Math.max(ahead, smooth[k]), ceiling[k]);
				upper[k] = Math.min(upper[k], ahead);
				highest = Math.max(highest, Math.min(Math.max(floor[k], sea), upper[k]));
				lower[k] = highest;
			}
			float water = Float.POSITIVE_INFINITY;
			for (int k = 0; k < n; k++) {
				water = path.fixed[k] ? floor[k] : Math.max(lower[k], Math.min(upper[k], Math.min(water, low[k])));
				path.water[k] = water;
			}
			return path.water;
		}

		/** The ground at tile-local pixel {@code (u, v)}, in blocks, or the lake there, whichever is higher. */
		private double ground(double u, double v) {
			double y = ground.y((j0 + v) / pixelsPerBlock, (i0 + u) / pixelsPerBlock);
			float lake = lakes.level((int) Math.floor(u), (int) Math.floor(v));
			return Float.isNaN(lake) ? y : Math.max(y, scale.surfaceY(lake));
		}

		/** The height of a path's water {@code along} pixels down it. */
		private float at(Path path, double along) {
			if (path.n == 0) {
				return Float.NEGATIVE_INFINITY;
			}
			double k = Math.clamp(along / step, 0, path.n - 1);
			int a = (int) Math.floor(k), b = Math.min(a + 1, path.n - 1);
			return path.water[a] + (path.water[b] - path.water[a]) * (float) (k - a);
		}
	}

	/** One path's samples while its water is worked out. */
	private static final class Path {
		final int n;
		final float[] along;
		final float[] u;
		final float[] v;
		/** The way the river runs, a unit vector. */
		final float[] du;
		final float[] dv;
		/** The smooth level, meters. */
		final float[] meters;
		final float[] discharge;
		/** Whether the sample lies within {@link #EDGE} of a side, where the level is fixed. */
		final boolean[] fixed;
		final float[] water;
		/** The least level the river entered at, its tributaries included, in blocks. */
		float cap = Float.POSITIVE_INFINITY;
		/** How far along the river it joins this one joins it, pixels. */
		float joinAlong;

		Path(int n) {
			this.n = n;
			along = new float[n];
			u = new float[n];
			v = new float[n];
			du = new float[n];
			dv = new float[n];
			meters = new float[n];
			discharge = new float[n];
			fixed = new boolean[n];
			water = new float[n];
		}
	}
}
