package io.github.elpideus.diffusicraft.worldgen;

import io.github.elpideus.diffusicraft.terrain.diffusion.FastNoiseLite;
import io.github.elpideus.diffusicraft.worldgen.geology.GeoNoise;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.elpideus.diffusicraft.worldgen.TerrainSource.COARSE_ELEVATION;
import static io.github.elpideus.diffusicraft.worldgen.TerrainSource.COARSE_LOW_ELEVATION;
import static io.github.elpideus.diffusicraft.worldgen.TerrainSource.COARSE_TEMPERATURE;

/**
 * The long fjords: the troughs that the great outlet glaciers of the ice ages cut along a cold mountain coast's main
 * valleys, a hundred kilometres and more inland, over a shallow sill at the mouth and a basin up to
 * {@value #MAX_DEPTH} m deep behind it, between walls a kilometre high. {@link Glaciation} drowns every valley near
 * such a coast, but only as far as the coarse map's valley floors reach below the trough; these are the few valleys
 * the ice followed far inland, and they are cut as one continuous trough, so their water always reaches the sea.
 *
 * <p>Fjords are traced on the source's coarse map. A mouth is a coastal land pixel as cold as a fully glaciated coast
 * ({@link Glaciation#cold}), mountainous itself or straight inland, whose valley floor is the lowest within
 * {@value #SPACING} coarse pixels, give or take up to {@value #MOUTH_JITTER} m of noise, so mouths lie
 * {@value #SPACING} pixels apart or more. From there the fjord walks inland to the neighbour whose floor is lowest,
 * with a cost for turning, always moving farther from the mouth; within {@value #INLAND} pixels of the sea, farther
 * from it too. How far it may go is drawn for each mouth: most fjords are short, and a few reach {@value #REACH} coarse
 * pixels from their mouth (123 km in the game). It stops where no neighbour is still mountainous or high, cold, away
 * from the sea and with its floor under {@value #HEAD_FLOOR} m; a walk shorter than {@value #MIN_STEPS} steps is no
 * fjord. Its path, two pixels out into the sea, through the walk and {@value #HEAD_EXTENSION} pixels on past the head,
 * is jittered and smoothed; it does not meander, as ice cuts straight reaches.
 *
 * <p>The trough's floor lies {@value #SILL} m under the sea at the sill, sinks over the first pixel and a half to the
 * fjord's depth, {@value #DEPTH_BASE} m and {@value #DEPTH_PER_PIXEL} m more per coarse pixel of its length, and rises
 * over the last third to {@value #HEAD_DEPTH} m under the sea at the head; beyond, the valley climbs
 * {@value #HEAD_RISE} m per coarse pixel. The floor is {@value #MOUTH_WIDTH} m wide on either side of the path at the
 * mouth and {@value #HEAD_WIDTH} m at the head, and the walls rise from it in a U. Between the sill and the head, the
 * width and the depth wander, into narrows and broad basins. Along each shore the walls stand out and back by up to
 * {@value #ROUGHNESS} m, into pointed spurs and rounded coves, but never over the floor, so the water runs through to
 * the head. Ground above the trough sinks to it, with a smooth crease where they meet.
 *
 * <p>Arms branch off the trunk up its side valleys, where the glaciers that fed the great one cut troughs of their
 * own. Each point of the trunk between the mouth and the head may grow one, by a chance of {@value #ARM_CHANCE}, at
 * least {@value #ARM_SPACING} steps after the last, up to {@value #MAX_ARMS} arms. An arm's first step leaves the trunk
 * at 37° to 90° from its way inland, never back towards the sea. From there it walks like the trunk, to the lowest
 * neighbour farther from where it branched, never beside the trunk, an earlier arm or the sea, while its valley floor
 * climbs at most {@value #ARM_RISE} m per step above the trunk's. Its reach is drawn like the trunk's, up to half the
 * trunk's steps and {@value #ARM_REACH} coarse pixels; a walk shorter than {@value #ARM_MIN_STEPS} steps is no arm.
 * Its trough starts on the trunk's floor, {@value #ARM_WIDTH} m wide on either side, and sinks or rises from there to
 * a basin as deep as its own length makes it, behind a sill or a step, before it rises to its head like the trunk.
 *
 * <p>Fjords are traced in blocks of {@value #BLOCK} coarse pixels, each from a coarse map with a margin large enough
 * that every fjord depends only on the map around its mouth: neighbouring blocks agree to the bit.
 */
final class Fjords {
	/** How far inland a fjord reaches at most, in coarse pixels from its mouth in a straight line. */
	static final int REACH = 16;
	/** Mouths lie at least this many coarse pixels apart, along both axes. */
	static final int SPACING = 7;
	/** The fewest steps inland that make a fjord: shorter ones are left to {@link Glaciation}'s valleys. */
	static final int MIN_STEPS = 3;
	/** Noise in meters added to the floors that compete for a mouth, so a coast's mouths are not all evenly spaced. */
	static final float MOUTH_JITTER = 400f;
	/** The cost of a turn of 180°, in meters of valley floor: ice flows straight unless the valley turns. */
	static final float TURN = 1000f;
	/** Valley floors above this, in meters, are too high for a fjord: the walk stops below them. */
	static final float HEAD_FLOOR = 2500f;
	/**
	 * Coarse pixels from the sea within which each step of the walk must take a fjord farther from the sea, so it
	 * heads inland rather than along the coast. Beyond, it need only get farther from its mouth.
	 */
	static final int INLAND = 8;
	/** Ground this high in meters counts as high enough for the ice to carry on, even where it is not rugged. */
	static final float HIGH = 400f;
	/** Depth of the sill at the fjord's mouth, in meters. */
	static final float SILL = 100f;
	/** Depth of a fjord's basin, in meters, before its length adds to it. */
	static final float DEPTH_BASE = 200f;
	/** Meters the basin deepens by per coarse pixel of the fjord's length. */
	static final float DEPTH_PER_PIXEL = 35f;
	/** The deepest a fjord's basin gets, in meters. */
	static final float MAX_DEPTH = 1200f;
	/** Depth of the water at the head of a fjord, in meters. */
	static final float HEAD_DEPTH = 40f;
	/** Meters per coarse pixel that the valley climbs beyond the head. */
	static final float HEAD_RISE = 400f;
	/** Coarse pixels by which the valley carries on past the head. */
	static final float HEAD_EXTENSION = 1.5f;
	/** Half the width of the trough's floor at the mouth, in meters. */
	static final float MOUTH_WIDTH = 1200f;
	/** Half the width of the trough's floor at the head, in meters. */
	static final float HEAD_WIDTH = 400f;
	/** How the walls rise: {@code WALL * x²} meters at {@code x} meters from the floor, before they steepen. */
	static final float WALL = 6e-4f;
	/** Meters from the floor at which the walls rise twice as fast as the U alone, and ever faster beyond. */
	static final float WALL_STEEPEN = 2000f;
	/** Meters from the floor beyond which the walls stand higher than any ground: the trough ends there. */
	static final float WALL_REACH = 3000f;
	/** Meters over which the trough's walls blend into the ground they cut. */
	static final float SMOOTH = 40f;
	/** How far each coarse pixel's point on a path may move, in coarse pixels along each axis. */
	static final float VERTEX_JITTER = 0.3f;
	/** How much the trough widens and narrows along its length, as a share of its width. */
	static final float WIDTH_VARIATION = 0.45f;
	/** How much the basin's floor rises and sinks along its length, as a share of its depth. */
	static final float DEPTH_VARIATION = 0.2f;
	/** The frequency of the width's and the depth's variation, per coarse pixel. */
	static final float VARIATION_FREQUENCY = 0.5f;
	/** How far the walls wander in and out, at most, in meters: the spurs and coves along the shore. */
	static final float ROUGHNESS = 800f;
	/**
	 * Meters from the floor's edge over which the walls' wandering grows to its full {@link #ROUGHNESS}: the floor
	 * itself stays clear, so the water always runs through to the head.
	 */
	static final float ROUGHNESS_RAMP = 1000f;
	/** The length of the walls' largest spurs and coves along the shore, in meters. */
	static final double ROUGHNESS_WAVELENGTH = 3000.0;
	/** Octaves of the walls' roughness, each half the wavelength of the one before. */
	static final int ROUGHNESS_OCTAVES = 4;
	/** How far an arm reaches at most, in coarse pixels from where it branches off the trunk in a straight line. */
	static final int ARM_REACH = 6;
	/** The fewest steps that make an arm. */
	static final int ARM_MIN_STEPS = 2;
	/** The most arms a fjord has; under 8, so that {@link Trough#id} tells them apart. */
	static final int MAX_ARMS = 6;
	/** Steps along the trunk between the points where two of its arms branch, at least. */
	static final int ARM_SPACING = 2;
	/** The chance that a point of the trunk tries to grow an arm. */
	static final float ARM_CHANCE = 0.5f;
	/**
	 * Meters per step by which an arm's valley floor may climb above the trunk's where it branches: arms follow
	 * the side valleys about as low as the trunk, which the ice filled too.
	 */
	static final float ARM_RISE = 300f;
	/** The cosine of the narrowest angle between an arm's first step and the trunk: 37°. */
	static final float ARM_ALONG = 0.8f;
	/** Half the width of an arm's floor where it branches, in meters. */
	static final float ARM_WIDTH = 700f;
	/** Points of the smoothed path per coarse step. */
	static final int SAMPLES = 16;
	/** Side of the blocks of coarse pixels whose fjords are traced together, in coarse pixels. */
	static final int BLOCK = 16;
	/** A block holds every fjord whose mouth lies this many coarse pixels around it, or less. */
	static final int MOUTH_RANGE = REACH + ARM_REACH + 4;
	/** The margin of coarse map around a block that its fjords are traced from. */
	static final int MARGIN = MOUTH_RANGE + REACH + Math.max(INLAND, ARM_REACH + 1) + 4;
	/** Native pixels between the points at which the troughs are worked out; they are interpolated in between. */
	static final int CARVE_STEP = 4;
	/** Coarse pixels around a fjord's path within which its trough can lower the ground. */
	static final float INFLUENCE = 1f;

	/** {@link #carve}'s plane of meters from the edge of the nearest trough's floor, infinite beyond its walls. */
	static final int BEYOND = 0;
	/** {@link #carve}'s plane of that trough's floor, in meters. */
	static final int FLOOR = 1;
	/** {@link #carve}'s plane of how far that trough's wall stands out from its U, in meters: see {@link #rough}. */
	static final int ROUGH = 2;
	static final int PLANES = 3;

	private static final int MAX_BLOCKS = 64;
	private static final int[][] FOUR = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
	private static final int SALT_MOUTH = 1, SALT_ROW = 2, SALT_COLUMN = 3, SALT_REACH = 4, SALT_ARM = 5;
	private static final int SALT_ARM_REACH = 6;
	private static final long ROUGHNESS_SALT = 0x6a61676765644cL;

	/**
	 * One trough of a fjord, its trunk or one of its arms: the smoothed path, in coarse pixel units (pixel {@code ci}
	 * spans {@code [ci, ci + 1)}), with the floor of the trough in meters and half the floor's width in meters at each
	 * point. {@code key} names the fjord by its mouth's coarse pixel, {@code row << 32 | column}; {@code arm} is 0 for
	 * the trunk and counts the arms from 1. {@code mouth} is the index of the mouth's point, or of the point where the
	 * arm branches off the trunk, and {@code head} that of the head's.
	 */
	record Trough(long key, int arm, float[] rows, float[] columns, float[] floor, float[] halfWidth, int mouth,
				  int head, float minRow, float maxRow, float minColumn, float maxColumn) {
		/** A number for this trough alone, as long as mouths lie within 2²⁸ coarse pixels of the origin. */
		long id() {
			return key << 3 | arm;
		}

		boolean near(double ci0, double cj0, double ci1, double cj1) {
			return maxRow + INFLUENCE >= ci0 && minRow - INFLUENCE <= ci1
				&& maxColumn + INFLUENCE >= cj0 && minColumn - INFLUENCE <= cj1;
		}
	}

	private final TerrainSource source;
	private final long seed;
	private final FastNoiseLite variation;
	private final ConcurrentHashMap<Long, CompletableFuture<List<Trough>>> blocks = new ConcurrentHashMap<>();

	Fjords(TerrainSource source, long seed) {
		this.source = source;
		this.seed = seed;
		this.variation = new FastNoiseLite((int) (seed ^ (seed >>> 32)) ^ 0xf102d5);
		variation.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
		variation.SetFrequency(VARIATION_FREQUENCY);
	}

	/** The troughs that may reach into the coarse pixels {@code [ci0, ci1) x [cj0, cj1)}, in any order. */
	List<Trough> near(double ci0, double cj0, double ci1, double cj1) {
		List<Trough> found = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		int bi0 = Math.floorDiv((int) Math.floor(ci0 - INFLUENCE), BLOCK);
		int bi1 = Math.floorDiv((int) Math.floor(ci1 + INFLUENCE), BLOCK);
		int bj0 = Math.floorDiv((int) Math.floor(cj0 - INFLUENCE), BLOCK);
		int bj1 = Math.floorDiv((int) Math.floor(cj1 + INFLUENCE), BLOCK);
		for (int bi = bi0; bi <= bi1; bi++) {
			for (int bj = bj0; bj <= bj1; bj++) {
				for (Trough trough : block(bi, bj)) {
					if (trough.near(ci0, cj0, ci1, cj1) && seen.add(trough.id())) {
						found.add(trough);
					}
				}
			}
		}
		return found;
	}

	/** The troughs of the fjords whose mouths lie within {@link #MOUTH_RANGE} of block {@code (bi, bj)}. */
	List<Trough> block(int bi, int bj) {
		long key = ((long) bi << 32) | (bj & 0xffffffffL);
		CompletableFuture<List<Trough>> future = blocks.get(key);
		if (future == null) {
			CompletableFuture<List<Trough>> mine = new CompletableFuture<>();
			future = blocks.putIfAbsent(key, mine);
			if (future == null) {
				future = mine;
				try {
					mine.complete(trace(bi * BLOCK, bj * BLOCK));
				} catch (Throwable t) {
					blocks.remove(key, mine);
					mine.completeExceptionally(t);
				}
				if (blocks.size() > MAX_BLOCKS) {
					blocks.keySet().removeIf(k -> k != key);
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

	/** The troughs of the fjords whose mouths lie within {@link #MOUTH_RANGE} of the block at {@code (ci0, cj0)}. */
	private List<Trough> trace(int ci0, int cj0) {
		int size = BLOCK + 2 * MARGIN;
		Ground ground = new Ground(source.coarse(ci0 - MARGIN, cj0 - MARGIN, ci0 - MARGIN + size, cj0 - MARGIN + size),
			ci0 - MARGIN, cj0 - MARGIN, size);
		List<Trough> troughs = new ArrayList<>();
		for (int ci = ci0 - MOUTH_RANGE; ci < ci0 + BLOCK + MOUTH_RANGE; ci++) {
			for (int cj = cj0 - MOUTH_RANGE; cj < cj0 + BLOCK + MOUTH_RANGE; cj++) {
				if (mouth(ground, ci, cj)) {
					troughs.addAll(walk(ground, ci, cj));
				}
			}
		}
		return troughs;
	}

	private boolean candidate(Ground g, int ci, int cj) {
		if (!g.land(ci, cj) || Glaciation.cold(g.seaTemperature(ci, cj)) < 0.5f) {
			return false;
		}
		// Mountains on the coast, or straight inland of it: a coastal plain that only lies beside them is no mouth.
		for (int[] d : FOUR) {
			if (!g.land(ci + d[0], cj + d[1])
				&& Math.max(g.rugged(ci, cj), g.rugged(ci - d[0], cj - d[1])) >= 0.5f) {
				return true;
			}
		}
		return false;
	}

	private float mouthKey(Ground g, int ci, int cj) {
		return Math.max(0f, g.low(ci, cj)) + MOUTH_JITTER * unit(seed, ci, cj, SALT_MOUTH);
	}

	/** Whether a fjord opens at coarse pixel {@code (ci, cj)}: a candidate lower than all others near it. */
	private boolean mouth(Ground g, int ci, int cj) {
		if (!candidate(g, ci, cj)) {
			return false;
		}
		float key = mouthKey(g, ci, cj);
		for (int a = -SPACING; a <= SPACING; a++) {
			for (int b = -SPACING; b <= SPACING; b++) {
				if ((a != 0 || b != 0) && candidate(g, ci + a, cj + b)) {
					float other = mouthKey(g, ci + a, cj + b);
					if (other < key || other == key && (a < 0 || a == 0 && b < 0)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** The troughs of the fjord at the mouth {@code (ci, cj)}, its trunk first; none if its valley is too short. */
	private List<Trough> walk(Ground g, int ci, int cj) {
		// The sea the mouth opens onto: its lowest neighbour there.
		int sr = 0, sc = 0;
		float lowest = Float.POSITIVE_INFINITY;
		for (int[] d : FOUR) {
			if (!g.land(ci + d[0], cj + d[1]) && g.mean(ci + d[0], cj + d[1]) < lowest) {
				lowest = g.mean(ci + d[0], cj + d[1]);
				sr = d[0];
				sc = d[1];
			}
		}
		// How far this fjord's ice reached: most fjords are short, a few run the full reach.
		float luck = unit(seed, ci, cj, SALT_REACH);
		float reach = MIN_STEPS + (REACH - MIN_STEPS) * luck * luck;
		List<int[]> path = new ArrayList<>();
		path.add(new int[]{ci, cj});
		int pr = ci, pc = cj, dr = -sr, dc = -sc;
		for (int step = 1; ; step++) {
			int here = (pr - ci) * (pr - ci) + (pc - cj) * (pc - cj);
			float best = Float.POSITIVE_INFINITY;
			int br = 0, bc = 0;
			for (int a = -1; a <= 1; a++) {
				for (int b = -1; b <= 1; b++) {
					int nr = pr + a, nc = pc + b;
					int there = (nr - ci) * (nr - ci) + (nc - cj) * (nc - cj);
					if (there <= here || there > reach * reach || !g.land(nr, nc)) {
						continue;
					}
					float low = g.low(nr, nc);
					if (low > HEAD_FLOOR || step > 2 && g.seaBeside(nr, nc)
						|| g.seaDistance(nr, nc) <= g.seaDistance(pr, pc) && g.seaDistance(pr, pc) <= INLAND
						|| !icy(g, nr, nc)) {
						continue;
					}
					float cos = (a * dr + b * dc) / (float) Math.sqrt((a * a + b * b) * (dr * dr + dc * dc));
					float cost = Math.max(0f, low) + TURN * 0.5f * (1f - cos);
					if (cost < best) {
						best = cost;
						br = nr;
						bc = nc;
					}
				}
			}
			if (best == Float.POSITIVE_INFINITY) {
				break;
			}
			dr = br - pr;
			dc = bc - pc;
			pr = br;
			pc = bc;
			path.add(new int[]{pr, pc});
		}
		if (path.size() - 1 < MIN_STEPS) {
			return List.of();
		}

		// The points: two out at sea, the walk, and one past the head.
		int n = path.size() + 3;
		float[] pointRow = new float[n], pointColumn = new float[n];
		pointRow[0] = ci + 2 * sr + 0.5f;
		pointColumn[0] = cj + 2 * sc + 0.5f;
		pointRow[1] = ci + sr + 0.5f;
		pointColumn[1] = cj + sc + 0.5f;
		for (int k = 0; k < path.size(); k++) {
			point(path.get(k), pointRow, pointColumn, k + 2);
		}
		long key = ((long) ci << 32) | (cj & 0xffffffffL);
		List<Trough> troughs = new ArrayList<>();
		Trough trunk = trough(key, 0, pointRow, pointColumn, 2, -SILL, MOUTH_WIDTH);
		troughs.add(trunk);

		// The arms, each from a point of the trunk between the mouth and the head, and at most half as long.
		float most = Math.min(ARM_REACH, (path.size() - 1) / 2f);
		if (most < ARM_MIN_STEPS) {
			return troughs;
		}
		Set<Long> taken = new HashSet<>();
		for (int[] p : path) {
			taken.add(cell(p[0], p[1]));
		}
		int last = -ARM_SPACING;
		for (int p = 1; p + 1 < path.size() && troughs.size() <= MAX_ARMS; p++) {
			int[] at = path.get(p);
			if (p - last < ARM_SPACING || unit(seed, at[0], at[1], SALT_ARM) >= ARM_CHANCE) {
				continue;
			}
			List<int[]> arm = arm(g, path, p, most, taken);
			if (arm.isEmpty()) {
				continue;
			}
			int m = arm.size() + 1;
			float[] armRow = new float[m], armColumn = new float[m];
			for (int k = 0; k < arm.size(); k++) {
				point(arm.get(k), armRow, armColumn, k);
				taken.add(cell(arm.get(k)[0], arm.get(k)[1]));
			}
			troughs.add(trough(key, troughs.size(), armRow, armColumn, 0, trunk.floor()[(p + 2) * SAMPLES], ARM_WIDTH));
			last = p;
		}
		return troughs;
	}

	/**
	 * The cells of the arm that branches off the trunk {@code path} at its {@code p}th cell, from that cell on, reaching
	 * {@code most} coarse pixels at most; none if no side valley there is low and long enough.
	 */
	private List<int[]> arm(Ground g, List<int[]> path, int p, float most, Set<Long> taken) {
		int[] at = path.get(p), before = path.get(p - 1), after = path.get(p + 1);
		int jr = at[0], jc = at[1];
		// The trunk's way inland here.
		int tr = after[0] - before[0], tc = after[1] - before[1];
		float floor = Math.max(0f, g.low(jr, jc));
		float luck = unit(seed, jr, jc, SALT_ARM_REACH);
		float reach = ARM_MIN_STEPS + (most - ARM_MIN_STEPS) * luck * luck;
		List<int[]> cells = new ArrayList<>();
		cells.add(at);
		int pr = jr, pc = jc, dr = 0, dc = 0;
		for (int step = 1; ; step++) {
			int here = (pr - jr) * (pr - jr) + (pc - jc) * (pc - jc);
			float ceiling = Math.min(HEAD_FLOOR, floor + ARM_RISE * step);
			float best = Float.POSITIVE_INFINITY;
			int br = 0, bc = 0;
			for (int a = -1; a <= 1; a++) {
				for (int b = -1; b <= 1; b++) {
					int nr = pr + a, nc = pc + b;
					int there = (nr - jr) * (nr - jr) + (nc - jc) * (nc - jc);
					if (there <= here || there > reach * reach || !g.land(nr, nc) || g.low(nr, nc) > ceiling
						|| g.seaBeside(nr, nc) || !icy(g, nr, nc)) {
						continue;
					}
					float cost = Math.max(0f, g.low(nr, nc));
					if (step == 1) {
						// A side valley: off the trunk, at 37° to 90° from it, never back towards the sea.
						float cos = (a * tr + b * tc) / (float) Math.sqrt((a * a + b * b) * (tr * tr + tc * tc));
						if (taken.contains(cell(nr, nc)) || cos < 0f || cos > ARM_ALONG) {
							continue;
						}
					} else {
						if (beside(taken, nr, nc)) {
							continue;
						}
						float cos = (a * dr + b * dc) / (float) Math.sqrt((a * a + b * b) * (dr * dr + dc * dc));
						cost += TURN * 0.5f * (1f - cos);
					}
					if (cost < best) {
						best = cost;
						br = nr;
						bc = nc;
					}
				}
			}
			if (best == Float.POSITIVE_INFINITY) {
				break;
			}
			dr = br - pr;
			dc = bc - pc;
			pr = br;
			pc = bc;
			cells.add(new int[]{pr, pc});
		}
		return cells.size() - 1 < ARM_MIN_STEPS ? List.of() : cells;
	}

	/** Whether the ice that cut a fjord's trough reached coarse pixel {@code (ci, cj)}: cold, and high or rugged. */
	private static boolean icy(Ground g, int ci, int cj) {
		return Glaciation.cold(g.seaTemperature(ci, cj)) >= 0.25f
			&& (g.mountains(ci, cj) >= 0.25f || g.mean(ci, cj) >= HIGH);
	}

	/** Whether coarse pixel {@code (ci, cj)} or one of its eight neighbours is among {@code cells}. */
	private static boolean beside(Set<Long> cells, int ci, int cj) {
		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				if (cells.contains(cell(ci + a, cj + b))) {
					return true;
				}
			}
		}
		return false;
	}

	private static long cell(int ci, int cj) {
		return ((long) ci << 32) | (cj & 0xffffffffL);
	}

	/** Sets point {@code k} to the middle of coarse pixel {@code cell}, jittered. */
	private void point(int[] cell, float[] pointRow, float[] pointColumn, int k) {
		pointRow[k] = cell[0] + 0.5f + VERTEX_JITTER * (2f * unit(seed, cell[0], cell[1], SALT_ROW) - 1f);
		pointColumn[k] = cell[1] + 0.5f + VERTEX_JITTER * (2f * unit(seed, cell[0], cell[1], SALT_COLUMN) - 1f);
	}

	/**
	 * The trough along {@code pointRow, pointColumn}, whose last point is left to this, past the head. It opens at
	 * point {@code mouthPoint}, where its floor lies at {@code start} meters, {@code width} meters wide on either side.
	 */
	private Trough trough(long key, int arm, float[] pointRow, float[] pointColumn, int mouthPoint, float start,
						  float width) {
		int n = pointRow.length;
		float lastRow = pointRow[n - 2] - pointRow[n - 3], lastColumn = pointColumn[n - 2] - pointColumn[n - 3];
		float last = (float) Math.sqrt(lastRow * lastRow + lastColumn * lastColumn);
		pointRow[n - 1] = pointRow[n - 2] + HEAD_EXTENSION * lastRow / last;
		pointColumn[n - 1] = pointColumn[n - 2] + HEAD_EXTENSION * lastColumn / last;

		// Catmull-Rom through the points. Ice cuts straight reaches: no meanders.
		int dense = (n - 1) * SAMPLES + 1;
		float[] rows = new float[dense], columns = new float[dense];
		for (int k = 0; k < n - 1; k++) {
			int k0 = Math.max(0, k - 1), k3 = Math.min(n - 1, k + 2);
			for (int s = 0; s < SAMPLES; s++) {
				float t = (float) s / SAMPLES;
				rows[k * SAMPLES + s] = catmullRom(pointRow[k0], pointRow[k], pointRow[k + 1], pointRow[k3], t);
				columns[k * SAMPLES + s] =
					catmullRom(pointColumn[k0], pointColumn[k], pointColumn[k + 1], pointColumn[k3], t);
			}
		}
		rows[dense - 1] = pointRow[n - 1];
		columns[dense - 1] = pointColumn[n - 1];

		// Distances along the path, in coarse pixels, and the trough's profile.
		float[] along = new float[dense];
		for (int k = 1; k < dense; k++) {
			float a = rows[k] - rows[k - 1], b = columns[k] - columns[k - 1];
			along[k] = along[k - 1] + (float) Math.sqrt(a * a + b * b);
		}
		int mouth = mouthPoint * SAMPLES, head = (n - 2) * SAMPLES;
		float length = along[head] - along[mouth];
		float depth = Math.min(MAX_DEPTH, DEPTH_BASE + DEPTH_PER_PIXEL * length);
		float sillLength = Math.min(1.5f, 0.3f * length), headLength = Math.max(1f, length / 3f);
		float[] floor = new float[dense], halfWidth = new float[dense];
		for (int k = 0; k < dense; k++) {
			float u = along[k] - along[mouth];
			if (u <= 0f) {
				floor[k] = start;
				halfWidth[k] = width;
			} else if (u <= length) {
				// Narrows and broad basins: the width and depth wander, but not at the sill or the head.
				float vr = rows[k], vc = columns[k];
				float inside = Glaciation.smoothstep(0f, sillLength, u)
					* (1f - Glaciation.smoothstep(length - headLength, length, u));
				float basin = start + (-depth * (1f + DEPTH_VARIATION * inside * variation.GetNoise(vr + 517f, vc))
					- start) * Glaciation.smoothstep(0f, sillLength, u);
				floor[k] = basin + (-HEAD_DEPTH - basin) * Glaciation.smoothstep(length - headLength, length, u);
				halfWidth[k] = (width + (HEAD_WIDTH - width) * u / length)
					* (1f + WIDTH_VARIATION * Glaciation.smoothstep(0f, sillLength, u)
					* variation.GetNoise(vr, vc - 211f));
			} else {
				floor[k] = -HEAD_DEPTH + HEAD_RISE * (u - length);
				halfWidth[k] = HEAD_WIDTH;
			}
		}
		float minRow = Float.POSITIVE_INFINITY, maxRow = Float.NEGATIVE_INFINITY;
		float minColumn = Float.POSITIVE_INFINITY, maxColumn = Float.NEGATIVE_INFINITY;
		for (int k = 0; k < dense; k++) {
			minRow = Math.min(minRow, rows[k]);
			maxRow = Math.max(maxRow, rows[k]);
			minColumn = Math.min(minColumn, columns[k]);
			maxColumn = Math.max(maxColumn, columns[k]);
		}
		return new Trough(key, arm, rows, columns, floor, halfWidth, mouth, head, minRow, maxRow, minColumn, maxColumn);
	}

	/**
	 * The {@code troughs} over native pixels {@code [i0, i0 + h) x [j0, j0 + w)}, in {@value #PLANES} planes of
	 * {@code h × w}, row-major: {@link #BEYOND}, {@link #FLOOR} and {@link #ROUGH}. Where troughs overlap, the lowest
	 * wins. {@code scale} is the native pixels per coarse pixel. The trough at a pixel is {@code floor + }{@link #wall}
	 * {@code (}{@link #rough}{@code (beyond, rough))}.
	 *
	 * <p>The walls stand out from their U and back by up to {@value #ROUGHNESS} m, into spurs and coves of every size
	 * down to a few hundred meters: folded fractal noise along each shore, so the spurs end in points. The noise runs
	 * along the shore, not across it, so the walls still rise all the way up from the water and hold no pits.
	 *
	 * <p>All of it is smooth at a few pixels, so it is worked out every {@value #CARVE_STEP} pixels, on a lattice fixed
	 * in the world so that overlapping windows agree to the bit, and interpolated in between.
	 */
	float[] carve(List<Trough> troughs, int scale, int i0, int j0, int h, int w) {
		int li0 = Math.floorDiv(i0, CARVE_STEP), lj0 = Math.floorDiv(j0, CARVE_STEP);
		int lh = Math.floorDiv(i0 + h - 1, CARVE_STEP) - li0 + 2, lw = Math.floorDiv(j0 + w - 1, CARVE_STEP) - lj0 + 2;
		int nodes = lh * lw;
		float[] height = new float[nodes], beyond = new float[nodes], bottom = new float[nodes];
		float[] along = new float[nodes];
		long[] shore = new long[nodes];
		Arrays.fill(height, Float.POSITIVE_INFINITY);
		Arrays.fill(beyond, Float.POSITIVE_INFINITY);
		float metres = (float) WorldScale.METERS_PER_PIXEL;
		for (Trough trough : troughs) {
			float[] rows = trough.rows(), columns = trough.columns(), floor = trough.floor();
			float[] halfWidth = trough.halfWidth();
			float walked = 0f;
			for (int k = 0; k + 1 < rows.length; k++) {
				// The segment in native pixels, from the centre of lattice point (0, 0).
				double ar = (double) rows[k] * scale - (li0 * CARVE_STEP + 0.5);
				double ac = (double) columns[k] * scale - (lj0 * CARVE_STEP + 0.5);
				double br = (double) rows[k + 1] * scale - (li0 * CARVE_STEP + 0.5);
				double bc = (double) columns[k + 1] * scale - (lj0 * CARVE_STEP + 0.5);
				float sr = (float) (br - ar), sc = (float) (bc - ac), squared = sr * sr + sc * sc;
				float segment = (float) Math.sqrt(squared) * metres;
				double reach = (Math.max(halfWidth[k], halfWidth[k + 1]) + WALL_REACH) / metres;
				int a0 = Math.max(0, (int) Math.floor((Math.min(ar, br) - reach) / CARVE_STEP));
				int a1 = Math.min(lh, (int) Math.ceil((Math.max(ar, br) + reach) / CARVE_STEP) + 1);
				int b0 = Math.max(0, (int) Math.floor((Math.min(ac, bc) - reach) / CARVE_STEP));
				int b1 = Math.min(lw, (int) Math.ceil((Math.max(ac, bc) + reach) / CARVE_STEP) + 1);
				for (int a = a0; a < a1; a++) {
					float pr = (float) (a * CARVE_STEP - ar);
					for (int b = b0; b < b1; b++) {
						float pc = (float) (b * CARVE_STEP - ac);
						float t = squared > 0f ? Math.clamp((pr * sr + pc * sc) / squared, 0f, 1f) : 0f;
						float dr = pr - t * sr, dc = pc - t * sc;
						float half = halfWidth[k] + (halfWidth[k + 1] - halfWidth[k]) * t;
						float x = (float) Math.sqrt(dr * dr + dc * dc) * metres - half;
						if (x > WALL_REACH) {
							continue;
						}
						float base = floor[k] + (floor[k + 1] - floor[k]) * t;
						float z = base + wall(x);
						int node = a * lw + b;
						if (z < height[node]) {
							height[node] = z;
							beyond[node] = x;
							bottom[node] = base;
							along[node] = walked + t * segment;
							// Each shore of each trough has noise of its own.
							shore[node] = trough.id() * 2 + (sr * pc - sc * pr >= 0f ? 1 : 0);
						}
					}
				}
				walked += segment;
			}
		}
		float[] rough = new float[nodes];
		for (int node = 0; node < nodes; node++) {
			if (beyond[node] != Float.POSITIVE_INFINITY) {
				rough[node] = roughness(shore[node], along[node]);
			}
		}

		int plane = h * w;
		float[] out = new float[PLANES * plane];
		Arrays.fill(out, BEYOND * plane, (BEYOND + 1) * plane, Float.POSITIVE_INFINITY);
		for (int r = 0; r < h; r++) {
			int i = i0 + r, a = Math.floorDiv(i, CARVE_STEP) - li0;
			float u = Math.floorMod(i, CARVE_STEP) / (float) CARVE_STEP;
			for (int c = 0; c < w; c++) {
				int j = j0 + c, b = Math.floorDiv(j, CARVE_STEP) - lj0;
				int n00 = a * lw + b, n10 = n00 + lw;
				if (beyond[n00] == Float.POSITIVE_INFINITY || beyond[n00 + 1] == Float.POSITIVE_INFINITY
					|| beyond[n10] == Float.POSITIVE_INFINITY || beyond[n10 + 1] == Float.POSITIVE_INFINITY) {
					continue;
				}
				float v = Math.floorMod(j, CARVE_STEP) / (float) CARVE_STEP;
				int px = r * w + c;
				out[BEYOND * plane + px] = bilinear(beyond, n00, lw, u, v);
				out[FLOOR * plane + px] = bilinear(bottom, n00, lw, u, v);
				out[ROUGH * plane + px] = bilinear(rough, n00, lw, u, v);
			}
		}
		return out;
	}

	/**
	 * How far the wall of a shore stands out from its U, {@code along} meters along it, in meters within
	 * {@code ±}{@value #ROUGHNESS}. {@code shore} tells the shores apart.
	 */
	private float roughness(long shore, float along) {
		double n = GeoNoise.fractal(seed ^ ROUGHNESS_SALT ^ shore * 0x9e3779b97f4a7c15L, along / ROUGHNESS_WAVELENGTH,
			0.5, ROUGHNESS_OCTAVES);
		return ROUGHNESS * (float) (1.0 - 2.0 * Math.min(1.0, 2.0 * Math.abs(n)));
	}

	/**
	 * Meters from the edge of the floor that the wall at {@code beyond} meters stands at, where it stands out by
	 * {@code rough}: by none of it on the floor, and by all of it {@value #ROUGHNESS_RAMP} m out. It grows with
	 * {@code beyond}, by at least {@code 1 − }{@value #ROUGHNESS}{@code  / }{@value #ROUGHNESS_RAMP} m a meter, and
	 * with {@code rough}.
	 */
	static float rough(float beyond, float rough) {
		return beyond + rough * Math.clamp(beyond / ROUGHNESS_RAMP, 0f, 1f);
	}

	private static float bilinear(float[] nodes, int n00, int width, float u, float v) {
		return lerp(lerp(nodes[n00], nodes[n00 + 1], v), lerp(nodes[n00 + width], nodes[n00 + width + 1], v), u);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** How high the walls stand {@code x} meters from the trough's floor, in meters above it. */
	static float wall(float x) {
		if (x <= 0f) {
			return 0f;
		}
		float q = x / WALL_STEEPEN;
		return WALL * x * x * (1f + q * q);
	}

	/**
	 * How far the ice lowered ground at sea level where a trough's floor lies at {@code floor} meters: down to the
	 * floor, and {@value #SMOOTH} m more, for the crease at its edges and for the cut's interpolation between points
	 * of the floor that lie higher.
	 */
	static float cut(float floor) {
		return Math.max(0f, SMOOTH - floor);
	}

	/** {@code min(a, b)}, with the crease smoothed over {@value #SMOOTH} m. */
	static float smoothMin(float a, float b) {
		float h = Math.max(SMOOTH - Math.abs(a - b), 0f) / SMOOTH;
		return Math.min(a, b) - h * h * SMOOTH * 0.25f;
	}

	private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
		float t2 = t * t, t3 = t2 * t;
		return 0.5f * (2f * p1 + (p2 - p0) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2
			+ (3f * p1 - p0 - 3f * p2 + p3) * t3);
	}

	/** A number in [0, 1) that depends only on the arguments. */
	static float unit(long seed, int ci, int cj, int salt) {
		long h = mix(seed ^ mix(((long) ci << 32 | (cj & 0xffffffffL)) + salt * 0x9e3779b97f4a7c15L));
		return (h >>> 40) * 0x1.0p-24f;
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		return z ^ (z >>> 31);
	}

	/** The source's coarse map over a square window, read by absolute coarse pixel. */
	private static final class Ground {
		private final int ci0, cj0, size;
		private final float[] mean, low, seaTemperature, rugged, seaDistance;

		Ground(float[] map, int ci0, int cj0, int size) {
			this.ci0 = ci0;
			this.cj0 = cj0;
			this.size = size;
			int plane = size * size;
			mean = new float[plane];
			low = new float[plane];
			seaTemperature = new float[plane];
			rugged = new float[plane];
			seaDistance = new float[plane];
			Arrays.fill(seaDistance, Float.NaN);
			for (int px = 0; px < plane; px++) {
				mean[px] = map[COARSE_ELEVATION * plane + px];
				low[px] = map[COARSE_LOW_ELEVATION * plane + px];
				seaTemperature[px] = map[COARSE_TEMPERATURE * plane + px]
					+ Glaciation.STANDARD_LAPSE * Math.max(0f, mean[px]);
				rugged[px] = mean[px] - Math.max(0f, low[px]);
			}
		}

		private int index(int ci, int cj) {
			int r = ci - ci0, c = cj - cj0;
			if (r < 0 || r >= size || c < 0 || c >= size) {
				throw new IllegalStateException("Coarse pixel " + ci + ", " + cj + " is outside the fjords' margin");
			}
			return r * size + c;
		}

		float mean(int ci, int cj) {
			return mean[index(ci, cj)];
		}

		float low(int ci, int cj) {
			return low[index(ci, cj)];
		}

		float seaTemperature(int ci, int cj) {
			return seaTemperature[index(ci, cj)];
		}

		/** Coarse pixels to the nearest sea, or {@link #INLAND} + 1 if it is farther. */
		float seaDistance(int ci, int cj) {
			int px = index(ci, cj);
			if (Float.isNaN(seaDistance[px])) {
				int nearest = (INLAND + 1) * (INLAND + 1);
				for (int a = -INLAND; a <= INLAND; a++) {
					for (int b = -INLAND; b <= INLAND; b++) {
						if (a * a + b * b < nearest && !land(ci + a, cj + b)) {
							nearest = a * a + b * b;
						}
					}
				}
				seaDistance[px] = (float) Math.sqrt(nearest);
			}
			return seaDistance[px];
		}

		boolean land(int ci, int cj) {
			return mean(ci, cj) >= 0f;
		}

		/** Whether the pixel or one of its eight neighbours is sea. */
		boolean seaBeside(int ci, int cj) {
			for (int a = -1; a <= 1; a++) {
				for (int b = -1; b <= 1; b++) {
					if (!land(ci + a, cj + b)) {
						return true;
					}
				}
			}
			return false;
		}

		/** How mountainous the pixel itself is, from how far it rises above its lowest ground or the sea, in [0, 1]. */
		float rugged(int ci, int cj) {
			return Glaciation.smoothstep(Glaciation.RUGGED_LOW, Glaciation.RUGGED_HIGH, rugged[index(ci, cj)]);
		}

		/** As in {@link Glaciation#ice}: how far the pixel or a neighbour rises above its lowest ground or the sea. */
		float mountains(int ci, int cj) {
			float most = Float.NEGATIVE_INFINITY;
			for (int a = -1; a <= 1; a++) {
				for (int b = -1; b <= 1; b++) {
					most = Math.max(most, rugged[index(ci + a, cj + b)]);
				}
			}
			return Glaciation.smoothstep(Glaciation.RUGGED_LOW, Glaciation.RUGGED_HIGH, most);
		}
	}
}
