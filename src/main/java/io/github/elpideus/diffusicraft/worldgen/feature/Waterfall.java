package io.github.elpideus.diffusicraft.worldgen.feature;

import io.github.elpideus.diffusicraft.worldgen.WorldScale;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * Where a waterfall goes over a cliff in a chunk into the water at its foot, block by block, worked out from the
 * heights of the ground and the water alone. {@link WaterfallFeature} places it.
 *
 * <p>A stream runs a few blocks over level ground to the edge of a sheer cliff, sunk into the ground so that its banks
 * hold it, and spills over: up to {@value #WIDEST} blocks wide in the wettest land of a 1:1 world, and narrower in a
 * smaller world or a drier land ({@link #widest}). It falls straight down into water, the sea, a lake or a river, which
 * carries it on. A cliff over dry ground takes none, nor does a face that slopes: the falls would end on the ground,
 * or have to cut a gorge into the face. The rivers fall where they run steeply on their own. The cliff must have land
 * behind its edge that does not fall away, as the step of a valley or a bench has, so no stream springs from a crest.
 * The ground around the stream must hold its water on every side, as the game's water spreads into any open block
 * beside it, and no ground is cut away from water beside it.
 *
 * <p>Everything lies within a few blocks of the chunk, in the 3×3 chunks a feature may write to. Pure, so it is
 * unit-tested without a world.
 */
final class Waterfall {
	/** No water over a column's ground. */
	static final int DRY = Integer.MIN_VALUE;
	/** Columns tried for the edge of a cliff in each chunk. */
	static final int ATTEMPTS = 24;
	/**
	 * The highest drops laid out, of which the waterfall with the largest curtain of water is kept: on a ragged edge
	 * the highest drop often takes a fall only a block or two wide, while a level edge beside it takes a wide one.
	 */
	private static final int CANDIDATES = 8;
	/**
	 * Most blocks of ground cut away over the stream, to keep it level: a block, so that the stream runs where the
	 * ground is level already rather than in a notch cut into it.
	 */
	static final int CUT = 1;
	/** Widest waterfall, in blocks, in the wettest land of a 1:1 world. */
	static final int WIDEST = 12;
	/**
	 * How fast the widest waterfall narrows with the world: each halving of its size takes this power of two off its
	 * width, so that at 1:32 it is 3 blocks wide.
	 */
	private static final double NARROW = 0.4;
	/** How far outside the chunk a lip may lie, in blocks, so that its stream lies within the 3×3 chunks. */
	private static final int REACH = 11;
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

	/** Water sources: the stream. */
	final LongArrayList sources = new LongArrayList();
	/** Ground cut away over the stream. */
	final LongArrayList cut = new LongArrayList();
	/** The water spilling over the edge, a block out from it. */
	final LongArrayList spilling = new LongArrayList();
	/** The falling water, from under the spill down to the water below. */
	final LongArrayList falling = new LongArrayList();
	/** Where the water runs on once the chunk ticks: each lip of the stream, and the foot of each fall. */
	final LongArrayList ticks = new LongArrayList();
	/** The level of the stream, on the edge's ground. */
	int y;
	/** How far the water falls to the water below, in blocks. */
	int drop;
	/** How many blocks wide it falls. */
	int width;
	/** The middle of the edge. */
	BlockPos lip;
	/** The lowest block of the chunk. */
	private final int x0, z0;

	private Waterfall(int x0, int z0) {
		this.x0 = x0;
		this.z0 = z0;
	}

	/**
	 * The widest a waterfall falls in a world of {@code scale}, in land whose {@code wetness} runs from 0, where the
	 * rain is just enough for one, to 1 in the wettest: half as wide in the first as in the last.
	 */
	static int widest(WorldScale scale, float wetness) {
		double width = WIDEST * Math.pow(scale.ratio(), -NARROW) * (0.5 + 0.5 * Math.clamp(wetness, 0f, 1f));
		return Math.clamp(Math.round(width), 1, WIDEST);
	}

	/**
	 * A waterfall over a cliff of the chunk whose lowest block is at {@code (x0, z0)}, at least {@code minDrop} blocks
	 * tall and at most {@code maxWidth} wide, or {@code null} if no cliff of it takes one. Of the falls the highest
	 * drops take, it is the one whose curtain of water is the largest, as wide times as tall.
	 */
	static @Nullable Waterfall plan(Ground ground, int x0, int z0, int minDrop, int maxWidth, RandomSource random) {
		int[] xs = new int[ATTEMPTS], zs = new int[ATTEMPTS], dirs = new int[ATTEMPTS], drops = new int[ATTEMPTS];
		int found = 0;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			int x = x0 + random.nextInt(16), z = z0 + random.nextInt(16);
			if (ground.water(x, z) != DRY) {
				continue;
			}
			int top = ground.top(x, z);
			int best = minDrop - 1, dir = -1;
			for (int d = 0; d < 4; d++) {
				int water = ground.water(x + DX[d], z + DZ[d]);
				if (water != DRY && top - water > best) {
					best = top - water;
					dir = d;
				}
			}
			if (dir < 0) {
				continue;
			}
			// Sorted highest first.
			int at = found++;
			while (at > 0 && drops[at - 1] < best) {
				xs[at] = xs[at - 1];
				zs[at] = zs[at - 1];
				dirs[at] = dirs[at - 1];
				drops[at] = drops[at - 1];
				at--;
			}
			xs[at] = x;
			zs[at] = z;
			dirs[at] = dir;
			drops[at] = best;
		}
		Waterfall best = null;
		for (int k = 0; k < Math.min(found, CANDIDATES); k++) {
			Waterfall fall = new Waterfall(x0, z0);
			if (fall.build(ground, xs[k], zs[k], dirs[k], minDrop, Math.clamp(maxWidth, 1, WIDEST), random)
				&& (best == null || fall.width * fall.drop > best.width * best.drop)) {
				best = fall;
			}
		}
		return best;
	}

	/**
	 * Lays out a waterfall from the edge at {@code (x, z)}, falling towards {@code dir} into the water a block out, a
	 * third as wide as {@code maxWidth} to as wide; false if it cannot hold.
	 */
	private boolean build(Ground ground, int x, int z, int dir, int minDrop, int maxWidth, RandomSource random) {
		y = ground.top(x, z);
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
		int want = Math.max(1, (int) Math.round(maxWidth * (0.35 + 0.65 * random.nextDouble())));
		int lo = 0, hi = 0;
		boolean up = true, down = true;
		while (hi - lo + 1 < want && (up || down)) {
			if (up) {
				up = lip(ground, x + (hi + 1) * px, z + (hi + 1) * pz, dx, dz, minDrop);
				hi += up ? 1 : 0;
			}
			if (down && hi - lo + 1 < want) {
				down = lip(ground, x + (lo - 1) * px, z + (lo - 1) * pz, dx, dz, minDrop);
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

		// The falls, from a block out from each lip down to the water.
		int landing = Integer.MAX_VALUE;
		for (int j = lo; j <= hi; j++) {
			int ox = x + dx + j * px, oz = z + dz + j * pz;
			int water = ground.water(ox, oz);
			landing = Math.min(landing, water);
			spilling.add(BlockPos.asLong(ox, y, oz));
			for (int cy = y - 1; cy > water; cy--) {
				falling.add(BlockPos.asLong(ox, cy, oz));
			}
			ticks.add(BlockPos.asLong(x + j * px, y, z + j * pz));
			ticks.add(BlockPos.asLong(ox, water + 1, oz));
		}
		drop = y - landing;
		return drop >= minDrop && !opensOntoWater(ground);
	}

	/**
	 * Whether a column beside the middle lip makes a lip too: no farther than {@value #REACH} blocks out of the chunk,
	 * level with the middle lip and dry, over water well below it.
	 */
	private boolean lip(Ground ground, int x, int z, int dx, int dz, int minDrop) {
		if (x < x0 - REACH || x > x0 + 15 + REACH || z < z0 - REACH || z > z0 + 15 + REACH) {
			return false;
		}
		int top = ground.top(x, z), water = ground.water(x + dx, z + dz);
		return top >= y && top <= y + CUT && ground.water(x, z) == DRY && water != DRY
			&& water <= y - Math.max(2, minDrop / 2);
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
}
