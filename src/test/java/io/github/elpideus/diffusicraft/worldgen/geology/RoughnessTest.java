package io.github.elpideus.diffusicraft.worldgen.geology;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoughnessTest {
	/** Roughness at 1:1, a meter per block across and 0.65 m up. */
	private static final Roughness ULTRA = new Roughness(0x1234_5678_9ABCL, 1.0, 0.65);
	/** Relief of plains and of mountains, in meters. */
	private static final double PLAINS = 10, MOUNTAINS = 300;

	/** The mean and the standard deviation of the roughness over a square 8 km wide, and its largest magnitude. */
	private static double[] stats(Roughness roughness, double cover, double relief) {
		double sum = 0, squares = 0, largest = 0;
		int count = 0;
		for (int x = -4000; x < 4000; x += 13) {
			for (int z = -4000; z < 4000; z += 17) {
				double h = roughness.height(x + 0.5, z + 0.5, cover, relief);
				sum += h;
				squares += h * h;
				largest = Math.max(largest, Math.abs(h));
				count++;
			}
		}
		double mean = sum / count;
		return new double[]{mean, Math.sqrt(squares / count - mean * mean), largest};
	}

	@Test
	void theSmallerSizesShowTheModelsOwnDetail() {
		// Knolls 240 m across down to 16 blocks: four octaves at 1:1, three at 1:2, two at 1:4 and one at 1:8.
		for (double metersPerBlock : new double[]{1, 2, 4, 8}) {
			assertTrue(new Roughness(1, metersPerBlock, 1).any(), metersPerBlock + " m per block");
		}
		for (double metersPerBlock : new double[]{16, 32}) {
			Roughness coarse = new Roughness(1, metersPerBlock, 1);
			assertFalse(coarse.any(), metersPerBlock + " m per block");
			assertEquals(0, coarse.height(10.5, 20.5, 0, MOUNTAINS));
		}
		// Scarps along a noise 800 m across, 200 blocks or more: from 1:4 up.
		assertTrue(new Roughness(1, 4, 2).scarps());
		assertFalse(new Roughness(1, 8, 3.5).scarps());
	}

	@Test
	void itLiftsTheGroundAsMuchAsItLowersIt() {
		for (double cover : new double[]{0, 0.5, 1}) {
			for (double relief : new double[]{PLAINS, MOUNTAINS}) {
				double[] stats = stats(ULTRA, cover, relief);
				System.out.printf("Roughness at 1:1, cover %.1f, relief %3.0f m: mean %+.2f, sd %.2f, largest %.1f%n",
					cover, relief, stats[0], stats[1], stats[2]);
				assertTrue(Math.abs(stats[0]) < 0.25 * stats[1], "the mean is near zero");
				assertTrue(stats[1] > 0.5, "the ground is rough");
				assertTrue(stats[2] < 30, "no knoll is a hill");
			}
		}
	}

	@Test
	void ruggedLandIsRougherThanPlains() {
		assertTrue(stats(ULTRA, 0.5, MOUNTAINS)[1] > 1.3 * stats(ULTRA, 0.5, PLAINS)[1]);
	}

	@Test
	void bareGroundHasScarps() {
		// Somewhere the bare ground rises by most of a scarp over the width of a cell of the terrain's interpolation.
		double steepest = 0, covered = 0;
		for (int x = -3000; x < 3000; x += 2) {
			for (int z = -3000; z < 3000; z += 97) {
				steepest = Math.max(steepest, Math.abs(ULTRA.height(x + 4, z, 0, PLAINS) - ULTRA.height(x, z, 0, PLAINS)));
				covered = Math.max(covered, Math.abs(ULTRA.height(x + 4, z, 1, PLAINS) - ULTRA.height(x, z, 1, PLAINS)));
			}
		}
		System.out.printf("Steepest rise over 4 blocks: %.1f blocks bare, %.1f covered%n", steepest, covered);
		assertTrue(steepest > 4, "bare ground has small cliffs");
		assertTrue(covered < steepest, "soil and plants round them off");
	}

	@Test
	void itIsTheSameForTheSameSeedAndNotForAnother() {
		Roughness again = new Roughness(0x1234_5678_9ABCL, 1.0, 0.65), other = new Roughness(99, 1.0, 0.65);
		int differ = 0;
		for (int x = -500; x < 500; x += 37) {
			for (int z = -500; z < 500; z += 41) {
				double h = ULTRA.height(x + 0.5, z + 0.5, 0.3, 50);
				assertEquals(h, again.height(x + 0.5, z + 0.5, 0.3, 50));
				if (h != other.height(x + 0.5, z + 0.5, 0.3, 50)) {
					differ++;
				}
			}
		}
		assertNotEquals(0, differ);
	}
}
