package io.github.elpideus.diffusicraft.worldgen.geology;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GulliesTest {
	/** Gullies at 1:1, a meter per block. */
	private static final Gullies ULTRA = new Gullies(0x1234_5678_9ABCL, 1.0);
	/** Rock hardness and ground cover at the corners of their range, and in the middle. */
	private static final double[][] CHARACTERS = {{0, 0}, {0, 1}, {1, 0}, {1, 1}, {0.5, 0.5}};
	private static final double MIDDLING = 0.5;

	private static double depth(double x, double z, double gx, double gz) {
		return ULTRA.depth(x, z, gx, gz, MIDDLING, MIDDLING);
	}

	@Test
	void onlyTheLargestSizesAreFineEnoughForThem() {
		assertTrue(ULTRA.any());
		// Octaves of 48, 24 and 12 m, of which those 12 blocks wide or more survive: all three at 1:1, two at 1:2 and one
		// at 1:4.
		for (double metersPerBlock : new double[]{2, 4}) {
			assertTrue(new Gullies(1, metersPerBlock).any(), metersPerBlock + " m per block");
		}
		for (double metersPerBlock : new double[]{8, 16, 32}) {
			Gullies coarse = new Gullies(1, metersPerBlock);
			assertFalse(coarse.any(), metersPerBlock + " m per block");
			assertFalse(coarse.cuts(1, 0.5), metersPerBlock + " m per block");
			assertEquals(0, coarse.depth(10.5, 20.5, 1, 0.5, MIDDLING, MIDDLING));
			assertEquals(0, coarse.maxDepth());
		}
	}

	@Test
	void gentleGroundStaysSmooth() {
		assertFalse(ULTRA.cuts(0.1, -0.1));
		assertTrue(ULTRA.cuts(0.2, 0));
		for (double[] character : CHARACTERS) {
			for (int x = -500; x < 500; x += 7) {
				for (int z = -500; z < 500; z += 11) {
					assertEquals(0, ULTRA.depth(x + 0.5, z + 0.5, 0.1, -0.1, character[0], character[1]));
					assertEquals(0, ULTRA.depth(x + 0.5, z + 0.5, 0, 0, character[0], character[1]));
				}
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
					double depth = depth(x + 0.5, z + 0.5, slope * 0.6, slope * 0.8);
					sum += Math.abs(depth);
					deepest = Math.max(deepest, Math.abs(depth));
					count++;
				}
			}
			double mean = sum / count;
			System.out.printf("Gullies on a slope of %.1f: mean %.2f, deepest %.2f blocks%n", slope, mean, deepest);
			assertTrue(mean > previous, "deeper than on gentler ground at slope " + slope);
			previous = mean;
		}
		assertTrue(previous > 1, "a steep slope is cut by more than a block on average");
	}

	@Test
	void noGullyIsDeeperThanTheDeepestThereCanBe() {
		for (double[] character : CHARACTERS) {
			double deepest = 0;
			for (double slope : new double[]{1.5, 3}) {
				for (int x = -600; x < 600; x += 2) {
					for (int z = -600; z < 600; z += 9) {
						double depth = ULTRA.depth(x + 0.5, z + 0.5, slope * 0.8, slope * -0.6, character[0],
							character[1]);
						deepest = Math.max(deepest, Math.abs(depth));
					}
				}
			}
			System.out.printf("Hardness %.1f, cover %.1f: deepest %.2f of at most %.2f blocks%n", character[0],
				character[1], deepest, ULTRA.maxDepth());
			assertTrue(deepest <= ULTRA.maxDepth(), "within the deepest a gully can be: " + deepest);
		}
	}

	/**
	 * Profiles across a slope of 0.9 running down along Z: the root mean square of the depth and of its second
	 * difference between neighbouring blocks, which is large where the ground is sharp.
	 */
	private static double[] profile(double hardness, double cover) {
		double depth = 0, curvature = 0;
		int count = 0;
		for (int z = -1000; z < 1000; z += 37) {
			double before = ULTRA.depth(-1000.5, z + 0.5, 0, 0.9, hardness, cover);
			double here = ULTRA.depth(-999.5, z + 0.5, 0, 0.9, hardness, cover);
			for (int x = -998; x < 1000; x++) {
				double next = ULTRA.depth(x + 0.5, z + 0.5, 0, 0.9, hardness, cover);
				double bend = next - 2 * here + before;
				depth += here * here;
				curvature += bend * bend;
				count++;
				before = here;
				here = next;
			}
		}
		double[] result = {Math.sqrt(depth / count), Math.sqrt(curvature / count)};
		System.out.printf("Hardness %.1f, cover %.1f: depth %.2f, second difference %.3f blocks (%.3f of the depth)%n",
			hardness, cover, result[0], result[1], result[1] / result[0]);
		return result;
	}

	@Test
	void soilAndPlantsRoundTheGroundOff() {
		for (double hardness : new double[]{0, 1}) {
			double[] covered = profile(hardness, 1), bare = profile(hardness, 0);
			assertTrue(covered[1] / covered[0] < 0.7 * bare[1] / bare[0], "smoother under cover at hardness "
				+ hardness);
		}
		// Soft rock under a full cover makes the roundest ground of all.
		double[] round = profile(0, 1);
		for (double[] character : CHARACTERS) {
			double[] other = profile(character[0], character[1]);
			assertTrue(round[1] / round[0] <= other[1] / other[0] + 1e-9, "rounder than " + character[0] + ", "
				+ character[1]);
		}
	}

	@Test
	void hardRockIsCutDeeperAndSharper() {
		double[] soft = profile(0, 1), hard = profile(1, 1);
		assertTrue(hard[0] > 1.2 * soft[0], "deeper in hard rock");
		assertTrue(hard[1] / hard[0] > 1.2 * soft[1] / soft[0], "sharper in hard rock");
	}

	@Test
	void bareSoftRockIsCutIntoDenseRills() {
		double[] soft = profile(0, 0), hard = profile(1, 0);
		assertTrue(soft[1] / soft[0] > 1.1 * hard[1] / hard[0], "finer gullies in bare soft rock");
	}

	@Test
	void gulliesRunDownhill() {
		// Downhill along (dx, dz): the ground changes far more across the slope than down it.
		double[][] downhill = {{1, 0}, {0, 1}, {0.6, -0.8}};
		for (double[] character : CHARACTERS) {
			for (double[] d : downhill) {
				double along = 0, across = 0;
				for (int x = -800; x < 800; x += 5) {
					for (int z = -800; z < 800; z += 7) {
						double px = x + 0.5, pz = z + 0.5, gx = -0.9 * d[0], gz = -0.9 * d[1];
						double here = ULTRA.depth(px, pz, gx, gz, character[0], character[1]);
						double down = ULTRA.depth(px + 3 * d[0], pz + 3 * d[1], gx, gz, character[0], character[1])
							- here;
						double side = ULTRA.depth(px - 3 * d[1], pz + 3 * d[0], gx, gz, character[0], character[1])
							- here;
						along += down * down;
						across += side * side;
					}
				}
				System.out.printf("Hardness %.1f, cover %.1f, downhill (%.1f, %.1f): change across the slope %.1f "
					+ "times that down it%n", character[0], character[1], d[0], d[1], Math.sqrt(across / along));
				assertTrue(Math.sqrt(across / along) > 2, "gullies run downhill along " + d[0] + ", " + d[1]);
			}
		}
	}

	@Test
	void theGroundIsContinuous() {
		for (double[] character : CHARACTERS) {
			for (int x = -300; x < 300; x += 5) {
				for (int z = -300; z < 300; z += 7) {
					double here = ULTRA.depth(x, z, 1.1, 0.4, character[0], character[1]);
					assertEquals(here, ULTRA.depth(x + 0.01, z, 1.1, 0.4, character[0], character[1]), 0.05,
						"at " + x + ", " + z);
					assertEquals(here, ULTRA.depth(x, z + 0.01, 1.1, 0.4, character[0], character[1]), 0.05,
						"at " + x + ", " + z);
				}
			}
		}
	}

	@Test
	void theRockAndTheCoverChangeTheGroundSmoothly() {
		for (int x = -300; x < 300; x += 5) {
			for (int z = -300; z < 300; z += 7) {
				for (double t = 0; t < 1; t += 0.1) {
					double here = ULTRA.depth(x, z, 1.1, 0.4, t, 1 - t);
					assertEquals(here, ULTRA.depth(x, z, 1.1, 0.4, t + 0.01, 1 - t), 0.05, "hardness " + t);
					assertEquals(here, ULTRA.depth(x, z, 1.1, 0.4, t, 1 - t + 0.01), 0.05, "cover " + (1 - t));
				}
			}
		}
	}

	@Test
	void theSeedDecidesTheGullies() {
		Gullies same = new Gullies(0x1234_5678_9ABCL, 1.0), other = new Gullies(42, 1.0);
		int differ = 0;
		for (int x = 0; x < 200; x += 9) {
			for (int z = 0; z < 200; z += 9) {
				double depth = depth(x, z, 0.8, 0.8);
				assertEquals(depth, same.depth(x, z, 0.8, 0.8, MIDDLING, MIDDLING));
				differ += depth != other.depth(x, z, 0.8, 0.8, MIDDLING, MIDDLING) ? 1 : 0;
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
					sink += depth(x + 0.5, z + 0.5, 0.7 + x * 1e-3, 0.3 - z * 1e-3);
					count++;
				}
			}
			System.out.printf("Gullies: %.0f ns per column%n", (System.nanoTime() - start) / (double) count);
		}
		assertTrue(Double.isFinite(sink));
	}
}
