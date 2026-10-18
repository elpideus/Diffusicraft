package io.github.elpideus.diffusicraft.worldgen.geology;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GulliesTest {
	/** Gullies at Earth 1:1, a meter per block. */
	private static final Gullies EARTH = new Gullies(0x1234_5678_9ABCL, 1.0);

	@Test
	void onlyEarthIsFineEnoughForThem() {
		assertTrue(EARTH.any());
		for (double metersPerBlock : new double[]{5, 15}) {
			Gullies coarse = new Gullies(1, metersPerBlock);
			assertFalse(coarse.any(), metersPerBlock + " m per block");
			assertEquals(0, coarse.depth(10.5, 20.5, 1, 0.5));
		}
	}

	@Test
	void gentleGroundStaysSmooth() {
		for (int x = -500; x < 500; x += 7) {
			for (int z = -500; z < 500; z += 11) {
				assertEquals(0, EARTH.depth(x + 0.5, z + 0.5, 0.1, -0.1));
				assertEquals(0, EARTH.depth(x + 0.5, z + 0.5, 0, 0));
			}
		}
	}

	@Test
	void slopesAreCutDeeperTheSteeperTheyAre() {
		double previous = 0;
		for (double slope : new double[]{0.3, 0.6, 1.0, 1.5}) {
			double sum = 0, deepest = 0;
			int count = 0;
			for (int x = -1000; x < 1000; x += 3) {
				for (int z = -1000; z < 1000; z += 13) {
					double depth = EARTH.depth(x + 0.5, z + 0.5, slope * 0.6, slope * 0.8);
					sum += Math.abs(depth);
					deepest = Math.max(deepest, Math.abs(depth));
					count++;
				}
			}
			double mean = sum / count;
			System.out.printf("Gullies on a slope of %.1f: mean %.2f, deepest %.2f blocks%n", slope, mean, deepest);
			assertTrue(deepest <= EARTH.maxDepth(), "within the deepest a gully can be at slope " + slope);
			assertTrue(mean > previous, "deeper than on gentler ground at slope " + slope);
			previous = mean;
		}
		assertTrue(previous > 1, "a steep slope is cut by more than a block on average");
	}

	@Test
	void gulliesRunDownhill() {
		// Downhill along (dx, dz): the ground changes far more across the slope than down it.
		double[][] downhill = {{1, 0}, {0, 1}, {0.6, -0.8}};
		for (double[] d : downhill) {
			double along = 0, across = 0;
			for (int x = -800; x < 800; x += 5) {
				for (int z = -800; z < 800; z += 7) {
					double px = x + 0.5, pz = z + 0.5, gx = -0.9 * d[0], gz = -0.9 * d[1];
					double here = EARTH.depth(px, pz, gx, gz);
					double down = EARTH.depth(px + 3 * d[0], pz + 3 * d[1], gx, gz) - here;
					double side = EARTH.depth(px - 3 * d[1], pz + 3 * d[0], gx, gz) - here;
					along += down * down;
					across += side * side;
				}
			}
			System.out.printf("Downhill (%.1f, %.1f): change across the slope %.1f times that down it%n", d[0], d[1],
				Math.sqrt(across / along));
			assertTrue(Math.sqrt(across / along) > 2, "gullies run downhill along " + d[0] + ", " + d[1]);
		}
	}

	@Test
	void theGroundIsContinuous() {
		for (int x = -300; x < 300; x += 5) {
			for (int z = -300; z < 300; z += 7) {
				double here = EARTH.depth(x, z, 1.1, 0.4);
				assertEquals(here, EARTH.depth(x + 0.01, z, 1.1, 0.4), 0.05, "at " + x + ", " + z);
				assertEquals(here, EARTH.depth(x, z + 0.01, 1.1, 0.4), 0.05, "at " + x + ", " + z);
			}
		}
	}

	@Test
	void theSeedDecidesTheGullies() {
		Gullies same = new Gullies(0x1234_5678_9ABCL, 1.0), other = new Gullies(42, 1.0);
		int differ = 0;
		for (int x = 0; x < 200; x += 9) {
			for (int z = 0; z < 200; z += 9) {
				double depth = EARTH.depth(x, z, 0.8, 0.8);
				assertEquals(depth, same.depth(x, z, 0.8, 0.8));
				differ += depth != other.depth(x, z, 0.8, 0.8) ? 1 : 0;
			}
		}
		assertNotEquals(0, differ);
	}

	@Test
	void isCheap() {
		double sink = 0;
		for (int round = 0; round < 2; round++) {
			long start = System.nanoTime();
			int count = 0;
			for (int x = 0; x < 512; x++) {
				for (int z = 0; z < 512; z++) {
					sink += EARTH.depth(x + 0.5, z + 0.5, 0.7 + x * 1e-3, 0.3 - z * 1e-3);
					count++;
				}
			}
			System.out.printf("Gullies: %.0f ns per column%n", (System.nanoTime() - start) / (double) count);
		}
		assertTrue(Double.isFinite(sink));
	}
}
