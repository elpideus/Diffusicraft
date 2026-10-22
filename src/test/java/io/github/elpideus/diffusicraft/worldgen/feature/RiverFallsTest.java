package io.github.elpideus.diffusicraft.worldgen.feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiverFallsTest {
	/** The water of a column, or {@link RiverFalls#NONE}, and whether it is a lake's. */
	private interface Water {
		int top(int x, int z);

		default boolean lake(int x, int z) {
			return false;
		}
	}

	private static RiverFalls plan(Water water, int x0, int z0, int deepest) {
		RiverFalls falls = new RiverFalls(x0, z0);
		for (int z = z0 - RiverFalls.BORDER; z < z0 + 16 + RiverFalls.BORDER; z++) {
			for (int x = x0 - RiverFalls.BORDER; x < x0 + 16 + RiverFalls.BORDER; x++) {
				int top = water.top(x, z);
				if (top != RiverFalls.NONE) {
					falls.set(x, z, top, water.lake(x, z));
				}
			}
		}
		falls.plan(deepest);
		return falls;
	}

	/** A river four blocks wide running east along z = 6 to 9, its water at {@code upper} west of x = 8. */
	private static Water river(int upper, int lower) {
		return (x, z) -> z < 6 || z > 9 ? RiverFalls.NONE : x < 8 ? upper : lower;
	}

	@Test
	void aRiverFallsOverItsStepIntoAPool() {
		RiverFalls falls = plan(river(76, 64), 0, 0, 4);
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int curtain = falls.curtain[x + 16 * z], pool = falls.pool[x + 16 * z];
				if (z < 6 || z > 9) {
					assertEquals(RiverFalls.NONE, curtain);
					assertEquals(0, pool);
				} else {
					// Only the column below the step, from the upper water down.
					assertEquals(x == 8 ? 76 : RiverFalls.NONE, curtain, "the curtain at " + x + ", " + z);
					// The upper river keeps its bed.
					assertTrue(x >= 8 || pool == 0, "a pool above the falls at " + x + ", " + z);
				}
			}
		}
		// Twelve blocks high: four blocks deep at the foot, and as far as three blocks downstream, less and less.
		assertEquals(4, falls.pool[8 + 16 * 7]);
		assertTrue(falls.pool[9 + 16 * 7] < 4 && falls.pool[9 + 16 * 7] > falls.pool[11 + 16 * 7]);
		assertTrue(falls.pool[11 + 16 * 7] > 0);
		assertEquals(0, falls.pool[12 + 16 * 7]);
	}

	@Test
	void aStepOfABlockTakesNoPool() {
		RiverFalls falls = plan(river(65, 64), 0, 0, 4);
		for (int z = 6; z <= 9; z++) {
			assertEquals(65, falls.curtain[8 + 16 * z]);
		}
		for (int pool : falls.pool) {
			assertEquals(0, pool);
		}
	}

	@Test
	void theDeepestPoolBoundsThePools() {
		RiverFalls falls = plan(river(100, 64), 0, 0, 2);
		for (int pool : falls.pool) {
			assertTrue(pool <= 2, pool + " deep");
		}
		assertEquals(2, falls.pool[8 + 16 * 7]);
		for (int pool : plan(river(100, 64), 0, 0, 0).pool) {
			assertEquals(0, pool);
		}
	}

	@Test
	void aRiverFallsIntoALakeWithoutAPool() {
		Water water = (x, z) -> x >= 8 ? 64 : z < 6 || z > 9 ? RiverFalls.NONE : 76;
		Water lake = new Water() {
			@Override
			public int top(int x, int z) {
				return water.top(x, z);
			}

			@Override
			public boolean lake(int x, int z) {
				return x >= 8;
			}
		};
		RiverFalls falls = plan(lake, 0, 0, 4);
		for (int z = 0; z < 16; z++) {
			assertEquals(z >= 6 && z <= 9 ? 76 : RiverFalls.NONE, falls.curtain[8 + 16 * z]);
		}
		for (int pool : falls.pool) {
			assertEquals(0, pool);
		}
	}

	@Test
	void aPoolAcrossTheSideOfAChunkIsDugAlikeOnBothSides() {
		// The step at x = 14, so that the pool reaches into the chunk to the east.
		Water water = (x, z) -> z < 6 || z > 9 ? RiverFalls.NONE : x < 14 ? 76 : 64;
		RiverFalls west = plan(water, 0, 0, 4), east = plan(water, 16, 0, 4);
		RiverFalls whole = plan((x, z) -> water.top(x + 8, z), 0, 0, 4);
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int wx = x + 8;
				int pool = wx < 16 ? west.pool[wx + 16 * z] : east.pool[wx - 16 + 16 * z];
				assertEquals(whole.pool[x + 16 * z], pool, "the pool at " + wx + ", " + z);
			}
		}
		assertTrue(east.pool[16 * 7] > 0, "no pool across the side");
	}
}
