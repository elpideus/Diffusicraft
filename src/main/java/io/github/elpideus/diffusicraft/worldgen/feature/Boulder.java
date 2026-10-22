package io.github.elpideus.diffusicraft.worldgen.feature;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * A boulder, and where the rocks that fall from a cliff come to rest. Its shape is a rounded box cut by a few planes into
 * facets, as a block of rock splits along its joints and the weather rounds its edges, turned to a heading of its own.
 * Its blocks are offsets from its centre, so that the shape knows nothing of the world it is set down in.
 */
final class Boulder {
	/** How far a boulder reaches across from its centre at most, so that it stays within the chunks it may change. */
	static final int MAX_REACH = 13;
	/** How far from its foot a cliff is looked at. */
	static final int REACH = 12;
	/** The columns of a chunk tried for the foot of a cliff. */
	private static final int TRIES = 24;
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
	/** Half the boulder's height, in blocks. */
	final float halfHeight;

	private Boulder(int reach, float halfHeight) {
		this.reach = reach;
		this.halfHeight = halfHeight;
	}

	/**
	 * A boulder about {@code radius} blocks across from its centre to its side, and {@code flatness} times as high.
	 * Its centre is always one of its blocks.
	 */
	static Boulder shape(float radius, float flatness, RandomSource random) {
		// A superellipsoid, between an ellipsoid and a box: its sides stand steep and its top is broad, where an
		// ellipsoid of blocks would rise in steps to a point. Across, it reaches farther at 45° to its axes than along
		// them, by as much as a squircle of the same power.
		float power = 2.4f + 0.8f * random.nextFloat();
		float corner = (float) Math.pow(2, 0.5 - 1 / power);
		float a = Math.min(radius * (0.8f + 0.4f * random.nextFloat()), MAX_REACH / corner);
		float b = Math.min(radius * (0.8f + 0.4f * random.nextFloat()), MAX_REACH / corner);
		float c = Math.max(radius * flatness, 0.75f);
		double yaw = random.nextDouble() * Math.PI;
		float cos = (float) Math.cos(yaw), sin = (float) Math.sin(yaw);
		// The joints the rock split along: planes as far from the centre as 60% to 95% of the way to its surface.
		int facets = 6 + random.nextInt(4);
		float[] planes = new float[4 * facets];
		for (int i = 0; i < facets; i++) {
			float ny = 2f * random.nextFloat() - 1f, around = (float) (2 * Math.PI * random.nextFloat());
			float across = (float) Math.sqrt(1f - ny * ny);
			planes[4 * i] = across * (float) Math.cos(around);
			planes[4 * i + 1] = ny;
			planes[4 * i + 2] = across * (float) Math.sin(around);
			planes[4 * i + 3] = 0.6f + 0.35f * random.nextFloat();
		}
		int reach = Math.min((int) Math.ceil(Math.max(a, b) * corner), MAX_REACH), depth = (int) Math.ceil(c);
		LongOpenHashSet blocks = new LongOpenHashSet();
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				// In the boulder's own frame, where its surface is the unit superellipsoid.
				float x = (dx * cos + dz * sin) / a, z = (dz * cos - dx * sin) / b;
				float across = (float) (Math.pow(Math.abs(x), power) + Math.pow(Math.abs(z), power));
				block:
				for (int dy = -depth; dy <= depth; dy++) {
					float y = dy / c;
					if (across + Math.pow(Math.abs(y), power) > 1f) {
						continue;
					}
					for (int i = 0; i < facets; i++) {
						if (planes[4 * i] * x + planes[4 * i + 1] * y + planes[4 * i + 2] * z > planes[4 * i + 3]) {
							continue block;
						}
					}
					blocks.add(BlockPos.asLong(dx, dy, dz));
				}
			}
		}
		// Where the shape just touches a layer, a lone block would stand on the top like a chimney: it goes.
		Boulder boulder = new Boulder(reach, c);
		long centre = BlockPos.asLong(0, 0, 0);
		for (long block : blocks) {
			int beside = 0;
			for (Direction side : Direction.Plane.HORIZONTAL) {
				if (blocks.contains(BlockPos.offset(block, side))) {
					beside++;
				}
			}
			if (block == centre || beside >= 2 || blocks.contains(BlockPos.offset(block, Direction.UP))) {
				boulder.blocks.add(block);
			}
		}
		return boulder;
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
