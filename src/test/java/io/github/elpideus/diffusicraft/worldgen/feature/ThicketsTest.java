package io.github.elpideus.diffusicraft.worldgen.feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThicketsTest {
	private static final int SIDE = 512;

	@Test
	void thicketsAndClearingsBothCoverTheFloor() {
		for (long seed = 0; seed < 4; seed++) {
			int thickets = 0, clearings = 0;
			for (int x = -SIDE / 2; x < SIDE / 2; x++) {
				for (int z = -SIDE / 2; z < SIDE / 2; z++) {
					float t = Thickets.at(seed, x, z);
					assertTrue(t >= 0f && t <= 1f, "a thicket of " + t);
					thickets += t > 0.9f ? 1 : 0;
					clearings += t < 0.1f ? 1 : 0;
				}
			}
			// Neither takes the floor: each covers a part of it, more than a tenth and less than two thirds.
			float area = SIDE * SIDE;
			assertTrue(thickets / area > 0.1f && thickets / area < 0.67f, "thickets cover " + thickets / area);
			assertTrue(clearings / area > 0.1f && clearings / area < 0.67f, "clearings cover " + clearings / area);
		}
	}

	@Test
	void aThicketThinsOutGradually() {
		// From one column to the next, across the borders of the noise's cells too.
		for (int x = -100; x < 100; x++) {
			for (int z = -100; z < 100; z++) {
				float t = Thickets.at(7, x, z);
				assertTrue(Math.abs(Thickets.at(7, x + 1, z) - t) < 0.35f, "a step at " + x + ", " + z);
				assertTrue(Math.abs(Thickets.at(7, x, z + 1) - t) < 0.35f, "a step at " + x + ", " + z);
			}
		}
	}

	@Test
	void theThicketsAreTheWorldsOwn() {
		assertEquals(Thickets.at(11, 300, -40), Thickets.at(11, 300, -40));
		int same = 0;
		for (int x = 0; x < 64; x++) {
			same += Thickets.at(11, 13 * x, 5 * x) == Thickets.at(12, 13 * x, 5 * x) ? 1 : 0;
		}
		assertNotEquals(64, same);
	}
}
