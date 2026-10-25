package io.github.elpideus.diffusicraft.worldgen.feature;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoulderTest {
	private static final int FLOOR = 64;

	@Test
	void aBoulderIsOneSolidPieceAroundItsCentre() {
		RandomSource random = RandomSource.create(3);
		for (int i = 0; i < 64; i++) {
			Boulder boulder = Boulder.shape(1.2f + 0.2f * i, 0.55f + 0.3f * random.nextFloat(), random);
			LongOpenHashSet blocks = new LongOpenHashSet(boulder.blocks);
			long centre = BlockPos.asLong(0, 0, 0);
			assertTrue(blocks.contains(centre), "no centre");
			// Every block is reached from the centre through the boulder's own blocks.
			LongOpenHashSet reached = new LongOpenHashSet(new long[]{centre});
			LongArrayFIFOQueue next = new LongArrayFIFOQueue();
			next.enqueue(centre);
			while (!next.isEmpty()) {
				long block = next.dequeueLong();
				for (Direction side : Direction.values()) {
					long neighbour = BlockPos.offset(block, side);
					if (blocks.contains(neighbour) && reached.add(neighbour)) {
						next.enqueue(neighbour);
					}
				}
			}
			assertEquals(blocks.size(), reached.size(), "a boulder of " + blocks.size() + " blocks in pieces");
		}
	}

	@Test
	void aBoulderStaysWithinItsReachAndHeight() {
		RandomSource random = RandomSource.create(5);
		for (int i = 0; i < 64; i++) {
			Boulder boulder = Boulder.shape(1.5f + 15f * random.nextFloat(), 0.7f, random);
			assertTrue(boulder.reach <= Boulder.MAX_REACH, "reaches " + boulder.reach);
			for (long block : boulder.blocks) {
				assertTrue(Math.abs(BlockPos.getX(block)) <= boulder.reach
					&& Math.abs(BlockPos.getZ(block)) <= boulder.reach);
				assertTrue(BlockPos.getY(block) >= boulder.bottom && BlockPos.getY(block) <= boulder.top);
			}
		}
	}

	@Test
	void theFootOfACliffIsFound() {
		// Level ground, and a face rising 2 blocks a block east of x = 20, up to 30 blocks over it.
		Boulder.Ground cliff = (x, z) -> FLOOR + Math.clamp(2 * (x - 20), 0, 30);
		Boulder.Foot foot = foot(cliff, 8);
		assertNotNull(foot);
		assertEquals(20, foot.x());
		assertEquals(1, foot.dx());
		assertEquals(0, foot.dz());
		assertEquals(24, foot.rise());
		assertEquals(Boulder.REACH, foot.run());
	}

	@Test
	void aGentleSlopeHasNoFoot() {
		// A slope of 30°: steeper than the floor, gentler than a face.
		Boulder.Ground slope = (x, z) -> FLOOR + Math.max(x - 20, 0) * 3 / 5;
		assertNull(foot(slope, 8));
	}

	@Test
	void aLowFaceHasNoFoot() {
		// A face 6 blocks high, its foot at x = 24.
		Boulder.Ground step = (x, z) -> FLOOR + Math.clamp(3 * (x - 24), 0, 6);
		assertNull(foot(step, 8));
		Boulder.Foot foot = foot(step, 6);
		assertNotNull(foot);
		assertEquals(24, foot.x());
		assertEquals(6, foot.rise());
		assertEquals(2, foot.run());
	}

	/** The first foot found in the chunk at (16, 0) of {@code ground}, of the chunks it is looked for in many times. */
	private static Boulder.@Nullable Foot foot(Boulder.Ground ground, int minRise) {
		// Only one column in sixteen is a foot, and each try looks at a few: one random for all, as for the chunks.
		RandomSource random = RandomSource.create(1);
		for (int chunk = 0; chunk < 16; chunk++) {
			Boulder.Foot foot = Boulder.foot(ground, 16, 0, minRise, random);
			if (foot != null) {
				return foot;
			}
		}
		return null;
	}
}
