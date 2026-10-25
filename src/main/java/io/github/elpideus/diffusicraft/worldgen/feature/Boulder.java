package io.github.elpideus.diffusicraft.worldgen.feature;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * A boulder, and where the rocks that fall from a cliff come to rest. Its shape is a block of rock bounded by its joints:
 * a box with rounded edges, longer one way than the other, tilted as it came to rest, its corners and edges broken off
 * along a few more planes, its faces worn uneven, and the big ones now and then split by frost from the top down. Its
 * blocks are offsets from its centre, so that the shape knows nothing of the world it is set down in.
 */
final class Boulder {
	/** How far a boulder reaches across from its centre at most, so that it stays within the chunks it may change. */
	static final int MAX_REACH = 13;
	/** How far from its foot a cliff is looked at. */
	static final int REACH = 12;
	/** The columns of a chunk tried for the foot of a cliff. */
	private static final int TRIES = 24;
	/** How boxy a boulder is: the power of its superellipsoid, from rounded to nearly a box. */
	private static final float POWER_MIN = 3.5f;
	private static final float POWER_MAX = 6f;
	/** Its long and its short half-width, as shares of its radius. */
	private static final float LONG_MIN = 0.95f;
	private static final float LONG_MAX = 1.4f;
	private static final float SHORT_MIN = 0.6f;
	private static final float SHORT_MAX = 1f;
	/**
	 * How many joints its corners and edges broke off along, how far out they lie, as shares of its farthest point
	 * that way, and how far down they may face, as the vertical part of their normal.
	 */
	private static final int FACETS_MIN = 4;
	private static final int FACETS_MAX = 8;
	private static final float FACET_MIN = 0.7f;
	private static final float FACET_MAX = 0.92f;
	private static final float FACET_DOWN = -0.3f;
	/** How far it is tipped about each of its axes at most, in radians (25°). */
	private static final double TILT = Math.toRadians(25);
	/** The waves that wear its faces uneven: how many, how far they move its surface, and their wavenumber. */
	private static final int LUMPS = 3;
	private static final float LUMP = 0.12f;
	private static final float LUMP_WAVENUMBER = 3.5f;
	/** How many of the boulders at least {@value #CLEFT_HEIGHT} blocks from their centre to their top are split. */
	private static final float CLEFT = 0.25f;
	private static final float CLEFT_HEIGHT = 2.5f;
	private static final int[] DX = {1, 0, -1, 0};
	private static final int[] DZ = {0, 1, 0, -1};

	/** The ground a boulder is set down on. */
	interface Ground {
		/** The height of the highest solid block of a column. */
		int top(int x, int z);
	}

	/**
	 * The foot of a cliff: a column of gentle ground at {@code (x, z)} with the cliff rising {@code rise} blocks over
	 * it towards {@code (dx, dz)}, as high as {@code run} blocks out from it.
	 */
	record Foot(int x, int z, int dx, int dz, int rise, int run) {
	}

	/** The blocks of the boulder, as offsets from its centre packed by {@link BlockPos#asLong}. */
	final LongArrayList blocks = new LongArrayList();
	/** How far the boulder reaches across from its centre, in blocks. */
	final int reach;
	/** Its lowest and its highest block, from its centre. */
	final int bottom;
	final int top;
	/** Half the boulder's height, in blocks. */
	final float halfHeight;

	private Boulder(int reach, int bottom, int top) {
		this.reach = reach;
		this.bottom = bottom;
		this.top = top;
		this.halfHeight = (top - bottom + 1) / 2f;
	}

	/**
	 * A boulder about {@code radius} blocks across from its centre to its side, and {@code flatness} times as high.
	 * Its centre is always one of its blocks.
	 */
	static Boulder shape(float radius, float flatness, RandomSource random) {
		// A block of rock bounded by its joints: a box with rounded edges (a superellipsoid of high power), longer one
		// way than the other, so that its sides stand steep and its top is broad, where an ellipsoid of blocks would
		// rise in rings to a dome.
		float power = POWER_MIN + (POWER_MAX - POWER_MIN) * random.nextFloat();
		float a = Math.min(radius * (LONG_MIN + (LONG_MAX - LONG_MIN) * random.nextFloat()), MAX_REACH - 1);
		float b = Math.min(radius * (SHORT_MIN + (SHORT_MAX - SHORT_MIN) * random.nextFloat()), MAX_REACH - 1);
		float c = Math.max(radius * flatness, 0.75f);
		// It came to rest tilted: turned to a heading, then tipped about its long and its short axis.
		double yaw = random.nextDouble() * Math.PI;
		double roll = TILT * (2 * random.nextDouble() - 1), pitch = TILT * (2 * random.nextDouble() - 1);
		float[] axes = axes(yaw, pitch, roll);
		// The joints its corners and edges broke off along: planes 70% to 92% of the way out to its farthest point that
		// way, so that they bevel it rather than slice it thin, and seldom facing down, so that it does not stand on a
		// point.
		int facets = FACETS_MIN + random.nextInt(FACETS_MAX - FACETS_MIN + 1);
		float[] planes = new float[4 * facets];
		double dual = power / (power - 1);
		for (int i = 0; i < facets; i++) {
			float ny = FACET_DOWN + (1f - FACET_DOWN) * random.nextFloat();
			float around = (float) (2 * Math.PI * random.nextFloat()), across = (float) Math.sqrt(1f - ny * ny);
			float nx = across * (float) Math.cos(around), nz = across * (float) Math.sin(around);
			double farthest = Math.pow(Math.pow(Math.abs(nx), dual) + Math.pow(Math.abs(ny), dual)
				+ Math.pow(Math.abs(nz), dual), 1 / dual);
			planes[4 * i] = nx;
			planes[4 * i + 1] = ny;
			planes[4 * i + 2] = nz;
			planes[4 * i + 3] = (float) farthest * (FACET_MIN + (FACET_MAX - FACET_MIN) * random.nextFloat());
		}
		// The weather wears its faces uneven: three waves, a lump or two across a face, move its surface in and out.
		float[] waves = new float[4 * LUMPS];
		for (int i = 0; i < LUMPS; i++) {
			float ny = 2f * random.nextFloat() - 1f, around = (float) (2 * Math.PI * random.nextFloat());
			float across = (float) Math.sqrt(1f - ny * ny), k = LUMP_WAVENUMBER * (0.8f + 0.4f * random.nextFloat());
			waves[4 * i] = k * across * (float) Math.cos(around);
			waves[4 * i + 1] = k * ny;
			waves[4 * i + 2] = k * across * (float) Math.sin(around);
			waves[4 * i + 3] = (float) (2 * Math.PI * random.nextFloat());
		}
		// Frost has split some of the big ones from the top down, along a joint that stands upright.
		boolean cleft = c >= CLEFT_HEIGHT && random.nextFloat() < CLEFT;
		double cleftHeading = random.nextDouble() * Math.PI;
		float cleftX = (float) Math.cos(cleftHeading), cleftZ = (float) Math.sin(cleftHeading);
		float cleftAt = (2f * random.nextFloat() - 1f) * 0.3f * b, cleftWidth = radius < 6 ? 0.5f : 0.9f;
		int bound = Math.min((int) Math.ceil(Math.max(a, Math.max(b, c)) * 1.3f), MAX_REACH);
		LongOpenHashSet blocks = new LongOpenHashSet();
		for (int dx = -bound; dx <= bound; dx++) {
			for (int dy = -bound; dy <= bound; dy++) {
				block:
				for (int dz = -bound; dz <= bound; dz++) {
					// In the boulder's own frame, where its surface is the unit superellipsoid.
					float x = (axes[0] * dx + axes[1] * dy + axes[2] * dz) / a;
					float y = (axes[3] * dx + axes[4] * dy + axes[5] * dz) / c;
					float z = (axes[6] * dx + axes[7] * dy + axes[8] * dz) / b;
					double out = Math.pow(Math.pow(Math.abs(x), power) + Math.pow(Math.abs(y), power)
						+ Math.pow(Math.abs(z), power), 1.0 / power);
					double lump = 0;
					for (int i = 0; i < LUMPS; i++) {
						lump += Math.sin(waves[4 * i] * x + waves[4 * i + 1] * y + waves[4 * i + 2] * z
							+ waves[4 * i + 3]);
					}
					if (out > 1 + LUMP * lump / LUMPS) {
						continue;
					}
					for (int i = 0; i < facets; i++) {
						if (planes[4 * i] * x + planes[4 * i + 1] * y + planes[4 * i + 2] * z > planes[4 * i + 3]) {
							continue block;
						}
					}
					if (cleft && dy > 0 && Math.abs(cleftX * dx + cleftZ * dz - cleftAt) < cleftWidth) {
						continue;
					}
					blocks.add(BlockPos.asLong(dx, dy, dz));
				}
			}
		}
		// Where the shape just touches a layer, a lone block would stand on the top like a chimney: it goes.
		LongOpenHashSet kept = new LongOpenHashSet();
		long centre = BlockPos.asLong(0, 0, 0);
		for (long block : blocks) {
			int beside = 0;
			for (Direction side : Direction.Plane.HORIZONTAL) {
				if (blocks.contains(BlockPos.offset(block, side))) {
					beside++;
				}
			}
			if (block == centre || beside >= 2 || blocks.contains(BlockPos.offset(block, Direction.UP))) {
				kept.add(block);
			}
		}
		// A worn bump, a cleft or a lone block gone may leave a crumb apart from the rest: only what holds on to the
		// centre is the boulder.
		LongArrayList piece = new LongArrayList();
		LongOpenHashSet reached = new LongOpenHashSet(new long[]{centre});
		LongArrayFIFOQueue next = new LongArrayFIFOQueue();
		next.enqueue(centre);
		int reach = 0, bottom = 0, top = 0;
		while (!next.isEmpty()) {
			long block = next.dequeueLong();
			piece.add(block);
			reach = Math.max(reach, Math.max(Math.abs(BlockPos.getX(block)), Math.abs(BlockPos.getZ(block))));
			bottom = Math.min(bottom, BlockPos.getY(block));
			top = Math.max(top, BlockPos.getY(block));
			for (Direction side : Direction.values()) {
				long neighbour = BlockPos.offset(block, side);
				if (kept.contains(neighbour) && reached.add(neighbour)) {
					next.enqueue(neighbour);
				}
			}
		}
		Boulder boulder = new Boulder(reach, bottom, top);
		boulder.blocks.addAll(piece);
		return boulder;
	}

	/**
	 * The rows of the turn from the world into a boulder's own frame, whose x runs along its long axis, y up through
	 * it and z across it: turned by {@code yaw} about the vertical, then tipped by {@code pitch} about its z and by
	 * {@code roll} about its x.
	 */
	private static float[] axes(double yaw, double pitch, double roll) {
		double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
		double cr = Math.cos(roll), sr = Math.sin(roll);
		// The boulder's axes in the world: R = Ry(yaw) Rz(pitch) Rx(roll), its columns. The rows of its transpose
		// take a world offset into the boulder's frame.
		double[][] r = {
			{cy * cp, cy * -sp * cr + sy * sr, cy * sp * sr + sy * cr},
			{sp, cp * cr, -cp * sr},
			{-sy * cp, sy * sp * cr + cy * sr, -sy * sp * sr + cy * cr}
		};
		float[] axes = new float[9];
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				axes[3 * row + col] = (float) r[col][row];
			}
		}
		return axes;
	}

	/**
	 * The foot of a cliff at least {@code minRise} blocks high in the chunk at {@code (x0, z0)}, or null if none of the
	 * columns tried has one: gentle ground on one side, where the rocks that fall come to rest, and on the other a face
	 * that rises at 45° or more from right beside the column, as far as {@link #REACH} blocks out.
	 */
	static @Nullable Foot foot(Ground ground, int x0, int z0, int minRise, RandomSource random) {
		for (int attempt = 0; attempt < TRIES; attempt++) {
			int x = x0 + random.nextInt(16), z = z0 + random.nextInt(16);
			int floor = ground.top(x, z), first = random.nextInt(4);
			for (int turn = 0; turn < 4; turn++) {
				int dx = DX[(first + turn) & 3], dz = DZ[(first + turn) & 3];
				if (Math.abs(ground.top(x - dx, z - dz) - floor) > 1 || ground.top(x + dx, z + dz) - floor < 2) {
					continue;
				}
				int rise = 0, run = 0;
				for (int d = 1; d <= REACH; d++) {
					int up = ground.top(x + d * dx, z + d * dz) - floor;
					// Gentler than 45° from the foot: the face has ended.
					if (up < d) {
						break;
					}
					if (up > rise) {
						rise = up;
						run = d;
					}
				}
				if (rise >= minRise) {
					return new Foot(x, z, dx, dz, rise, run);
				}
			}
		}
		return null;
	}
}
