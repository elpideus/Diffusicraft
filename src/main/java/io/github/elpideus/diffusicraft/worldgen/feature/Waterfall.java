package io.github.elpideus.diffusicraft.worldgen.feature;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * Where a waterfall goes over a steep face in a chunk, block by block, worked out from the heights of the ground alone.
 * {@link WaterfallFeature} places it.
 *
 * <p>A stream one to three blocks wide runs a few blocks over level ground to the edge of a face, sunk into the ground
 * so that its banks hold it, and spills over. The face falls at least one and a half blocks for every block out, and
 * seldom sheer: where it slopes, the falls have cut a gorge back into it, a block wider than they are on either side,
 * whose floor lies level with the foot of the face, so that they drop straight down its head. They land in a plunge
 * pool they dig, or in the sea or a lake. The face must have land behind its edge that does not fall away, as the step
 * of a valley or a bench has, so no stream springs from a crest. The ground around the stream and the pool must hold
 * their water on every side, as the game's water spreads into any open block beside it, and no ground is cut away
 * from water beside it.
 *
 * <p>Everything lies within a few blocks of the chunk, in the 3×3 chunks a feature may write to. Pure, so it is
 * unit-tested without a world.
 */
final class Waterfall {
	/** No water over a column's ground. */
	static final int DRY = Integer.MIN_VALUE;
	/** Columns tried for the edge of a cliff in each chunk. */
	static final int ATTEMPTS = 24;
	/** The highest drops checked in turn, until one takes a waterfall. */
	private static final int CANDIDATES = 4;
	/** Most blocks of ground cut away over the stream and the pool, to keep them level. */
	static final int CUT = 3;
	/** Longest gorge, from the edge to the foot of the face, in blocks. */
	static final int MAX_RUN = 12;
	/** Widest waterfall, in blocks. */
	static final int MAX_WIDTH = 3;
	/** Longest stream behind the edge, in blocks. */
	static final int MAX_STREAM = 6;
	/** How far back the land behind the edge must not fall away, in blocks. */
	private static final int[] BEHIND = {4, 8};
	private static final int[] DX = {1, 0, -1, 0};
	private static final int[] DZ = {0, 1, 0, -1};

	/** The ground the plan is drawn on. */
	interface Ground {
		/** The height of the highest solid block of a column. */
		int top(int x, int z);

		/** The height of the surface of the water over a column's ground, or {@link #DRY}. */
		int water(int x, int z);
	}

	/** Water sources: the stream and the plunge pool. */
	final LongArrayList sources = new LongArrayList();
	/** Ground cut away over the stream, the gorge and the pool. */
	final LongArrayList cut = new LongArrayList();
	/** The water spilling over the edge, a block out from it. */
	final LongArrayList spilling = new LongArrayList();
	/** The falling water, from under the spill to the foot. */
	final LongArrayList falling = new LongArrayList();
	/** Where the water runs on once the chunk ticks: each lip of the stream, and the foot of each fall. */
	final LongArrayList ticks = new LongArrayList();
	/** The level of the stream, on the edge's ground. */
	int y;
	/** How far the gorge runs back from the foot of the face to the falls, in blocks: 0 under a sheer cliff. */
	int gorge;
	/** How far the water falls to the foot, the pool or the water below, in blocks. */
	int drop;
	/** How many blocks wide it falls. */
	int width;
	/** The middle of the edge. */
	BlockPos lip;
	/** Whether it falls into a pool of its own. */
	boolean pool;

	private Waterfall() {
	}

	/**
	 * A waterfall over a cliff of the chunk whose lowest block is at {@code (x0, z0)}, at least {@code minDrop} blocks
	 * tall, or {@code null} if no cliff of it takes one. The highest drops are tried first.
	 */
	static @Nullable Waterfall plan(Ground ground, int x0, int z0, int minDrop, RandomSource random) {
		int[] xs = new int[ATTEMPTS], zs = new int[ATTEMPTS], dirs = new int[ATTEMPTS], runs = new int[ATTEMPTS];
		int[] scores = new int[ATTEMPTS];
		int found = 0;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			int x = x0 + random.nextInt(16), z = z0 + random.nextInt(16);
			if (ground.water(x, z) != DRY) {
				continue;
			}
			int top = ground.top(x, z);
			int best = Integer.MIN_VALUE, dir = -1, run = 0;
			for (int d = 0; d < 4; d++) {
				int foot = face(ground, x, z, d, top, minDrop);
				int score = foot > 0 ? score(top - level(ground, x + foot * DX[d], z + foot * DZ[d]), foot) : best;
				if (score > best) {
					best = score;
					dir = d;
					run = foot;
				}
			}
			if (dir < 0) {
				continue;
			}
			// Sorted best first.
			int at = found++;
			while (at > 0 && scores[at - 1] < best) {
				xs[at] = xs[at - 1];
				zs[at] = zs[at - 1];
				dirs[at] = dirs[at - 1];
				runs[at] = runs[at - 1];
				scores[at] = scores[at - 1];
				at--;
			}
			xs[at] = x;
			zs[at] = z;
			dirs[at] = dir;
			runs[at] = run;
			scores[at] = best;
		}
		for (int k = 0; k < Math.min(found, CANDIDATES); k++) {
			Waterfall fall = new Waterfall();
			if (fall.build(ground, xs[k], zs[k], dirs[k], runs[k], minDrop, random)) {
				return fall;
			}
		}
		return null;
	}

	/**
	 * How many blocks out from {@code (x, z)} towards {@code dir} the foot of the face below it lies, or 0 if there is
	 * none: the column of the best {@linkplain #score score} with a drop of at least {@code minDrop}, down a face that
	 * falls from the first block out, falls at least one and a half blocks for every block out, never rises, and ends
	 * at water.
	 */
	private static int face(Ground ground, int x, int z, int dir, int top, int minDrop) {
		int foot = 0, best = Integer.MIN_VALUE, prev = top;
		for (int out = 1; out <= MAX_RUN; out++) {
			int cx = x + out * DX[dir], cz = z + out * DZ[dir];
			int level = level(ground, cx, cz);
			if (level > prev || level == top) {
				break;
			}
			int drop = top - level;
			if (drop >= minDrop && 2 * drop >= 3 * out && score(drop, out) > best) {
				best = score(drop, out);
				foot = out;
			}
			if (ground.water(cx, cz) != DRY) {
				break;
			}
			prev = level;
		}
		return foot;
	}

	/**
	 * How good a fall of {@code drop} blocks at the head of a gorge {@code run} blocks long is: the higher the better,
	 * and the shorter the gorge, so that a fall stands at the edge of a face rather than back from it, where a bench
	 * lies level with the edge.
	 */
	private static int score(int drop, int run) {
		return 2 * drop - run;
	}

	/** The height of the ground or of the water over it. */
	private static int level(Ground ground, int x, int z) {
		return Math.max(ground.top(x, z), ground.water(x, z));
	}

	/**
	 * Lays out a waterfall from the edge at {@code (x, z)}, falling towards {@code dir} down a face whose foot lies
	 * {@code run} blocks out; false if it cannot hold.
	 */
	private boolean build(Ground ground, int x, int z, int dir, int run, int minDrop, RandomSource random) {
		y = ground.top(x, z);
		gorge = run - 1;
		int foot = level(ground, x + run * DX[dir], z + run * DZ[dir]);
		int dx = DX[dir], dz = DZ[dir];
		// Along the edge.
		int px = -dz, pz = dx;
		for (int back : BEHIND) {
			if (ground.top(x - back * dx, z - back * dz) < y - 1) {
				return false;
			}
		}

		// The lips, grown from the middle to either side, then trimmed where the ground beside them would let the
		// stream out: a lip left out is then the bank.
		int want = 1 + random.nextInt(MAX_WIDTH);
		int lo = 0, hi = 0;
		boolean up = true, down = true;
		while (hi - lo + 1 < want && (up || down)) {
			if (up) {
				up = lip(ground, x + (hi + 1) * px, z + (hi + 1) * pz, dx, dz, minDrop, foot);
				hi += up ? 1 : 0;
			}
			if (down && hi - lo + 1 < want) {
				down = lip(ground, x + (lo - 1) * px, z + (lo - 1) * pz, dx, dz, minDrop, foot);
				lo -= down ? 1 : 0;
			}
		}
		if (ground.top(x + (hi + 1) * px, z + (hi + 1) * pz) < y) {
			if (hi == 0) {
				return false;
			}
			hi--;
		}
		if (ground.top(x + (lo - 1) * px, z + (lo - 1) * pz) < y) {
			if (lo == 0) {
				return false;
			}
			lo++;
		}

		// The stream behind the lips: rows that lie level with them, between banks that hold it, and ground behind
		// its last row that holds it too.
		int wantRows = 1 + random.nextInt(MAX_STREAM);
		int rows = 0;
		for (int row = 1; row <= wantRows && streamRow(ground, x, z, row, lo, hi, dx, dz, px, pz); row++) {
			rows = row;
		}
		while (rows >= 0 && !heldBehind(ground, x, z, rows, lo, hi, dx, dz, px, pz)) {
			rows--;
		}
		if (rows < 0) {
			return false;
		}

		width = hi - lo + 1;
		lip = new BlockPos(x + (lo + hi) / 2 * px, y, z + (lo + hi) / 2 * pz);
		for (int row = 0; row <= rows; row++) {
			for (int j = lo; j <= hi; j++) {
				int cx = x - row * dx + j * px, cz = z - row * dz + j * pz;
				sources.add(BlockPos.asLong(cx, y, cz));
				for (int cy = y + 1; cy <= ground.top(cx, cz); cy++) {
					cut.add(BlockPos.asLong(cx, cy, cz));
				}
			}
		}

		// The gorge, from the falls out to the foot of the face, a block wider than they are on either side where the
		// ground lets it be.
		LongOpenHashSet gorged = new LongOpenHashSet();
		if (gorge > 0) {
			if (!gorgeLine(ground, x, z, dx, dz, foot)) {
				return false;
			}
			int from = gorgeLine(ground, x + (lo - 1) * px, z + (lo - 1) * pz, dx, dz, foot) ? lo - 1 : lo;
			int to = gorgeLine(ground, x + (hi + 1) * px, z + (hi + 1) * pz, dx, dz, foot) ? hi + 1 : hi;
			for (int j = from; j <= to; j++) {
				for (int out = 1; out <= gorge; out++) {
					int cx = x + out * dx + j * px, cz = z + out * dz + j * pz;
					gorged.add(BlockPos.asLong(cx, 0, cz));
					for (int cy = foot + 1; cy <= ground.top(cx, cz); cy++) {
						cut.add(BlockPos.asLong(cx, cy, cz));
					}
				}
			}
		}
		Ground carved = gorged.isEmpty() ? ground : new Carved(ground, gorged, foot);

		// The pool at the foot of the falls, where every one of them lands on dry ground.
		int landing = Integer.MAX_VALUE;
		boolean dry = true;
		for (int j = lo; j <= hi; j++) {
			int ox = x + dx + j * px, oz = z + dz + j * pz;
			dry &= carved.water(ox, oz) == DRY;
			landing = Math.min(landing, level(carved, ox, oz));
		}
		drop = y - landing;
		LongOpenHashSet basin = dry ? basin(carved, x + dx, z + dz, lo, hi, px, pz, landing, drop) : null;
		pool = basin != null;
		if (pool) {
			int depth = drop >= 30 ? 3 : 2;
			for (long column : basin) {
				int cx = BlockPos.getX(column), cz = BlockPos.getZ(column);
				for (int cy = landing - depth + 1; cy <= landing; cy++) {
					sources.add(BlockPos.asLong(cx, cy, cz));
				}
				for (int cy = landing + 1; cy <= carved.top(cx, cz); cy++) {
					cut.add(BlockPos.asLong(cx, cy, cz));
				}
			}
		}

		// The falls, from a block out from each lip down to the pool, the water or the ground.
		for (int j = lo; j <= hi; j++) {
			int ox = x + dx + j * px, oz = z + dz + j * pz;
			int bottom = (pool ? landing : level(carved, ox, oz)) + 1;
			spilling.add(BlockPos.asLong(ox, y, oz));
			for (int cy = y - 1; cy >= bottom; cy--) {
				falling.add(BlockPos.asLong(ox, cy, oz));
			}
			ticks.add(BlockPos.asLong(x + j * px, y, z + j * pz));
			ticks.add(BlockPos.asLong(ox, bottom, oz));
		}
		return drop >= minDrop && !opensOntoWater(ground);
	}

	/**
	 * Whether a column beside the middle lip makes a lip too: level with it and dry, over a drop of its own or a line
	 * of the gorge.
	 */
	private boolean lip(Ground ground, int x, int z, int dx, int dz, int minDrop, int foot) {
		int top = ground.top(x, z);
		return top >= y && top <= y + CUT && ground.water(x, z) == DRY && (gorge > 0
			? gorgeLine(ground, x, z, dx, dz, foot)
			: level(ground, x + dx, z + dz) <= y - Math.max(2, minDrop / 2));
	}

	/**
	 * Whether the columns out from {@code (x, z)} make a line of the gorge: dry, none of them much higher than the
	 * stream, and none lower than its floor next to the falls, where the pool goes.
	 */
	private boolean gorgeLine(Ground ground, int x, int z, int dx, int dz, int foot) {
		for (int out = 1; out <= gorge; out++) {
			int cx = x + out * dx, cz = z + out * dz;
			int top = ground.top(cx, cz);
			if (ground.water(cx, cz) != DRY || top > y + CUT || out == 1 && top < foot) {
				return false;
			}
		}
		return true;
	}

	/** Whether any ground cut away has water beside it above the cut's floor, which would pour in. */
	private boolean opensOntoWater(Ground ground) {
		LongOpenHashSet open = new LongOpenHashSet(cut);
		for (long pos : cut) {
			int x = BlockPos.getX(pos), cy = BlockPos.getY(pos), z = BlockPos.getZ(pos);
			for (int d = 0; d < 4; d++) {
				int water = ground.water(x + DX[d], z + DZ[d]);
				if (water != DRY && water >= cy && !open.contains(BlockPos.asLong(x + DX[d], cy, z + DZ[d]))) {
					return true;
				}
			}
		}
		return false;
	}

	/** The ground with the gorge cut into it: no column of it higher than its floor. */
	private record Carved(Ground ground, LongOpenHashSet gorge, int floor) implements Ground {
		@Override
		public int top(int x, int z) {
			int top = ground.top(x, z);
			return gorge.contains(BlockPos.asLong(x, 0, z)) ? Math.min(top, floor) : top;
		}

		@Override
		public int water(int x, int z) {
			return ground.water(x, z);
		}
	}

	/** Whether a row of the stream lies level with the lips, dry, between banks that hold it. */
	private boolean streamRow(Ground ground, int x, int z, int row, int lo, int hi, int dx, int dz, int px, int pz) {
		int rx = x - row * dx, rz = z - row * dz;
		for (int j = lo; j <= hi; j++) {
			int top = ground.top(rx + j * px, rz + j * pz);
			if (top < y || top > y + CUT || ground.water(rx + j * px, rz + j * pz) != DRY) {
				return false;
			}
		}
		return ground.top(rx + (lo - 1) * px, rz + (lo - 1) * pz) >= y
			&& ground.top(rx + (hi + 1) * px, rz + (hi + 1) * pz) >= y;
	}

	/** Whether the ground behind the stream's last row holds it. */
	private boolean heldBehind(Ground ground, int x, int z, int rows, int lo, int hi, int dx, int dz, int px, int pz) {
		int rx = x - (rows + 1) * dx, rz = z - (rows + 1) * dz;
		for (int j = lo; j <= hi; j++) {
			if (ground.top(rx + j * px, rz + j * pz) < y) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The columns of a plunge pool at the foot of the falls, whose water stands at {@code foot}, or {@code null} if
	 * the ground there cannot hold one under every fall. It starts as a disc around the falls, wider under a higher
	 * one, of ground no lower than the water and not much higher, and loses every column the ground beside would let
	 * the water out of.
	 */
	private static @Nullable LongOpenHashSet basin(Ground ground, int x, int z, int lo, int hi, int px, int pz,
												   int foot, int drop) {
		int radius = 2 + Math.min(2, drop / 20);
		LongOpenHashSet basin = new LongOpenHashSet();
		for (int j = lo; j <= hi; j++) {
			int fx = x + j * px, fz = z + j * pz;
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					int cx = fx + dx, cz = fz + dz;
					int top = ground.top(cx, cz);
					if (dx * dx + dz * dz <= radius * radius + radius && top >= foot && top <= foot + CUT
						&& ground.water(cx, cz) == DRY) {
						basin.add(BlockPos.asLong(cx, 0, cz));
					}
				}
			}
		}
		LongArrayList leaking = new LongArrayList();
		do {
			leaking.clear();
			for (long column : basin) {
				if (leaks(ground, basin, column, foot)) {
					leaking.add(column);
				}
			}
			basin.removeAll(leaking);
		} while (!leaking.isEmpty());
		for (int j = lo; j <= hi; j++) {
			if (!basin.contains(BlockPos.asLong(x + j * px, 0, z + j * pz))) {
				return null;
			}
		}
		return basin;
	}

	/** Whether water standing at {@code foot} in a column of the basin would run out through a column beside it. */
	private static boolean leaks(Ground ground, LongOpenHashSet basin, long column, int foot) {
		int x = BlockPos.getX(column), z = BlockPos.getZ(column);
		for (int d = 0; d < 4; d++) {
			if (!basin.contains(BlockPos.asLong(x + DX[d], 0, z + DZ[d]))
				&& level(ground, x + DX[d], z + DZ[d]) < foot) {
				return true;
			}
		}
		return false;
	}
}
